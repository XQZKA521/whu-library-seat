package cn.edu.whu.libseat.domain.model

import kotlinx.serialization.Serializable

/** 图书馆（对应座位系统 filters.buildings）。新版 ID 为 19 位雪花号，必须用 Long。 */
@Serializable
data class Building(
    val id: Long,
    val name: String,
    val floorCount: Int = 0,
)

/** 阅览室 / 区域（对应座位系统 filters.rooms）。 */
@Serializable
data class RoomInfo(
    val id: Long,
    val name: String,
    val buildingId: Long,
    val floor: Int,
)

/** 楼层（新版 /jsq 的 floorId 与展示名）。 */
@Serializable
data class FloorInfo(
    val id: Long,
    val buildingId: Long,
    val name: String,
    val number: Int,
)

/** 房间实时概览（对应 /rest/v2/room/stats2/{buildingId}）。 */
data class RoomStats(
    val room: RoomInfo,
    val free: Int,
    val total: Int,
    val inUse: Int = 0,
    val away: Int = 0,
    val reserved: Int = 0,
    val countsKnown: Boolean = true,
) {
    val occupied: Int get() = (total - free).coerceAtLeast(0)
    val freeRatio: Float get() = if (total <= 0) 0f else free.toFloat() / total
}

enum class SeatStatus {
    FREE,
    IN_USE,
    AWAY,
    RESERVED,
    UNKNOWN,
}

/** 座位。新版 ID 为雪花号；row / col 为渲染网格坐标。 */
data class Seat(
    val id: Long,
    val name: String,
    val row: Int,
    val col: Int,
    val status: SeatStatus,
    val nearWindow: Boolean = false,
    val hasPower: Boolean = false,
)

data class SeatMap(
    val roomId: Long,
    val roomName: String,
    val rows: Int,
    val cols: Int,
    val seats: List<Seat>,
) {
    private val byId: Map<Long, Seat> = seats.associateBy { it.id }
    private val byCell: Map<Pair<Int, Int>, Seat> = seats.associateBy { it.row to it.col }

    fun seatById(id: Long): Seat? = byId[id]
    fun seatAt(row: Int, col: Int): Seat? = byCell[row to col]
    val freeSeats: List<Seat> get() = seats.filter { it.status == SeatStatus.FREE }
    val freeCount: Int get() = seats.count { it.status == SeatStatus.FREE }
}

/** 当前预约 / 预约结果。 */
data class Reservation(
    val id: Long,
    val seatId: Long = 0L,
    val location: String,
    val date: String,
    val begin: String,
    val end: String,
    val status: String = "RESERVE",
    val receipt: String = "",
    val message: String = "",
)

data class HistoryItem(
    val id: Long,
    val date: String,
    val begin: String,
    val end: String,
    val location: String,
    val status: String,
    val seatId: Long = 0L,
    val seatLabel: String = "",
    val roomName: String = "",
    val useMinute: Int = 0,
)

data class TimeSlot(
    val minutes: Int,
    val label: String,
    /** 官方“现在”选项：预约时按协议传 startMinute=-1，由服务端取当前时间。 */
    val isNow: Boolean = false,
)

data class UserProfile(
    val name: String,
    val username: String,
    val violationCount: Int = 0,
    val checkedIn: Boolean = false,
    val totalMake: Int = 0,
    val totalMinute: Int = 0,
)

/** 常用座位：保存后可在首页一键预约。 */
@Serializable
data class FavoriteSeat(
    val seatId: Long,
    val seatName: String,
    val roomId: Long,
    val roomName: String,
    val buildingId: Long,
    val buildingName: String,
    val floor: Int,
    val nearWindow: Boolean = false,
    val hasPower: Boolean = false,
    val createdAt: Long = 0L,
)

/** 快速预约的结果，包含是否启用了“附近座位回退”。 */
sealed interface QuickBookResult {
    data class Success(
        val reservation: Reservation,
        val seat: Seat,
        val usedFallback: Boolean,
        val requestedSeatName: String,
    ) : QuickBookResult

    data class Failure(
        val message: String,
        val requestedSeatName: String,
        val attempts: Int,
    ) : QuickBookResult

    data class NotLoggedIn(val requestedSeatName: String) : QuickBookResult
}
