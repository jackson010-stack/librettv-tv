package com.gathin.librettv;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * The collector sites of LibreTV's own source list (KeyRotate/LibreTV-App, js/config.js).
 *
 * <p>The list is copied verbatim from the upstream app: 21 built-in entries, plus the eight sites
 * upstream commented out (it refuses to ship them). All of them are Mac CMS ("苹果 CMS") collectors
 * that answer {@code /api.php/provide/vod/?ac=videolist}.
 */
public final class Sources {

    public static final class Source {
        public final String key;
        public final String name;
        public final String api;
        public final boolean adult;

        Source(String key, String name, String api, boolean adult) {
            this.key = key;
            this.name = name;
            this.api = api;
            this.adult = adult;
        }

        /** Scheme and host of the collector, used as the Referer when playing its streams. */
        public String origin() {
            try {
                URL u = new URL(api);
                int port = u.getPort();
                return u.getProtocol() + "://" + u.getHost() + (port > 0 ? ":" + port : "");
            } catch (MalformedURLException e) {
                return api;
            }
        }
    }

    public static final List<Source> ALL = new ArrayList<>();
    public static final List<Source> NORMAL = new ArrayList<>();
    public static final List<Source> ADULT = new ArrayList<>();

    private Sources() {
    }

    private static void add(String key, String name, String api, boolean adult) {
        Source s = new Source(key, name, api, adult);
        ALL.add(s);
        (adult ? ADULT : NORMAL).add(s);
    }

    static {
        add("dyttzy", "电影天堂资源", "http://caiji.dyttzyapi.com", false);
        add("ruyi", "如意资源", "https://cj.rycjapi.com", false);
        add("bfzy", "暴风资源", "https://bfzyapi.com", false);
        add("tyyszy", "天涯资源", "https://tyyszy.com", false);
        add("xiaomaomi", "小猫咪资源", "https://zy.xiaomaomi.cc", false);
        add("ffzy", "非凡影视", "http://ffzy5.tv", false);
        add("heimuer", "黑木耳", "https://json.heimuer.xyz", false);
        add("zy360", "360资源", "https://360zy.com", false);
        add("wolong", "卧龙资源", "https://wolongzyw.com", false);
        add("hwba", "华为吧资源", "https://cjhwba.com", false);
        add("jisu", "极速资源", "https://jszyapi.com", false);
        add("dbzy", "豆瓣资源", "https://dbzy.com", false);
        add("mozhua", "魔爪资源", "https://mozhuazy.com", false);
        add("mdzy", "魔都资源", "https://www.mdzyapi.com", false);
        add("zuid", "最大资源", "https://api.zuidapi.com", false);
        add("yinghua", "樱花资源", "https://m3u8.apiyhzy.com", false);
        add("baidu", "百度云资源", "https://api.apibdzy.com", false);
        add("wujin", "无尽资源", "https://api.wujinapi.me", false);
        add("wwzy", "旺旺短剧", "https://wwzy.tv", false);
        add("ikun", "iKun资源", "https://ikunzyapi.com", false);
        add("testSource", "空内容测试源", "https://www.example.com", false);

        // Upstream keeps these commented out; they are listed here but start switched off.
        add("ckzy", "CK资源", "https://www.ckzy1.com", true);
        add("jkun", "jkun资源", "https://jkunzyapi.com", true);
        add("bwzy", "百万资源", "https://api.bwzym3u8.com", true);
        add("souav", "souav资源", "https://api.souavzy.vip", true);
        add("r155", "155资源", "https://155api.com", true);
        add("lsb", "lsb资源", "https://apilsbzy1.com", true);
        add("huangcang", "黄色仓库", "https://hsckzy.vip", true);
        add("yutu", "玉兔资源", "https://yutuzy10.com", true);
    }

    public static Source byKey(String key) {
        for (Source s : ALL) {
            if (s.key.equals(key)) {
                return s;
            }
        }
        return null;
    }
}
