package com.gathin.librettv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.GZIPInputStream;

/** Talks to the Mac CMS collectors of {@link Sources}. */
public final class Api {

    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/122.0.0.0 Safari/537.36";

    private static final int TIMEOUT_MS = 12000;

    public static final class Movie {
        public String id = "";
        public String name = "";
        public String pic = "";
        public String content = "";
        public String year = "";
        public String area = "";
        public String type = "";
        public String remarks = "";
        public String playUrl = "";
        public String playFrom = "";
        public String sourceKey = "";
        public String sourceName = "";
        /** The collector's own category of this title, e.g. 电影 / 动作片. */
        public String typeName = "";
        public int typeId = 0;
        /** The group the category belongs to, as the collector reports it in {@code type_id_1}. */
        public int parentTypeId = 0;
    }

    /** One entry of a collector's category tree. */
    public static final class Category {
        public int typeId;
        public int typePid;
        public String typeName = "";
        /** The name of the parent category, empty for a top level one. */
        public String parentName = "";
    }

    private Api() {
    }

    /** One page of a collector's catalogue or search result. */
    public static final class Page {
        public final List<Movie> items = new ArrayList<>();
        public int page = 1;
        public int pageCount = 1;
        public int total = 0;
        /** The collector could not be reached, as opposed to having nothing to show. */
        public boolean failed;
    }

    public static List<Movie> search(Sources.Source s, String keyword) {
        return searchPage(s, keyword, 1).items;
    }

    /** The collector's own list page, used as the "推荐" shelf when nothing is searched for. */
    public static List<Movie> list(Sources.Source s) {
        return catalogue(s, 1).items;
    }

    /** Page {@code pg} of the collector's whole catalogue. */
    public static Page catalogue(Sources.Source s, int pg) {
        return fetchPage(s, "", pg);
    }

    /** Page {@code pg} of the collector's hits for {@code keyword}. */
    public static Page searchPage(Sources.Source s, String keyword, int pg) {
        try {
            return fetchPage(s, "&wd=" + URLEncoder.encode(keyword, "UTF-8"), pg);
        } catch (Exception e) {
            return new Page();
        }
    }

    /** Page {@code pg} of one category of the collector. */
    public static Page categoryPage(Sources.Source s, int typeId, int pg) {
        return fetchPage(s, "&t=" + typeId, pg);
    }

    /**
     * The collector's category tree. Mac CMS answers {@code ?ac=list} with a {@code class} array
     * whose entries carry {@code type_id}, {@code type_pid} and {@code type_name}; the top level
     * ones have a parent of 0.
     */
    public static List<Category> classes(Sources.Source s) {
        List<Category> out = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(s.api + "/api.php/provide/vod/?ac=list").openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Referer", s.origin() + "/");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) {
                return out;
            }
            InputStream in = conn.getInputStream();
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                in = new GZIPInputStream(in);
            }
            JSONObject obj = new JSONObject(readAll(in));
            JSONArray arr = obj.optJSONArray("class");
            if (arr == null) {
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it == null) {
                    continue;
                }
                Category c = new Category();
                c.typeId = num(it.optString("type_id"), 0);
                c.typePid = num(it.optString("type_pid"), 0);
                c.typeName = it.optString("type_name");
                if (!c.typeName.isEmpty() && c.typeId > 0) {
                    out.add(c);
                }
            }
            fillParentNames(out);
        } catch (Exception e) {
            // A collector that cannot be reached simply has no categories.
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return out;
    }

    /** Gives every category the name of its parent, once the parents are known. */
    private static void fillParentNames(List<Category> cats) {
        for (Category c : cats) {
            for (Category p : cats) {
                if (c.typePid != 0 && c.typePid == p.typeId) {
                    c.parentName = p.typeName;
                    break;
                }
            }
        }
    }

    /**
     * Some collectors publish a flat category list: every entry looks like a top level one, yet
     * their titles carry the leaf category in {@code type_id} and the group in {@code type_id_1}.
     * Asking the collector for one title of every category is the only way to learn which leaves
     * belong to which group, so the whole tree costs one request per category.
     */
    public static void resolveParents(Sources.Source s, List<Category> cats) {
        ExecutorService es = Executors.newFixedThreadPool(4);
        List<Future<Integer>> futures = new ArrayList<>();
        for (final Category c : cats) {
            futures.add(es.submit(() -> {
                Page p = categoryPage(s, c.typeId, 1);
                for (Movie m : p.items) {
                    if (m.parentTypeId > 0 && m.parentTypeId != c.typeId) {
                        return m.parentTypeId;
                    }
                }
                return 0;
            }));
        }
        es.shutdown();
        for (int i = 0; i < cats.size() && i < futures.size(); i++) {
            try {
                int parent = futures.get(i).get();
                if (parent > 0) {
                    cats.get(i).typePid = parent;
                }
            } catch (Exception ignored) {
                // A category the collector would not answer for stays a top level one.
            }
        }
        fillParentNames(cats);
    }

    /** The parent of every category as JSON, so a flat collector is only probed once. */
    public static String encodeTree(List<Category> cats) {
        JSONArray arr = new JSONArray();
        for (Category c : cats) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", c.typeId);
                o.put("pid", c.typePid);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        return arr.toString();
    }

    /** Restores a tree that {@link #encodeTree} wrote. */
    public static void applyTree(List<Category> cats, String json) {
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(json);
            Map<Integer, Integer> pids = new HashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    pids.put(o.optInt("id"), o.optInt("pid"));
                }
            }
            for (Category c : cats) {
                Integer pid = pids.get(c.typeId);
                if (pid != null) {
                    c.typePid = pid;
                }
            }
            fillParentNames(cats);
        } catch (Exception ignored) {
        }
    }

    public static List<Movie> detail(Sources.Source s, String id) {
        try {
            return fetch(s, "&ids=" + URLEncoder.encode(id, "UTF-8"));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static List<Movie> fetch(Sources.Source s, String extra) {
        return fetchPage(s, extra, 1).items;
    }

    private static Page fetchPage(Sources.Source s, String extra, int pg) {
        Page page = new Page();
        page.page = pg;
        List<Movie> out = page.items;
        String url = s.api + "/api.php/provide/vod/?ac=videolist" + extra + "&pg=" + pg;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Referer", s.origin() + "/");
            conn.setRequestProperty("Accept-Encoding", "gzip");

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                page.failed = true;
                return page;
            }
            InputStream in = conn.getInputStream();
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                in = new GZIPInputStream(in);
            }
            JSONObject obj = new JSONObject(readAll(in));
            page.page = num(obj.optString("page"), pg);
            page.pageCount = num(obj.optString("pagecount"), 1);
            page.total = num(obj.optString("total"), 0);
            JSONArray list = obj.optJSONArray("list");
            if (list == null) {
                return page;
            }
            for (int i = 0; i < list.length(); i++) {
                JSONObject it = list.optJSONObject(i);
                if (it == null) {
                    continue;
                }
                Movie m = new Movie();
                m.id = it.optString("vod_id");
                m.name = it.optString("vod_name");
                m.pic = it.optString("vod_pic");
                m.content = it.optString("vod_content");
                m.year = it.optString("vod_year");
                m.area = it.optString("vod_area");
                m.type = it.optString("vod_class");
                m.remarks = it.optString("vod_remarks");
                m.playUrl = it.optString("vod_play_url");
                m.playFrom = it.optString("vod_play_from");
                m.sourceKey = s.key;
                m.sourceName = s.name;
                m.typeId = num(it.optString("type_id"), 0);
                m.typeName = it.optString("type_name");
                m.parentTypeId = num(it.optString("type_id_1"), 0);
                if (!m.name.isEmpty()) {
                    out.add(m);
                }
            }
        } catch (Exception e) {
            // A dead or slow collector must not take the whole search down.
            page.failed = true;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return page;
    }

    private static int num(String s, int fallback) {
        try {
            return s == null || s.trim().isEmpty() ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    /** One play line of a Mac CMS title: {@code vod_play_from} name plus its episodes. */
    public static final class Line {
        public String label = "";
        public List<String[]> episodes = new ArrayList<>();
    }

    /** Splits a Mac CMS {@code vod_play_url} into its play lines. */
    public static List<Line> parseLines(String playUrl, String playFrom) {
        List<Line> out = new ArrayList<>();
        if (playUrl == null || playUrl.isEmpty()) {
            return out;
        }
        String[] urls = playUrl.split("\\$\\$\\$");
        String[] froms = (playFrom == null || playFrom.isEmpty()) ? new String[0] : playFrom.split("\\$\\$\\$");
        for (int i = 0; i < urls.length; i++) {
            Line line = new Line();
            line.label = i < froms.length && !froms[i].trim().isEmpty() ? froms[i].trim() : "线路" + (i + 1);
            for (String ep : urls[i].split("#")) {
                if (ep.isEmpty()) {
                    continue;
                }
                int j = ep.indexOf('$');
                String name = j > 0 ? ep.substring(0, j) : "播放";
                String url = j > 0 ? ep.substring(j + 1) : ep;
                if (!url.isEmpty()) {
                    line.episodes.add(new String[]{name, url});
                }
            }
            if (!line.episodes.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    public static boolean looksLikeMedia(String url) {
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase();
        return u.contains(".m3u8") || u.contains(".mp4") || u.contains(".mkv") || u.contains(".flv")
                || u.contains(".ts") || u.contains(".mov");
    }

    /**
     * Some collectors hand out a "/share/xxxx" page instead of the playlist. Follow it and pull the
     * first playlist URL out of the HTML.
     */
    public static String resolveMediaUrl(String url, String referer) {
        if (url == null || url.isEmpty() || looksLikeMedia(url)) {
            return url;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*");
            if (referer != null && !referer.isEmpty()) {
                conn.setRequestProperty("Referer", referer);
            }
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) {
                return null;
            }
            String html = readAll(conn.getInputStream());
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("https?://[^\"'<>\\\\\\s]+\\.m3u8[^\"'<>\\\\\\s]*").matcher(html);
            if (m.find()) {
                return m.group(0);
            }
            m = java.util.regex.Pattern
                    .compile("\"url\"\\s*:\\s*\"(https?://[^\"]+)\"").matcher(html);
            if (m.find()) {
                return m.group(1);
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
