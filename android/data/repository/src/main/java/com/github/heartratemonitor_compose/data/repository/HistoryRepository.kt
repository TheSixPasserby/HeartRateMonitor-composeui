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

@Singleton
class HistoryRepository @Inject constructor(private val dao: HeartRateDao) {
    val allSessions: Flow<List<HeartRateSessionInfo>> =
        dao.getAllSessions().map { sessions -> sessions.map { it.toInfo() } }

    suspend fun getSessionStats(): List<SessionStatsInfo> =
        dao.getAllSessionStats().map { it.toInfo() }

    suspend fun getHeartRatesForSession(sessionId: Long): List<Int> =
        dao.getHeartRatesForSession(sessionId)

    suspend fun getSampledHeartRatesForSession(sessionId: Long, step: Int): List<Int> =
        dao.getSampledHeartRatesForSession(sessionId, step)

    suspend fun getRecordsForSession(sessionId: Long): List<HeartRateRecordInfo> =
        dao.getRecordsForSession(sessionId).map { it.toInfo() }

    suspend fun deleteSessionsByIds(ids: List<Long>) = dao.deleteSessionsByIds(ids)

    /**
     * 调试用：写入一段约 2 分钟的模拟心率会话（每秒一条，共 120 条，
     * 与真实设备常见 1Hz 广播节奏一致，导出的 CSV 即 1 秒 1 行）。
     * 曲线：70 bpm 起步，前 1/4 热身爬升至 150，中段高强度区间 5 周期正弦振荡，
     * 末 15% 缓和回落至 85，全程叠加 ±4 随机噪声。
     * 入口为历史页标题长按（隐藏手势），用于无实体设备时验证图表与 CSV 导出链路。
     */
    suspend fun insertDebugSession(now: Long = System.currentTimeMillis()) {
        val durationMs = 2 * 60 * 1000L
        val intervalMs = 1_000L
        val count = (durationMs / intervalMs).toInt()
        // 结束于 1 分钟前：避免与「进行中」会话混淆，列表中呈现为刚完成的会话
        val startTime = now - durationMs - 60_000L
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