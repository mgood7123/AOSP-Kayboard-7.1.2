package com.android.inputmethod.predictive.engine;

import android.os.Debug;
import android.os.Message;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodSubtype;

import com.android.inputmethod.accessibility.AccessibilityUtils;
import com.android.inputmethod.event.Event;
import com.android.inputmethod.event.InputTransaction;
import com.android.inputmethod.keyboard.KeyboardSwitcher;
import com.android.inputmethod.keyboard.MainKeyboardView;
import com.android.inputmethod.latin.InputAttributes;
import com.android.inputmethod.latin.LastComposedWord;
import com.android.inputmethod.latin.LatinIME;
import com.android.inputmethod.latin.Suggest;
import com.android.inputmethod.latin.SuggestedWords;
import com.android.inputmethod.latin.WordComposer;
import com.android.inputmethod.latin.common.Constants;
import com.android.inputmethod.latin.common.StringUtils;
import com.android.inputmethod.latin.define.DebugFlags;
import com.android.inputmethod.latin.inputlogic.InputLogic;
import com.android.inputmethod.latin.inputlogic.SpaceState;
import com.android.inputmethod.latin.settings.Settings;
import com.android.inputmethod.latin.settings.SettingsValues;
import com.android.inputmethod.latin.touchinputconsumer.GestureConsumer;
import com.android.inputmethod.latin.utils.InputTypeUtils;
import com.android.inputmethod.latin.utils.StatsUtils;

import javax.annotation.Nonnull;

import static com.android.inputmethod.latin.common.Constants.ImeOption.FORCE_ASCII;
import static com.android.inputmethod.latin.common.Constants.ImeOption.NO_MICROPHONE;
import static com.android.inputmethod.latin.common.Constants.ImeOption.NO_MICROPHONE_COMPAT;

public final class engine {

    // suggestion strip updates are triggered by updateStateAfterInputTransaction

    public void handleNonSpecialCharacterEvent(
            final InputLogic inputLogic, final WordComposer wordComposer,
            final SettingsValues settingsValues, @Nonnull final Event currentEvent,
            final InputTransaction inputTransaction, final LatinIME.UIHandler handler){
        final int codePoint = currentEvent.mCodePoint;
        inputLogic.mSpaceState = SpaceState.NONE;
        if (
                StringUtils.newSingleCodePointString(codePoint).equals(" ") ||
                        StringUtils.newSingleCodePointString(codePoint).equals("\n")
        ) {
            // do we require this?
            final int codePoint2 = currentEvent.mCodePoint;
            final boolean wasComposingWord = wordComposer.isComposingWord();
            // We avoid sending spaces in languages without spaces if we were composing.
            final boolean shouldAvoidSendingCode = Constants.CODE_SPACE == codePoint
                    && !settingsValues.mSpacingAndPunctuations.mCurrentLanguageHasSpaces
                    && wasComposingWord;
            if (wordComposer.isCursorFrontOrMiddleOfComposingWord()) {
                // If we are in the middle of a recorrection, we need to commit the recorrection
                // first so that we can insert the separator at the current cursor position.
                // We also need to unlearn the original word that is now being corrected.
                inputLogic.unlearnWord(wordComposer.getTypedWord(), inputTransaction.mSettingsValues,
                        Constants.EVENT_BACKSPACE);
                inputLogic.resetEntireInputState(inputLogic.mConnection.getExpectedSelectionStart(),
                        inputLogic.mConnection.getExpectedSelectionEnd(), true /* clearSuggestionStrip */);
            }
            // isComposingWord() may have changed since we stored wasComposing
            String codePointString = StringUtils.newSingleCodePointString(codePoint);
            if (wordComposer.isComposingWord()) {
                if (settingsValues.mAutoCorrectionEnabledPerUserSettings) {
                    final String separator = shouldAvoidSendingCode ? LastComposedWord.NOT_A_SEPARATOR
                            : StringUtils.newSingleCodePointString(codePoint);
                    inputLogic.commitCurrentAutoCorrection(settingsValues, separator, handler);
                    inputTransaction.setDidAutoCorrect();
                } else {
                    inputLogic.commitTyped(settingsValues, codePointString);
                }
            }

            final boolean swapWeakSpace = inputLogic.tryStripSpaceAndReturnWhetherShouldSwapInstead(currentEvent,
                    inputTransaction);

            final boolean isInsideDoubleQuoteOrAfterDigit = Constants.CODE_DOUBLE_QUOTE == codePoint
                    && inputLogic.mConnection.isInsideDoubleQuoteOrAfterDigit();

            final boolean needsPrecedingSpace;
            if (SpaceState.PHANTOM != inputTransaction.mSpaceState) {
                needsPrecedingSpace = false;
            } else if (Constants.CODE_DOUBLE_QUOTE == codePoint) {
                // Double quotes behave like they are usually preceded by space iff we are
                // not inside a double quote or after a digit.
                needsPrecedingSpace = !isInsideDoubleQuoteOrAfterDigit;
            } else if (settingsValues.mSpacingAndPunctuations.isClusteringSymbol(codePoint)
                    && settingsValues.mSpacingAndPunctuations.isClusteringSymbol(
                    inputLogic.mConnection.getCodePointBeforeCursor())) {
                needsPrecedingSpace = false;
            } else {
                needsPrecedingSpace = settingsValues.isUsuallyPrecededBySpace(codePoint);
            }

            if (needsPrecedingSpace) {
                inputLogic.insertAutomaticSpaceIfOptionsAndTextAllow(settingsValues);
            }

            if (inputLogic.tryPerformDoubleSpacePeriod(currentEvent, inputTransaction)) {
                inputLogic.mSpaceState = SpaceState.DOUBLE;
                inputTransaction.setRequiresUpdateSuggestions();
                StatsUtils.onDoubleSpacePeriod();
            } else if (swapWeakSpace && inputLogic.trySwapSwapperAndSpace(currentEvent, inputTransaction)) {
                inputLogic.mSpaceState = SpaceState.SWAP_PUNCTUATION;
                inputLogic.mSuggestionStripViewAccessor.setNeutralSuggestionStrip();
            } else if (Constants.CODE_SPACE == codePoint) {
                if (!inputLogic.mSuggestedWords.isPunctuationSuggestions()) {
                    inputLogic.mSpaceState = SpaceState.WEAK;
                }

                inputLogic.startDoubleSpacePeriodCountdown(inputTransaction);
                if (wasComposingWord || inputLogic.mSuggestedWords.isEmpty()) {
                    inputTransaction.setRequiresUpdateSuggestions();
                }

                if (!shouldAvoidSendingCode) {
                    inputLogic.sendKeyCodePoint(settingsValues, codePoint);
                }
            } else {
                if ((SpaceState.PHANTOM == inputTransaction.mSpaceState
                        && settingsValues.isUsuallyFollowedBySpace(codePoint))
                        || (Constants.CODE_DOUBLE_QUOTE == codePoint
                        && isInsideDoubleQuoteOrAfterDigit)) {
                    // If we are in phantom space state, and the user presses a separator, we want to
                    // stay in phantom space state so that the next keypress has a chance to add the
                    // space. For example, if I type "Good dat", pick "day" from the suggestion strip
                    // then insert a comma and go on to typing the next word, I want the space to be
                    // inserted automatically before the next word, the same way it is when I don't
                    // input the comma. A double quote behaves like it's usually followed by space if
                    // we're inside a double quote.
                    // The case is a little different if the separator is a space stripper. Such a
                    // separator does not normally need a space on the right (that's the difference
                    // between swappers and strippers), so we should not stay in phantom space state if
                    // the separator is a stripper. Hence the additional test above.
                    inputLogic.mSpaceState = SpaceState.PHANTOM;
                }

                inputLogic.sendKeyCodePoint(settingsValues, codePoint);

                // Set punctuation right away. onUpdateSelection will fire but tests whether it is
                // already displayed or not, so it's okay.
                inputLogic.mSuggestionStripViewAccessor.setNeutralSuggestionStrip();
            }

            inputTransaction.requireShiftUpdate(InputTransaction.SHIFT_UPDATE_NOW);
        } else {
            if (SpaceState.PHANTOM == inputTransaction.mSpaceState) {
                if (wordComposer.isCursorFrontOrMiddleOfComposingWord()) {
                    // If we are in the middle of a recorrection, we need to commit the recorrection
                    // first so that we can insert the character at the current cursor position.
                    // We also need to unlearn the original word that is now being corrected.
                    inputLogic.unlearnWord(wordComposer.getTypedWord(), inputTransaction.mSettingsValues,
                            Constants.EVENT_BACKSPACE);
                    inputLogic.resetEntireInputState(inputLogic.mConnection.getExpectedSelectionStart(),
                            inputLogic.mConnection.getExpectedSelectionEnd(), true /* clearSuggestionStrip */);
                } else {
                    inputLogic.commitTyped(inputTransaction.mSettingsValues, LastComposedWord.NOT_A_SEPARATOR);
                }
            }

            // handleNonSeparatorEvent
//                    inputLogic.handleNonSeparatorEvent(currentEvent, settingsValues, inputTransaction);

            final int codePoint2 = currentEvent.mCodePoint;

            // TODO: refactor this method to stop flipping isComposingWord around all the time, and
            // make it shorter (possibly cut into several pieces). Also factor
            // handleNonSpecialCharacterEvent which has the same name as other handle* methods but is
            // not the same.
            boolean isComposingWord = wordComposer.isComposingWord();

            // TODO: remove isWordConnector() and use isUsuallyFollowedBySpace() instead.
            // See onStartBatchInput() to see how to do it.
            if (SpaceState.PHANTOM == inputTransaction.mSpaceState
                    && !settingsValues.isWordConnector(codePoint2)) {
                if (isComposingWord) {
                    // Sanity check
                    throw new RuntimeException("Should not be composing here");
                }
                inputLogic.insertAutomaticSpaceIfOptionsAndTextAllow(settingsValues);
            }

            if (wordComposer.isCursorFrontOrMiddleOfComposingWord()) {
                // If we are in the middle of a recorrection, we need to commit the recorrection
                // first so that we can insert the character at the current cursor position.
                // We also need to unlearn the original word that is now being corrected.
                inputLogic.unlearnWord(wordComposer.getTypedWord(), inputTransaction.mSettingsValues,
                        Constants.EVENT_BACKSPACE);
                inputLogic.resetEntireInputState(inputLogic.mConnection.getExpectedSelectionStart(),
                        inputLogic.mConnection.getExpectedSelectionEnd(), true);
                // if true then keep composing, otherwise obey above
                isComposingWord = true ? true : false;
            }
            // We want to find out whether to start composing a new word with this character. If so,
            // we need to reset the composing state and switch isComposingWord. The order of the
            // tests is important for good performance.
            // We only start composing if we're not already composing.
            if (!isComposingWord
                    // We only start composing if this is a word code point. Essentially that means it's a
                    // a letter or a word connector.
                    // start composing even if this is not a word code point

                    // set to true to only compose on word code point
                    && (
                    true
                            ? true
                            : settingsValues.isWordCodePoint(codePoint2)
            )

                    // We never go into composing state if suggestions are not requested.
                    && settingsValues.needsToLookupSuggestions() &&
                    // In languages with spaces, we only start composing a word when we are not already
                    // touching a word. In languages without spaces, the above conditions are sufficient.
                    // NOTE: If the InputConnection is slow, we skip the text-after-cursor check since it
                    // can incur a very expensive getTextAfterCursor() lookup, potentially making the
                    // keyboard UI slow and non-responsive.
                    // TODO: Cache the text after the cursor so we don't need to go to the InputConnection
                    // each time. We are already doing this for getTextBeforeCursor().
                    (!settingsValues.mSpacingAndPunctuations.mCurrentLanguageHasSpaces
                            // change to true to start composing even if the cursor is
                            // touching a word
                            || (
                            true
                                    ? true
                                    : !inputLogic.mConnection.isCursorTouchingWord(
                                    settingsValues.mSpacingAndPunctuations,
                                    !inputLogic.mConnection.hasSlowInputConnection() /* checkTextAfter  */
                            )
                    )
                    )
            ) {
                // Reset entirely the composing state anyway, then start composing a new word unless
                // the character is a word connector. The idea here is, word connectors are not
                // separators and they should be treated as normal characters, except in the first
                // position where they should not start composing a word.
                // if true then keep composing regardless if symbol, otherwise obey above
                isComposingWord = true
                        ? true
                        : !settingsValues.mSpacingAndPunctuations.isWordConnector(
                        codePoint2
                );
                // Here we don't need to reset the last composed word. It will be reset
                // when we commit this one, if we ever do; if on the other hand we backspace
                // it entirely and resume suggestions on the previous word, we'd like to still
                // have touch coordinates for it.
                inputLogic.resetComposingState(false /* alsoResetLastComposedWord */);
            }
            if (isComposingWord) {
                wordComposer.applyProcessedEvent(currentEvent);
                // If it's the first letter, make note of auto-caps state
                if (wordComposer.isSingleLetter()) {
                    wordComposer.setCapitalizedModeAtStartComposingTime(inputTransaction.mShiftState);
                }
                inputLogic.setComposingTextInternal(inputLogic.getTextWithUnderline(wordComposer.getTypedWord()), 1);
            } else {
                final boolean swapWeakSpace = inputLogic.tryStripSpaceAndReturnWhetherShouldSwapInstead(currentEvent,
                        inputTransaction);

                if (swapWeakSpace && inputLogic.trySwapSwapperAndSpace(currentEvent, inputTransaction)) {
                    inputLogic.mSpaceState = SpaceState.WEAK;
                } else {
                    inputLogic.sendKeyCodePoint(settingsValues, codePoint2);
                }
            }
            inputTransaction.setRequiresUpdateSuggestions();
        }
    }

    /**
     * Handle a press on the backspace key.
     * @param currentEvent The event to handle.
     * @param inputTransaction The transaction in progress.
     */
    public void handleBackspaceEvent(
            final InputLogic inputLogic, final WordComposer wordComposer,
            final SettingsValues settingsValues, @Nonnull final Event currentEvent,
            final InputTransaction inputTransaction, final int currentKeyboardScriptId
    ) {

        inputLogic.mSpaceState = SpaceState.NONE;
        inputLogic.mDeleteCount++;

        // In many cases after backspace, we need to update the shift state. Normally we need
        // to do this right away to avoid the shift state being out of date in case the user types
        // backspace then some other character very fast. However, in the case of backspace key
        // repeat, this can lead to flashiness when the cursor flies over positions where the
        // shift state should be updated, so if this is a key repeat, we update after a small delay.
        // Then again, even in the case of a key repeat, if the cursor is at start of text, it
        // can't go any further back, so we can update right away even if it's a key repeat.
        final int shiftUpdateKind =
                currentEvent.isKeyRepeat() && inputLogic.mConnection.getExpectedSelectionStart() > 0
                        ? InputTransaction.SHIFT_UPDATE_LATER : InputTransaction.SHIFT_UPDATE_NOW;
        inputTransaction.requireShiftUpdate(shiftUpdateKind);

        if (wordComposer.isCursorFrontOrMiddleOfComposingWord()) {
            // If we are in the middle of a recorrection, we need to commit the recorrection
            // first so that we can remove the character at the current cursor position.
            // We also need to unlearn the original word that is now being corrected.
            inputLogic.unlearnWord(wordComposer.getTypedWord(), inputTransaction.mSettingsValues,
                    Constants.EVENT_BACKSPACE);
            inputLogic.resetEntireInputState(inputLogic.mConnection.getExpectedSelectionStart(),
                    inputLogic.mConnection.getExpectedSelectionEnd(), true /* clearSuggestionStrip */);
            // When we exit this if-clause, wordComposer.isComposingWord() will return false.
        }
        if (wordComposer.isComposingWord()) {
            if (wordComposer.isBatchMode()) {
                final String rejectedSuggestion = wordComposer.getTypedWord();
                wordComposer.reset();
                wordComposer.setRejectedBatchModeSuggestion(rejectedSuggestion);
                if (!TextUtils.isEmpty(rejectedSuggestion)) {
                    inputLogic.unlearnWord(rejectedSuggestion, inputTransaction.mSettingsValues,
                            Constants.EVENT_REJECTION);
                }
                StatsUtils.onBackspaceWordDelete(rejectedSuggestion.length());
            } else {
                wordComposer.applyProcessedEvent(currentEvent);
                StatsUtils.onBackspacePressed(1);
            }
            if (wordComposer.isComposingWord()) {
                inputLogic.setComposingTextInternal(inputLogic.getTextWithUnderline(wordComposer.getTypedWord()), 1);
            } else {
                inputLogic.mConnection.commitText("", 1);
            }
            inputTransaction.setRequiresUpdateSuggestions();
        } else {
            if (inputLogic.mLastComposedWord.canRevertCommit()) {
                final String lastComposedWord = inputLogic.mLastComposedWord.mTypedWord;
                inputLogic.revertCommit(inputTransaction, inputTransaction.mSettingsValues);
                StatsUtils.onRevertAutoCorrect();
                StatsUtils.onWordCommitUserTyped(lastComposedWord, wordComposer.isBatchMode());
                // Restart suggestions when backspacing into a reverted word. This is required for
                // the final corrected word to be learned, as learning only occurs when suggestions
                // are active.
                //
                // Note: restartSuggestionsOnWordTouchedByCursor is already called for normal
                // (non-revert) backspace handling.
                if (inputTransaction.mSettingsValues.isSuggestionsEnabledPerUserSettings()
                        && inputTransaction.mSettingsValues.mSpacingAndPunctuations
                        .mCurrentLanguageHasSpaces
                        && !inputLogic.mConnection.isCursorFollowedByWordCharacter(
                        inputTransaction.mSettingsValues.mSpacingAndPunctuations)) {
                    inputLogic.restartSuggestionsOnWordTouchedByCursor(inputTransaction.mSettingsValues,
                            false /* forStartInput */, currentKeyboardScriptId);
                }
                return;
            }
            if (inputLogic.mEnteredText != null && inputLogic.mConnection.sameAsTextBeforeCursor(inputLogic.mEnteredText)) {
                // Cancel multi-character input: remove the text we just entered.
                // This is triggered on backspace after a key that inputs multiple characters,
                // like the smiley key or the .com key.
                inputLogic.mConnection.deleteTextBeforeCursor(inputLogic.mEnteredText.length());
                StatsUtils.onDeleteMultiCharInput(inputLogic.mEnteredText.length());
                inputLogic.mEnteredText = null;
                // If we have mEnteredText, then we know that mHasUncommittedTypedChars == false.
                // In addition we know that spaceState is false, and that we should not be
                // reverting any autocorrect at this point. So we can safely return.
                return;
            }
            if (SpaceState.DOUBLE == inputTransaction.mSpaceState) {
                inputLogic.cancelDoubleSpacePeriodCountdown();
                if (inputLogic.mConnection.revertDoubleSpacePeriod(
                        inputTransaction.mSettingsValues.mSpacingAndPunctuations)) {
                    // No need to reset mSpaceState, it has already be done (that's why we
                    // receive it as a parameter)
                    inputTransaction.setRequiresUpdateSuggestions();
                    wordComposer.setCapitalizedModeAtStartComposingTime(
                            WordComposer.CAPS_MODE_OFF);
                    StatsUtils.onRevertDoubleSpacePeriod();
                    return;
                }
            } else if (SpaceState.SWAP_PUNCTUATION == inputTransaction.mSpaceState) {
                if (inputLogic.mConnection.revertSwapPunctuation()) {
                    StatsUtils.onRevertSwapPunctuation();
                    // Likewise
                    return;
                }
            }

            boolean hasUnlearnedWordBeingDeleted = false;

            // No cancelling of commit/double space/swap: we have a regular backspace.
            // We should backspace one char and restart suggestion if at the end of a word.
            if (inputLogic.mConnection.hasSelection()) {
                // If there is a selection, remove it.
                // We also need to unlearn the selected text.
                final CharSequence selection = inputLogic.mConnection.getSelectedText(0 /* 0 for no styles */);
                if (!TextUtils.isEmpty(selection)) {
                    inputLogic.unlearnWord(selection.toString(), inputTransaction.mSettingsValues,
                            Constants.EVENT_BACKSPACE);
                    hasUnlearnedWordBeingDeleted = true;
                }
                final int numCharsDeleted = inputLogic.mConnection.getExpectedSelectionEnd()
                        - inputLogic.mConnection.getExpectedSelectionStart();
                inputLogic.mConnection.setSelection(inputLogic.mConnection.getExpectedSelectionEnd(),
                        inputLogic.mConnection.getExpectedSelectionEnd());
                inputLogic.mConnection.deleteTextBeforeCursor(numCharsDeleted);
                StatsUtils.onBackspaceSelectedText(numCharsDeleted);
            } else {
                // There is no selection, just delete one character.
                if (inputTransaction.mSettingsValues.isBeforeJellyBean()
                        || inputTransaction.mSettingsValues.mInputAttributes.isTypeNull()
                        || Constants.NOT_A_CURSOR_POSITION
                        == inputLogic.mConnection.getExpectedSelectionEnd()) {
                    // There are three possible reasons to send a key event: either the field has
                    // type TYPE_NULL, in which case the keyboard should send events, or we are
                    // running in backward compatibility mode, or we don't know the cursor position.
                    // Before Jelly bean, the keyboard would simulate a hardware keyboard event on
                    // pressing enter or delete. This is bad for many reasons (there are race
                    // conditions with commits) but some applications are relying on this behavior
                    // so we continue to support it for older apps, so we retain this behavior if
                    // the app has target SDK < JellyBean.
                    // As for the case where we don't know the cursor position, it can happen
                    // because of bugs in the framework. But the framework should know, so the next
                    // best thing is to leave it to whatever it thinks is best.
                    inputLogic.sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL);
                    int totalDeletedLength = 1;
                    if (inputLogic.mDeleteCount > Constants.DELETE_ACCELERATE_AT) {
                        // If this is an accelerated (i.e., double) deletion, then we need to
                        // consider unlearning here because we may have already reached
                        // the previous word, and will lose it after next deletion.
                        hasUnlearnedWordBeingDeleted |= inputLogic.unlearnWordBeingDeleted(
                                inputTransaction.mSettingsValues, currentKeyboardScriptId);
                        inputLogic.sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL);
                        totalDeletedLength++;
                    }
                    StatsUtils.onBackspacePressed(totalDeletedLength);
                } else {
                    final int codePointBeforeCursor = inputLogic.mConnection.getCodePointBeforeCursor();
                    if (codePointBeforeCursor == Constants.NOT_A_CODE) {
                        // HACK for backward compatibility with broken apps that haven't realized
                        // yet that hardware keyboards are not the only way of inputting text.
                        // Nothing to delete before the cursor. We should not do anything, but many
                        // broken apps expect something to happen in this case so that they can
                        // catch it and have their broken interface react. If you need the keyboard
                        // to do this, you're doing it wrong -- please fix your app.
                        inputLogic.mConnection.deleteTextBeforeCursor(1);
                        // TODO: Add a new StatsUtils method onBackspaceWhenNoText()
                        return;
                    }
                    final int lengthToDelete =
                            Character.isSupplementaryCodePoint(codePointBeforeCursor) ? 2 : 1;
                    inputLogic.mConnection.deleteTextBeforeCursor(lengthToDelete);
                    int totalDeletedLength = lengthToDelete;
                    if (inputLogic.mDeleteCount > Constants.DELETE_ACCELERATE_AT) {
                        // If this is an accelerated (i.e., double) deletion, then we need to
                        // consider unlearning here because we may have already reached
                        // the previous word, and will lose it after next deletion.
                        hasUnlearnedWordBeingDeleted |= inputLogic.unlearnWordBeingDeleted(
                                inputTransaction.mSettingsValues, currentKeyboardScriptId);
                        final int codePointBeforeCursorToDeleteAgain =
                                inputLogic.mConnection.getCodePointBeforeCursor();
                        if (codePointBeforeCursorToDeleteAgain != Constants.NOT_A_CODE) {
                            final int lengthToDeleteAgain = Character.isSupplementaryCodePoint(
                                    codePointBeforeCursorToDeleteAgain) ? 2 : 1;
                            inputLogic.mConnection.deleteTextBeforeCursor(lengthToDeleteAgain);
                            totalDeletedLength += lengthToDeleteAgain;
                        }
                    }
                    StatsUtils.onBackspacePressed(totalDeletedLength);
                }
            }
            if (!hasUnlearnedWordBeingDeleted) {
                // Consider unlearning the word being deleted (if we have not done so already).
                inputLogic.unlearnWordBeingDeleted(
                        inputTransaction.mSettingsValues, currentKeyboardScriptId);
            }
            if (inputLogic.mConnection.hasSlowInputConnection()) {
                inputLogic.mSuggestionStripViewAccessor.setNeutralSuggestionStrip();
            } else if (inputTransaction.mSettingsValues.isSuggestionsEnabledPerUserSettings()
                    && inputTransaction.mSettingsValues.mSpacingAndPunctuations
                    .mCurrentLanguageHasSpaces
                    && !inputLogic.mConnection.isCursorFollowedByWordCharacter(
                    inputTransaction.mSettingsValues.mSpacingAndPunctuations)) {
                inputLogic.restartSuggestionsOnWordTouchedByCursor(inputTransaction.mSettingsValues,
                        false /* forStartInput */, currentKeyboardScriptId);
            }
        }
    }

    public void process(
            final LatinIME latinIME, final boolean isHardwareKey,
            final InputLogic inputLogic, final WordComposer wordComposer,
            final SettingsValues settingsValues, @Nonnull final Event unprocessedEvent,
            final int keyboardShiftMode, final int spaceState,
            final int currentKeyboardScriptId, final LatinIME.UIHandler handler
    ) {
        final Event processedEvent = wordComposer.processEvent(unprocessedEvent);
        final InputTransaction inputTransaction = new InputTransaction(settingsValues,
                processedEvent, SystemClock.uptimeMillis(), spaceState,
                inputLogic.getActualCapsMode(settingsValues, keyboardShiftMode));

        print("onCodeInput");
        inputLogic.mConnection.beginBatchEdit();
        Event currentEvent = processedEvent;
        print("events are handled via a linked list, event = event.mNextEvent");
        while (null != currentEvent) {
            print("while (null != currentEvent)");
            onTextEntry(wordComposer.getTypedWord(), types.MULTI_CHARACTER);
            if (currentEvent.isConsumed()) {
                print("currentEvent.isConsumed()");
                print("letter: " + wordComposer.getTypedWord());
                print("currentEvent key: " + currentEvent.mKeyCode);
                print("currentEvent codePoint: " + StringUtils.newSingleCodePointString(currentEvent.mCodePoint));
//                new engine().onTextEntry(codePointString, engine.types.SINGLE_CHARACTER);
                // A consumed event may have text to commit and an update to the composing state, so
                // we evaluate both. With some combiners, it's possible than an event contains both
                // and we enter both of the following if clauses.
                final CharSequence textToCommit = currentEvent.getTextToCommit();
                if (!TextUtils.isEmpty(textToCommit)) {
                    inputLogic.mConnection.commitText(textToCommit, 1);
                    inputTransaction.setDidAffectContents();
                }
                if (wordComposer.isComposingWord()) {
                    inputLogic.setComposingTextInternal(wordComposer.getTypedWord(), 1);
                    inputTransaction.setDidAffectContents();
                    inputTransaction.setRequiresUpdateSuggestions();
                }
            } else if (currentEvent.isFunctionalKeyEvent()) {
                print("currentEvent.isFunctionalKeyEvent()");
                switch (currentEvent.mKeyCode) {
                    case Constants.CODE_DELETE:
                        handleBackspaceEvent(
                                inputLogic, wordComposer, settingsValues,
                                currentEvent, inputTransaction, currentKeyboardScriptId
                        );
                        // Backspace is a functional key, but it affects the contents of the editor.
                        inputTransaction.setDidAffectContents();
                        break;
                    case Constants.CODE_SHIFT:
                        inputLogic.performRecapitalization(inputTransaction.mSettingsValues);
                        inputTransaction.requireShiftUpdate(InputTransaction.SHIFT_UPDATE_NOW);
                        if (inputLogic.mSuggestedWords.isPrediction()) {
                            inputTransaction.setRequiresUpdateSuggestions();
                        }
                        break;
                    case Constants.CODE_CAPSLOCK:
                        // Note: Changing keyboard to shift lock state is handled in
                        // {@link KeyboardSwitcher#onEvent(Event)}.
                        break;
                    case Constants.CODE_SYMBOL_SHIFT:
                        // Note: Calling back to the keyboard on the symbol Shift key is handled in
                        // {@link #onPressKey(int,int,boolean)} and {@link #onReleaseKey(int,boolean)}.
                        break;
                    case Constants.CODE_SWITCH_ALPHA_SYMBOL:
                        // Note: Calling back to the keyboard on symbol key is handled in
                        // {@link #onPressKey(int,int,boolean)} and {@link #onReleaseKey(int,boolean)}.
                        break;
                    case Constants.CODE_SETTINGS:
                        inputLogic.onSettingsKeyPressed();
                        break;
                    case Constants.CODE_SHORTCUT:
                        // We need to switch to the shortcut IME. This is handled by LatinIME since the
                        // input logic has no business with IME switching.
                        break;
                    case Constants.CODE_ACTION_NEXT:
                        inputLogic.performEditorAction(EditorInfo.IME_ACTION_NEXT);
                        break;
                    case Constants.CODE_ACTION_PREVIOUS:
                        inputLogic.performEditorAction(EditorInfo.IME_ACTION_PREVIOUS);
                        break;
                    case Constants.CODE_LANGUAGE_SWITCH:
                        inputLogic.handleLanguageSwitchKey();
                        break;
                    case Constants.CODE_EMOJI:
                        // Note: Switching emoji keyboard is being handled in
                        // {@link KeyboardState#onEvent(Event,int)}.
                        break;
                    case Constants.CODE_ALPHA_FROM_EMOJI:
                        // Note: Switching back from Emoji keyboard to the main keyboard is being
                        // handled in {@link KeyboardState#onEvent(Event,int)}.
                        break;
                    case Constants.CODE_SHIFT_ENTER:
                        final Event tmpEvent = Event.createSoftwareKeypressEvent(Constants.CODE_ENTER,
                                currentEvent.mKeyCode, currentEvent.mX, currentEvent.mY, currentEvent.isKeyRepeat());
                        handleNonSpecialCharacterEvent(
                                inputLogic, wordComposer, settingsValues,
                                tmpEvent, inputTransaction, handler
                        );
                        // Shift + Enter is treated as a functional key but it results in adding a new
                        // line, so that does affect the contents of the editor.
                        inputTransaction.setDidAffectContents();
                        break;
                    default:
                        throw new RuntimeException("Unknown key code : " + currentEvent.mKeyCode);
                }
            } else {
                print("!(currentEvent.isConsumed() || currentEvent.isFunctionalKeyEvent())");
                print("letter: " + wordComposer.getTypedWord());
                print("currentEvent key: " + currentEvent.mKeyCode);
                print("currentEvent codePoint: " + StringUtils.newSingleCodePointString(currentEvent.mCodePoint));
//                new engine().onTextEntry(wordComposer.getTypedWord(), engine.types.MULTI_CHARACTER);
//                new engine().onTextEntry(codePointString, engine.types.SINGLE_CHARACTER);

                // handleNonFunctionalEvent

                inputTransaction.setDidAffectContents();
                switch (currentEvent.mCodePoint) {
                    case Constants.CODE_ENTER:
                        final EditorInfo editorInfo = inputLogic.getCurrentInputEditorInfo();
                        final int imeOptionsActionId =
                                InputTypeUtils.getImeOptionsActionIdFromEditorInfo(editorInfo);
                        if (InputTypeUtils.IME_ACTION_CUSTOM_LABEL == imeOptionsActionId) {
                            // Either we have an actionLabel and we should performEditorAction with
                            // actionId regardless of its value.
                            inputLogic.performEditorAction(editorInfo.actionId);
                        } else if (EditorInfo.IME_ACTION_NONE != imeOptionsActionId) {
                            // We didn't have an actionLabel, but we had another action to execute.
                            // EditorInfo.IME_ACTION_NONE explicitly means no action. In contrast,
                            // EditorInfo.IME_ACTION_UNSPECIFIED is the default value for an action, so it
                            // means there should be an action and the app didn't bother to set a specific
                            // code for it - presumably it only handles one. It does not have to be treated
                            // in any specific way: anything that is not IME_ACTION_NONE should be sent to
                            // performEditorAction.
                            inputLogic.performEditorAction(imeOptionsActionId);
                        } else {
                            // No action label, and the action from imeOptions is NONE: this is a regular
                            // enter key that should input a carriage return.
                            handleNonSpecialCharacterEvent(
                                    inputLogic, wordComposer, settingsValues,
                                    currentEvent, inputTransaction, handler
                            );
                        }
                        break;
                    default:
                        handleNonSpecialCharacterEvent(
                                inputLogic, wordComposer, settingsValues,
                                currentEvent, inputTransaction, handler
                        );
                        break;
                }
            }
            currentEvent = currentEvent.mNextEvent;
            print("currentEvent = currentEvent.mNextEvent");
            print("currentEvent = " + currentEvent);
        }
        if (!inputTransaction.didAutoCorrect() && processedEvent.mKeyCode != Constants.CODE_SHIFT
                && processedEvent.mKeyCode != Constants.CODE_CAPSLOCK
                && processedEvent.mKeyCode != Constants.CODE_SWITCH_ALPHA_SYMBOL)
            inputLogic.mLastComposedWord.deactivate();
        if (Constants.CODE_DELETE != processedEvent.mKeyCode) {
            inputLogic.mEnteredText = null;
        }
        inputLogic.mConnection.endBatchEdit();
        if (!isHardwareKey) {
            print("updating suggestions");
            latinIME.updateStateAfterInputTransaction(inputTransaction);
        }
    }

    public void processUI(LatinIME latinIME, EditorInfo editorInfo, boolean restarting) {
        latinIME.mDictionaryFacilitator.onStartInput();
        // Switch to the null consumer to handle cases leading to early exit below, for which we
        // also wouldn't be consuming gesture data.
        latinIME.mGestureConsumer = GestureConsumer.NULL_GESTURE_CONSUMER;
        latinIME.mRichImm.refreshSubtypeCaches();
        final KeyboardSwitcher switcher = latinIME.mKeyboardSwitcher;
        switcher.updateKeyboardTheme();
        final MainKeyboardView mainKeyboardView = switcher.getMainKeyboardView();
        // If we are starting input in a different text field from before, we'll have to reload
        // settings, so currentSettingsValues can't be final.
        SettingsValues currentSettingsValues = latinIME.mSettings.getCurrent();

        if (editorInfo == null) {
            Log.e(TAG, "Null EditorInfo in onStartInputView()");
            if (DebugFlags.DEBUG_ENABLED) {
                throw new NullPointerException("Null EditorInfo in onStartInputView()");
            }
            return;
        }
        if (DebugFlags.DEBUG_ENABLED) {
            Log.d(TAG, "onStartInputView: editorInfo:"
                    + String.format("inputType=0x%08x imeOptions=0x%08x",
                    editorInfo.inputType, editorInfo.imeOptions));
            Log.d(TAG, "All caps = "
                    + ((editorInfo.inputType & InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0)
                    + ", sentence caps = "
                    + ((editorInfo.inputType & InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0)
                    + ", word caps = "
                    + ((editorInfo.inputType & InputType.TYPE_TEXT_FLAG_CAP_WORDS) != 0));
        }
        Log.i(TAG, "Starting input. Cursor position = "
                + editorInfo.initialSelStart + "," + editorInfo.initialSelEnd);
        // TODO: Consolidate these checks with {@link InputAttributes}.
        if (InputAttributes.inPrivateImeOptions(null, NO_MICROPHONE_COMPAT, editorInfo)) {
            Log.w(TAG, "Deprecated private IME option specified: " + editorInfo.privateImeOptions);
            Log.w(TAG, "Use " + latinIME.getPackageName() + "." + NO_MICROPHONE + " instead");
        }
        if (InputAttributes.inPrivateImeOptions(latinIME.getPackageName(), FORCE_ASCII, editorInfo)) {
            Log.w(TAG, "Deprecated private IME option specified: " + editorInfo.privateImeOptions);
            Log.w(TAG, "Use EditorInfo.IME_FLAG_FORCE_ASCII flag instead");
        }

        // In landscape mode, this method gets called without the input view being created.
        if (mainKeyboardView == null) {
            return;
        }

        // Update to a gesture consumer with the current editor and IME state.
        latinIME.mGestureConsumer = GestureConsumer.newInstance(editorInfo,
                latinIME.mInputLogic.getPrivateCommandPerformer(),
                latinIME.mRichImm.getCurrentSubtypeLocale(),
                switcher.getKeyboard());

        // Forward this event to the accessibility utilities, if enabled.
        final AccessibilityUtils accessUtils = AccessibilityUtils.getInstance();
        if (accessUtils.isTouchExplorationEnabled()) {
            accessUtils.onStartInputViewInternal(mainKeyboardView, editorInfo, restarting);
        }

        final boolean inputTypeChanged = !currentSettingsValues.isSameInputType(editorInfo);
        final boolean isDifferentTextField = !restarting || inputTypeChanged;

        StatsUtils.onStartInputView(editorInfo.inputType,
                Settings.getInstance().getCurrent().mDisplayOrientation,
                !isDifferentTextField);

        // The EditorInfo might have a flag that affects fullscreen mode.
        // Note: This call should be done by InputMethodService?
        latinIME.updateFullscreenMode();

        // ALERT: settings have not been reloaded and there is a chance they may be stale.
        // In the practice, if it is, we should have gotten onConfigurationChanged so it should
        // be fine, but this is horribly confusing and must be fixed AS SOON AS POSSIBLE.

        // In some cases the input connection has not been reset yet and we can't access it. In
        // this case we will need to call loadKeyboard() later, when it's accessible, so that we
        // can go into the correct mode, so we need to do some housekeeping here.
        final boolean needToCallLoadKeyboardLater;
        final Suggest suggest = latinIME.mInputLogic.mSuggest;
        if (!latinIME.isImeSuppressedByHardwareKeyboard()) {
            // The app calling setText() has the effect of clearing the composing
            // span, so we should reset our state unconditionally, even if restarting is true.
            // We also tell the input logic about the combining rules for the current subtype, so
            // it can adjust its combiners if needed.
            latinIME.mInputLogic.startInput(latinIME.mRichImm.getCombiningRulesExtraValueOfCurrentSubtype(),
                    currentSettingsValues);

            latinIME.resetDictionaryFacilitatorIfNecessary();

            // TODO[IL]: Can the following be moved to InputLogic#startInput?
            if (!latinIME.mInputLogic.mConnection.resetCachesUponCursorMoveAndReturnSuccess(
                    editorInfo.initialSelStart, editorInfo.initialSelEnd,
                    false /* shouldFinishComposition */)) {
                // Sometimes, while rotating, for some reason the framework tells the app we are not
                // connected to it and that means we can't refresh the cache. In this case, schedule
                // a refresh later.
                // We try resetting the caches up to 5 times before giving up.
                latinIME.mHandler.postResetCaches(isDifferentTextField, 5 /* remainingTries */);
                // mLastSelection{Start,End} are reset later in this method, no need to do it here
                needToCallLoadKeyboardLater = true;
            } else {
                // When rotating, and when input is starting again in a field from where the focus
                // didn't move (the keyboard having been closed with the back key),
                // initialSelStart and initialSelEnd sometimes are lying. Make a best effort to
                // work around this bug.
                latinIME.mInputLogic.mConnection.tryFixLyingCursorPosition();
                latinIME.mHandler.postResumeSuggestionsForStartInput(true /* shouldDelay */);
                needToCallLoadKeyboardLater = false;
            }
        } else {
            // If we have a hardware keyboard we don't need to call loadKeyboard later anyway.
            needToCallLoadKeyboardLater = false;
        }

        if (isDifferentTextField ||
                !currentSettingsValues.hasSameOrientation(latinIME.getResources().getConfiguration())) {
            latinIME.loadSettings();
        }
        if (isDifferentTextField) {
            mainKeyboardView.closing();
            currentSettingsValues = latinIME.mSettings.getCurrent();

            if (currentSettingsValues.mAutoCorrectionEnabledPerUserSettings) {
                suggest.setAutoCorrectionThreshold(
                        currentSettingsValues.mAutoCorrectionThreshold);
            }
            suggest.setPlausibilityThreshold(currentSettingsValues.mPlausibilityThreshold);

            switcher.loadKeyboard(editorInfo, currentSettingsValues, latinIME.getCurrentAutoCapsState(),
                    latinIME.getCurrentRecapitalizeState());
            if (needToCallLoadKeyboardLater) {
                // If we need to call loadKeyboard again later, we need to save its state now. The
                // later call will be done in #retryResetCaches.
                switcher.saveKeyboardState();
            }
        } else if (restarting) {
            // TODO: Come up with a more comprehensive way to reset the keyboard layout when
            // a keyboard layout set doesn't get reloaded in this method.
            switcher.resetKeyboardStateToAlphabet(latinIME.getCurrentAutoCapsState(),
                    latinIME.getCurrentRecapitalizeState());
            // In apps like Talk, we come here when the text is sent and the field gets emptied and
            // we need to re-evaluate the shift state, but not the whole layout which would be
            // disruptive.
            // Space state must be updated before calling updateShiftState
            switcher.requestUpdatingShiftState(latinIME.getCurrentAutoCapsState(),
                    latinIME.getCurrentRecapitalizeState());
        }
        // This will set the punctuation suggestions if next word suggestion is off;
        // otherwise it will clear the suggestion strip.
        latinIME.setNeutralSuggestionStrip();

        latinIME.mHandler.cancelUpdateSuggestionStrip();

        mainKeyboardView.setMainDictionaryAvailability(
                latinIME.mDictionaryFacilitator.hasAtLeastOneInitializedMainDictionary());
        mainKeyboardView.setKeyPreviewPopupEnabled(currentSettingsValues.mKeyPreviewPopupOn,
                currentSettingsValues.mKeyPreviewPopupDismissDelay);
        mainKeyboardView.setSlidingKeyInputPreviewEnabled(
                currentSettingsValues.mSlidingKeyInputPreviewEnabled);
        mainKeyboardView.setGestureHandlingEnabledByUser(
                currentSettingsValues.mGestureInputEnabled,
                currentSettingsValues.mGestureTrailEnabled,
                currentSettingsValues.mGestureFloatingPreviewTextEnabled);

        if (latinIME.TRACE) Debug.startMethodTracing("/data/trace/latinime");
    }

    public void handleMessage(LatinIME.UIHandler uiHandler, LatinIME latinIme, Message msg) {
        final KeyboardSwitcher switcher = latinIme.mKeyboardSwitcher;
        switch (msg.what) {
            case LatinIME.UIHandler.MSG_UPDATE_SUGGESTION_STRIP:
                print("handleMessage: MSG_UPDATE_SUGGESTION_STRIP");
                uiHandler.cancelUpdateSuggestionStrip();
                latinIme.mInputLogic.performUpdateSuggestionStripSync(
                        latinIme.mSettings.getCurrent(), msg.arg1 /* inputStyle */);
                break;
            case LatinIME.UIHandler.MSG_UPDATE_SHIFT_STATE:
                print("handleMessage: MSG_UPDATE_SHIFT_STATE");
                switcher.requestUpdatingShiftState(latinIme.getCurrentAutoCapsState(),
                        latinIme.getCurrentRecapitalizeState());
                break;
            case LatinIME.UIHandler.MSG_SHOW_GESTURE_PREVIEW_AND_SUGGESTION_STRIP:
                print("handleMessage: MSG_SHOW_GESTURE_PREVIEW_AND_SUGGESTION_STRIP");
                if (msg.arg1 == LatinIME.UIHandler.ARG1_NOT_GESTURE_INPUT) {
                    final SuggestedWords suggestedWords = (SuggestedWords) msg.obj;
                    latinIme.showSuggestionStrip(suggestedWords);
                } else {
                    latinIme.showGesturePreviewAndSuggestionStrip(
                            (SuggestedWords) msg.obj,
                            msg.arg1 ==
                                    LatinIME.UIHandler.ARG1_DISMISS_GESTURE_FLOATING_PREVIEW_TEXT
                    );
                }
                break;
            case LatinIME.UIHandler.MSG_RESUME_SUGGESTIONS:
                print("handleMessage: MSG_RESUME_SUGGESTIONS");
                latinIme.mInputLogic.restartSuggestionsOnWordTouchedByCursor(
                        latinIme.mSettings.getCurrent(), false /* forStartInput */,
                        latinIme.mKeyboardSwitcher.getCurrentKeyboardScriptId());
                break;
            case LatinIME.UIHandler.MSG_RESUME_SUGGESTIONS_FOR_START_INPUT:
                print("handleMessage: MSG_RESUME_SUGGESTIONS_FOR_START_INPUT");
                latinIme.mInputLogic.restartSuggestionsOnWordTouchedByCursor(
                        latinIme.mSettings.getCurrent(), true /* forStartInput */,
                        latinIme.mKeyboardSwitcher.getCurrentKeyboardScriptId());
                break;
            case LatinIME.UIHandler.MSG_REOPEN_DICTIONARIES:
                print("handleMessage: MSG_REOPEN_DICTIONARIES");
                // We need to re-evaluate the currently composing word in case the script has
                // changed.
                uiHandler.postWaitForDictionaryLoad();
                latinIme.resetDictionaryFacilitatorIfNecessary();
                break;
            case LatinIME.UIHandler.MSG_UPDATE_TAIL_BATCH_INPUT_COMPLETED:
                // this indicates the end of gesture input (swipe typing) typing
                print("handleMessage: MSG_UPDATE_TAIL_BATCH_INPUT_COMPLETED");
                final SuggestedWords suggestedWords = (SuggestedWords) msg.obj;
                latinIme.mInputLogic.onUpdateTailBatchInputCompleted(
                        latinIme.mSettings.getCurrent(),
                        suggestedWords, latinIme.mKeyboardSwitcher);
                latinIme.onTailBatchInputResultShown(suggestedWords);
                break;
            case LatinIME.UIHandler.MSG_RESET_CACHES:
                print("handleMessage: MSG_RESET_CACHES");
                final SettingsValues settingsValues = latinIme.mSettings.getCurrent();
                if (latinIme.mInputLogic.retryResetCachesAndReturnSuccess(
                        msg.arg1 == LatinIME.UIHandler.ARG1_TRUE /* tryResumeSuggestions */,
                        msg.arg2 /* remainingTries */, uiHandler /* handler */)) {
                    // If we were able to reset the caches, then we can reload the keyboard.
                    // Otherwise, we'll do it when we can.
                    latinIme.mKeyboardSwitcher.loadKeyboard(latinIme.getCurrentInputEditorInfo(),
                            settingsValues, latinIme.getCurrentAutoCapsState(),
                            latinIme.getCurrentRecapitalizeState());
                }
                break;
            case LatinIME.UIHandler.MSG_WAIT_FOR_DICTIONARY_LOAD:
                print("handleMessage: MSG_WAIT_FOR_DICTIONARY_LOAD");
                Log.i(TAG, "Timeout waiting for dictionary load");
                break;
            case LatinIME.UIHandler.MSG_DEALLOCATE_MEMORY:
                print("handleMessage: MSG_DEALLOCATE_MEMORY");
                latinIme.deallocateMemory();
                break;
            case LatinIME.UIHandler.MSG_SWITCH_LANGUAGE_AUTOMATICALLY:
                print("handleMessage: MSG_SWITCH_LANGUAGE_AUTOMATICALLY");
                latinIme.switchLanguage((InputMethodSubtype)msg.obj);
                break;
        }
    }

    public static final class types {
        public static final int NO_TYPE = -1;
        public static final int BACKSPACE = 0;
        public static final int SINGLE_CHARACTER = 1;
        public static final int MULTI_CHARACTER = 2;
    }
    public final String TAG = "predictive.engine";

    public final void print(String what) {
        Log.e(TAG, what);
    }
    public final void info(String letter, String type) {
        print("letter: " + letter);
        print("type:   " + type);
    }

    /**
     * this gets called when a letter is typed, this BLOCKS the UI Thread
     * @param letter
     * @param type
     */

    public final void onTextEntry(String letter, int type) {
        print("INPUT letter: " + letter + ", " + "type:   " + type);
    }

    public final void onBackspace(String letter, int type) {
        print("BACKSPACE letter: " + letter + ", " + "type:   " + type);
    }
    public final void onSuggestion(String letter, int type) {
        print("SUGGESTION letter: " + letter + ", " + "type:   " + type);
    }
}
