package org.aggregated.player.domain.repository

import org.aggregated.player.domain.model.AdRule
import org.aggregated.player.domain.model.AdRuleKind

/**
 * 广告规则仓储（设计文档 §4.3）。
 *
 * 写入后调用方需要显式触发 `AdRuleEngine.reload()`：
 * 仓储不反向通知引擎，避免「谁依赖谁」的环，也让重建索引的时机掌握在使用方（可批量改完再重建）。
 */
interface AdRuleRepository {

    suspend fun loadAll(): List<AdRule>

    /**
     * 首次启动种子：仅当规则文件不存在时写入 [defaults]，幂等、不覆盖用户清空后的空文件。
     * 调用方应在 [loadAll] 之前调用一次（如 `AppViewModel.bootstrap`）。
     */
    suspend fun seedDefaults(defaults: List<AdRule>)

    /** 新增或按 id 覆盖。 */
    suspend fun upsert(rule: AdRule)

    suspend fun delete(ruleId: String)

    /** 启用/停用。单独成方法是因为它是排查误拦截时最高频的操作，UI 需要一个廉价开关。 */
    suspend fun setEnabled(ruleId: String, enabled: Boolean)

    /**
     * 点级标记入口：为 [siteId] 追加一条规则。
     *
     * @return 生成的规则；若 [pattern] 与既有规则重复则返回已存在的那条（幂等，便于 UI 提示「已标记过」）。
     */
    suspend fun addMarkedRule(siteId: String?, pattern: String, kind: AdRuleKind, note: String?): AdRule
}
