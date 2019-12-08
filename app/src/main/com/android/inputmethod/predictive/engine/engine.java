package com.android.inputmethod.predictive.engine;

import android.os.Debug;
import android.os.Message;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.text.style.SuggestionSpan;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodSubtype;

import com.android.inputmethod.accessibility.AccessibilityUtils;
import com.android.inputmethod.event.Event;
import com.android.inputmethod.event.InputTransaction;
import com.android.inputmethod.keyboard.Keyboard;
import com.android.inputmethod.keyboard.KeyboardSwitcher;
import com.android.inputmethod.keyboard.MainKeyboardView;
import com.android.inputmethod.latin.AudioAndHapticFeedbackManager;
import com.android.inputmethod.latin.Dictionary;
import com.android.inputmethod.latin.InputAttributes;
import com.android.inputmethod.latin.LastComposedWord;
import com.android.inputmethod.latin.LatinIME;
import com.android.inputmethod.latin.NgramContext;
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
import com.android.inputmethod.latin.settings.SettingsValuesForSuggestion;
import com.android.inputmethod.latin.touchinputconsumer.GestureConsumer;
import com.android.inputmethod.latin.utils.InputTypeUtils;
import com.android.inputmethod.latin.utils.ScriptUtils;
import com.android.inputmethod.latin.utils.SpannableStringUtils;
import com.android.inputmethod.latin.utils.StatsUtils;
import com.android.inputmethod.latin.utils.TextRange;

import java.util.ArrayList;
import java.util.Locale;

import javax.annotation.Nonnull;

import static com.android.inputmethod.latin.common.Constants.ImeOption.FORCE_ASCII;
import static com.android.inputmethod.latin.common.Constants.ImeOption.NO_FLOATING_GESTURE_PREVIEW;
import static com.android.inputmethod.latin.common.Constants.ImeOption.NO_MICROPHONE;
import static com.android.inputmethod.latin.common.Constants.ImeOption.NO_MICROPHONE_COMPAT;

public final class engine {

    static boolean predictionAllowNonWords = true;
    static boolean allowAndroidTextViewEmulation = false;
    static boolean mAutoCorrectionEnabledPerUserSettings = false;
    // suggestion strip updates are triggered by updateStateAfterInputTransaction

    /**
     * Sends a DOWN key event followed by an UP key event to the editor.
     *
     * If possible at all, avoid using this method. It causes all sorts of race conditions with
     * the text view because it goes through a different, asynchronous binder. Also, batch edits
     * are ignored for key events. Use the normal software input methods instead.
     *
     * @param keyCode the key code to send inside the key event.
     */
    static public void sendDownUpKeyEvent(final InputLogic inputLogic, final int keyCode) {
        print("sendDownUpKeyEvent");
        final long eventTime = SystemClock.uptimeMillis();
        inputLogic.mConnection.sendKeyEvent(new KeyEvent(eventTime, eventTime,
                KeyEvent.ACTION_DOWN, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
        inputLogic.mConnection.sendKeyEvent(new KeyEvent(SystemClock.uptimeMillis(), eventTime,
                KeyEvent.ACTION_UP, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
    }

    /**
     * Sends a code point to the editor, using the most appropriate method.
     *
     * Normally we send code points with commitText, but there are some cases (where backward
     * compatibility is a concern for example) where we want to use deprecated methods.
     *
     * @param settingsValues the current values of the settings.
     * @param codePoint the code point to send.
     */
    // TODO: replace these two parameters with an InputTransaction
    static public void sendKeyCodePoint(
            final InputLogic inputLogic, final SettingsValues settingsValues, final int codePoint
    ) {
        print(
                "sendKeyCodePoint: " + codePoint +
                " (as string: " + StringUtils.newSingleCodePointString(codePoint) + ")"
        );
        // TODO: Remove this special handling of digit letters.
        // For backward compatibility. See {@link InputMethodService#sendKeyChar(char)}.
        if (codePoint >= '0' && codePoint <= '9') {
            sendDownUpKeyEvent(inputLogic, codePoint - '0' + KeyEvent.KEYCODE_0);
            return;
        }

        // TODO: we should do this also when the editor has TYPE_NULL
        if (
                Constants.CODE_ENTER == codePoint && (
                        settingsValues.isBeforeJellyBean() ||
                        (inputLogic.getCurrentInputEditorInfo().inputType == InputType.TYPE_NULL)
                )
        ) {
            // Backward compatibility mode. Before Jelly bean, the keyboard would simulate
            // a hardware keyboard event on pressing enter or delete. This is bad for many
            // reasons (there are race conditions with commits) but some applications are
            // relying on this behavior so we continue to support it for older apps.
            sendDownUpKeyEvent(inputLogic, KeyEvent.KEYCODE_ENTER);
        } else {
            inputLogic.mConnection.commitText(StringUtils.newSingleCodePointString(codePoint), 1);
        }
    }

    static public void handleNonSpecialCharacterEvent(
            final InputLogic inputLogic, final SettingsValues settingsValues,
            @Nonnull final Event currentEvent, final InputTransaction inputTransaction,
            final LatinIME.UIHandler handler){
        print("handleNonSpecialCharacterEvent");
        final int codePoint = currentEvent.mCodePoint;
        inputLogic.mSpaceState = SpaceState.NONE;
        if (
                StringUtils.newSingleCodePointString(codePoint).equals(" ") ||
                        StringUtils.newSingleCodePointString(codePoint).equals("\n")
        ) {
            final boolean wasComposingWord = inputLogic.mWordComposer.isComposingWord();
            // We avoid sending spaces in languages without spaces if we were composing.
            final boolean shouldAvoidSendingCode = Constants.CODE_SPACE == codePoint
                    && !settingsValues.mSpacingAndPunctuations.mCurrentLanguageHasSpaces
                    && wasComposingWord;
            if (inputLogic.mWordComposer.isCursorFrontOrMiddleOfComposingWord()) {
                print("is space or new line, commit something");
                // If we are in the middle of a recorrection, we need to commit the recorrection
                // first so that we can insert the separator at the current cursor position.
                // We also need to unlearn the original word that is now being corrected.

                inputLogic.unlearnWord(inputLogic.mWordComposer.getTypedWord(), inputTransaction.mSettingsValues,
                        Constants.EVENT_BACKSPACE);
                inputLogic.resetEntireInputState(inputLogic.mConnection.getExpectedSelectionStart(),
                        inputLogic.mConnection.getExpectedSelectionEnd(), true);
            }
            // isComposingWord() may have changed since we stored wasComposing
            if (inputLogic.mWordComposer.isComposingWord())
                onCommitNormal(
                        inputLogic, inputTransaction, shouldAvoidSendingCode,
                        mAutoCorrectionEnabledPerUserSettings,
                        StringUtils.newSingleCodePointString(codePoint),
                        settingsValues, handler
                );

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
                    onCommitKeyCodePoint(inputLogic, settingsValues, codePoint);
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

                onCommitKeyCodePoint(inputLogic, settingsValues, codePoint);

                // Set punctuation right away. onUpdateSelection will fire but tests whether it is
                // already displayed or not, so it's okay.
                inputLogic.mSuggestionStripViewAccessor.setNeutralSuggestionStrip();
            }

            inputTransaction.requireShiftUpdate(InputTransaction.SHIFT_UPDATE_NOW);
        } else {
            if (SpaceState.PHANTOM == inputTransaction.mSpaceState) {
                if (inputLogic.mWordComposer.isCursorFrontOrMiddleOfComposingWord()) {
                    print("is NOT space or new line, commit on phantom space");
                    // If we are in the middle of a recorrection, we need to commit the recorrection
                    // first so that we can insert the character at the current cursor position.
                    // We also need to unlearn the original word that is now being corrected.
                    inputLogic.unlearnWord(inputLogic.mWordComposer.getTypedWord(), inputTransaction.mSettingsValues,
                            Constants.EVENT_BACKSPACE);
                    inputLogic.resetEntireInputState(inputLogic.mConnection.getExpectedSelectionStart(),
                            inputLogic.mConnection.getExpectedSelectionEnd(), true /* clearSuggestionStrip */);
                } else {
                    print("is NOT space or new line, commit typed");
                    onCommitNormal(
                            inputLogic, inputTransaction,
                            false, false,
                            null, inputTransaction.mSettingsValues,
                            handler
                    );
                }
            }

            // handleNonSeparatorEvent

            final int codePoint2 = currentEvent.mCodePoint;

            // TODO: refactor this method to stop flipping isComposingWord around all the time, and
            //  make it shorter (possibly cut into several pieces). Also factor
            //  handleNonSpecialCharacterEvent which has the same name as other handle* methods but
            //  is not the same.
            boolean isComposingWord = inputLogic.mWordComposer.isComposingWord();

            // TODO: remove isWordConnector() and use isUsuallyFollowedBySpace() instead.
            //  See onStartBatchInput() to see how to do it.
            if (SpaceState.PHANTOM == inputTransaction.mSpaceState
                    && !settingsValues.isWordConnector(codePoint2)) {
                if (isComposingWord) {
                    // Sanity check
                    throw new RuntimeException("Should not be composing here");
                }
                inputLogic.insertAutomaticSpaceIfOptionsAndTextAllow(settingsValues);
            }

            if (inputLogic.mWordComposer.isCursorFrontOrMiddleOfComposingWord()) {
                // If we are in the middle of a recorrection, we need to commit the recorrection
                // first so that we can insert the character at the current cursor position.
                // We also need to unlearn the original word that is now being corrected.
                inputLogic.unlearnWord(inputLogic.mWordComposer.getTypedWord(), inputTransaction.mSettingsValues,
                        Constants.EVENT_BACKSPACE);
                inputLogic.resetEntireInputState(inputLogic.mConnection.getExpectedSelectionStart(),
                        inputLogic.mConnection.getExpectedSelectionEnd(), true);
                // if true then keep composing, otherwise obey above
                isComposingWord = predictionAllowNonWords;
            }
            // We want to find out whether to start composing a new word with this character. If so,
            // we need to reset the composing state and switch isComposingWord. The order of the
            // tests is important for good performance.
            // We only start composing if we're not already composing.
            if (!isComposingWord
                    // We only start composing if this is a word code point. Essentially that means it's a
                    // a letter or a word connector.

                    // start composing even if this is not a word code point

                    // if predictionAllowNonWords is false then only compose on word code point
                    && (predictionAllowNonWords || settingsValues.isWordCodePoint(codePoint2))

                    // We never go into composing state if suggestions are not requested.
                    && settingsValues.needsToLookupSuggestions() &&
                    // In languages with spaces, we only start composing a word when we are not already
                    // touching a word. In languages without spaces, the above conditions are sufficient.
                    // NOTE: If the InputConnection is slow, we skip the text-after-cursor check since it
                    // can incur a very expensive getTextAfterCursor() lookup, potentially making the
                    // keyboard UI slow and non-responsive.
                    // TODO: Cache the text after the cursor so we don't need to go to the InputConnection
                    //  each time. We are already doing this for getTextBeforeCursor().
                    (!settingsValues.mSpacingAndPunctuations.mCurrentLanguageHasSpaces
                            // if predictionAllowNonWords is true then start composing even if
                            // the cursor is touching a word
                            || (
                            predictionAllowNonWords || !inputLogic.mConnection.isCursorTouchingWord(
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
                // if predictionAllowNonWords is true then keep composing regardless if symbol,
                // otherwise obey above
                isComposingWord = predictionAllowNonWords ||
                        !settingsValues.mSpacingAndPunctuations.isWordConnector(codePoint2);
                // Here we don't need to reset the last composed word. It will be reset
                // when we commit this one, if we ever do; if on the other hand we backspace
                // it entirely and resume suggestions on the previous word, we'd like to still
                // have touch coordinates for it.
                inputLogic.resetComposingState(false /* alsoResetLastComposedWord */);
            }
            if (isComposingWord) {
                inputLogic.mWordComposer.applyProcessedEvent(currentEvent);
                // If it's the first letter, make note of auto-caps state
                if (inputLogic.mWordComposer.isSingleLetter()) {
                    inputLogic.mWordComposer.setCapitalizedModeAtStartComposingTime(inputTransaction.mShiftState);
                }
                inputLogic.setComposingTextInternal(inputLogic.getTextWithUnderline(inputLogic.mWordComposer.getTypedWord()), 1);
            } else {
                final boolean swapWeakSpace = inputLogic.tryStripSpaceAndReturnWhetherShouldSwapInstead(currentEvent,
                        inputTransaction);

                if (swapWeakSpace && inputLogic.trySwapSwapperAndSpace(currentEvent, inputTransaction)) {
                    inputLogic.mSpaceState = SpaceState.WEAK;
                } else {
                    onCommitKeyCodePoint(inputLogic, settingsValues, codePoint2);
                }
            }
            inputTransaction.setRequiresUpdateSuggestions();
        }
    }

    static public void onCommitKeyCodePoint(
            final InputLogic inputLogic, final SettingsValues settingsValues, final int codePoint
    ) {
        onCommit(
                null, null, inputLogic,
                null, false, false,
                null, null, 0, settingsValues, null,
                false, false, false,
                true, codePoint
        );
    }

    static public void onCommitCompletion(
            final LatinIME latinIME, final CompletionInfo mApplicationSpecifiedCompletionInfo
    ) {
        onCommit(
                latinIME, mApplicationSpecifiedCompletionInfo, null,
                null, false, false,
                null, null, 0,null,
                null, false, true, false,
                false, 0
        );
    }

    static public void onCommitConnection(
            final InputLogic inputLogic, final InputTransaction inputTransaction,
            final SettingsValues settingsValues, final String whatToCommit
    ) {
        onCommit(
                null, null, inputLogic,
                inputTransaction, false, false,
                whatToCommit, null, 0, settingsValues, null,
                true, false, false,
                false, 0
        );
    }

    static public void onCommitNormal(
            final InputLogic inputLogic, final InputTransaction inputTransaction,
            final boolean shouldAutoCorrect, final boolean shouldAvoidSendingCode,
            final String whatToCommit, final SettingsValues settingsValues,
            final LatinIME.UIHandler handler
    ) {
        onCommit(
                null, null, inputLogic, inputTransaction,
                shouldAvoidSendingCode, mAutoCorrectionEnabledPerUserSettings,
                whatToCommit, null, 0, settingsValues,
                handler, false, false, false,
                false, 0
        );
    }

    static public void onCommitSuggestion(
            final LatinIME latinIME, final SettingsValues settingsValues, final String suggestion,
            final int commitTypeManualPick, final String separatorString) {
        onCommit(
                latinIME, null, null, null,
                false, false, suggestion, separatorString,
                commitTypeManualPick, settingsValues, null,
                false, false, true,
                false, 0
        );
    }

    static public void onCommitSuggestion(
            final InputLogic inputLogic, final SettingsValues settingsValues,
            final String suggestion, final int commitTypeManualPick, final String separatorString) {
        onCommit(
                null, null, inputLogic, null,
                false, false, suggestion, separatorString,
                commitTypeManualPick, settingsValues, null,
                false, true, true,
                false, 0
        );
    }

    static public void onCommit(
            final LatinIME latinIME, final CompletionInfo mApplicationSpecifiedCompletionInfo,
            final InputLogic inputLogic, final InputTransaction inputTransaction,
            final boolean shouldAutoCorrect, final boolean shouldAvoidSendingCode,
            final String whatToCommit, final String separatorString,
            final int commitTypeManualPick, final SettingsValues settingsValues,
            final LatinIME.UIHandler handler, final boolean useConnection,
            final boolean isCompletion, final boolean isSuggestion,
            final boolean sendKeyCodePoint, final int codePoint
    ) {
        // TODO: handle adding a new suggestion on each word commit
        print("onCommit");
        if (sendKeyCodePoint) {
            print("commit is a Key Code Point (non text view origin)");
            // TODO: refactor this to send a string
            sendKeyCodePoint(inputLogic, settingsValues, codePoint);
        } else if (isSuggestion) {
            if (isCompletion) {
                // TODO: can this be merged?
                print("commit is a suggestion from completion");
                inputLogic.commitChosenWord(
                        settingsValues, whatToCommit, commitTypeManualPick, separatorString
                );
            } else {
                print("commit is a suggestion");
                // TODO: ADD TO SUGGESTION LIST
                latinIME.mInputLogic.commitChosenWord(
                        settingsValues, whatToCommit, commitTypeManualPick, separatorString
                );
            }
        } else if (isCompletion) {
            print("commit is a completion");
            latinIME.mInputLogic.mConnection.commitCompletion(mApplicationSpecifiedCompletionInfo);
        }
        else if (shouldAutoCorrect) {
            print("commit is an auto correction");
            final String separator = shouldAvoidSendingCode ? LastComposedWord.NOT_A_SEPARATOR
                    : whatToCommit;
            print("is space or new line, commit AutoCorrection");
            // Complete any pending suggestions query first
            if (handler.hasPendingUpdateSuggestions()) {
                handler.cancelUpdateSuggestionStrip();
                // To know the input style here, we should retrieve the in-flight "update suggestions"
                // message and read its arg1 member here. However, the Handler class does not let
                // us retrieve this message, so we can't do that. But in fact, we notice that
                // we only ever come here when the input style was typing. In the case of batch
                // input, we update the suggestions synchronously when the tail batch comes. Likewise
                // for application-specified completions. As for recorrections, we never auto-correct,
                // so we don't come here either. Hence, the input style is necessarily
                // INPUT_STYLE_TYPING.
                inputLogic.performUpdateSuggestionStripSync(settingsValues, SuggestedWords.INPUT_STYLE_TYPING);
            }
            final SuggestedWords.SuggestedWordInfo autoCorrectionOrNull = inputLogic.mWordComposer.getAutoCorrectionOrNull();
            final String typedWord = inputLogic.mWordComposer.getTypedWord();
            final String stringToCommit = (autoCorrectionOrNull != null)
                    ? autoCorrectionOrNull.mWord : typedWord;
            if (stringToCommit != null) {
                if (TextUtils.isEmpty(typedWord)) {
                    throw new RuntimeException("We have an auto-correction but the typed word "
                            + "is empty? Impossible! I must commit suicide.");
                }
                final boolean isBatchMode = inputLogic.mWordComposer.isBatchMode();
                onCommitSuggestion(inputLogic, settingsValues, stringToCommit,
                        LastComposedWord.COMMIT_TYPE_DECIDED_WORD, separator);
                if (!typedWord.equals(stringToCommit)) {
                    // This will make the correction flash for a short while as a visual clue
                    // to the user that auto-correction happened. It has no other effect; in particular
                    // note that this won't affect the text inside the text field AT ALL: it only makes
                    // the segment of text starting at the supplied index and running for the length
                    // of the auto-correction flash. At this moment, the "typedWord" argument is
                    // ignored by TextView.
                    inputLogic.mConnection.commitCorrection(new CorrectionInfo(
                            inputLogic.mConnection.getExpectedSelectionEnd() - stringToCommit.length(),
                            typedWord, stringToCommit));
                    String prevWordsContext = (autoCorrectionOrNull != null)
                            ? autoCorrectionOrNull.mPrevWordsContext
                            : "";
                    StatsUtils.onAutoCorrection(typedWord, stringToCommit, isBatchMode,
                            inputLogic.mDictionaryFacilitator, prevWordsContext);
                    StatsUtils.onWordCommitAutoCorrect(stringToCommit, isBatchMode);
                } else {
                    StatsUtils.onWordCommitUserTyped(stringToCommit, isBatchMode);
                }
            }
            inputTransaction.setDidAutoCorrect();
        } else {
            if (useConnection) {
                print("commit is from a consumed event");
                inputLogic.mConnection.commitText(whatToCommit, 1);
                inputTransaction.setDidAffectContents();
            } else {
                print("commit is normal");
                print("is space or new line, commit typed");
                // TODO: ADD TO SUGGESTION LIST
                inputLogic.commitTyped(settingsValues, whatToCommit);
            }
        }
    }

    /**
     * Handle a press on the backspace key.
     * @param currentEvent The event to handle.
     * @param inputTransaction The transaction in progress.
     */
    static public void handleBackspaceEvent(
            final InputLogic inputLogic, final SettingsValues settingsValues,
            @Nonnull final Event currentEvent, final InputTransaction inputTransaction,
            final int currentKeyboardScriptId
    ) {
        print("handleBackspaceEvent");
        // TODO: handle removing suggestions on backspace?
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

        if (inputLogic.mWordComposer.isCursorFrontOrMiddleOfComposingWord()) {
            // If we are in the middle of a recorrection, we need to commit the recorrection
            // first so that we can remove the character at the current cursor position.
            // We also need to unlearn the original word that is now being corrected.
            inputLogic.unlearnWord(inputLogic.mWordComposer.getTypedWord(), inputTransaction.mSettingsValues,
                    Constants.EVENT_BACKSPACE);
            inputLogic.resetEntireInputState(inputLogic.mConnection.getExpectedSelectionStart(),
                    inputLogic.mConnection.getExpectedSelectionEnd(), true /* clearSuggestionStrip */);
            // When we exit this if-clause, inputLogic.mWordComposer.isComposingWord() will return false.
        }
        if (inputLogic.mWordComposer.isComposingWord()) {
            if (inputLogic.mWordComposer.isBatchMode()) {
                final String rejectedSuggestion = inputLogic.mWordComposer.getTypedWord();
                inputLogic.mWordComposer.reset();
                inputLogic.mWordComposer.setRejectedBatchModeSuggestion(rejectedSuggestion);
                if (!TextUtils.isEmpty(rejectedSuggestion)) {
                    inputLogic.unlearnWord(rejectedSuggestion, inputTransaction.mSettingsValues,
                            Constants.EVENT_REJECTION);
                }
                StatsUtils.onBackspaceWordDelete(rejectedSuggestion.length());
            } else {
                inputLogic.mWordComposer.applyProcessedEvent(currentEvent);
                StatsUtils.onBackspacePressed(1);
            }
            if (inputLogic.mWordComposer.isComposingWord()) {
                inputLogic.setComposingTextInternal(inputLogic.getTextWithUnderline(inputLogic.mWordComposer.getTypedWord()), 1);
            } else {
                inputLogic.mConnection.commitText("", 1);
            }
            inputTransaction.setRequiresUpdateSuggestions();
        } else {
            if (inputLogic.mLastComposedWord.canRevertCommit()) {
                final String lastComposedWord = inputLogic.mLastComposedWord.mTypedWord;
                inputLogic.revertCommit(inputTransaction, inputTransaction.mSettingsValues);
                StatsUtils.onRevertAutoCorrect();
                StatsUtils.onWordCommitUserTyped(lastComposedWord, inputLogic.mWordComposer.isBatchMode());
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
                    inputLogic.mWordComposer.setCapitalizedModeAtStartComposingTime(
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

    static public void process(
            final LatinIME latinIME, final boolean isHardwareKey,
            final InputLogic inputLogic, final SettingsValues settingsValues,
            @Nonnull final Event unprocessedEvent, final int keyboardShiftMode, final int spaceState,
            final int currentKeyboardScriptId, final LatinIME.UIHandler handler
    ) {
        final Event processedEvent = inputLogic.mWordComposer.processEvent(unprocessedEvent);
        final InputTransaction inputTransaction = new InputTransaction(settingsValues,
                processedEvent, SystemClock.uptimeMillis(), spaceState,
                inputLogic.getActualCapsMode(settingsValues, keyboardShiftMode));

        print("onCodeInput");
        inputLogic.mConnection.beginBatchEdit();
        Event currentEvent = processedEvent;
        print("events are handled via a linked list, event = event.mNextEvent");
        while (null != currentEvent) {
            print("while (null != currentEvent)");
            if (currentEvent.isConsumed()) {
                print("currentEvent.isConsumed()");
                print("letter: " + inputLogic.mWordComposer.getTypedWord());
                print("currentEvent key                : " + currentEvent.mKeyCode);
                print("currentEvent codePoint          : " + currentEvent.mCodePoint);
                print("currentEvent codePoint as string: " +
                        StringUtils.newSingleCodePointString(currentEvent.mCodePoint));
                // A consumed event may have text to commit and an update to the composing state, so
                // we evaluate both. With some combiners, it's possible than an event contains both
                // and we enter both of the following if clauses.
                final CharSequence textToCommit = currentEvent.getTextToCommit();
                if (!TextUtils.isEmpty(textToCommit)) {
                    onCommitConnection(
                            inputLogic, inputTransaction, settingsValues, textToCommit.toString()
                    );
                }
                if (inputLogic.mWordComposer.isComposingWord()) {
                    inputLogic.setComposingTextInternal(inputLogic.mWordComposer.getTypedWord(), 1);
                    inputTransaction.setDidAffectContents();
                    inputTransaction.setRequiresUpdateSuggestions();
                }
            } else if (currentEvent.isFunctionalKeyEvent()) {
                print("currentEvent.isFunctionalKeyEvent()");
                print("currentEvent key                : " + currentEvent.mKeyCode);
                print("currentEvent codePoint          : " + currentEvent.mCodePoint);
                print("currentEvent codePoint as string: " +
                        StringUtils.newSingleCodePointString(currentEvent.mCodePoint));
                switch (currentEvent.mKeyCode) {
                    case Constants.CODE_DELETE:
                        handleBackspaceEvent(
                                inputLogic, settingsValues, currentEvent, inputTransaction,
                                currentKeyboardScriptId
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
                                inputLogic, settingsValues, tmpEvent, inputTransaction, handler
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
                print("letter: " + inputLogic.mWordComposer.getTypedWord());
                print("currentEvent key                : " + currentEvent.mKeyCode);
                print("currentEvent codePoint          : " + currentEvent.mCodePoint);
                print("currentEvent codePoint as string: " +
                        StringUtils.newSingleCodePointString(currentEvent.mCodePoint));
//                engine.onTextEntry(inputLogic.mWordComposer.getTypedWord(), engine.types.MULTI_CHARACTER);
//                engine.onTextEntry(codePointString, engine.types.SINGLE_CHARACTER);

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
                                    inputLogic, settingsValues, currentEvent, inputTransaction,
                                    handler
                            );
                        }
                        break;
                    default:
                        handleNonSpecialCharacterEvent(
                                inputLogic, settingsValues, currentEvent, inputTransaction, handler
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
            switch (inputTransaction.getRequiredShiftUpdate()) {
                case InputTransaction.SHIFT_UPDATE_LATER:
                    latinIME.mHandler.postUpdateShiftState();
                    break;
                case InputTransaction.SHIFT_UPDATE_NOW:
                    latinIME.mKeyboardSwitcher.requestUpdatingShiftState(latinIME.getCurrentAutoCapsState(),
                            latinIME.getCurrentRecapitalizeState());
                    break;
                default: // SHIFT_NO_UPDATE
            }
            if (inputTransaction.requiresUpdateSuggestions()) {
                final int inputStyle;
                if (inputTransaction.mEvent.isSuggestionStripPress()) {
                    // Suggestion strip press: no input.
                    inputStyle = SuggestedWords.INPUT_STYLE_NONE;
                } else if (inputTransaction.mEvent.isGesture()) {
                    inputStyle = SuggestedWords.INPUT_STYLE_TAIL_BATCH;
                } else {
                    inputStyle = SuggestedWords.INPUT_STYLE_TYPING;
                }
                latinIME.mHandler.postUpdateSuggestionStrip(inputStyle);
            }
            if (inputTransaction.didAffectContents()) {
                latinIME.mSubtypeState.setCurrentSubtypeHasBeenUsed();
            }

            latinIME.updateStateAfterInputTransaction(inputTransaction);
        }
    }

    static public void processUI(LatinIME latinIME, EditorInfo editorInfo, boolean restarting) {
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
            final Locale locale = latinIME.mRichImm.getCurrentSubtypeLocale();
            final InputAttributes inputAttributes = new InputAttributes();
            // manual initialization
            // TODO: mmigrate this to global InitAttributes class
            inputAttributes.mEditorInfo = editorInfo;
            inputAttributes.mPackageNameForPrivateImeOptions = latinIME.getPackageName();
            inputAttributes.mTargetApplicationPackageName = null != editorInfo ? editorInfo.packageName : null;
            final int inputType = null != editorInfo ? editorInfo.inputType : 0;
            final int inputClass = inputType & InputType.TYPE_MASK_CLASS;
            inputAttributes.mInputType = inputType;
            inputAttributes.mIsPasswordField = InputTypeUtils.isPasswordInputType(inputType)
                    || InputTypeUtils.isVisiblePasswordInputType(inputType);
            inputAttributes.AndroidTextViewEmulation = false;
            boolean continueInitialization = false;
            if (inputClass != InputType.TYPE_CLASS_TEXT) {
                // If we are not looking at a TYPE_CLASS_TEXT field, the following strange
                // cases may arise, so we do a couple sanity checks for them. If it's a
                // TYPE_CLASS_TEXT field, these special cases cannot happen, by construction
                // of the flags.
                if (null == editorInfo) {
                    Log.w(TAG, "No editor info for this field. Bug?");
                } else if (InputType.TYPE_NULL == inputType) {
                    // TODO: We should honor TYPE_NULL specification.
                    Log.i(TAG, "InputType.TYPE_NULL is specified");
                    inputAttributes.AndroidTextViewEmulation = allowAndroidTextViewEmulation;
                } else if (inputClass == 0) {
                    // TODO: is this check still necessary?
                    Log.w(TAG, String.format("Unexpected input class: inputType=0x%08x"
                            + " imeOptions=0x%08x", inputType, editorInfo.imeOptions));
                }
                if (inputAttributes.AndroidTextViewEmulation)
                    continueInitialization = true;
                else {
                    inputAttributes.mShouldShowSuggestions = false;
                    inputAttributes.mInputTypeNoAutoCorrect = false;
                    inputAttributes.mApplicationSpecifiedCompletionOn = false;
                    inputAttributes.mShouldInsertSpacesAutomatically = false;
                    inputAttributes.mShouldShowVoiceInputKey = false;
                    inputAttributes.mDisableGestureFloatingPreviewText = false;
                    inputAttributes.mIsGeneralTextInput = false;
                }
            }
            if (inputClass == InputType.TYPE_CLASS_TEXT || continueInitialization) {
                // inputClass == InputType.TYPE_CLASS_TEXT
                final int variation = inputType & InputType.TYPE_MASK_VARIATION;
                final boolean flagNoSuggestions =
                        0 != (inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                final boolean flagMultiLine =
                        0 != (inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                final boolean flagAutoCorrect =
                        0 != (inputType & InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
                final boolean flagAutoComplete =
                        0 != (inputType & InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE);

                // TODO: Have a helper method in InputTypeUtils

                // Make sure that passwords are not displayed in {@link SuggestionStripView}.
                final boolean shouldSuppressSuggestions = inputAttributes.mIsPasswordField
                        || InputTypeUtils.isEmailVariation(variation)
                        || InputType.TYPE_TEXT_VARIATION_URI == variation
                        || InputType.TYPE_TEXT_VARIATION_FILTER == variation
                        || flagNoSuggestions
                        || flagAutoComplete;
                inputAttributes.mShouldShowSuggestions = !shouldSuppressSuggestions;

                inputAttributes.mShouldInsertSpacesAutomatically = InputTypeUtils.isAutoSpaceFriendlyType(inputType);

                final boolean noMicrophone = inputAttributes.mIsPasswordField
                        || InputTypeUtils.isEmailVariation(variation)
                        || InputType.TYPE_TEXT_VARIATION_URI == variation
                        || inputAttributes.hasNoMicrophoneKeyOption();
                inputAttributes.mShouldShowVoiceInputKey = !noMicrophone;

                inputAttributes.mDisableGestureFloatingPreviewText = InputAttributes.inPrivateImeOptions(
                        inputAttributes.mPackageNameForPrivateImeOptions, NO_FLOATING_GESTURE_PREVIEW, editorInfo);

                // If it's a browser edit field and auto correct is not ON explicitly, then
                // disable auto correction, but keep suggestions on.
                // If NO_SUGGESTIONS is set, don't do prediction.
                // If it's not multiline and the autoCorrect flag is not set, then don't correct
                inputAttributes.mInputTypeNoAutoCorrect =
                        (variation == InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT && !flagAutoCorrect)
                                || flagNoSuggestions
                                || (!flagAutoCorrect && !flagMultiLine);

                inputAttributes.mApplicationSpecifiedCompletionOn = flagAutoComplete && latinIME.isFullscreenMode();

                // If we come here, inputClass is always TYPE_CLASS_TEXT
                inputAttributes.mIsGeneralTextInput = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS != variation
                        && InputType.TYPE_TEXT_VARIATION_PASSWORD != variation
                        && InputType.TYPE_TEXT_VARIATION_PHONETIC != variation
                        && InputType.TYPE_TEXT_VARIATION_URI != variation
                        && InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD != variation
                        && InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS != variation
                        && InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD != variation;
            }
            // end of manual initialization
            latinIME.mSettings.loadSettings(latinIME, locale, inputAttributes);
            AudioAndHapticFeedbackManager.getInstance().onSettingsChanged(currentSettingsValues);
            // This method is called on startup and language switch, before the new layout has
            // been displayed. Opening dictionaries never affects responsivity as dictionaries are
            // asynchronously loaded.
            if (!latinIME.mHandler.hasPendingReopenDictionaries()) {
                latinIME.resetDictionaryFacilitator(locale);
            }
            latinIME.refreshPersonalizationDictionarySession(currentSettingsValues);
            latinIME.resetDictionaryFacilitatorIfNecessary();
            latinIME.mStatsUtilsManager.onLoadSettings(latinIME /* context */, currentSettingsValues);
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
            //  a keyboard layout set doesn't get reloaded in this method.
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

    /**
     * Check if the cursor is touching a word. If so, restart suggestions on this word, else
     * do nothing.
     *
     * @param settingsValues the current values of the settings.
     * @param forStartInput whether we're doing this in answer to starting the input (as opposed
     *   to a cursor move, for example). In ICS, there is a platform bug that we need to work
     *   around only when we come here at input start time.
     */
    static public void restartSuggestionsOnWordTouchedByCursor(
            final InputLogic inputLogic, final SettingsValues settingsValues,
            final boolean forStartInput, final int currentKeyboardScriptId) {
        // TODO: should we handle spelling correction suggestions?
        print("restartSuggestionsOnWordTouchedByCursor");
        // HACK: We may want to special-case some apps that exhibit bad behavior in case of
        // recorrection. This is a temporary, stopgap measure that will be removed later.
        // TODO: remove this.
        if (settingsValues.isBrokenByRecorrection()
                // Recorrection is not supported in languages without spaces because we don't know
                // how to segment them yet.
                || !settingsValues.mSpacingAndPunctuations.mCurrentLanguageHasSpaces
                // If no suggestions are requested, don't try restarting suggestions.
                || !settingsValues.needsToLookupSuggestions()
                // If we are currently in a batch input, we must not resume suggestions, or the result
                // of the batch input will replace the new composition. This may happen in the corner case
                // that the app moves the cursor on its own accord during a batch input.
                || inputLogic.mInputLogicHandler.isInBatchInput()
                // If the cursor is not touching a word, or if there is a selection, return right away.
                || inputLogic.mConnection.hasSelection()
                // If we don't know the cursor location, return.
                || inputLogic.mConnection.getExpectedSelectionStart() < 0) {
            inputLogic.mSuggestionStripViewAccessor.setNeutralSuggestionStrip();
            return;
        }
        final int expectedCursorPosition = inputLogic.mConnection.getExpectedSelectionStart();

        // if true, suggestions obey isCursorTouchingWord
        if (false)
            if (!inputLogic.mConnection.isCursorTouchingWord(settingsValues.mSpacingAndPunctuations,
                true /* checkTextAfter */)) {
            // Show predictions.
            inputLogic.mWordComposer.setCapitalizedModeAtStartComposingTime(WordComposer.CAPS_MODE_OFF);
            inputLogic.mLatinIME.mHandler.postUpdateSuggestionStrip(SuggestedWords.INPUT_STYLE_RECORRECTION);
            return;
        }

        TextRange range = null;

        inputLogic.mConnection.mIC = inputLogic.mConnection.mParent.getCurrentInputConnection();
        if (inputLogic.mConnection.isConnected()) {
            final CharSequence before = inputLogic.mConnection.getTextBeforeCursorAndDetectLaggyConnection(
                    inputLogic.mConnection.OPERATION_GET_WORD_RANGE_AT_CURSOR,
                    inputLogic.mConnection.SLOW_INPUT_CONNECTION_ON_PARTIAL_RELOAD_MS,
                    inputLogic.mConnection.NUM_CHARS_TO_GET_BEFORE_CURSOR,
                    InputConnection.GET_TEXT_WITH_STYLES);
            final CharSequence after = inputLogic.mConnection.getTextAfterCursorAndDetectLaggyConnection(
                    inputLogic.mConnection.OPERATION_GET_WORD_RANGE_AT_CURSOR,
                    inputLogic.mConnection.SLOW_INPUT_CONNECTION_ON_PARTIAL_RELOAD_MS,
                    inputLogic.mConnection.NUM_CHARS_TO_GET_AFTER_CURSOR,
                    InputConnection.GET_TEXT_WITH_STYLES);
            if (before != null && after != null) {
                // Going backward, find the first breaking point (separator)
                int startIndexInBefore = before.length();
                while (startIndexInBefore > 0) {
                    final int codePoint = Character.codePointBefore(before, startIndexInBefore);
                    if (!predictionAllowNonWords) {
                        if (!(settingsValues.mSpacingAndPunctuations.isWordConnector(codePoint)
                                // Otherwise, it's part of composition if it's part of script and not a separator.
                                || (!settingsValues.mSpacingAndPunctuations.isWordSeparator(codePoint)
                                && ScriptUtils.isLetterPartOfScript(codePoint, currentKeyboardScriptId)))) {
                            break;
                        }
                    } else {
                        String x = StringUtils.newSingleCodePointString(codePoint);
                        if(x.equals(" ") || x.equals("\n")) break;
                    }
                    --startIndexInBefore;
                    if (Character.isSupplementaryCodePoint(codePoint)) {
                        --startIndexInBefore;
                    }
                }

                // Find last word separator after the cursor
                int endIndexInAfter = -1;
                while (++endIndexInAfter < after.length()) {
                    final int codePoint = Character.codePointAt(after, endIndexInAfter);
                    // We always consider word connectors part of compositions.
                    if (!predictionAllowNonWords) {
                        if (!(settingsValues.mSpacingAndPunctuations.isWordConnector(codePoint)
                                // Otherwise, it's part of composition if it's part of script and not a separator.
                                || (!settingsValues.mSpacingAndPunctuations.isWordSeparator(codePoint)
                                && ScriptUtils.isLetterPartOfScript(codePoint, currentKeyboardScriptId)))) {
                            break;
                        }
                    } else {
                        String x = StringUtils.newSingleCodePointString(codePoint);
                        if(x.equals(" ") || x.equals("\n")) break;
                    }
                    if (Character.isSupplementaryCodePoint(codePoint)) {
                        ++endIndexInAfter;
                    }
                }
                final boolean hasUrlSpans =
                        SpannableStringUtils.hasUrlSpans(before, startIndexInBefore, before.length())
                                || SpannableStringUtils.hasUrlSpans(after, 0, endIndexInAfter);
                // We don't use TextUtils#concat because it copies all spans without respect to their
                // nature. If the text includes a PARAGRAPH span and it has been split, then
                // TextUtils#concat will crash when it tries to concat both sides of it.
                range = new TextRange(
                        SpannableStringUtils.concatWithNonParagraphSuggestionSpansOnly(before, after),
                        startIndexInBefore, before.length() + endIndexInAfter, before.length(),
                        hasUrlSpans);
            }
        }

//        final TextRange range = inputLogic.mConnection.getWordRangeAtCursor(
//                settingsValues.mSpacingAndPunctuations, currentKeyboardScriptId);
        if (null == range) return; // Happens if we don't have an input connection at all
        if (range.length() <= 0) {
            // Race condition, or touching a word in a non-supported script.
            inputLogic.mLatinIME.setNeutralSuggestionStrip();
            return;
        }
        // If for some strange reason (editor bug or so) we measure the text before the cursor as
        // longer than what the entire text is supposed to be, the safe thing to do is bail out.
        if (range.mHasUrlSpans) return; // If there are links, we don't resume suggestions. Making
        // edits to a linkified text through batch commands would ruin the URL spans, and unless
        // we take very complicated steps to preserve the whole link, we can't do things right so
        // we just do not resume because it's safer.
        final int numberOfCharsInWordBeforeCursor = range.getNumberOfCharsInWordBeforeCursor();
        if (numberOfCharsInWordBeforeCursor > expectedCursorPosition) return;
        final ArrayList<SuggestedWords.SuggestedWordInfo> suggestions = new ArrayList<>();
        final String typedWordString = range.mWord.toString();
        final SuggestedWords.SuggestedWordInfo typedWordInfo = new SuggestedWords.SuggestedWordInfo(typedWordString,
                "" /* prevWordsContext */, SuggestedWords.MAX_SUGGESTIONS + 1,
                SuggestedWords.SuggestedWordInfo.KIND_TYPED, Dictionary.DICTIONARY_USER_TYPED,
                SuggestedWords.SuggestedWordInfo.NOT_AN_INDEX /* indexOfTouchPointOfSecondWord */,
                SuggestedWords.SuggestedWordInfo.NOT_A_CONFIDENCE /* autoCommitFirstWordConfidence */);
        suggestions.add(typedWordInfo);
        if (!predictionAllowNonWords) if (!inputLogic.isResumableWord(settingsValues, typedWordString)) {
            inputLogic.mSuggestionStripViewAccessor.setNeutralSuggestionStrip();
            return;
        }
        int i = 0;
        for (final SuggestionSpan span : range.getSuggestionSpansAtWord()) {
            for (final String s : span.getSuggestions()) {
                ++i;
                if (!TextUtils.equals(s, typedWordString)) {
                    suggestions.add(new SuggestedWords.SuggestedWordInfo(s,
                            "" /* prevWordsContext */, SuggestedWords.MAX_SUGGESTIONS - i,
                            SuggestedWords.SuggestedWordInfo.KIND_RESUMED, Dictionary.DICTIONARY_RESUMED,
                            SuggestedWords.SuggestedWordInfo.NOT_AN_INDEX /* indexOfTouchPointOfSecondWord */,
                            SuggestedWords.SuggestedWordInfo.NOT_A_CONFIDENCE
                            /* autoCommitFirstWordConfidence */));
                }
            }
        }
        final int[] codePoints = StringUtils.toCodePointArray(typedWordString);
        inputLogic.mWordComposer.setComposingWord(codePoints,
                inputLogic.mLatinIME.getCoordinatesForCurrentKeyboard(codePoints));
        inputLogic.mWordComposer.setCursorPositionWithinWord(
                typedWordString.codePointCount(0, numberOfCharsInWordBeforeCursor));
        if (forStartInput) {
            inputLogic.mConnection.maybeMoveTheCursorAroundAndRestoreToWorkaroundABug();
        }
        inputLogic.mConnection.setComposingRegion(expectedCursorPosition - numberOfCharsInWordBeforeCursor,
                expectedCursorPosition + range.getNumberOfCharsInWordAfterCursor());
        if (suggestions.size() <= 1) {
            // If there weren't any suggestion spans on this word, suggestions#size() will be 1
            // if shouldIncludeResumedWordInSuggestions is true, 0 otherwise. In this case, we
            // have no useful suggestions, so we will try to compute some for it instead.
            inputLogic.mInputLogicHandler.getSuggestedWords(Suggest.SESSION_ID_TYPING,
                    SuggestedWords.NOT_A_SEQUENCE_NUMBER, new Suggest.OnGetSuggestedWordsCallback() {
                        @Override
                        public void onGetSuggestedWords(final SuggestedWords suggestedWords) {
                            inputLogic.doShowSuggestionsAndClearAutoCorrectionIndicator(suggestedWords);
                        }});
        } else {
            // We found suggestion spans in the word. We'll create the SuggestedWords out of
            // them, and make willAutoCorrect false. We make typedWordValid false, because the
            // color of the word in the suggestion strip changes according to this parameter,
            // and false gives the correct color.
            final SuggestedWords suggestedWords = new SuggestedWords(suggestions,
                    null /* rawSuggestions */, typedWordInfo, false /* typedWordValid */,
                    false /* willAutoCorrect */, false /* isObsoleteSuggestions */,
                    SuggestedWords.INPUT_STYLE_RECORRECTION, SuggestedWords.NOT_A_SEQUENCE_NUMBER);
            inputLogic.doShowSuggestionsAndClearAutoCorrectionIndicator(suggestedWords);
        }
    }


    static public void handleMessage(LatinIME.UIHandler uiHandler, LatinIME latinIME, Message msg) {
        final KeyboardSwitcher switcher = latinIME.mKeyboardSwitcher;
        switch (msg.what) {
            case LatinIME.UIHandler.MSG_UPDATE_SUGGESTION_STRIP:
                print("handleMessage: MSG_UPDATE_SUGGESTION_STRIP");
                uiHandler.cancelUpdateSuggestionStrip();
                latinIME.mInputLogic.performUpdateSuggestionStripSync(
                        latinIME.mSettings.getCurrent(), msg.arg1 /* inputStyle */);
                break;
            case LatinIME.UIHandler.MSG_UPDATE_SHIFT_STATE:
                print("handleMessage: MSG_UPDATE_SHIFT_STATE");
                switcher.requestUpdatingShiftState(latinIME.getCurrentAutoCapsState(),
                        latinIME.getCurrentRecapitalizeState());
                break;
            case LatinIME.UIHandler.MSG_SHOW_GESTURE_PREVIEW_AND_SUGGESTION_STRIP:
                print("handleMessage: MSG_SHOW_GESTURE_PREVIEW_AND_SUGGESTION_STRIP");
                if (msg.arg1 == LatinIME.UIHandler.ARG1_NOT_GESTURE_INPUT) {
                    final SuggestedWords suggestedWords = (SuggestedWords) msg.obj;
                    latinIME.showSuggestionStrip(suggestedWords);
                } else {
                    latinIME.showGesturePreviewAndSuggestionStrip(
                            (SuggestedWords) msg.obj,
                            msg.arg1 ==
                                    LatinIME.UIHandler.ARG1_DISMISS_GESTURE_FLOATING_PREVIEW_TEXT
                    );
                }
                break;
            case LatinIME.UIHandler.MSG_RESUME_SUGGESTIONS:
                print("handleMessage: MSG_RESUME_SUGGESTIONS");
                restartSuggestionsOnWordTouchedByCursor(
                        latinIME.mInputLogic,
                        latinIME.mSettings.getCurrent(), false /* forStartInput */,
                        latinIME.mKeyboardSwitcher.getCurrentKeyboardScriptId());
                break;
            case LatinIME.UIHandler.MSG_RESUME_SUGGESTIONS_FOR_START_INPUT:
                print("handleMessage: MSG_RESUME_SUGGESTIONS_FOR_START_INPUT");
                restartSuggestionsOnWordTouchedByCursor(
                        latinIME.mInputLogic,
                        latinIME.mSettings.getCurrent(), true /* forStartInput */,
                        latinIME.mKeyboardSwitcher.getCurrentKeyboardScriptId());
                break;
            case LatinIME.UIHandler.MSG_REOPEN_DICTIONARIES:
                print("handleMessage: MSG_REOPEN_DICTIONARIES");
                // We need to re-evaluate the currently composing word in case the script has
                // changed.
                uiHandler.postWaitForDictionaryLoad();
                latinIME.resetDictionaryFacilitatorIfNecessary();
                break;
            case LatinIME.UIHandler.MSG_UPDATE_TAIL_BATCH_INPUT_COMPLETED:
                // this indicates the end of gesture input (swipe typing) typing
                print("handleMessage: MSG_UPDATE_TAIL_BATCH_INPUT_COMPLETED");
                final SuggestedWords suggestedWords = (SuggestedWords) msg.obj;
                latinIME.mInputLogic.onUpdateTailBatchInputCompleted(
                        latinIME.mSettings.getCurrent(),
                        suggestedWords, latinIME.mKeyboardSwitcher);
                latinIME.onTailBatchInputResultShown(suggestedWords);
                break;
            case LatinIME.UIHandler.MSG_RESET_CACHES:
                print("handleMessage: MSG_RESET_CACHES");
                final SettingsValues settingsValues = latinIME.mSettings.getCurrent();
                if (latinIME.mInputLogic.retryResetCachesAndReturnSuccess(
                        msg.arg1 == LatinIME.UIHandler.ARG1_TRUE /* tryResumeSuggestions */,
                        msg.arg2 /* remainingTries */, uiHandler /* handler */)) {
                    // If we were able to reset the caches, then we can reload the keyboard.
                    // Otherwise, we'll do it when we can.
                    latinIME.mKeyboardSwitcher.loadKeyboard(latinIME.getCurrentInputEditorInfo(),
                            settingsValues, latinIME.getCurrentAutoCapsState(),
                            latinIME.getCurrentRecapitalizeState());
                }
                break;
            case LatinIME.UIHandler.MSG_WAIT_FOR_DICTIONARY_LOAD:
                print("handleMessage: MSG_WAIT_FOR_DICTIONARY_LOAD");
                Log.i(TAG, "Timeout waiting for dictionary load");
                break;
            case LatinIME.UIHandler.MSG_DEALLOCATE_MEMORY:
                print("handleMessage: MSG_DEALLOCATE_MEMORY");
                latinIME.deallocateMemory();
                break;
            case LatinIME.UIHandler.MSG_SWITCH_LANGUAGE_AUTOMATICALLY:
                print("handleMessage: MSG_SWITCH_LANGUAGE_AUTOMATICALLY");
                latinIME.switchLanguage((InputMethodSubtype)msg.obj);
                break;
        }
    }

    /**
     * A suggestion was picked from the suggestion strip.
     * @param settingsValues the current values of the settings.
     * @param suggestionInfo the suggestion info.
     * @param keyboardShiftState the shift state of the keyboard, as returned by
     *     {@link KeyboardSwitcher#getKeyboardShiftMode()}
     * @return the complete transaction object
     */
    // Called from {@link SuggestionStripView} through the {@link SuggestionStripView#Listener}
    // interface
    static public void onPickSuggestionManually(final LatinIME latinIME, final SettingsValues settingsValues,
                                         final SuggestedWords.SuggestedWordInfo suggestionInfo, final int keyboardShiftState,
                                         final int currentKeyboardScriptId, final LatinIME.UIHandler handler) {
        final SuggestedWords suggestedWords = latinIME.mInputLogic.mSuggestedWords;
        final String suggestion = suggestionInfo.mWord;
        // If this is a punctuation picked from the suggestion strip, pass it to onCodeInput
        if (suggestion.length() == 1 && suggestedWords.isPunctuationSuggestions()) {
            // We still want to log a suggestion click.
            StatsUtils.onPickSuggestionManually(
                    latinIME.mInputLogic.mSuggestedWords, suggestionInfo, latinIME.mInputLogic.mDictionaryFacilitator);
            // Word separators are suggested before the user inputs something.
            // Rely on onCodeInput to do the complicated swapping/stripping logic consistently.
            final Event event = Event.createPunctuationSuggestionPickedEvent(suggestionInfo);
            latinIME.mInputLogic.onCodeInput(latinIME.mInputLogic.mLatinIME,false, settingsValues, event, keyboardShiftState,
                    currentKeyboardScriptId, handler);
            return;
        }

        final Event event = Event.createSuggestionPickedEvent(suggestionInfo);
        final InputTransaction inputTransaction = new InputTransaction(settingsValues,
                event, SystemClock.uptimeMillis(), latinIME.mInputLogic.mSpaceState, keyboardShiftState);
        // Manual pick affects the contents of the editor, so we take note of this. It's important
        // for the sequence of language switching.
        inputTransaction.setDidAffectContents();
        latinIME.mInputLogic.mConnection.beginBatchEdit();
        if (SpaceState.PHANTOM == latinIME.mInputLogic.mSpaceState && suggestion.length() > 0
                // In the batch input mode, a manually picked suggested word should just replace
                // the current batch input text and there is no need for a phantom space.
                && !latinIME.mInputLogic.mWordComposer.isBatchMode()) {
            final int firstChar = Character.codePointAt(suggestion, 0);
            if (!settingsValues.isWordSeparator(firstChar)
                    || settingsValues.isUsuallyPrecededBySpace(firstChar)) {
                latinIME.mInputLogic.insertAutomaticSpaceIfOptionsAndTextAllow(settingsValues);
            }
        }

        // TODO: We should not need the following branch. We should be able to take the same
        //  code path as for other kinds, use commitChosenWord, and do everything normally. We will
        //  however need to reset the suggestion strip right away, because we know we can't take
        //  the risk of calling commitCompletion twice because we don't know how the app will react.
        if (suggestionInfo.isKindOf(SuggestedWords.SuggestedWordInfo.KIND_APP_DEFINED)) {
            latinIME.mInputLogic.mSuggestedWords = SuggestedWords.getEmptyInstance();
            latinIME.mInputLogic.mSuggestionStripViewAccessor.setNeutralSuggestionStrip();
            inputTransaction.requireShiftUpdate(InputTransaction.SHIFT_UPDATE_NOW);
            latinIME.mInputLogic.resetComposingState(true /* alsoResetLastComposedWord */);
            onCommitCompletion(latinIME, suggestionInfo.mApplicationSpecifiedCompletionInfo);
            latinIME.mInputLogic.mConnection.endBatchEdit();
            latinIME.updateStateAfterInputTransaction(inputTransaction);
            return;
        }
        print("commit chosen word: " + suggestion);
        onCommitSuggestion(
                latinIME, settingsValues, suggestion,
                LastComposedWord.COMMIT_TYPE_MANUAL_PICK,
                LastComposedWord.NOT_A_SEPARATOR
        );
        latinIME.mInputLogic.mConnection.endBatchEdit();
        // Don't allow cancellation of manual pick
        latinIME.mInputLogic.mLastComposedWord.deactivate();
        // Space state must be updated before calling updateShiftState
        latinIME.mInputLogic.mSpaceState = SpaceState.PHANTOM;
        inputTransaction.requireShiftUpdate(InputTransaction.SHIFT_UPDATE_NOW);

        // If we're not showing the "Touch again to save", then update the suggestion strip.
        // That's going to be predictions (or punctuation suggestions), so INPUT_STYLE_NONE.
        handler.postUpdateSuggestionStrip(SuggestedWords.INPUT_STYLE_NONE);

        StatsUtils.onPickSuggestionManually(
                latinIME.mInputLogic.mSuggestedWords, suggestionInfo, latinIME.mInputLogic.mDictionaryFacilitator);
        StatsUtils.onWordCommitSuggestionPickedManually(
                suggestionInfo.mWord, latinIME.mInputLogic.mWordComposer.isBatchMode());
        latinIME.updateStateAfterInputTransaction(inputTransaction);
    }

    static public final class types {
        static public final int NO_TYPE = -1;
        static public final int BACKSPACE = 0;
        static public final int SINGLE_CHARACTER = 1;
        static public final int MULTI_CHARACTER = 2;
    }
    static public final String TAG = "predictive.engine";

    static public final void print(String what) {
        Log.e(TAG, what);
    }
    static public final void info(String letter, String type) {
        print("letter: " + letter);
        print("type:   " + type);
    }

    /**
     * this gets called when a letter is typed, this BLOCKS the UI Thread
     * @param letter
     * @param type
     */

    static public final void onTextEntry(String letter, int type) {
        print("INPUT letter: " + letter + ", " + "type:   " + type);
    }

    static public final void onBackspace(String letter, int type) {
        print("BACKSPACE letter: " + letter + ", " + "type:   " + type);
    }

    static public final SuggestedWords.SuggestedWordInfo newWord(String word) {
        return new SuggestedWords.SuggestedWordInfo(
                word, "",
                SuggestedWords.SuggestedWordInfo.MAX_SCORE,
                SuggestedWords.SuggestedWordInfo.KIND_TYPED,
                Dictionary.DICTIONARY_USER_TYPED,
                SuggestedWords.SuggestedWordInfo.NOT_AN_INDEX /* indexOfTouchPointOfSecondWord */,
                SuggestedWords.SuggestedWordInfo.NOT_A_CONFIDENCE /* autoCommitFirstWordConfidence */);
    }

    static public void getSuggestedWords(
            final Suggest mSuggest, final WordComposer mWordComposer,
            final NgramContext ngramContextFromNthPreviousWordForSuggestion,
            final Keyboard keyboard, final SettingsValuesForSuggestion settingsValuesForSuggestion,
            final boolean isCorrectionEnabled, final int inputStyle, final int sequenceNumber,
            final Suggest.OnGetSuggestedWordsCallback callback) {
        if (mWordComposer.isBatchMode()) {
            print("BATCH MODE SUGGESTION (gesture input (swipe typing))");
            // forward to suggest.getSuggestedWordsForBatchInput as we do not yet know how to handle
            // gesture input suggestion
            mSuggest.getSuggestedWordsForBatchInput(
                    mWordComposer, ngramContextFromNthPreviousWordForSuggestion, keyboard,
                    settingsValuesForSuggestion, inputStyle, sequenceNumber, callback
            );
        } else {
            print("NON BATCH MODE SUGGESTION (non gesture input (swipe typing) (normal typing))");
            print("getSuggestedWordsForNonBatchInput");
            final ArrayList<SuggestedWords.SuggestedWordInfo> suggestionsList = new ArrayList(0);
            info(mWordComposer.getTypedWord(),
                    "mCursorPositionWithinWord: " + mWordComposer.getCursorPositionWithinWord());
            // if there are more then one characters,
            // and there exists only one suggestion,
            // then the current word is the only suggestion

            // fill the list
            suggestionsList.add(newWord("suggestion one"));
            suggestionsList.add(newWord("suggestion two"));

            callback.onGetSuggestedWords(
                    new SuggestedWords(
                            suggestionsList,
                            null,
                            null,
                            false,
                            false,
                            false,
                            SuggestedWords.INPUT_STYLE_PREDICTION,
                            // what is sequence number used for?
                            sequenceNumber
                    )
            );
        }
    }
}
