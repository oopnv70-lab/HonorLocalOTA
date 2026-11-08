package io.github.oopnv70_lab.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Material 3 风格的开关（SwitchMaterial 那颗胶囊）。
 *
 * ── 为什么自己画 ──
 *   framework 自带的 android.widget.Switch 在 Android 12+ 会走
 *   OEM 的换肤（荣耀会给它套自己的配色和尺寸），
 *   在 OUC 进程里更是会用 OUC 的 theme，
 *   结果就是同一个 APK 在不同宿主里长得完全不一样。
 *   自己用 Canvas 画，只依赖 framework 的 View + Paint，
 *   在任何进程、任何 ROM 上都是同一个样子。
 *
 * 结构就是 Material 3 的规格：
 *   · 轨道：52×32dp 全圆角
 *   · 圆点：开 24dp / 关 16dp，关态居中偏左
 *   · 关态轨道 surfaceVariant + outline 描边
 *   · 开态轨道 primary，圆点 onPrimary
 *   · 切换用 200ms 减速插值，圆点尺寸同步过渡
 */
final class SwitchButton extends View {

    private static final long DUR_MS = 200;

    private final Theme theme;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();
    private final Path trackPath = new Path();

    /** 0 = 全关，1 = 全开。动画在中间时是小数。 */
    private float progress;

    private boolean checked;
    private ValueAnimator anim;

    private OnCheckedChangeListener listener;

    interface OnCheckedChangeListener {
        /**
         * @param fromUser 只有用户点击引起的改变才是 true。
         *                 setChecked 程序化设置时为 false，避免回调套回调。
         */
        void onCheckedChanged(SwitchButton v, boolean isChecked, boolean fromUser);
    }

    SwitchButton(Context c) {
        super(c);
        this.theme = Theme.of(c);
        setClickable(true);
        setFocusable(true);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Theme.dp(c, 2));
        strokePaint.setColor(theme.outline);
        trackPaint.setColor(theme.surfaceVariant);
        thumbPaint.setColor(theme.outline);

        setOnClickListener(v -> {
            boolean next = !checked;
            setCheckedInternal(next, true);
        });
    }

    void setOnCheckedChangeListener(OnCheckedChangeListener l) {
        this.listener = l;
    }

    boolean isChecked() {
        return checked;
    }

    /** 程序化设置，不触发 fromUser 回调。 */
    void setChecked(boolean v) {
        setCheckedInternal(v, false);
    }

    private void setCheckedInternal(boolean v, boolean fromUser) {
        if (checked == v) {
            return;
        }
        checked = v;
        if (anim != null) {
            anim.cancel();
        }
        float target = v ? 1f : 0f;
        anim = ValueAnimator.ofFloat(progress, target);
        anim.setDuration(DUR_MS);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();

        if (listener != null) {
            listener.onCheckedChanged(this, checked, fromUser);
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = Theme.dp(getContext(), 52);
        int h = Theme.dp(getContext(), 32);
        setMeasuredDimension(
                resolveSize(w, wSpec),
                resolveSize(h, hSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Context c = getContext();
        float pad = Theme.dp(c, 1);
        float w = getWidth() - pad * 2;
        float h = getHeight() - pad * 2;
        float r = h / 2f;

        trackRect.set(pad, pad, pad + w, pad + h);
        trackPath.reset();
        trackPath.addRoundRect(trackRect, r, r, Path.Direction.CW);

        // 轨道颜色：关态 → 开态 插值
        int trackColor = blend(theme.surfaceVariant, theme.primary, progress);
        trackPaint.setColor(trackColor);
        canvas.drawPath(trackPath, trackPaint);

        // 关态描边逐渐消失
        if (progress < 0.99f) {
            strokePaint.setAlpha((int) (255 * (1f - progress)));
            canvas.drawPath(trackPath, strokePaint);
        }

        // 圆点：关 16dp / 开 24dp
        float thumbMin = Theme.dp(c, 16);
        float thumbMax = Theme.dp(c, 24);
        float thumbSize = thumbMin + (thumbMax - thumbMin) * progress;
        float thumbR = thumbSize / 2f;

        // 水平位置：关态靠左，开态靠右
        float leftEdge = pad + Theme.dp(c, 8);
        float rightEdge = pad + w - Theme.dp(c, 8);
        float cx = leftEdge + (rightEdge - leftEdge) * progress;
        float cy = pad + h / 2f;

        thumbPaint.setColor(blend(theme.outline, theme.onPrimary, progress));
        canvas.drawCircle(cx, cy, thumbR, thumbPaint);
    }

    private static int blend(int from, int to, float t) {
        if (t <= 0f) {
            return from;
        }
        if (t >= 1f) {
            return to;
        }
        int a = (int) (((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
        super.onDetachedFromWindow();
    }
}