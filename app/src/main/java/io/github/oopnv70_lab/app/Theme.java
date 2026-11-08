package io.github.oopnv70_lab.app;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 极简 Material You 风格主题。
 *
 * ── 为什么不用 Material Components 库 ──
 *   1. 那个库要给 APK 增加 1MB+ 体积，而本模块的功能只有两件，
 *      不值得为了几个控件引入整个设计系统。
 *   2. 更关键的是 PanelView 跑在 com.hihonor.ouc 进程里，
 *      它用的是 OUC 的 ClassLoader。如果 PanelView 依赖了
 *      Material 组件，那些类在 OUC 的 ClassLoader 里不存在，
 *      运行到 addView 时会 NoClassDefFoundError。
 *      保持两者都只用 framework 的 View，是最稳的做法。
 *
 * ── 视觉上怎么做到「现代」──
 *   跟 Material You 对齐的是这几个可量化的点，而不是某个具体库：
 *   · 大圆角（卡片 24dp、按钮/开关 999dp 胶囊）
 *   · 卡片式分区 + 12dp 以上的卡片间距
 *   · 标题 24sp/半粗、正文 14sp、辅助文字 12sp 三级字号
 *   · 主色 + onSurfaceVariant 两级文字色，避免纯黑纯白
 *   · 深色模式跟随系统（values-night 换主题与底色）
 */
final class Theme {

    /** 亮/暗由系统决定，Activity 侧传进来。 */
    final boolean dark;

    // ── 主色（Material You 的 primary 近似值）──
    final int primary;
    final int onPrimary;

    // ── 表面与背景 ──
    final int background;
    final int surface;
    final int surfaceVariant;
    final int outline;

    // ── 文字 ──
    final int onSurface;
    final int onSurfaceVariant;

    private Theme(boolean dark) {
        this.dark = dark;
        if (dark) {
            primary = 0xFFA8C7FA;
            onPrimary = 0xFF0A305F;

            background = 0xFF111318;
            surface = 0xFF1A1C20;
            surfaceVariant = 0xFF23262B;
            outline = 0xFF8E9099;

            onSurface = 0xFFE3E2E6;
            onSurfaceVariant = 0xFFA0A3AC;
        } else {
            primary = 0xFF1565C0;
            onPrimary = 0xFFFFFFFF;

            background = 0xFFF7F9FC;
            surface = 0xFFFFFFFF;
            surfaceVariant = 0xFFEDF0F5;
            outline = 0xFF76777F;

            onSurface = 0xFF1A1C20;
            onSurfaceVariant = 0xFF44474E;
        }
    }

    static Theme of(Context c) {
        boolean dark = false;
        try {
            int mode = c.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            dark = mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable ignored) {
        }
        return new Theme(dark);
    }

    // ─────────────── 尺寸换算 ───────────────

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    // ─────────────── 绘制辅助 ───────────────

    /** 圆角实心背景。 */
    GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    // ─────────────── 控件工厂 ───────────────

    TextView text(Context c, String s, float spSize, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(spSize);
        t.setTextColor(color);
        return t;
    }

    /** 一级标题。 */
    TextView title(Context c, String s) {
        TextView t = text(c, s, 24, onSurface);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLetterSpacing(-0.01f);
        return t;
    }

    /** 卡片内小标题。 */
    TextView sectionTitle(Context c, String s) {
        TextView t = text(c, s, 13, onSurfaceVariant);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLetterSpacing(0.06f);
        t.setAllCaps(true);
        return t;
    }

    /** 卡片：圆角 + 阴影感（用 surfaceVariant 底色区分层次，不用 elevation 以免和老 ROM 打架）。 */
    LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(rounded(c, surface, 24));
        l.setPadding(dp(c, 20), dp(c, 18), dp(c, 20), dp(c, 18));
        return l;
    }

    LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 上下留白的整宽布局参数。 */
    LinearLayout.LayoutParams gaps(Context c, float topDp, float bottomDp) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(0, dp(c, topDp), 0, dp(c, bottomDp));
        return lp;
    }
}