package io.github.honorlocalota;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 界面：把用户放在 Download/OTA/ 的 OTA 包搬进原版真正扫描的目录。
 *
 * 为什么必须搬：原版 kh.l.L() 里扫描路径是硬编码的 const-string "/data/local_ota/"。
 * 而 /sdcard 下无法 chcon（FUSE 不支持改 SELinux 标签，实测报
 * "Operation not supported on transport endpoint"），update_engine 的 allow
 * 列表里也没有 fuse/sdcardfs，所以文件必须落到 /data 并打上 local_ota_file。
 *
 * 搬完之后完全交给原版流程：点「本地更新」→ kh.l.L() 扫目录 → 有文件就直接
 * 启动 LocalUpdateVabInstallingActivity 开始安装。本模块不伪造任何安装逻辑。
 *
 * 目录枚举也走 su：应用自身没有 all-files 权限，Download 在 Android 11+ 的
 * 分区存储下 listFiles() 会返回 null；而 root 已经拿到了，没必要再让用户
 * 去系统设置里单独开「所有文件访问权限」。
 */
public class MainActivity extends Activity {

    /** 用户投放 OTA 包的目录。 */
    private static final String SRC_DIR = "/storage/emulated/0/Download/OTA";

    /** 原版硬编码扫描的目录，同时受 system_ext_file_contexts:189 约束，不可改。 */
    private static final String DST_DIR = "/data/local_ota";

    /** 原版文件名过滤器：.*update_full_.*\.zip（String.matches 是全串匹配）。 */
    private static final String NEEDLE = "update_full_";

    /** 原版 L() 的空间门槛：0x780000000 字节 = 30 GiB，不够就拒绝安装。 */
    private static final long MIN_FREE_BYTES = 0x780000000L;

    private TextView out;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView log = new TextView(this);
        log.setPadding(48, 48, 48, 48);
        log.setTextSize(13);
        log.setTextIsSelectable(true);
        out = log;
        log.setText("正在读取目录状态…（首次会弹 root 授权）\n");

        Button scan = new Button(this);
        scan.setText("扫描并搬运到 /data/local_ota");
        scan.setOnClickListener(v -> runAll(scan));

        ScrollView sv = new ScrollView(this);
        sv.addView(log, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(scan, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        refresh();
    }

    /** 后台线程：枚举、搬运、校验，一次 su 会话完成，只弹一次授权。 */
    private void runAll(Button btn) {
        btn.setEnabled(false);
        new Thread(() -> {
            List<String> names = listZips();
            if (names.isEmpty()) {
                append("\n=== " + SRC_DIR + " 里没有能用的包 ===\n");
                append("请把 OTA 的 .zip 放进去再点一次。\n");
                ui.post(() -> btn.setEnabled(true));
                return;
            }

            StringBuilder script = new StringBuilder();
            script.append("mkdir -p ").append(DST_DIR).append("; ");
            for (String name : names) {
                String dst = DST_DIR + "/" + normalize(name);
                // 先删同名残留，避免 cp 覆盖到旧 inode 上导致标签不生效。
                script.append("rm -f ").append(q(dst)).append("; ");
                script.append("cp -f ").append(q(SRC_DIR + "/" + name))
                        .append(' ').append(q(dst)).append("; ");
                // 关键一步：打上 update_engine 唯一被允许读取的类型。
                script.append("chcon u:object_r:local_ota_file:s0 ").append(q(dst)).append("; ");
                script.append("chown system:cache ").append(q(dst)).append("; ");
                script.append("chmod 0644 ").append(q(dst)).append("; ");
            }
            script.append("echo '--- /data/local_ota ---'; ls -lZ ").append(DST_DIR).append("; ");
            script.append("echo '--- df ---'; df -k /data | tail -1");

            String result = execSu(script.toString());
            append("\n=== 搬运结果 ===\n");
            append(result);
            append(judge(result, names.size()));
            ui.post(() -> btn.setEnabled(true));
        }, "ota-move").start();
    }

    /** 只读刷新：看两侧目录现状与空间余量。 */
    private void refresh() {
        new Thread(() -> {
            List<String> names = listZips();
            StringBuilder sb = new StringBuilder();
            sb.append("投放目录 ").append(SRC_DIR).append('\n');
            if (names.isEmpty()) {
                sb.append("  （没有 .zip）\n");
            } else {
                for (String n : names) {
                    sb.append("  ").append(n).append('\n');
                }
            }

            String r = execSu("ls -lZ " + DST_DIR + " 2>/dev/null; "
                    + "echo '--- df ---'; df -k /data | tail -1");
            sb.append("\n原版扫描目录 ").append(DST_DIR).append('\n');
            sb.append(r);

            long avail = parseAvailKb(r);
            if (avail > 0) {
                sb.append("\n空间门槛 30 GiB，当前可用 ")
                        .append(avail / 1024 / 1024).append(" GiB ")
                        .append(avail * 1024L >= MIN_FREE_BYTES
                                ? "（通过）" : "（不足，原版会拒绝安装）")
                        .append('\n');
            }
            sb.append("\n流程：本页搬运 → 打开「软件更新」→ 点「本地更新」")
                    .append("→ 原版扫描到文件后直接开始安装。\n");
            ui.post(() -> out.setText(sb.toString()));
        }, "ota-refresh").start();
    }

    /** 用 root 列目录，避开分区存储对 Download 的限制。 */
    private List<String> listZips() {
        List<String> out = new ArrayList<>();
        // 只看一层，不做递归：原版 Files.walk 虽然递归，但投放目录按约定是平铺的。
        String r = execSu("ls -1 " + q(SRC_DIR) + " 2>/dev/null");
        for (String line : r.split("\n")) {
            String n = line.trim();
            if (n.isEmpty() || n.startsWith("exit=") || n.startsWith("执行失败")) {
                continue;
            }
            if (n.toLowerCase().endsWith(".zip")) {
                out.add(n);
            }
        }
        return out;
    }

    /**
     * 原版用 String.matches(".*update_full_.*\.zip") 全串匹配路径 URI，
     * 文件名里没有 update_full_ 就永远扫不到。这里补上，避免搬过去却按不了。
     */
    private static String normalize(String name) {
        if (name.contains(NEEDLE)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : ".zip";
        return base + "_update_full_" + ext;
    }

    /** 单引号包裹，内部单引号按 POSIX 规则转义，路径含空格也不会散。 */
    private static String q(String p) {
        return "'" + p.replace("'", "'\\''") + "'";
    }

    private String judge(String result, int expect) {
        if (result == null) {
            return "\n=== 失败 ===\n没有拿到输出。\n";
        }
        int ok = 0;
        for (String line : result.split("\n")) {
            if (line.contains("local_ota_file")) {
                ok++;
            }
        }
        StringBuilder sb = new StringBuilder();
        if (ok >= expect) {
            sb.append("\n=== 成功 ===\n")
                    .append(ok).append(" 个包已就位并打了 local_ota_file 标签。\n")
                    .append("现在打开「软件更新」→ 点「本地更新」，原版会直接开始安装。\n");
        } else {
            sb.append("\n=== 未完全成功 ===\n")
                    .append("期望 ").append(expect).append(" 个，实际带标签 ")
                    .append(ok).append(" 个。\n")
                    .append("看上方输出；若提示空间不足，那是原版 30 GiB 门槛拦的，与标签无关。\n");
        }
        return sb.toString();
    }

    /** df -k /data 的可用 KB 数；解析不到返回 -1。 */
    private static long parseAvailKb(String s) {
        if (s == null) {
            return -1;
        }
        String[] lines = s.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            String[] p = line.split("\\s+");
            if (p.length < 5) {
                continue;
            }
            try {
                long kb = Long.parseLong(p[3]);
                if (kb > 0) {
                    return kb;
                }
            } catch (NumberFormatException ignored) {
                // 继续往上看
            }
        }
        return -1;
    }

    private String execSu(String script) {
        StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            // redirectErrorStream(true)：stderr 并入单流，避免先读完 stdout 再读
            // stderr 时双端互相阻塞在管道缓冲区上。
            ProcessBuilder pb = new ProcessBuilder("su", "-c", script);
            pb.redirectErrorStream(true);
            p = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            int code = p.waitFor();
            sb.append("exit=").append(code).append('\n');
        } catch (Throwable t) {
            sb.append("执行失败：").append(t).append('\n');
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
        return sb.toString();
    }

    private void append(String s) {
        ui.post(() -> out.append(s));
    }
}