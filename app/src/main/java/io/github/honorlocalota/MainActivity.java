package io.github.honorlocalota;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 界面：一个按钮完成三件事，全部走 su。
 *
 * 为什么需要 root：update_engine 是 native 程序，它自己读 msc.update.type，
 * LSPosed 注入不到它。属性必须在系统属性区里真的等于 "1"，
 * 而写属性区需要 root（resetprop -n 直接 mmap，绕开 property_service 的同值拒绝）。
 *
 * /data/local_ota 同理：必须在 post-fs-data 时就存在，且 SELinux 类型为
 * local_ota_file —— 这两件都只有 root 能做。
 */
public class MainActivity extends Activity {

    private TextView out;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView sv = new ScrollView(this);
        TextView log = new TextView(this);
        log.setPadding(48, 48, 48, 48);
        log.setTextSize(13);
        log.setTextIsSelectable(true);
        log.setText("点击下方按钮，通过 root 写入本地更新所需的运行时状态。\n\n"
                + "只影响 com.hihonor.ouc 与 update_engine 的判定，不改任何分区。\n"
                + "首次点击时系统会弹出 root 授权，允许即可。\n");
        out = log;

        Button btn = new Button(this);
        btn.setText("应用本地更新状态");
        btn.setOnClickListener(v -> runAll(btn));

        ScrollView.LayoutParams lp = new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT);
        sv.addView(log, lp);

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.addView(sv, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(btn, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    /** 后台线程执行，避免阻塞 UI；一次 su 会话跑完整套命令，避免多次弹授权。 */
    private void runAll(Button btn) {
        btn.setEnabled(false);
        append("\n=== 执行 ===\n");
        new Thread(() -> {
            StringBuilder script = new StringBuilder();
            // 1. 属性：update_engine 依赖它判定 LOCAL_OTA。
            //    用 -n 跳过 property_service（同值会被拒），直接写属性区。
            script.append("resetprop -n msc.update.type 1; echo \"prop=$(getprop msc.update.type)\"; ");
            // 2. 开发者开关：kh.l.C() 第 3 道判定读它。
            script.append("settings put secure local_ota_for_developer 1; ");
            // 3. 目录：不存在则建，并打上系统为该特性定义的类型。
            script.append("mkdir -p /data/local_ota; ");
            script.append("chown system:cache /data/local_ota; ");
            script.append("chmod 0777 /data/local_ota; ");
            script.append("restorecon -v /data/local_ota; ");
            // 4. 回读确认
            script.append("ls -ldZ /data/local_ota; ");
            script.append("echo \"dev=$(settings get secure local_ota_for_developer)\"");

            String result = execSu(script.toString());
            append(result);
            boolean ok = result != null
                    && result.contains("prop=1")
                    && result.contains("dev=1")
                    && result.contains("local_ota_file");
            append(ok ? "\n=== 成功 ===\n属性、开关、目录均已就绪。\n"
                    + "现在强制停止「软件更新」再打开，菜单里会出现「本地更新」。\n"
                    + "（本模块的 hook 也会让它直接显示，两者互相独立。）"
                    : "\n=== 未完全成功 ===\n请检查上方输出，或确认已授予 root。");
            ui.post(() -> btn.setEnabled(true));
        }, "ota-apply").start();
    }

    /** 通过 su 执行脚本，返回合并的 stdout+stderr。 */
    private String execSu(String script) {
        StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            // redirectErrorStream(true)：把 stderr 并入 stdout 单流读取。
            // 否则先读完 stdout 再读 stderr，一旦 stderr 写满管道缓冲区，
            // 子进程会阻塞在写、父进程阻塞在读，形成死锁。
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