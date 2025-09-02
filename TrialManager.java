package com.squeezer.app;

import android.content.Context;
import android.content.SharedPreferences;

public class TrialManager {
    private static final String PREF_NAME = "TrialPrefs";
    private static final String KEY_START_TIME = "trial_start";
    private static final long TRIAL_DURATION_MS = 60 * 60 * 1000; // 1 hour in milliseconds

    public static boolean isTrialExpired(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        long startTime = prefs.getLong(KEY_START_TIME, -1);
        if (startTime == -1) {
            prefs.edit().putLong(KEY_START_TIME, System.currentTimeMillis()).apply();
            return false;
        }
        return System.currentTimeMillis() - startTime > TRIAL_DURATION_MS;
    }

    public static long getRemainingTimeMillis(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        long startTime = prefs.getLong(KEY_START_TIME, -1);
        if (startTime == -1) return TRIAL_DURATION_MS;
        long elapsed = System.currentTimeMillis() - startTime;
        return Math.max(0, TRIAL_DURATION_MS - elapsed);
    }
}
