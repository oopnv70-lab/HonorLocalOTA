plugins {
    alias(libs.plugins.agp.app)
}

android {
    namespace = "io.github.honorlocalota"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "2.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
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