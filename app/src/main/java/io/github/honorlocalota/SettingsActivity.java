package io.github.honorlocalota;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 桌面入口。
 *
 * 跑在模块自己的进程里，不参与 OUC 的 hook，
 * 唯一的职责是：把 version_spoof.conf 读写出来给你改。
 *
 * 写文件走 root：
 *   本模块 io.github.honorlocalota 已在 KernelSU 授权列表里
 *   （/data/adb/ksu/.allowlist 第一行），进程内可直接 su。
 *   实测 su 10357 -c '/system/bin/su -c id' → uid=0(root)。
 *
 *   目标文件在 OUC 自己的外部私有目录：
 *     /storage/emulated/0/Android/data/com.hihonor.ouc/files/
 *   这个目录从 Android 11 起对其他 app 平台强制隔离，
 *   普通 app uid 写会直接 Permission denied（已实测），
 *   但 root 下写入正常（已实测 OK）。所以必须走 su，
 *   不需要申请 MANAGE_EXTERNAL_STORAGE，也不需要你手动授权。
 *
 * 为什么用「一个大文本框」而不是逐字段输入框：
 *   版本串的格式不是固定的，SP 号、C 号、R 号、P 号都可能变，
 *   做成一堆输入框反而限制了你试别的组合。
 *   直接给全文编辑，你想怎么拼就怎么拼，保存后立刻去点检查更新即可。
 */
public class SettingsActivity extends Activity {

    /**
     * 主配置文件，和 Const.SPOOF_CONFIG_PATHS[0] 保持一致。
     * OUC 自己的外部私有目录，root 写入、OUC 直接读，零权限问题。
     */
    private static final String CONF_PATH =
            "/storage/emulated/0/Android/data/com.hihonor.ouc/files/version_spoof.conf";

    /** 备选位置，主位置写不进去时用。 */
    private static final String CONF_PATH_ALT = "/data/local_ota/version_spoof.conf";

    /** OUC 的 uid，写文件后要把属主改回它，否则 OUC 自己可能读不了。 */
    private static final String OUC_UID = "10132";

    private EditText editor;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        // ── 1. 当前真实值 ──
        TextView now = new TextView(this);
        now.setText(buildCurrentText());
        now.setTextSize(12);
        now.setTextColor(Color.parseColor("#555555"));
        now.setPadding(0, 0, 0, dp(12));
        root.addView(now, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── 2. 配置编辑区 ──
        TextView hint = new TextView(this);
        hint.setText("写入：" + CONF_PATH
                + "\n去掉行首 # 才会生效；留空的项不改。保存走 root，不需要额外授权。");
        hint.setTextSize(11);
        hint.setTextColor(Color.parseColor("#888888"));
        hint.setPadding(0, 0, 0, dp(6));
        root.addView(hint);

        editor = new EditText(this);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setTextSize(13);
        editor.setHorizontallyScrolling(false);
        editor.setMinLines(12);
        editor.setTypeface(android.graphics.Typeface.MONOSPACE);
        editor.setText(readConf());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(editor, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ── 3. 状态 + 按钮 ──
        status = new TextView(this);
        status.setTextSize(12);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);

        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(v -> doSave());
        bar.addView(save, weight());

        Button reload = new Button(this);
        reload.setText("重载");
        reload.setOnClickListener(v -> {
            editor.setText(readConf());
            refreshStatus();
            toast("已重新读取");
        });
        bar.addView(reload, weight());

        Button clear = new Button(this);
        clear.setText("清空");
        clear.setOnClickListener(v -> {
            editor.setText(Const.SPOOF_TEMPLATE);
            refreshStatus();
            toast("已填入模板，记得保存");
        });
        bar.addView(clear, weight());

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        setTitle("系统包投放 · 版本伪造");
        refreshStatus();
    }

    /** 本机当前真实上报值，帮你确定要填的格式。 */
    private String buildCurrentText() {
        StringBuilder sb = new StringBuilder();
        sb.append("本机当前真实值（对照格式用）：\n");
        appendProp(sb, "ro.comp.hl.product_base_version", "→ productBaseVersion");
        appendProp(sb, "ro.comp.hl.product_cust_version", "→ cVersion");
        appendProp(sb, "ro.build.display.id", "→ deviceDisplayVersion");
        sb.append("  root = ").append(hasRoot() ? "可用" : "不可用").append('\n');
        return sb.toString();
    }

    private void appendProp(StringBuilder sb, String key, String note) {
        String v = getProp(key);
        sb.append("  ").append(key).append(" = ").append(v).append('\n');
        if (note != null) {
            sb.append("      ").append(note).append('\n');
        }
    }

    /** 走反射读，避免和 android.os.SystemProperties 的隐藏 API 冲突。 */
    private String getProp(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object o = c.getMethod("get", String.class, String.class)
                    .invoke(null, key, "");
            return o == null ? "" : o.toString();
        } catch (Throwable t) {
            return "(读取失败)";
        }
    }

    /** 探测 su 是否可用（跑一次 id）。 */
    private boolean hasRoot() {
        try {
            Proc p = su("id");
            return p != null && p.code == 0 && p.out.contains("uid=0");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读配置：主位置优先，读不到再读备选，都读不到给模板。 */
    private String readConf() {
        for (String p : new String[]{CONF_PATH, CONF_PATH_ALT}) {
            // 先用 root 读（主位置普通权限读不到）
            String viaRoot = readViaRoot(p);
            if (viaRoot != null) {
                return viaRoot;
            }
            // 备选位置可能普通权限就能读
            String viaJava = readViaJava(p);
            if (viaJava != null) {
                return viaJava;
            }
        }
        return Const.SPOOF_TEMPLATE;
    }

    /** root 读文件，失败返回 null。 */
    private String readViaRoot(String path) {
        try {
            Proc p = su("cat '" + path + "' 2>/dev/null");
            if (p != null && p.code == 0 && !p.out.isEmpty()) {
                return p.out;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 普通 Java 读，失败返回 null。 */
    private String readViaJava(String path) {
        File f = new File(path);
        if (!f.exists() || !f.canRead()) {
            return null;
        }
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private void doSave() {
        String text = editor.getText().toString();
        int effective = countEffective(text);

        String err = writeConf(CONF_PATH, text);
        if (err != null) {
            String err2 = writeConf(CONF_PATH_ALT, text);
            if (err2 != null) {
                setStatus("保存失败：\n主位置 " + err + "\n备选 " + err2, true);
                return;
            }
            setStatus("已保存到备选 " + CONF_PATH_ALT
                    + "（有效项 " + effective + "）。", false);
            return;
        }
        setStatus("已保存（有效项 " + effective + "）。"
                + "现在去系统更新点「检查更新」，LSPosed 日志会打印实际替换结果。", false);
        toast("已保存");
    }

    /**
     * root 写文件。返回 null 表示成功，否则返回错误描述。
     *
     * 用 base64 传内容，避免版本串里的空格、括号、# 被 shell 解释。
     * 先写临时文件再 mv，避免写一半留下残缺文件。
     * 写完把属主改回 OUC 的 uid，保证 OUC 自己能读。
     */
    private String writeConf(String path, String text) {
        try {
            String b64 = android.util.Base64.encodeToString(
                    text.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
            String tmp = path + ".tmp";
            String cmd = "printf '%s' '" + b64 + "' | base64 -d > '" + tmp + "'"
                    + " && chmod 660 '" + tmp + "'"
                    + " && chown " + OUC_UID + ":ext_data_rw '" + tmp + "' 2>/dev/null"
                    + " ; mv -f '" + tmp + "' '" + path + "'"
                    + " && echo WROTE_OK";
            Proc p = su(cmd);
            if (p == null) {
                return "su 不可用（本模块未获 KernelSU 授权？）";
            }
            if (p.code == 0 && p.out.contains("WROTE_OK")) {
                // 顺手也放一份到 Download，方便你在文件管理器里直接看
                su("printf '%s' '" + b64 + "' | base64 -d > '"
                        + "/storage/emulated/0/Download/version_spoof.conf'"
                        + " ; chmod 660 /storage/emulated/0/Download/version_spoof.conf"
                        + " 2>/dev/null");
                return null;
            }
            return "exit=" + p.code + " " + p.err.trim();
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }

    /** 数一下有多少行是真正会生效的 key=value。 */
    private int countEffective(String text) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            if (!k.isEmpty() && !v.isEmpty()) {
                m.put(k, v);
            }
        }
        return m.size();
    }

    private void refreshStatus() {
        int n = countEffective(editor.getText().toString());
        setStatus("当前文件里有 " + n + " 个生效项。"
                + (n == 0 ? "为 0 时不会伪造，按真实版本上报。" : ""), false);
    }

    private void setStatus(String msg, boolean error) {
        status.setText(msg);
        status.setTextColor(error ? Color.parseColor("#C62828")
                : Color.parseColor("#2E7D32"));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    // ─────────────── root 执行 ───────────────

    /** su 执行结果。 */
    private static final class Proc {
        int code;
        String out = "";
        String err = "";
    }

    /**
     * 用 su 执行一条命令。
     *
     * 直接 ProcessBuilder(SU_BIN, "-c", cmd)，不经过额外 sh 包装，
     * 避免多一层转义。
     */
    private Proc su(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(Const.SU_BIN, "-c", cmd);
            Process pr = pb.start();
            StringBuilder o = new StringBuilder();
            StringBuilder e = new StringBuilder();
            readAll(pr.getInputStream(), o);
            readAll(pr.getErrorStream(), e);
            Proc p = new Proc();
            p.code = pr.waitFor();
            p.out = o.toString();
            p.err = e.toString();
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    private void readAll(java.io.InputStream in, StringBuilder sb) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Throwable ignored) {
        }
    }
}