package com.spxiaolong.gofilm;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.view.KeyEvent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/** 第一课：用 Android 自带的控件搭一个网站客户端，无第三方依赖。 */
public final class MainActivity extends Activity {
    private static final String HOME = "https://film.spxiaolong.com";
    private static final int BACKGROUND = Color.rgb(20, 20, 24);
    private boolean television;
    private TvRemoteController tvRemote;
    private FrameLayout root;
    private FrameLayout fullScreen;
    private LinearLayout normalPage;
    private LinearLayout errorPanel;
    private WebView webView;
    private ProgressBar progress;
    private TextView errorMessage;
    private WebChromeClient.CustomViewCallback fullScreenCallback;
    private boolean mainPageFailed;
    private String retryUrl = HOME;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    public void onCreate(Bundle state) {
        super.onCreate(state);
        television = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK)
                == Configuration.UI_MODE_TYPE_TELEVISION
                || getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        television = getPreferences(MODE_PRIVATE).getBoolean("television_mode", television);
        if (television) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        else setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND);
        setContentView(root);
        applySystemInsets();

        normalPage = new LinearLayout(this);
        normalPage.setOrientation(LinearLayout.VERTICAL);
        root.addView(normalPage, matchParent());

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(4), 0, dp(4), 0);
        toolbar.addView(button("返回", v -> navigateBack()));
        TextView title = new TextView(this);
        title.setText("小龙影视");
        title.setTextSize(17);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        toolbar.addView(button("首页", v -> webView.loadUrl(HOME)));
        toolbar.addView(button("刷新", v -> retry()));
        toolbar.addView(button(television ? "触屏" : "电视", v -> {
            getPreferences(MODE_PRIVATE).edit().putBoolean("television_mode", !television).apply();
            recreate();
        }));
        if (television) toolbar.addView(button("遥控", v -> {
            if (tvRemote != null) tvRemote.toggle();
        }));
        normalPage.addView(toolbar);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        normalPage.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        FrameLayout content = new FrameLayout(this);
        normalPage.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        webView = new WebView(this);
        webView.setBackgroundColor(BACKGROUND);
        content.addView(webView, matchParent());

        errorPanel = new LinearLayout(this);
        errorPanel.setOrientation(LinearLayout.VERTICAL);
        errorPanel.setGravity(Gravity.CENTER);
        errorPanel.setPadding(dp(24), dp(24), dp(24), dp(24));
        errorPanel.setBackgroundColor(BACKGROUND);
        errorMessage = new TextView(this);
        errorMessage.setTextColor(Color.WHITE);
        errorMessage.setGravity(Gravity.CENTER);
        errorPanel.addView(errorMessage);
        errorPanel.addView(button("重新加载", v -> retry()));
        content.addView(errorPanel, matchParent());
        errorPanel.setVisibility(View.GONE);

        WebSettings settings = webView.getSettings();
        // Vue 网站需要 JavaScript 和浏览器本地存储。
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                mainPageFailed = false;
                retryUrl = url;
                errorPanel.setVisibility(View.GONE);
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showPageError("页面暂时无法打开。请检查网络后重试。\n错误码：" + error.getErrorCode());
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame()) {
                    showPageError("网站返回 HTTP " + response.getStatusCode() + "，请稍后重试。");
                }
            }
            // 不覆盖证书错误处理：WebView 默认会阻止无效证书。
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value);
                progress.setVisibility(value == 100 || mainPageFailed ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                enterFullScreen(view, callback);
            }

            @Override
            public void onHideCustomView() {
                exitFullScreen();
            }
        });

        if (television) {
            // Keep the page readable at television distance.
            settings.setTextZoom(115);
            tvRemote = new TvRemoteController(this, root,
                    () -> { if (fullScreen == null) webView.scrollBy(0, -dp(120)); },
                    () -> { if (fullScreen == null) webView.scrollBy(0, dp(120)); });
            Toast.makeText(this, "方向键移动金色光标，确认键点击，返回键返回；菜单键切换遥控模式",
                    Toast.LENGTH_LONG).show();
        }

        if (state == null || webView.restoreState(state) == null) {
            webView.loadUrl(HOME);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (tvRemote != null && tvRemote.handle(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private boolean handleNavigation(Uri uri) {
        String scheme = uri.getScheme();
        if ("https".equalsIgnoreCase(scheme)
                && "film.spxiaolong.com".equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443)) {
            return false;
        }
        if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "没有可打开外部链接的浏览器", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "此链接类型暂不支持", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void showPageError(String message) {
        mainPageFailed = true;
        progress.setVisibility(View.GONE);
        errorMessage.setText(message);
        errorPanel.setVisibility(View.VISIBLE);
    }

    private void retry() {
        errorPanel.setVisibility(View.GONE);
        webView.loadUrl(retryUrl);
    }

    private void enterFullScreen(View video, WebChromeClient.CustomViewCallback callback) {
        if (fullScreen != null) {
            callback.onCustomViewHidden();
            return;
        }
        fullScreenCallback = callback;
        fullScreen = new FrameLayout(this);
        fullScreen.setBackgroundColor(Color.BLACK);
        fullScreen.addView(video, matchParent());
        Button exit = button("退出全屏", v -> exitFullScreen());
        FrameLayout.LayoutParams exitParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        exitParams.topMargin = dp(24);
        exitParams.rightMargin = dp(12);
        fullScreen.addView(exit, exitParams);
        root.addView(fullScreen, matchParent());
        if (tvRemote != null) tvRemote.bringToFront();
        normalPage.setVisibility(View.GONE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersiveMode(true);
    }

    private void exitFullScreen() {
        if (fullScreen == null) return;
        root.removeView(fullScreen);
        fullScreen.removeAllViews();
        fullScreen = null;
        normalPage.setVisibility(View.VISIBLE);
        if (!television) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersiveMode(false);
        WebChromeClient.CustomViewCallback callback = fullScreenCallback;
        fullScreenCallback = null;
        if (callback != null) callback.onCustomViewHidden();
    }

    @SuppressWarnings("deprecation")
    private void applyImmersiveMode(boolean enabled) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                if (enabled) {
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    controller.hide(WindowInsets.Type.systemBars());
                } else {
                    controller.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(enabled
                    ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : View.SYSTEM_UI_FLAG_VISIBLE);
        }
        root.requestApplyInsets();
    }

    private void applySystemInsets() {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
    }

    @SuppressWarnings("deprecation")
    private void navigateBack() {
        if (fullScreen != null) exitFullScreen();
        else if (webView.canGoBack()) {
            errorPanel.setVisibility(View.GONE);
            webView.goBack();
        } else super.onBackPressed();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() { navigateBack(); }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        webView.saveState(state);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onPause() {
        exitFullScreen();
        webView.onPause();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        if (television) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            ((ViewGroup) webView.getParent()).removeView(webView);
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setOnClickListener(listener);
        return b;
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
