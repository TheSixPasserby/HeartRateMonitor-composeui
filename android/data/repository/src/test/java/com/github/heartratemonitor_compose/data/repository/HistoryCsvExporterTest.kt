package com.github.heartratemonitor_compose.data.repository

import com.github.heartratemonitor_compose.data.model.HeartRateRecordInfo
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * HistoryCsvExporter 纯构建逻辑单测（不依赖 Android 运行时）。
 *
 * 覆盖契约 12 关注点：CSV 中的全部数字输出（timestamp/elapsed/时间列/心率）
 * 必须为 ASCII 十进制，不随系统默认 Locale 切换为本地数字系统。
 * 默认时区固定为 GMT+8，保证期望串与运行机器时区无关。
 */
class HistoryCsvExporterTest {

    private lateinit var savedLocale: Locale
    private lateinit var savedTimeZone: TimeZone

    @Before
    fun pinLocaleAndTimeZone() {
        savedLocale = Locale.getDefault()
        savedTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+08:00"))
    }

    @After
    fun restoreLocaleAndTimeZone() {
        Locale.setDefault(savedLocale)
        TimeZone.setDefault(savedTimeZone)
    }

    private fun record(id: Long, timestamp: Long, heartRate: Int) =
        HeartRateRecordInfo(id = id, sessionId = 1L, timestamp = timestamp, heartRate = heartRate)

    @Test
    fun `csv has header then records in crlf lines`() {
        val records = listOf(
            record(1, 1_000_000L, 72),
            record(2, 1_000_500L, 75),
            record(3, 1_001_500L, 80),
        )

        val csv = HistoryCsvExporter.buildCsv(records)
        val lines = csv.split("\r\n")

        // 1 000 000 ms = 1970-01-01 00:16:40 UTC = GMT+8 08:16:40
        assertThat(lines[0]).isEqualTo("timestamp_ms,time,elapsed_seconds,heart_rate")
        assertThat(lines[1]).isEqualTo("1000000,1970-01-01 08:16:40.000,0.000,72")
        assertThat(lines[2]).isEqualTo("1000500,1970-01-01 08:16:40.500,0.500,75")
        assertThat(lines[3]).isEqualTo("1001500,1970-01-01 08:16:41.500,1.500,80")
        assertThat(csv.endsWith("\r\n")).isTrue()
    }

    @Test
    fun `csv output is ascii under local digit system locale`() {
        // 阿拉伯语默认 Locale 使用本地数字系统（٠-٩）；CSV 输出不得受影响（契约 12）
        Locale.setDefault(Locale("ar"))
        val records = listOf(record(1, 1_700_000_000_123L, 65))

        val csv = HistoryCsvExporter.buildCsv(records)

        // 1 700 000 000 s = 2023-11-14 22:13:20 UTC，GMT+8 为 2023-11-15 06:13:20
        assertThat(csv).contains("1700000000123,2023-11-15 06:13:20.123,0.000,65")
        assertThat(csv.all { it == '\r' || it == '\n' || it.code < 128 }).isTrue()
    }

    @Test
    fun `empty records produce header only`() {
        assertThat(HistoryCsvExporter.buildCsv(emptyList()))
            .isEqualTo("timestamp_ms,time,elapsed_seconds,heart_rate\r\n")
    }

    @Test
    fun `suggested file name is filesystem safe ascii`() {
        // 尼泊尔语同为本地数字系统语言；文件名必须稳定 ASCII
        Locale.setDefault(Locale("ne"))
        val name = HistoryCsvExporter.suggestedFileName(1_700_000_000_123L)

        assertThat(name).isEqualTo("heart_rate_2023-11-15_06-13-20.csv")
        assertThat(name.all { it.code < 128 }).isTrue()
    }
}
