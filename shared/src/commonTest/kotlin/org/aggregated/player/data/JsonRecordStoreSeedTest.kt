package org.aggregated.player.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.aggregated.player.FakeLogger
import org.aggregated.player.InMemoryJsonDocumentStore
import org.aggregated.player.domain.model.AdRule
import org.aggregated.player.domain.model.AdRuleKind
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonRecordStoreSeedTest {

    private fun store() = JsonRecordStore(
        documentStore = InMemoryJsonDocumentStore(),
        relativePath = "ad_rules.json",
        itemSerializer = AdRule.serializer(),
        logger = FakeLogger(),
    )

    @Test
    fun `seedIfAbsent writes only when file absent and never duplicates`() = runTest {
        val s = store()
        val defaults = listOf(AdRule(id = "a", pattern = "x.com", kind = AdRuleKind.DOMAIN))

        s.seedIfAbsent(defaults)
        assertEquals(defaults, s.readAll(), "首次种子应写入默认值")

        // 文件已存在，二次种子不得重复追加
        s.seedIfAbsent(defaults + AdRule(id = "b", pattern = "y.com", kind = AdRuleKind.DOMAIN))
        assertEquals(defaults, s.readAll(), "已存在时不应再次写入")
    }

    @Test
    fun `seedIfAbsent is a no-op when file already present`() = runTest {
        val s = store()
        val existing = listOf(AdRule(id = "user", pattern = "z.com", kind = AdRuleKind.DOMAIN))
        s.writeAll(existing)

        s.seedIfAbsent(listOf(AdRule(id = "a", pattern = "x.com", kind = AdRuleKind.DOMAIN)))
        assertEquals(existing, s.readAll(), "已有文件时不得覆盖用户数据")
    }
}
