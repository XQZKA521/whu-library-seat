package cn.edu.whu.libseat.data.remote

import cn.edu.whu.libseat.core.time.TimeText
import cn.edu.whu.libseat.domain.model.Building
import cn.edu.whu.libseat.domain.model.HistoryItem
import cn.edu.whu.libseat.domain.model.Reservation
import cn.edu.whu.libseat.domain.model.RoomInfo
import cn.edu.whu.libseat.domain.model.RoomStats
import cn.edu.whu.libseat.domain.model.Seat
import cn.edu.whu.libseat.domain.model.SeatMap
import cn.edu.whu.libseat.domain.model.SeatStatus
import cn.edu.whu.libseat.domain.model.TimeSlot
import cn.edu.whu.libseat.domain.model.UserProfile
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * 离线演示数据源：实现与真实接口完全相同的契约，
 * 用于没有图书馆账号时验证首页、座位图、一键预约与回退逻辑。
 */
class DemoLibraryApi : LibraryApi {

    private data class RoomDef(
        val id: Long,
        val name: String,
        val buildingId: Long,
        val floor: Int,
        val rows: Int,
        val cols: Int,
    )

    private val buildings = listOf(
        Building(1L, "总馆", 4),
        Building(2L, "工学分馆", 3),
        Building(3L, "信息科学分馆", 3),
        Building(4L, "医学分馆", 3),
    )

    private val rooms = listOf(
        RoomDef(101L, "2F 北厅", 1L, 2, 6, 12),
        RoomDef(102L, "2F 南厅", 1L, 2, 5, 10),
        RoomDef(201L, "3F 北区", 1L, 3, 7, 14),
        RoomDef(202L, "3F 南区", 1L, 3, 7, 12),
        RoomDef(203L, "3F 西区", 1L, 3, 5, 10),
        RoomDef(301L, "4F 北区", 1L, 4, 8, 14),
        RoomDef(302L, "4F 南1区", 1L, 4, 7, 12),
        RoomDef(303L, "4F 南2区", 1L, 4, 5, 10),
        RoomDef(304L, "4F 西北区", 1L, 4, 6, 10),
        RoomDef(401L, "5F 西区", 1L, 5, 5, 10),
        RoomDef(402L, "5F 东区", 1L, 5, 5, 10),
        RoomDef(501L, "2F 自习区", 2L, 2, 6, 10),
        RoomDef(502L, "3F 阅览区", 2L, 3, 7, 12),
        RoomDef(503L, "4F 研习区", 2L, 4, 5, 10),
        RoomDef(601L, "2F 东区", 3L, 2, 6, 12),
        RoomDef(602L, "2F 西区", 3L, 2, 6, 10),
        RoomDef(603L, "3F 阅览区", 3L, 3, 7, 12),
        RoomDef(701L, "2F 中文区", 4L, 2, 6, 12),
        RoomDef(702L, "3F 外文区", 4L, 3, 5, 10),
    )

    private val activeReservations = mutableListOf<Reservation>()
    private val demoHistory = mutableListOf<HistoryItem>()
    private val bookedSeatKeys = mutableSetOf<String>()
    private val nextReservationId = AtomicLong(90001L)

    private val myName = "珞珈同学"
    private val myAccount = "2023302000"

    private fun roomInfo(def: RoomDef) = RoomInfo(def.id, def.name, def.buildingId, def.floor)

    override suspend fun login(username: String, password: String): String {
        if (username.isBlank() || password.isBlank()) {
            throw ApiException("13", "账号或密码不能为空")
        }
        return "DEMO-TOKEN"
    }

    override suspend fun user(token: String): UserProfile = UserProfile(
        name = myName,
        username = myAccount,
        violationCount = 0,
        checkedIn = false,
    )

    override suspend fun catalog(token: String): CatalogData = CatalogData(
        buildings = buildings,
        rooms = rooms.map(::roomInfo),
        dates = listOf(
            TimeText.iso(LocalDate.now()),
            TimeText.iso(LocalDate.now().plusDays(1)),
        ),
    )

    override suspend fun roomStats(token: String, buildingId: Long): List<RoomStats> {
        val date = TimeText.iso(LocalDate.now())
        return rooms.filter { it.buildingId == buildingId }.map { def ->
            val map = buildMap(def, date)
            val total = map.seats.size
            val free = map.seats.count { it.status == SeatStatus.FREE }
            RoomStats(
                room = roomInfo(def),
                free = free,
                total = total,
                inUse = map.seats.count { it.status == SeatStatus.IN_USE },
                away = map.seats.count { it.status == SeatStatus.AWAY },
                reserved = map.seats.count { it.status == SeatStatus.RESERVED },
            )
        }
    }

    override suspend fun seatMap(token: String, roomId: Long, date: String): SeatMap {
        val def = rooms.firstOrNull { it.id == roomId } ?: throw ApiException(null, "区域不存在")
        return buildMap(def, date)
    }

    override suspend fun book(token: String, seatId: Long, date: String, start: Int, end: Int): Reservation {
        if (end <= start) throw ApiException(null, "结束时间需要晚于开始时间")
        if (start < 7 * 60 || end > 22 * 60) throw ApiException(null, "预约时间需在 07:00 - 22:00 之间")

        val def = rooms.firstOrNull { it.id == seatId / 1000 }
            ?: throw ApiException(null, "座位不存在")
        val map = buildMap(def, date)
        val seat = map.seatById(seatId) ?: throw ApiException(null, "座位不存在")
        if (seat.status != SeatStatus.FREE) {
            throw ApiException("14", "该座位已被占用，请选择其他座位")
        }
        val key = "$seatId-$date"
        if (key in bookedSeatKeys) {
            throw ApiException("14", "你已有重叠时段的预约")
        }
        val conflict = activeReservations.any { existing ->
            existing.date == date && overlaps(existing.begin, existing.end, start, end)
        }
        if (conflict) {
            throw ApiException("14", "你在该时段已有预约")
        }
        bookedSeatKeys += key

        val buildingName = buildings.first { it.id == def.buildingId }.name
        val location = "$buildingName ${def.floor}层 ${def.name}，座位号 ${seat.name}"
        val reservation = Reservation(
            id = nextReservationId.getAndIncrement(),
            seatId = seatId,
            location = location,
            date = date,
            begin = TimeText.minutesToHhmm(start),
            end = TimeText.minutesToHhmm(end),
            status = "RESERVE",
            receipt = "D${System.currentTimeMillis() % 100000}",
            message = "请在开始时间前后 30 分钟内到馆刷卡签到",
        )
        activeReservations += reservation
        return reservation
    }

    override suspend fun reservations(token: String): List<Reservation> {
        val today = TimeText.iso(LocalDate.now())
        return activeReservations.filter { it.date >= today }.sortedBy { it.date }
    }

    override suspend fun cancel(token: String, reservationId: Long) {
        val target = activeReservations.firstOrNull { it.id == reservationId }
            ?: throw ApiException(null, "预约不存在或已失效")
        activeReservations.remove(target)
        demoHistory.add(
            HistoryItem(
                id = target.id,
                date = target.date,
                begin = target.begin,
                end = target.end,
                location = target.location,
                status = "CANCEL",
            )
        )
        val seatKey = "${target.seatId}-${target.date}"
        bookedSeatKeys.remove(seatKey)
    }

    private fun overlaps(beginA: String, endA: String, startB: Int, endB: Int): Boolean {
        val startA = TimeText.hhmmToMinutes(beginA) ?: return false
        val finishA = TimeText.hhmmToMinutes(endA) ?: return false
        return startA < endB && startB < finishA
    }

    override suspend fun history(token: String): List<HistoryItem> {
        val seeded = listOf(
            HistoryItem(88001, TimeText.iso(LocalDate.now().minusDays(2)), "09:00", "12:00", "总馆 4层 4F 北区，座位号 052", "COMPLETE"),
            HistoryItem(88002, TimeText.iso(LocalDate.now().minusDays(5)), "14:00", "18:00", "总馆 3层 3F 北区，座位号 018", "COMPLETE"),
            HistoryItem(88003, TimeText.iso(LocalDate.now().minusDays(8)), "08:30", "11:30", "工学分馆 3层 3F 阅览区，座位号 031", "CANCEL"),
            HistoryItem(88004, TimeText.iso(LocalDate.now().minusDays(12)), "13:00", "17:00", "总馆 4层 4F 南1区，座位号 066", "MISS"),
        )
        return (seeded + demoHistory).sortedByDescending { it.date }
    }

    override suspend fun startTimes(token: String, seatId: Long, date: String): List<TimeSlot> {
        return TimeText.buildDaySlots(7 * 60, 21 * 60)
    }

    override suspend fun endTimes(token: String, seatId: Long, date: String, start: Int): List<TimeSlot> {
        val endLimit = minOf(start + 8 * 60, 22 * 60)
        return TimeText.buildDaySlots(start + 30, endLimit)
    }

    // ---------------- 座位图生成 ----------------

    private fun buildMap(def: RoomDef, date: String): SeatMap {
        val seats = mutableListOf<Seat>()
        var number = 0
        for (row in 1..def.rows) {
            for (col in 1..def.cols) {
                // 中间过道与靠墙的柱子不生成座位
                if (col == def.cols / 2 + 1) continue
                if (row == 1 && col == 2) continue
                number++
                val seatId = def.id * 1000L + number
                seats += Seat(
                    id = seatId,
                    name = "%03d".format(number),
                    row = row,
                    col = col,
                    status = statusOf(seatId, date),
                    nearWindow = col == 1 || col == def.cols,
                    hasPower = number % 3 != 0,
                )
            }
        }
        return SeatMap(
            roomId = def.id,
            roomName = def.name,
            rows = def.rows,
            cols = def.cols,
            seats = seats,
        )
    }

    private fun statusOf(seatId: Long, date: String): SeatStatus {
        if ("$seatId-$date" in bookedSeatKeys) return SeatStatus.RESERVED
        val bucket = (abs(seatId * 37 + date.hashCode() * 17) % 100).toInt()
        return when {
            bucket < 9 -> SeatStatus.IN_USE
            bucket < 13 -> SeatStatus.AWAY
            bucket < 16 -> SeatStatus.RESERVED
            else -> SeatStatus.FREE
        }
    }
}
