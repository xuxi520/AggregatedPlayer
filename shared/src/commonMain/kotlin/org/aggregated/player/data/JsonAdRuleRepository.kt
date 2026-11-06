package org.aggregated.player.data

import org.aggregated.player.core.Logger
import org.aggregated.player.domain.model.AdRule
import org.aggregated.player.domain.model.AdRuleKind
import org.aggregated.player.domain.model.Ids
import org.aggregated.player.domain.repository.AdRuleRepository
import org.aggregated.player.platform.JsonDocumentStore

/**
 * 基于 `ad_rules.json` 的规则仓储（设计文档 §4.3）。
 *
 * 只负责存取与幂等去重，**不做匹配**——匹配在无 IO 的 `AdRuleEngine` 里，
 * 这样 `shouldInterceptRequest` 的热路径不会碰到磁盘。
 */
class JsonAdRuleRepository(
    documentStore: JsonDocumentStore,
    logger: Logger,
    private val clock: () -> Long,
) : AdRuleRepository {

    private val records = JsonRecordStore(
        documentStore = documentStore,
        relativePath = FILE_NAME,
        itemSerializer = AdRule.serializer(),
        logger = logger,
    )

    override suspend fun loadAll(): List<AdRule> = records.readAll()

    override suspend fun seedDefaults(defaults: List<AdRule>) {
        records.seedIfAbsent(defaults)
    }

    override suspend fun upsert(rule: AdRule) {
        records.mutate { current ->
            val previous = current.firstOrNull { it.id == rule.id }
            val createdAt = rule.createdAt.takeIf { it > 0L } ?: previous?.createdAt ?: clock()
            current.filterNot { it.id == rule.id } + rule.copy(createdAt = createdAt)
        }
    }

    override suspend fun delete(ruleId: String) {
        records.mutate { current -> current.filterNot { it.id == ruleId } }
    }

    override suspend fun setEnabled(ruleId: String, enabled: Boolean) {
        records.mutate { current -> current.map { if (it.id == ruleId) it.copy(enabled = enabled) else it } }
    }

    /**
     * 点级标记落盘。
     *
     * 去重键是「作用域 + 类型 + pattern」：同一 pattern 在两个站点各自存在是合理的
     * （站点级规则互不影响），但同一站点重复标记同一条必须幂等返回旧规则，
     * 否则规则表会被反复点击堆满噪声记录。
     */
    override suspend fun addMarkedRule(
        siteId: String?,
        pattern: String,
        kind: AdRuleKind,
        note: String?,
    ): AdRule {
        val normalized = pattern.trim()
        val created = clock()
        var result: AdRule? = null
        records.mutate { current ->
            val existing = current.firstOrNull {
                it.siteId == siteId && it.kind == kind && it.pattern.equals(normalized, ignoreCase = true)
            }
            if (existing != null) {
                result = existing
                current
            } else {
                val rule = AdRule(
                    id = Ids.next("rule", created),
                    pattern = normalized,
                    kind = kind,
                    siteId = siteId,
                    note = note,
                    createdAt = created,
                )
                result = rule
                current + rule
            }
        }
        // mutate 保证块内至少执行一次；为空只可能是实现被改动，这里给出明确异常而不是 !!
        return result ?: error("规则写入未产生结果：$normalized")
    }

    private companion object {

        const val FILE_NAME = "ad_rules.json"
    }
}
