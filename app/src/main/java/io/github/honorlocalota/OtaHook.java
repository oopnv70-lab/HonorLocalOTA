package io.github.honorlocalota;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 跑在 com.hihonor.ouc 进程里的钩子。
 *
 * 三个改造点，全部有反编译依据，不是猜的：
 *
 *   1. 菜单内容（构造器 og.x$c.<init>(og.x, List)）
 *      b3(View) 里先 new ArrayList 逐项填，再 new og.x$c(og.x, list)，
 *      而 og.x$c 的 c() 返回 list.size()、d(i) 返回 list.get(i)，
 *      是实时读这个 List 的。所以在构造器返回后改 List，
 *      弹出层拿到的就是改过的内容。
 *      → 这里剔除原版「本地更新」(0x7f0903a5)，换成我们自己的一项。
 *
 *   2. 点击分派（og.x$c.a(ListItemData, int, View)）
 *      原版实现在这里读 item.a 再交给 f2() → j2() 的 packed-switch。
 *      我们的 id 不在 0x7f0903a4..0x7f0903aa 区间里，
 *      一旦放行就会落到 default 分支（只打日志），所以必须在
 *      调用 proceed 之前就截住，自己拉起投放页。
 *      第三个参数是 View，直接 getContext() 就能拿到 Activity，
 *      不必反射 adapter 的字段。
 *
 *   3. 安装触发（MainEntranceActivity.onCreate / onNewIntent）
 *      投放页做完搬运后带 extra 启动 MainEntranceActivity，
 *      这里认出 extra 就清掉它，再反射调用 kh.l 的私有无参入口 L()。
 *      走 L() 而不是自己拼 Intent，是因为 L() 除了启动
 *      LocalUpdateVabInstallingActivity，还会先把扫描结果写进
 *      kh.l 的 w 字段——那个字段正是 applyPayload 的参数来源。
 *
 * 关于那个"会格式化设备"的弹窗：
 * 它来自 kh.l.R()（日志字符串 showLocalUpdateWarrantDialog），
 * 唯一的入口是 kh.l.K()，而 K() 只被原版菜单项 0x7f0903a5 触发。
 * 所以让原版项不出现，弹窗就自然不会出现。
 * 不能改成让 kh.l.C() 返回 false：C() 还被 S() 调用，
 * S() 里 C() 为 false 会直接中止安装。
 */
public class OtaHook extends XposedModule {

    private static final String TAG = "HonorLocalOTA";

    /** 混淆后的真实类名，随 OUC 版本变化，只对本版本（205）成立。 */
    private static final String CLS_VIEW = "og.x";
    private static final String CLS_ADAPTER = "og.x$c";
    private static final String CLS_LIST_ITEM =
            "com.hihonor.uikit.hnlistpopupwindow.data.ListItemData";
    private static final String CLS_KHL = "kh.l";
    private static final String CLS_MAIN_ACT =
            "com.hihonor.android.hnouc.ui.activities.MainEntranceActivity";

    /** 两条链路的 hook 各装一次就够。 */
    private volatile boolean actHooked;
    private volatile boolean menuHooked;

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!Const.PKG_OUC.equals(pkg)) {
            return;
        }
        ClassLoader cl = param.getClassLoader();
        log(Log.INFO, TAG, "onPackageReady: " + pkg);

        // 两条链路互不依赖，一条失败不能拖死另一条。
        installActivityHooks(cl);
        installMenuHooks(cl);
    }

    // ---------------------------------------------------------------- 安装入口

    /**
     * 认 extra，然后调 kh.l.L()。
     *
     * onCreate 和 onNewIntent 都挂，是因为投放页可能在「软件更新」
     * 已经在栈里的时候被拉起，这时只会走 onNewIntent。
     */
    private void installActivityHooks(ClassLoader cl) {
        if (actHooked) {
            return;
        }
        try {
            Class<?> act = Class.forName(CLS_MAIN_ACT, false, cl);

            Method onCreate = act.getDeclaredMethod("onCreate", Bundle.class);
            hook(onCreate).intercept(chain -> {
                Object r = chain.proceed();
                try {
                    handleStart((Activity) chain.getThisObject(), null);
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "handleStart(create): " + t);
                }
                return r;
            });

            Method onNewIntent = act.getDeclaredMethod("onNewIntent", Intent.class);
            hook(onNewIntent).intercept(chain -> {
                Intent incoming = (Intent) chain.getArg(0);
                Object r = chain.proceed();
                try {
                    handleStart((Activity) chain.getThisObject(), incoming);
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "handleStart(newIntent): " + t);
                }
                return r;
            });

            actHooked = true;
            log(Log.INFO, TAG, "已挂 MainEntranceActivity.onCreate/onNewIntent");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "挂 Activity 失败: " + t);
        }
    }

    // ---------------------------------------------------------------- 菜单改造

    private void installMenuHooks(ClassLoader cl) {
        if (menuHooked) {
            return;
        }
        try {
            final Class<?> adapter = Class.forName(CLS_ADAPTER, false, cl);
            final Class<?> itemCls = Class.forName(CLS_LIST_ITEM, false, cl);

            // 构造器只有一个 (og.x, List)，按签名取，不按顺序猜。
            Class<?> host = Class.forName(CLS_VIEW, false, cl);
            final Constructor<?> ctor =
                    adapter.getDeclaredConstructor(host, List.class);

            hook(ctor).intercept(chain -> {
                // 构造器签名就是 (og.x, List)，第 2 个参数就是那个 ArrayList，
                // 直接用它，不必依赖构造器里 getThisObject() 的语义。
                Object arg = chain.getArg(1);
                if (arg instanceof List) {
                    try {
                        @SuppressWarnings("unchecked")
                        List<Object> list = (List<Object>) arg;
                        rewriteMenu(list, itemCls);
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "rewriteMenu: " + t);
                    }
                } else {
                    log(Log.ERROR, TAG, "构造器第 2 个参数不是 List");
                }
                return chain.proceed();
            });

            // a(ListItemData, int, View) 是点击入口，已在 d$i 里声明过。
            final Method click =
                    adapter.getDeclaredMethod("a", itemCls, int.class, View.class);

            hook(click).intercept(chain -> {
                Object item = chain.getArg(0);
                if (idOf(item) != Const.MENU_ID) {
                    return chain.proceed();
                }
                Object v = chain.getArg(2);
                Object ctx = v instanceof View ? ((View) v).getContext() : null;
                openPanel(ctx);
                // 不 proceed：原版 j2() 不认识我们的 id，
                // 放行只会多打一行 default 日志，还会把点击事件带进原版分支。
                return null;
            });

            menuHooked = true;
            log(Log.INFO, TAG, "已挂菜单构造器与点击分派");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "挂菜单失败: " + t);
        }
    }

    /**
     * 改菜单内容：删掉原版项、追加自己的一项。
     *
     * 注意 hideStock 和 enable 是两个独立开关：
     *   hideStock 关掉 → 原版项留着（会弹免责声明，用户自己选的）。
     *   enable    关掉 → 不加我们那一项，等于菜单原样。
     */
    private void rewriteMenu(List<Object> list, Class<?> itemCls) throws Exception {
        boolean enable = Prefs.isOn(Prefs.PROP_ENABLE, Prefs.DEF_ENABLE);
        boolean hideStock = Prefs.isOn(Prefs.PROP_HIDE_STOCK, Prefs.DEF_HIDE_STOCK);

        // 顺手把原版项的图标留下来，给我们那项用，视觉上不突兀。
        Object icon = null;
        Iterator<Object> it = list.iterator();
        while (it.hasNext()) {
            Object o = it.next();
            if (idOf(o) == Const.STOCK_MENU_ID) {
                icon = fieldGet(o, itemCls, "d");
                if (hideStock) {
                    it.remove();
                }
            }
        }

        if (!enable) {
            log(Log.INFO, TAG, "开关关闭，菜单保持原样，共 " + list.size() + " 项");
            return;
        }

        // 防重复注入：列表里已经有我们那项就直接返回。
        for (Object o : list) {
            if (idOf(o) == Const.MENU_ID) {
                return;
            }
        }

        Method factory = itemCls.getMethod("b", int.class, String.class);
        Object mine = factory.invoke(null, Const.MENU_ID, Const.MENU_TITLE);
        if (icon != null) {
            fieldSet(mine, itemCls, "d", icon);
        }
        list.add(mine);

        log(Log.INFO, TAG, "菜单已改造，注入 " + Const.MENU_TITLE
                + "，现有 " + list.size() + " 项，隐藏原版项=" + hideStock);
    }

    // ---------------------------------------------------------------- 安装动作

    private void handleStart(Activity act, Intent fromArg) {
        if (act == null) {
            return;
        }
        Intent intent = fromArg != null ? fromArg : act.getIntent();
        if (intent == null || !intent.getBooleanExtra(Const.EXTRA_START, false)) {
            return;
        }

        // 先清标记再干活：不清的话用户从安装页返回时 onNewIntent
        // 或 onResume 会再触发一次，形成重复安装。
        intent.removeExtra(Const.EXTRA_START);
        try {
            act.setIntent(intent);
        } catch (Throwable ignored) {
            // setIntent 失败不影响本次流程
        }

        log(Log.INFO, TAG, "收到 " + Const.EXTRA_START + "，稍后调用 kh.l.L()");
        // 等一下让界面先画出来，否则会看到「软件更新」还没显示就被顶掉。
        new Handler(Looper.getMainLooper())
                .postDelayed(() -> invokeInstall(act), 400L);
    }

    /**
     * 反射调 kh.l 的 L(Context)。
     *
     * L 是 private，u() 是 public static 单例取值，两个都要反射。
     * L() 内部自己会做三件事：扫 /data/local_ota、比 30 GiB 门槛、
     * 起 LocalUpdateVabInstallingActivity(is_start_install=true)。
     */
    private void invokeInstall(Activity act) {
        try {
            Class<?> kl = Class.forName(CLS_KHL, false, act.getClassLoader());

            Method u = kl.getDeclaredMethod("u");
            u.setAccessible(true);
            Object inst = u.invoke(null);

            Method l = kl.getDeclaredMethod("L", Context.class);
            l.setAccessible(true);
            l.invoke(inst, act);

            log(Log.INFO, TAG, "已调用 kh.l.L()，安装流程交给原版");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "调用 kh.l.L() 失败: " + t);
        }
    }

    // ---------------------------------------------------------------- 拉起投放页

    private void openPanel(Object ctxObj) {
        try {
            Context ctx = (Context) ctxObj;
            Intent i = new Intent();
            // 必须写死本模块包名：这段代码跑在 OUC 进程里，
            // ctx.getPackageName() 给的是 com.hihonor.ouc。
            i.setClassName(Const.PKG_MODULE, Const.PKG_MODULE + ".OtaActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            log(Log.INFO, TAG, "已拉起投放页");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "拉起投放页失败: " + t);
        }
    }

    // ---------------------------------------------------------------- 反射小工具

    /** 读 ListItemData.a（菜单 id）。读不到时给一个不可能撞上的值。 */
    private static int idOf(Object item) {
        if (item == null) {
            return Integer.MIN_VALUE;
        }
        try {
            Field f = item.getClass().getField("a");
            return f.getInt(item);
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }

    private static Object fieldGet(Object obj, Class<?> cls, String name) {
        try {
            return cls.getField(name).get(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void fieldSet(Object obj, Class<?> cls, String name, Object value) {
        try {
            cls.getField(name).set(obj, value);
        } catch (Throwable ignored) {
            // 图标没设上也照样能用
        }
    }
}