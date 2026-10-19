package cn.edu.whu.libseat.ui.quickbook

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.whu.libseat.LibrarySeatApp
import cn.edu.whu.libseat.core.time.TimeText
import cn.edu.whu.libseat.ui.components.AppCard
import cn.edu.whu.libseat.ui.components.HDivider
import cn.edu.whu.libseat.ui.components.InfoBanner
import cn.edu.whu.libseat.ui.components.Pill
import cn.edu.whu.libseat.ui.components.Tag
import cn.edu.whu.libseat.ui.simpleViewModelFactory
import java.time.LocalDate

/**
 * 小部件“预约”后的确认页：进来即提交，滑块验证卡片会盖在这一页上方，
 * 结果直接在本页给出，用户不需要再回首页确认。
 */
@Composable
fun QuickBookScreen(
    app: LibrarySeatApp,
    request: QuickBookRequest,
    onClose: () -> Unit,
    onOpenHome: () -> Unit,
    onLogin: () -> Unit,
) {
    val vm: QuickBookViewModel = viewModel(
        factory = simpleViewModelFactory {
            QuickBookViewModel(app.container.repository, app.container.quickBook, request)
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val date = TimeText.parseIso(request.date) ?: LocalDate.now()

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text("快速预约", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "来自桌面小部件 · 确认后立即提交",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.phase == QuickBookPhase.BOOKING || state.phase == QuickBookPhase.LOADING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            state.favorite?.let { fav ->
                AppCard {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        fav.seatName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    Text(
                                        "号",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${fav.buildingName} ${fav.floor}F",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    "${fav.floor}F ${fav.roomName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (fav.nearWindow || fav.hasPower) {
                                    Spacer(Modifier.height(5.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (fav.nearWindow) Tag("靠窗")
                                        if (fav.hasPower) Tag("有电源", Icons.Rounded.Bolt)
                                    }
                                }
                            }
                            Pill(phaseLabel(state.phase))
                        }

                        Spacer(Modifier.height(12.dp))
                        HDivider()
                        Spacer(Modifier.height(10.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                "${TimeText.relative(date)} · ${TimeText.pretty(date)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${TimeText.minutesToHhmm(request.start)} - ${TimeText.minutesToHhmm(request.end)}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "共 ${durationText(request.end - request.start)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            StatusCard(state = state)

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (state.phase) {
                    QuickBookPhase.SUCCESS -> {
                        Button(onClick = onOpenHome, modifier = Modifier.weight(1f)) {
                            Text("查看我的预约")
                        }
                        TextButton(onClick = onClose) { Text("关闭") }
                    }

                    QuickBookPhase.FAILURE -> {
                        Button(onClick = vm::submit, modifier = Modifier.weight(1f)) {
                            Text("重试")
                        }
                        TextButton(onClick = onClose) { Text("关闭") }
                    }

                    QuickBookPhase.NOT_LOGGED_IN -> {
                        Button(onClick = onLogin, modifier = Modifier.weight(1f)) {
                            Text("去登录")
                        }
                        TextButton(onClick = onClose) { Text("关闭") }
                    }

                    QuickBookPhase.MISSING -> {
                        Button(onClick = onOpenHome, modifier = Modifier.weight(1f)) {
                            Text("回首页选座")
                        }
                        TextButton(onClick = onClose) { Text("关闭") }
                    }

                    else -> {
                        TextButton(onClick = onClose) { Text("取消") }
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            InfoBanner(
                icon = Icons.Rounded.Info,
                text = "预约成功后刷通道机即签到；暂离超过 30 分钟会释放座位。" +
                    "小部件里也可以直接改座位和时间再预约。",
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusCard(state: QuickBookUiState) {
    val (container, content) = when (state.phase) {
        QuickBookPhase.SUCCESS -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer

        QuickBookPhase.FAILURE -> MaterialTheme.colorScheme.error.copy(alpha = 0.10f) to
            MaterialTheme.colorScheme.error

        QuickBookPhase.NOT_LOGGED_IN -> MaterialTheme.colorScheme.tertiaryContainer to
            MaterialTheme.colorScheme.onTertiaryContainer

        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f) to
            MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = container,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (state.phase) {
                    QuickBookPhase.BOOKING, QuickBookPhase.LOADING -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = content,
                    )

                    else -> Icon(
                        imageVector = phaseIcon(state.phase),
                        contentDescription = null,
                        tint = content,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    phaseTitle(state),
                    style = MaterialTheme.typography.titleSmall,
                    color = content,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                phaseDescription(state),
                style = MaterialTheme.typography.bodySmall,
                color = content.copy(alpha = 0.85f),
            )
            if (state.phase == QuickBookPhase.BOOKING) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "如弹出安全验证，请拖动滑块完成；验证通过后会自动提交预约。",
                    style = MaterialTheme.typography.labelSmall,
                    color = content.copy(alpha = 0.75f),
                )
            }
        }
    }
}

private fun phaseIcon(phase: QuickBookPhase): ImageVector = when (phase) {
    QuickBookPhase.SUCCESS -> Icons.Rounded.CheckCircle
    QuickBookPhase.FAILURE -> Icons.Rounded.ErrorOutline
    QuickBookPhase.NOT_LOGGED_IN -> Icons.Rounded.Lock
    else -> Icons.Rounded.Info
}

private fun phaseLabel(phase: QuickBookPhase): String = when (phase) {
    QuickBookPhase.SUCCESS -> "预约成功"
    QuickBookPhase.FAILURE -> "未成功"
    QuickBookPhase.NOT_LOGGED_IN -> "需登录"
    QuickBookPhase.MISSING -> "已失效"
    QuickBookPhase.BOOKING, QuickBookPhase.LOADING -> "提交中"
}

private fun phaseTitle(state: QuickBookUiState): String = when (state.phase) {
    QuickBookPhase.SUCCESS -> "预约成功"
    QuickBookPhase.FAILURE -> "预约未成功"
    QuickBookPhase.NOT_LOGGED_IN -> "需要重新登录"
    QuickBookPhase.MISSING -> "座位信息已失效"
    QuickBookPhase.BOOKING -> "正在提交预约…"
    QuickBookPhase.LOADING -> "正在读取常用座位…"
}

private fun phaseDescription(state: QuickBookUiState): String = when (state.phase) {
    QuickBookPhase.SUCCESS -> buildString {
        append("已预约 ${state.bookedSeatName} 号座位")
        if (state.usedFallback) {
            append("（${state.requestedSeatName} 号已被占用，已自动换到同区域最近的空座）")
        }
        append("，请按时到馆刷卡签到。")
    }

    QuickBookPhase.FAILURE -> state.message
    QuickBookPhase.NOT_LOGGED_IN -> state.message
    QuickBookPhase.MISSING -> state.message
    QuickBookPhase.BOOKING -> "正在为你锁定座位，请稍候…"
    QuickBookPhase.LOADING -> "正在准备预约信息"
}

private fun durationText(minutes: Int): String {
    val safe = minutes.coerceAtLeast(0)
    val hours = safe / 60
    val rest = safe % 60
    return when {
        hours > 0 && rest > 0 -> "$hours 小时 $rest 分钟"
        hours > 0 -> "$hours 小时"
        else -> "$rest 分钟"
    }
}
