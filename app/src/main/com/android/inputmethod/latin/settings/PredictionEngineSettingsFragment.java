package com.android.inputmethod.latin.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.Preference;

import com.android.inputmethod.prediction.engine.Engine;

import AOSP.KEYBOARD.R;

/**
 * "Prediction Engine" settings sub screen.
 *
 * This settings sub screen handles the following Prediction Engine preferences.
 */
public final class PredictionEngineSettingsFragment extends SubScreenFragment
        implements Preference.OnPreferenceClickListener {

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();
//        SharedPreferences sharedPreferences = getSharedPreferences();
        if (key.equals(Settings.PREF_PredictionEngineClearData)) {
            Engine.database.reset();
            return true;
        }
        return false;
    }

//    @Override public void onSharedPreferenceChanged (
//            final SharedPreferences sharedPreferences, final String key
//    ) {
//        if (TextUtils.equals(key, Settings.PREF_PREDICTIVE_ENGINE_VERSION_TWO)) {
//            boolean state = !sharedPreferences.getBoolean(key, false);
//            setPreferenceEnabled(Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE, state);
//            setPreferenceEnabled(Settings.PREF_AUTO_CORRECTION, state);
//            setPreferenceEnabled(Settings.PREF_SHOW_SUGGESTIONS, state);
//            setPreferenceEnabled(Settings.PREF_KEY_USE_PERSONALIZED_DICTS, state);
//            setPreferenceEnabled(Settings.PREF_KEY_USE_CONTACTS_DICT, state);
//            setPreferenceEnabled(Settings.PREF_BIGRAM_PREDICTIONS, state);
//            return;
//        } else if (!TextUtils.equals(key, Settings.PREF_KEY_USE_CONTACTS_DICT)) {
//            return;
//        }
//    }

    @Override
    public void onCreate(final Bundle icicle) {
        super.onCreate(icicle);
        addPreferencesFromResource(R.xml.prefs_screen_predictive_engine);
    }
}
