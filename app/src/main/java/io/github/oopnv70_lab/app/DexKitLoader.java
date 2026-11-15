package io.github.oopnv70_lab.app;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.io.File;

/**
 * 把 libdexkit.so 加载进宿主进程。
 *
 * ── 为什么这一步是必需的，且不能靠 System.loadLibrary ──
 *   DexKit 是 C++ 实现，java 层只是壳，真正干活的是 libdexkit.so。
 *   但本模块的代码跑在 com.hihonor.ouc 进程里，那个进程的
 *   nativeLibraryPath 是它自己的（/data/app/.../com.hihonor.ouc-xxx/lib/arm64），
 *   里面根本没有 libdexkit.so。
 *   System.loadLibrary("dexkit") 会去宿主自己的目录里找，
 *   结果必然是 java.lang.UnsatisfiedLinkError: couldn't find "libdexkit.so"。
 *
 *   唯一可靠的路径是：拿到**模块自己 APK** 的安装位置，
 *   从它的 lib/<abi>/ 里按绝对路径 System.load()。
 *
 * ── 怎么拿到模块自己的 APK 路径 ──
 *   libxposed 102 的 XposedInterfaceWrapper 提供了
 *   getModuleApplicationInfo()（已用 javap 核实过存在），
 *   返回的是模块自己的 ApplicationInfo，sourceDir 就是模块 APK 的绝对路径。
 *
 *   这是本方案唯一的额外依赖，如果将来 libxposed 改了这个 API，
 *   只需要改这个类，其余逻辑不受影响。
 *
 * ── 为什么准备多个候选路径 ──
 *   Android 安装 APK 时，是否把 lib/ 解压到独立目录取决于
 *   useLegacyPackaging 的取值：
 *     · useLegacyPackaging = true  → 解压出 /data/app/.../lib/arm64/，
 *       so 是真实文件，可直接 System.load
 *     · useLegacyPackaging = false → so 只存在于 APK 内部，
 *       磁盘上没有独立文件，必须先解压出来
 *   我们已经在 app/build.gradle.kts 里打开了 useLegacyPackaging，
 *   但不同 ROM（尤其是荣耀这种深度定制）对安装行为的处理未必一致，
 *   所以这里按「nativeLibraryDir → APK 同级 lib/arm64 → APK 同级 lib/<abi>」
 *   逐级尝试。任何一级成功就返回，全都失败才报错——报错要明确，
 *   不能静默退化成「没适配」让用户去猜。
 */
final class DexKitLoader {

    private static final String TAG = "HonorLocalOTA";

    /** so 文件名。AAR 里就是这个名字，不能改。 */
    private static final String SO_NAME = "libdexkit.so";

    /** 已经加载成功就不用再试。失败也不重复试，避免每次 hook 都卡一遍。 */
    private static volatile Boolean sLoaded;

    private DexKitLoader() {
    }

    /**
     * 确保 libdexkit.so 已加载。
     *
     * @param moduleInfo 模块自己的 ApplicationInfo（由 XposedInterface 提供）
     * @return true 表示可用；false 表示加载失败，调用方必须走兜底路径
     */
    static synchronized boolean ensureLoaded(ApplicationInfo moduleInfo) {
        if (sLoaded != null) {
            return sLoaded;
        }
        if (moduleInfo == null) {
            Log.e(TAG, "moduleInfo 为 null，无法定位模块 APK");
            sLoaded = false;
            return false;
        }

        for (String path : candidates(moduleInfo)) {
            if (path == null) {
                continue;
            }
            File f = new File(path);
            if (!f.isFile() || !f.canRead()) {
                continue;
            }
            // 已经加载过同名库会抛「already loaded」，那也算成功。
            try {
                System.load(path);
                Log.i(TAG, "libdexkit.so 已从 " + path + " 加载");
                sLoaded = true;
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "从 " + path + " 加载失败: " + t);
            }
        }

        Log.e(TAG, "libdexkit.so 全部候选路径均失败，动态定位不可用。"
                + "模块 sourceDir=" + moduleInfo.sourceDir
                + " nativeLibraryDir=" + moduleInfo.nativeLibraryDir);
        sLoaded = false;
        return false;
    }

    /**
     * 按可信度排列候选路径。
     *
     * 顺序有意义，不要随意调：
     *   1. nativeLibraryDir —— 系统告诉我们的权威位置，最可信
     *   2. APK 同级 lib/arm64 —— 老式安装布局，很多定制 ROM 走这条
     *   3. APK 同级 lib/<Build.CPU_ABI> —— abi 目录名不一定是 arm64
     */
    private static String[] candidates(ApplicationInfo moduleInfo) {
        String abi = android.os.Build.SUPPORTED_ABIS.length > 0
                ? android.os.Build.SUPPORTED_ABIS[0]
                : "arm64-v8a";

        String nativeDir = moduleInfo.nativeLibraryDir;
        String apkDir = moduleInfo.sourceDir == null
                ? null
                : new File(moduleInfo.sourceDir).getParent();

        return new String[]{
                nativeDir == null ? null : nativeDir + "/" + SO_NAME,
                apkDir == null ? null : apkDir + "/lib/arm64/" + SO_NAME,
                apkDir == null ? null : apkDir + "/lib/" + abi + "/" + SO_NAME,
        };
    }
}