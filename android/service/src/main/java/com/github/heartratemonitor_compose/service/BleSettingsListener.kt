package com.github.heartratemonitor_compose.service

import com.github.heartratemonitor_compose.data.settings.RecordingMode
import com.github.heartratemonitor_compose.data.settings.SettingsKeys
import com.github.heartratemonitor_compose.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * 设置项变更响应器：各流 drop(1) 跳过初始发射，「仅响应注册后变化」。
 */
class BleSettingsListener(
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
    private val onServerSettingsChanged: () -> Unit,
    private val onSpeedSettingsChanged: () -> Unit,
    private val onHistoryRecordingDisabled: () -> Unit,
    private val onChartCacheClear: () -> Unit
) {
    private var jobs: List<Job> = emptyList()

    fun register() {
        jobs = listOf(
            scope.launch {
                merge(
                    settingsRepository.observe(SettingsKeys.HTTP_SERVER_ENABLED).drop(1),
                    settingsRepository.observe(SettingsKeys.HTTP_SERVER_PORT).drop(1),
                    settingsRepository.observe(SettingsKeys.WEBSOCKET_SERVER_ENABLED).drop(1),
                    settingsRepository.observe(SettingsKeys.WEBSOCKET_SERVER_PORT).drop(1),
                    settingsRepository.observe(SettingsKeys.SERVER_ACCESS_TOKEN).drop(1)
                ).collect { onServerSettingsChanged() }
            },
            scope.launch {
                settingsRepository.observe(SettingsKeys.SPEED_DISPLAY_ENABLED)
                    .drop(1)
                    .collect { onSpeedSettingsChanged() }
            },
            scope.launch {
                settingsRepository.observe(SettingsKeys.RECORDING_MODE)
                    .drop(1)
                    .collect { mode ->
                        // 切到「不记录」时立即结束当前 session，避免 endTime 一直为 NULL
                        // 导致 UI 显示「进行中」；AUTO ↔ MANUAL 切换不响应，当前会话按既定节奏收尾。
                        if (mode == RecordingMode.OFF) {
                            onHistoryRecordingDisabled()
                            onChartCacheClear()
                        }
                    }
            }
        )
    }

    fun unregister() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }
}
