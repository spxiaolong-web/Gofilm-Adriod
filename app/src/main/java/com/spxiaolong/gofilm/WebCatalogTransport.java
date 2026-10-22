package com.spxiaolong.gofilm;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Reuses Chromium networking without exposing a JavaScript-to-Java bridge. */
final class WebCatalogTransport {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final CountDownLatch ready = new CountDownLatch(1);
    private final WebView browser;
    private volatile boolean closed;
    private volatile Pending pending;
    private int sequence;

    private static final class Pending {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> body = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        String key;
        void fail(Exception error) { failure.set(error); done.countDown(); }
    }

    @SuppressLint("SetJavaScriptEnabled")
    WebCatalogTransport(Activity activity, ViewGroup parent) {
        browser = new WebView(activity);
        browser.setFocusable(false);
        browser.setClickable(false);
        browser.setAlpha(0f);
        browser.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        parent.addView(browser, new ViewGroup.LayoutParams(1, 1));
        browser.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) { ready.countDown(); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
        });
        // No remote scripts or HTML are loaded. The base sets the same HTTPS origin
        // as the working website; only public catalogue XHR requests are permitted.
        browser.loadDataWithBaseURL(TvApi.HOME + "/",
                "<!doctype html><html><head><meta charset='utf-8'>"
                        + "<meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; connect-src "
                        + TvApi.HOME + ";\"></head><body></body></html>",
                "text/html", "UTF-8", null);
    }

    // Called on the catalogue worker, never on the main thread.
    String get(String path) throws Exception {
        CatalogEndpoint.requirePath(path);
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("需要后台请求");
        if (!ready.await(10, TimeUnit.SECONDS) || closed) throw new IOException("浏览器网络模块初始化超时");
        Pending call = new Pending();
        pending = call;
        ui.post(() -> start(call, path));
        try {
            if (!call.done.await(30, TimeUnit.SECONDS)) throw new IOException("浏览器接口请求超时");
            if (call.failure.get() != null) throw call.failure.get();
            if (call.body.get() == null) throw new IOException("浏览器没有返回数据");
            return call.body.get();
        } finally {
            call.done.countDown();
            ui.post(() -> {
                if (!closed && call.key != null) browser.evaluateJavascript(
                        "if(window.__tvCalls){var c=window.__tvCalls[" + JSONObject.quote(call.key)
                                + "];if(c&&c.xhr)c.xhr.abort();delete window.__tvCalls["
                                + JSONObject.quote(call.key) + "];}", null);
            });
            if (pending == call) pending = null;
        }
    }

    private void start(Pending call, String path) {
        if (closed || call.done.getCount() == 0) { call.fail(new IOException("请求已取消")); return; }
        call.key = "request" + (++sequence);
        String key = JSONObject.quote(call.key);
        String script = "(function(){try{window.__tvCalls=window.__tvCalls||{};"
                + "var c={done:false};window.__tvCalls[" + key + "]=c;"
                + "var x=new XMLHttpRequest();c.xhr=x;x.open('GET'," + JSONObject.quote(path) + ",true);"
                + "x.timeout=22000;x.setRequestHeader('Accept','application/json');"
                + "x.onload=function(){c.done=true;c.status=x.status;"
                + "if(x.status===200&&x.responseText.length<=3145728){c.body=x.responseText;}"
                + "else{c.error=x.status===200?'接口返回内容过大':'浏览器接口 HTTP '+x.status;}};"
                + "x.onerror=function(){c.done=true;c.error='浏览器连接失败（XHR）';};"
                + "x.ontimeout=function(){c.done=true;c.error='浏览器接口请求超时';};"
                + "x.send();return 'started';}catch(e){return 'start-error';}})()";
        browser.evaluateJavascript(script, result -> {
            if (!"\"started\"".equals(result)) { call.fail(new IOException("浏览器接口初始化失败")); return; }
            poll(call);
        });
    }

    private void poll(Pending call) {
        if (closed || call.done.getCount() == 0) return;
        String key = JSONObject.quote(call.key);
        browser.evaluateJavascript("(function(){var c=window.__tvCalls&&window.__tvCalls[" + key
                + "];if(!c||!c.done)return null;return {body:c.body,error:c.error};})()", value -> {
            if (closed || call.done.getCount() == 0) return;
            if (value == null || "null".equals(value)) {
                ui.postDelayed(() -> poll(call), 150);
                return;
            }
            try {
                JSONObject result = new JSONObject(value);
                if (result.has("error")) call.fail(new IOException(result.optString("error")));
                else { call.body.set(result.getString("body")); call.done.countDown(); }
            } catch (Exception exception) { call.fail(new IOException("浏览器返回的数据格式异常", exception)); }
        });
    }

    void close() {
        closed = true;
        ready.countDown();
        if (pending != null) pending.fail(new IOException("页面已关闭"));
        ui.removeCallbacksAndMessages(null);
        if (browser.getParent() instanceof ViewGroup) ((ViewGroup) browser.getParent()).removeView(browser);
        browser.stopLoading();
        browser.destroy();
    }
}
