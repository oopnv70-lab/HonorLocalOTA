-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
# 入口 Activity 由 AndroidManifest 按类名引用，保留类名与无参构造。
-keep class io.github.honorlocalota.MainActivity {
    public <init>();
}