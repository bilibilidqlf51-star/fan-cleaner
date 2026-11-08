package com.example.fancleaner;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Intent;
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    /** 检查本包的无障碍服务是否已开启 */
    private void updateStatus() {
        boolean enabled = false;
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am != null) {
            for (AccessibilityServiceInfo info :
                    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                if (info.getResolveInfo() != null
                        && info.getResolveInfo().serviceInfo != null
                        && getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)) {
                    enabled = true;
                    break;
                }
            }
        }
        tvStatus.setText(enabled
                ? "无障碍服务：已开启 ✓\n\n打开抖音 → 我 → 点「粉丝」数字进入列表页，服务会自动开始清理。"
                : "无障碍服务：未开启\n\n请点下方按钮，在系统设置里找到「清粉助手」并开启。");
    }
}
