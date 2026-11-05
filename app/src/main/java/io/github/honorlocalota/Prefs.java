package io.github.honorlocalota;

/**
 * 开关状态的唯一来源。
 *
 * 关键约束：开关在我们自己的进程里改，真正读它的是 com.hihonor.ouc 进程里的
 * OtaHook。两个进程不共享 SharedPreferences（MODE_WORLD_READABLE 从 Android N
 * 起直接抛异常），所以走系统属性：
 *
 *   写入端：MainActivity 用 su 执行 setprop（有 root）。
 *   读取端：OtaHook 用 android.os.SystemProperties 读（任何进程可读）。
 *
 * 用 persist. 前缀是为了跨重启保留；debug. 前缀重启即丢，不适合做用户开关。
 */
final class Prefs {

    /** 总开关：关掉后三点菜单里不再出现我们那一项。 */
    static final String PROP_ENABLE = "persist.honorlocalota.enable";

    /** 二级开关：关掉后隐藏原版「本地更新」项，避免它弹"会格式化设备"的免责声明。 */
    static final String PROP_HIDE_STOCK = "persist.honorlocalota.hide_stock";

    /** 默认值：两项都开。开箱即用，且不会看到原版那个吓人的弹窗。 */
    static final boolean DEF_ENABLE = true;
    static final boolean DEF_HIDE_STOCK = true;

    private Prefs() {
    }

    /** 读开关。读不到时按 defaultValue 处理。 */
    static boolean isOn(String key, boolean defaultValue) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class)
                    .invoke(null, key, defaultValue ? "1" : "0");
            return "1".equals(String.valueOf(v).trim());
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }

    /** 通过 root 写开关，返回 su 输出便于界面直接显示成败。 */
    static String set(String key, boolean on) {
        String v = on ? "1" : "0";
        // setprop 的值不经 shell 再解析，所以不能加引号——加了会把引号写进值里。
        return Root.exec("setprop " + key + " " + v + "; getprop " + key);
    }
}