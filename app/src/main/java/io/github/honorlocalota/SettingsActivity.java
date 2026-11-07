package io.github.honorlocalota;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 桌面入口：模块设置页。
 *
 * 跑在模块自己的进程里，不参与 OUC 的 hook。
 * 它只做一件事：读写「屏蔽系统更新」这一个开关。
 *
 * ── 为什么不需要任何权限、也不需要 root ──
 *   开关文件在 /data/local_ota/（权限 0777）。
 *   本模块 uid 实测可直接写，不需要 su。
 *   详见 Const.SWITCH_FILE 的注释。
 *
 * ── 为什么还保留「本地更新」的说明而没有额外开关 ──
 *   面板（PanelView）是挂到 OUC 界面上的，只要 hook 挂上就存在，
 *   没有需要用户配置的东西。它的状态由 LSPosed 是否启用模块决定，
 *   在那之前加一个本模块自己的开关只会造成「开着但没生效」的困惑。
 */
public class SettingsActivity extends Activity {

    private Theme theme;
    private SwitchButton blockSwitch;
    private TextView statusLine;
    private TextView switchHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        theme = Theme.of(this);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = Theme.dp(this, 20);
        page.setPadding(pad, Theme.dp(this, 28), pad, Theme.dp(this, 28));
        page.setBackgroundColor(theme.background);

        // ── 页头 ──
        TextView title = theme.title(this, "系统包投放");
        page.addView(title, theme.matchWrap());

        TextView sub = theme.text(this, "荣耀「软件更新」增强模块", 14, theme.onSurfaceVariant);
        sub.setPadding(0, Theme.dp(this, 6), 0, 0);
        page.addView(sub, theme.matchWrap());

        // ── 卡片一：屏蔽系统更新 ──
        page.addView(buildBlockCard(), theme.gaps(this, 24, 0));

        // ── 卡片二：本地更新 ──
        page.addView(buildLocalCard(), theme.gaps(this, 14, 0));

        // ── 卡片三：状态 ──
        page.addView(buildStatusCard(), theme.gaps(this, 14, 0));

        ScrollView sv = new ScrollView(this);
        sv.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(theme.background);
        root.addView(sv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        // 状态栏留白，避免标题被刘海/状态栏压住
        root.setFitsSystemWindows(true);

        setContentView(root);
        setTitle("系统包投放");

        refresh();
    }

    // ─────────────── 卡片：屏蔽系统更新 ───────────────

    private LinearLayout buildBlockCard() {
        LinearLayout card = theme.card(this);

        card.addView(theme.sectionTitle(this, "更新提示"), theme.matchWrap());

        // 标题 + 开关同一行
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView name = theme.text(this, "屏蔽系统更新", 17, theme.onSurface);
        name.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        textCol.addView(name, theme.matchWrap());

        switchHint = theme.text(this, "", 12, theme.primary);
        switchHint.setPadding(0, Theme.dp(this, 4), 0, 0);
        textCol.addView(switchHint, theme.matchWrap());

        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(textCol, colLp);

        blockSwitch = new SwitchButton(this);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                Theme.dp(this, 52), Theme.dp(this, 32));
        row.addView(blockSwitch, swLp);

        card.addView(row, theme.gaps(this, 14, 0));

        TextView desc = theme.text(this,
                "开启后，「软件更新」里的检查更新会一直显示「已是最新版本」，"
                        + "不会再弹新版本提示。改完立刻生效，不用重启「软件更新」。",
                13, theme.onSurfaceVariant);
        desc.setLineSpacing(Theme.dp(this, 3), 1f);
        card.addView(desc, theme.gaps(this, 14, 0));

        // 让整张卡片都能点（点文字也能切）
        ViewGroup clickable = card;
        clickable.setOnClickListener(v -> blockSwitch.performClick());

        blockSwitch.setOnCheckedChangeListener((v, checked, fromUser) -> {
            if (!fromUser) {
                return;
            }
            String err = SwitchStore.setBlockUpdate(checked);
            if (err != null) {
                toast("保存失败：" + err);
                // 存不进去就把 UI 拨回去，避免显示的和实际不一致
                v.setChecked(!checked);
                return;
            }
            refresh();
        });

        return card;
    }

    // ─────────────── 卡片：本地更新 ───────────────

    private LinearLayout buildLocalCard() {
        LinearLayout card = theme.card(this);

        card.addView(theme.sectionTitle(this, "本地更新"), theme.matchWrap());

        TextView name = theme.text(this, "从本地安装包升级", 17, theme.onSurface);
        name.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        card.addView(name, theme.gaps(this, 14, 0));

        TextView desc = theme.text(this,
                "打开「软件更新」，点右上角三点菜单 → 本地更新，即可选一个 update.zip "
                        + "交给原版的安装流程。这个入口不占用安装包扫描目录之外的任何权限。",
                13, theme.onSurfaceVariant);
        desc.setLineSpacing(Theme.dp(this, 3), 1f);
        card.addView(desc, theme.gaps(this, 10, 0));

        return card;
    }

    // ─────────────── 卡片：状态 ───────────────

    private LinearLayout buildStatusCard() {
        LinearLayout card = theme.card(this);

        card.addView(theme.sectionTitle(this, "状态"), theme.matchWrap());

        statusLine = theme.text(this, "", 13, theme.onSurfaceVariant);
        statusLine.setTypeface(android.graphics.Typeface.MONOSPACE);
        statusLine.setLineSpacing(Theme.dp(this, 4), 1f);
        statusLine.setTextIsSelectable(true);
        card.addView(statusLine, theme.gaps(this, 12, 0));

        return card;
    }

    // ─────────────── 状态刷新 ───────────────

    private void refresh() {
        boolean on = SwitchStore.isBlockUpdate();
        blockSwitch.setChecked(on);

        boolean hasFile = SwitchStore.exists();
        if (on) {
            switchHint.setText("已开启");
            switchHint.setTextColor(theme.primary);
        } else {
            switchHint.setText("已关闭");
            switchHint.setTextColor(theme.onSurfaceVariant);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("开关 ").append(hasFile ? "已写入配置文件" : "尚未写入，按默认值生效").append('\n');
        sb.append("当前值 ").append(Const.KEY_BLOCK_UPDATE).append('=')
                .append(on).append('\n');
        sb.append("文件 ").append(Const.SWITCH_FILE).append('\n');
        sb.append("安装包目录 ").append(Const.DST_DIR);
        statusLine.setText(sb.toString());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}