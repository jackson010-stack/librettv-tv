package com.gathin.librettv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small poster loader: a memory cache plus a fixed thread pool, no third-party image library. */
public final class ImageLoader {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(8 * 1024 * 1024);
    private static final ExecutorService POOL = Executors.newFixedThreadPool(6);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ImageLoader() {
    }

    public static void load(ImageView view, String url, String referer) {
        if (url == null || url.isEmpty()) {
            view.setImageDrawable(null);
            return;
        }
        if (url.equals(view.getTag()) && view.getDrawable() != null) {
            return;
        }
        view.setTag(url);
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        POOL.execute(() -> {
            Bitmap bmp = download(url, referer);
            if (bmp == null) {
                return;
            }
            CACHE.put(url, bmp);
            MAIN.post(() -> {
                if (url.equals(view.getTag())) {
                    view.setImageBitmap(bmp);
                }
            });
        });
    }

    private static Bitmap download(String url, String referer) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", Api.UA);
            conn.setRequestProperty("Accept", "image/*,*/*");
            if (referer != null && !referer.isEmpty()) {
                conn.setRequestProperty("Referer", referer);
            }
            if (conn.getResponseCode() / 100 != 2) {
                return null;
            }
            InputStream in = conn.getInputStream();
            byte[] bytes = readAll(in);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            int sample = 1;
            while (opts.outWidth / sample > 480) {
                sample *= 2;
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toByteArray();
    }
}
