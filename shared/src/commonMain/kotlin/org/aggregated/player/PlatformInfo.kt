package org.aggregated.player

/**
 * 运行平台标识。
 *
 * 只用于两件事：日志诊断、按平台族挑选容量默认值（例如保活标签上限，见设计文档 §4.2）。
 * **不要**用它做能力判断——能力判断一律走 `platform` 包里的接口，
 * 否则「平台 = 能力」的隐含耦合会让新增 target 时到处改业务代码。
 */
interface PlatformInfo {

    /** 人类可读的平台名，例如 `Android 34`、`Java 21`。仅用于展示与日志。 */
    val displayName: String

    /** 平台族，用于选择默认参数。 */
    val family: PlatformFamily
}

/**
 * 平台族。
 * [UNKNOWN] 是有意保留的兜底值：新增 target 时宁可走保守默认参数，也不要抛异常打断启动。
 */
enum class PlatformFamily {
    ANDROID,
    DESKTOP,
    UNKNOWN,
}

/**
 * 取得当前平台的标识信息。
 *
 * 采用 expect 工厂而非 `expect class`：平台实现通常需要构造参数或平台 API，
 * 工厂形式让 `di.AppGraph` 只需在启动时装配一次（见设计文档 §6）。
 */
expect fun createPlatformInfo(): PlatformInfo
