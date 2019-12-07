package com.android.inputmethod.predictive.engine;

import android.os.Debug;
import android.os.Message;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodSubtype;

import com.android.inputmethod.accessibility.AccessibilityUtils;
import com.android.inputmethod.event.Event;
import com.android.inputmethod.event.InputTransaction;
import com.android.inputmethod.keyboard.KeyboardSwitcher;
import com.android.inputmethod.keyboard.MainKeyboardView;
import com.android.inputmethod.latin.InputAttributes;
import com.android.inputmethod.latin.LatinIME;
import com.android.inputmethod.latin.Suggest;
import com.android.inputmethod.latin.SuggestedWords;
import com.android.inputmethod.latin.WordComposer;
import com.android.inputmethod.latin.common.Constants;
import com.android.inputmethod.latin.common.StringUtils;
import com.android.inputmethod.latin.define.DebugFlags;
import com.android.inputmethod.latin.inputlogic.InputLogic;
import com.android.inputmethod.latin.settings.Settings;
import com.android.inputmethod.latin.settings.SettingsValues;
import com.android.inputmethod.latin.touchinputconsumer.GestureConsumer;
import com.android.inputmethod.latin.utils.StatsUtils;

import javax.annotation.Nonnull;

import static com.android.inputmethod.latin.common.Constants.ImeOption.FORCE_ASCII;
import static com.android.inputmethod.latin.common.Constants.ImeOption.NO_MICROPHONE;
import static com.android.inputmethod.latin.common.Constants.ImeOption.NO_MICROPHONE_COMPAT;

public final class engine {
    public InputTransaction process(
            final InputLogic inputLogic, final WordComposer wordComposer,
            final SettingsValues settingsValues, @Nonnull final Event unprocessedEvent,
            final int keyboardShiftMode, final int spaceState,
            final int currentKeyboardScriptId, final LatinIME.UIHandler handler
            ) {
        final Event processedEvent = wordComposer.processEvent(unprocessedEvent);
        final InputTransaction inputTransaction = new InputTransaction(settingsValues,
                processedEvent, SystemClock.uptimeMillis(), spaceState,
                inputLogic.getActualCapsMode(settingsValues, keyboardShiftMode));
        print("letter: " + wordComposer.getTypedWord());
        print("onCodeInput");
        print("key: " + processedEvent.mKeyCode);
        print("codePoint: " + StringUtils.newSingleCodePointString(processedEvent.mCodePoint));
        print("events are handled vis a linked list, event = event.mNextEvent");

        inputLogic.mConnection.beginBatchEdit();
        Event currentEvent = processedEvent;
        while (null != currentEvent) {
            if (currentEvent.isConsumed()) {
                inputLogic.handleConsumedEvent(currentEvent, inputTransaction);
            } else if (currentEvent.isFunctionalKeyEvent()) {
                inputLogic.handleFunctionalEvent(currentEvent, inputTransaction, currentKeyboardScriptId,
                        handler);
            } else {
                inputLogic.handleNonFunctionalEvent(currentEvent, inputTransaction, handler);
            }
            currentEvent = currentEvent.mNextEvent;
        }
        if (!inputTransaction.didAutoCorrect() && processedEvent.mKeyCode != Constants.CODE_SHIFT
                && processedEvent.mKeyCode != Constants.CODE_CAPSLOCK
                && processedEvent.mKeyCode != Constants.CODE_SWITCH_ALPHA_SYMBOL)
            inputLogic.mLastComposedWord.deactivate();
        if (Constants.CODE_DELETE != processedEvent.mKeyCode) {
            inputLogic.mEnteredText = null;
        }
        inputLogic.mConnection.endBatchEdit();
        return inputTransaction;
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
