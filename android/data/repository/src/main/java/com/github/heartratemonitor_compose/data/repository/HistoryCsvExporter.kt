package com.github.heartratemonitor_compose.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.github.heartratemonitor_compose.data.model.HeartRateRecordInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val CSV_MIME_TYPE = "text/csv"

/**
 * 历史心率会话导出 CSV（数据层，SAF 写入，无需存储权限）。
 *
 * CSV 列：`timestamp_ms`（Unix 毫秒）、`time`（本地可读时间）、
 * `elapsed_seconds`（相对首条记录的秒偏移，毫秒精度，便于视频时间轴对齐）、
 * `heart_rate`（bpm）。行尾 CRLF（RFC 4180）。
 * 纯构建逻辑在 companion 中，可脱离 Android 单测；格式化固定 Locale.US
 * （契约 12：规避小语种本地数字系统）。
 */
@Singleton
class HistoryCsvExporter @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 写入 SAF 目标文档（CreateDocument 返回的 uri），IO 失败抛异常由调用方映射为失败结果。 */
    suspend fun exportToDocument(uri: Uri, records: List<HeartRateRecordInfo>) =
        withContext(Dispatchers.IO) {
            // "wt" 截断模式：用户将文件指到已有同名文档时覆盖旧内容而非追加
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(buildCsv(records).toByteArray(Charsets.UTF_8))
            } ?: throw IOException("无法打开目标文档输出流")
        }

    /**
     * 在 SAF 目录（OpenDocumentTree 返回的 uri）下新建 [fileName] 并写入。
     * 返回 false 表示目录不可写或文件创建失败（属可预期失败，不抛异常）。
     */
    suspend fun exportToDirectory(
        treeUri: Uri,
        fileName: String,
        records: List<HeartRateRecordInfo>
    ): Boolean = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext false
        val file = tree.createFile(CSV_MIME_TYPE, fileName) ?: return@withContext false
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { out ->
            out.write(buildCsv(records).toByteArray(Charsets.UTF_8))
        } ?: return@withContext false
        true
    }

    companion object {
        /** 建议文件名：heart_rate_2026-09-04_15-20-00.csv（文件系统安全的 ASCII 格式）。 */
        fun suggestedFileName(startTimeMs: Long): String {
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(startTimeMs))
            return "heart_rate_$stamp.csv"
        }

        internal fun buildCsv(records: List<HeartRateRecordInfo>): String {
            val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            val firstTimestamp = records.firstOrNull()?.timestamp ?: 0L
            val sb = StringBuilder()
            sb.append("timestamp_ms,time,elapsed_seconds,heart_rate").append("\r\n")
            for (record in records) {
                val elapsed = (record.timestamp - firstTimestamp) / 1000.0
                sb.append(record.timestamp).append(',')
                    .append(timeFormat.format(Date(record.timestamp))).append(',')
                    .append(String.format(Locale.US, "%.3f", elapsed)).append(',')
                    .append(record.heartRate)
                    .append("\r\n")
            }
            return sb.toString()
        }
    }
}
