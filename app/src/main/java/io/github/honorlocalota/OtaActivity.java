package io.github.honorlocalota;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 第二个界面：从「软件更新」三点菜单点进来。
 *
 * 职责只有三件事：
 *   1. 显示已扫描到的系统包（Const.SRC_DIR 里含 update_full_ 的 zip）。
 *   2. 显示 /data 剩余空间，并与原版 30 GiB 门槛对比。
 *   3. 点「移动」把包搬进 /data/local_ota 并打标签，随后弹二次确认，
 *      用户确认后才真正触发安装。
 *
 * 为什么不直接「点击即安装」：原版 kh.l.L() 一进来自检空间，不够就
 * 只弹一个 toast 然后 return，用户会觉得"点了没反应"。所以把空间判定
 * 前置到这个页面上，达标才让点。
 */
public class OtaActivity extends Activity {

    private TextView log;
    private Button btnMove;
    private Button btnRefresh;

    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 本次扫描到的 zip 文件名（不含路径）。 */
    private final List<String> found = new ArrayList<>();

    /** /data 可用字节数，-1 表示没读到。 */
    private long availBytes = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        log = new TextView(this);
        log.setPadding(56, 56, 56, 56);
        log.setTextSize(13);
        log.setTextIsSelectable(true);
        log.setTypeface(Typeface.MONOSPACE);
        log.setText("正在扫描…\n");

        btnRefresh = new Button(this);
        btnRefresh.setText("重新扫描");
        btnRefresh.setOnClickListener(v -> scan());

        btnMove = new Button(this);
        btnMove.setText("移动并准备安装");
        btnMove.setEnabled(false);
        btnMove.setOnClickListener(v -> confirmThenMove());

        ScrollView sv = new ScrollView(this);
        sv.addView(log, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(btnRefresh, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(btnMove, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        scan();
    }

    /** 后台扫描：列投放目录 + 读 /data 可用空间，然后刷新界面。 */
    private void scan() {
        btnMove.setEnabled(false);
        btnRefresh.setEnabled(false);
        ui.post(() -> log.setText("正在扫描…\n"));

        new Thread(() -> {
            List<String> all = Root.ls(Const.SRC_DIR);
            List<String> zips = new ArrayList<>();
            for (String n : all) {
                if (n.toLowerCase().endsWith(".zip")) {
                    // 原版过滤器是全串匹配 .*update_full_.*\.zip。
                    // 这里只做展示，名字不合规的会在移动时自动补上。
                    zips.add(n);
                }
            }
            long avail = Root.availBytes("/data");

            ui.post(() -> render(zips, avail));
        }, "ota-scan").start();
    }

    private void render(List<String> zips, long avail) {
        found.clear();
        found.addAll(zips);
        availBytes = avail;

        StringBuilder sb = new StringBuilder();
        sb.append("投放目录 ").append(Const.SRC_DIR).append('\n');
        if (zips.isEmpty()) {
            sb.append("  （没有 .zip）\n");
        } else {
            for (String n : zips) {
                sb.append("  ").append(n).append('\n');
            }
        }

        sb.append('\n').append("/data 剩余空间\n");
        sb.append("  实际 ").append(Root.gib(avail)).append('\n');
        sb.append("  门槛 ").append(Root.gib(Const.MIN_FREE_BYTES))
                .append("（原版硬编码 0x780000000）\n");

        boolean spaceOk = avail >= Const.MIN_FREE_BYTES;
        sb.append("  判定 ").append(spaceOk
                ? "达标，可以继续"
                : "不足，原版会拒绝安装").append('\n');

        sb.append('\n').append("目标目录 ").append(Const.DST_DIR).append('\n');

        if (zips.isEmpty()) {
            sb.append("\n请先把 OTA 包放进投放目录，再点「重新扫描」。\n");
            btnMove.setEnabled(false);
        } else if (!spaceOk) {
            sb.append("\n空间不够。原版 kh.l.L() 自检会直接 return，")
                    .append("点了也不会有反应，所以这里先拦住。\n");
            btnMove.setEnabled(false);
        } else {
            sb.append("\n点「移动并准备安装」→ 搬包 → 二次确认 → 开始安装。\n");
            btnMove.setEnabled(true);
        }

        log.setText(sb.toString());
        btnRefresh.setEnabled(true);
    }

    /** 二次确认：先说清楚要干什么，再动手。 */
    private void confirmThenMove() {
        new AlertDialog.Builder(this)
                .setTitle("移动安装包")
                .setMessage("将把 " + found.size() + " 个包从\n" + Const.SRC_DIR
                        + "\n复制到\n" + Const.DST_DIR
                        + "\n\n并给它打上 update_engine 唯一可读的 SELinux 标签"
                        + "（u:object_r:local_ota_file:s0）。\n\n"
                        + "移动完成后会再问一次是否开始安装。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移动", (d, w) -> doMove())
                .show();
    }

    /** 后台搬运：一次 su 会话完成，避免多次弹授权。 */
    private void doMove() {
        btnMove.setEnabled(false);
        btnRefresh.setEnabled(false);
        ui.post(() -> log.setText("正在移动…\n"));

        new Thread(() -> {
            StringBuilder script = new StringBuilder();
            script.append("mkdir -p ").append(Const.DST_DIR).append("; ");
            for (String name : found) {
                String dstName = normalize(name);
                String dst = Const.DST_DIR + "/" + dstName;
                // 先删同名残留：cp 覆盖旧 inode 时标签不一定跟着更新。
                script.append("rm -f ").append(Root.q(dst)).append("; ");
                script.append("cp -f ").append(Root.q(Const.SRC_DIR + "/" + name))
                        .append(' ').append(Root.q(dst)).append("; ");
                // update_engine 只被允许读 local_ota_file 这一种标签。
                script.append("chcon u:object_r:local_ota_file:s0 ")
                        .append(Root.q(dst)).append("; ");
                script.append("chown system:cache ").append(Root.q(dst)).append("; ");
                script.append("chmod 0644 ").append(Root.q(dst)).append("; ");
            }
            script.append("echo '--- 目标目录 ---'; ls -lZ ")
                    .append(Const.DST_DIR).append("; ");

            String outText = Root.exec(script.toString());

            int labeled = 0;
            for (String line : outText.split("\n")) {
                if (line.contains("local_ota_file")) {
                    labeled++;
                }
            }
            final int ok = labeled;
            final String text = outText;

            ui.post(() -> {
                StringBuilder sb = new StringBuilder();
                sb.append("=== 移动结果 ===\n").append(text);
                sb.append('\n');
                if (ok >= found.size()) {
                    sb.append("成功：").append(ok).append(" 个包已就位并打好标签。\n");
                    log.setText(sb.toString());
                    btnRefresh.setEnabled(true);
                    confirmInstall();
                } else {
                    sb.append("未完全成功：期望 ").append(found.size())
                            .append(" 个，实际带标签 ").append(ok).append(" 个。\n")
                            .append("看上面的输出定位原因，常见是空间不足或路径权限。\n");
                    log.setText(sb.toString());
                    btnMove.setEnabled(true);
                    btnRefresh.setEnabled(true);
                }
            });
        }, "ota-move").start();
    }

    /** 真正的二次确认：问过之后才触发安装。 */
    private void confirmInstall() {
        new AlertDialog.Builder(this)
                .setTitle("开始安装？")
                .setMessage("包已就位。\n\n点「开始安装」会调用原版安装流程"
                        + "（LocalUpdateVabInstallingActivity），"
                        + "设备将重启进入更新流程。\n\n"
                        + "注意：这一步之后无法在应用内撤销。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("开始安装", (d, w) -> triggerInstall())
                .show();
    }

    /**
     * 触发原版安装。
     *
     * 做法：带一个自定义 extra 启动 OUC 的 MainEntranceActivity，
     * OtaHook 在那边认出这个 extra，直接反射调用 kh.l.L(ctx)。
     *
     * 为什么不直接 startActivity(LocalUpdateVabInstallingActivity)：
     * 那个 Activity 不在 LAUNCHER 过滤器里，跨应用启动会被 exported 挡住；
     * 而 L() 是原版自己的入口，走它还能顺带完成 w/f/g/h 这些字段的初始化，
     * 少踩一类"字段没赋值导致 applyPayload 参数为空"的坑。
     */
    private void triggerInstall() {
        try {
            Intent i = new Intent();
            i.setClassName(Const.PKG_OUC, Const.ACT_MAIN);
            i.putExtra(Const.EXTRA_START, true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);

            ui.post(() -> log.append("\n已请求原版开始安装，正在切到「软件更新」…\n"));
        } catch (Throwable t) {
            ui.post(() -> log.append("\n启动失败：" + t + "\n"));
        }
    }

    /**
     * 原版用 String.matches(".*update_full_.*\.zip") 全串匹配路径，
     * 文件名里没有 update_full_ 就永远扫不到。缺了就补上。
     */
    private static String normalize(String name) {
        if (name.contains(Const.NEEDLE)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : ".zip";
        return base + "_update_full_" + ext;
    }
}