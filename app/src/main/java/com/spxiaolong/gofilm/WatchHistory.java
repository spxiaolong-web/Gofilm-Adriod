package com.spxiaolong.gofilm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/** Local playback positions, shared by both app layouts. */
final class WatchHistory {
    private static final Pattern KEY = Pattern.compile("[1-9][0-9]{0,15}/[A-Za-z0-9_-]{1,80}/[0-9]{1,7}");
    private static final int LIMIT = 100;
    private final SharedPreferences prefs;
    WatchHistory(Context context) {
        prefs = context.getSharedPreferences("gofilm_watch_history", Context.MODE_PRIVATE);
    }

    static final class Entry {
        final String key, title;
        final double position, duration;
        final long updated;
        final boolean completed;
        Entry(String key, JSONObject value) {
            this.key = key;
            String name = value.optString("title").trim();
            this.title = name.isEmpty() ? fallbackTitle(key) : name;
            this.position = value.optDouble("position", 0);
            this.duration = value.optDouble("duration", 0);
            this.updated = value.optLong("updated");
            this.completed = value.optBoolean("completed");
        }
        String url() {
            String[] parts = key.split("/");
            return Uri.parse(TvApi.HOME + "/play").buildUpon()
                    .appendQueryParameter("id", parts[0])
                    .appendQueryParameter("source", parts[1])
                    .appendQueryParameter("episode", parts[2]).build().toString();
        }
        String label() {
            return title + "  ·  " + (completed ? "已看完" : format(position) + " / " + format(duration));
        }
    }

    void save(JSONObject sample, String titleHint, String hintKey) {
        String key = sample.optString("key");
        if (!KEY.matcher(key).matches()) return;
        double position = sample.optDouble("position"), duration = sample.optDouble("duration");
        if (!Double.isFinite(position) || !Double.isFinite(duration)
                || position < 1 || duration < 3 || position > duration + 1) return;
        boolean completed = sample.optBoolean("completed") && duration - position <= 2;
        Entry old = get(key);
        String title = sample.optString("title").replaceAll("\\s+", " ").trim();
        if (title.isEmpty() && key.equals(hintKey) && titleHint != null) title = titleHint.trim();
        if (title.isEmpty() && old != null) title = old.title;
        title = title.substring(0, Math.min(title.length(), 160));
        if (old != null && Math.abs(old.position - position) < 0.8
                && old.completed == completed && (title.isEmpty() || old.title.equals(title))) return;
        SharedPreferences.Editor edit = prefs.edit();
        try {
            JSONObject value = new JSONObject();
            value.put("title", title).put("position", position).put("duration", duration)
                    .put("completed", completed).put("updated", System.currentTimeMillis());
            edit.putString(key, value.toString());
            if (old == null && prefs.getAll().size() >= LIMIT) {
                ArrayList<Entry> items = entries();
                for (int i = LIMIT - 1; i < items.size(); i++) edit.remove(items.get(i).key);
            }
            edit.apply();
        } catch (org.json.JSONException ignored) { }
    }

    JSONObject positions() {
        JSONObject values = new JSONObject();
        for (Entry e : entries()) if (!e.completed) {
            try { values.put(e.key, e.position); } catch (org.json.JSONException ignored) { }
        }
        return values;
    }

    private Entry get(String key) {
        try {
            String value = prefs.getString(key, null);
            return value == null ? null : new Entry(key, new JSONObject(value));
        } catch (org.json.JSONException | ClassCastException ignored) { return null; }
    }

    private ArrayList<Entry> entries() {
        ArrayList<Entry> entries = new ArrayList<>();
        for (String key : prefs.getAll().keySet()) if (KEY.matcher(key).matches()) {
            Entry entry = get(key);
            if (entry != null) entries.add(entry);
        }
        entries.sort((a,b) -> Long.compare(b.updated, a.updated));
        return entries;
    }

    void show(Activity activity, BiConsumer<Entry, Boolean> open) {
        ArrayList<Entry> items = entries();
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setTitle("观看历史");
        if (items.isEmpty()) dialog.setMessage("还没有观看记录。开始播放后，进度会自动保存在这台设备上。");
        else {
            String[] labels = new String[items.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = items.get(i).label();
            dialog.setItems(labels, (list, index) -> {
                Entry entry = items.get(index);
                AlertDialog.Builder choice = new AlertDialog.Builder(activity).setTitle(entry.title)
                        .setMessage(entry.label()).setNegativeButton("取消", null);
                if (entry.completed) choice.setPositiveButton("重新播放", (d,w) -> open.accept(entry, true));
                else choice.setPositiveButton("继续播放", (d,w) -> open.accept(entry, false))
                        .setNeutralButton("从头播放", (d,w) -> open.accept(entry, true));
                choice.show();
            });
        }
        dialog.setNegativeButton("关闭", null).show();
    }

    static String keyForUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"film.spxiaolong.com".equalsIgnoreCase(uri.getHost())
                    || (uri.getPort() != -1 && uri.getPort() != 443) || !"/play".equals(uri.getPath())) return "";
            String episode = uri.getQueryParameter("episode");
            String id = uri.getQueryParameter("id"), source = uri.getQueryParameter("source");
            if (id == null || source == null) return "";
            String key = id + "/" + source + "/" + (episode == null ? "0" : episode);
            return KEY.matcher(key).matches() ? key : "";
        } catch (RuntimeException ignored) { return ""; }
    }

    private static String fallbackTitle(String key) {
        String[] parts = key.split("/");
        return "影片 " + parts[0] + " · 第 " + (Integer.parseInt(parts[2]) + 1) + " 集";
    }

    private static String format(double seconds) {
        long s = Math.max(0, (long) seconds);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
