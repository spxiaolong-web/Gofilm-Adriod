package com.spxiaolong.gofilm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Native TV catalogue. Playback is handed to the existing website player. */
public final class TvCatalogActivity extends Activity {
    private static final int BG = Color.rgb(9, 17, 31);
    private static final int PANEL = Color.rgb(23, 35, 54);
    private static final int GOLD = Color.rgb(255, 211, 105);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService pictures = Executors.newFixedThreadPool(3);
    private final TvApi api = new TvApi();
    private final ArrayList<Screen> stack = new ArrayList<>();
    private final ArrayList<Future<?>> imageTasks = new ArrayList<>();
    private Future<?> request;
    private WebCatalogTransport webTransport;
    private FrameLayout host;
    private ProgressBar loading;
    private ScrollView scroll;
    private LinearLayout body;
    private Button homeButton;
    private JSONArray categories = new JSONArray();
    private int generation;

    private static final class Screen {
        final String kind, title, path;
        JSONObject data;
        String focus = "";
        int scrollY, source, episodePage;
        Screen(String kind, String title, String path) {
            this.kind = kind; this.title = title; this.path = path;
        }
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = column();
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(16, 29, 49), BG, BG}));
        root.setPadding(dp(40), dp(20), dp(40), dp(12));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(dp(40) + bars.left, dp(20) + bars.top,
                        dp(40) + bars.right, dp(12) + bars.bottom);
            } else {
                view.setPadding(dp(40) + insets.getSystemWindowInsetLeft(), dp(20) + insets.getSystemWindowInsetTop(),
                        dp(40) + insets.getSystemWindowInsetRight(), dp(12) + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(root);
        webTransport = new WebCatalogTransport(this, root);

        LinearLayout header = row();
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.dragon_artwork);
        header.addView(logo, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout brand = column();
        brand.setPadding(dp(12), 0, 0, 0);
        brand.addView(text("小龙影视", 25, Color.WHITE, true));
        brand.addView(text("方向键选择 · 确认键打开 · 返回键返回", 12, Color.rgb(167, 181, 202), false));
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        homeButton = button("首页", () -> goHome());
        header.addView(homeButton);
        header.addView(button("搜索", this::searchDialog));
        header.addView(button("网页模式", () -> openWeb(TvApi.HOME)));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(64)));
        loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loading.setIndeterminate(true);
        root.addView(loading, new LinearLayout.LayoutParams(-1, dp(3)));
        host = new FrameLayout(this);
        root.addView(host, new LinearLayout.LayoutParams(-1, 0, 1));
        if (saved != null) {
            try {
                JSONArray states = new JSONArray(saved.getString("screens", "[]"));
                for (int i = 0; i < states.length(); i++) {
                    JSONObject value = states.getJSONObject(i);
                    Screen screen = new Screen(value.getString("kind"), value.getString("title"), value.getString("path"));
                    screen.focus = value.optString("focus");
                    screen.scrollY = value.optInt("scrollY");
                    screen.source = value.optInt("source");
                    screen.episodePage = value.optInt("episodePage");
                    stack.add(screen);
                }
                categories = new JSONArray(saved.getString("categories", "[]"));
            } catch (Exception ignored) { stack.clear(); }
        }
        if (stack.isEmpty()) stack.add(new Screen("home", "首页", "/api/index"));
        load(current());
    }

    private Screen current() { return stack.get(stack.size() - 1); }

    private void remember() {
        Screen screen = current();
        if (scroll != null) screen.scrollY = scroll.getScrollY();
        View focused = getCurrentFocus();
        if (focused != null && focused.getTag() instanceof String) screen.focus = (String) focused.getTag();
    }

    private void push(Screen screen) {
        remember();
        stack.add(screen);
        load(screen);
    }

    private void goHome() {
        remember();
        Screen home = stack.get(0);
        stack.clear();
        stack.add(home);
        load(home);
    }

    private void load(Screen screen) {
        final int token = ++generation;
        if (request != null) request.cancel(true);
        for (Future<?> task : imageTasks) task.cancel(true);
        imageTasks.clear();
        host.removeAllViews();
        scroll = null;
        loading.setVisibility(View.VISIBLE);
        homeButton.requestFocus();
        if (screen.data != null) { render(screen); return; }
        request = network.submit(() -> {
            try {
                JSONObject data;
                try {
                    data = api.decode(screen.path, webTransport.get(screen.path));
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception browserFailure) {
                    if (Thread.currentThread().isInterrupted()) return;
                    try {
                        data = api.get(screen.path);
                    } catch (Exception systemFailure) {
                        throw new java.io.IOException("网页通道：" + safeMessage(browserFailure)
                                + "\n系统通道：" + safeMessage(systemFailure));
                    }
                }
                final JSONObject loadedData = data;
                ui.post(() -> {
                    if (isFinishing() || isDestroyed() || token != generation) return;
                    screen.data = loadedData;
                    render(screen);
                });
            } catch (Exception exception) {
                ui.post(() -> {
                    if (isFinishing() || isDestroyed() || token != generation) return;
                    loading.setVisibility(View.GONE);
                    LinearLayout panel = column();
                    panel.setGravity(Gravity.CENTER);
                    panel.addView(text("暂时没有加载成功", 26, Color.WHITE, true));
                    TextView message = text(safeMessage(exception), 16, Color.LTGRAY, false);
                    message.setMaxWidth(dp(740));
                    message.setGravity(Gravity.CENTER);
                    message.setPadding(0, dp(16), 0, dp(20));
                    panel.addView(message);
                    Button retry = button("重新加载", () -> load(screen));
                    panel.addView(retry);
                    host.addView(panel, new FrameLayout.LayoutParams(-1, -1));
                    retry.requestFocus();
                });
            }
        });
    }

    private String safeMessage(Exception exception) {
        if (exception instanceof javax.net.ssl.SSLException) return "安全连接失败（TLS/系统证书）";
        if (exception instanceof java.net.UnknownHostException) return "域名解析失败（DNS）";
        if (exception instanceof java.net.SocketTimeoutException) return "服务器连接超时";
        if (exception instanceof java.net.ConnectException) return "无法连接服务器";
        if (exception instanceof org.json.JSONException) return "接口未返回有效的 JSON 数据";
        if (exception instanceof java.io.InterruptedIOException) return "网络请求被中断";
        String message = exception.getMessage();
        if (message != null && (message.startsWith("服务器返回 HTTP") || message.startsWith("网站没有")
                || message.startsWith("浏览器") || message.startsWith("网页通道：")
                || message.startsWith("返回内容过大"))) return message;
        return "请求失败（" + exception.getClass().getSimpleName() + "）";
    }

    private void render(Screen screen) {
        final int renderToken = ++generation;
        for (Future<?> task : imageTasks) task.cancel(true);
        imageTasks.clear();
        loading.setVisibility(View.GONE);
        host.removeAllViews();
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        body = column();
        body.setPadding(dp(6), dp(12), dp(6), dp(24));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        host.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        if (screen.kind.equals("home")) renderHome(screen);
        else if (screen.kind.equals("detail")) renderDetail(screen);
        else renderList(screen);
        body.post(() -> {
            if (isDestroyed() || current() != screen || generation != renderToken) return;
            View saved = screen.focus.isEmpty() ? null : body.findViewWithTag(screen.focus);
            if (saved != null) saved.requestFocus();
            else {
                View first = firstAction(body);
                if (first != null) first.requestFocus();
            }
            if (screen.scrollY > 0) scroll.scrollTo(0, screen.scrollY);
        });
    }

    private void renderHome(Screen screen) {
        JSONObject category = screen.data.optJSONObject("category");
        categories = category == null ? new JSONArray() : array(category, "children");
        LinearLayout tabs = row();
        for (int i = 0; i < categories.length(); i++) {
            JSONObject item = categories.optJSONObject(i);
            if (item == null || !item.optBoolean("show", true)) continue;
            String id = item.optString("id");
            String name = item.optString("name", "分类");
            tabs.addView(button(name, () -> category(id, name)));
        }
        horizontal(body, tabs);
        TextView heading = text("发现好故事", 30, Color.WHITE, true);
        heading.setPadding(dp(4), dp(20), 0, 0);
        body.addView(heading);
        JSONArray sections = array(screen.data, "content");
        int shown = 0;
        for (int i = 0; i < sections.length(); i++) {
            JSONObject section = sections.optJSONObject(i);
            if (section == null) continue;
            JSONObject nav = section.optJSONObject("nav");
            JSONArray movies = array(section, "movies");
            if (nav == null || movies.length() == 0) continue;
            shown++;
            String id = nav.optString("id");
            String name = nav.optString("name", "影片");
            LinearLayout headingRow = row();
            headingRow.setPadding(dp(4), dp(16), 0, dp(6));
            headingRow.addView(text(name, 22, Color.WHITE, true), new LinearLayout.LayoutParams(0, -2, 1));
            headingRow.addView(button("查看全部", () -> category(id, name)));
            body.addView(headingRow);
            LinearLayout cards = row();
            for (int j = 0; j < movies.length(); j++) {
                JSONObject movie = movies.optJSONObject(j);
                if (movie != null) cards.addView(card(movie, "home:" + id + ":" + j), cardParams());
            }
            horizontal(body, cards);
        }
        if (shown == 0) body.addView(text("暂无影片，请稍后刷新", 20, Color.LTGRAY, false));
        body.addView(button("刷新片库", () -> { screen.data = null; screen.focus = ""; load(screen); }));
    }

    private void category(String id, String name) {
        push(new Screen("list", name, "/api/filmClassifySearch?Pid=" + Uri.encode(id) + "&current=1"));
    }

    private void renderList(Screen screen) {
        body.addView(text(screen.title, 28, Color.WHITE, true));
        JSONArray movies = array(screen.data, "list");
        if (movies.length() == 0) body.addView(text("没有找到相关影片，试试其他关键词", 20, Color.LTGRAY, false));
        grid(movies, 5, (movie, index) -> card(movie, "list:" + index));
        JSONObject page = screen.data.optJSONObject("page");
        int currentPage = page == null ? 1 : Math.max(1, page.optInt("current", 1));
        int pageCount = page == null ? 1 : Math.max(1, page.optInt("pageCount", 1));
        LinearLayout pages = row();
        pages.setPadding(0, dp(20), 0, 0);
        if (currentPage > 1) pages.addView(button("上一页", () -> changePage(screen, currentPage - 1)));
        pages.addView(text("第 " + currentPage + " / " + pageCount + " 页", 16, Color.LTGRAY, false));
        if (currentPage < pageCount) pages.addView(button("下一页", () -> changePage(screen, currentPage + 1)));
        body.addView(pages);
    }

    private void changePage(Screen screen, int number) {
        screen.data = null;
        screen.scrollY = 0;
        screen.focus = "";
        String path = screen.path.replaceAll("&current=\\d+", "&current=" + number);
        Screen replacement = new Screen(screen.kind, screen.title, path);
        stack.set(stack.size() - 1, replacement);
        load(replacement);
    }

    private void detail(JSONObject movie) {
        String id = movie.optString("id");
        push(new Screen("detail", movie.optString("name"), "/api/filmDetail?id=" + Uri.encode(id)));
    }

    private void renderDetail(Screen screen) {
        JSONObject detail = screen.data.optJSONObject("detail");
        if (detail == null) { body.addView(text("影片信息暂不可用", 24, Color.WHITE, true)); return; }
        LinearLayout top = row();
        top.setGravity(Gravity.TOP);
        ImageView cover = poster(detail.optString("picture"));
        top.addView(cover, new LinearLayout.LayoutParams(dp(138), dp(195)));
        LinearLayout info = column();
        info.setPadding(dp(24), 0, dp(8), 0);
        info.addView(text(detail.optString("name", screen.title), 28, Color.WHITE, true));
        TextView meta = text(detail.optString("year") + "  ·  " + detail.optString("area")
                + "  ·  " + detail.optString("cName") + "  " + detail.optString("remarks"), 16, GOLD, false);
        meta.setPadding(0, dp(10), 0, dp(12));
        info.addView(meta);
        String plain = Html.fromHtml(detail.optString("content", "暂无简介"), Html.FROM_HTML_MODE_LEGACY).toString().trim();
        TextView synopsis = text(plain, 16, Color.rgb(182, 195, 212), false);
        synopsis.setMaxLines(4);
        synopsis.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(synopsis);
        JSONArray sources = array(detail, "list");
        if (sources.length() > 0) {
            screen.source = Math.max(0, Math.min(screen.source, sources.length() - 1));
            JSONObject source = sources.optJSONObject(screen.source);
            if (source != null && array(source, "linkList").length() > 0) {
                Button play = button("立即播放", () -> play(detail, source, 0));
                play.setTag("play");
                LinearLayout.LayoutParams playSize = new LinearLayout.LayoutParams(dp(160), dp(48));
                playSize.topMargin = dp(16);
                info.addView(play, playSize);
            }
        }
        top.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(top);
        sectionTitle("播放线路");
        LinearLayout tabs = row();
        for (int i = 0; i < sources.length(); i++) {
            JSONObject source = sources.optJSONObject(i);
            if (source == null) continue;
            final int index = i;
            Button tab = button((i == screen.source ? "✓ " : "") + source.optString("name", "线路 " + (i + 1)), () -> {
                screen.source = index;
                screen.episodePage = 0;
                screen.focus = "source:" + index;
                screen.scrollY = scroll.getScrollY();
                render(screen);
            });
            tab.setTag("source:" + i);
            tabs.addView(tab);
        }
        horizontal(body, tabs);
        sectionTitle("选择剧集");
        JSONObject source = sources.optJSONObject(screen.source);
        JSONArray episodes = source == null ? new JSONArray() : array(source, "linkList");
        int start = Math.min(screen.episodePage * 60, Math.max(0, ((episodes.length() - 1) / 60) * 60));
        JSONArray batch = new JSONArray();
        for (int i = start; i < Math.min(episodes.length(), start + 60); i++) batch.put(episodes.optJSONObject(i));
        final int offset = start;
        grid(batch, 6, (episode, index) -> {
            Button choice = button(episode.optString("episode", "第 " + (offset + index + 1) + " 集"),
                    () -> play(detail, source, offset + index));
            choice.setTag("episode:" + (offset + index));
            choice.setMaxLines(2);
            choice.setEllipsize(TextUtils.TruncateAt.END);
            choice.setMinimumHeight(dp(52));
            return choice;
        });
        if (episodes.length() == 0) body.addView(text("暂无可播放剧集，可尝试其他线路", 18, Color.LTGRAY, false));
        LinearLayout pages = row();
        if (start > 0) pages.addView(button("前 60 集", () -> episodePage(screen, -1)));
        if (start + 60 < episodes.length()) pages.addView(button("后 60 集", () -> episodePage(screen, 1)));
        body.addView(pages);
    }

    private void episodePage(Screen screen, int delta) {
        screen.episodePage = Math.max(0, screen.episodePage + delta);
        screen.focus = "episode:" + (screen.episodePage * 60);
        screen.scrollY = 0;
        render(screen);
    }

    private void play(JSONObject detail, JSONObject source, int episode) {
        if (source == null) return;
        String id = detail.optString("mid", detail.optString("id"));
        String url = Uri.parse(TvApi.HOME + "/play").buildUpon()
                .appendQueryParameter("id", id).appendQueryParameter("source", source.optString("id"))
                .appendQueryParameter("episode", String.valueOf(episode)).build().toString();
        remember();
        openWeb(url);
    }

    private void openWeb(String url) {
        startActivity(new Intent(this, MainActivity.class).putExtra("web_player", true)
                .putExtra("from_tv_catalog", true).putExtra("start_url", url));
    }

    private void searchDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("输入影片名称");
        input.setTextSize(20);
        input.setPadding(dp(20), dp(16), dp(20), dp(16));
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("搜索影片").setView(input)
                .setPositiveButton("搜索", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(ignored -> {
            Runnable submit = () -> {
                String keyword = input.getText().toString().trim();
                if (keyword.isEmpty()) { input.setError("请输入影片名称"); return; }
                InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (manager != null) manager.hideSoftInputFromWindow(input.getWindowToken(), 0);
                dialog.dismiss();
                push(new Screen("list", "搜索：" + keyword,
                        "/api/searchFilm?keyword=" + Uri.encode(keyword) + "&current=1"));
            };
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> submit.run());
            input.setOnEditorActionListener((v, action, event) -> {
                if (action == EditorInfo.IME_ACTION_SEARCH) { submit.run(); return true; }
                return false;
            });
            input.requestFocus();
        });
        dialog.show();
    }

    private interface CellFactory { View make(JSONObject item, int index); }

    private void grid(JSONArray items, int columns, CellFactory factory) {
        LinearLayout line = null;
        for (int i = 0; i < items.length(); i++) {
            if (i % columns == 0) {
                line = row();
                line.setGravity(Gravity.TOP);
                body.addView(line, new LinearLayout.LayoutParams(-1, -2));
            }
            JSONObject item = items.optJSONObject(i);
            View view = item == null ? new View(this) : factory.make(item, i);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
            size.setMargins(dp(5), dp(8), dp(5), dp(8));
            line.addView(view, size);
        }
        if (line != null) while (line.getChildCount() < columns) line.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    }

    private View card(JSONObject movie, String key) {
        LinearLayout card = column();
        card.setPadding(dp(5), dp(5), dp(5), dp(8));
        ImageView image = poster(movie.optString("picture"));
        card.addView(image, new LinearLayout.LayoutParams(-1, dp(160)));
        TextView name = text(movie.optString("name", "未命名影片"), 16, Color.WHITE, true);
        name.setPadding(dp(4), dp(8), dp(4), 0);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(name);
        TextView meta = text(movie.optString("remarks", movie.optString("year")), 12, Color.LTGRAY, false);
        meta.setPadding(dp(4), dp(4), dp(4), 0);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(meta);
        card.setTag(key);
        card.setContentDescription(movie.optString("name"));
        focusStyle(card);
        card.setOnClickListener(v -> detail(movie));
        return card;
    }

    private ImageView poster(String url) {
        ImageView image = new ImageView(this);
        image.setImageResource(R.drawable.dragon_artwork);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(PANEL);
        final int token = generation;
        imageTasks.add(pictures.submit(() -> {
            try {
                Bitmap bitmap = api.image(url);
                if (bitmap != null) ui.post(() -> {
                    if (!isDestroyed() && generation == token) image.setImageBitmap(bitmap);
                });
            } catch (Exception ignored) { /* Keep the dragon placeholder. */ }
        }));
        return image;
    }

    private void sectionTitle(String value) {
        TextView title = text(value, 22, Color.WHITE, true);
        title.setPadding(dp(4), dp(24), 0, dp(12));
        body.addView(title);
    }

    private void horizontal(LinearLayout parent, LinearLayout line) {
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        horizontal.setClipToPadding(false);
        horizontal.setPadding(dp(4), dp(4), dp(4), dp(4));
        horizontal.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        horizontal.addView(line, new FrameLayout.LayoutParams(-2, -2));
        parent.addView(horizontal, new LinearLayout.LayoutParams(-1, -2));
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setMinWidth(dp(72));
        button.setMinimumWidth(dp(72));
        button.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-2, -2);
        size.setMargins(dp(4), dp(4), dp(4), dp(4));
        button.setLayoutParams(size);
        focusStyle(button);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void focusStyle(View view) {
        view.setId(View.generateViewId());
        view.setFocusable(true);
        view.setClickable(true);
        view.setBackground(background(false));
        view.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(background(focused));
            v.setElevation(focused ? dp(6) : 0);
            if (v instanceof Button) ((Button) v).setTextColor(focused ? GOLD : Color.WHITE);
        });
    }

    private GradientDrawable background(boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(10));
        drawable.setColor(focused ? Color.rgb(65, 57, 37) : PANEL);
        drawable.setStroke(dp(focused ? 3 : 1), focused ? GOLD : Color.rgb(42, 56, 76));
        return drawable;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private View firstAction(View view) {
        if (view.isFocusable() && view.isClickable()) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = firstAction(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private JSONArray array(JSONObject value, String key) {
        JSONArray result = value.optJSONArray(key);
        return result == null ? new JSONArray() : result;
    }
    private LinearLayout column() { LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.VERTICAL); return value; }
    private LinearLayout row() { LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.HORIZONTAL); value.setGravity(Gravity.CENTER_VERTICAL); return value; }
    private LinearLayout.LayoutParams cardParams() { LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(128), -2); size.setMargins(dp(5), dp(5), dp(5), dp(8)); return size; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override @SuppressWarnings("deprecation") public void onBackPressed() {
        if (stack.size() <= 1) { super.onBackPressed(); return; }
        stack.remove(stack.size() - 1);
        load(current());
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        remember();
        JSONArray screens = new JSONArray();
        for (Screen screen : stack) {
            try {
                JSONObject value = new JSONObject();
                value.put("kind", screen.kind).put("title", screen.title).put("path", screen.path)
                        .put("focus", screen.focus).put("scrollY", screen.scrollY)
                        .put("source", screen.source).put("episodePage", screen.episodePage);
                screens.put(value);
            } catch (Exception ignored) { }
        }
        state.putString("screens", screens.toString());
        state.putString("categories", categories.toString());
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        generation++;
        if (webTransport != null) webTransport.close();
        network.shutdownNow();
        pictures.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
