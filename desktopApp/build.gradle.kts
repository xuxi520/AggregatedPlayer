import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    // 共享层对 material3 是 implementation（不传递），桌面端自己的 Composable 需要显式引
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        // 入口函数所在类：Kotlin/JVM 会把 top-level main 编译为 <FileName>Kt
        mainClass = "org.aggregated.player.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "org.aggregated.player"
            packageVersion = "1.0.0"
        }
    }
}

// 运行期把 CEF natives 目录告诉 JCEF（libcef.dll / locales 所在层）。
// 未解压 natives 时该目录不存在，JCEF_BIN_DIR 指向空路径 → 代码判定 natives 缺失 → 退回外链浏览器模式。
tasks.withType<JavaExec>().configureEach {
    val cefWin64 = "${rootProject.projectDir}/shared/libs/desktop/cef/bin/lib/win64"
    environment("JCEF_BIN_DIR", cefWin64)
    // CEF 的 JNI 绑定库（chrome_elf.dll / libcef.dll）由 JOGL/gluegen 动态加载：
    // Gradle 会用自己的 java.library.path 覆盖 jvmArgs 里的同名参数，因此**双保险**——
    // 既追加 PATH（Windows 的 DLL 搜索会走它），也保留 -Djava.library.path。
    environment("PATH", "$cefWin64;${System.getenv("PATH")}")
    jvmArgs("-Djava.library.path=$cefWin64;${System.getProperty("java.library.path")}")
}