package io.github.honorlocalota;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * 作用域：com.hihonor.ouc（荣耀软件更新 / MagicOS 11）。
 *
 * 背景：kh.l.C(Context) 是"是否支持本地更新"的三道判定入口：
 *   1. SystemPropertiesEx.get("msc.update.type") == "1"
 *   2. A() —— 当前 userId 必须为 0
 *   3. Settings.Secure["local_ota_for_developer"] == "1"
 * 三者全满足才返回 true，菜单里才出现"本地更新"。
 *
 * 本模块把判定强制为 true，作用仅限 OUC 这个 app 进程。
 * 注意 update_engine（native）会自己 getprop msc.update.type，hook 影响不到它；
 * 那部分由 MainActivity 里的 root 操作负责，见该类说明。
 */
public class OtaHook extends XposedModule {

    private static final String TAG = "HonorLocalOTA";

    private static final String CLS_L = "kh.l";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "loaded in " + param.getProcessName()
                + ", api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        ClassLoader cl = param.getClassLoader();
        log(Log.INFO, TAG, "ready: " + param.getPackageName());

        try {
            Class<?> clazz = Class.forName(CLS_L, false, cl);
            hook(clazz.getDeclaredMethod("C", android.content.Context.class))
                    .intercept(chain -> {
                        log(Log.DEBUG, TAG, "kh.l.C() forced true");
                        return true;
                    });
            log(Log.INFO, TAG, "hooked " + CLS_L + "#C(Context) -> true");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook kh.l.C failed: " + t);
        }
    }
}