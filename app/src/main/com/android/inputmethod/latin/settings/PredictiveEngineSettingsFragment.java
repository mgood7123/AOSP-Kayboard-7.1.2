package com.android.inputmethod.latin.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.media.AudioManager;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.text.TextUtils;

import com.android.inputmethod.latin.AudioAndHapticFeedbackManager;
import com.android.inputmethod.latin.SystemBroadcastReceiver;
import com.android.inputmethod.predictive.engine.engine;

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
            engine.database.reset();
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
