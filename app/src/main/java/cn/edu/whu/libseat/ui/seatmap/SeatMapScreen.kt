package cn.edu.whu.libseat.ui.seatmap

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EventSeat
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.whu.libseat.LibrarySeatApp
import cn.edu.whu.libseat.core.time.TimeText
import cn.edu.whu.libseat.domain.model.Seat
import cn.edu.whu.libseat.domain.model.TimeSlot
import cn.edu.whu.libseat.ui.components.EmptyState
import cn.edu.whu.libseat.ui.components.InfoBanner
import cn.edu.whu.libseat.ui.components.LoadingState
import cn.edu.whu.libseat.ui.components.Pill
import cn.edu.whu.libseat.ui.components.SeatCell
import cn.edu.whu.libseat.ui.components.SeatLegend
import cn.edu.whu.libseat.ui.components.Tag
import cn.edu.whu.libseat.ui.simpleViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeatMapScreen(
    app: LibrarySeatApp,
    roomId: Long,
    buildingId: Long,
    floor: Int,
    dateText: String,
    onBack: () -> Unit,
) {
    val vm: SeatMapViewModel = viewModel(
        key = "seatmap-$roomId-$dateText",
        factory = simpleViewModelFactory {
            SeatMapViewModel(app.container.repository, roomId, buildingId, floor, dateText)
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.room?.name ?: state.map?.roomName ?: "座位图",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            "${state.building?.name.orEmpty()} ${state.room?.floor?.let { "${it}F" }.orEmpty()} · " +
                                TimeText.pretty(state.date),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.selectedSeat != null) {
                        IconButton(onClick = vm::toggleFavorite) {
                            Icon(
                                if (state.selectedFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                contentDescription = "收藏",
                                tint = if (state.selectedFavorite) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = vm::load) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.map != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Pill(
                        "${state.map!!.freeCount} 个空座",
                        background = MaterialTheme.colorScheme.primaryContainer,
                        foreground = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "共 ${state.map!!.seats.size} 座",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "点选绿色座位",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SeatLegend(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = 4.dp),
            ) {
                when {
                    state.loading -> LoadingState("正在加载座位图")
                    state.error != null -> EmptyState(
                        icon = Icons.Rounded.EventSeat,
                        title = "座位图加载失败",
                        description = state.error.orEmpty(),
                        actionLabel = "重试",
                        onAction = vm::load,
                    )

                    state.map == null -> EmptyState(
                        icon = Icons.Rounded.EventSeat,
                        title = "该区域暂无座位",
                        description = "换一个区域试试",
                    )

                    else -> SeatGrid(state = state, onSeatClick = vm::selectSeat)
                }
            }
        }
    }

    val selected = state.selectedSeat
    if (selected != null) {
        ModalBottomSheet(
            onDismissRequest = vm::clearSelection,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            SeatDetailSheet(
                state = state,
                seat = selected,
                onToggleFavorite = vm::toggleFavorite,
                onSelectStart = vm::selectStart,
                onSelectEnd = vm::selectEnd,
                onBook = vm::book,
                isToday = state.date == TimeText.today(),
            )
        }
    }

    state.booked?.let { reservation ->
        AlertDialog(
            onDismissRequest = {
                vm.dismissBooked()
                onBack()
            },
            icon = {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = { Text("预约成功") },
            text = {
                Column {
                    Text(
                        reservation.location.ifBlank { "${selected?.name ?: ""} 号座位" },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${reservation.date}  ${reservation.begin} - ${reservation.end}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        reservation.message.ifBlank { "入馆刷通道机即签到，请在开始时间 30 分钟内到馆" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissBooked()
                    onBack()
                }) { Text("知道了") }
            },
        )
    }
}

@Composable
private fun SeatGrid(state: SeatMapUiState, onSeatClick: (Seat) -> Unit) {
    val map = state.map ?: return
    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .align(Alignment.CenterHorizontally),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 讲台方向提示
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        "窗  ·  自 习 区  ·  窗",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))

                for (row in 1..map.rows) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (col in 1..map.cols) {
                            val seat = map.seatAt(row, col)
                            if (seat == null) {
                                Box(Modifier.size(27.dp))
                            } else {
                                SeatCell(
                                    seat = seat,
                                    selected = state.selectedSeat?.id == seat.id,
                                    favorite = state.favorites.contains(seat.id),
                                    onClick = { onSeatClick(seat) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    "座位号与馆内桌贴一致",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            InfoBanner(
                modifier = Modifier.padding(horizontal = 20.dp),
                icon = Icons.Rounded.Info,
                text = "点选空座后可在下方选择起止时间。预约成功后刷通道机即签到；" +
                    "暂离超过 30 分钟（用餐时段 60 分钟）会释放座位并记违约。",
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SeatDetailSheet(
    state: SeatMapUiState,
    seat: Seat,
    onToggleFavorite: () -> Unit,
    onSelectStart: (Int) -> Unit,
    onSelectEnd: (Int) -> Unit,
    onBook: () -> Unit,
    isToday: Boolean,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    seat.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${seat.name} 号座位", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${state.building?.name.orEmpty()} ${state.room?.floor?.let { "${it}F" }.orEmpty()} · " +
                        (state.room?.name ?: state.map?.roomName.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (state.selectedFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = "收藏",
                    tint = if (state.selectedFavorite) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (seat.nearWindow) Tag("靠窗")
            if (seat.hasPower) Tag("有电源")
            Tag("空闲")
        }

        Spacer(Modifier.height(18.dp))
        Text("预约时间", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TimeDropdown(
                label = "开始",
                value = TimeText.minutesToHhmm(state.start),
                options = state.startSlots.filter {
                    !isToday || it.minutes >= TimeText.nowRoundedToHalfHour()
                }.ifEmpty { state.startSlots },
                modifier = Modifier.weight(1f),
                onSelect = { onSelectStart(it.minutes) },
            )
            Text("至", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TimeDropdown(
                label = "结束",
                value = TimeText.minutesToHhmm(state.end),
                options = state.endSlots,
                modifier = Modifier.weight(1f),
                onSelect = { onSelectEnd(it.minutes) },
            )
        }

        Spacer(Modifier.height(18.dp))
        Surface(
            onClick = { if (!state.booking) onBook() },
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (state.booking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(
                        "预约此座位",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "预约后请在开始时间前后 30 分钟内到馆刷卡签到，逾期将记违约。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TimeDropdown(
    label: String,
    value: String,
    options: List<TimeSlot>,
    modifier: Modifier = Modifier,
    onSelect: (TimeSlot) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    value,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 300.dp),
        ) {
            options.forEach { slot ->
                DropdownMenuItem(
                    text = { Text(slot.label) },
                    onClick = {
                        expanded = false
                        onSelect(slot)
                    },
                    trailingIcon = {
                        if (slot.label == value) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    },
                )
            }
        }
    }
}
