package io.github.oopnv70_lab.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import org.luckypray.dexkit.DexKitBridge;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在本模块自己的进程里，对「软件更新」APK 做一次目标定位。
 *
 * ══════════════════════════════════════════════════════════════
 * 这个类存在的理由
 * ══════════════════════════════════════════════════════════════
 * 定位的本质是**静态分析 dex**：读 APK 里的 dex，按语义特征找类和方法。
 * 它和「运行在谁的进程里」没有关系——不需要加载目标类，不需要调用目标
 * 方法，只需要能读到那个 APK 的文件。
 *
 * 之前把定位放在 com.hihonor.ouc 进程里跑，是被「hook 要在那个进程里」
 * 这件事带偏了：hook 确实必须在宿主进程，但**定位不必**。定位结果一旦
 * 算出来，写进缓存文件，宿主进程启动时读缓存即可。
 *
 * 放到本进程带来三个直接好处：
 *   1. 用户能自己点一下就看到结果，不用装 hook、不用重启宿主、不用
 *      翻 logcat 才知道适配成不成功。
 *   2. 换版本/换设备时，先在这里验证一遍，成功了再去用，失败当场可见。
 *   3. 不需要 root：读别的应用的 base.apk 只要 READ_EXTERNAL /
 *      清单可见性允许即可，普通权限足够；这里的 APK 权限本来就是
 *      全员可读（-rw-r--r--）。
 *
 * ── 与 TargetResolver 的关系 ──
 *   TargetResolver 是引擎，这里只负责「喂它什么」：
 *     给它 OUC 的 APK 路径、null 的 ClassLoader（表示不在宿主进程）、
 *     一个缓存命名空间，然后拿回每个目标的定位结果。
 *   ClassLoader 传 null 是关键——TargetResolver 里所有需要验证类是否
 *   存在的地方，都改成走 DexKit 查 dex 了（见 classExists / methodExists
 *   / shapeMatches 的三处注释）。
 */
final class TargetProbe {
    private static final String TAG = "HonorLocalOTA";

    /** 缓存目录。放本 App 私有目录，不需要 root。 */
    private static final String CACHE_NAME = "resolved_targets.conf";

    private TargetProbe() {
    }

    /** 一次探测的结果：每个目标的定位情况，外加整体是否可用。 */
    static final class Report {
        /** 按 HookTarget.ALL 顺序的定位结果。 */
        final Map<String, TargetResolver.Resolved> results = new LinkedHashMap<>();
        /** OUC 是否装了。 */
        boolean hostInstalled;
        /** 找到的 APK 路径，null 表示没找到。 */
        String apkPath;
        /** APK 里的 dex 数量，0 表示没读到。 */
        int dexCount;
        /** libdexkit.so 是否加载成功。 */
        boolean nativeReady;
        /** 出过的错，非空时说明这一轮不完整。 */
        final List<String> errors = new ArrayList<>();

        /** 有几个目标是真正定位到的（DexKit 或硬编码都算）。 */
        int resolvedCount() {
            int n = 0;
            for (TargetResolver.Resolved r : results.values()) {
                if (r.className != null) {
                    n++;
                }
            }
            return n;
        }

        /** 拿到某个目标的结果，没有则 null。 */
        TargetResolver.Resolved get(String id) {
            return results.get(id);
        }

        /** 一句话概括，给界面顶部的状态行用。 */
        String summary() {
            if (!hostInstalled) {
                return "没找到「软件更新」，本机可能不是荣耀系统";
            }
            if (!nativeReady) {
                return "libdexkit.so 加载失败，无法定位";
            }
            if (apkPath == null) {
                return "找到了「软件更新」但读不到它的安装包";
            }
            return "已定位 " + resolvedCount() + " / " + results.size()
                    + " 个目标（APK dex " + dexCount + " 个）";
        }
    }

    /**
     * 执行一次探测。
     *
     * 全部在调用线程跑，调用方负责放到后台线程——DexKit 首次解析
     * 一个几十 MB 的 APK 要几秒，放主线程必 ANR。
     *
     * 任何一步失败都不抛：把原因写进 Report.errors 和 Report 的字段，
     * 让界面能一字一句告诉用户卡在哪，而不是笼统的「失败了」。
     */
    static Report probe(Context ctx) {
        Report rep = new Report();

        // ── 一、找到 OUC 的 APK ──
        ApplicationInfo info = null;
        try {
            PackageManager pm = ctx.getPackageManager();
            info = pm.getApplicationInfo(Const.PKG_OUC, 0);
        } catch (Throwable t) {
            Log.w(TAG, "getApplicationInfo(" + Const.PKG_OUC + ") 失败: " + t);
        }
        if (info == null) {
            rep.hostInstalled = false;
            rep.errors.add("查询不到 com.hihonor.ouc 的安装信息");
            return rep;
        }
        rep.hostInstalled = true;
        rep.apkPath = pickApk(info);
        if (rep.apkPath == null) {
            rep.errors.add("安装信息里没有可读的 APK 路径：sourceDir=" + info.sourceDir
                    + " splitSourceDirs=" + (info.splitSourceDirs == null
                    ? "null" : info.splitSourceDirs.length + " 个"));
            return rep;
        }

        // ── 二、加载 libdexkit.so ──
        // 在自己的进程里，nativeLibraryDir 就是本 App 的 lib 目录，
        // System.loadLibrary 直接能命中，不需要 DexKitLoader 那套
        // 「从模块 APK 里按绝对路径加载」的绕路（那套是给宿主进程用的）。
        rep.nativeReady = loadNative(ctx);
        if (!rep.nativeReady) {
            rep.errors.add("libdexkit.so 加载失败，见 logcat");
            return rep;
        }

        // ── 三、创建 bridge 并解析 ──
        TargetResolver resolver = new TargetResolver(null);
        try {
            DexKitBridge b = DexKitBridge.create(rep.apkPath);
            if (b == null) {
                rep.errors.add("DexKitBridge.create 返回 null");
                return rep;
            }
            rep.dexCount = b.getDexNum();
            resolver.attachBridge(b);
        } catch (Throwable t) {
            Log.e(TAG, "创建 DexKitBridge 失败: " + t);
            rep.errors.add("创建 DexKitBridge 失败: " + t);
            return rep;
        }

        // ClassLoader 传 null：明确告诉引擎「这里不是宿主进程」，
        // 所有类存在性验证都走 DexKit。
        Map<String, TargetResolver.Resolved> out;
        try {
            out = resolver.resolveAll(HookTarget.ALL, null, rep.apkPath, cacheTag(ctx, rep));
        } catch (Throwable t) {
            Log.e(TAG, "resolveAll 抛异常: " + t);
            rep.errors.add("解析过程异常: " + t);
            return rep;
        }
        for (HookTarget t : HookTarget.ALL) {
            TargetResolver.Resolved r = out.get(t.id);
            if (r == null) {
                r = new TargetResolver.Resolved(null, null, new String[0], "none");
            }
            rep.results.put(t.id, r);
        }
        return rep;
    }

    /**
     * 选一个能读的 APK 路径。
     *
     * 优先 sourceDir（主 APK，绝大多数混淆类都在里面）。
     * 主 APK 读不到时再去 splitSourceDirs 里找——荣耀这种定制 ROM
     * 有可能把某些代码放进 split。读不到就返回 null，让上层报错，
     * 不要拿一个不存在/不可读的路径去喂 DexKit，那个失败信息更难懂。
     */
    private static String pickApk(ApplicationInfo info) {
        if (info.sourceDir != null && readable(info.sourceDir)) {
            return info.sourceDir;
        }
        if (info.splitSourceDirs != null) {
            for (String s : info.splitSourceDirs) {
                if (s != null && readable(s)) {
                    return s;
                }
            }
        }
        // 主 APK 存在但不可读时，也返回它：让 DexKit 报出真实的错误
        // （比如权限问题），比在这里返回 null 更有信息量。
        if (info.sourceDir != null && new File(info.sourceDir).isFile()) {
            return info.sourceDir;
        }
        return null;
    }

    private static boolean readable(String path) {
        File f = new File(path);
        return f.isFile() && f.canRead();
    }

    /**
     * 加载 DexKit 的 native 库。
     *
     * 这里是本 App 自己的进程，libdexkit.so 就在自己的 nativeLibraryDir
     * 里，System.loadLibrary("dexkit") 一步到位。
     * 失败时补一次按绝对路径的尝试：极少数 ROM 的安装布局里
     * nativeLibraryDir 指向的目录是空壳，而 so 实际落在 APK 同级
     * 的 lib/<abi>/ 下。
     */
    private static boolean loadNative(Context ctx) {
        try {
            System.loadLibrary("dexkit");
            Log.i(TAG, "libdexkit.so 已通过 System.loadLibrary 加载");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "System.loadLibrary(dexkit) 失败，改按绝对路径试: " + t);
        }
        // 退化路径：本 App 自己的 ApplicationInfo，nativeLibraryDir
        // 是系统给的位置，sourceDir 的父目录是 APK 所在的装目录。
        try {
            ApplicationInfo self = ctx.getApplicationInfo();
            String abi = android.os.Build.SUPPORTED_ABIS.length > 0
                    ? android.os.Build.SUPPORTED_ABIS[0] : "arm64-v8a";
            String apkDir = self.sourceDir == null
                    ? null : new File(self.sourceDir).getParent();
            String[] cands = {
                    self.nativeLibraryDir == null
                            ? null : self.nativeLibraryDir + "/libdexkit.so",
                    apkDir == null ? null : apkDir + "/lib/arm64/libdexkit.so",
                    apkDir == null ? null : apkDir + "/lib/" + abi + "/libdexkit.so",
            };
            for (String p : cands) {
                if (p == null) {
                    continue;
                }
                File f = new File(p);
                if (!f.isFile() || !f.canRead()) {
                    continue;
                }
                try {
                    System.load(p);
                    Log.i(TAG, "libdexkit.so 已从 " + p + " 加载");
                    return true;
                } catch (Throwable t) {
                    Log.w(TAG, "从 " + p + " 加载失败: " + t);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "定位本 App 的 native 目录失败: " + t);
        }
        Log.e(TAG, "libdexkit.so 全部候选路径均失败");
        return false;
    }

    /**
     * 缓存命名空间。
     *
     * 用「包名 + APK 指纹」而不是 versionCode：CI 上拿不到 versionCode
     * （真实 SDK 37 里 ApplicationInfo.versionCode / longVersionCode
     * 对第三方不可见，本地 robolectric 的 android.jar 却暴露了，
     * 曾经造成「本地能编、CI 编不过」）。APK 指纹同样能区分版本，
     * 而且换包必变，比版本号更可靠。
     */
    private static String cacheTag(Context ctx, Report rep) {
        StringBuilder sb = new StringBuilder(Const.PKG_OUC);
        try {
            File f = new File(rep.apkPath);
            sb.append('@').append(f.length()).append('-').append(f.lastModified());
        } catch (Throwable t) {
            sb.append("@unknown");
        }
        return sb.toString();
    }

    /** 缓存文件位置。 */
    static File cacheFile(Context ctx) {
        return new File(ctx.getFilesDir(), CACHE_NAME);
    }
}
