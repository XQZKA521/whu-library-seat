package cn.edu.whu.libseat.data.remote

import cn.edu.whu.libseat.domain.model.Building
import cn.edu.whu.libseat.domain.model.FloorInfo
import cn.edu.whu.libseat.domain.model.HistoryItem
import cn.edu.whu.libseat.domain.model.Reservation
import cn.edu.whu.libseat.domain.model.RoomInfo
import cn.edu.whu.libseat.domain.model.RoomStats
import cn.edu.whu.libseat.domain.model.SeatMap
import cn.edu.whu.libseat.domain.model.TimeSlot
import cn.edu.whu.libseat.domain.model.UserProfile

class ApiException(
    val code: String?,
    override val message: String,
) : Exception(message)

class SessionExpiredException(message: String = "登录状态已失效，请重新登录") : Exception(message)

data class CatalogData(
    val buildings: List<Building>,
    val rooms: List<RoomInfo>,
    val dates: List<String> = emptyList(),
    val floors: List<FloorInfo> = emptyList(),
)

/**
 * 与图书馆座位系统交互的最小契约。
 *
 * 这个契约完全来自 Ham 文档站与其所对接的座位系统（seat.lib.whu.edu.cn）的公开行为：
 * - 登录拿 token
 * - filters 拿图书馆 / 房间 / 可预约日期
 * - stats2 拿房间余量
 * - layoutByDate 拿座位图
 * - freeBook / cancel / reservations / history 完成预约闭环
 *
 * DemoLibraryApi 实现同一契约，便于在没有真实账号时调试 UI 与预约链路。
 */
interface LibraryApi {
    suspend fun login(username: String, password: String): String

    suspend fun catalog(token: String): CatalogData

    suspend fun roomStats(token: String, buildingId: Long): List<RoomStats>

    suspend fun seatMap(token: String, roomId: Long, date: String): SeatMap

    suspend fun book(token: String, seatId: Long, date: String, start: Int, end: Int): Reservation

    suspend fun reservations(token: String): List<Reservation>

    suspend fun cancel(token: String, reservationId: Long)

    suspend fun history(token: String): List<HistoryItem>

    suspend fun startTimes(token: String, seatId: Long, date: String): List<TimeSlot>

    suspend fun endTimes(token: String, seatId: Long, date: String, start: Int): List<TimeSlot>

    suspend fun user(token: String): UserProfile
}
