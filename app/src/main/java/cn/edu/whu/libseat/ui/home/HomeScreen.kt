package cn.edu.whu.libseat.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EventSeat
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.whu.libseat.LibrarySeatApp
import cn.edu.whu.libseat.core.time.TimeText
import cn.edu.whu.libseat.domain.model.TimeSlot
import cn.edu.whu.libseat.ui.components.TimeDropdown
import cn.edu.whu.libseat.domain.model.FavoriteSeat
import cn.edu.whu.libseat.domain.model.Reservation
import cn.edu.whu.libseat.domain.model.RoomStats
import cn.edu.whu.libseat.ui.components.AppBottomBar
import cn.edu.whu.libseat.ui.components.AppCard
import cn.edu.whu.libseat.ui.components.CapacityBar
import cn.edu.whu.libseat.ui.components.EmptyState
import cn.edu.whu.libseat.ui.components.HDivider
import cn.edu.whu.libseat.ui.components.InfoBanner
import cn.edu.whu.libseat.ui.components.LoadingState
import cn.edu.whu.libseat.ui.components.Pill
import cn.edu.whu.libseat.ui.components.SectionHeader
import cn.edu.whu.libseat.ui.components.Tag
import cn.edu.whu.libseat.ui.simpleViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    app: LibrarySeatApp,
    onOpenSeatMap: (roomId: Long, buildingId: Long, floor: Int, date: String) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogin: () -> Unit,
) {
    val vm: HomeViewModel = viewModel(
        factory = simpleViewModelFactory { HomeViewModel(app.container.repository, app.container.quickBook) }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showBuildings by remember { mutableStateOf(false) }
    var showStopConfirm by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    LifecycleResumeEffect(Unit) {
        vm.refreshOnResume()
        onPauseOrDispose { }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            AppBottomBar(currentRoute = "home") { route ->
                when (route) {
                    "favorites" -> onOpenFavorites()
                    "settings" -> onOpenSettings()
                }
            }
        },
    ) { padding ->
        if (state.loading && state.catalog == null) {
            Box(Modifier.fillMaxSize().padding(padding)) { LoadingState("正在同步图书馆数据") }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    HomeHeader(
                        demoMode = state.settings.demoMode,
                        webLoggedIn = state.settings.webLoggedIn,
                        username = state.profile?.name?.takeIf { it.isNotBlank() }
                            ?: state.settings.displayName.ifBlank { state.settings.username },
                        onRefresh = { vm.load(forceRefresh = true) },
                        onSettings = onOpenSettings,
                    )
                }

                if (state.settings.demoMode) {
                    item {
                        DemoBanner(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            webLoggedIn = state.settings.webLoggedIn,
                            onLogin = onLogin,
                            onAccount = onOpenSettings,
                        )
                    }
                }

                if (state.error != null) {
                    item {
                        AppCard(modifier = Modifier.padding(horizontal = 20.dp)) {
                            Column {
                                Text("数据加载失败", style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    state.error.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { vm.load(true) }) { Text("重试") }
                            }
                        }
                    }
                }

                item {
                    DateSelector(
                        date = state.date,
                        windowText = "${TimeText.minutesToHhmm(state.settings.defaultStart)} - " +
                            TimeText.minutesToHhmm(state.settings.defaultEnd),
                        onSelect = vm::selectDate,
                    )
                }

                item {
                    Box(Modifier.padding(horizontal = 20.dp)) {
                        SectionHeader(
                            title = "常用座位 · 一键预约",
                            subtitle = "点卡片选时间段，失败自动换附近空座",
                            actionLabel = "管理",
                            onAction = onOpenFavorites,
                        )
                    }
                }

                if (state.favorites.isEmpty()) {
                    item {
                        EmptyFavoritesCard(
                            onPick = {
                                state.floorRooms.firstOrNull()?.let {
                                    onOpenSeatMap(
                                        it.room.id,
                                        it.room.buildingId,
                                        it.room.floor,
                                        TimeText.iso(state.date),
                                    )
                                }
                            },
                        )
                    }
                } else {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.favorites.take(6), key = { "${it.buildingId}-${it.seatId}" }) { fav ->
                                FavoriteCard(
                                    favorite = fav,
                                    start = state.settings.defaultStart,
                                    end = state.settings.defaultEnd,
                                    booking = state.bookingFavoriteSeatId == fav.seatId,
                                    usage = state.seatUsage[fav.seatId]
                                        ?: state.seatUsage.values.firstOrNull {
                                            it.label.contains("· ${fav.seatName} 号")
                                        },
                                    onBook = { vm.quickBookFavorite(fav) },
                                    onOpen = {
                                        onOpenSeatMap(
                                            fav.roomId,
                                            fav.buildingId,
                                            fav.floor,
                                            TimeText.iso(state.date),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                item {
                    LibraryCard(
                        state = state,
                        onSwitchBuilding = { showBuildings = true },
                        onSelectFloor = vm::selectFloor,
                        onOpenRoom = { room ->
                            onOpenSeatMap(
                                room.room.id,
                                room.room.buildingId,
                                room.room.floor,
                                TimeText.iso(state.date),
                            )
                        },
                    )
                }

                if (state.reservation != null) {
                    item {
                        ReservationCard(
                            reservation = state.reservation!!,
                            stopping = state.stoppingReservation,
                            onCancel = { vm.cancelReservation(state.reservation!!.id) },
                            onStop = { showStopConfirm = true },
                        )
                    }
                }

                item {
                    InfoBanner(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        icon = Icons.Rounded.Info,
                        text = "预约成功后刷通道机即签到；暂离超过 30 分钟会释放座位；" +
                            "违约满 7 次将进入 7 天黑名单。次日座位 22:45 起开放。",
                    )
                }

                item {
                    Text(
                        text = "座位数据来自 seat.lib.whu.edu.cn",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }

    state.quickBookTarget?.let { favorite ->
        QuickBookTimeDialog(
            favorite = favorite,
            isToday = state.date == TimeText.today(),
            defaultStart = state.settings.defaultStart,
            defaultEnd = state.settings.defaultEnd,
            booking = state.bookingFavoriteSeatId == favorite.seatId,
            onConfirm = vm::confirmQuickBook,
            onDismiss = vm::dismissQuickBookPicker,
        )
    }

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text("停止使用") },
            text = { Text("确认结束当前履约中的座位使用？结束后座位会被释放，本次使用提前结束。") },
            confirmButton = {
                TextButton(onClick = {
                    showStopConfirm = false
                    state.reservation?.let { vm.stopReservation(it.id) }
                }) { Text("确认停止") }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) { Text("再想想") }
            },
        )
    }

    if (showBuildings) {
        ModalBottomSheet(
            onDismissRequest = { showBuildings = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("选择图书馆", style = MaterialTheme.typography.titleLarge)
                Text(
                    "选择后会保存为默认图书馆",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                state.catalog?.buildings?.forEach { building ->
                    val selected = building.id == state.buildingId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                vm.selectBuilding(building.id)
                                showBuildings = false
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.MenuBook,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(building.name, style = MaterialTheme.typography.titleSmall)
                            val floors = state.catalog?.floorsOf(building.id).orEmpty()
                            Text(
                                "${floors.size} 个楼层 · ${floors.joinToString(" ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selected) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    demoMode: Boolean,
    webLoggedIn: Boolean,
    username: String,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        Color.Transparent,
                    )
                )
            )
            .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "WHU LIBRARY",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(2.dp))
                Text("图书馆预约", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        webLoggedIn && username.isNotBlank() -> "$username · 已登录"
                        demoMode -> "演示数据 · 随时可登录真实账号"
                        username.isNotBlank() -> username
                        else -> "已连接图书馆系统"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Rounded.Settings, contentDescription = "设置")
            }
        }
    }
}

@Composable
private fun DemoBanner(
    modifier: Modifier = Modifier,
    webLoggedIn: Boolean,
    onLogin: () -> Unit,
    onAccount: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (webLoggedIn) "已登录（Web 会话）· 当前展示演示数据"
            else "当前为演示模式，座位与预约为本地模拟",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f),
        )
        Surface(
            onClick = if (webLoggedIn) onAccount else onLogin,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.tertiary,
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (webLoggedIn) Icons.Rounded.Settings else Icons.Rounded.Login,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiary,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (webLoggedIn) "账号" else "登录",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiary,
                )
            }
        }
    }
}

@Composable
private fun DateSelector(
    date: java.time.LocalDate,
    windowText: String,
    onSelect: (java.time.LocalDate) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DateChip("今天", selected = date == TimeText.today()) { onSelect(TimeText.today()) }
        Spacer(Modifier.width(8.dp))
        DateChip("明天", selected = date == TimeText.tomorrow()) { onSelect(TimeText.tomorrow()) }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.AccessTime,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                windowText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DateChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun LibraryCard(
    state: HomeUiState,
    onSwitchBuilding: () -> Unit,
    onSelectFloor: (Int) -> Unit,
    onOpenRoom: (RoomStats) -> Unit,
) {
    AppCard(modifier = Modifier.padding(horizontal = 20.dp), padding = 0.dp) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.buildingName, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(6.dp))
                        Pill("默认", background = MaterialTheme.colorScheme.secondaryContainer,
                            foreground = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Text(
                        "${state.floors.size} 个楼层 · ${state.rooms.size} 个区域",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onSwitchBuilding) {
                    Text("切换")
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (state.countsKnown) "${state.freeSeats}" else "—",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (state.countsKnown) " / ${state.totalSeats} 个座位" else " / 余量待获取",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "实时余量",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            CapacityBar(free = state.freeSeats, total = state.totalSeats)

            Spacer(Modifier.height(14.dp))
            if (state.floors.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.floors) { floor ->
                        FloorChip(
                            floor = floor,
                            selected = state.floor == floor,
                            onClick = { onSelectFloor(floor) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            if (state.floorRooms.isEmpty()) {
                Text(
                    "该楼层暂无可预约区域",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                state.floorRooms.forEachIndexed { index, room ->
                    if (index > 0) HDivider()
                    RoomRow(room = room, onClick = { onOpenRoom(room) })
                }
            }
        }
    }
}

@Composable
private fun FloorChip(floor: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Text(
            "${floor}F",
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun RoomRow(room: RoomStats, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(room.room.name, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${room.room.floor}F",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Box(Modifier.width(64.dp)) {
                    CapacityBar(free = room.free, total = room.total)
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (room.countsKnown) "余 ${room.free}" else "余 —",
                style = MaterialTheme.typography.titleSmall,
                color = if (room.countsKnown && room.free == 0) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            )
            Text(
                if (room.countsKnown) "共 ${room.total}" else "共 —",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ReservationCard(
    reservation: Reservation,
    stopping: Boolean,
    onCancel: () -> Unit,
    onStop: () -> Unit,
) {
    val beginMinutes = TimeText.hhmmToMinutes(reservation.begin)
    val endMinutes = TimeText.hhmmToMinutes(reservation.end)
    val nowMinutes = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
    val withinWindow = reservation.date.startsWith(TimeText.iso(TimeText.today())) &&
        beginMinutes != null && endMinutes != null &&
        nowMinutes in beginMinutes until endMinutes

    // 状态字段缺失时，用预约时间段兜底判断“履约中”，避免一律显示待签到
    val status = reservation.status.uppercase().ifBlank {
        if (withinWindow) "IN_USE" else "RESERVE"
    }
    val statusLabel = when (status) {
        "IN_USE", "USING", "CHECKED_IN", "CHECK_IN", "ACTIVE" -> "履约中"
        "AWAY", "LEAVE" -> "暂离中"
        "RESERVE", "BOOKED", "RESERVED" -> "待签到"
        "COMPLETE" -> "已完成"
        else -> reservation.status.ifBlank { "已预约" }
    }
    // 待签到（还没刷通道机）才能取消预约；已签到 / 履约中 / 暂离只能停止使用
    val inUse = status == "IN_USE" || status == "USING" ||
        status == "CHECKED_IN" || status == "CHECK_IN" || status == "ACTIVE"
    val canStop = inUse || status == "AWAY" || status == "LEAVE"
    val hint = when (status) {
        "IN_USE", "USING", "CHECKED_IN", "CHECK_IN", "ACTIVE" ->
            "已签到，暂离超过 30 分钟会释放座位"

        "AWAY", "LEAVE" -> "暂离中，请及时返回，超时会释放座位"
        else -> "入馆刷卡即签到，请提前 10 分钟到馆"
    }
    val statusColor = when (status) {
        "AWAY", "LEAVE" -> Color(0xFF8A5A00)
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.EventSeat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (canStop) "当前使用中" else "当前预约",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.weight(1f))
                Pill(
                    statusLabel,
                    background = MaterialTheme.colorScheme.surface,
                    foreground = statusColor,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Place,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    reservation.location.ifBlank { "图书馆座位" }.replace("|", " · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${reservation.date}  ${reservation.begin} - ${reservation.end}" +
                    if (reservation.receipt.isNotBlank()) "  ·  ${reservation.receipt}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    modifier = Modifier.weight(1f),
                )
                if (stopping) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(horizontal = 14.dp)
                            .size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    TextButton(onClick = if (canStop) onStop else onCancel) {
                        Icon(
                            if (canStop) Icons.Rounded.PowerSettingsNew else Icons.Rounded.Close,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(if (canStop) "停止使用" else "取消预约")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyFavoritesCard(onPick: () -> Unit) {
    AppCard(modifier = Modifier.padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.StarBorder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("还没有常用座位", style = MaterialTheme.typography.titleSmall)
                Text(
                    "在座位图里收藏常坐的位置",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onPick) { Text("去选座") }
        }
    }
}

@Composable
private fun FavoriteCard(
    favorite: FavoriteSeat,
    start: Int,
    end: Int,
    booking: Boolean,
    usage: cn.edu.whu.libseat.ui.stats.SeatUsageStat?,
    onBook: () -> Unit,
    onOpen: () -> Unit,
) {
    Surface(
        modifier = Modifier.width(252.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant
        ),
        onClick = onOpen,
    ) {
        Column(Modifier.padding(14.dp)) {
            // 卡片内容结构固定：统计行 / 座位号 / 位置 / 属性 / 时间段 各自占位，
            // 缺数据时用透明占位补齐，保证同一行卡片的宽度和高度完全一致。
            Text(
                text = usage?.let {
                    "近一年预约 ${it.count} 次 · 共 ${cn.edu.whu.libseat.ui.stats.formatMinutes(it.minutes)}"
                } ?: "近一年暂无预约记录",
                style = MaterialTheme.typography.labelSmall,
                color = if (usage != null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    favorite.seatName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                Text("号", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Rounded.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${favorite.buildingName} ${favorite.floor}F · ${favorite.roomName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!favorite.nearWindow && !favorite.hasPower) {
                    // 没有属性标签时用透明占位，避免这张卡片比其他卡片矮一截
                    Box(Modifier.alpha(0f)) { Tag("靠窗") }
                }
                if (favorite.nearWindow) Tag("靠窗")
                if (favorite.hasPower) Tag("有电源")
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${TimeText.minutesToHhmm(start)}-${TimeText.minutesToHhmm(end)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    onClick = { if (!booking) onBook() },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Box(
                        Modifier
                            .height(30.dp)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (booking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(
                                "一键预约",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}


/**
 * 快速预约：先选时间段，确认后预约（替代“去设置里改默认时间”）。
 */
@Composable
private fun QuickBookTimeDialog(
    favorite: FavoriteSeat,
    isToday: Boolean,
    defaultStart: Int,
    defaultEnd: Int,
    booking: Boolean,
    onConfirm: (start: Int, end: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // 官方允许从当前精确时间开始：今天默认选中“现在”，开始时间不必半小时取整
    val initialStart = if (isToday) TimeText.nowMinutes() else defaultStart
    var start by remember { mutableIntStateOf(initialStart) }
    var startIsNow by remember { mutableStateOf(isToday) }
    var end by remember { mutableIntStateOf(maxOf(defaultEnd, initialStart + 30)) }

    val nowSlot = cn.edu.whu.libseat.domain.model.TimeSlot(TimeText.nowMinutes(), "现在", isNow = true)
    val startSlots = (
        if (isToday) listOf(nowSlot) else emptyList()
        ) + TimeText.buildDaySlots(7 * 60, 21 * 60).filter {
        !isToday || it.minutes > TimeText.nowMinutes()
    }.ifEmpty { TimeText.buildDaySlots(7 * 60, 21 * 60) }
    val endSlots = TimeText.buildDaySlots(start + 30, 22 * 60)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("快速预约") },
        text = {
            Column {
                Text(
                    "${favorite.buildingName} ${favorite.roomName} · ${favorite.seatName} 号座位",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (favorite.nearWindow || favorite.hasPower) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (favorite.nearWindow) Tag("靠窗")
                        if (favorite.hasPower) Tag("有电源")
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TimeDropdown(
                        label = "开始",
                        value = if (startIsNow) "现在" else TimeText.minutesToHhmm(start),
                        options = startSlots,
                        modifier = Modifier.weight(1f),
                        onSelect = {
                            start = it.minutes
                            startIsNow = it.isNow
                            if (end <= start) end = start + 30
                        },
                    )
                    Text("至", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TimeDropdown(
                        label = "结束",
                        value = TimeText.minutesToHhmm(end),
                        options = endSlots,
                        modifier = Modifier.weight(1f),
                        onSelect = { end = it.minutes },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (startIsNow) "已选择从现在开始，到馆刷卡即签到。"
                    else "预约后请在开始时间前后 30 分钟内到馆刷卡签到。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(start, end) }, enabled = !booking) {
                if (booking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("预约")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !booking) { Text("取消") }
        },
    )
}