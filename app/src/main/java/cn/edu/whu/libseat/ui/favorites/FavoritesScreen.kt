package cn.edu.whu.libseat.ui.favorites

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EventSeat
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.whu.libseat.LibrarySeatApp
import cn.edu.whu.libseat.core.time.TimeText
import cn.edu.whu.libseat.domain.model.FavoriteSeat
import cn.edu.whu.libseat.ui.components.AppBottomBar
import cn.edu.whu.libseat.ui.components.AppCard
import cn.edu.whu.libseat.ui.components.EmptyState
import cn.edu.whu.libseat.ui.components.HDivider
import cn.edu.whu.libseat.ui.components.InfoBanner
import cn.edu.whu.libseat.ui.components.Pill
import cn.edu.whu.libseat.ui.components.Tag
import cn.edu.whu.libseat.ui.simpleViewModelFactory
import androidx.compose.material.icons.rounded.Info

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    app: LibrarySeatApp,
    onOpenSeatMap: (roomId: Long, buildingId: Long, floor: Int, date: String) -> Unit,
    onGoHome: () -> Unit,
    onGoSettings: () -> Unit,
) {
    val vm: FavoritesViewModel = viewModel(
        factory = simpleViewModelFactory { FavoritesViewModel(app.container.repository, app.container.quickBook) }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<FavoriteSeat?>(null) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("常用座位", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "保存常坐的位置，一键完成预约",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onGoHome) {
                        Icon(Icons.Rounded.EventSeat, contentDescription = "去选座")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            AppBottomBar(currentRoute = "favorites") { route ->
                when (route) {
                    "home" -> onGoHome()
                    "settings" -> onGoSettings()
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                InfoBanner(
                    icon = Icons.Rounded.Info,
                    text = if (state.settings.fallbackEnabled) {
                        "一键预约会使用默认时间段 ${TimeText.minutesToHhmm(state.settings.defaultStart)}-" +
                            "${TimeText.minutesToHhmm(state.settings.defaultEnd)}；座位被占用时自动换到同区域最近空座。"
                    } else {
                        "自动换座已关闭：座位被占用时会直接提示预约失败。可在设置中开启。"
                    },
                )
            }

            if (state.favorites.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.Star,
                        title = "还没有常用座位",
                        description = "进入任意区域的座位图，点开一个空座并点击右上角星标，就能把它加入这里。",
                        actionLabel = "去首页选座",
                        onAction = onGoHome,
                    )
                }
            } else {
                items(state.favorites, key = { "${it.buildingId}-${it.seatId}" }) { favorite ->
                    FavoriteRow(
                        favorite = favorite,
                        start = state.settings.defaultStart,
                        end = state.settings.defaultEnd,
                        booking = state.bookingSeatId == favorite.seatId,
                        onBook = { vm.book(favorite) },
                        onOpen = {
                            onOpenSeatMap(
                                favorite.roomId,
                                favorite.buildingId,
                                favorite.floor,
                                TimeText.iso(TimeText.today()),
                            )
                        },
                        onDelete = { pendingDelete = favorite },
                    )
                }
                item {
                    Text(
                        "列表按收藏时间倒序排列，最新收藏的座位在最前。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    pendingDelete?.let { favorite ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("移除常用座位？") },
            text = { Text("${favorite.buildingName} ${favorite.floor}F ${favorite.roomName} ${favorite.seatName} 号") },
            confirmButton = {
                TextButton(onClick = {
                    vm.remove(favorite)
                    pendingDelete = null
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun FavoriteRow(
    favorite: FavoriteSeat,
    start: Int,
    end: Int,
    booking: Boolean,
    onBook: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    AppCard(padding = 0.dp, onClick = onOpen) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            favorite.seatName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold,
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${favorite.buildingName} ${favorite.floor}F",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.width(6.dp))
                        Pill("常用", background = MaterialTheme.colorScheme.tertiaryContainer,
                            foreground = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        favorite.roomName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (favorite.nearWindow) Tag("靠窗")
                        if (favorite.hasPower) Tag("有电源")
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "移除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            HDivider()
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${TimeText.minutesToHhmm(start)} - ${TimeText.minutesToHhmm(end)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpen) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("调整")
                }
                Spacer(Modifier.width(4.dp))
                Surface(
                    onClick = { if (!booking) onBook() },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Row(
                        Modifier
                            .height(38.dp)
                            .padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (booking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "预约中",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(
                                Icons.Rounded.EventSeat,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                "一键预约",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}
