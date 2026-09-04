package com.github.heartratemonitor_compose.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.heartratemonitor_compose.data.settings.RecordingMode
import com.github.heartratemonitor_compose.feature.main.R
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 首页手动记录控件（TopAppBar 动作区）。
 *
 * 仅 MANUAL 模式渲染；AUTO 模式随连接自动记录、OFF 不记录，均不显示。
 * - 未记录：播放图标圆形按钮，点击开始（未连接时禁用）；
 * - 记录中：红色胶囊（停止图标 + 实时计时 mm:ss），点击停止并保存。
 */
@Composable
internal fun RecordControl(
    recordingMode: String,
    isConnected: Boolean,
    recordingStartTime: Long?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (recordingMode != RecordingMode.MANUAL) return

    if (recordingStartTime == null) {
        Surface(
            onClick = onStart,
            enabled = isConnected,
            modifier = modifier.size(40.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceBright,
            contentColor = if (isConnected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_play),
                    contentDescription = stringResource(R.string.start_recording),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        return
    }

    // 记录中：计时每秒刷新（LaunchedEffect 随 recordingStartTime 重启）
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(recordingStartTime) {
        while (true) {
            delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }
    val elapsedSec = ((nowMs - recordingStartTime) / 1000).coerceAtLeast(0)
    // i18n 契约 12：格式化固定 Locale.US，输出恒 ASCII 数字
    val timerText = String.format(
        Locale.US, "%02d:%02d",
        elapsedSec / 60, elapsedSec % 60
    )

    Surface(
        onClick = onStop,
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(com.github.heartratemonitor_compose.ui.widgets.R.drawable.ic_stop),
                contentDescription = stringResource(R.string.stop_recording),
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = timerText,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
