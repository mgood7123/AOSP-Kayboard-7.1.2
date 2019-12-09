package com.android.inputmethod.latin.settings;

import android.os.Bundle;
import android.preference.Preference;
import com.android.inputmethod.predictive.engine.Engine;

import AOSP.KEYBOARD.R;

/**
 * "Predictive Engine" settings sub screen.
 *
 * This settings sub screen handles the following Predictive Engine preferences.
 */
public final class PredictiveEngineSettingsFragment extends SubScreenFragment
        implements Preference.OnPreferenceClickListener {
    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();
        if (key.equals("pref_predictive_engine_version_two_clear")) {
            Engine.database.reset();
            return true;
        }
        return false;
    }

    @Override
    public void onCreate(final Bundle icicle) {
        super.onCreate(icicle);
        addPreferencesFromResource(R.xml.prefs_screen_predictive_engine);
    }
}
