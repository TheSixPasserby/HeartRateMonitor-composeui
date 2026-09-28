package com.github.heartratemonitor_compose.service

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import com.github.heartratemonitor_compose.data.settings.RecordingMode
import com.github.heartratemonitor_compose.data.db.HeartRateDao
import com.github.heartratemonitor_compose.data.db.HeartRateRecord
import com.github.heartratemonitor_compose.data.db.HeartRateSession
import com.github.heartratemonitor_compose.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


class HeartRateRecorder(
    private val settingsRepository: SettingsRepository,
    private val dao: HeartRateDao,
    private val scope: CoroutineScope
) {

    @Volatile
    private var currentSessionId: Long? = null

    private val pendingRecords = mutableListOf<HeartRateRecord>()
    private val pendingRecordsLock = Any()
    private var recordFlushJob: Job? = null

    // 秒级网格填充状态：上一写入锚点的时间与数值（含填充行），随 startSession/endSession 复位
    private var padAnchorTime = 0L
    private var padAnchorBpm = 0


    suspend fun startSession(deviceName: String): Long? {
        if (!isHistoryEnabled()) return null
        // ⚠️ 反直觉设计：先结束可能残留的旧会话，再创建新 session。
        endSession()
        val session = HeartRateSession(
            deviceName = deviceName,
            startTime = System.currentTimeMillis()
        )
        currentSessionId = dao.insertSession(session)
        trimOldSessionsIfNeeded()
        padAnchorTime = 0L
        padAnchorBpm = 0
        startRecordFlushLoop()
        return currentSessionId
    }

    suspend fun record(bpm: Int, deviceName: String) {
        if (!isHistoryEnabled()) return

        if (currentSessionId == null) {
            // 手动模式禁止懒创建：无用户显式「开始记录」产生的活动会话即不落盘；
            // 懒创建仅保留给 AUTO（沿用连接即记的历史行为，兜住 startSession 被跳过的路径）
            if (recorderMode() != RecordingMode.AUTO) return
            val session = HeartRateSession(
                deviceName = deviceName,
                startTime = System.currentTimeMillis()
            )
            currentSessionId = dao.insertSession(session)
            trimOldSessionsIfNeeded()
            padAnchorTime = 0L
            padAnchorBpm = 0
            startRecordFlushLoop()
        }

        synchronized(pendingRecordsLock) {
            val sessionId = currentSessionId!!
            val now = System.currentTimeMillis()
            // 秒级网格前向填充：设备广播间隔 >1.5s 时（如 0.5Hz 广播的手环），
            // 按每秒补一条前值，使历史与导出 CSV 恒为 1 行/秒（视频叠加友好的均匀采样）；
            // 缺口超过 10s 不填充——断链/佩戴松动期间不得伪造数据。
            val anchor = padAnchorTime
            for (t in gapFillGrid(anchor, now)) {
                pendingRecords.add(HeartRateRecord(sessionId = sessionId, timestamp = t, heartRate = padAnchorBpm))
            }
            pendingRecords.add(
                HeartRateRecord(
                    sessionId = sessionId,
                    timestamp = now,
                    heartRate = bpm
                )
            )
            padAnchorTime = now
            padAnchorBpm = bpm
            // DB 故障时 flush 反复失败→缓冲无限增长，设上限保证有界（约 1 小时心率量，不撑爆 Data 10KB）；put-back 瞬时超限下次 record() 截断。
            if (pendingRecords.size > MAX_PENDING_RECORDS) {
                val dropped = pendingRecords.size - MAX_PENDING_RECORDS
                pendingRecords.subList(0, dropped).clear()
                Log.w(TAG, "待落盘缓冲超过上限（$MAX_PENDING_RECORDS），丢弃最旧的 $dropped 条记录")
            }
        }
    }

    suspend fun endSession() {
        cancelFlushLoop()
        flushPendingRecords()
        padAnchorTime = 0L
        padAnchorBpm = 0
        currentSessionId?.let { id ->
            try {
                dao.endSession(id, System.currentTimeMillis())
            } catch (e: Exception) {
                // ⚠️ 反直觉设计：teardown 路径无外层兜底，异常必须就地消化（会话未关闭，下次 startSession 修复）。
                Log.e(TAG, "endSession 失败（会话 $id 保持未关闭，下次连接时修复）", e)
            }
            currentSessionId = null
        }
    }

    fun cancelFlushLoop() {
        recordFlushJob?.cancel()
        recordFlushJob = null
    }

    // 主线程安全（仅持锁拷贝清空）；调用方负责持久化，避免生命周期回调同步阻塞。
    fun drainPendingRecords(): List<HeartRateRecord> {
        synchronized(pendingRecordsLock) {
            if (pendingRecords.isEmpty()) return emptyList()
            val drained = pendingRecords.toList()
            pendingRecords.clear()
            return drained
        }
    }

    // 异常分级：SQLiteConstraintException→重置 sessionId 丢弃数据；其他→放回缓冲重试。
    suspend fun flushPendingRecords() {
        val toFlush: List<HeartRateRecord>
        synchronized(pendingRecordsLock) {
            if (pendingRecords.isEmpty()) return
            toFlush = pendingRecords.toList()
            pendingRecords.clear()
        }
        try {
            dao.insertRecords(toFlush)
        } catch (e: CancellationException) {
            // ⚠️ 反直觉设计：放回缓冲头部让后续 drain/endSession 抢救，但必须继续传播取消。
            synchronized(pendingRecordsLock) {
                pendingRecords.addAll(0, toFlush)
            }
            throw e
        } catch (_: SQLiteConstraintException) {
            currentSessionId = null
        } catch (e: Exception) {
            // 瞬时故障：记录放回缓冲头部自动重试，避免静默数据丢失。
            Log.e(TAG, "flush 失败，${toFlush.size} 条记录将下轮重试", e)
            synchronized(pendingRecordsLock) {
                pendingRecords.addAll(0, toFlush)
            }
        }
    }

    private fun isHistoryEnabled(): Boolean {
        return settingsRepository.recordingEnabled()
    }

    private fun recorderMode(): String {
        return settingsRepository.recordingMode()
    }

    /**
     * 手动停止记录专用：正常结束会话后，若该会话无任何记录（如误触开始立即按停），
     * 删除空会话，避免历史列表出现「0 条记录」卡片。会话复位语义与 [endSession] 一致。
     */
    suspend fun endSessionDiscardIfEmpty() {
        val id = currentSessionId
        if (id == null) {
            endSession()
            return
        }
        endSession()
        try {
            if (dao.getLastRecordTimestampForSession(id) == null) {
                dao.deleteSession(id)
            }
        } catch (e: Exception) {
            // 清理失败只留一个空会话记录，属可接受降级，不得影响结束流程
            Log.w(TAG, "清理空会话失败（会话 $id）", e)
        }
    }

    private fun startRecordFlushLoop() {
        recordFlushJob?.cancel()
        recordFlushJob = scope.launch {
            while (true) {
                delay(BATCH_FLUSH_INTERVAL_MS)
                try {
                    flushPendingRecords()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "flush 循环未预见异常，跳过本轮", e)
                }
            }
        }
    }

    // 创建新 session 后调用，保证历史不超 MAX_SESSIONS 条；异常就地消化。
    private suspend fun trimOldSessionsIfNeeded() {
        try {
            dao.trimOldSessions(MAX_SESSIONS, currentSessionId)
        } catch (e: Exception) {
            Log.e(TAG, "清理旧会话失败", e)
        }
    }

    companion object {
        private const val TAG = "HeartRateRecorder"
        private const val BATCH_FLUSH_INTERVAL_MS = 5000L
        private const val MAX_SESSIONS = 30
        // 缓冲宽松上限（约 1 小时心率量）：DB 故障时无上限会撑爆 Data 10KB 限制，超限时丢弃最旧记录。
        private const val MAX_PENDING_RECORDS = 3600

        /** 前向填充触发的最小缺口（<1.5s 视为正常 1Hz 节奏，不补） */
        private const val FILL_MIN_GAP_MS = 1500L

        /** 前向填充允许的最大缺口（更大缺口视为断链/佩戴松动，不伪造数据） */
        private const val MAX_GAP_FILL_MS = 10_000L

        /**
         * 计算需前向填充的 1s 网格时间戳（不含锚点与 [now]）。
         * 纯函数，供单测；缺口 <[FILL_MIN_GAP_MS] 或 >[MAX_GAP_FILL_MS] 时为空。
         */
        internal fun gapFillGrid(anchor: Long, now: Long): List<Long> {
            if (anchor <= 0L || now - anchor !in FILL_MIN_GAP_MS..MAX_GAP_FILL_MS) return emptyList()
            val out = ArrayList<Long>()
            var t = anchor + 1000L
            while (t < now) {
                out.add(t)
                t += 1000L
            }
            return out
        }
    }
}
