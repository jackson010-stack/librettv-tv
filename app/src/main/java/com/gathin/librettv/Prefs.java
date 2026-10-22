package com.gathin.librettv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Which collectors are switched on. Every normal source is on from the first start. */
public final class Prefs {

    private static final String FILE = "librettv";
    private static final String KEY_ENABLED = "enabled_sources";

    private Prefs() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static Set<String> defaults() {
        Set<String> set = new HashSet<>();
        for (Sources.Source s : Sources.NORMAL) {
            set.add(s.key);
        }
        return set;
    }

    public static boolean isEnabled(Context c, Sources.Source s) {
        Set<String> stored = prefs(c).getStringSet(KEY_ENABLED, null);
        if (stored == null) {
            return !s.adult;
        }
        return stored.contains(s.key);
    }

    public static void setEnabled(Context c, Sources.Source s, boolean on) {
        Set<String> stored = prefs(c).getStringSet(KEY_ENABLED, null);
        Set<String> set = new HashSet<>(stored == null ? defaults() : stored);
        if (on) {
            set.add(s.key);
        } else {
            set.remove(s.key);
        }
        prefs(c).edit().putStringSet(KEY_ENABLED, set).apply();
    }

    public static void setAll(Context c, boolean on) {
        Set<String> set = new HashSet<>();
        if (on) {
            for (Sources.Source s : Sources.ALL) {
                set.add(s.key);
            }
        }
        prefs(c).edit().putStringSet(KEY_ENABLED, set).apply();
    }
}
