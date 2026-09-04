package com.github.heartratemonitor_compose.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.heartratemonitor_compose.data.settings.RecordingMode
import com.github.heartratemonitor_compose.feature.settings.R
import com.github.heartratemonitor_compose.ui.util.SheetTopShape
import com.github.heartratemonitor_compose.ui.util.StatusBarScrim
import com.github.heartratemonitor_compose.ui.util.rememberExpandedSheetState
import com.github.heartratemonitor_compose.ui.util.rememberSheetDismissHandler
import com.github.heartratemonitor_compose.ui.widgets.ExpressiveButton
import com.github.heartratemonitor_compose.ui.widgets.ExpressiveTextButton


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FunctionSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val viewModel: FunctionSettingsViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                title = {
                    Text(
                        stringResource(R.string.function_settings),
                        style = MaterialTheme.typography.headlineSmall
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Surface(
                            modifier = Modifier.size(40.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceBright
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(com.github.heartratemonitor_compose.ui.widgets.R.string.cd_back)
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(Modifier.height(padding.calculateTopPadding() + 16.dp))

            // ── 分组 1：显示与记录 ──
            DisplayAndRecordGroup(
                uiState = uiState,
                onRecordModeChange = {
                    viewModel.dispatch(FunctionSettingsIntent.SetRecordingMode(it))
                },
                onHeartbeatAnimationChange = {
                    viewModel.dispatch(FunctionSettingsIntent.SetHeartbeatAnimation(it))
                },
                onSpeedDisplayChange = {
                    viewModel.dispatch(FunctionSettingsIntent.SetSpeedDisplay(it))
                }
            )

           
            Spacer(Modifier.height(24.dp))

            // ── 分组 2：连接与后台 ──
            ConnectionAndBackgroundGroup(
                uiState = uiState,
                onHideFromRecentsChange = {
                    viewModel.dispatch(FunctionSettingsIntent.SetHideFromRecents(it))
                },
                onNavAnimationDisabledChange = {
                    viewModel.dispatch(FunctionSettingsIntent.SetNavAnimationDisabled(it))
                },
                onAutoConnectChange = {
                    viewModel.dispatch(FunctionSettingsIntent.SetAutoConnect(it))
                },
                onAutoReconnectChange = {
                    viewModel.dispatch(FunctionSettingsIntent.SetAutoReconnect(it))
                },
                onScanFilterChange = {
                    viewModel.dispatch(FunctionSettingsIntent.SetScanFilter(it))
                }
            )

            Spacer(Modifier.height(64.dp))
            }
            StatusBarScrim()
        }
    }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplayAndRecordGroup(
    uiState: FunctionSettingsUiState,
    onRecordModeChange: (String) -> Unit,
    onHeartbeatAnimationChange: (Boolean) -> Unit,
    onSpeedDisplayChange: (Boolean) -> Unit
) {
    var showModePicker by remember { mutableStateOf(false) }
    // 待确认的启用型模式：手动/自动涉及持续落盘，需经性能警告确认
    var pendingRecordMode by remember { mutableStateOf<String?>(null) }
    val showSpeedDialog = remember { mutableStateOf(false) }

    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val iconTint = MaterialTheme.colorScheme.onPrimaryContainer

    SettingsGroupCard {
        SettingsItem(isFirst = true, onClick = { showModePicker = true }) {
            SettingsLink(
                title = stringResource(R.string.record_history),
                subtitle = stringResource(recordModeLabel(uiState.recordingMode)),
                leadingIcon = painterResource(R.drawable.ic_deployed_code_history),
                leadingIconContainerColor = containerColor,
                leadingIconTint = iconTint
            )
        }

        SettingsItem {
            SettingsSwitch(
                checked = uiState.heartbeatAnimationEnabled,
                onCheckedChange = onHeartbeatAnimationChange,
                title = stringResource(R.string.heartbeat_animation),
                subtitle = stringResource(R.string.subtitle_heartbeat_animation),
                leadingIcon = painterResource(R.drawable.ic_animation),
                leadingIconContainerColor = containerColor,
                leadingIconTint = iconTint
            )
        }

        SettingsItem(isLast = true) {
            SettingsSwitch(
                checked = uiState.speedDisplayEnabled,
                onCheckedChange = { checked ->
                    if (checked) {
                        showSpeedDialog.value = true
                    } else {
                        onSpeedDisplayChange(false)
                    }
                },
                title = stringResource(R.string.display_speed_gps),
                subtitle = stringResource(R.string.subtitle_display_speed_gps),
                leadingIcon = painterResource(R.drawable.ic_speed),
                leadingIconContainerColor = containerColor,
                leadingIconTint = iconTint
            )
        }
    }

    // 记录模式选择器（单 BottomSheet，内容在「三档列表 ↔ 性能警告确认」间切换，
    // 避免两个 sheet 叠加）：OFF 立即生效；MANUAL/AUTO 进入警告页签，确认后写入
    if (showModePicker) {
        val sheetState = rememberExpandedSheetState()
        val dismiss = rememberSheetDismissHandler(sheetState) {
            showModePicker = false
            pendingRecordMode = null // 关闭即放弃待确认模式，重进回到列表
        }
        ModalBottomSheet(
            onDismissRequest = { showModePicker = false; pendingRecordMode = null },
            sheetState = sheetState,
            shape = SheetTopShape
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 16.dp)
            ) {
                val pending = pendingRecordMode
                if (pending == null) {
                    Text(
                        text = stringResource(R.string.record_history),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    RecordModeRow(
                        title = stringResource(R.string.record_mode_manual),
                        description = stringResource(R.string.record_mode_manual_desc),
                        selected = uiState.recordingMode == RecordingMode.MANUAL,
                        onSelect = { pendingRecordMode = RecordingMode.MANUAL }
                    )
                    RecordModeRow(
                        title = stringResource(R.string.record_mode_auto),
                        description = stringResource(R.string.record_mode_auto_desc),
                        selected = uiState.recordingMode == RecordingMode.AUTO,
                        onSelect = { pendingRecordMode = RecordingMode.AUTO }
                    )
                    RecordModeRow(
                        title = stringResource(R.string.record_mode_off),
                        description = stringResource(R.string.record_mode_off_desc),
                        selected = uiState.recordingMode == RecordingMode.OFF,
                        onSelect = {
                            onRecordModeChange(RecordingMode.OFF)
                            dismiss()
                        }
                    )
                } else {
                    Text(
                        text = stringResource(R.string.performance_warning),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Text(
                        text = stringResource(R.string.history_warning_message),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        ExpressiveTextButton(
                            label = stringResource(com.github.heartratemonitor_compose.ui.widgets.R.string.cancel),
                            onClick = { pendingRecordMode = null } // 返回三档列表
                        )
                        Spacer(Modifier.width(8.dp))
                        ExpressiveButton(
                            label = stringResource(com.github.heartratemonitor_compose.ui.widgets.R.string.confirm),
                            onClick = {
                                onRecordModeChange(pending)
                                dismiss()
                            }
                        )
                    }
                }
            }
        }
    }

    if (showSpeedDialog.value) {
        val sheetState = rememberExpandedSheetState()
        val dismiss = rememberSheetDismissHandler(sheetState) { showSpeedDialog.value = false }
        ModalBottomSheet(
            onDismissRequest = { showSpeedDialog.value = false },
            sheetState = sheetState,
            shape = SheetTopShape
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    text = stringResource(R.string.enable_speed_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Text(
                    text = stringResource(R.string.speed_gps_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    ExpressiveTextButton(
                        label = stringResource(com.github.heartratemonitor_compose.ui.widgets.R.string.cancel),
                        onClick = dismiss
                    )
                    Spacer(Modifier.width(8.dp))
                    ExpressiveButton(
                        label = stringResource(com.github.heartratemonitor_compose.ui.widgets.R.string.confirm),
                        onClick = {
                            onSpeedDisplayChange(true)
                            dismiss()
                        }
                    )
                }
            }
        }
    }
}


@Composable
private fun ConnectionAndBackgroundGroup(
    uiState: FunctionSettingsUiState,
    onHideFromRecentsChange: (Boolean) -> Unit,
    onNavAnimationDisabledChange: (Boolean) -> Unit,
    onAutoConnectChange: (Boolean) -> Unit,
    onAutoReconnectChange: (Boolean) -> Unit,
    onScanFilterChange: (Boolean) -> Unit
) {
    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val iconTint = MaterialTheme.colorScheme.onPrimaryContainer

    SettingsGroupCard {
        SettingsItem(isFirst = true) {
            SettingsSwitch(
                checked = uiState.hideFromRecentsEnabled,
                onCheckedChange = onHideFromRecentsChange,
                title = stringResource(R.string.hide_from_recents),
                subtitle = stringResource(R.string.subtitle_hide_from_recents),
                leadingIcon = painterResource(com.github.heartratemonitor_compose.ui.widgets.R.drawable.ic_hide_source),
                leadingIconContainerColor = containerColor,
                leadingIconTint = iconTint
            )
        }

        SettingsItem {
            SettingsSwitch(
                checked = uiState.navAnimationDisabled,
                onCheckedChange = onNavAnimationDisabledChange,
                title = stringResource(R.string.nav_animation_disabled),
                subtitle = stringResource(R.string.subtitle_nav_animation_disabled),
                leadingIcon = painterResource(R.drawable.ic_nav_animation_off),
                leadingIconContainerColor = containerColor,
                leadingIconTint = iconTint
            )
        }

        SettingsItem {
            SettingsSwitch(
                checked = uiState.autoConnectEnabled,
                onCheckedChange = onAutoConnectChange,
                title = stringResource(R.string.auto_connect_favorite),
                subtitle = stringResource(R.string.subtitle_auto_connect_favorite),
                leadingIcon = painterResource(R.drawable.ic_bluetooth_connected_symbol),
                leadingIconContainerColor = containerColor,
                leadingIconTint = iconTint
            )
        }

        SettingsItem {
            SettingsSwitch(
                checked = uiState.autoReconnectEnabled,
                onCheckedChange = onAutoReconnectChange,
                title = stringResource(R.string.auto_reconnect),
                subtitle = stringResource(R.string.subtitle_auto_reconnect),
                leadingIcon = painterResource(R.drawable.ic_plug_connect),
                leadingIconContainerColor = containerColor,
                leadingIconTint = iconTint
            )
        }

        SettingsItem(isLast = true) {
            SettingsSwitch(
                checked = uiState.scanFilterEnabled,
                onCheckedChange = onScanFilterChange,
                title = stringResource(R.string.scan_filter),
                subtitle = stringResource(R.string.subtitle_scan_filter),
                leadingIcon = painterResource(R.drawable.ic_search_filter),
                leadingIconContainerColor = containerColor,
                leadingIconTint = iconTint
            )
        }
    }
}

/** 记录模式单选行（三档选择器子项）。 */
@Composable
private fun RecordModeRow(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 记录模式下拉副标题：显示当前档位名。 */
private fun recordModeLabel(mode: String): Int = when (mode) {
    RecordingMode.AUTO -> R.string.record_mode_auto
    RecordingMode.OFF -> R.string.record_mode_off
    else -> R.string.record_mode_manual
}
