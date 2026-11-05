package io.github.honorlocalota;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 覆盖在「软件更新」首页上的全屏面板。
 *
 * 这个类整体跑在 com.hihonor.ouc 进程内，所以：
 *   · 能直接反射 kh.l，不需要跨进程传话；
 *   · 本身就在 platform_app 域下，对 /data/local_ota 有完整写权限
 *     （system_ext_sepolicy.cil:15450/15453 allow platform_app_all local_ota_file），
 *     不需要 root，也不需要 chcon —— 标签由 system_ext_file_contexts:189 自动带上。
 *
 * 它自己没有任何高级能力：选文件用系统 SAF，读流写文件用 java.io，
 * 最后只是把「装这个包」这件事转告给同进程的 kh.l。
 */
final class PanelView extends FrameLayout {

    private static final int REQ_PICK = 0x4C4F54;

    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final TextView body;
    private final Button pick;
    private final Button install;

    /** 用户选中的 zip 的显示名，未选为 null。 */
    private String pickedName;
    /** 用户选中的 zip 的 uri。 */
    private Uri pickedUri;

    PanelView(Activity activity) {
        super(activity);
        this.act = activity;

        setBackgroundColor(Color.WHITE);
        setClickable(true); // 吃掉背景点击，避免透传到下面的列表

        TextView title = new TextView(activity);
        title.setText("系统包投放");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(56, 64, 56, 24);

        body = new TextView(activity);
        body.setPadding(56, 0, 56, 24);
        body.setTextSize(13);
        body.setTextIsSelectable(true);
        body.setTypeface(Typeface.MONOSPACE);

        pick = new Button(activity);
        pick.setText("选择安装包");
        pick.setOnClickListener(v -> onPick());

        install = new Button(activity);
        install.setText("写入并开始安装");
        install.setEnabled(false);
        install.setOnClickListener(v -> onInstall());

        Button close = new Button(activity);
        close.setText("返回");
        close.setOnClickListener(v -> dismiss());

        ScrollView sv = new ScrollView(activity);
        sv.addView(body, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(title);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(pick, row());
        root.addView(install, row());
        root.addView(close, row());

        addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        render();
    }

    private static LinearLayout.LayoutParams row() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(56, 8, 56, 8);
        return p;
    }

    /** 供 hook 判断这个 onActivityResult 是不是我们宿主 Activity 的。 */
    Activity hostActivity() {
        return act;
    }

    /** 挂到 Activity 的 decorView 上，盖住整屏。 */
    void show() {
        ViewGroup content = act.findViewById(android.R.id.content);
        content.addView(this, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setTranslationZ(100f);
        ui.post(this::render);
    }

    void dismiss() {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) {
            parent.removeView(this);
        }
    }

    // ------------------------------------------------------------ 选文件

    /**
     * 走系统 SAF。不用申请任何权限：OUC 本身读不了 /sdcard
     * （实测 ls /sdcard/Download 报 Permission denied，MANAGE_EXTERNAL_STORAGE 未授予），
     * SAF 只把这一个文件授权给我们。
     */
    private void onPick() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            // 有些机型对 zip 的 mime 判定不一致，放开成任意文件再自己校验扩展名。
            act.startActivityForResult(i, REQ_PICK);
        } catch (Throwable t) {
            body.append("\n无法打开系统文件选择器：" + t + "\n");
        }
    }

    /** 由 OtaHook 里挂的 onActivityResult 转进来。 */
    boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK) {
            return false;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return true;
        }
        pickedUri = data.getData();
        pickedName = queryName(pickedUri);
        // 读权限要显式要一次，否则后续 openInputStream 可能被拒。
        try {
            act.getContentResolver().takePersistableUriPermission(
                    pickedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
            // 非 persistable 的授权，忽略即可
        }
        render();
        return true;
    }

    private String queryName(Uri uri) {
        Cursor c = null;
        try {
            ContentResolver cr = act.getContentResolver();
            c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) {
                    return n;
                }
            }
        } catch (Throwable ignored) {
            // 落到下面的兜底
        } finally {
            if (c != null) {
                c.close();
            }
        }
        String p = uri.getLastPathSegment();
        return p != null ? p : "package.zip";
    }

    // ------------------------------------------------------------ 写文件 + 安装

    private void onInstall() {
        if (pickedUri == null) {
            return;
        }
        install.setEnabled(false);
        pick.setEnabled(false);
        body.append("\n正在写入…\n");

        final Uri uri = pickedUri;
        final String name = pickedName;

        new Thread(() -> {
            String result;
            try {
                result = copyIn(uri, name);
            } catch (Throwable t) {
                result = "写入失败：" + t;
            }
            final String r = result;
            ui.post(() -> {
                body.append(r + "\n");
                pick.setEnabled(true);
                if (r.startsWith("已写入") || r.contains("写入成功")) {
                    body.append("正在调用原版安装流程…\n");
                    invokeInstall();
                } else {
                    install.setEnabled(true);
                }
            });
        }, "hlot-copy").start();
    }

    /**
     * 把 content:// 流写成 /data/local_ota/xxx_update_full_.zip。
     *
     * 不 chcon：system_ext_file_contexts:189 规定 /data/local_ota(/.*)? 一律是
     * u:object_r:local_ota_file:s0，新建文件自动继承。
     * 不 chown：update_engine 的规则是 (allow update_engine local_ota_file
     * (file (read getattr map unlink open)))，按类型授权，不要求 owner 是 system。
     */
    private String copyIn(Uri uri, String displayName) throws Exception {
        File dir = new File(Const.DST_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return "写入失败：无法创建 " + Const.DST_DIR;
        }

        long need = -1;
        try (InputStream probe = act.getContentResolver().openInputStream(uri)) {
            if (probe == null) {
                return "写入失败：打不开选中的文件";
            }
            need = probe.available();
        } catch (Throwable ignored) {
            // available 不可靠，拿不到就不做空间预估
        }
        long free = dir.getUsableSpace();
        if (need > 0 && free > 0 && free < need) {
            return String.format(Locale.US,
                    "写入失败：/data 剩余 %.2f GiB，不够放下 %.2f GiB",
                    free / 1073741824.0, need / 1073741824.0);
        }

        String target = normalize(displayName);
        File dst = new File(dir, target);

        // 先删旧同名文件：覆盖写时如果旧 inode 标签异常，会带坏新文件。
        if (dst.exists() && !dst.delete()) {
            return "写入失败：无法删除旧文件 " + dst;
        }

        long written = 0;
        try (InputStream in = act.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) {
                return "写入失败：打不开选中的文件";
            }
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                written += n;
            }
            out.flush();
        }

        if (written <= 0) {
            dst.delete();
            return "写入失败：读到 0 字节";
        }

        return String.format(Locale.US,
                "写入成功：%s（%.2f MiB）\n/data 剩余 %.2f GiB，门槛 %.2f GiB，%s",
                dst.getAbsolutePath(), written / 1048576.0,
                free / 1073741824.0, Const.MIN_FREE_BYTES / 1073741824.0,
                free >= Const.MIN_FREE_BYTES ? "达标" : "不足，原版会拒绝");
    }

    /**
     * 原版用 String.matches(".*update_full_.*\.zip") 全串匹配路径，
     * 名字里没有 update_full_ 就永远扫不到。缺了就补在扩展名前面。
     */
    private static String normalize(String name) {
        String base = name;
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        if (base.contains(Const.NEEDLE)) {
            return base;
        }
        int dot = base.lastIndexOf('.');
        if (dot <= 0) {
            return base + "_update_full_.zip";
        }
        return base.substring(0, dot) + "_update_full_" + base.substring(dot);
    }

    /**
     * 反射调 kh.l 的 private L(Context)。
     *
     * 走 L() 而不是自己拼 Intent，是因为 L() 除了起
     * LocalUpdateVabInstallingActivity，还会先把扫描结果写进 kh.l 的 w 字段，
     * 那正是 applyPayload 的参数来源。
     */
    private void invokeInstall() {
        try {
            Class<?> kl = Class.forName("kh.l", false, act.getClassLoader());

            Method u = kl.getDeclaredMethod("u");
            u.setAccessible(true);
            Object inst = u.invoke(null);

            Method l = kl.getDeclaredMethod("L", android.content.Context.class);
            l.setAccessible(true);
            l.invoke(inst, act);

            body.append("已交给原版安装流程。\n");
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            body.append("调用原始安装入口失败：" + cause + "\n");
            install.setEnabled(true);
        }
    }

    // ------------------------------------------------------------ 显示

    private void render() {
        StringBuilder sb = new StringBuilder();
        sb.append("作用域  ").append(Const.PKG_OUC).append('\n');
        sb.append("目标目录 ").append(Const.DST_DIR).append('\n');

        File dir = new File(Const.DST_DIR);
        long free = dir.getUsableSpace();
        sb.append("/data 剩余 ").append(gib(free))
                .append("（门槛 ").append(gib(Const.MIN_FREE_BYTES)).append("）\n\n");

        sb.append("选中的包\n");
        sb.append("  ").append(pickedName == null ? "（还没选）" : pickedName).append('\n');
        if (pickedName != null) {
            sb.append("  写入后将命名为 ").append(normalize(pickedName)).append('\n');
        }

        // 顺便列出目标目录里已经有的包，便于判断上次有没有写成功。
        String[] existing = dir.list();
        sb.append("\n目标目录现有\n");
        if (existing == null || existing.length == 0) {
            sb.append("  （空）\n");
        } else {
            for (String n : existing) {
                sb.append("  ").append(n).append('\n');
            }
        }

        boolean spaceOk = free >= Const.MIN_FREE_BYTES;
        if (!spaceOk) {
            sb.append("\n空间不足，原版 kh.l.L() 自检会直接 return，先去清理空间。\n");
        }

        body.setText(sb.toString());
        install.setEnabled(pickedName != null && spaceOk);
    }

    private static String gib(long b) {
        if (b <= 0) {
            return "未知";
        }
        return String.format(Locale.US, "%.2f GiB", b / 1073741824.0);
    }
}