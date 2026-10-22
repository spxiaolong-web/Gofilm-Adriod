package com.spxiaolong.gofilm;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Focusable TV buttons control the existing HTML video without a native bridge. */
final class TvPlaybackControls {
    private final Activity activity;
    private final WebView browser;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final FrameLayout overlay;
    private final TextView status;
    private final ProgressBar timeline;
    private final Button play;
    private String script;
    private boolean active;
    private boolean closed;
    private boolean ready;
    private long lastInteraction;

    TvPlaybackControls(Activity activity, FrameLayout root, WebView browser, String title, Runnable classicPlayer) {
        this.activity = activity;
        this.browser = browser;
        overlay = new FrameLayout(activity);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(32), dp(20), dp(32), dp(24));
        panel.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.TRANSPARENT, Color.argb(242, 8, 15, 26)}));
        overlay.addView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        TextView name = new TextView(activity);
        name.setText(title == null || title.isEmpty() ? "小龙影视" : title);
        name.setTextColor(Color.WHITE);
        name.setTextSize(21);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        panel.addView(name);
        status = new TextView(activity);
        status.setText("正在准备播放器…");
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(15);
        status.setPadding(0, dp(8), 0, dp(10));
        panel.addView(status);
        timeline = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        timeline.setMax(1000);
        panel.addView(timeline, new LinearLayout.LayoutParams(-1, dp(5)));
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, dp(12), 0, 0);
        buttons.addView(button("快退 10 秒", () -> command("rewind")));
        play = button("播放 / 暂停", () -> command("toggle"));
        buttons.addView(play);
        buttons.addView(button("快进 10 秒", () -> command("forward")));
        buttons.addView(button("选集 / 换线路", activity::finish));
        buttons.addView(button("重新加载", () -> { pageStarted(); browser.reload(); }));
        buttons.addView(button("网页播放", classicPlayer));
        panel.addView(buttons);
        TextView hint = new TextView(activity);
        hint.setText("按钮隐藏时：确认键播放/暂停 · 左右键快退/快进 · 下键显示按钮 · 返回键回选集");
        hint.setTextColor(Color.LTGRAY);
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(10), 0, 0);
        panel.addView(hint);
        try (InputStream input = activity.getAssets().open("tv-player.js");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096];
            int length;
            while ((length = input.read(bytes)) != -1) output.write(bytes, 0, length);
            script = output.toString(StandardCharsets.UTF_8.name());
        } catch (Exception exception) {
            status.setText("播放控制加载失败，可返回片库使用网页模式");
        }
        show();
    }

    void pageStarted() { ready = false; status.setText("正在加载影片…"); show(); }

    void install() {
        if (closed || !active || script == null) return;
        browser.evaluateJavascript(script, null);
    }

    void bringToFront() { overlay.bringToFront(); }

    private void command(String action) {
        lastInteraction = SystemClock.uptimeMillis();
        browser.evaluateJavascript("window.__tvPlayer ? window.__tvPlayer.command(" + JSONObject.quote(action) + ") : false", value -> {
            if (closed) return;
            if (!"true".equals(value)) {
                show();
                status.setText("播放器尚未就绪或当前视频暂不支持此操作，请稍候重试");
            }
        });
    }

    boolean handle(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (overlay.getVisibility() != View.VISIBLE) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) hide();
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_UP) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (code == KeyEvent.KEYCODE_MENU && overlay.getVisibility() == View.VISIBLE) hide();
                else show();
            }
            return true;
        }
        String action = null;
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PAUSE
                || code == KeyEvent.KEYCODE_MEDIA_PLAY) {
            action = code == KeyEvent.KEYCODE_MEDIA_PAUSE ? "pause" : code == KeyEvent.KEYCODE_MEDIA_PLAY ? "play" : "toggle";
        } else if (code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) action = "forward";
        else if (code == KeyEvent.KEYCODE_MEDIA_REWIND) action = "rewind";
        else if (overlay.getVisibility() != View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) action = "toggle";
            else if (code == KeyEvent.KEYCODE_DPAD_LEFT) action = "rewind";
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) action = "forward";
        }
        if (action != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                command(action);
                if (action.equals("forward") || action.equals("rewind")) Toast.makeText(activity,
                        action.equals("forward") ? "快进 10 秒" : "快退 10 秒", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) lastInteraction = SystemClock.uptimeMillis();
        return false;
    }

    private void show() {
        lastInteraction = SystemClock.uptimeMillis();
        overlay.setVisibility(View.VISIBLE);
        overlay.bringToFront();
        play.requestFocus();
    }
    private void hide() { overlay.setVisibility(View.GONE); }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active || closed) return;
            browser.evaluateJavascript("window.__tvPlayer ? window.__tvPlayer.state() : null", value -> {
                if (!active || closed) return;
                try {
                    if (value == null || value.equals("null")) { install(); return; }
                    JSONObject state = new JSONObject(value);
                    ready = state.optBoolean("ready");
                    boolean paused = state.optBoolean("paused", true);
                    boolean buffering = state.optBoolean("loading", true);
                    double current = state.optDouble("current", 0), duration = state.optDouble("duration", 0);
                    String error = state.optString("error");
                    play.setText(paused ? "播放" : "暂停");
                    timeline.setProgress(duration > 0 ? (int) Math.min(1000, current * 1000 / duration) : 0);
                    if (!error.isEmpty()) { status.setText(error); showIfHidden(); }
                    else if (!ready || buffering) { status.setText("视频加载中，请稍候…"); showIfHidden(); }
                    else {
                        status.setText((state.optBoolean("ended") ? "播放结束  ·  " : paused ? "已暂停  ·  " : "")
                                + time(current) + " / " + (duration > 0 ? time(duration) : "直播"));
                        if (paused) showIfHidden();
                        else if (SystemClock.uptimeMillis() - lastInteraction > 5500) hide();
                    }
                } catch (Exception ignored) { status.setText("正在读取播放状态…"); }
            });
            ui.postDelayed(this, 1000);
        }
    };

    private void showIfHidden() { if (overlay.getVisibility() != View.VISIBLE) show(); }
    void resume() { if (closed) return; active = true; ui.removeCallbacks(tick); ui.post(tick); }
    void pause() {
        active = false;
        ui.removeCallbacks(tick);
        browser.evaluateJavascript("window.__tvPlayer && window.__tvPlayer.command('pause')", null);
    }
    void close() { closed = true; pause(); ui.removeCallbacksAndMessages(null); }

    private String time(double seconds) {
        long value = Math.max(0, (long) seconds);
        return value >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", value / 3600, value / 60 % 60, value % 60)
                : String.format(Locale.ROOT, "%02d:%02d", value / 60, value % 60);
    }

    private Button button(String title, Runnable action) {
        Button button = new Button(activity);
        button.setId(View.generateViewId());
        button.setText(title);
        button.setTextSize(17);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setPadding(dp(18), dp(8), dp(18), dp(8));
        button.setBackground(background(false));
        button.setOnFocusChangeListener((view, focused) -> {
            view.setBackground(background(focused));
            button.setTextColor(focused ? Color.BLACK : Color.WHITE);
        });
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, dp(52));
        size.setMargins(dp(5), 0, dp(5), 0);
        button.setLayoutParams(size);
        return button;
    }

    private GradientDrawable background(boolean focused) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(8));
        shape.setColor(focused ? Color.rgb(255, 211, 105) : Color.rgb(30, 43, 63));
        return shape;
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
