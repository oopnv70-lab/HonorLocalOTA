package io.github.honorlocalota;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 桌面入口：模块设置页。
 *
 * 跑在模块自己的进程里，不参与 OUC 的 hook。它只做一件事：
 * 读写两个开关——「屏蔽系统更新」与「强制全量包」。
 *
 * ── 两个开关的关系 ──
 *   互斥，但不是靠「互相改写」实现的。
 *   打开全量包时，屏蔽开关本身不会被程序拨掉，而是它的判定变成
 *   SwitchStore.isBlockEffective() = 屏蔽 && !全量包，界面在旁边
 *   标一句「已被覆盖」。这样没有状态需要回滚，也不会出现
 *   两个键写同一文件的中途态。理由详见 SwitchStore.isBlockEffective()。
 *
 * ── 为什么不需要任何权限、也不需要 root ──
 *   开关文件在 /data/local_ota/（权限 0777）。
 *   本模块 uid 实测可直接写，不需要 su。
 *   详见 Const.SWITCH_FILE 的注释。
 *
 * ── 为什么「本地更新」只有说明没有开关 ──
 *   面板（PanelView）是挂到 OUC 界面上的，只要 hook 挂上就存在，
 *   没有需要用户配置的东西。它的状态由 LSPosed 是否启用模块决定，
 *   在这之前加一个本模块自己的开关只会造成「开着但没生效」的困惑。
 */
public class SettingsActivity extends Activity {

    private Theme theme;

    private SwitchButton blockSwitch;
    private SwitchButton fullSwitch;

    /** 屏蔽开关下方的状态行（已开启 / 已关闭 / 被全量包覆盖）。 */
    private TextView blockHint;

    /** 全量包开关下方的状态行。 */
    private TextView fullHint;

    private TextView statusLine;

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

        // ── 卡片二：强制全量包 ──
        page.addView(buildFullCard(), theme.gaps(this, 14, 0));

        // ── 卡片三：本地更新 ──
        page.addView(buildLocalCard(), theme.gaps(this, 14, 0));

        // ── 卡片四：状态 ──
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

        blockHint = theme.text(this, "", 12, theme.primary);
        blockSwitch = new SwitchButton(this);

        LinearLayout row = switchRow("屏蔽系统更新", blockHint, blockSwitch);
        card.addView(row, theme.gaps(this, 14, 0));

        TextView desc = theme.text(this,
                "开启后，「软件更新」里的检查更新会一直显示「已是最新版本」，"
                        + "不会再弹新版本提示。改完立刻生效，不用重启「软件更新」。",
                13, theme.onSurfaceVariant);
        desc.setLineSpacing(Theme.dp(this, 3), 1f);
        card.addView(desc, theme.gaps(this, 14, 0));

        // 让整张卡片都能点（点文字也能切）
        card.setOnClickListener(v -> blockSwitch.performClick());

        blockSwitch.setOnCheckedChangeListener((v, checked, fromUser) -> {
            if (!fromUser) {
                return;
            }
            String err = SwitchStore.setBlockUpdate(checked);
            if (err != null) {
                toast("保存失败：" + err);
                // 存不进去就把 UI 拨回去，避免显示的和实际不一致。
                // 回拨不触发保存：fromUser 为 false。
                v.setChecked(!checked);
                return;
            }
            refresh();
        });

        return card;
    }

    // ─────────────── 卡片：强制全量包 ───────────────

    private LinearLayout buildFullCard() {
        LinearLayout card = theme.card(this);

        card.addView(theme.sectionTitle(this, "更新包类型"), theme.matchWrap());

        fullHint = theme.text(this, "", 12, theme.onSurfaceVariant);
        fullSwitch = new SwitchButton(this);

        LinearLayout row = switchRow("强制全量包", fullHint, fullSwitch);
        card.addView(row, theme.gaps(this, 14, 0));

        TextView desc = theme.text(this,
                "开启后，检查更新会直接向服务器要完整包，而不是增量包。"
                        + "这替代了「反复清除应用数据碰运气」的土办法：它是确定性的，"
                        + "清数据只是恰好改变了全量/增量的判定条件。",
                13, theme.onSurfaceVariant);
        desc.setLineSpacing(Theme.dp(this, 3), 1f);
        card.addView(desc, theme.gaps(this, 14, 0));

        TextView note = theme.text(this,
                "注意：此开关只决定服务器回哪种包，不保证一定能装。"
                        + "目标版本高于当前版本可以正常安装；降级仍会被系统安装引擎拒绝。",
                12, theme.onSurfaceVariant);
        note.setLineSpacing(Theme.dp(this, 3), 1f);
        card.addView(note, theme.gaps(this, 10, 0));

        card.setOnClickListener(v -> fullSwitch.performClick());

        fullSwitch.setOnCheckedChangeListener((v, checked, fromUser) -> {
            if (!fromUser) {
                return;
            }
            String err = SwitchStore.setForceFullPackage(checked);
            if (err != null) {
                toast("保存失败：" + err);
                v.setChecked(!checked);
                return;
            }
            refresh();
        });

        return card;
    }

    // ─────────────── 复用：标题 + 提示 + 开关的一行 ───────────────

    /**
     * 左列是标题和提示文字（占满剩余宽度），右列是开关。
     *
     * 抽出来是为了两个开关长得完全一样，
     * 否则以后改一处样式另一处会漏改。
     */
    private LinearLayout switchRow(String name, TextView hint, SwitchButton sw) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView t = theme.text(this, name, 17, theme.onSurface);
        t.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        textCol.addView(t, theme.matchWrap());

        hint.setPadding(0, Theme.dp(this, 4), 0, 0);
        textCol.addView(hint, theme.matchWrap());

        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(textCol, colLp);

        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                Theme.dp(this, 52), Theme.dp(this, 32));
        row.addView(sw, swLp);

        return row;
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

    /**
     * 从开关文件重读并刷新界面。
     *
     * 不用 setChecked 的监听做状态同步，而是每次统一从这里刷：
     * 两个开关互相影响（全量包会覆盖屏蔽），
     * 只在一处计算才能保证界面显示与实际判定一致。
     */
    private void refresh() {
        boolean block = SwitchStore.isBlockUpdate();
        boolean full = SwitchStore.isForceFullPackage();

        blockSwitch.setChecked(block);
        fullSwitch.setChecked(full);

        // 屏蔽开关的状态行：三态。被覆盖时要显式说出来，
        // 否则用户会以为「我明明开着屏蔽，怎么还有更新提示」。
        if (!block) {
            blockHint.setText("已关闭");
            blockHint.setTextColor(theme.onSurfaceVariant);
        } else if (full) {
            blockHint.setText("已被「强制全量包」覆盖，当前不生效");
            blockHint.setTextColor(theme.onSurfaceVariant);
        } else {
            blockHint.setText("已开启");
            blockHint.setTextColor(theme.primary);
        }

        fullHint.setText(full ? "已开启，检查更新时请求完整包" : "已关闭，按系统原逻辑判定");
        fullHint.setTextColor(full ? theme.primary : theme.onSurfaceVariant);

        StringBuilder sb = new StringBuilder();
        sb.append("配置 ").append(SwitchStore.exists() ? "已写入文件" : "尚未写入，按默认值生效")
                .append('\n');
        sb.append(Const.KEY_BLOCK_UPDATE).append('=').append(block)
                .append("（实际生效 ").append(SwitchStore.isBlockEffective()).append("）\n");
        sb.append(Const.KEY_FORCE_FULL_PACKAGE).append('=').append(full).append('\n');
        sb.append("文件 ").append(Const.SWITCH_FILE).append('\n');
        sb.append("安装包目录 ").append(Const.DST_DIR);
        statusLine.setText(sb.toString());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}