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


/**
 * 将原本散落在 BleService（:service 采集引擎，Phase 3 迁入 :data:repository）
 * 中的「会话创建 → 缓冲 → 批量 flush → 会话结束」逻辑收敛到一处，
 * 让采集引擎只关心连接/断开/心率接收，而不必直接操作数据库与缓冲队列。
 */
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
        // 先结束可能残留的旧会话（如切换设备时旧连接的 cleanup 被纪元守卫跳过），
        // 刷新缓冲记录并关闭旧 session，再创建新 session。
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
            // 超出宽松上限时丢弃最旧记录（缓冲头部）：DB 持续故障时 flush 反复失败、
            // 记录被放回缓冲，只有此增长点设上限才能保证缓冲有界（约 1 小时心率量），
            // 同时让 onDestroy 分片入队的总量可控。put-back 路径的瞬时超限会在下次
            // record() 调用时收敛回上限（有界，无需在放回处重复截断）。
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
                // teardown 路径（connectionJob 的 NonCancellable finally）无外层兜底，
                // DAO 异常必须就地消化，避免协程异常逸出导致进程崩溃。
                // 会话保持未关闭状态，由下次 startSession 的 endSession() 修复。
                Log.e(TAG, "endSession 失败（会话 $id 保持未关闭，下次连接时修复）", e)
            }
            currentSessionId = null
        }
    }

    fun cancelFlushLoop() {
        recordFlushJob?.cancel()
        recordFlushJob = null
    }

    /**
     * 可在主线程安全调用：仅持锁拷贝+清空（微秒级），不涉及任何 I/O。
     * 取出的记录应由调用方负责持久化，避免在生命周期回调中同步阻塞主线程。
     */
    fun drainPendingRecords(): List<HeartRateRecord> {
        synchronized(pendingRecordsLock) {
            if (pendingRecords.isEmpty()) return emptyList()
            val drained = pendingRecords.toList()
            pendingRecords.clear()
            return drained
        }
    }

    /**
     * 异常分级处理：
     * - [SQLiteConstraintException]：外键约束失败（会话已删除），重置 sessionId，丢弃本批数据。
     * - 其他 [Exception]：磁盘 I/O 错误等瞬时故障，将记录放回缓冲区，下轮 flush 自动重试。
     */
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
            // 结构化取消（关停/设备切换）：记录放回缓冲区头部，让后续 drain/endSession
            // 有机会抢救，同时必须继续传播取消，不能当普通 IO 故障吞掉。
            synchronized(pendingRecordsLock) {
                pendingRecords.addAll(0, toFlush)
            }
            throw e
        } catch (_: SQLiteConstraintException) {
            // 外键约束失败（如会话已被删除），后续数据不再归属当前会话
            currentSessionId = null
        } catch (e: Exception) {
            // 瞬时故障（磁盘 I/O 错误、数据库锁竞争、数据库已关闭等）：
            // 将记录放回缓冲区头部，下一轮 flush 自动重试，避免静默数据丢失。
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
                // flushPendingRecords 内部已做异常分级捕获，
                // 此处 try-catch 为双保险，确保任何未预见异常都不会终止循环。
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

    /**
     * 清理超出保留数量限制的最旧会话（级联删除其下心率记录）。
     *
     * 在每次创建新 session 后调用，保证历史记录总数不超过 [MAX_SESSIONS] 条。
     * 异常就地消化，不影响主流程。
     */
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
        /** 历史会话最大保留数量，超出时自动删除最旧的。 */
        private const val MAX_SESSIONS = 30
        /**
         * 待落盘缓冲的宽松上限（约 1 小时心率量）：DB 持续故障时 flush 失败的记录被放回
         * 缓冲重试，无上限会无限增长，并最终撑爆 FlushRecordsWorker 单请求的 Data 10KB
         * 限制。超限时丢弃最旧记录（保留最近约 1 小时），属故障场景下的降级取舍。
         */
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
