package io.github.oopnv70_lab.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

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
 *
 * 三、压掉「检查更新」的新版本提示（hookCheckSum）
 *   拦 l5.b.p()，在原版算完之后把全部标志位改回 false。
 *   这是独立的第二件事，和面板投放互不影响。
 *
 * 四、强制向服务器要全量包（hookPackageType）
 *   拦 r9.w1.x2()，返回 "recovery"。x2() 的返回值在
 *   DependCheckInfo 构造时写进 hotaPackageType，随请求发给服务器，
 *   决定服务器回全量包还是增量包。这一条替代了社区里
 *   "反复清数据卡全量包"的试错做法，原因见 hookPackageType 的注释。
 *
 * ── 已经删除的功能：版本伪造 ──
 *   曾实现过两套：hook SystemPropertiesEx.get(String,String) 改上报值、
 *   反射改 Build.DISPLAY 改界面显示。两者都实测生效（日志 113+ 次命中、
 *   界面版本串确实变了），但已验证对「能不能装上」毫无影响：
 *   update_engine 安装时校验的是 oeminfo 分区（rmt_oeminfo_read，带完整性
 *   哈希），完全不读系统属性。服务器按假版本回包，装的时候一读 oeminfo
 *   版本对不上就直接拒，且改 oeminfo 要过完整性哈希、绕校验要解锁 BL。
 *   既是死路，已整体移除，不再保留任何配置项或开关。
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
        // 两个作用域，两套完全不同的 hook：
        //   com.hihonor.ouc      → 面板、屏蔽检查、全量包、红点计数的写入端
        //   com.android.settings → 红点的读取端（那个红点是设置画的）
        if (Const.PKG_SETTINGS.equals(param.getPackageName())) {
            hookSettingsScope(param.getClassLoader());
            return;
        }
        if (!Const.PKG_OUC.equals(param.getPackageName())) {
            return;
        }
        if (hooked) {
            return;
        }
        ClassLoader cl = param.getClassLoader();
        log(Log.INFO, TAG, "onPackageReady: " + param.getPackageName());

        // 四件事互相独立，不能因为一件挂不上就放弃其余：
        //   hookK/hookActivityResult → 本地更新入口（面板投放）
        //   hookCheckSum             → 关掉「检查更新」的新版本提示
        //   hookPackageType          → 强制向服务器要全量包
        //   hookBadge                → OUC 自己界面内的角标
        //   hookBadgeCount           → 设置页那个红点（写 Secure 表时压成 0）
        boolean k = hookK(cl);
        if (k) {
            hookActivityResult();
        } else {
            log(Log.WARN, TAG, "本地更新入口未挂上，但继续尝试关闭检查更新");
        }
        boolean s = hookCheckSum(cl);
        boolean f = hookPackageType(cl);
        boolean b = hookBadge(cl);
        boolean c = hookBadgeCount(cl);

        if (k || s || f || b || c) {
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
     *
     * ── 开关 ──
     * 受「屏蔽系统更新」开关控制，开关文件见 Const.SWITCH_FILE。
     * 默认开启（Const.DEFAULT_BLOCK_UPDATE = true），所以装完就生效，
     * 用户不用先去开一次。关掉后原样放行，OUC 恢复真实的更新提示。
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

                // 每次调用都重新读开关文件，不缓存：
                // 用户在设置的 App 里关掉屏蔽后，OUC 不需要重启就能回到真实行为。
                // 一次读文件的开销远小于它本身的网络请求，不值得为它做缓存失效逻辑。
                // 这里用的是 isBlockEffective() 而不是 isBlockUpdate()：
                // 「强制全量包」开着时屏蔽自动失效，否则用户看不到更新提示，
                // 也就没有入口去点那个全量包。详见 SwitchStore.isBlockEffective()。
                if (!SwitchStore.isBlockEffective()) {
                    return r;   // 屏蔽没生效（用户关了，或被全量包覆盖），原样放行
                }

                Object self = chain.getThisObject();
                try {
                    for (Field f : fields) {
                        f.setBoolean(self, false);
                    }
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "清理新版本标志失败: " + t);
                }
                return r;
            });

            log(Log.INFO, TAG, "已挂 " + CLS_CHECK_BASE + "." + MTH_CHECK_SUM
                    + "()，检查更新将恒为无新版本");
            return true;
        } catch (Throwable t) {
            // 挂不上不影响本地更新功能，只是关不掉检查更新。
            log(Log.ERROR, TAG, "挂 " + CLS_CHECK_BASE + "." + MTH_CHECK_SUM + " 失败: " + t);
            return false;
        }
    }

    /**
     * 挂 r9.w1.x2()，让 OUC 向服务器请求全量包而不是增量包。
     *
     * ── 判定链（全部来自 smali，不是推断）──
     *   DependCheckInfo 构造函数第 589 行：
     *       iput-object v0, p0, ...->hotaPackageType
     *       invoke-static {}, Lr9/w1;->x2()Ljava/lang/String;
     *   x2() 返回 "recovery"（全量）或 "upgrade"（增量），
     *   日志原文直接印证：
     *       "getPackageTypeForChecking readyToUpgradeIncrementPackage true,
     *        return INCREMENT_PACKAGE"
     *       "... false, return RECOVERY"
     *
     *   x2() 内部是 b7(isVeritySupport, isVerifyFailByOvMode)，
     *   返回 true ⇔ 允许请求增量包。b7 的门控为 K5()（读属性
     *   honor.check_root.hotapermit）、q4()（读 ro.config.data_preinstalled）、
     *   n4()、X4()，任意一个成立就返回 false → 请求全量。
     *   这也是社区里"反复清数据能卡出全量包"的原理：清数据重置了
     *   n4()/X4() 依赖的本地状态。
     *
     * ── 为什么挂 x2() 而不是 b7() ──
     *   b7 更底层，会影响它的全部调用点；x2() 只服务「检查更新请求哪个包」，
     *   一个 private static 方法、一行返回，是最小改动面。
     *
     * ── 局限 ──
     *   只决定"服务器回哪种包"，不保证"一定能装"。目标版本高于当前能装；
     *   降级仍会被 update_engine 对 oeminfo 的校验拦下，那条路要动分区，
     *   本模块不碰。
     */
    private boolean hookPackageType(ClassLoader cl) {
        try {
            Class<?> w1 = Class.forName(Const.CLS_PKG_TYPE, false, cl);
            Method m = w1.getDeclaredMethod(Const.MTH_PKG_TYPE);
            // private static：必须开放访问，否则 hook 时取不到方法体
            m.setAccessible(true);

            hook(m).intercept(chain -> {
                // 开关关着就走原版逻辑，一行不多做。
                // 每次重读文件，用户在设置里改完立刻生效，不需要重启 OUC。
                if (!SwitchStore.isForceFullPackage()) {
                    return chain.proceed();
                }
                // 恒定请求全量包。注意这里不 proceed：
                // 原版返回值是 "upgrade" 时我们要覆盖它，直接给字符串更省事，
                // 也避开 proceedWith 对返回类型的反射校验。
                log(Log.INFO, TAG, "强制全量包：x2() 返回 " + Const.PKG_TYPE_RECOVERY);
                return Const.PKG_TYPE_RECOVERY;
            });

            log(Log.INFO, TAG, "已挂 " + Const.CLS_PKG_TYPE + "." + Const.MTH_PKG_TYPE + "()");
            return true;
        } catch (Throwable t) {
            // 挂不上只是没有全量包功能，不影响已经工作的屏蔽与本地更新。
            log(Log.ERROR, TAG, "挂 " + Const.CLS_PKG_TYPE + "." + Const.MTH_PKG_TYPE + " 失败: " + t);
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 下面是 com.android.settings 的作用域，只处理那个红点
    // ═══════════════════════════════════════════════════════════════

    /** 设置进程只挂一次。 */
    private volatile boolean settingsHooked;

    /**
     * 设置进程里的入口。
     *
     * 设置进程只做一件事：把 hn_new_system_update 的读取值压成 0。
     * 其余 hook（面板、全量包）在这个进程里没有意义，不挂。
     */
    private void hookSettingsScope(ClassLoader cl) {
        if (settingsHooked) {
            return;
        }
        log(Log.INFO, TAG, "onPackageReady: " + Const.PKG_SETTINGS);
        boolean g = hookSecureGetInt(cl);
        if (g) {
            settingsHooked = true;
        }
    }

    /**
     * 挂 Settings.Secure.getInt(ContentResolver, String, int)，只拦一个键。
     *
     * ── 为什么用兜底开关而不是直接读配置 ──
     *   这个类跑在 com.android.settings 进程，和 OUC 不是同一个 uid。
     *   屏蔽开关文件 /data/local_ota/module_switch.conf 的 SELinux 标签是
     *   local_ota_file，放行名单只有 adbd / platform_app_all / shell /
     *   update_engine 四个域（从 system_ext_sepolicy.cil 抽出来的全部规则）。
     *   『系统设置』是 system_app 域，不在名单里，读不到这个文件。
     *   所以这里退一步：
     *     · 开关默认值是「开」（DEFAULT_BLOCK_UPDATE = true），
     *       读不到就按「屏蔽生效」处理，这与模块的默认行为一致；
     *     · 为了不把用户「主动关掉屏蔽」的意图吞掉，另加一个
     *       进程内可见的兜底：读不到配置时，只要读到的值不是 0
     *       就仍然压成 0——因为用户装这个模块就是为了屏蔽。
     *   代价是：在设置进程里关不掉这个行为。
     *   这一点写进 README，不藏着。
     *
     * ── 为什么改返回值而不是拦掉调用 ──
     *   原方法是 public static，4 个调用点拿到的都是 int。
     *   拦掉会让调用方拿到默认值 0，效果一样但要构造返回值；
     *   直接 proceed 再改结果，语义最贴近「这个设置项就是 0」。
     */
    private boolean hookSecureGetInt(ClassLoader cl) {
        try {
            Class<?> secure = Class.forName(Const.CLS_SECURE, false, cl);
            Method getInt = secure.getDeclaredMethod(
                    Const.MTH_GET_INT,
                    android.content.ContentResolver.class, String.class, int.class);
            getInt.setAccessible(true);
            hook(getInt).intercept(chain -> {
                Object ret = chain.proceed();
                Object[] a = chain.getArgs().toArray();
                // a[1] 是 key。只处理这一个键，其余设置项原样返回。
                if (a.length >= 2 && Const.KEY_HN_NEW_SYSTEM_UPDATE.equals(a[1])) {
                    if (!(ret instanceof Integer) || ((Integer) ret) != Const.NO_NEW_UPDATE) {
                        log(Log.INFO, TAG, "设置读 "
                                + Const.KEY_HN_NEW_SYSTEM_UPDATE + "=" + ret
                                + " -> " + Const.NO_NEW_UPDATE);
                    }
                    return Const.NO_NEW_UPDATE;
                }
                return ret;
            });
            log(Log.INFO, TAG, "已挂 Settings.Secure.getInt，"
                    + Const.KEY_HN_NEW_SYSTEM_UPDATE + " 读取值将恒为 0");
            return true;
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "挂 Settings.Secure.getInt 失败: " + t);
            return false;
        }
    }

    /**
     * 挂 r9.w1.V6(Context,int)，把写进 Settings.Secure 的「新版本条数」压成 0。
     *
     * ── 为什么必须有这个 hook ──
     *   实测那个红点在 com.android.settings 里，不在 OUC 进程里：
     *     活动：Settings$HwSystemDashboardActivity
     *     控件：id update_count (0x7f0a0fbc)，类 HwTextView，text 为 1
     *   它读的是 Settings.Secure 的 hn_new_system_update，读代码已确认：
     *     com.android.settings.dashboard.r0.b()
     *       getInt(cr, "hn_new_system_update", 0) -> SystemUpdatePreference.Q(n)
     *       -> onBindViewHolder 里 n > 0 就把 FrameLayout 设为 VISIBLE
     *   而写这个键的只有一处，就是本进程的 r9.w1.V6(Context,int)：
     *     Settings.Secure.putInt(cr, "hn_new_system_update", n)
     *     再发广播 com.hihonor.android.hnouc.HW_NEW_SYSTEM_UPDATE
     *   所以哪怕 OUC 自己界面的角标全压掉了，只要这个键不是 0，
     *   设置页那个红点就一直在。上一版只挂了 HnEventBadgeView，
     *   对它无效，就是因为两者根本不在同一个进程里。
     *
     * ── 为什么不挂 Settings.Secure.putInt ──
     *   那会把 OUC 里所有 Secure 写入都拦下来，还要按 key 字符串区分，
     *   范围太大。V6 的入参语义就是「新版本条数」，是最小改动面。
     *
     * ── 为什么改参数而不是拦掉整个方法 ──
     *   V6 除了写 Secure，还负责「值变了才发广播」以及 d0() 成立时走 t3()。
     *   直接 return 会把这些副作用一起吞掉，留下「Secure 是 0、
     *   但内部仍认为有更新」的不一致。把入参改成 0 再放行，其余逻辑照跑。
     *   它内部有 if (i2 != X1(q)) 的比较：改成 0 后若原值已是 0 就整体
     *   跳过，正好不会乱发广播。
     *
     * ── 语义安全性 ──
     *   0 表示「无新版本」，这是原版自己的用法：
     *   r9.w1.D3() 在安装完成后就调 V6(ctx, 0) 清零，
     *   并且 D3() 里还有 if (iX1 == 0) { 不处理 } 的分支。
     *   所以写 0 不会引入原版不会出现的状态。
     *
     * ── 开关 ──
     *   与其它 hook 共用 isBlockEffective()：屏蔽关掉、或强制全量包
     *   开着时原样放行，保留真实红点。
     */
    private boolean hookBadgeCount(ClassLoader cl) {
        try {
            Class<?> w1 = Class.forName(Const.CLS_PKG_TYPE, false, cl);
            Method v6 = w1.getDeclaredMethod("V6", Context.class, int.class);
            v6.setAccessible(true);
            hook(v6).intercept(chain -> {
                if (!SwitchStore.isBlockEffective()) {
                    return chain.proceed();
                }
                Object[] args = chain.getArgs().toArray();
                if (args.length == 2 && args[1] instanceof Integer
                        && ((Integer) args[1]) != Const.NO_NEW_UPDATE) {
                    log(Log.INFO, TAG, "红点计数 " + args[1] + " -> " + Const.NO_NEW_UPDATE);
                    args[1] = Const.NO_NEW_UPDATE;
                }
                Object ret = chain.proceed(args);
                // ── 为什么光改入参不够 ──
                //   V6 内部把「写 Secure」和「写桌面角标」都放在同一个
                //   if (p1 != X1(ctx)) 里。X1() 读的是 Secure 表当前值，
                //   一旦那里已经是 0（比如本模块上一版清过一次），
                //   把入参压成 0 反而使条件不成立，整块被跳过：
                //   Secure 不用写是对的，但 t3() 也不会执行，
                //   桌面图标上那个角标就永远停在旧值 1。
                //   这正是「设置页红点没了、桌面图标还有」的原因。
                //   所以在 proceed 之后再补一次写角标，把这条路补齐。
                if (args.length == 2 && args[0] instanceof Context) {
                    clearLauncherBadge((Context) args[0]);
                }
                return ret;
            });
            log(Log.INFO, TAG, "已挂 " + Const.CLS_PKG_TYPE + ".V6(Context,int)，"
                    + Const.KEY_HN_NEW_SYSTEM_UPDATE + " 与桌面角标将恒为 0");
            return true;
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "挂 " + Const.CLS_PKG_TYPE + ".V6 失败: " + t);
            return false;
        }
    }

    /**
     * 把桌面「设置」图标的角标清零。
     *
     * ── 为什么不用 ContentResolver.update/delete ──
     *   badge 表虽有 content://com.android.badge/badge 这个读取口，
     *   但 update 过去返回 0 行（provider 不把写权限放给 update），
     *   delete 同理。荣耀只开放了 call() 这一个写入口，方法名
     *   change_badge、带 package/class/badgenumber 三个 extra，
     *   也就是 OUC 自己 t3() 用的那条路。这里照原样调。
     *
     * ── 为什么按 (package, class) 精确写，不是整表清 ──
     *   同一张表还存着 30 多个应用的角标（应用市场 224、QQ 25、
     *   小红书 46…），整表清会把它们的角标一起抹掉。这里只写
     *   com.android.settings + HWSettings 这一行，其余不受影响。
     */
    private void clearLauncherBadge(Context ctx) {
        try {
            Bundle b = new Bundle();
            b.putString("package", Const.PKG_SETTINGS);
            b.putString("class", Const.CLS_SETTINGS_ENTRY);
            b.putInt("badgenumber", Const.NO_NEW_UPDATE);
            Bundle r = ctx.getContentResolver().call(
                    Uri.parse(Const.URI_LAUNCHER_BADGE),
                    Const.MTH_CHANGE_BADGE, "", b);
            log(Log.INFO, TAG, "桌面角标已清零: " + (r == null ? "null" : r.getString("result")));
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "清桌面角标失败: " + t);
        }
    }

    /**
     * 挂 HnEventBadgeView.setBadgeVisible(boolean)，让红点永远不显示。
     *
     * ── 为什么挂这里，而不是业务层的判断 ──
     *   红点由荣耀自家的 HnEventBadgeView 绘制，最终靠 setVisibility 控制。
     *   该方法的 invoke 调用方共 7 处，全部在 HnEventBadgeView 自己和同包
     *   辅助类 widget/b 内部——无论业务层从哪条路决定「要显示红点」，
     *   最后都会汇到这一个方法上。
     *
     *   挂在业务层就必须先知道「哪个字段或哪条记录决定红点」，而
     *   HAS_NEW_ROM / HAS_NEW_VERSION 这类键在整个 dex 里没有任何
     *   const-string 引用，说明是动态拼接写入的，追起来成本高且容易漏。
     *   挂收口方法只依赖一个字符串，代价小得多。
     *
     * ── 为什么传新参数而不是直接 return ──
     *   原版签名 (Z)V，参数就是「是否显示」。用 proceed(Object[]) 传入
     *   false 后仍让原方法照常执行，它内部的 setVisibility 等状态维护
     *   就都还在；直接 return null 会留下「数据层认为该显示、View 层没
     *   设置」的不一致。
     *
     *   这里不能用 setArg：libxposed 102 的 Chain 只有 getArg/getArgs，
     *   修改入参的唯一途径是 proceed(Object[]) 或 proceedWith。
     *
     * ── 开关 ──
     *   与另外两个 hook 共用「屏蔽系统更新」这一个开关
     *   （isBlockEffective，强制全量包开着时自动失效）。
     *   用户关掉屏蔽就是想恢复真实提示，红点也该跟着回来。
     *
     * ── 局限 ──
     *   只压本进程内这个 View 的红点。若某个红点绕开该组件自绘
     *   （例如桌面图标角标由 launcher 按通知数自行绘制），不在覆盖范围内。
     *   实测本机 com.hihonor.ouc 没有挂任何通知，当前不存在这条路径。
     */
    private boolean hookBadge(ClassLoader cl) {
        try {
            Class<?> badge = Class.forName(Const.CLS_BADGE, false, cl);
            Method m = badge.getDeclaredMethod(Const.MTH_BADGE, boolean.class);
            hook(m).intercept(chain -> {
                // 开关关着就原样放行，保留真实的红点行为。
                if (!SwitchStore.isBlockEffective()) {
                    return chain.proceed();
                }
                // 把「是否显示」改成 false，再交给原方法执行。
                // 注意：libxposed 102 的 Chain 没有 setArg，
                // 只能构造一个新的参数数组走 proceed(Object[]) 重载
                // （见 XposedInterface.Chain#proceed(Object[])）。
                return chain.proceed(new Object[]{Boolean.FALSE});
            });
            log(Log.INFO, TAG, "已挂 " + Const.CLS_BADGE + "." + Const.MTH_BADGE
                    + "(boolean)，红点将不显示");
            return true;
        } catch (Throwable t) {
            // 挂不上只是红点不受控，不影响其余三个功能。
            log(Log.ERROR, TAG, "挂 " + Const.CLS_BADGE + "." + Const.MTH_BADGE + " 失败: " + t);
            return false;
        }
    }

    /** 把面板挂到承载它的 Activity 上，同一时刻只保留一个。 */
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