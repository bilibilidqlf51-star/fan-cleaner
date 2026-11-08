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
 * 悬浮窗：状态面板 + 控制按钮。
 *
 * 显示：服务状态、页面识别、进度、当前动作
 * 操作：开始 / 暂停 / 停止 / 折叠
 * 拖动：按住标题「清粉助手」拖动
 */
public class FloatingWindow {

    public interface Listener {
        void onStartClicked();

        void onPauseClicked();

        void onStopClicked();
    }

    private final Context context;
    private final Listener listener;

    private WindowManager wm;
    private View view;
    private TextView tvTitle;
    private TextView tvService;
    private TextView tvPage;
    private TextView tvProgress;
    private TextView tvAction;
    private View body;
    private View btnCollapse;
    private WindowManager.LayoutParams params;
    private boolean collapsed = false;

    public FloatingWindow(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public boolean canShow() {
        return Settings.canDrawOverlays(context);
    }

    public void show() {
        if (view != null || !canShow()) return;

        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        view = LayoutInflater.from(context).inflate(R.layout.floating_window, null);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE1A1A1A);
        bg.setCornerRadius(28f);
        view.setBackground(bg);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 30;
        params.y = 240;

        tvTitle = view.findViewById(R.id.tv_title);
        tvService = view.findViewById(R.id.tv_service);
        tvPage = view.findViewById(R.id.tv_page);
        tvProgress = view.findViewById(R.id.tv_progress);
        tvAction = view.findViewById(R.id.tv_action);
        body = view.findViewById(R.id.body);
        btnCollapse = view.findViewById(R.id.btn_collapse);

        view.findViewById(R.id.btn_start).setOnClickListener(v -> listener.onStartClicked());
        view.findViewById(R.id.btn_pause).setOnClickListener(v -> listener.onPauseClicked());
        view.findViewById(R.id.btn_stop).setOnClickListener(v -> listener.onStopClicked());

        // 标题栏拖动
        View dragArea = tvTitle;
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

        // 折叠/展开
        btnCollapse.setOnClickListener(v -> {
            collapsed = !collapsed;
            body.setVisibility(collapsed ? View.GONE : View.VISIBLE);
            btnCollapse.setTag(collapsed ? "1" : "0");
            ((TextView) btnCollapse).setText(collapsed ? "▸" : "▾");
        });

        try {
            wm.addView(view, params);
        } catch (Exception e) {
            view = null;
        }
    }

    public void setService(String text) {
        if (tvService != null) tvService.post(() -> tvService.setText("服务：" + text));
    }

    public void setPage(String text) {
        if (tvPage != null) tvPage.post(() -> tvPage.setText("页面：" + text));
    }

    public void setProgress(int current, int total) {
        if (tvProgress != null) tvProgress.post(() -> tvProgress.setText("进度：" + current + " / " + total));
    }

    public void setAction(String text) {
        if (tvAction != null) tvAction.post(() -> tvAction.setText("动作：" + text));
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
