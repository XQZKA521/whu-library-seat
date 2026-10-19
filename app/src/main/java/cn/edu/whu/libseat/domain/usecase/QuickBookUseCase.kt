package cn.edu.whu.libseat.domain.usecase

import cn.edu.whu.libseat.core.time.TimeText
import cn.edu.whu.libseat.data.remote.ApiException
import cn.edu.whu.libseat.data.remote.SessionExpiredException
import cn.edu.whu.libseat.data.repository.LibraryRepository
import cn.edu.whu.libseat.domain.model.FavoriteSeat
import cn.edu.whu.libseat.domain.model.QuickBookResult
import cn.edu.whu.libseat.domain.model.Seat
import cn.edu.whu.libseat.domain.model.SeatStatus
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs

/**
 * “快速预约 / 一键预约”核心逻辑，沿用 Ham 的既有策略：
 *
 * 1. 优先预约常用座位；
 * 2. 常用座位被占用或预约失败时，立刻在同一区域里挑最近的空座重试；
 * 3. 每次尝试之间保持间隔，避免高频打接口触发风控。
 *
 * 与 Ham 的差异：Ham 的看板预约不预检余量，这里在快速预约路径上会先读一次
 * layout（本来就要读，用于找附近空座），失败后仍然保留“换座重试”。
 */
class QuickBookUseCase(
    private val repo: LibraryRepository,
) {

    suspend fun book(
        favorite: FavoriteSeat,
        date: LocalDate,
        start: Int,
        end: Int,
        fallbackEnabled: Boolean,
    ): QuickBookResult {
        val settings = repo.currentSettings()
        val dateText = TimeText.iso(date)

        // 次日预约在 22:45 之后才开放（图书馆规则）；演示模式不限制
        if (!settings.demoMode && date == TimeText.tomorrow() &&
            LocalTime.now().isBefore(LocalTime.of(22, 45))
        ) {
            return QuickBookResult.Failure(
                requestedSeatName = favorite.seatName,
                message = "次日座位 22:45 起开放预约",
                attempts = 0,
            )
        }

        var effectiveStart = start
        var startNow = false
        if (date == TimeText.today()) {
            // 官方允许从当前精确时间开始（不必半小时取整）；
            // 所选开始时间已过或就是现在时，按官方协议传 -1（服务端取当前时间）
            val now = TimeText.nowMinutes()
            if (effectiveStart <= now) {
                startNow = true
                effectiveStart = now
            }
        }
        if (end <= effectiveStart) {
            return QuickBookResult.Failure(
                requestedSeatName = favorite.seatName,
                message = "所选时间段无效或已过，请重新选择",
                attempts = 0,
            )
        }

        // 已有进行中的预约时直接失败：这种情况换多少座位、过多少次验证码都没用
        val currentReservation = try {
            repo.reservations().firstOrNull()
        } catch (e: SessionExpiredException) {
            return QuickBookResult.NotLoggedIn(favorite.seatName)
        } catch (e: Exception) {
            null
        }
        currentReservation?.let { r ->
            return QuickBookResult.Failure(
                requestedSeatName = favorite.seatName,
                message = "你已有进行中的预约：${r.location.ifBlank { "座位预约" }} " +
                    "${r.date} ${r.begin}-${r.end}，请先取消再预约",
                attempts = 0,
            )
        }

        val map = try {
            repo.seatMap(favorite.roomId, dateText)
        } catch (e: SessionExpiredException) {
            return QuickBookResult.NotLoggedIn(favorite.seatName)
        } catch (e: Exception) {
            return QuickBookResult.Failure(
                requestedSeatName = favorite.seatName,
                message = e.message ?: "座位数据加载失败",
                attempts = 0,
            )
        }

        val target = map.seatById(favorite.seatId)
        if (target == null) {
            // 换了图书馆数据源（例如从演示库切到真实库）后，旧收藏的座位 id 可能不存在，
            // 此时不做“附近座位”回退，避免在用户不知情的情况下预约到别的座位。
            return QuickBookResult.Failure(
                requestedSeatName = favorite.seatName,
                message = "该常用座位在当前图书馆数据中不存在，请重新收藏",
                attempts = 0,
            )
        }
        val candidates = buildCandidates(map.seats, target, fallbackEnabled)
        if (candidates.isEmpty()) {
            return QuickBookResult.Failure(
                requestedSeatName = favorite.seatName,
                message = "该区域当前没有可预约的空座",
                attempts = 0,
            )
        }

        // 整个候选序列先过一次滑块验证；若服务端判定票据单次使用，
        // 预约报“验证码错误”时会重新弹验证码（同一座位重试一次）
        val hint = "快速预约自动换座：请完成滑块验证后继续预约"
        var capToken: String? = if (settings.demoMode) {
            "demo"
        } else {
            try {
                repo.acquireCapToken("快速预约：完成滑块验证后将自动尝试预约")
            } catch (e: SessionExpiredException) {
                return QuickBookResult.NotLoggedIn(favorite.seatName)
            } catch (e: Exception) {
                null
            }
        }
        if (!settings.demoMode && capToken == null) {
            return QuickBookResult.Failure(
                requestedSeatName = favorite.seatName,
                message = "安全验证未完成，预约未提交",
                attempts = 0,
            )
        }

        var lastError = "预约失败，请稍后再试"
        candidates.forEachIndexed { index, seat ->
            if (index > 0) delay(350)
            var attemptToken = capToken
            var captchaRetried = false
            var seatTaken = false
            while (true) {
                try {
                    val startMinute = if (startNow) -1 else effectiveStart
                    val reservation = repo.book(seat.id, dateText, startMinute, end, attemptToken)
                    return QuickBookResult.Success(
                        reservation = reservation,
                        seat = seat,
                        usedFallback = seat.id != favorite.seatId,
                        requestedSeatName = favorite.seatName,
                    )
                } catch (e: SessionExpiredException) {
                    return QuickBookResult.NotLoggedIn(favorite.seatName)
                } catch (e: ApiException) {
                    lastError = e.message ?: lastError
                    if (lastError.contains("验证码") && !captchaRetried) {
                        // 服务端判定验证票据失效：重新弹一次滑块，同一座位重试
                        captchaRetried = true
                        capToken = try {
                            repo.acquireCapToken("自动换座：请重新完成滑块验证后继续预约")
                        } catch (e: SessionExpiredException) {
                            return QuickBookResult.NotLoggedIn(favorite.seatName)
                        } catch (e: Exception) {
                            null
                        } ?: return QuickBookResult.Failure(
                            requestedSeatName = favorite.seatName,
                            message = "安全验证未完成，预约未提交",
                            attempts = index + 1,
                        )
                        attemptToken = capToken
                    } else {
                        // 自动换座：当前座位不可用，尝试下一个候选座位。
                        // 换座会再次弹出滑块验证，属于预期行为（验证弹窗里有文字提示）
                        seatTaken = true
                    }
                } catch (e: Exception) {
                    return QuickBookResult.Failure(
                        requestedSeatName = favorite.seatName,
                        message = e.message ?: "网络异常，请检查网络后重试",
                        attempts = index + 1,
                    )
                }
                if (!seatTaken) continue
                seatTaken = false
                break // 座位被占：换下一个候选座位
            }
        }

        return QuickBookResult.Failure(
            requestedSeatName = favorite.seatName,
            message = lastError,
            attempts = candidates.size,
        )
    }

    /**
     * 候选座位顺序：目标座位（若空闲）→ 同区域距离最近的空座。
     * 回退开关关闭时只尝试目标座位。
     */
    private fun buildCandidates(
        seats: List<Seat>,
        target: Seat?,
        fallbackEnabled: Boolean,
    ): List<Seat> {
        if (!fallbackEnabled) {
            return listOfNotNull(target?.takeIf { it.status == SeatStatus.FREE })
        }
        val free = seats.filter { it.status == SeatStatus.FREE }
        if (free.isEmpty()) return emptyList()
        val ordered = LinkedHashMap<Long, Seat>()
        if (target?.status == SeatStatus.FREE) ordered[target.id] = target

        val anchor = target ?: free.first()
        free.asSequence()
            .filter { it.id != target?.id }
            .sortedWith(
                compareBy<Seat> { distance(anchor, it) }
                    .thenBy { it.id }
            )
            .take(MAX_ATTEMPTS - ordered.size)
            .forEach { ordered[it.id] = it }

        if (ordered.isEmpty()) {
            free.take(MAX_ATTEMPTS).forEach { ordered[it.id] = it }
        }
        return ordered.values.toList()
    }

    private fun distance(a: Seat, b: Seat): Int =
        abs(a.row - b.row) * 2 + abs(a.col - b.col)


    private companion object {
        const val MAX_ATTEMPTS = 4
    }
}
