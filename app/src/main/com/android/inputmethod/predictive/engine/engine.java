package com.android.inputmethod.predictive.engine;

import android.util.Log;

public final class engine {
    public static final class types {
        public static final int NO_TYPE = -1;
        public static final int BACKSPACE = 0;
        public static final int SINGLE_CHARACTER = 1;
        public static final int MULTI_CHARACTER = 2;
    }
    public final String TAG = "predictive.engine";

    public final void print(String what) {
        Log.i(TAG, what);
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
