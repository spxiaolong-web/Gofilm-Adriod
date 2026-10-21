package com.spxiaolong.gofilm;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.Toast;

/** A local virtual pointer for web controls that do not expose D-pad focus. */
final class TvRemoteController {
    private final Activity activity;
    private final FrameLayout root;
    private final Pointer pointer;
    private final Runnable scrollUp;
    private final Runnable scrollDown;
    private boolean enabled = true;

    TvRemoteController(Activity activity, FrameLayout root, Runnable up, Runnable down) {
        this.activity = activity;
        this.root = root;
        scrollUp = up;
        scrollDown = down;
        pointer = new Pointer(activity);
        pointer.setFocusable(false);
        pointer.setClickable(false);
        pointer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(pointer, new FrameLayout.LayoutParams(-1, -1));
    }

    void bringToFront() { pointer.bringToFront(); }

    void toggle() {
        enabled = !enabled;
        pointer.setVisibility(enabled ? View.VISIBLE : View.GONE);
        Toast.makeText(activity, enabled
                ? "遥控光标：方向键移动，确认键点击；移到底部继续按可滚动"
                : "已切换普通按键；按菜单键或顶部遥控按钮恢复光标",
                Toast.LENGTH_LONG).show();
    }

    boolean handle(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_UP) toggle();
            return true;
        }
        if (!enabled || keyboardVisible()) return false;
        boolean direction = code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_DOWN;
        if (direction) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                float step = dp(event.getRepeatCount() > 8 ? 32 : 12);
                float dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -step
                        : code == KeyEvent.KEYCODE_DPAD_RIGHT ? step : 0;
                float dy = code == KeyEvent.KEYCODE_DPAD_UP ? -step
                        : code == KeyEvent.KEYCODE_DPAD_DOWN ? step : 0;
                if (dy > 0 && pointer.y >= root.getHeight() - root.getPaddingBottom() - dp(28)) {
                    scrollDown.run();
                } else if (dy < 0 && pointer.y <= root.getPaddingTop() + dp(80)) {
                    scrollUp.run();
                }
                pointer.move(dx, dy);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) click();
            return true;
        }
        if (code == KeyEvent.KEYCODE_PAGE_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN
                || code == KeyEvent.KEYCODE_PAGE_UP || code == KeyEvent.KEYCODE_CHANNEL_UP) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (code == KeyEvent.KEYCODE_PAGE_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN) scrollDown.run();
                else scrollUp.run();
            }
            return true;
        }
        return false;
    }

    private void click() {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, pointer.x, pointer.y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, pointer.x, pointer.y, 0);
        root.dispatchTouchEvent(down);
        root.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    private boolean keyboardVisible() {
        WindowInsets insets = root.getRootWindowInsets();
        if (Build.VERSION.SDK_INT >= 30 && insets != null) {
            return insets.isVisible(WindowInsets.Type.ime());
        }
        Rect visible = new Rect();
        root.getWindowVisibleDisplayFrame(visible);
        return root.getHeight() - visible.height() > dp(150);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private final class Pointer extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float x;
        private float y;

        Pointer(Activity context) { super(context); }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            super.onSizeChanged(w, h, oldW, oldH);
            x = oldW == 0 ? w / 2f : x * w / oldW;
            y = oldH == 0 ? h / 2f : y * h / oldH;
            move(0, 0);
        }

        void move(float dx, float dy) {
            x = Math.max(root.getPaddingLeft() + dp(12),
                    Math.min(root.getWidth() - root.getPaddingRight() - dp(12), x + dx));
            y = Math.max(root.getPaddingTop() + dp(12),
                    Math.min(root.getHeight() - root.getPaddingBottom() - dp(12), y + dy));
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(180, 0, 0, 0));
            canvas.drawCircle(x, y, dp(10), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.rgb(255, 208, 70));
            canvas.drawCircle(x, y, dp(8), paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(x, y, dp(2), paint);
        }
    }
}
