package org.aggregated.player

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.aggregated.player.platform.web.AndroidBrowserHosts
import org.aggregated.player.platform.web.VideoFullscreenBridge
import org.aggregated.player.platform.web.VideoFullscreenHost
import org.aggregated.player.ui.AndroidApp

/**
 * Android 宿主 Activity。
 *
 * 阶段 1 起它只做三件事：开启边到边、把组合交给共享层入口 [AndroidApp]、**不干扰实例池**。
 * 需要特别注意的两点（设计文档 §4.2）：
 * 1. Manifest 里已声明 `configChanges`，让旋转等操作不重建 Activity——重建会重挂容器，
 *    实例虽在池里不会没，但多余的一摘一挂容易引入渲染问题；
 * 2. 返回键由共享层 `MainScreen` 的 `PlatformBackHandler` 统一接管（设置/工具/侧栏 > 网页后退 > 主页面连按两次退出），
 *    这里不要再加常驻的 `onBackPressed` 逻辑，否则两处会抢同一个事件。
 *
 * 阶段 1.5 新增：作为 [VideoFullscreenHost] 承接网页 `<video>` 的全屏请求。
 * 返回键退出全屏**临时**插队到 Compose 的 `BackHandler` 之前（在 `showCustomView` 时注册、最晚添加即最高优先级），
 * 退出全屏后该回调即移除，恢复由共享层接管。
 */
class MainActivity : ComponentActivity(), VideoFullscreenHost {

    /** 全屏时承载视频表面的黑色铺满层；动态挂到 `android.R.id.content` 之上。 */
    private val fullscreenContainer by lazy {
        android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            visibility = View.GONE
        }
    }

    private var fullscreenView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var backCallback: OnBackPressedCallback? = null

    /** 全屏方向循环：自动(跟随设备) → 锁定横屏 → 锁定竖屏。默认自动，兼顾竖屏视频与手动切换。 */
    private val orientationCycle = intArrayOf(
        ActivityInfo.SCREEN_ORIENTATION_SENSOR,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
    )
    private var fullscreenOrientationIndex = 0
    private var fullscreenRotateButton: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // 边到边显示：WebView 内容区需要吃满屏幕，站点页面的安全区由页面自己处理
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AndroidApp()
        }

        // 注册为全屏宿主：WebView 的 onShowCustomView 经共享层桥接到这里
        VideoFullscreenBridge.register(this)

        // 离线下载经前台服务跑，Android 13+ 必须授予 POST_NOTIFICATIONS 才能 startForeground。
        // 首次进入且未授权时请求一次；拒绝也不阻断（下载器会兜底提示），只是 Android 13 不显示进度通知、
        // Android 14 直接失败。放在宿主层而非 Compose 按钮，因权限 API 是 Android 专属、不能进 commonMain。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 2001
    }

    override fun onStart() {
        super.onStart()
        // 回前台：撤销 onAppBackground 的降载（恢复进程级定时器 + 活动标签的 WebView.onResume()）。
        // 否则 WebView 一直停在暂停态，切后台回来就是白屏——只有重新点进该标签触发 resume 才恢复。
        AndroidBrowserHosts.peek()?.runtime?.onAppForeground()
    }

    override fun onStop() {
        // 应用退到后台时若存在全屏，先收起，避免覆盖层卡在界面上（视频本身也会随 onPause 暂停）
        if (fullscreenView != null) hideCustomView()
        super.onStop()
    }

    override fun onDestroy() {
        VideoFullscreenBridge.unregister(this)
        super.onDestroy()
    }

    // region VideoFullscreenHost

    override fun showCustomView(
        view: View,
        requestedOrientation: Int,
        callback: WebChromeClient.CustomViewCallback,
    ) {
        // 防止重复进入：先把上一次的清掉（会通知页面退出旧的全屏）
        if (fullscreenView != null) hideCustomView()

        fullscreenView = view
        customViewCallback = callback

        fullscreenContainer.removeAllViews()
        fullscreenContainer.addView(
            view,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        val content = findViewById<ViewGroup>(android.R.id.content)
        content.addView(fullscreenContainer)
        fullscreenContainer.bringToFront()
        fullscreenContainer.visibility = View.VISIBLE

        // 记忆进入全屏前的方向（退出时还原）。默认「自动跟随设备」(SCREEN_ORIENTATION_SENSOR)：
        // 它临时覆盖系统旋转锁，竖屏视频竖着全屏、横屏视频横着全屏，用户旋转手机即切换；
        // 页面若显式指定方向则以其为准（不在循环内则回落到自动）。
        previousOrientation = this.requestedOrientation
        val initial = if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            requestedOrientation
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
        fullscreenOrientationIndex = orientationCycle.indexOf(initial).let { if (it < 0) 0 else it }
        this.requestedOrientation = orientationCycle[fullscreenOrientationIndex]

        enterImmersive()
        // 右上角旋转/锁定按钮：自动 → 横屏 → 竖屏 循环
        setupRotateButton()

        // 返回键优先退出全屏：此刻注册，确保优先级高于 Compose 的 BackHandler
        backCallback?.remove()
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                hideCustomView()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback!!)
    }

    override fun hideCustomView() {
        val callback = customViewCallback ?: run {
            cleanupFullscreen()
            return
        }
        cleanupFullscreen()
        callback.onCustomViewHidden()
        customViewCallback = null
        fullscreenView = null
    }

    /** 移除视频表面、还原界面方向与系统栏、注销返回键回调。幂等。 */
    private fun cleanupFullscreen() {
        backCallback?.remove()
        backCallback = null
        fullscreenRotateButton = null

        fullscreenContainer.removeAllViews()
        fullscreenContainer.visibility = View.GONE
        val content = findViewById<ViewGroup>(android.R.id.content)
        content.removeView(fullscreenContainer)

        // 还原到进入全屏前的方向（进入时 previousOrientation 已记下当时值）
        this.requestedOrientation = previousOrientation
        exitImmersive()
    }

    /** 全屏时在右上角放一个轻量旋转/锁定按钮，循环 自动 → 横屏 → 竖屏。 */
    private fun setupRotateButton() {
        val btn = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(28, 16, 28, 16)
            // 半透明黑底圆角，保证压在视频上仍清晰可点
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(0x99, 0, 0, 0))
                cornerRadius = 22f
            }
            setOnClickListener { cycleFullscreenOrientation() }
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            // 避开顶部状态栏/手势区
            topMargin = (resources.displayMetrics.density * 40).toInt()
            rightMargin = (resources.displayMetrics.density * 16).toInt()
        }
        btn.layoutParams = lp
        fullscreenContainer.addView(btn)
        fullscreenRotateButton = btn
        updateRotateLabel()
    }

    /** 切换全屏方向：自动 → 横屏 → 竖屏 → 自动。 */
    private fun cycleFullscreenOrientation() {
        fullscreenOrientationIndex = (fullscreenOrientationIndex + 1) % orientationCycle.size
        this.requestedOrientation = orientationCycle[fullscreenOrientationIndex]
        updateRotateLabel()
    }

    private fun updateRotateLabel() {
        fullscreenRotateButton?.text = when (orientationCycle[fullscreenOrientationIndex]) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR -> "自动"
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> "横屏"
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT -> "竖屏"
            else -> "自动"
        }
    }

    private fun enterImmersive() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(
            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars(),
        )
    }

    private fun exitImmersive() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(
            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars(),
        )
    }

    // endregion
}
