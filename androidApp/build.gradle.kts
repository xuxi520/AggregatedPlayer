import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "org.aggregated.player"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.aggregated.player"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    /**
     * Release 签名。
     *
     * 优先使用正式证书：从 gradle 属性或环境变量读取
     * （APK_STORE_FILE / APK_STORE_PASSWORD / APK_KEY_ALIAS / APK_KEY_PASSWORD）。
     * 未配置时**回退到 debug 证书**——只为让本地能直接出可安装的 release 包，
     * 正式分发前必须配置正式证书（否则包只能用 debug 证书签名，无法上架/无法覆盖安装正式签名的旧包）。
     */
    signingConfigs {
        create("release") {
            val storePath = providers.gradleProperty("APK_STORE_FILE").orNull
                ?: System.getenv("APK_STORE_FILE")
            val storePwd = providers.gradleProperty("APK_STORE_PASSWORD").orNull
                ?: System.getenv("APK_STORE_PASSWORD")
            val alias = providers.gradleProperty("APK_KEY_ALIAS").orNull
                ?: System.getenv("APK_KEY_ALIAS")
            val keyPwd = providers.gradleProperty("APK_KEY_PASSWORD").orNull
                ?: System.getenv("APK_KEY_PASSWORD")
            if (storePath != null && storePwd != null && alias != null && keyPwd != null) {
                storeFile = file(storePath)
                storePassword = storePwd
                keyAlias = alias
                keyPassword = keyPwd
            } else {
                // 回退：debug 证书（仅供本地验证混淆后的 release 构建链路）
                storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            // Release 开启混淆 + 资源裁剪；规则见 proguard-rules.pro
            // （序列化模型、WebView JS 桥、枚举常量名等必须放行，否则线上功能静默失效）
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}
