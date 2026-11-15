plugins {
    alias(libs.plugins.agp.app)
}

// 发布签名：CI 由 GitHub Secrets 注入（KEYSTORE_PATH / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD）。
// 找不到 keystore 时退回 debug 签名，保证本地和 fork 也能构建；
// 但 debug 签名每次构建都是新密钥，产物无法覆盖升级，不能用于发布。
// 见 workflow 里的 "Prepare signing key"，缺 secret 时那里会直接失败。
val keystorePath: String? = System.getenv("KEYSTORE_PATH")
val keystoreFile = keystorePath?.let { File(it) }
val hasReleaseKey: Boolean =
    keystoreFile?.exists() == true && !System.getenv("KEYSTORE_PASSWORD").isNullOrEmpty()

android {
    namespace = "io.github.oopnv70_lab.app"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        // applicationId 必须与提交到 LSPosed 仓库时使用的包名完全一致：
        // bot 会用 issue 标题里的包名建仓库，LSPosed 管理器再按 applicationId
        // 匹配更新。两边不一致时模块能装、也能正常 hook，但永远收不到更新。
        // 校验依据（Xposed-Modules-Repo/submission 的 bot.ts）：
        //   getPrefixLowercase(username) = "io.github." + username.toLowerCase().replaceAll('-','_') + "."
        // 因此必须是 io.github.oopnv70_lab.*，注意是下划线不是连字符。
        applicationId = "io.github.oopnv70_lab.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 13
        versionName = "2.11"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "oopnv70lab"
                keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("KEYSTORE_PATH / KEYSTORE_PASSWORD 未设置，release 将使用 debug 签名（不可发布）")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            // merges 优先级高于 excludes：这里的模式会被强制保留进 APK。
            // xposed 三件套（module.prop / java_init.list / scope.list）必须进 APK，
            // 否则 LSPosed 无法识别本模块；AGPL-3.0 要求随二进制附完整许可文本。
            merges += listOf(
                "META-INF/xposed/*",
                "META-INF/LICENSE",
                "META-INF/NOTICE"
            )
            excludes += "**"
        }
        jniLibs {
            // ── 这一行是 DexKit 能不能用起来的关键 ──
            // DexKit 的 findClass/findMethod 都是 native 实现，靠 libdexkit.so。
            // AGP 9 默认 useLegacyPackaging = false，会把 .so 以「不压缩、直接
            // 对齐存放」的方式打进 APK，运行时由 linker 直接从 APK 内映射，
            // 官方文档明确写了：这种模式下 System.loadLibrary 找不到它。
            // 而本模块的思路是「用 System.load(模块 APK 里的 so 绝对路径)」，
            // 需要 so 是磁盘上的真实文件，所以必须打开 legacy packaging，
            // 让 AGP 把 so 解压到 nativeLibraryDir。
            // 依据：https://luckypray.org/DexKit/zh-cn/guide/quick-start.html
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly(libs.libxposed.api)

    // ── DexKit：动态定位 target 的引擎 ──
    // 为什么不是 compileOnly：它自带 native 库 libdexkit.so，
    // 必须在运行时存在于模块 APK 里，hook 时再按绝对路径 System.load。
    // 为什么不是 implementation 的替代方案（自己解析 dex）：
    // 纯 Java 解析 dex 要处理 multidex、紧凑常量池、隐藏 API，
    // 在宿主进程里做还会踩内存和类加载时序，成本远高于引一个库。
    //
    // 许可：DexKit 是 LGPL-3.0 + Apache-2.0 双许可，本项目 AGPL-3.0。
    // LGPL 允许在 AGPL 项目中链接使用（AGPL 与 LGPL-3.0 兼容），
    // 但要求保留其许可声明——META-INF 的 LICENSE/NOTICE 已在上面
    // packaging.resources.merges 里强制保留。
    implementation(libs.dexkit)
}