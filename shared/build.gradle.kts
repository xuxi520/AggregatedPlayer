import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.detekt)
}

detekt {
    // 当前阶段仅报告、不阻断构建；待收敛后再收紧（见设计文档 §5.1 静态检查）
    buildUponDefaultConfig = true
    allRules = false
    ignoreFailures = true
    config.setFrom(files("detekt.yml"))
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    // 不分析 Gradle 生成的代码（如 Compose 资源访问器），避免误报
    // 注意：生成源码的 srcDir 本身就是 build/generated 目录，SourceTask 的排除模式是相对
    // 该目录的，因此要用绝对路径判断。
    exclude { it.file.path.replace('\\', '/').contains("/build/generated/") }
}

kotlin {
    jvm()
    android {
       namespace = "org.aggregated.player.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }
    sourceSets {
        val jvmMain by getting {
            dependencies {
                // JCEF（CEF 的 Java 绑定）：桌面端内嵌浏览内核（阶段 5）。
                // 用 **JCEF 发行包自带的 jar**（与 natives 同版本）：Maven 上的 `dev.datlag:jcef`
                // 与本仓库 natives 版本不一致时，JNI 会报 "Failed to get CEF version"。
                // 解压方式见 README §阶段 5：把 JCEF 发行包解压到 `shared/libs/desktop/cef/`。
                // 文件缺失时整块跳过——保证没放 natives 的机器仍可编译（运行时退回外链浏览器模式）。
                val cefBin = file("libs/desktop/cef/bin")
                if (cefBin.resolve("jcef.jar").exists()) {
                    implementation(files("libs/desktop/cef/bin/jcef.jar"))
                    implementation(files("libs/desktop/cef/bin/jogl-all.jar"))
                    implementation(files("libs/desktop/cef/bin/gluegen-rt.jar"))
                    // JOGL 的 native dll（JOGL 负责加载 libcef.dll），与发行包同版本
                    implementation(files("libs/desktop/cef/bin/jogl-all-natives-windows-amd64.jar"))
                    implementation(files("libs/desktop/cef/bin/gluegen-rt-natives-windows-amd64.jar"))
                }
            }
        }
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
            // AndroidApp 的返回键处理：页面历史优先于退出应用（设计文档 §4.2）
            implementation(libs.androidx.activity.compose)
            // 增强播放覆盖层：自封装 Media3（路径 B）；4-B 起直链 + HLS 共用同一份带防盗链头的 DataSource
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.hls)
            implementation(libs.media3.exoplayer.dash)
            implementation(libs.media3.database)
            implementation(libs.media3.datasource)
            implementation(libs.media3.ui)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            // 共享层的异步与序列化基建（见设计文档 §4.7、§4.8）
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // 共享层的仓储是挂起函数，需要协程测试支撑（runTest）
            implementation(libs.kotlinx.coroutinesTest)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.testRunner)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}