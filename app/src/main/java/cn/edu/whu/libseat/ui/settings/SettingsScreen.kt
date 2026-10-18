package cn.edu.whu.libseat.ui.settings

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.EventSeat
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.whu.libseat.LibrarySeatApp
import cn.edu.whu.libseat.core.time.TimeText
import cn.edu.whu.libseat.data.local.UserSettings
import cn.edu.whu.libseat.domain.model.HistoryItem
import cn.edu.whu.libseat.ui.components.AppBottomBar
import cn.edu.whu.libseat.ui.components.AppCard
import cn.edu.whu.libseat.ui.components.HDivider
import cn.edu.whu.libseat.ui.components.InfoBanner
import cn.edu.whu.libseat.ui.components.Pill
import cn.edu.whu.libseat.ui.components.SectionHeader
import cn.edu.whu.libseat.ui.simpleViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    app: LibrarySeatApp,
    onGoHome: () -> Unit,
    onGoFavorites: () -> Unit,
    onLogin: () -> Unit,
    onOpenLogs: () -> Unit = {},
) {
    val vm: SettingsViewModel = viewModel(
        factory = simpleViewModelFactory { SettingsViewModel(app.container.repository) }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showBuildings by remember { mutableStateOf(false) }
    var showWindow by remember { mutableStateOf(false) }
    var showBaseUrl by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showLogout by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("设置", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "账号 · 默认图书馆 · 预约偏好",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            AppBottomBar(currentRoute = "settings") { route ->
                when (route) {
                    "home" -> onGoHome()
                    "favorites" -> onGoFavorites()
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                AccountCard(
                    settings = state.settings,
                    displayName = state.profile?.name ?: state.settings.displayName,
                    violations = state.profile?.violationCount ?: 0,
                    onLogin = onLogin,
                    onLogout = { showLogout = true },
                )
            }

            item {
                SectionHeader(title = "预约偏好", subtitle = "首页与一键预约会默认使用这些设置")
            }

            item {
                AppCard(padding = 0.dp) {
                    Column {
                        SettingRow(
                            icon = Icons.Rounded.School,
                            title = "默认图书馆",
                            subtitle = state.defaultBuildingName,
                            onClick = { showBuildings = true },
                        )
                        HDivider(Modifier.padding(horizontal = 16.dp))
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.MenuBook,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("默认楼层", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "首页打开时优先显示",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            if (state.floors.isEmpty()) {
                                Text(
                                    "请先选择默认图书馆",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    state.floors.forEach { floor ->
                                        val selected = state.settings.defaultFloor == floor
                                        Surface(
                                            onClick = { vm.setDefaultFloor(floor) },
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                        ) {
                                            Text(
                                                "${floor}F",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        HDivider(Modifier.padding(horizontal = 16.dp))
                        SettingRow(
                            icon = Icons.Rounded.AccessTime,
                            title = "默认预约时间段",
                            subtitle = "${TimeText.minutesToHhmm(state.settings.defaultStart)} - " +
                                TimeText.minutesToHhmm(state.settings.defaultEnd),
                            onClick = { showWindow = true },
                        )
                        HDivider(Modifier.padding(horizontal = 16.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.EventSeat,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("失败自动换附近座位", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "与 Ham 的快速预约策略一致",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = state.settings.fallbackEnabled,
                                onCheckedChange = vm::setFallback,
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader(title = "数据与调试")
            }

            item {
                AppCard(padding = 0.dp) {
                    Column {
                        SettingRow(
                            icon = Icons.Rounded.Refresh,
                            title = "更新基础数据",
                            subtitle = if (state.refreshing) "正在从座位系统拉取…" else "图书馆、房间与可预约日期",
                            onClick = { if (!state.refreshing) vm.refreshCatalog() },
                            trailing = {
                                if (state.refreshing) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        Icons.Rounded.ChevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                        )
                        HDivider(Modifier.padding(horizontal = 16.dp))
                        SettingRow(
                            icon = Icons.Rounded.DeleteSweep,
                            title = "清除本地基础数据",
                            subtitle = "下次进入首页会重新拉取",
                            onClick = vm::clearCache,
                        )
                        HDivider(Modifier.padding(horizontal = 16.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("演示模式", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "使用本地模拟的武大图书馆数据",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = state.settings.demoMode,
                                onCheckedChange = vm::setDemoMode,
                            )
                        }
                        HDivider(Modifier.padding(horizontal = 16.dp))
                        SettingRow(
                            icon = Icons.Rounded.Storage,
                            title = "服务器地址",
                            subtitle = state.settings.baseUrl,
                            onClick = { showBaseUrl = true },
                        )
                    }
                }
            }

            if (state.history.isNotEmpty()) {
                item {
                    SectionHeader(title = "预约记录", subtitle = "最近 5 条")
                }
                item {
                    AppCard(padding = 0.dp) {
                        Column {
                            state.history.take(5).forEachIndexed { index, item ->
                                if (index > 0) HDivider(Modifier.padding(horizontal = 16.dp))
                                HistoryRow(item)
                            }
                        }
                    }
                }
            }

            item {
                AppCard(onClick = onOpenLogs) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.BugReport,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("运行日志", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "验证码 / 接口排查记录，可复制或分享",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                AppCard(onClick = { showAbout = true }) {
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
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("关于珞珈座位", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "v1.0.0 · 复用了 Ham 的预约思路与接口",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                InfoBanner(
                    icon = Icons.Rounded.Info,
                    text = "本应用不是图书馆官方客户端。请遵守座位系统规则，避免高频请求造成账号受限。",
                )
            }
        }
    }

    if (showBuildings) {
        val catalog = state.catalog
        AlertDialog(
            onDismissRequest = { showBuildings = false },
            title = { Text("默认图书馆") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    catalog?.buildings?.forEach { building ->
                        val selected = state.settings.defaultBuildingId == building.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    vm.setDefaultBuilding(building.id)
                                    showBuildings = false
                                }
                                .padding(vertical = 11.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(building.name, modifier = Modifier.weight(1f))
                            if (selected) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBuildings = false }) { Text("完成") }
            },
        )
    }

    if (showWindow) {
        DefaultWindowDialog(
            initialStart = state.settings.defaultStart,
            initialEnd = state.settings.defaultEnd,
            onDismiss = { showWindow = false },
            onConfirm = { start, end ->
                vm.setDefaultWindow(start, end)
                showWindow = false
            },
        )
    }

    if (showBaseUrl) {
        BaseUrlDialog(
            initial = state.settings.baseUrl,
            onDismiss = { showBaseUrl = false },
            onConfirm = {
                vm.setBaseUrl(it)
                showBaseUrl = false
            },
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("关于珞珈座位") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text("v1.0.0 · 武汉大学图书馆座位预约", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "复用自 Ham（whu-ham.github.io）图书馆模块的实现思路：\n" +
                            "· 看板预约：按图书馆 / 楼层 / 区域读取座位图并直接预约\n" +
                            "· 快速预约：首选座位 + 附近座位回退\n" +
                            "· 基础数据本地缓存、预约状态与取消\n" +
                            "· WebView 打开官方登录页，手动完成图片验证码 / 统一身份认证\n\n" +
                            "本次新增：\n" +
                            "· 首页即预约入口，默认图书馆与默认楼层\n" +
                            "· 常用座位收藏与一键预约\n" +
                            "· 原生 Android（Compose）实现、演示数据源、服务器地址可配置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("关闭") }
            },
        )
    }

    if (showLogout) {
        AlertDialog(
            onDismissRequest = { showLogout = false },
            title = { Text("退出登录？") },
            text = { Text("常用座位与默认设置会保留，预约相关数据需要重新登录后查看。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.logout()
                    showLogout = false
                }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { showLogout = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AccountCard(
    settings: UserSettings,
    displayName: String?,
    violations: Int,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(
                        if (settings.webLoggedIn || !settings.demoMode) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.tertiaryContainer
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = if (settings.webLoggedIn || !settings.demoMode) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            settings.webLoggedIn -> displayName?.takeIf { it.isNotBlank() } ?: "已登录"
                            settings.demoMode -> "演示模式"
                            settings.isLoggedIn -> displayName?.ifBlank { "已登录" } ?: "已登录"
                            else -> "未登录"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.width(6.dp))
                    if (settings.webLoggedIn) {
                        Pill("Web 登录")
                    } else if (settings.demoMode) {
                        Pill(
                            "本地数据",
                            background = MaterialTheme.colorScheme.tertiaryContainer,
                            foreground = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    } else if (settings.isLoggedIn) {
                        Pill("已连接")
                    }
                }
                Text(
                    when {
                        settings.webLoggedIn && settings.demoMode ->
                            "${settings.username.ifBlank { "信息门户会话" }} · 数据源：演示库"
                        settings.webLoggedIn -> "${settings.username} · 信息门户 Web 会话"
                        settings.demoMode -> "登录后即可预约真实座位"
                        settings.isLoggedIn -> "${settings.username} · 违约 $violations 次"
                        else -> "用 WebView 打开信息门户登录"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (settings.webLoggedIn || (settings.isLoggedIn && !settings.demoMode)) {
                IconButton(onClick = onLogout) {
                    Icon(Icons.Rounded.Logout, contentDescription = "退出登录")
                }
            } else {
                TextButton(onClick = onLogin) {
                    Icon(Icons.Rounded.Login, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("登录")
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.location.ifBlank { "图书馆座位" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                "${item.date}  ${item.begin}-${item.end}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val (label, color) = when (item.status.uppercase()) {
            "COMPLETE" -> "已完成" to MaterialTheme.colorScheme.primary
            "MISS" -> "违约" to MaterialTheme.colorScheme.error
            "CANCEL" -> "已取消" to MaterialTheme.colorScheme.onSurfaceVariant
            else -> item.status to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun DefaultWindowDialog(
    initialStart: Int,
    initialEnd: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    var start by remember { mutableStateOf(initialStart) }
    var end by remember { mutableStateOf(initialEnd) }
    val slots = TimeText.buildDaySlots(7 * 60, 22 * 60)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("默认预约时间段") },
        text = {
            Column {
                Text(
                    "一键预约会按这个时间段下单",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                WindowSelector("开始", TimeText.minutesToHhmm(start), slots) { start = it }
                Spacer(Modifier.height(10.dp))
                WindowSelector("结束", TimeText.minutesToHhmm(end), slots) { end = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(start, end) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun WindowSelector(
    label: String,
    value: String,
    slots: List<cn.edu.whu.libseat.domain.model.TimeSlot>,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(40.dp),
                )
                Text(value, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 300.dp),
        ) {
            slots.forEach { slot ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(slot.label) },
                    onClick = {
                        expanded = false
                        onSelect(slot.minutes)
                    },
                )
            }
        }
    }
}

@Composable
private fun BaseUrlDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("座位系统地址") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text("Base URL") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "默认 https://seat.lib.whu.edu.cn，仅在系统迁移或调试时修改。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
