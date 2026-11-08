package com.example.fancleaner;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);

        findViewById(R.id.btn_open).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        findViewById(R.id.btn_overlay).setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    /** 检查无障碍服务与悬浮窗权限状态 */
    private void updateStatus() {
        boolean accessibility = isAccessibilityEnabled();
        boolean overlay = Settings.canDrawOverlays(this);

        StringBuilder sb = new StringBuilder();
        sb.append("无障碍服务：").append(accessibility ? "已开启 ✓" : "未开启 ✗").append('\n');
        sb.append("悬浮窗权限：").append(overlay ? "已授予 ✓" : "未授予 ✗").append("\n\n");

        if (accessibility && overlay) {
            sb.append("全部就绪：打开抖音 → 我 → 点「粉丝」数字进入列表页，");
            sb.append("然后点悬浮窗上的「开始」即可。");
        } else {
            sb.append("请点下方对应按钮完成授权：\n");
            if (!accessibility) sb.append("• 「去系统设置开启无障碍服务」\n");
            if (!overlay) sb.append("• 「授予悬浮窗权限」\n");
        }
        tvStatus.setText(sb.toString());
    }

    private boolean isAccessibilityEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        for (AccessibilityServiceInfo info :
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getResolveInfo() != null
                    && info.getResolveInfo().serviceInfo != null
                    && getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)) {
                return true;
            }
        }
        return false;
    }
}
