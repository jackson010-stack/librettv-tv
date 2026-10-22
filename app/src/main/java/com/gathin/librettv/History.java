package com.gathin.librettv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** What was played, where it stopped, so it can be picked up again later. */
public final class History {

    private static final String FILE = "librettv";
    private static final String KEY = "history";
    private static final int MAX = 50;
    /** Below this many milliseconds the title barely started, so it is not worth remembering. */
    private static final long MIN_POSITION_MS = 5000;

    public static final class Entry {
        public String key = "";
        public String sourceKey = "";
        public String sourceName = "";
        public String id = "";
        public String name = "";
        public String pic = "";
        public String year = "";
        public String type = "";
        public String remarks = "";
        public String playUrl = "";
        public String playFrom = "";
        public String episodeName = "";
        public String episodeUrl = "";
        /** The page the episode was played from, needed to resolve a share link again. */
        public String referer = "";
        public long positionMs;
        public long durationMs;
        public long updatedAt;
        /** The title was watched to the end, so it is offered as a fresh start. */
        public boolean finished;

        public int progressPercent() {
            if (durationMs <= 0) {
                return 0;
            }
            int p = (int) (positionMs * 100 / durationMs);
            return Math.max(0, Math.min(100, p));
        }
    }

    private History() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String keyOf(String sourceKey, String id) {
        return sourceKey + "|" + id;
    }

    public static List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<>();
        String raw = prefs(c).getString(KEY, null);
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Entry e = new Entry();
                e.key = o.optString("key");
                e.sourceKey = o.optString("sourceKey");
                e.sourceName = o.optString("sourceName");
                e.id = o.optString("id");
                e.name = o.optString("name");
                e.pic = o.optString("pic");
                e.year = o.optString("year");
                e.type = o.optString("type");
                e.remarks = o.optString("remarks");
                e.playUrl = o.optString("playUrl");
                e.playFrom = o.optString("playFrom");
                e.episodeName = o.optString("episodeName");
                e.episodeUrl = o.optString("episodeUrl");
                e.referer = o.optString("referer");
                e.positionMs = o.optLong("positionMs");
                e.durationMs = o.optLong("durationMs");
                e.updatedAt = o.optLong("updatedAt");
                e.finished = o.optBoolean("finished");
                if (!e.key.isEmpty()) {
                    out.add(e);
                }
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Long.compare(b.updatedAt, a.updatedAt);
            }
        });
        return out;
    }

    public static Entry find(Context c, String sourceKey, String id) {
        String want = keyOf(sourceKey, id);
        for (Entry e : list(c)) {
            if (e.key.equals(want)) {
                return e;
            }
        }
        return null;
    }

    /** Stores the position of one title, newest first. */
    public static void record(Context c, Entry entry) {
        if (entry == null || entry.key == null || entry.key.isEmpty()) {
            return;
        }
        List<Entry> all = list(c);
        Entry existing = null;
        for (Entry e : all) {
            if (e.key.equals(entry.key)) {
                existing = e;
                break;
            }
        }
        if (existing != null) {
            all.remove(existing);
        }
        entry.updatedAt = System.currentTimeMillis();
        all.add(0, entry);
        while (all.size() > MAX) {
            all.remove(all.size() - 1);
        }
        save(c, all);
    }

    public static void remove(Context c, String key) {
        List<Entry> all = list(c);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).key.equals(key)) {
                all.remove(i);
            }
        }
        save(c, all);
    }

    public static void clear(Context c) {
        prefs(c).edit().remove(KEY).apply();
    }

    private static void save(Context c, List<Entry> all) {
        JSONArray arr = new JSONArray();
        for (Entry e : all) {
            JSONObject o = new JSONObject();
            try {
                o.put("key", e.key);
                o.put("sourceKey", e.sourceKey);
                o.put("sourceName", e.sourceName);
                o.put("id", e.id);
                o.put("name", e.name);
                o.put("pic", e.pic);
                o.put("year", e.year);
                o.put("type", e.type);
                o.put("remarks", e.remarks);
                o.put("playUrl", e.playUrl);
                o.put("playFrom", e.playFrom);
                o.put("episodeName", e.episodeName);
                o.put("episodeUrl", e.episodeUrl);
                o.put("referer", e.referer);
                o.put("positionMs", e.positionMs);
                o.put("durationMs", e.durationMs);
                o.put("updatedAt", e.updatedAt);
                o.put("finished", e.finished);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        prefs(c).edit().putString(KEY, arr.toString()).apply();
    }

    /** True when the position is worth remembering at all. */
    public static boolean worthKeeping(long positionMs) {
        return positionMs >= MIN_POSITION_MS;
    }

    public static String formatMs(long ms) {
        if (ms <= 0) {
            return "00:00";
        }
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, sec);
        }
        return String.format("%02d:%02d", m, sec);
    }
}
