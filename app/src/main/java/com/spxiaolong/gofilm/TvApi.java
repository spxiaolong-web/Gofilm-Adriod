package com.spxiaolong.gofilm;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.LruCache;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Read-only access to the same public catalogue used by the website. */
final class TvApi {
    static final String HOME = "https://film.spxiaolong.com";
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };

    JSONObject get(String path) throws Exception {
        CatalogEndpoint.requirePath(path);
        return decode(path, new String(read(HOME + path, 3 * 1024 * 1024), StandardCharsets.UTF_8));
    }

    JSONObject decode(String path, String body) throws Exception {
        if (body.length() > 3 * 1024 * 1024) throw new IOException("返回内容过大");
        JSONObject response = new JSONObject(body);
        if (response.optInt("code", -1) != 0) {
            String message = response.optString("msg", "数据加载失败");
            if (path.startsWith("/api/searchFilm?") && message.contains("暂无")) {
                return new JSONObject("{\"list\":[],\"page\":{\"pageCount\":1}}");
            }
            throw new IOException(message);
        }
        JSONObject data = response.optJSONObject("data");
        if (data == null) throw new IOException("网站没有返回有效数据");
        return data;
    }

    Bitmap image(String value) throws Exception {
        if (value == null || value.isEmpty()) return null;
        String url = value.startsWith("//") ? "https:" + value
                : value.startsWith("/") ? HOME + value : value;
        // Cleartext remains disabled, including for third-party posters.
        if (!"https".equalsIgnoreCase(Uri.parse(url).getScheme())) return null;
        Bitmap cached = images.get(url);
        if (cached != null) return cached;
        byte[] bytes = read(url, 4 * 1024 * 1024);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > 600
                || bounds.outHeight / options.inSampleSize > 900) options.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap != null) images.put(url, bitmap);
        return bitmap;
    }

    private byte[] read(String address, int limit) throws Exception {
        URL url = new URL(address);
        for (int redirects = 0; redirects < 5; redirects++) {
            if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("地址需要 HTTPS");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "GoFilm-Android-TV/0.5.1");
            connection.setRequestProperty("Referer", HOME + "/");
            connection.setRequestProperty("Accept", "application/json,image/*;q=0.9,*/*;q=0.8");
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("网站重定向异常");
                    url = new URL(url, location);
                    continue;
                }
                if (status != 200) throw new IOException("服务器返回 HTTP " + status);
                try (InputStream input = connection.getInputStream();
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int length;
                    while ((length = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("请求已取消");
                        if (output.size() + length > limit) throw new IOException("返回内容过大");
                        output.write(buffer, 0, length);
                    }
                    return output.toByteArray();
                }
            } finally { connection.disconnect(); }
        }
        throw new IOException("网站重定向次数过多");
    }
}
