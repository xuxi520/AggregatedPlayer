package org.aggregated.player.domain.usecase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ManifestInspector] 纯逻辑单测：嗅探增强的「判断资源真伪」信息全部出自这里，
 * 解析错了比不解析更糟（误导用户），所以边界用例按「脏输入不给假数据」的口径覆盖。
 */
class ManifestInspectorTest {

    @Test
    fun `主清单取最高带宽变体并解析分辨率与相对子清单地址`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=640x360
            360p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4128000,RESOLUTION=1920x1080
            1080p/index.m3u8
        """.trimIndent()
        val info = ManifestInspector.parseHls(master, "https://cdn.e.com/v/master.m3u8?sign=abc")

        assertEquals("1920x1080", info.resolution)
        assertEquals(4_128_000L, info.bandwidthBps)
        assertEquals("https://cdn.e.com/v/1080p/index.m3u8", info.variantUrl)
        // 主清单顶层没有 EXTINF：时长必须为 null，等子清单补——不能拿 0 误导调用方
        assertNull(info.durationSeconds)
        assertNull(info.estimatedBytes)
    }

    @Test
    fun `媒体清单累加 EXTINF 得总时长`() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.006,first
            seg-1.ts
            #EXTINF:5.5,second
            seg-2.ts
            #EXTINF:0.5,
            seg-3.ts
        """.trimIndent()
        val info = ManifestInspector.parseHls(media, "https://cdn.e.com/v/1080p/index.m3u8")

        assertEquals(11L, info.durationSeconds) // 每片截断后累加：6 + 5 + 0
        assertNull(info.resolution) // 媒体清单没有分辨率，如实为 null
        assertNull(info.variantUrl)
    }

    @Test
    fun `脏带宽被丢弃不估出天文数字`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=999999999999,RESOLUTION=1920x1080
            https://cdn.e.com/huge.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4128000,RESOLUTION=1280x720
            720p/index.m3u8
        """.trimIndent()
        val info = ManifestInspector.parseHls(master, "https://cdn.e.com/v/master.m3u8")

        // 超过 200 Mbps 上限的变体整体忽略：分辨率/码率都不取自脏行
        assertEquals("1280x720", info.resolution)
        assertEquals(4_128_000L, info.bandwidthBps)
    }

    @Test
    fun `码率与时长齐备时估算体积`() {
        val info = ManifestInfo(bandwidthBps = 4_128_000L, durationSeconds = 1_000L)
        // 4128000 bps × 1000 s / 8 = 516 MB 量级，符合一部高清电影的直觉
        assertEquals(516_000_000L, info.estimatedBytes)
    }

    @Test
    fun `MPD 取根时长属性与最大带宽`() {
        val mpd = """
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" mediaPresentationDuration="PT1H2M3S">
              <Period>
                <AdaptationSet>
                  <Representation id="1" bandwidth="1500000" width="640" height="360"/>
                  <Representation id="2" bandwidth="4500000" width="1920" height="1080"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val info = ManifestInspector.parseMpd(mpd)

        assertEquals(3_723L, info.durationSeconds) // 1×3600 + 2×60 + 3
        assertEquals(4_500_000L, info.bandwidthBps)
        assertEquals(2_094_187_500L, info.estimatedBytes) // 4.5 Mbps × 3723 s / 8
    }

    @Test
    fun `坏清单返回空结果而非抛异常`() {
        val garbage = "not a manifest at all"
        val info = ManifestInspector.parseHls(garbage, "https://e.com/x.m3u8")
        assertNull(info.resolution)
        assertNull(info.durationSeconds)
        assertNull(info.bandwidthBps)
        assertNull(info.variantUrl)
        assertNull(ManifestInspector.parseMpd(garbage).durationSeconds)
    }

    @Test
    fun `时长格式化与分辨率短格式供 UI 直接使用`() {
        assertEquals("1小时2分", readableDuration(3_723L)) // 3600 + 120 + 3
        assertEquals("5分20秒", readableDuration(320L))
        assertEquals("45秒", readableDuration(45L))
        assertEquals("", readableDuration(null))
        assertEquals("", readableDuration(0L))

        assertEquals("1080p", shortResolution("1920x1080"))
        assertEquals("1080p", shortResolution("1080x1920")) // 竖屏取短边
        assertEquals("", shortResolution(null))
        assertEquals("", shortResolution("bad"))
    }
}
