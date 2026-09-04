package com.github.heartratemonitor_compose.data.repository

import com.github.heartratemonitor_compose.data.db.HeartRateDao
import com.github.heartratemonitor_compose.data.db.HeartRateRecord
import com.github.heartratemonitor_compose.data.db.HeartRateSession
import com.github.heartratemonitor_compose.data.model.HeartRateRecordInfo
import com.github.heartratemonitor_compose.data.model.HeartRateSessionInfo
import com.github.heartratemonitor_compose.data.model.SessionStatsInfo
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 将 UI 层对 AppDatabase 的直接访问下沉到 repository 层，
 * 对外返回 Domain Model，避免 Room Entity 泄漏到 UI/ViewModel 层。
 *
 * DAO 由 Hilt 构造注入（Phase 2 起，替代 AppContainer 手工装配）。
 */
@Singleton
class HistoryRepository @Inject constructor(private val dao: HeartRateDao) {
    val allSessions: Flow<List<HeartRateSessionInfo>> =
        dao.getAllSessions().map { sessions -> sessions.map { it.toInfo() } }

    suspend fun getSessionStats(): List<SessionStatsInfo> =
        dao.getAllSessionStats().map { it.toInfo() }

    suspend fun getHeartRatesForSession(sessionId: Long): List<Int> =
        dao.getHeartRatesForSession(sessionId)

    /**
     * 在 SQL 层完成等间距采样，避免将全部心率记录加载到 Kotlin 内存。
     * 调用方需先从 SessionStats 获取 recordCount，计算 step = max(1, recordCount / 50)。
     */
    suspend fun getSampledHeartRatesForSession(sessionId: Long, step: Int): List<Int> =
        dao.getSampledHeartRatesForSession(sessionId, step)

    suspend fun getRecordsForSession(sessionId: Long): List<HeartRateRecordInfo> =
        dao.getRecordsForSession(sessionId).map { it.toInfo() }

    suspend fun deleteSessionsByIds(ids: List<Long>) = dao.deleteSessionsByIds(ids)

    /**
     * 调试用：写入一段约 15 分钟的模拟心率会话（每 2 秒一条，共 450 条）。
     * 曲线：70 bpm 起步，前 1/4 热身爬升至 150，中段高强度区间 5 周期正弦振荡，
     * 末 15% 缓和回落至 85，全程叠加 ±4 随机噪声。
     * 入口为历史页标题长按（隐藏手势），用于无实体设备时验证图表与 CSV 导出链路。
     */
    suspend fun insertDebugSession(now: Long = System.currentTimeMillis()) {
        val durationMs = 15 * 60 * 1000L
        val intervalMs = 2_000L
        val count = (durationMs / intervalMs).toInt()
        // 结束于 5 分钟前：避免与「进行中」会话混淆，列表中呈现为刚完成的会话
        val startTime = now - durationMs - 5 * 60_000L
        val sessionId = dao.insertSession(
            HeartRateSession(
                deviceName = "Debug Device",
                startTime = startTime,
                endTime = startTime + durationMs
            )
        )
        val records = (0 until count).map { i ->
            val t = i.toDouble() / count
            val base = when {
                t < 0.25 -> 70 + (150 - 70) * (t / 0.25)
                t < 0.85 -> 150 + sin(t * 2 * PI * 5) * 8
                else -> 150 - (150 - 85) * ((t - 0.85) / 0.15)
            }
            val heartRate = (base + Random.nextInt(-4, 5)).roundToInt().coerceIn(40, 220)
            HeartRateRecord(
                sessionId = sessionId,
                timestamp = startTime + i * intervalMs,
                heartRate = heartRate
            )
        }
        dao.insertRecords(records)
    }
}