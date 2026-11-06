package org.aggregated.player.ui

import aggregatedplayer.shared.generated.resources.Res
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.aggregated.player.core.createLogger
import org.aggregated.player.domain.usecase.BuiltInSpec

/**
 * 内置站点目录（产品核心能力，非临时预览数据）。
 *
 * **站点配置已外置为资源文件** `composeResources/files/builtin_sites.json`：
 * 发版 / 更新替换内置站点时，只需改这一个 JSON（无需改 Kotlin、无需重排同步逻辑），
 * 以此兑现「暴露站点配置、方便更新」的诉求。
 *
 * 加载失败（资源缺失 / 格式错误）时回退到下方 [FALLBACK]，保证至少能出一份内置目录。
 * 具体的增删对齐仍交给 [org.aggregated.player.domain.usecase.BuiltInSiteSync]
 * （以 URL 为键、每次启动对齐）。
 *
 * ⚠️ 合规：内置第三方聚合站点会放大版权与上架风险，发布形态需评估目标市场法规。
 */
object BuiltInSites {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 当前版本的内置目录，转成同步用例可消费的 [BuiltInSpec]。
     * 为 suspend：从 composeResources 读取并解析 JSON。
     */
    suspend fun specs(): List<BuiltInSpec> = runCatching { loadFromResource() }
        .onFailure { error ->
            // 兜底必须**可观测**：否则配置文件放错位置/格式写错时，用户只会看到「站点没变」，
            // 没人知道真正加载的是代码里的兜底目录。
            createLogger("BuiltInSites")
                .warn("读取 builtin_sites.json 失败，回退到代码内置目录", error)
        }
        .getOrElse { FALLBACK.map { BuiltInSpec(it.name, it.url) } }

    /** 内置站点数量，供日志与「种入是否完整」校验。 */
    val count: Int
        get() = FALLBACK.size

    private suspend fun loadFromResource(): List<BuiltInSpec> {
        val bytes = Res.readBytes("files/builtin_sites.json")
        val catalog = json.decodeFromString<JsonCatalog>(bytes.decodeToString())
        return catalog.sites.map { BuiltInSpec(it.name, it.url) }
    }

    @Serializable
    private data class JsonSite(val name: String, val url: String)

    @Serializable
    private data class JsonCatalog(val sites: List<JsonSite>)

    /**
     * 兜底目录：与 `builtin_sites.json` 保持一致即可。仅在资源加载失败时启用，
     * 正常情况下不会走到这里。
     */
    private val FALLBACK = listOf(
        Entry("看片狂人", "https://kpkuang.fun/"),
        Entry("91毒舌电影", "https://www.duse0.com/"),
        Entry("金牌影院", "https://www.x8kb9k8.com/"),
        Entry("茶杯狐", "https://cupfox.love/"),
        Entry("NO视频", "https://www.novipnoad.net/"),
        Entry("Vision TV+", "https://weishitv.xyz/"),
        Entry("Destiny TV", "https://www.tvfans.top/"),
        Entry("anifun动漫", "https://anifun.cn/"),
        Entry("次元城动画", "https://www.cycani.org/"),
        Entry("漫客栈", "https://www.mkzhan.com/"),
        Entry("AEG动漫", "https://www.agedm.io/"),
        Entry("霹雳书屋", "https://www.plishuwu.cn/plishuwu.html"),
        Entry("红薯中文网", "https://www.hongshu.com/"),
    )

    private data class Entry(val name: String, val url: String)
}
