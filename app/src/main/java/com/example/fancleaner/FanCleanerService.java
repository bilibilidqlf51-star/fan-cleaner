package com.example.fancleaner;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 抖音粉丝批量清理（无障碍服务，带头像过滤）。
 *
 * 流程：
 *   1. 只在「粉丝指数」页（有「关注我的人」标题）动作。
 *   2. 扫描当前屏所有粉丝行：节点树定位每行头像与「更多(···)」按钮。
 *   3. AccessibilityService.takeScreenshot() 截全屏 → 按头像 bounds 裁剪。
 *   4. ML Kit 人脸检测：无人脸的头像（风景/书法/作品/默认灰头像）判为可清理。
 *   5. 点该行「更多」→「移除粉丝」→ 等确认 → 计数 → 下一轮。
 *
 * 限制：年龄为模型回归估计，侧脸/滤镜/口罩/低分辨率头像可能估偏；阈值见 AvatarClassifier。
 *
 * 要求：minSdk >= 30（takeScreenshot），依赖见 app/build.gradle。
 */
public class FanCleanerService extends AccessibilityService {

    private static final String TAG = "FanCleaner";
    private static final String DOUYIN_PKG = "com.ss.android.ugc.aweme";

    // ================== 可调参数 ==================
    private static final int TARGET = 20;              // 本次最多移除多少个粉丝
    private static final int DELAY_MIN = 1200;         // 每步最小延迟(ms)
    private static final int DELAY_MAX = 2200;         // 每步最大延迟(ms)
    private static final int CONFIRM_WAIT = 1800;      // 点「移除粉丝」后等二次确认框的时间(ms)
    private static final int PAGE_CHECK_INTERVAL = 2500; // 不在粉丝页时的轮询间隔(ms)
    private static final boolean ENABLE_AVATAR_FILTER = true; // 是否开启头像过滤
    // ==============================================

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private AvatarClassifier classifier;
    private FloatingWindow floating;

    private int removed = 0;
    private boolean scanning = false;
    private volatile boolean running = false;
    private Step step = Step.IDLE;

    private enum Step {
        IDLE,          // 还没进入粉丝列表页
        SCAN,          // 扫描当前屏 + 头像过滤
        WAIT_MENU,     // 等菜单弹出，找「移除粉丝」
        WAIT_CONFIRM   // 等二次确认（有些版本会弹「移除」确认框）
    }

    /** 一行粉丝的定位信息 */
    private static class RowTarget {
        final AccessibilityNodeInfo more;   // 该行的「更多」按钮
        final Rect avatarBounds;            // 该行头像在屏幕上的位置

        RowTarget(AccessibilityNodeInfo more, Rect avatarBounds) {
            this.more = more;
            this.avatarBounds = avatarBounds;
        }
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "服务已连接，头像过滤=" + ENABLE_AVATAR_FILTER);
        classifier = new AvatarClassifier(this);

        floating = new FloatingWindow(this, new FloatingWindow.Listener() {
            @Override
            public void onStartClicked() {
                startCleaning();
            }

            @Override
            public void onStopClicked() {
                stopCleaning();
            }
        });
        if (floating.canShow()) {
            floating.show();
            floating.setStatus("清粉：待命（打开抖音粉丝页后点开始）");
        } else {
            toast("没有悬浮窗权限：请回 App 里点「授予悬浮窗权限」");
        }

        toast("清粉助手已启动");
        handler.postDelayed(this::tick, 2000);
    }

    /** 悬浮窗「开始」：重置计数并启动 */
    private void startCleaning() {
        running = true;
        removed = 0;
        step = Step.SCAN;
        if (floating != null) floating.setStatus("运行中 0/" + TARGET);
        toast("开始清理，请保持抖音粉丝列表页在前台");
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::tick, 300);
    }

    /** 悬浮窗「停止」 */
    private void stopCleaning() {
        running = false;
        step = Step.IDLE;
        handler.removeCallbacksAndMessages(null);
        if (floating != null) {
            floating.setStatus("已停止（已移除 " + removed + " 个）");
        }
        Log.i(TAG, "已停止，累计移除 " + removed);
        handler.postDelayed(this::tick, 1500);
    }

    @Override
    public void onDestroy() {
        if (classifier != null) classifier.close();
        if (floating != null) floating.hide();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() != null
                && DOUYIN_PKG.contentEquals(event.getPackageName())
                && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(TAG, "窗口变化: " + event.getClassName());
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "服务被中断");
    }

    // ======================== 主循环 ========================

    private void tick() {
        if (!running) {
            schedule(1500);
            return;
        }
        if (removed >= TARGET) {
            finish("已完成：本次移除 " + removed + " 个粉丝");
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !DOUYIN_PKG.equals(String.valueOf(root.getPackageName()))) {
            schedule(PAGE_CHECK_INTERVAL);
            return;
        }
        if (!isFanListPage(root)) {
            if (step != Step.IDLE) {
                Log.d(TAG, "离开粉丝页，暂停");
                step = Step.IDLE;
            }
            schedule(PAGE_CHECK_INTERVAL);
            return;
        }

        switch (step) {
            case IDLE:
            case SCAN:
                handleScan(root);
                break;
            case WAIT_MENU:
                handleWaitMenu(root);
                break;
            case WAIT_CONFIRM:
                handleWaitConfirm(root);
                break;
        }
    }

    /** 扫描当前屏所有行，按头像过滤出可清理的行 */
    private void handleScan(AccessibilityNodeInfo root) {
        if (scanning) return;
        if (classifier == null) {
            schedule(PAGE_CHECK_INTERVAL);
            return;
        }

        List<RowTarget> rows = scanRows(root);
        if (rows.isEmpty()) {
            if (scrollListForward(root)) {
                Log.d(TAG, "当前屏无行，滚动找下一批");
                schedule(DELAY_MAX);
            } else {
                schedule(PAGE_CHECK_INTERVAL);
            }
            return;
        }

        if (!ENABLE_AVATAR_FILTER || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // 不开过滤 / 系统不支持截图 → 直接按顺序清理
            pickAndClean(rows);
            return;
        }

        scanning = true;
        requestScreenshot(new ScreenshotCallback() {
            @Override
            public void onSuccess(Bitmap full) {
                if (full == null) {
                    scanning = false;
                    pickAndClean(rows);
                    return;
                }
                classifyAndPick(full, rows);
            }

            @Override
            public void onFailure() {
                scanning = false;
                pickAndClean(rows); // 截图失败 → 降级为不过滤
            }
        });
    }

    /** 逐行裁剪头像并做人脸检测，全部完成后挑第一行可清理的 */
    private void classifyAndPick(Bitmap full, List<RowTarget> rows) {
        final AtomicInteger pending = new AtomicInteger(rows.size());
        final List<RowTarget> removable = new ArrayList<>();
        final int w = full.getWidth();
        final int h = full.getHeight();

        for (RowTarget row : rows) {
            final Rect b = new Rect(row.avatarBounds);
            if (!b.intersect(0, 0, w, h) || b.width() < 24 || b.height() < 24) {
                if (pending.decrementAndGet() == 0) {
                    handler.post(() -> finishClassify(full, removable));
                }
                continue;
            }

            Bitmap avatar;
            try {
                avatar = Bitmap.createBitmap(full, b.left, b.top, b.width(), b.height());
            } catch (Exception e) {
                Log.w(TAG, "裁剪头像失败", e);
                if (pending.decrementAndGet() == 0) {
                    handler.post(() -> finishClassify(full, removable));
                }
                continue;
            }

            classifier.decide(avatar, shouldRemove -> {
                avatar.recycle();
                if (shouldRemove) {
                    synchronized (removable) {
                        removable.add(row);
                    }
                }
                if (pending.decrementAndGet() == 0) {
                    handler.post(() -> finishClassify(full, removable));
                }
            });
        }
    }

    private void finishClassify(Bitmap full, List<RowTarget> removable) {
        full.recycle();
        scanning = false;

        List<RowTarget> sorted = new ArrayList<>(removable);
        sorted.sort(Comparator.comparingInt(r -> r.avatarBounds.top));

        if (sorted.isEmpty()) {
            Log.d(TAG, "本屏没有可清理的头像，滚动");
            AccessibilityNodeInfo root = getRootInActiveWindow();
            boolean scrolled = root != null && scrollListForward(root);
            schedule(scrolled ? DELAY_MAX : PAGE_CHECK_INTERVAL);
            return;
        }

        clickMore(sorted.get(0));
    }

    /** 不开过滤时的降级路径：直接清理第一行 */
    private void pickAndClean(List<RowTarget> rows) {
        if (rows.isEmpty()) {
            schedule(PAGE_CHECK_INTERVAL);
            return;
        }
        clickMore(rows.get(0));
    }

    private void clickMore(RowTarget row) {
        boolean clicked = performClick(row.more);
        Log.d(TAG, "点击「更多」: " + clicked);
        if (clicked) {
            step = Step.WAIT_MENU;
            schedule(900);
        } else {
            schedule(randomDelay());
        }
    }

    /** 菜单弹出后点「移除粉丝」 */
    private void handleWaitMenu(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> targets = root.findAccessibilityNodeInfosByText("移除粉丝");
        if (targets == null || targets.isEmpty()) {
            Log.d(TAG, "未找到「移除粉丝」，菜单可能没弹出来");
            step = Step.SCAN;
            schedule(randomDelay());
            return;
        }
        AccessibilityNodeInfo clickable = clickableSelfOrAncestor(targets.get(0));
        if (clickable == null) {
            Log.d(TAG, "「移除粉丝」不可点击");
            step = Step.SCAN;
            schedule(randomDelay());
            return;
        }
        boolean clicked = performClick(clickable);
        Log.d(TAG, "点击「移除粉丝」: " + clicked);
        if (clicked) {
            step = Step.WAIT_CONFIRM;
            handler.postDelayed(this::tick, CONFIRM_WAIT);
        } else {
            step = Step.SCAN;
            schedule(randomDelay());
        }
    }

    /** 二次确认：有「移除/确认」按钮就点，没有就认为已经移除成功 */
    private void handleWaitConfirm(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo confirm = findConfirmButton(root);
        if (confirm != null) {
            boolean clicked = performClick(confirm);
            Log.d(TAG, "点击二次确认: " + clicked);
            handler.postDelayed(this::onRemoved, 800);
        } else {
            handler.postDelayed(this::onRemoved, 500);
        }
    }

    private void onRemoved() {
        removed++;
        Log.i(TAG, "已移除 " + removed + "/" + TARGET);
        if (floating != null) {
            floating.setStatus("运行中 " + removed + "/" + TARGET);
        }
        if (removed >= TARGET) {
            finish("已完成：本次移除 " + removed + " 个粉丝");
            return;
        }
        step = Step.SCAN;
        handler.postDelayed(this::tick, randomDelay());
    }

    // ======================== 截图 ========================

    private interface ScreenshotCallback {
        void onSuccess(Bitmap full);

        void onFailure();
    }

    private void requestScreenshot(ScreenshotCallback cb) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            cb.onFailure();
            return;
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(@NonNull ScreenshotResult result) {
                Bitmap full = null;
                HardwareBuffer hb = result.getHardwareBuffer();
                try {
                    Bitmap wrapped = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                    if (wrapped != null) {
                        full = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "解析截图失败", e);
                } finally {
                    try {
                        hb.close();
                    } catch (Exception ignored) {
                    }
                }
                cb.onSuccess(full);
            }

            @Override
            public void onFailure(int errorCode) {
                Log.w(TAG, "截图失败 code=" + errorCode);
                cb.onFailure();
            }
        });
    }

    // ======================== 节点查找 ========================

    private boolean isFanListPage(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("关注我的人");
        return nodes != null && !nodes.isEmpty();
    }

    /** 扫描当前屏所有粉丝行 */
    private List<RowTarget> scanRows(AccessibilityNodeInfo root) {
        List<RowTarget> out = new ArrayList<>();
        List<AccessibilityNodeInfo> guan = root.findAccessibilityNodeInfosByText("回关");
        if (guan == null) return out;

        int screenH = getResources().getDisplayMetrics().heightPixels;
        for (AccessibilityNodeInfo g : guan) {
            AccessibilityNodeInfo row = findRowContainer(g);
            if (row == null) continue;

            AccessibilityNodeInfo avatarNode = findFirstImage(row, 0);
            if (avatarNode == null) continue;

            Rect ab = new Rect();
            avatarNode.getBoundsInScreen(ab);
            if (ab.top < 0 || ab.bottom > screenH || ab.width() < 30 || ab.height() < 30) continue;

            AccessibilityNodeInfo more = findMoreInRow(row);
            if (more == null) continue;

            out.add(new RowTarget(more, ab));
        }
        return out;
    }

    /** 从「回关」按钮向上找行容器（宽约全屏、高约一行） */
    private AccessibilityNodeInfo findRowContainer(AccessibilityNodeInfo node) {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        AccessibilityNodeInfo cur = node.getParent();
        for (int i = 0; i < 6 && cur != null; i++) {
            Rect r = new Rect();
            cur.getBoundsInScreen(r);
            int hgt = r.height();
            if (hgt > 120 && hgt < 800 && r.width() > screenW * 0.6) {
                return cur;
            }
            cur = cur.getParent();
        }
        return null;
    }

    private AccessibilityNodeInfo findFirstImage(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 12) return null;
        CharSequence cls = node.getClassName();
        if (cls != null && cls.toString().contains("ImageView")) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findFirstImage(node.getChild(i), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findMoreInRow(AccessibilityNodeInfo row) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collectNodes(row, all, 0);

        for (AccessibilityNodeInfo n : all) {
            CharSequence d = n.getContentDescription();
            if (d != null && d.toString().contains("更多")) return n;
        }
        for (AccessibilityNodeInfo n : all) {
            CharSequence t = n.getText();
            if (t != null) {
                String s = t.toString().trim();
                if (s.equals("···") || s.equals("⋯") || s.equals("...")) return n;
            }
        }
        // 兜底：行内最靠右的可点击节点（排除「回关」）
        AccessibilityNodeInfo rightmost = null;
        Rect rb = new Rect(Integer.MIN_VALUE, 0, Integer.MIN_VALUE, 0);
        for (AccessibilityNodeInfo n : all) {
            if (!n.isClickable()) continue;
            CharSequence t = n.getText();
            if (t != null && t.toString().contains("回关")) continue;
            Rect nb = new Rect();
            n.getBoundsInScreen(nb);
            if (nb.centerX() >= rb.centerX()) {
                rightmost = n;
                rb = nb;
            }
        }
        return rightmost;
    }

    private void collectNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || out.size() > 2000 || depth > 60) return;
        out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            collectNodes(node.getChild(i), out, depth + 1);
        }
    }

    private AccessibilityNodeInfo findConfirmButton(AccessibilityNodeInfo root) {
        String[] labels = {"确认移除", "确认", "移除"};
        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo n : nodes) {
                CharSequence t = n.getText();
                if (t != null && t.toString().contains("移除粉丝")) continue;
                AccessibilityNodeInfo c = clickableSelfOrAncestor(n);
                if (c != null) return c;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo clickableSelfOrAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 6 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private boolean performClick(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo target = clickableSelfOrAncestor(node);
        if (target == null) return false;
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private boolean scrollListForward(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        collectNodes(root, out, 0);
        for (AccessibilityNodeInfo n : out) {
            if (n.isScrollable() && n.isEnabled()) {
                return n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            }
        }
        return false;
    }

    // ======================== 工具 ========================

    private int randomDelay() {
        return DELAY_MIN + random.nextInt(DELAY_MAX - DELAY_MIN);
    }

    private void schedule(long delay) {
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::tick, delay);
    }

    private void finish(String msg) {
        Log.i(TAG, msg);
        toast(msg);
        running = false;
        step = Step.IDLE;
        if (floating != null) {
            floating.setStatus(msg.replace("已完成：", "已完成 "));
        }
        handler.removeCallbacksAndMessages(null);
    }

    private void toast(String msg) {
        handler.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show());
    }
}
