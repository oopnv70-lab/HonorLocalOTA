package io.github.honorlocalota;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 跑在 com.hihonor.ouc 进程里的钩子。只做两件事。
 *
 * 一、拦截 kh.l.K(Context)
 *   三点菜单原版「本地更新」项（0x7f0903a5）→ og.x.j2() pswitch_3 → kh.l.K(ctx)。
 *   K() 内部：z() 判断是否正在更新 → 看 p 字段 → 否则调 R()，
 *   而 R() 的日志字符串就是 showLocalUpdateWarrantDialog，
 *   即"更新过程会格式化设备"那个免责声明。
 *
 *   所以这里不 proceed，改成挂我们的面板。R() 永远不会被执行到，
 *   弹窗从根上没有出现的机会，而不是靠改文案或改返回值绕。
 *
 *   顺带说明为什么不能把 kh.l.C() 改成 false：
 *   C() 还被 S() 调用，S() 里 C() 为 false 会走
 *   iput u=7; iput p=true; 起 kh/l$b 线程，也就是直接中止安装。
 *
 * 二、把 Activity 的 onActivityResult 转给面板
 *   面板用 SAF 选文件（startActivityForResult），结果回到承载它的 Activity。
 *   注意 MainEntranceActivity 和它的父类 BaseActivity 都没有重写
 *   onActivityResult（已在 smali 里逐个确认过），所以不能 getDeclaredMethod
 *   那个类上的方法——会 NoSuchMethodException。改为直接挂框架基类
 *   android.app.Activity 的实现，它一定存在，且是所有 Activity 的必经之路。
 */
public class OtaHook extends XposedModule {

    private static final String TAG = "HonorLocalOTA";

    /** 混淆后的真实类名，仅对 com.hihonor.ouc 2.1.1.1（versionCode 205）成立。 */
    private static final String CLS_KHL = "kh.l";

    /**
     * 检查更新的汇总基类，source: CheckMode.java。
     *
     * 手动检查走的 l5.d0（NormalMode.java）等 10 个模式类全部继承它，
     * 而 p() 只在这个基类里定义，没有任何子类覆盖。
     */
    private static final String CLS_CHECK_BASE = "l5.b";

    /** 汇总方法，签名 ()V，protected。 */
    private static final String MTH_CHECK_SUM = "p";

    /**
     * 需要压回 false 的「有新版本」标志位。
     *
     * h 是最终结论（h = l || i），其余是参与合成的分项：
     *   j 增量包 / k 参数更新 / l 完整包 / m COTA / n 附带项 / o 自身新版本。
     * 全部压掉，是为了让对象在 hook 之后处于自洽的「真的没有新版本」状态。
     */
    private static final String[] CHECK_FLAGS = {
            "h", "i", "l", "j", "k", "m", "n", "o"
    };

    /** 当前正在显示的面板，同一时刻最多一个。 */
    private static volatile PanelView sPanel;

    private volatile boolean hooked;

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!Const.PKG_OUC.equals(param.getPackageName())) {
            return;
        }
        if (hooked) {
            return;
        }
        ClassLoader cl = param.getClassLoader();
        log(Log.INFO, TAG, "onPackageReady: " + param.getPackageName());

        // 两件事互相独立，不能因为一件挂不上就放弃另一件：
        //   hookK/hookActivityResult → 本地更新入口（面板投放）
        //   hookCheckSum             → 关掉「检查更新」的新版本提示
        boolean k = hookK(cl);
        if (k) {
            hookActivityResult();
        } else {
            log(Log.WARN, TAG, "本地更新入口未挂上，但继续尝试关闭检查更新");
        }
        boolean s = hookCheckSum(cl);
        boolean v = hookSpoofVersion(cl);
        boolean b = hookBuildFields(cl);

        if (k || s || v || b) {
            hooked = true;
        }
    }

    /**
     * 拦住 kh.l.K(Context)。
     *
     * K 是 public，用 getDeclaredMethod 取即可。
     */
    private boolean hookK(ClassLoader cl) {
        try {
            Class<?> kl = Class.forName(CLS_KHL, false, cl);
            Method k = kl.getDeclaredMethod("K", Context.class);

            hook(k).intercept(chain -> {
                Object ctxArg = chain.getArg(0);

                if (!(ctxArg instanceof Activity)) {
                    // 不是 Activity 上下文就挂不了面板，交回原版（会弹窗，但不至于崩）。
                    log(Log.WARN, TAG, "K() 的 Context 不是 Activity，放行原版流程");
                    return chain.proceed();
                }

                Activity act = (Activity) ctxArg;
                try {
                    showPanel(act);
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "挂面板失败: " + t);
                }

                // 不 proceed：R() 就在 K() 里面，放行必然会弹免责声明。
                log(Log.INFO, TAG, "已拦截 K()，改为显示投放面板");
                return null;
            });

            log(Log.INFO, TAG, "已挂 kh.l.K(Context)");
            return true;
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "挂 K() 失败: " + t);
            return false;
        }
    }

    /**
     * 挂框架基类 android.app.Activity#onActivityResult(int,int,Intent)。
     *
     * 为什么不挂 MainEntranceActivity 或 BaseActivity 上的同名方法：
     * 那两个类都没有重写它。目标 Activity 上最终执行的是
     * android.app.Activity 的实现，所以只能挂基类。
     *
     * 挂基类唯一的副作用是：OUC 进程里所有 Activity 的回调都会经过这里，
     * 因此只处理我们自己发起的 requestCode，其余一律原样 proceed。
     */
    private void hookActivityResult() {
        try {
            Method m = Activity.class.getDeclaredMethod(
                    "onActivityResult", int.class, int.class, Intent.class);

            hook(m).intercept(chain -> {
                PanelView p = sPanel;
                Object self = chain.getThisObject();
                if (p != null && self == p.hostActivity()) {
                    try {
                        if (p.onActivityResult(
                                (Integer) chain.getArg(0),
                                (Integer) chain.getArg(1),
                                (Intent) chain.getArg(2))) {
                            // 面板认领了这次结果，不再往下传，
                            // 避免原版拿一个它不认识的 requestCode 做事。
                            return null;
                        }
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "转交 onActivityResult 失败: " + t);
                    }
                }
                return chain.proceed();
            });

            log(Log.INFO, TAG, "已挂 Activity.onActivityResult");
        } catch (Throwable t) {
            // 挂不上不致命，只是选完文件后面板收不到结果。
            log(Log.ERROR, TAG, "挂 Activity.onActivityResult 失败: " + t);
        }
    }

    /**
     * 挂 l5.b.p()，让检查更新的结论永远是「没有新版本」。
     *
     * ── 为什么是这个位置 ──
     * 原版向服务器问完有没有新版本后，各模式类（NormalMode / ParaOnlyMode /
     * HotaInfoMode / LiteNormalMode ...）把结果塞进 l5.b 的字段：
     *     j 增量包  k 参数更新  l 完整包  m COTA  n 附带项  o 自身新版本
     * 再在 p() 结尾合成：
     *     i = (k || m || j)        → 有任一组件
     *     h = (l || i)             → 有新版本（最终结论）
     * 对应 b.smali 第 2963-2967 行那两条 iput-boolean。
     *
     * 选这里的三个理由：
     *   1. p() 只在 l5.b 定义，10 个子类无一覆盖（grep 只命中 b 自己），
     *      挂一次就覆盖全部检查模式。
     *   2. 它是唯一的汇总出口，上游不管是 hota、cota、ecota 还是 patch，
     *      最后都要在这里落成一个 boolean，没有第二条路。
     *   3. p() 只写字段，不碰网络也不写数据库。写库的是 l5.d0.b0()，
     *      它在第 1949 行读 h，那时读到的已经是我们压过的 false。
     *
     * ── 为什么不改上游响应 ──
     * 上游 HTTP 在 gk.n.D(String, gk.b0)（baseAndPlugin getNewVersionFromServer），
     * 但那条线的回调接口 source 是 CharacterCheckCallback.java，属于「字符与插件」，
     * 跟 ROM 检查不是一套。ROM 这条线的响应解析分散在各 Mode 子类里，没有单一入口。
     *
     * ── 局限 ──
     * 只压住 OUC 自己。CheckNewVersionReceiver 还会往 com.hihonor.phoneservice
     * 发一条 HAS_NEW_ROM 广播，那条链不在本 hook 覆盖范围内。
     * 类名与字段名都是混淆产物，只对本 APK 版本成立。
     */
    private boolean hookCheckSum(ClassLoader cl) {
        try {
            Class<?> base = Class.forName(CLS_CHECK_BASE, false, cl);
            Method sum = base.getDeclaredMethod(MTH_CHECK_SUM);

            // 先解析全部字段。任何一个取不到就整体放弃：
            // 字段名对不上说明 OUC 换了版本，这时挂上去只会空转，不如明确报错。
            final Field[] fields = new Field[CHECK_FLAGS.length];
            for (int i = 0; i < CHECK_FLAGS.length; i++) {
                Field f = base.getDeclaredField(CHECK_FLAGS[i]);
                f.setAccessible(true);
                fields[i] = f;
            }

            hook(sum).intercept(chain -> {
                Object r = chain.proceed();   // 先让原版把各标志算完

                // ── 开关：block_check ──
                // 配置里写 block_check=1 → 压掉「有新版本」标志（功能一生效）
                // 不写或写 0            → 完全放行，标志保持服务器的原判
                //
                // 之前这里是「配置非空就放行」的隐式互斥，问题在于
                // 只要写了任何伪造项，功能一就被悄悄关掉，用户无从得知。
                // 现在改成显式开关，两个功能各自独立可控：
                //
                //   只想关检查更新：block_check=1，不写任何版本项
                //   只想伪造版本：  写版本项，不写 block_check（或写 0）
                //   两个都要：      两者都写（会互相抵消，见下方说明）
                //
                // 注意：同时开启会互相抵消。压掉标志后 UI 恒显示
                // 「已是最新版本」，无论服务器回了什么，等于把功能二
                // 的效果完全遮住。验证功能二时必须关掉这里。
                java.util.Map<String, String> cfg = readSpoofConfig();
                String sw = cfg.get(Const.KEY_BLOCK_CHECK);
                boolean block = "1".equals(sw) || "true".equalsIgnoreCase(sw);
                if (!block) {
                    log(Log.INFO, TAG, "block_check 未开启，功能一放行（标志保持原判）");
                    return r;
                }

                Object self = chain.getThisObject();
                try {
                    for (Field f : fields) {
                        f.setBoolean(self, false);
                    }
                    log(Log.INFO, TAG, "block_check 已开启，已压掉 " + fields.length
                            + " 个新版本标志");
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "清理新版本标志失败: " + t);
                }
                return r;
            });

            log(Log.INFO, TAG, "已挂 " + CLS_CHECK_BASE + "." + MTH_CHECK_SUM
                    + "()，由配置 block_check 控制是否恒为无新版本");
            return true;
        } catch (Throwable t) {
            // 挂不上不影响本地更新功能，只是关不掉检查更新。
            log(Log.ERROR, TAG, "挂 " + CLS_CHECK_BASE + "." + MTH_CHECK_SUM + " 失败: " + t);
            return false;
        }
    }

    /**
     * 在「读系统属性」这一层伪造版本号。
     *
     * ── 为什么改这里 ──
     * 版本号在 OUC 里唯一的来源就是系统属性。整个 APK 里对属性读取
     * 只有一处入口：com.hihonor.android.os.SystemPropertiesEx，
     * 其中 get(String) 被调用 108 次（getBoolean 28 / getInt 9 / getLong 2）。
     *
     * 在 get() 里把版本类属性的返回值换掉，效果是：
     *   属性层 → DependCheckInfo 构造 → 请求体 → 界面显示
     * 全部自动继承假值，不需要再去猜哪个类、哪个字段。
     * 改一处，全链一致。
     *
     * ── 为什么只改白名单里的键 ──
     * get() 承载 108 个调用点，无差别替换会把整个 App 搞崩。
     * 只对 Const.SPOOF_PROP_PREFIX 开头、或名称里含 version 的键下手，
     * 其余原样透传。
     *
     * ── 局限 ──
     * SystemPropertiesEx 是 ROM 私有类，不同机型/ROM 可能不存在。
     * 找不到就返回 false，不影响其它功能。
     */
    private boolean hookSpoofVersion(ClassLoader cl) {
        try {
            Class<?> sp = Class.forName(Const.CLS_SYS_PROP_EX, false, cl);

            // 两个重载都要挂：
            //   get(String)        21 处调用
            //   get(String,String) 87 处调用  ← 版本号读取全走这个
            // 只挂前者会完全看不到效果，这是上一版的错误。
            Method get2 = null;
            try {
                get2 = sp.getDeclaredMethod("get", String.class, String.class);
                get2.setAccessible(true);
            } catch (NoSuchMethodException ignored) {
            }

            Method get1 = null;
            try {
                get1 = sp.getDeclaredMethod("get", String.class);
                get1.setAccessible(true);
            } catch (NoSuchMethodException ignored) {
            }

            if (get1 == null && get2 == null) {
                log(Log.ERROR, TAG, "SystemPropertiesEx 没有 get 方法，放弃版本伪造");
                return false;
            }

            // ── 双参版：get(String key, String def) ──
            // 命中白名单键时返回配置值；否则原样透传（含默认值语义）。
            if (get2 != null) {
                hook(get2).intercept(chain -> {
                    Object keyObj = chain.getArg(0);
                    String key = keyObj instanceof String ? (String) keyObj : null;
                    if (key == null) {
                        return chain.proceed();
                    }
                    String v = spoofedValue(key);
                    if (v == null) {
                        return chain.proceed();
                    }
                    Object real = chain.proceed();
                    if (!v.equals(real)) {
                        log(Log.INFO, TAG, "版本伪造 [" + key + "] " + real + " -> " + v);
                    }
                    return v;
                });
            }

            // ── 单参版：get(String key) ──
            if (get1 != null) {
                hook(get1).intercept(chain -> {
                    Object keyObj = chain.getArg(0);
                    String key = keyObj instanceof String ? (String) keyObj : null;
                    if (key == null) {
                        return chain.proceed();
                    }
                    String v = spoofedValue(key);
                    if (v == null) {
                        return chain.proceed();
                    }
                    Object real = chain.proceed();
                    if (!v.equals(real)) {
                        log(Log.INFO, TAG, "版本伪造 [" + key + "] " + real + " -> " + v);
                    }
                    return v;
                });
            }

            log(Log.INFO, TAG, "已挂 " + Const.CLS_SYS_PROP_EX
                    + ".get(1参+2参)，版本类属性将按配置返回");
            return true;
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "挂 SystemPropertiesEx.get 失败: " + t);
            return false;
        }
    }

    /**
     * 伪造 android.os.Build 的静态版本字段。
     *
     * ── 为什么属性层改了界面还是不变 ──
     * 实测：属性 hook 已命中 113 次并全部返回假值，但界面上那行
     * 「当前版本」纹丝不动。原因是有第二条完全独立的读取路径：
     *
     *   og/x.z2() → r9/w1.m1() → lb/u.h(true) → lb/u.e(true)
     *     → lb/u.d(true) 为空 → lb/u.b()
     *     → PhonePropertyUtils.A()
     *     → PhonePropertyUtils.f("DISPLAY")
     *     → Class.forName("android.os.Build")
     *         .getDeclaredField("DISPLAY").get(null)
     *     → Build.DISPLAY 静态字段
     *
     * Build.DISPLAY 是进程启动时由 Zygote 从 ro.build.display.id
     * 一次性读入的静态常量，之后再不读属性。所以无论属性层怎么改，
     * 这个字段永远是启动那一刻的真值。
     *
     * ── 怎么改 ──
     * 静态 final 字段无法直接 set，但可以用反射清掉 final 修饰再写。
     * 在 onPackageReady（App 启动后）执行，此时字段已被初始化，
     * 改完立即对所有后续读取生效。
     *
     * ── 为什么只改 DISPLAY ──
     * 界面只用到 DISPLAY。FINGERPRINT 也被上报，但它的格式里
     * 含有构建日期等大量信息，乱改更容易被判定异常，暂不碰。
     */
    private boolean hookBuildFields(ClassLoader cl) {
        try {
            java.util.Map<String, String> want = readSpoofConfig();
            if (want.isEmpty()) {
                log(Log.WARN, TAG, "无伪造配置，跳过 Build 静态字段改写");
                return false;
            }

            Class<?> build = Class.forName("android.os.Build", false, cl);
            boolean any = false;

            String display = want.get("ro.build.display.id");
            if (display != null && !display.isEmpty()) {
                if (setStaticString(build, "DISPLAY", display)) {
                    log(Log.INFO, TAG, "已改 Build.DISPLAY -> " + display);
                    any = true;
                }
            }
            return any;
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "改 Build 静态字段失败: " + t);
            return false;
        }
    }

    /**
     * 改写一个静态 String 字段，包括 static final 的。
     *
     * final 字段直接 set 会抛 IllegalAccessException，
     * 所以要先把 modifiers 里的 FINAL 位清掉。
     * 在 ART 上改 static final String 是允许的，字段本身仍可写。
     */
    private boolean setStaticString(Class<?> cls, String name, String value) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            try {
                f.set(null, value);
            } catch (IllegalAccessException e) {
                // 清掉 final 修饰后再试一次
                Field mods = Field.class.getDeclaredField("accessFlags");
                mods.setAccessible(true);
                mods.setInt(f, f.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
                f.set(null, value);
            }
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "改静态字段 " + name + " 失败: " + t);
            return false;
        }
    }

    /**
     * 查某个属性键该不该被伪造，该则返回假值，不该返回 null。
     *
     * ── 为什么认 .real 后缀 ──
     * OUC 的读取套路是：先读 xxx.real，为空才回落到 xxx。
     * 两边都要改，否则一旦某天 .real 被填值，假值就被绕过。
     */
    private String spoofedValue(String key) {
        if (!isVersionKey(key)) {
            return null;
        }
        java.util.Map<String, String> want = readSpoofConfig();
        if (want.isEmpty()) {
            return null;
        }
        String v = want.get(key);
        if (v != null && !v.isEmpty()) {
            return v;
        }
        if (key.endsWith(".real")) {
            v = want.get(key.substring(0, key.length() - 5));
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    /**
     * 判断某个属性键是不是版本类。
     *
     * 命中条件宁少勿多：get() 有 108 个调用点，误改会把 App 弄崩。
     * 这里只认白名单前缀 + 明确的几个键。
     */
    private static boolean isVersionKey(String key) {
        if (key.endsWith(".real")) {
            key = key.substring(0, key.length() - 5);
        }
        for (String p : Const.SPOOF_PROP_KEYS) {
            if (key.equals(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读版本伪造配置。
     *
     * 每次构造请求都重新读，所以改完文件立刻生效，不用重启 OUC。
     * 文件格式：key=value，# 开头是注释，值两侧空白会被去掉。
     * 读不到就返回空表，调用方按原版版本上报（即不伪造）。
     */
    private java.util.Map<String, String> readSpoofConfig() {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        for (String path : Const.SPOOF_CONFIG_PATHS) {
            java.io.BufferedReader br = null;
            try {
                java.io.File f = new java.io.File(path);
                if (!f.exists() || !f.canRead()) {
                    continue;
                }
                br = new java.io.BufferedReader(new java.io.InputStreamReader(
                        new java.io.FileInputStream(f), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
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
                        out.put(k, v);
                    }
                }
                if (!out.isEmpty()) {
                    log(Log.INFO, TAG, "读到伪造配置 " + path + "，共 " + out.size() + " 项");
                    return out;
                }
            } catch (Throwable t) {
                log(Log.WARN, TAG, "读配置失败 " + path + ": " + t);
            } finally {
                if (br != null) {
                    try {
                        br.close();
                    } catch (Throwable ignored) {
                        // 关不掉就算了，不影响主流程
                    }
                }
            }
        }
        return out;
    }

    /** 在传入的 Activity 上盖一层全屏面板。已有面板就先拆掉，避免叠加。 */
    private void showPanel(Activity act) {
        PanelView old = sPanel;
        if (old != null) {
            try {
                old.dismiss();
            } catch (Throwable ignored) {
                // 旧面板所在 Activity 可能已销毁
            }
            sPanel = null;
        }
        PanelView p = new PanelView(act);
        p.show();
        sPanel = p;
        log(Log.INFO, TAG, "面板已显示");
    }
}