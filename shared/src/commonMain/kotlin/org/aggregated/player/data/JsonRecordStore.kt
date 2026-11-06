package org.aggregated.player.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.aggregated.player.core.Logger
import org.aggregated.player.platform.JsonDocumentStore

/**
 * 持久化文件存在但无法解析。
 *
 * 抛出的目的不是「让 UI 弹错误框」，而是**阻止后续写入把坏文件当成空表覆盖掉**：
 * 用户配置被静默清空，比配置读不出来严重得多。调用方可以提示用户手工修复文件。
 */
class CorruptDocumentException(
    relativePath: String,
    reason: String,
) : IllegalStateException("持久化文件 $relativePath 无法解析（$reason），已拒绝覆盖写入")

/**
 * JSON 记录文件的通用编解码骨架（设计文档 §4.7）。
 *
 * 文件结构固定为：
 * ```json
 * { "schemaVersion": 1, "items": [ ] }
 * ```
 *
 * 四条刻意的设计，改动前请先读：
 * 1. **读侧降级、写侧保守**：读取失败返回空列表并记日志，不抛异常（不能因为配置坏掉就打不开应用）；
 *    但一旦判定文件「坏掉」（截断、版本高于当前支持），写侧默认抛 [CorruptDocumentException] 拒绝覆盖。
 * 2. **读-改-写在同一把锁内完成**（[mutate]），否则两个协程并发修改会互相丢更新。
 * 3. **IO 一律切到后台**：`commonMain` 没有 `Dispatchers.IO`，统一用 [Dispatchers.Default]（§4.8）。
 * 4. **不做「跳过坏记录」的乐观处理**：单条解析失败即判定整表损坏。静默丢几条记录比整表报错更难排查。
 *
 * @param allowRewriteWhenUnreadable 低价值数据（如标签会话）可以设为 `true`：坏了就直接重写；
 *   站点、规则这类用户资产必须保持 `false`。
 * @param schemaVersion 本实现支持的文件版本；文件里的版本高于它时只读降级、拒绝写入。
 */
class JsonRecordStore<T>(
    private val documentStore: JsonDocumentStore,
    private val relativePath: String,
    private val itemSerializer: KSerializer<T>,
    private val logger: Logger,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    private val allowRewriteWhenUnreadable: Boolean = false,
) {

    private val json = Json {
        prettyPrint = true
        // 向前兼容：高版本写入的未知字段在旧版本读取时忽略，而不是抛异常
        ignoreUnknownKeys = true
        // 默认值也写出来：用户可直接查看/编辑 JSON，语义完整比省几个字节重要
        encodeDefaults = true
    }

    /** 同一进程内的串行化，配合平台实现的原子替换。 */
    private val ioLock = Mutex()

    /**
     * 一次读取的结果。
     *
     * [usable] 为 `false` 表示「文件在但没读明白」，与「文件不存在（usable = true, items 为空）」
     * 必须区别对待——前者禁止覆盖写，后者是首次运行的正常状态。
     */
    private class Snapshot<R>(val items: List<R>, val usable: Boolean)

    /**
     * 读取全部记录。
     *
     * @return 记录列表；文件不存在或无法解析时为空列表（后者会记 `error` 日志）。
     */
    suspend fun readAll(): List<T> = withContext(ioDispatcher) {
        ioLock.withLock { readLocked().items }
    }

    /** 整体覆盖写入。低价值数据（会话快照）的高频保存走这条路即可，不做读-改-写。 */
    suspend fun writeAll(items: List<T>) = withContext(ioDispatcher) {
        ioLock.withLock { writeLocked(items) }
    }

    /**
     * 幂等种子：仅当文件**不存在**时整体写入 [defaults]，已存在（含空文件或读不出的文件）则跳过。
     *
     * 用于「首次启动写入内置默认规则」——文件缺失=首次运行，正常写入；用户后来清空规则得到的是已存在的空文件，
     * 不会再被复活，避免每次启动把用户清掉的内置规则又塞回去。`read()` 返回 `null` 才表示文件不存在，
     * 其余情况（内容在、或 IO 异常）一律不写，绝不覆盖用户已有数据。检查与写入在同一把锁内，保证原子。
     */
    suspend fun seedIfAbsent(defaults: List<T>) = withContext(ioDispatcher) {
        ioLock.withLock {
            if (documentStore.read(relativePath) != null) return@withLock
            writeLocked(defaults)
        }
    }

    /**
     * 读-改-写原子操作，仓储实现的写路径都应走它。
     *
     * @return 变换后的列表
     * @throws CorruptDocumentException 文件不可解析且未允许覆盖重写
     */
    suspend fun mutate(transform: (List<T>) -> List<T>): List<T> = withContext(ioDispatcher) {
        ioLock.withLock {
            val snapshot = readLocked()
            if (!snapshot.usable && !allowRewriteWhenUnreadable) {
                throw CorruptDocumentException(relativePath, "内容损坏或版本过新")
            }
            val updated = transform(snapshot.items)
            writeLocked(updated)
            updated
        }
    }

    /**
     * 必须在 [ioLock] 内调用。
     * 读取失败只能按 Exception 兜底：commonMain 不能具名 java.io.IOException（平台类型），
     * 无法收窄到更具体的异常，故在此刻意抑制 TooGenericExceptionCaught。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun readLocked(): Snapshot<T> {
        val raw = try {
            documentStore.read(relativePath) ?: return Snapshot(emptyList(), usable = true)
        } catch (error: Exception) {
            // 文件读不出（磁盘/权限问题）：降级为空表，绝不抛——配置读不出来不能打不开应用。
            // 注：read() 声明抛 java.io.IOException，但那是 JVM 平台类型，commonMain 无法具名，
            // 故此处只能按 Exception 兜底（上方已 @Suppress TooGenericExceptionCaught）。
            logger.error("读取记录文件失败：$relativePath", error)
            return Snapshot(emptyList(), usable = false)
        }

        return try {
            val root = json.parseToJsonElement(raw) as? JsonObject ?: return corrupted("顶层不是 JSON 对象")
            val fileVersion = root[FIELD_SCHEMA_VERSION]?.jsonPrimitive?.intOrNull ?: UNKNOWN_VERSION
            if (fileVersion > schemaVersion) {
                // 由更高版本写下的文件：按空表处理并禁止覆盖，避免升级后再降级时把新字段抹平
                return corrupted("schemaVersion=$fileVersion 高于当前支持的 $schemaVersion")
            }
            val elements = root[FIELD_ITEMS] as? JsonArray ?: return corrupted("items 字段缺失或类型错误")
            Snapshot(elements.map { json.decodeFromJsonElement(itemSerializer, it) }, usable = true)
        } catch (error: SerializationException) {
            // 只有序列化失败才判定损坏（单条坏=整表坏，见类注释设计点 4）；其它异常照常上抛
            logger.error("解析记录文件失败：$relativePath", error)
            Snapshot(emptyList(), usable = false)
        }
    }

    private fun corrupted(reason: String): Snapshot<T> {
        logger.error("记录文件 $relativePath 已损坏：$reason")
        return Snapshot(emptyList(), usable = false)
    }

    /** 必须在 [ioLock] 内调用。IO 异常向上抛出，由调用方（UI 层）提示用户，不静默失败。 */
    private fun writeLocked(items: List<T>) {
        val encoded: List<JsonElement> = items.map { json.encodeToJsonElement(itemSerializer, it) }
        val document = buildJsonObject {
            put(FIELD_SCHEMA_VERSION, JsonPrimitive(schemaVersion))
            put(FIELD_ITEMS, JsonArray(encoded))
        }
        documentStore.write(relativePath, json.encodeToString(JsonObject.serializer(), document))
        logger.debug("写入 ${items.size} 条记录到 $relativePath")
    }

    companion object {

        /** 阶段 0 的第一个版本。新增字段属于兼容变更，不改版本号；改语义或删字段才需要 +1。 */
        const val CURRENT_SCHEMA_VERSION = 1

        private const val FIELD_SCHEMA_VERSION = "schemaVersion"
        private const val FIELD_ITEMS = "items"

        /** 缺字段视为「来自无版本概念的早期文件」，允许读写；高于当前版本才拒绝。 */
        private const val UNKNOWN_VERSION = 0
    }
}
