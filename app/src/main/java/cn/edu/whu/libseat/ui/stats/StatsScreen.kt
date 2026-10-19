package cn.edu.whu.libseat.ui.stats

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.whu.libseat.LibrarySeatApp
import cn.edu.whu.libseat.core.log.AppLog
import cn.edu.whu.libseat.data.repository.LibraryRepository
import cn.edu.whu.libseat.domain.model.HistoryItem
import cn.edu.whu.libseat.domain.model.UserProfile
import cn.edu.whu.libseat.ui.components.AppCard
import cn.edu.whu.libseat.ui.components.EmptyState
import cn.edu.whu.libseat.ui.simpleViewModelFactory
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------------- 数据模型与统计计算 ----------------

data class DurationPoint(val label: String, val minutes: Double)

data class SeatUsageStat(
    val seatId: Long,
    val label: String,
    val count: Int,
    val minutes: Int,
)

data class StatsSummary(
    val total: Int = 0,
    val done: Int = 0,
    val early: Int = 0,
    val cancel: Int = 0,
    val totalMinutes: Int = 0,
    val weekly: List<DurationPoint> = emptyList(),
    val monthly: List<DurationPoint> = emptyList(),
    val yearly: List<DurationPoint> = emptyList(),
    val seats: List<SeatUsageStat> = emptyList(),
    val rangeText: String = "",
)

private val STATUS_DONE = setOf("STOP", "IN_USE", "CHECK_IN", "DONE")

fun computeStats(items: List<HistoryItem>): StatsSummary {
    val recs = items.mapNotNull { item ->
        val d = runCatching { LocalDate.parse(item.date) }.getOrNull() ?: return@mapNotNull null
        Triple(d, item, item.useMinute)
    }.sortedBy { it.first }

    val totalMinutes = recs.sumOf { it.third }
    val done = recs.count { it.second.status in STATUS_DONE }
    val early = recs.count { it.second.status == "LEAVE_EARLY" }
    val cancel = recs.count { it.second.status == "CANCEL" }

    // 周聚合：最近 10 周（周一起算，含当前周）
    val weekly = run {
        val thisWeekStart = weekStart(LocalDate.now())
        val byWeek = recs.groupBy { weekStart(it.first) }
            .mapValues { e -> e.value.sumOf { it.third.toDouble() } }
        (9 downTo 0).map { back ->
            val ws = thisWeekStart.minus(back.toLong(), ChronoUnit.WEEKS)
            DurationPoint(label = "${ws.monthValue}/${ws.dayOfMonth}", minutes = byWeek[ws] ?: 0.0)
        }
    }

    // 月聚合：最近 12 个月
    val monthly = run {
        val now = LocalDate.now()
        val byMonth = recs.groupBy { it.first.year * 100 + it.first.monthValue }
            .mapValues { e -> e.value.sumOf { it.third.toDouble() } }
        (11 downTo 0).map { back ->
            val m = now.minusMonths(back.toLong())
            val key = m.year * 100 + m.monthValue
            DurationPoint(label = "${m.monthValue}月", minutes = byMonth[key] ?: 0.0)
        }
    }

    // 年聚合：全部年份
    val yearly = recs.groupBy { it.first.year }
        .toSortedMap()
        .map { (year, recs) ->
            DurationPoint(label = "$year", minutes = recs.sumOf { it.third.toDouble() })
        }

    // 座位聚合
    val seats = recs.groupBy { rec ->
        val it = rec.second
        if (it.seatId != 0L) "id-${it.seatId}" else "loc-${it.location}-${it.begin}"
    }.map { (_, recs) ->
        val first = recs.first().second
        SeatUsageStat(
            seatId = first.seatId,
            label = buildString {
                append(first.roomName.ifBlank { first.location.substringAfterLast('|') })
                if (first.seatLabel.isNotBlank()) append(" · ").append(first.seatLabel).append(" 号")
            },
            count = recs.size,
            minutes = recs.sumOf { it.second.useMinute },
        )
    }.sortedWith(compareByDescending<SeatUsageStat> { it.minutes }.thenByDescending { it.count })

    val rangeText = if (recs.isEmpty()) "" else "${recs.first().first} ~ ${recs.last().first}"

    return StatsSummary(
        total = recs.size,
        done = done,
        early = early,
        cancel = cancel,
        totalMinutes = totalMinutes,
        weekly = weekly,
        monthly = monthly,
        yearly = yearly,
        seats = seats,
        rangeText = rangeText,
    )
}

private fun weekStart(date: LocalDate): LocalDate =
    date.minus(((date.dayOfWeek.value + 6) % 7).toLong(), ChronoUnit.DAYS)

fun formatMinutes(minutes: Int): String =
    if (minutes >= 60) String.format("%.1f 小时", minutes / 60.0) else "$minutes 分钟"

// ---------------- ViewModel ----------------

data class StatsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val summary: StatsSummary = StatsSummary(),
    val recordCount: Int = 0,
    val profile: UserProfile? = null,
)

class StatsViewModel(private val repo: LibraryRepository) : ViewModel() {

    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val items = repo.historyAll(forceRefresh)
                val profile = runCatching { repo.profile() }.getOrNull()
                _state.update {
                    it.copy(
                        loading = false,
                        summary = computeStats(items),
                        recordCount = items.size,
                        profile = profile,
                    )
                }
            } catch (e: Exception) {
                AppLog.e("Stats", "统计加载失败", e)
                _state.update { it.copy(loading = false, error = e.message ?: "统计加载失败") }
            }
        }
    }
}

// ---------------- UI ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(app: LibrarySeatApp, onBack: () -> Unit) {
    val vm: StatsViewModel = viewModel(
        factory = simpleViewModelFactory { StatsViewModel(app.container.repository) },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("预约统计") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.load(true) }) {
                        Icon(Icons.Rounded.Insights, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(
                    modifier = Modifier.size(28.dp).align(Alignment.Center),
                    strokeWidth = 3.dp,
                )

                state.error != null -> EmptyState(
                    title = "统计加载失败",
                    description = state.error.orEmpty(),
                    actionLabel = "重试",
                    onAction = { vm.load(true) },
                )

                else -> StatsContent(state)
            }
        }
    }
}

@Composable
private fun StatsContent(state: StatsUiState) {
    val s = state.summary
    val profile = state.profile
    var chartMode by remember { mutableIntStateOf(0) } // 0=周 1=月 2=年

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AppCard(Modifier.padding(horizontal = 20.dp)) {
                Column {
                    if (profile != null && profile.totalMake > 0) {
                        Row(Modifier.fillMaxWidth()) {
                            StatBlock("累计预约（官方）", "${profile.totalMake} 次", Modifier.weight(1f))
                            StatBlock(
                                "累计使用（官方）",
                                formatMinutes(profile.totalMinute),
                                Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        StatBlock("记录预约次数", "${s.total} 次", Modifier.weight(1f))
                        StatBlock("记录使用时长", formatMinutes(s.totalMinutes), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "完成 ${s.done} 次 · 早退 ${s.early} 次 · 取消 ${s.cancel} 次",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (s.rangeText.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "记录范围：${s.rangeText}（座位系统官方预约记录）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }

        item {
            AppCard(Modifier.padding(horizontal = 20.dp)) {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChartModeButton("每周", chartMode == 0) { chartMode = 0 }
                        ChartModeButton("每月", chartMode == 1) { chartMode = 1 }
                        ChartModeButton("每年", chartMode == 2) { chartMode = 2 }
                    }
                    Spacer(Modifier.height(12.dp))
                    val points = when (chartMode) {
                        0 -> s.weekly
                        1 -> s.monthly
                        else -> s.yearly
                    }
                    val title = when (chartMode) {
                        0 -> "每周预约总时长"
                        1 -> "每月预约总时长"
                        else -> "每年预约总时长"
                    }
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(10.dp))
                    if (points.isEmpty() || points.all { it.minutes <= 0.0 }) {
                        Text(
                            "暂无数据",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 30.dp),
                        )
                    } else {
                        SimpleLineChart(points)
                    }
                }
            }
        }

        item {
            AppCard(Modifier.padding(horizontal = 20.dp)) {
                Column {
                    Text("常用座位统计", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(10.dp))
                    val top = s.seats.take(8)
                    val maxMinutes = top.maxOfOrNull { it.minutes }?.coerceAtLeast(1) ?: 1
                    if (top.isEmpty()) {
                        Text(
                            "暂无数据",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        top.forEach { seat ->
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Row(Modifier.fillMaxWidth()) {
                                    Text(
                                        seat.label.ifBlank { "座位" },
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "${seat.count} 次 · ${formatMinutes(seat.minutes)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth((seat.minutes.toFloat() / maxMinutes).coerceIn(0.05f, 1f))
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(MaterialTheme.colorScheme.primary),
                                    )
                                }
                            }
                        }
                        if (s.seats.size > top.size) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "仅展示前 ${top.size} 个座位，共 ${s.seats.size} 个",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                "统计基于座位系统官方预约记录生成",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            )
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ChartModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun SimpleLineChart(points: List<DurationPoint>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val maxHours = ceil((points.maxOfOrNull { it.minutes } ?: 0.0) / 60.0).coerceAtLeast(1.0)

    Column {
        Text(
            "峰值 %.1f 小时".format(maxHours),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .padding(horizontal = 4.dp),
        ) {
            val w = size.width
            val h = size.height
            drawLine(gridColor, Offset(0f, h - 1), Offset(w, h - 1), 1.5f)
            val n = points.size
            if (n < 2) return@Canvas
            val stepX = w / (n - 1)
            val coords = points.mapIndexed { i, p ->
                val v = (p.minutes / 60.0 / maxHours).coerceIn(0.0, 1.0)
                Offset(i * stepX, (h * (1 - v)).toFloat())
            }
            val path = Path().apply {
                moveTo(coords.first().x, coords.first().y)
                coords.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, lineColor, style = Stroke(width = 5f, cap = StrokeCap.Round))
            coords.forEach { drawCircle(lineColor, radius = 6f, center = it) }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            points.forEachIndexed { i, p ->
                val show = points.size <= 8 || i % 2 == 0 || i == points.lastIndex
                Text(
                    if (show) p.label else "",
                    fontSize = 8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
