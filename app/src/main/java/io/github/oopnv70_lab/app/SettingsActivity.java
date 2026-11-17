package io.github.oopnv70_lab.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
 * 跑在模块自己的 App 进程里（SELinux 域 untrusted_app），不参与 OUC 的 hook。
 * 它只做一件事：读写两个开关——「屏蔽系统更新」与「强制全量包」。
 *
 * ── 为什么读写要放到后台线程 ──
 *   本进程被 SELinux 挡在 /data/local_ota 之外（local_ota_file 的 allow
 *   规则只有 adbd / platform_app_all / shell / update_engine 四个域），
 *   所以每次读写都得 fork 一个 su 进程。fork 是阻塞调用，
 *   放在主线程会让界面卡一下，甚至触发 ANR。因此：
 *     读 → reload() 在后台线程做，结果 post 回主线程
 *     写 → 后台线程做，成功后重新 reload()
 *   界面上的开关先按用户操作立刻变，写失败再拨回去并提示原因。
 *
 * ── 两个开关的关系 ──
 *   互斥，但不是靠「互相改写」实现的。
 *   打开全量包时，屏蔽开关本身不会被程序拨掉，而是它的判定变成
 *   SwitchStore.isBlockEffective() = 屏蔽 && !全量包，界面在旁边
 *   标一句「已被覆盖」。这样没有状态需要回滚，也不会出现
 *   两个键写同一文件的中途态。理由详见 SwitchStore.isBlockEffective()。
 */
public class SettingsActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private Theme theme;

    private SwitchButton blockSwitch;
    private SwitchButton fullSwitch;

    /** 屏蔽开关下方的状态行（已开启 / 已关闭 / 被全量包覆盖）。 */
    private TextView blockHint;

    /** 全量包开关下方的状态行。 */
    private TextView fullHint;

    private TextView statusLine;

    /** 已经读到的当前值。写的时候基于它算另一项，避免两次读文件。 */
    private boolean curBlock = Const.DEFAULT_BLOCK_UPDATE;
    private boolean curFull = Const.DEFAULT_FORCE_FULL_PACKAGE;

    /** 上次读到的 root 可用性与文件存在性，失败回滚时复用，避免在 UI 线程 fork su。 */
    private boolean curHasFile;
    private boolean curRoot;

    /** 后台读写进行中，防止连点导致并发 fork。 */
    private volatile boolean busy;

    /** 「重启软件更新」按钮与它的状态行。 */
    private TextView restartHint;
    private TextView restartBtn;

    /** 「目标定位」按钮与结果区。 */
    private TextView probeBtn;
    private TextView probeSummary;
    private TextView probeResult;
    /** 解析进行中，防止连点。与 busy 分开：它不碰开关文件，互不干扰。 */
    private volatile boolean probing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        theme = Theme.of(this);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = Theme.dp(this, 16);
        page.setPadding(pad, Theme.dp(this, 20), pad, Theme.dp(this, 20));
        page.setBackgroundColor(theme.background);

        // ── 页头 ──
        TextView title = theme.title(this, "软件更新增强");
        page.addView(title, theme.matchWrap());

        // ── 卡片一：屏蔽系统更新 ──
        page.addView(buildBlockCard(), theme.gaps(this, 16, 0));

        // ── 卡片二：强制全量包 ──
        page.addView(buildFullCard(), theme.gaps(this, 10, 0));

        // ── 卡片三：目标定位 ──
        page.addView(buildProbeCard(), theme.gaps(this, 10, 0));

        // ── 卡片四：重启「软件更新」 ──
        page.addView(buildRestartCard(), theme.gaps(this, 10, 0));

        // ── 卡片五：状态 ──
        page.addView(buildStatusCard(), theme.gaps(this, 10, 0));

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
        setTitle("软件更新增强");

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从别处改了配置文件再回来时，界面要跟上
        reload();
    }

    // ─────────────── 卡片：屏蔽系统更新 ───────────────

    private LinearLayout buildBlockCard() {
        LinearLayout card = theme.card(this);

        card.addView(theme.sectionTitle(this, "更新提示"), theme.matchWrap());

        blockHint = theme.text(this, "", 12, theme.primary);
        blockSwitch = new SwitchButton(this);

        LinearLayout row = switchRow("屏蔽系统更新", blockHint, blockSwitch);
        card.addView(row, theme.gaps(this, 12, 0));

        // 让整张卡片都能点（点文字也能切）
        card.setOnClickListener(v -> blockSwitch.performClick());

        blockSwitch.setOnCheckedChangeListener((v, checked, fromUser) -> {
            if (!fromUser) {
                return;
            }
            saveBlock(checked);
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
        card.addView(row, theme.gaps(this, 12, 0));

        TextView note = theme.text(this,
                "只决定服务器回哪种包，降级仍会被系统拒绝。",
                11, theme.onSurfaceVariant);
        card.addView(note, theme.gaps(this, 10, 0));

        card.setOnClickListener(v -> fullSwitch.performClick());

        fullSwitch.setOnCheckedChangeListener((v, checked, fromUser) -> {
            if (!fromUser) {
                return;
            }
            saveFull(checked);
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

        TextView t = theme.text(this, name, 16, theme.onSurface);
        t.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        textCol.addView(t, theme.matchWrap());

        hint.setPadding(0, Theme.dp(this, 3), 0, 0);
        textCol.addView(hint, theme.matchWrap());

        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(textCol, colLp);

        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                Theme.dp(this, 52), Theme.dp(this, 32));
        row.addView(sw, swLp);

        return row;
    }

    // ─────────────── 卡片：目标定位 ───────────────
    /**
     * 动态定位 OUC 的混淆类名/方法名，并把结果直接显示出来。
     *
     * ── 为什么用户需要看见这个 ──
     *   模块能不能用，取决于「目标定没定到」。以前这件事只在
     *   logcat 里，用户装完模块、点不动、看不出任何原因，只能来问。
     *   现在点一下就把五个目标的定位结论摆在眼前：
     *     · 定到了 → 显示 类名#方法名 [来源]
     *     · 没定到 → 显示「未定位」，并且能看出是哪一类目标失败
     *
     * ── 为什么这个按钮不依赖 root、不依赖 hook ──
     *   解析是对 APK 的静态分析，只读 dex。跑在本 App 自己的进程里，
     *   和 OUC 有没有被 hook 完全无关。所以「换版本、换设备是否还能
     *   定位到」这件事，本来就可以在这里独立验证，不必拿实机试错。
     */
    private LinearLayout buildProbeCard() {
        LinearLayout card = theme.card(this);

        card.addView(theme.sectionTitle(this, "目标定位"), theme.matchWrap());

        probeBtn = theme.text(this, "解析目标", 15, theme.onPrimary);
        probeBtn.setGravity(Gravity.CENTER);
        probeBtn.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        probeBtn.setPadding(0, Theme.dp(this, 10), 0, Theme.dp(this, 10));
        probeBtn.setBackground(theme.rounded(this, theme.primary, 999));
        probeBtn.setClickable(true);
        card.addView(probeBtn, theme.gaps(this, 12, 0));

        probeSummary = theme.text(this, "尚未解析", 12, theme.onSurfaceVariant);
        probeSummary.setPadding(0, Theme.dp(this, 8), 0, 0);
        card.addView(probeSummary, theme.matchWrap());

        probeResult = theme.text(this, "", 12, theme.onSurface);
        probeResult.setTypeface(android.graphics.Typeface.MONOSPACE);
        probeResult.setLineSpacing(Theme.dp(this, 3), 1f);
        probeResult.setTextIsSelectable(true);
        probeResult.setPadding(0, Theme.dp(this, 8), 0, 0);
        card.addView(probeResult, theme.matchWrap());

        probeBtn.setOnClickListener(v -> doProbe());
        return card;
    }

    /**
     * 执行一次解析。
     *
     * 放后台线程：DexKit 首次解析一个 29MB 的 APK 要数秒，主线程必 ANR。
     * 期间按钮置灰改文案，避免用户以为没反应而连点。
     */
    private void doProbe() {
        if (probing) {
            toast("正在解析，请稍候");
            return;
        }
        probing = true;
        probeBtn.setEnabled(false);
        probeBtn.setText("正在解析…");
        probeBtn.setBackground(theme.rounded(this, theme.surfaceVariant, 999));
        probeBtn.setTextColor(theme.onSurfaceVariant);
        probeResult.setText("");
        probeSummary.setText("正在准备…");
        probeSummary.setTextColor(theme.onSurfaceVariant);

        new Thread(() -> {
            TargetProbe.Report rep = TargetProbe.probe(this, line ->
                    // 进度回调在工作线程上来，改控件要切回主线程。
                    ui.post(() -> probeSummary.setText(line)));
            ui.post(() -> finishProbe(rep));
        }, "target-probe").start();
    }

    /**
     * 一次解析结束后的收尾。
     *
     * 解析全程在本 App 内完成，界面一直可见，所以这里不需要浮窗、
     * 也不需要把自己拉回前台——那是「借宿主进程解析」那条路留下的。
     */
    private void finishProbe(TargetProbe.Report rep) {
        probing = false;
        probeBtn.setEnabled(true);
        probeBtn.setText("解析目标");
        probeBtn.setBackground(theme.rounded(this, theme.primary, 999));
        probeBtn.setTextColor(theme.onPrimary);
        renderReport(rep);
    }

    /**
     * 把解析结果铺到界面上。
     *
     * 顺序按 HookTarget.ALL 走；每行三段：中文名 → 混淆名 → 来源。
     * 「来源」是关键信息：
     *   dexkit    = 语义定位成功，跨版本能力正常
     *   cache     = 上次查过，直接复用
     *   hardcoded = 语义定位失败，退回写死的名字，换版本就会失效
     *   none      = 完全没定到，这个功能在本机不可用
     */
    private void renderReport(TargetProbe.Report rep) {
        boolean hasError = !rep.errors.isEmpty();
        probeSummary.setText(rep.summary());
        probeSummary.setTextColor(hasError ? theme.onSurfaceVariant : theme.primary);

        StringBuilder sb = new StringBuilder();
        if (rep.apkTag != null && !rep.apkTag.isEmpty()) {
            sb.append("APK 指纹 ").append(rep.apkTag).append('\n');
            sb.append("（换版本后这个值会变，缓存也随之重算）\n\n");
        }
        for (HookTarget t : HookTarget.ALL) {
            TargetResolver.Resolved r = rep.get(t.id);
            sb.append(t.label).append('\n');
            if (r == null || r.className == null) {
                sb.append("  ✗ 未定位 —— 该功能在本机不可用\n\n");
                continue;
            }
            sb.append("  ").append(r.className).append('#').append(r.methodName).append('\n');
            sb.append("  来源 ").append(describeSource(r.source)).append('\n');
            sb.append('\n');
        }
        if (!rep.errors.isEmpty()) {
            sb.append("── 错误 ──\n");
            for (String e : rep.errors) {
                sb.append("· ").append(e).append('\n');
            }
        }
        probeResult.setText(sb.toString().trim());
    }

    /** 把来源标识翻成一句人话。 */
    private String describeSource(String source) {
        if (source == null) {
            return "未知";
        }
        switch (source) {
            case "dexkit":
                return "语义定位（跨版本可用）";
            case "cache":
                return "上次解析结果（已复核存在）";
            case "hardcoded":
                return "写死的名字（换版本会失效）";
            case "none":
                return "无";
            default:
                return source;
        }
    }

    // ─────────────── 卡片：重启「软件更新」 ───────────────

    /**
     * 一个按钮：以 root 执行 force-stop + am start，把「软件更新」拉成干净进程。
     *
     * ── 这个按钮到底解决什么 ──
     *   两个开关是「读时生效」的（SwitchStore 每次都真读文件，没有缓存），
     *   所以单纯改开关并不需要重启。会用到重启的是这两种情况：
     *     · 刚在 LSPosed 里启用/更新模块 —— hook 只在 OUC 进程启动时挂载，
     *       旧进程还带着旧代码；
     *     · OUC 界面状态卡住。
     *   界面上必须把这点写清楚，否则用户会以为所有改动都要点一次重启。
     */
    private LinearLayout buildRestartCard() {
        LinearLayout card = theme.card(this);
        card.addView(theme.sectionTitle(this, "重启软件更新"), theme.matchWrap());

        TextView when = theme.text(this,
                "刚在 LSPosed 里启用或更新本模块，或界面卡住时用。开关即时生效，不需要重启。",
                11, theme.onSurfaceVariant);
        when.setLineSpacing(Theme.dp(this, 3), 1f);
        card.addView(when, theme.gaps(this, 8, 0));

        restartBtn = theme.text(this, "重启「软件更新」", 15, theme.onPrimary);
        restartBtn.setGravity(Gravity.CENTER);
        restartBtn.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        restartBtn.setPadding(0, Theme.dp(this, 10), 0, Theme.dp(this, 10));
        restartBtn.setBackground(theme.rounded(this, theme.primary, 999));
        restartBtn.setClickable(true);
        card.addView(restartBtn, theme.gaps(this, 12, 0));

        restartHint = theme.text(this, "", 12, theme.onSurfaceVariant);
        restartHint.setPadding(0, Theme.dp(this, 8), 0, 0);
        card.addView(restartHint, theme.matchWrap());

        restartBtn.setOnClickListener(v -> doRestart());
        return card;
    }

    /**
     * 执行重启。
     *
     * 按钮要立刻置灰并改文案，否则用户会连点；
     * 整个流程在后台线程做，因为内部要 fork su，不能在 UI 线程阻塞。
     */
    private void doRestart() {
        if (busy) {
            toast("上一个操作还没完成");
            return;
        }
        busy = true;
        restartBtn.setEnabled(false);
        restartBtn.setText("正在重启…");
        restartBtn.setBackground(theme.rounded(this, theme.surfaceVariant, 999));
        restartBtn.setTextColor(theme.onSurfaceVariant);
        restartHint.setText("正在停止并重新启动「软件更新」…");
        restartHint.setTextColor(theme.onSurfaceVariant);

        new Thread(() -> {
            final OucRestarter.Result r = OucRestarter.restart();
            ui.post(() -> {
                busy = false;
                restartBtn.setEnabled(true);
                restartBtn.setText("重启「软件更新」");
                restartBtn.setBackground(theme.rounded(this, theme.primary, 999));
                restartBtn.setTextColor(theme.onPrimary);
                if (r.ok) {
                    restartHint.setText(r.detail + "，界面已打开。");
                    restartHint.setTextColor(theme.primary);
                } else {
                    restartHint.setText("重启失败：" + r.detail);
                    restartHint.setTextColor(theme.onSurfaceVariant);
                }
            });
        }, "ouc-restart").start();
    }

    // ─────────────── 卡片：状态 ───────────────

    private LinearLayout buildStatusCard() {
        LinearLayout card = theme.card(this);

        card.addView(theme.sectionTitle(this, "状态"), theme.matchWrap());

        statusLine = theme.text(this, "读取中…", 12, theme.onSurfaceVariant);
        statusLine.setTypeface(android.graphics.Typeface.MONOSPACE);
        statusLine.setLineSpacing(Theme.dp(this, 3), 1f);
        statusLine.setTextIsSelectable(true);
        card.addView(statusLine, theme.gaps(this, 8, 0));

        return card;
    }

    // ─────────────── 读取 ───────────────

    /**
     * 后台读配置并刷新界面。
     *
     * 每次都会真的去 fork 一次 su（App 进程读不到该目录），
     * 所以必须离开主线程。两次读（开关值 + 文件是否存在）合并到
     * 一次后台任务里，减少 fork 次数。
     */
    private void reload() {
        if (busy) {
            return;
        }
        busy = true;
        new Thread(() -> {
            final boolean root = RootShell.available();
            final boolean block = SwitchStore.isBlockUpdate();
            final boolean full = SwitchStore.isForceFullPackage();
            final boolean hasFile = SwitchStore.exists();
            ui.post(() -> {
                busy = false;
                applyState(block, full, hasFile, root);
            });
        }, "settings-reload").start();
    }

    /** 纯 UI 刷新，不读文件、不 fork。 */
    private void applyState(boolean block, boolean full, boolean hasFile, boolean root) {
        curBlock = block;
        curFull = full;
        curHasFile = hasFile;
        curRoot = root;

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
        sb.append("root ").append(root ? "可用" : "不可用，开关无法保存").append('\n');
        sb.append("配置 ").append(hasFile ? "已写入文件" : "尚未写入，按默认值生效").append('\n');
        sb.append(Const.KEY_BLOCK_UPDATE).append('=').append(block)
                .append("（实际生效 ").append(block && !full).append("）\n");
        sb.append(Const.KEY_FORCE_FULL_PACKAGE).append('=').append(full).append('\n');
        sb.append("文件 ").append(Const.SWITCH_FILE).append('\n');
        sb.append("安装包目录 ").append(Const.DST_DIR);
        statusLine.setText(sb.toString());
    }

    // ─────────────── 写入 ───────────────

    private void saveBlock(boolean on) {
        if (busy) {
            // 上一次还没写完，把开关拨回去，避免界面和实际不一致
            blockSwitch.setChecked(curBlock);
            toast("上一个操作还没完成");
            return;
        }
        busy = true;
        blockSwitch.setEnabled(false);
        new Thread(() -> {
            final String err = SwitchStore.setBlockUpdate(on);
            if (err == null && on) {
                // 打开屏蔽时顺手把设置页那个红点清零。
                // hook 只在 OUC 下次写 Secure 表时生效，而键里若已留着旧值，
                // 用户会一直看到红点、误以为没生效。这里主动清一次。
                // 失败不算保存失败：hook 仍会拦住后续写入，只是晚一点。
                final String dot = SwitchStore.clearRedDot();
                if (dot != null) {
                    ui.post(() -> toast(dot));
                }
            }
            finishSave(err, "屏蔽系统更新");
        }, "settings-save-block").start();
    }

    private void saveFull(boolean on) {
        if (busy) {
            fullSwitch.setChecked(curFull);
            toast("上一个操作还没完成");
            return;
        }
        busy = true;
        fullSwitch.setEnabled(false);
        new Thread(() -> {
            final String err = SwitchStore.setForceFullPackage(on);
            finishSave(err, "强制全量包");
        }, "settings-save-full").start();
    }

    /**
     * 写完之后统一收尾：失败就把界面拨回读到的真实值并说明原因，
     * 成功就重新读一遍让两个开关的联动状态一起更新。
     *
     * 注意这里不调用 SwitchStore.exists() / RootShell.available()：
     * 两者都会 fork su，在 UI 线程做会卡界面。
     * 失败回滚用的 hasFile/root 直接取上次 reload 读到的缓存值。
     */
    private void finishSave(String err, String what) {
        ui.post(() -> {
            blockSwitch.setEnabled(true);
            fullSwitch.setEnabled(true);
            busy = false;
            if (err != null) {
                toast(what + "保存失败：" + err);
                // 拨回读到的真实状态，别让界面撒谎
                applyState(curBlock, curFull, curHasFile, curRoot);
                return;
            }
            reload();
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}