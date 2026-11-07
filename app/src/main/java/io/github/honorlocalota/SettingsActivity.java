package io.github.honorlocalota;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
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
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 桌面入口。
 *
 * 这个 Activity 跑在模块自己的进程里，不参与 OUC 的 hook，
 * 唯一的职责是：把 version_spoof.conf 读写出来给你改。
 *
 * 为什么用「一个大文本框」而不是逐字段输入框：
 *   版本串的格式不是固定的，SP 号、C 号、R 号、P 号都可能变，
 *   做成一堆输入框反而限制了你试别的组合。
 *   直接给全文编辑，你想怎么拼就怎么拼，保存后立刻去点检查更新即可。
 *
 * 界面分三块：
 *   1. 顶部：本机当前真实值（只读），供你对照格式
 *   2. 中间：配置文件全文，可编辑
 *   3. 底部：保存 / 重载 / 清空 三个按钮
 */
public class SettingsActivity extends Activity {

    private static final String TAG = "HonorLocalOTA";

    /** 主配置文件，和 Const.SPOOF_CONFIG_PATHS[0] 保持一致。
     *
     * 放 /sdcard/Download 的原因见 Const.SPOOF_CONFIG_PATHS 的注释：
     * OUC 的外部私有目录在 Android 11+ 对其他应用强制隔离，写不进去。
     */
    private static final String CONF_PATH =
            "/storage/emulated/0/Download/version_spoof.conf";

    /** 备选位置，主位置写不进去时用。 */
    private static final String CONF_PATH_ALT = "/data/local_ota/version_spoof.conf";

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
        hint.setText("下面的内容保存到：\n" + CONF_PATH
                + "\n去掉行首 # 才会生效；留空的项不改。");
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, lp);

        // ── 3. 按钮 ──
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
            toast("已重新读取");
        });
        bar.addView(reload, weight());

        Button clear = new Button(this);
        clear.setText("清空");
        clear.setOnClickListener(v -> {
            editor.setText(Const.SPOOF_TEMPLATE);
            toast("已填入模板，记得保存");
        });
        bar.addView(clear, weight());

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        setTitle("系统包投放 · 版本伪造");

        // MANAGE_EXTERNAL_STORAGE 必须用户在系统设置里手动授予，
        // 没有它写不了 /sdcard/Download，界面会一直保存失败，所以主动引导一次。
        ensureStoragePermission();
    }

    /**
     * 检查并引导授予「所有文件访问」权限。
     *
     * 没有这个权限也能用，只是保存会失败（会退到 /data/local_ota，那也需要 root）。
     * 所以要主动弹一次系统设置页，避免出现「点了保存没反应」。
     */
    private void ensureStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return; // Android 10 及以下按旧存储模型，不用管
        }
        try {
            if (Environment.isExternalStorageManager()) {
                refreshStatus();
                return;
            }
            setStatus("需要「所有文件访问」权限才能写 " + CONF_PATH
                    + "\n正在打开系统设置，请找到本应用并打开开关。", true);
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            // 部分 ROM 没有这个 Action，退回全部应用列表页
            try {
                startActivity(new Intent(
                        Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Throwable ignored) {
                setStatus("无法打开权限设置页，请手动到系统设置里给本应用"
                        + "「所有文件访问」权限。", true);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从权限设置页返回后重新判断一次
        if (status != null) {
            refreshStatus();
        }
    }

    /** 本机当前真实上报值，帮你确定要填的格式。 */
    private String buildCurrentText() {
        StringBuilder sb = new StringBuilder();
        sb.append("本机当前真实值（点检查更新时会原样上报）：\n");
        appendProp(sb, "ro.comp.hl.product_base_version", "→ productBaseVersion");
        appendProp(sb, "ro.comp.hl.product_cust_version", "（cVersion 的来源）");
        appendProp(sb, "ro.build.display.id", "（完整版本串）");
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

    /** 读配置：主位置优先，读不到再读备选，都读不到给模板。 */
    private String readConf() {
        for (String p : new String[]{CONF_PATH, CONF_PATH_ALT}) {
            File f = new File(p);
            if (!f.exists()) {
                continue;
            }
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                return sb.toString();
            } catch (Throwable ignored) {
                // 换下一个位置
            }
        }
        return Const.SPOOF_TEMPLATE;
    }

    private void doSave() {
        String text = editor.getText().toString();
        int effective = countEffective(text);

        String err = writeConf(CONF_PATH, text);
        if (err != null) {
            // 主位置失败，退到备选位置
            String err2 = writeConf(CONF_PATH_ALT, text);
            if (err2 != null) {
                setStatus("保存失败：\n主位置 " + err + "\n备选 " + err2, true);
                return;
            }
            setStatus("已保存到备选位置 " + CONF_PATH_ALT
                    + "（有效项 " + effective + "）。", false);
        } else {
            setStatus("已保存（有效项 " + effective + "）。"
                    + "现在去系统更新里点「检查更新」，日志会打印实际替换结果。", false);
        }
    }

    private String writeConf(String path, String text) {
        try {
            File f = new File(path);
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(f), "UTF-8")) {
                w.write(text);
                w.flush();
            }
            return null;
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
}