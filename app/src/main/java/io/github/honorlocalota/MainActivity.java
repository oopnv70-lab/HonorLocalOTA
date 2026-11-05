package io.github.honorlocalota;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 本模块自己的入口界面：开关 + 强制重启「软件更新」。
 *
 * 三个开关都是在 APP 内独立打开/关闭的（不在系统设置里）：
 *   1. 启用三点菜单增强   —— 关掉后「软件更新」恢复原样。
 *   2. 隐藏原版「本地更新」—— 就是那个会弹"会格式化设备"免责声明的项。
 *   3. 打开「系统包投放」页 —— 也可以直接从桌面图标进。
 *
 * 开关值存在系统属性里，原因见 Prefs 的注释：hook 跑在 OUC 进程，
 * 读不到我们的 SharedPreferences。
 */
public class MainActivity extends Activity {

    private TextView status;
    private Switch swEnable;
    private Switch swHideStock;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        status = new TextView(this);
        status.setPadding(56, 56, 56, 40);
        status.setTextSize(13);
        status.setTextIsSelectable(true);

        swEnable = new Switch(this);
        swEnable.setText("启用三点菜单增强");
        swEnable.setOnCheckedChangeListener((v, checked) -> {
            if (!v.isPressed()) {
                return; // 程序化刷新时不要回写
            }
            apply(Prefs.PROP_ENABLE, checked);
        });

        swHideStock = new Switch(this);
        swHideStock.setText("隐藏原版「本地更新」项");
        swHideStock.setOnCheckedChangeListener((v, checked) -> {
            if (!v.isPressed()) {
                return;
            }
            apply(Prefs.PROP_HIDE_STOCK, checked);
        });

        Button open = new Button(this);
        open.setText("打开「系统包投放」");
        open.setOnClickListener(v -> startActivity(
                new android.content.Intent(this, OtaActivity.class)));

        Button restart = new Button(this);
        restart.setText("强制重启「软件更新」");
        restart.setOnClickListener(v -> forceRestartOuc(restart));

        Button refresh = new Button(this);
        refresh.setText("刷新状态");
        refresh.setOnClickListener(v -> refresh());

        ScrollView sv = new ScrollView(this);
        sv.addView(status, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 24, 0, 24);
        root.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(swEnable, lp());
        root.addView(swHideStock, lp());
        root.addView(open, lp());
        root.addView(restart, lp());
        root.addView(refresh, lp());
        setContentView(root);

        refresh();
    }

    private static LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    /** 写开关：走 root 的 setprop，然后刷新显示。 */
    private void apply(String key, boolean on) {
        new Thread(() -> {
            String r = Prefs.set(key, on);
            ui.post(() -> {
                refresh();
                status.append("\n写入 " + key + "=" + (on ? "1" : "0") + " → "
                        + r.trim().replace("\n", " ") + "\n"
                        + "（改完需要让「软件更新」重新加载菜单，"
                        + "点「强制重启」即可）\n");
            });
        }, "ota-setprop").start();
    }

    /**
     * 强制重启「软件更新」。
     *
     * 为什么需要：菜单是 MainEntranceView 在 onCreate/onResume 时构建的，
     * 改完开关不重启进程，用户切回去看到的还是旧菜单。
     *
     * force-stop 之后必须显式 am start：OUC 没有前台服务，force-stop
     * 不会自动拉起，不 start 的话用户会以为"应用被搞坏了"。
     */
    private void forceRestartOuc(Button btn) {
        btn.setEnabled(false);
        new Thread(() -> {
            String r = Root.exec(
                    "am force-stop " + Const.PKG_OUC + "; sleep 1; "
                            + "am start -n " + Const.PKG_OUC + "/" + Const.ACT_MAIN
                            + " 2>&1");
            ui.post(() -> {
                status.append("\n=== 重启「软件更新」 ===\n" + r + '\n');
                btn.setEnabled(true);
            });
        }, "ota-restart").start();
    }

    /** 读回当前状态并展示。 */
    private void refresh() {
        new Thread(() -> {
            boolean en = Prefs.isOn(Prefs.PROP_ENABLE, Prefs.DEF_ENABLE);
            boolean hide = Prefs.isOn(Prefs.PROP_HIDE_STOCK, Prefs.DEF_HIDE_STOCK);

            long avail = Root.availBytes("/data");

            StringBuilder sb = new StringBuilder();
            sb.append("本模块：系统包投放器\n\n");
            sb.append("作用域  ").append(Const.PKG_OUC).append('\n');
            sb.append("投放目录 ").append(Const.SRC_DIR).append('\n');
            sb.append("目标目录 ").append(Const.DST_DIR).append('\n');
            sb.append("/data 可用 ").append(Root.gib(avail))
                    .append("（门槛 ").append(Root.gib(Const.MIN_FREE_BYTES)).append("）\n\n");
            sb.append("开关状态\n");
            sb.append("  三点菜单增强  ").append(en ? "开" : "关").append('\n');
            sb.append("  隐藏原版项    ").append(hide ? "开" : "关").append('\n');

            ui.post(() -> {
                status.setText(sb.toString());
                swEnable.setChecked(en);
                swHideStock.setChecked(hide);
            });
        }, "ota-refresh").start();
    }
}