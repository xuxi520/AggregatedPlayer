package org.aggregated.player.domain.model

import kotlinx.serialization.Serializable

/**
 * 悬浮触发器的停靠角落。
 *
 * 只是「吸附基准」——用户仍可在该角附近自由拖动摆放（见 [AppSettings.triggerOffsetXDp]）。
 * 在设置里切换本项会把自由偏移归零，让触发器重新贴回所选角落。
 */
@Serializable
enum class TriggerDock {
    TOP_LEFT,
    TOP_RIGHT,
}

/** 悬浮触发器的尺寸档位，映射到 UI 侧的具体 dp。 */
@Serializable
enum class TriggerSize {
    SMALL,
    MEDIUM,
    LARGE,
}

/**
 * 应用设置（设计文档 §4.7 新增 `settings.json`）。
 *
 * 全部字段带默认值，且 [kotlinx.serialization] 读侧对缺失字段用默认值兜底——
 * 老版本或手工编辑过的文件缺项也不会崩，直接落到合理默认。
 *
 * @param floatingTriggerEnabled 是否显示悬浮触发器；关闭后仅能靠侧栏/手势操作。
 * @param edgeSwipeDrawerEnabled 是否允许从屏幕边缘滑动唤起侧边栏。
 * @param triggerDock 触发器停靠角落。
 * @param idleDimmingEnabled 闲置一段时间后是否自动淡化触发器（减少遮挡）。
 * @param tapPageCollapseEnabled 点击网页时是否自动收起展开的气泡面板。
 * @param triggerSize 触发器尺寸档位。
 * @param triggerOffsetXDp 触发器中左上角为原点的绝对 X 坐标（dp）；`-1` 表示尚未手动拖动，按停靠角自动定位。
 * @param triggerOffsetYDp 绝对 Y 坐标（dp）；`-1` 同上。
 * @param adBlockEnabled 广告拦截全局总开关。关闭则所有站点一律放行；站点级开关（`Site.adBlockEnabled`）
 *   在其之上再做细粒度逃生口。默认开启（§4.3）。
 */
@Serializable
data class AppSettings(
    val floatingTriggerEnabled: Boolean = true,
    val edgeSwipeDrawerEnabled: Boolean = true,
    val triggerDock: TriggerDock = TriggerDock.TOP_LEFT,
    val idleDimmingEnabled: Boolean = true,
    val tapPageCollapseEnabled: Boolean = true,
    val triggerSize: TriggerSize = TriggerSize.MEDIUM,
    val triggerOffsetXDp: Float = -1f,
    val triggerOffsetYDp: Float = -1f,
    val adBlockEnabled: Boolean = true,

    /**
     * 全局默认播放引擎。默认 [PlaybackMode.WEBVIEW]（不抢站点原生播放器）；
     * 旧 `settings.json` 无此字段时按默认值降级，保证向后兼容。
     */
    val defaultPlaybackMode: PlaybackMode = PlaybackMode.WEBVIEW,
)
