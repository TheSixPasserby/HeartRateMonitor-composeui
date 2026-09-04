package com.github.heartratemonitor_compose.ui.history

import android.net.Uri
import com.github.heartratemonitor_compose.data.model.HeartRateRecordInfo
import com.github.heartratemonitor_compose.data.repository.HistoryCsvExporter
import com.github.heartratemonitor_compose.data.repository.HistoryRepository
import com.github.heartratemonitor_compose.service.FairMemoryReceiver
import com.github.heartratemonitor_compose.ui.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException

/**
 * 会话心率记录归约进单一 UiState，
 * UI 层仅订阅记录状态并经 Intent 触发加载。
 * 依赖由 Hilt 构造注入（Phase 3 起）。
 */
@HiltViewModel
class ChartViewModel @Inject constructor(
    private val repository: HistoryRepository,
    private val csvExporter: HistoryCsvExporter,
    private val fairMemoryReceiver: FairMemoryReceiver
) : MviViewModel<ChartUiState, ChartIntent>(ChartUiState()),
    FairMemoryReceiver.MemoryListener {

    /**
     * 导出结果一次性回调：Composable 注册/注销（一次性事件不进 UiState，避免重组重放），
     * 语义同 HistoryViewModel.deleteResultListener（迁移方案 §3.4 方案 1）。
     * SAF 写入异常必须在 VM 内捕获：dispatch 为即发即忘，未捕获异常会崩进程。
     */
    @Volatile
    var exportResultListener: ((ChartExportResult) -> Unit)? = null

    init {
        fairMemoryReceiver.addMemoryListener(this)
    }

    override suspend fun handleIntent(intent: ChartIntent) {
        when (intent) {
            is ChartIntent.LoadRecords -> {
                val records = repository.getRecordsForSession(intent.sessionId).toImmutableList()
                setState {
                    it.copy(
                        records = records,
                        exportFileName = records.firstOrNull()?.timestamp
                            ?.let(HistoryCsvExporter::suggestedFileName)
                    )
                }
            }
            is ChartIntent.ExportCsv -> exportCsv(intent.targetUri)
        }
    }

    private suspend fun exportCsv(targetUri: Uri) {
        val records = currentState.records
        if (records.isEmpty()) {
            exportResultListener?.invoke(ChartExportResult.NoData)
            return
        }
        val result = try {
            csvExporter.exportToDocument(targetUri, records)
            ChartExportResult.Exported
        } catch (e: CancellationException) {
            throw e // 取消必须重抛，禁止并入普通 Exception 分支吞掉（契约 6）
        } catch (e: Exception) {
            ChartExportResult.Failed(e.message ?: e.javaClass.simpleName)
        }
        exportResultListener?.invoke(result)
    }

    /** 公平运行内存 TRIM：清空详情页心率记录缓存，释放内存。 */
    override fun onTrimMemory(notifyType: Int) {
        setState { it.copy(records = persistentListOf()) }
    }

    /** 公平运行内存 KILL：历史数据已由 Room 持久化，无需额外保存。 */
    override fun onKillMemory() {
    }

    override fun onCleared() {
        super.onCleared()
        fairMemoryReceiver.removeMemoryListener(this)
    }
}

/** 心率历史详情页用户意图。 */
sealed interface ChartIntent {
    data class LoadRecords(val sessionId: Long) : ChartIntent

    /** 将当前会话记录写入 SAF 目标文档（CreateDocument 返回的 uri）。 */
    data class ExportCsv(val targetUri: Uri) : ChartIntent
}

/** 心率历史详情页 UI 状态（只读快照）。 */
data class ChartUiState(
    val records: ImmutableList<HeartRateRecordInfo> = persistentListOf(),
    /** SAF 创建文档预填名，随记录加载派生；空记录时为 null */
    val exportFileName: String? = null
)

/** 导出结果一次性事件（VM 无 Context，文案由 UI 侧映射，不进 UiState）。 */
sealed interface ChartExportResult {
    /** 当前会话无记录，未执行写入 */
    data object NoData : ChartExportResult

    data object Exported : ChartExportResult

    data class Failed(val reason: String) : ChartExportResult
}
