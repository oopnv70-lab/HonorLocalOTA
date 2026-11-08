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
        versionCode = 6
        versionName = "2.4"
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
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
}