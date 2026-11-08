package com.example.fancleaner;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * 悬浮窗：显示清理状态（待命 / 运行中 x/20 / 已停止 / 已完成），并提供「开始 / 停止」按钮。
 * 拖动：按住状态文字区域拖动；按钮点击不受影响。
 */
public class FloatingWindow {

    public interface Listener {
        void onStartClicked();

        void onStopClicked();
    }

    private final Context context;
    private final Listener listener;

    private WindowManager wm;
    private View view;
    private TextView tvStatus;
    private WindowManager.LayoutParams params;

    public FloatingWindow(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    /** 是否已获得悬浮窗权限 */
    public boolean canShow() {
        return Settings.canDrawOverlays(context);
    }

    public void show() {
        if (view != null || !canShow()) return;

        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        view = LayoutInflater.from(context).inflate(R.layout.floating_window, null);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE1F1F1F);
        bg.setCornerRadius(24f);
        view.setBackground(bg);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 40;
        params.y = 260;

        tvStatus = view.findViewById(R.id.tv_status);
        view.findViewById(R.id.btn_start).setOnClickListener(v -> listener.onStartClicked());
        view.findViewById(R.id.btn_stop).setOnClickListener(v -> listener.onStopClicked());

        // 只在状态文字上拖动，按钮点击不受影响
        final View dragArea = tvStatus;
        dragArea.setOnTouchListener(new View.OnTouchListener() {
            private int startX;
            private int startY;
            private float downRawX;
            private float downRawY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = params.x;
                        startY = params.y;
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = startX + (int) (event.getRawX() - downRawX);
                        params.y = startY + (int) (event.getRawY() - downRawY);
                        if (wm != null && view != null) {
                            wm.updateViewLayout(view, params);
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        try {
            wm.addView(view, params);
        } catch (Exception e) {
            view = null;
        }
    }

    public void setStatus(String text) {
        if (tvStatus == null) return;
        tvStatus.post(() -> tvStatus.setText(text));
    }

    public void hide() {
        if (wm != null && view != null) {
            try {
                wm.removeView(view);
            } catch (Exception ignored) {
            }
            view = null;
        }
    }
}
