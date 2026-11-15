package io.github.oopnv70_lab.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 解析过程中的系统浮窗进度条。
 *
 * ══════════════════════════════════════════════════════════════
 * 为什么需要浮窗
 * ══════════════════════════════════════════════════════════════
 * 解析必须借住宿主进程（见 TargetProbe 的注释），所以流程是：
 *   点按钮 → 模块 App 用 root 把「软件更新」拉起来
 *          → 「软件更新」盖到最前面
 *          → 它在自己的进程里解析，写结果文件
 *          → 模块 App 把文件读回来
 *
 * 第二步之后本 App 就退到后台了，界面上的进度谁来也看不见。
 * 用户这时只能盯着「软件更新」的界面发呆，不知道背后有没有在干活。
 * 所以进度要画在所有人都盖不住的那一层——系统浮窗。
 *
 * ── 为什么不"用 root 造窗口" ──
 *   root 能让进程以 uid 0 运行，但画窗口这件事本身要走
 *   WindowManager + ViewRootImpl，这仍然是「应用进程里的一个窗口」。
 *   用 root 起一个 app_process 去挂窗口，代价是重写一遍渲染循环，
 *   而且和本 App 的进度状态要跨进程同步。
 *   系统给的正规入口就是 TYPE_APPLICATION_OVERLAY + 「显示在其他应用上层」
 *   权限，一行 Settings.canDrawOverlays 就能判断有没有授权。
 *   root 在这里的正确用途是「拉起宿主进程」和「读 SELinux 受保护的文件」，
 *   不是「替代 WindowManager」。
 *
 * ── 没授权怎么办 ──
 *   不阻塞流程：浮窗只是"看得见的进度"，没有它解析照样跑。
 *   界面会提示一句「授予悬浮窗权限后可以看到进度」，结果照样显示。
 *   把可选能力做成硬前置是最容易招人烦的设计。
 */
final class OverlayProgress {

    /** 浮窗是否真的显示出来了。false 表示没权限，调用方不必强求。 */
    private boolean shown;

    private Context appCtx;
    private WindowManager wm;
    private LinearLayout root;
    private TextView title;
    private TextView detail;
    private WindowManager.LayoutParams lp;

    /**
     * 有没有悬浮窗权限。
     *
     * API 23 起是运行时授予的（用户在系统设置里打开），
     * 23 以下安装即得，不需要检查。
     */
    static boolean canOverlay(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        try {
            return Settings.canDrawOverlays(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 尝试显示浮窗。
     *
     * @return true 表示窗口已挂上；false 表示没权限或挂失败（调用方照常继续）
     */
    boolean show(Context ctx) {
        if (!canOverlay(ctx)) {
            return false;
        }
        try {
            appCtx = ctx.getApplicationContext();
            wm = (WindowManager) appCtx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                return false;
            }

            root = new LinearLayout(appCtx);
            root.setOrientation(LinearLayout.VERTICAL);
            int pad = Theme.dp(appCtx, 14);
            root.setPadding(pad, Theme.dp(appCtx, 10), pad, Theme.dp(appCtx, 10));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xF2202024);
            bg.setCornerRadius(Theme.dp(appCtx, 14));
            root.setBackground(bg);

            title = new TextView(appCtx);
            title.setText("正在定位目标");
            title.setTextSize(13);
            title.setTextColor(Color.WHITE);
            title.setTypeface(android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL));
            root.addView(title);

            detail = new TextView(appCtx);
            detail.setText("");
            detail.setTextSize(11);
            detail.setTextColor(0xFFB8B8C0);
            detail.setPadding(0, Theme.dp(appCtx, 4), 0, 0);
            detail.setMaxLines(3);
            root.addView(detail);

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = Theme.dp(appCtx, 90);

            wm.addView(root, lp);
            shown = true;
            return true;
        } catch (Throwable t) {
            shown = false;
            return false;
        }
    }

    /**
     * 更新进度行。
     *
     * 必须能承受「窗口已销毁后再被调用」：解析线程和窗口生命周期
     * 不在同一条线上，收尾时来一脚是常态。
     */
    void step(String line) {
        if (!shown || detail == null) {
            return;
        }
        try {
            detail.setText(line == null ? "" : line);
        } catch (Throwable ignored) {
            // 窗口没了就算了，进度不是正确性前提。
        }
    }

    /** 换成最终结论，并保留一小会儿让人看清。 */
    void finish(String line) {
        if (!shown) {
            return;
        }
        try {
            if (title != null) {
                title.setText("定位完成");
            }
            step(line);
        } catch (Throwable ignored) {
        }
    }

    /** 移除浮窗。重复调用安全。 */
    void dismiss() {
        if (!shown) {
            return;
        }
        shown = false;
        try {
            if (wm != null && root != null) {
                wm.removeView(root);
            }
        } catch (Throwable ignored) {
            // 已经不在窗口树上了。
        } finally {
            root = null;
            detail = null;
            title = null;
            wm = null;
        }
    }
}