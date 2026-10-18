package cn.edu.whu.libseat.data.remote

import cn.edu.whu.libseat.core.codec.LzString
import cn.edu.whu.libseat.core.time.TimeText
import cn.edu.whu.libseat.data.local.DEFAULT_BASE_URL
import cn.edu.whu.libseat.domain.model.Building
import cn.edu.whu.libseat.domain.model.FloorInfo
import cn.edu.whu.libseat.domain.model.HistoryItem
import cn.edu.whu.libseat.domain.model.Reservation
import cn.edu.whu.libseat.domain.model.RoomInfo
import cn.edu.whu.libseat.domain.model.RoomStats
import cn.edu.whu.libseat.domain.model.Seat
import cn.edu.whu.libseat.domain.model.SeatMap
import cn.edu.whu.libseat.domain.model.SeatStatus
import cn.edu.whu.libseat.domain.model.TimeSlot
import cn.edu.whu.libseat.domain.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

/**
 * 新版武大座位系统（jsq-pc）数据实现。
 *
 * 登录由 WebView 完成并保存 token，这里只消费 token：
 *  - POST /jsq/static/frontApi/res/buildingFloorDate
 *  - POST /jsq/static/frontApi/res/findRoomDuration/{floorId}/{date}
 *  - POST /jsq/static/frontApi/res/querySeatLayout/{roomId}/{updVersion}
 *  - POST /jsq/static/frontApi/res/freeSeatIdsDuration/{roomId}/{date}
 *  - POST /jsq/static/frontApi/make/freeBook/{seat}/{date}/{start}/{end}?capToken=
 *  - POST /jsq/static/frontApi/user/currentUseMake
 *  - POST /jsq/static/frontApi/make/cancel/{id}
 *  - POST /jsq/static/frontApi/user/history/{page}/{size}
 *
 * 座位图是 LZString(Base64) 压缩的 Fabric.js JSON，座位对象为 labeledImage.seat。
 * 由于不同部署字段略有差异，解析层对常见字段名做了兼容。
 */
class JsqLibraryApi(
    private val client: OkHttpClient,
    private val baseUrlProvider: () -> String,
    private val capTokenProvider: (suspend () -> String?)? = null,
) : LibraryApi {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    @Volatile
    private var floorsCache: List<FloorInfo> = emptyList()

    /** 新版接口开启了 HMAC 校验（systemInfo.hmac = 1），密钥由公开配置下发后 AES 解密。 */
    @Volatile
    private var hmacKey: String? = null

    override suspend fun login(username: String, password: String): String =
        throw UnsupportedOperationException("新版座位系统请在网页中登录")

    override suspend fun catalog(token: String): CatalogData = io {
        loadHmacKey()
        val rawData = post("/static/frontApi/res/buildingFloorDate", token)
        // 少数部署会把 data 再序列化成字符串
        val data = if (rawData is JsonPrimitive) {
            runCatching { json.parseToJsonElement(rawData.content) }.getOrDefault(rawData)
        } else {
            rawData
        }
        val parsed = parseCatalogData(data)
        floorsCache = parsed.floors
        parsed
    }

    /** internal：JVM 单测直接喂 buildingFloorDate 的真实结构验证解析。 */
    internal fun parseCatalogData(data: JsonElement?): CatalogData {
        val obj = data.asObject() ?: throw ApiException(null, "未获取到图书馆数据")

        val buildingsArr = locateBuildings(obj)
            ?: throw ApiException(
                null,
                "未解析到图书馆数据（字段：${obj.keys.joinToString(",")}）",
            )

        // 有些部署把楼层单独放在 data.floors 里，用 buildingId 关联
        val globalFloors = (
            obj.array("floors", "floorList")
                ?: findNamedArray(obj, setOf("floors", "floorList"))
            )
            ?.mapIndexedNotNull { index, el -> parseFloor(el, index, 0) }
            .orEmpty()

        val buildings = mutableListOf<Building>()
        val floors = mutableListOf<FloorInfo>()

        buildingsArr.forEachIndexed { index, item ->
            val b = item.asObject()
            val bArray = item as? JsonArray
            val buildingId = b?.long("id", "buildId", "buildingId", "venueId", "value")
                ?: bArray?.getOrNull(0).asLongOrNull()
                ?: (index + 1).toLong()
            val buildingName = b?.str("name", "buildName", "buildingName", "venueName", "label")
                ?: bArray?.getOrNull(1).asStringOrNull()
                ?: "图书馆$buildingId"

            val floorArr = b?.array(
                "floors", "floorList", "floorData", "floorsList", "floor", "children", "list",
            ) ?: (bArray?.getOrNull(2) as? JsonArray)
            val buildingFloors = mutableListOf<FloorInfo>()
            floorArr?.forEachIndexed { floorIndex, floorEl ->
                parseFloor(floorEl, floorIndex, buildingId)?.let(buildingFloors::add)
            }
            if (buildingFloors.isEmpty()) {
                buildingFloors += globalFloors.filter { it.buildingId == buildingId }
            }

            buildings += Building(buildingId, buildingName, buildingFloors.size)
            floors += buildingFloors
        }

        if (buildings.isEmpty()) {
            throw ApiException(null, "没有可用的图书馆（字段：${obj.keys.joinToString(",")}）")
        }
        val dates = parseDates(obj)
        return CatalogData(
            buildings = buildings,
            rooms = emptyList(),
            dates = dates,
            floors = floors.distinctBy { it.id }.sortedWith(compareBy({ it.buildingId }, { it.number })),
        )
    }

    override suspend fun roomStats(token: String, buildingId: Long): List<RoomStats> =
        roomStatsForFloor(token, buildingId, null, TimeText.iso(LocalDate.now()))

    /** 只拉当前楼层的房间，避免进入首页时把整栋楼所有楼层都请求一遍。 */
    internal suspend fun roomStatsForFloor(
        token: String,
        buildingId: Long,
        floorNumber: Int?,
        date: String,
    ): List<RoomStats> = io {
        loadHmacKey()
        if (floorsCache.isEmpty()) {
            runCatching { catalog(token) }
        }
        val floors = floorsCache.filter {
            it.buildingId == buildingId && (floorNumber == null || it.number == floorNumber)
        }
        val results = floors.map { floor ->
            runCatching { fetchRooms(token, buildingId, floor, date) }
        }
        val rooms = results.flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.room.id }
        if (rooms.isEmpty()) {
            results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        }
        rooms
    }

    override suspend fun seatMap(token: String, roomId: Long, date: String): SeatMap = io {
        val data = post("/static/frontApi/res/querySeatLayout/$roomId/0", token)
        val raw = when (data) {
            is JsonPrimitive -> data.contentOrNull
            is JsonObject -> data.str("data") ?: data.str("layout")
            else -> null
        } ?: throw ApiException(null, "座位图数据为空")

        val layoutJson = decodeLayoutPayload(raw)
        val statuses = runCatching { fetchSeatStatuses(token, roomId, date) }.getOrNull()
        parseSeatMapJson(layoutJson, roomId, statuses)
    }

    /** 真实部署返回明文 JSON + "_updVersion_x"；老部署返回 LZString(Base64)。 */
    internal fun decodeLayoutPayload(raw: String): String {
        val plain = raw.substringBefore("_updVersion_")
        return if (plain.trimStart().startsWith("{")) {
            plain
        } else {
            LzString.decompressFromBase64(plain) ?: throw ApiException(null, "座位图解码失败")
        }
    }

    /** internal：JVM 单测直接喂 Fabric.js 布局 JSON 验证座位解析。 */
    internal fun parseSeatMapJson(
        layoutJson: String,
        roomId: Long,
        seatStatuses: Map<Long, SeatStatus>?,
    ): SeatMap {
        val root = runCatching { Json.parseToJsonElement(layoutJson).jsonObject }.getOrNull()
            ?: throw ApiException(null, "座位图格式异常")

        val objects = root["objects"] as? JsonArray ?: JsonArray(emptyList())
        data class RawSeat(val seat: Seat, val left: Double, val top: Double)
        val rawSeats = mutableListOf<RawSeat>()
        objects.forEach { el ->
            val o = el.asObject() ?: return@forEach
            val seatObj = o["seat"]?.jsonObject ?: return@forEach
            val id = seatObj.long("id") ?: seatObj.str("id")?.toLongOrNull() ?: return@forEach
            val label = seatObj.str("label") ?: seatObj.str("name") ?: id.toString()
            val left = o.dbl("left") ?: 0.0
            val top = o.dbl("top") ?: 0.0
            val locked = seatObj.bool("lock") == true
            val localOnly = seatObj.bool("scene") == true
            rawSeats += RawSeat(
                Seat(
                    id = id,
                    name = label,
                    row = 0,
                    col = 0,
                    status = mapStatus(seatObj.str("status"), locked, localOnly),
                    nearWindow = seatObj.bool("window") == true,
                    hasPower = seatObj.bool("power") == true,
                ),
                left,
                top,
            )
        }

        if (rawSeats.isEmpty()) throw ApiException(null, "该区域暂无座位数据")

        // 座位图坐标间距约等于座位实际宽度（真实数据约 11.5px），
        // 用 effectiveWidth 推导步长，比写死 40 更贴近官方布局。
        val widths = objects.mapNotNull { el ->
            val o = el.asObject() ?: return@mapNotNull null
            val width = o.dbl("width") ?: return@mapNotNull null
            val scale = o.dbl("scaleX") ?: 1.0
            (width * scale).takeIf { it in 2.0..60.0 }
        }
        val step = if (widths.isNotEmpty()) {
            widths.average().roundToInt().coerceIn(8, 40)
        } else {
            13
        }

        // 用原始 double 坐标做分桶，避免先取整再整除造成列错位
        val rawRows = rawSeats.map { (it.top / step).roundToInt() }
        val rawCols = rawSeats.map { (it.left / step).roundToInt() }
        val minRow = rawRows.minOrNull() ?: 0
        val minCol = rawCols.minOrNull() ?: 0
        val normalized = rawSeats.mapIndexed { index, raw ->
            raw.seat.copy(
                row = rawRows[index] - minRow + 1,
                col = rawCols[index] - minCol + 1,
            )
        }

        // 真实接口的状态在 freeSeatIdsDuration 的每个座位值里（FREE/IN_USE/AWAY），
        // 不是“出现在 key 里就是空闲”。
        val merged = if (!seatStatuses.isNullOrEmpty()) {
            normalized.map { seat ->
                seat.copy(status = seatStatuses[seat.id] ?: SeatStatus.UNKNOWN)
            }
        } else {
            normalized
        }

        val rows = (merged.maxOfOrNull { it.row } ?: 1).coerceAtLeast(1)
        val cols = (merged.maxOfOrNull { it.col } ?: 1).coerceAtLeast(1)
        return SeatMap(roomId, "", rows, cols, merged)
    }

    override suspend fun book(token: String, seatId: Long, date: String, start: Int, end: Int): Reservation = io {
        // 新版预约强制人机校验：由 UI 层弹出滑块，拿到 capToken 后再提交
        val capToken = capTokenProvider?.invoke()
        if (capTokenProvider != null && capToken.isNullOrBlank()) {
            throw ApiException(null, "已取消安全验证，预约未提交")
        }
        val encoded = java.net.URLEncoder.encode(capToken.orEmpty(), "UTF-8")
        val data = post(
            "/static/frontApi/make/freeBook/$seatId/$date/$start/$end?capToken=$encoded",
            token,
        )
        val o = data.asObject()
        Reservation(
            id = o?.long("id") ?: o?.long("makeId") ?: 0L,
            seatId = seatId,
            location = o?.str("location") ?: o?.str("roomName") ?: "图书馆座位",
            date = o?.str("makeDateStr") ?: o?.str("makeDate") ?: date,
            begin = normalizeTime(o?.str("makeBeginStr") ?: o?.str("begin")) ?: TimeText.minutesToHhmm(start),
            end = normalizeTime(o?.str("makeEndStr") ?: o?.str("end")) ?: TimeText.minutesToHhmm(end),
            status = "RESERVE",
            receipt = o?.str("receipt").orEmpty(),
            message = o?.str("message").orEmpty(),
        )
    }

    override suspend fun reservations(token: String): List<Reservation> = io {
        val data = post("/static/frontApi/user/currentUseMake", token)
        val list = when (data) {
            null, JsonNull -> return@io emptyList()
            is JsonArray -> data
            is JsonObject -> {
                val nested = data["list"] as? JsonArray
                if (nested != null) nested else JsonArray(listOf(data))
            }
            else -> return@io emptyList()
        }
        list.mapNotNull { parseReservation(it.asObject()) }
    }

    override suspend fun cancel(token: String, reservationId: Long): Unit = io {
        post("/static/frontApi/make/cancel/$reservationId", token)
        Unit
    }

    override suspend fun history(token: String): List<HistoryItem> = io {
        val data = post("/static/frontApi/user/history/1/20", token)
        val arr = (data.asObject()?.get("list") as? JsonArray) ?: return@io emptyList()
        arr.mapNotNull { el ->
            val o = el.asObject() ?: return@mapNotNull null
            HistoryItem(
                id = o.long("id") ?: 0L,
                date = o.str("makeDateStr", "makeDate", "date").orEmpty(),
                begin = normalizeTime(o.str("makeBeginStr") ?: o.str("begin")).orEmpty(),
                end = normalizeTime(o.str("makeEndStr") ?: o.str("end")).orEmpty(),
                location = o.str("location") ?: o.str("roomName").orEmpty(),
                status = o.str("status") ?: o.str("stat").orEmpty(),
            )
        }
    }

    override suspend fun startTimes(token: String, seatId: Long, date: String): List<TimeSlot> = io {
        val data = post("/static/frontApi/res/getStartTimes/$seatId/$date", token)
        val slots = parseTimePairs(data)
        slots.ifEmpty { TimeText.buildDaySlots(7 * 60, 21 * 60) }
    }

    override suspend fun endTimes(token: String, seatId: Long, date: String, start: Int): List<TimeSlot> = io {
        val data = post("/static/frontApi/res/getEndTimes/$seatId/$date/$start", token)
        val slots = parseTimePairs(data)
        slots.ifEmpty { TimeText.buildDaySlots(start + 30, minOf(start + 8 * 60, 22 * 60)) }
    }

    override suspend fun user(token: String): UserProfile = io {
        val data = post("/static/frontApi/user/getUserInfo", token)
        val o = data.asObject()
        UserProfile(
            name = o?.str("fullName", "name", "realName").orEmpty(),
            username = o?.str("username") ?: o?.str("userName").orEmpty(),
            violationCount = o?.int("violationCount") ?: o?.int("breachCount") ?: 0,
            checkedIn = o?.bool("checkedIn") ?: false,
        )
    }

    // ---------------- 数据解析 ----------------

    /**
     * buildingFloorDate 的字段名在不同版本里不完全一致：
     * buildings / buildList / venues，building.id / buildId / venueId，floors / floorList。
     * 先按常见 key 找，再递归找“带 floors 数组”的那一层数组。
     */
    private fun locateBuildings(root: JsonObject): JsonArray? {
        val keys = setOf("buildings", "buildList", "venues", "venueList", "buildingList")
        keys.forEach { key ->
            root.array(key)?.let { arr ->
                if (arr.any { it is JsonObject || it is JsonArray }) return arr
            }
        }
        // 数据可能被包了一层 data / result，递归找同名数组
        findNamedArray(root, keys)?.let { return it }

        var found: JsonArray? = null
        fun walk(el: JsonElement?) {
            if (found != null) return
            when (el) {
                is JsonArray -> {
                    val hasBuildingShape = el.any { item ->
                        val o = item as? JsonObject ?: return@any false
                        o.array("floors", "floorList", "floorData", "floorsList", "floor") != null
                    }
                    if (hasBuildingShape) {
                        found = el
                        return
                    }
                    el.forEach(::walk)
                }

                is JsonObject -> el.values.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return found
    }

    private fun findNamedArray(el: JsonElement?, keys: Set<String>): JsonArray? {
        when (el) {
            is JsonObject -> {
                keys.forEach { key ->
                    (el[key] as? JsonArray)?.let { return it }
                }
                el.values.forEach { child -> findNamedArray(child, keys)?.let { return it } }
            }

            is JsonArray -> el.forEach { child -> findNamedArray(child, keys)?.let { return it } }
            else -> Unit
        }
        return null
    }

    private fun parseFloor(el: JsonElement?, index: Int, fallbackBuildingId: Long): FloorInfo? {
        val f = el.asObject()
        val arr = el as? JsonArray
        val buildingId = f?.long("buildingId", "buildId", "venueId", "building")
            ?: arr?.getOrNull(3).asLongOrNull()
            ?: fallbackBuildingId
        if (buildingId <= 0) return null
        val floorName = f?.str("name", "floorName", "label", "floor", "value")
            ?: arr?.getOrNull(1).asStringOrNull()
            ?: "${index + 1}F"
        val floorId = f?.long("id", "floorId", "floorid", "value")
            ?: arr?.getOrNull(0).asLongOrNull()
            ?: (buildingId * 100 + index + 1)
        val number = f?.int("floor", "floorNum", "number", "sort", "order")
            ?: arr?.getOrNull(2).asIntOrNull()
            ?: Regex("(\\d+)").find(floorName)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: (index + 1)
        return FloorInfo(floorId, buildingId, floorName, number)
    }

    private fun parseDates(root: JsonObject): List<String> {
        val arr = root.array("dates", "dateList", "canMakeDates", "makeDates", "selectDates")
            ?: findNamedArray(root, setOf("dates", "dateList", "canMakeDates", "makeDates", "selectDates"))
            ?: findDateArray(root)
            ?: return emptyList()
        return arr.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull
                is JsonArray -> item.firstOrNull().asStringOrNull()
                is JsonObject -> item.str("date", "value", "makeDate", "day")
                else -> null
            }?.takeIf { it.length >= 10 }
        }.distinct()
    }

    private fun findDateArray(el: JsonElement?): JsonArray? {
        when (el) {
            is JsonArray -> {
                if (el.isNotEmpty() && el.all { item ->
                        val s = item.asStringOrNull() ?: return@all false
                        Regex("\\d{4}-\\d{2}-\\d{2}").containsMatchIn(s)
                    }
                ) {
                    return el
                }
                el.forEach { child -> findDateArray(child)?.let { return it } }
            }

            is JsonObject -> el.values.forEach { child -> findDateArray(child)?.let { return it } }
            else -> Unit
        }
        return null
    }

    private fun normalizeReservationStatus(raw: String?): String {
        val value = raw?.trim()?.uppercase().orEmpty()
        return when (value) {
            "RESERVE", "RESERVED", "BOOKED", "RESERVATION", "待签到" -> "RESERVE"
            "IN_USE", "INUSE", "USING", "USE", "CHECKED_IN", "CHECK_IN", "CHECKIN", "SIGNED",
            "ACTIVE", "履约中", "使用中" -> "IN_USE"
            "AWAY", "LEAVE", "TEMPORARY", "暂离" -> "AWAY"
            "COMPLETE", "COMPLETED", "已完成" -> "COMPLETE"
            "MISS", "NO_SHOW", "违约" -> "MISS"
            "CANCEL", "CANCELED", "CANCELLED", "已取消" -> "CANCEL"
            else -> value
        }
    }

    private suspend fun fetchRooms(
        token: String,
        buildingId: Long,
        floor: FloorInfo,
        date: String,
    ): List<RoomStats> {
        // 前端调用：/findRoomDuration/{venueId}/{currentPage}
        // floorId 在 body，日期不参与这个接口（返回的是当前余量）
        val body = roomQueryBody(floor.id)
        val data = post("/static/frontApi/res/findRoomDuration/$buildingId/$date", token, body)
        return parseRoomsPage(data, buildingId, floor, floorsCache)
    }

    internal fun roomQueryBody(floorId: Long): JsonObject = JsonObject(
        mapOf(
            "beginMinute" to JsonPrimitive(-1),
            "currentPage" to JsonPrimitive(1),
            "endMinute" to JsonPrimitive(0),
            "floorId" to JsonPrimitive(floorId.toString()),
            "minMinute" to JsonPrimitive(0),
            "pageSize" to JsonPrimitive(100),
            "power" to JsonPrimitive(false),
            "roomType" to JsonPrimitive(false),
            "sortField" to JsonPrimitive(""),
            "sortType" to JsonPrimitive(""),
            "windows" to JsonPrimitive(false),
        )
    )

    /**
     * 解析房间分页数据。internal 以便 JVM 单测直接用真实返回结构验证。
     */
    internal fun parseRoomsPage(
        data: JsonElement?,
        buildingId: Long,
        floor: FloorInfo,
        allFloors: List<FloorInfo>,
    ): List<RoomStats> {
        val roomObjects = mutableListOf<JsonObject>()
        collectRoomObjects(data, roomObjects)

        val pageList = (data.asObject()?.get("pageList") as? JsonArray)
            ?: (data.asObject()?.get("data") as? JsonObject)?.get("pageList") as? JsonArray

        val rooms = mutableListOf<RoomStats>()
        roomObjects.forEach { o -> parseRoomObject(o, buildingId, floor, allFloors)?.let(rooms::add) }
        pageList?.forEach { el ->
            val o = el.asObject() ?: return@forEach
            parseRoomObject(o, buildingId, floor, allFloors)?.let(rooms::add)
        }

        if (rooms.isEmpty()) {
            val shape = pageList?.firstOrNull()?.asObject()?.keys?.joinToString(",")
                ?: (data as? JsonObject)?.keys?.joinToString(",")
                ?: "空"
            throw ApiException(null, "未解析到 ${floor.name} 的房间数据（字段：$shape）")
        }
        return rooms.distinctBy { it.room.id }
    }

    private fun parseRoomObject(
        o: JsonObject,
        buildingId: Long,
        floor: FloorInfo,
        allFloors: List<FloorInfo>,
    ): RoomStats? {
        // 少数部署会把房间信息再包一层，例如 { room: {...}, seatFree: ... }
        val source = if (o.int("id", "roomId", "roomID", "roomid") != null &&
            o.str("roomName", "name", "room", "label") != null
        ) {
            o
        } else {
            o.values.filterIsInstance<JsonObject>().firstOrNull { nested ->
                nested.int("id", "roomId") != null && nested.str("roomName", "name") != null
            } ?: o
        }

        val id = source.long("id", "roomId", "roomID", "roomid") ?: return null
        val name = source.str("roomName", "name", "room", "label") ?: return null
        val total = source.int(
            "seatTotal", "totalSeat", "seatNum", "totalNum", "seatCount", "total", "count", "capacity",
        ) ?: source.fuzzyInt("total")
            ?: source.fuzzyInt("capacity")
            ?: 0
        val free = source.int(
            "seatFree", "freeSeat", "freeNum", "free", "available", "empty",
            "emptyCount", "remain", "surplus",
        ) ?: source.fuzzyInt("free")
            ?: source.fuzzyInt("available")
            ?: source.fuzzyInt("empty")
            ?: source.fuzzyInt("remain")
            ?: source.fuzzyInt("surplus")
            ?: 0
        if (total <= 0 && free <= 0) {
            // 有的接口房间对象不带余量字段，仍然保留房间，避免整页失败
            return RoomStats(
                room = RoomInfo(id, name, buildingId, resolveFloor(source, floor, allFloors).number),
                free = 0,
                total = 0,
                countsKnown = false,
            )
        }
        return RoomStats(
            room = RoomInfo(id, name, buildingId, resolveFloor(source, floor, allFloors).number),
            free = free,
            total = total,
            inUse = (total - free).coerceAtLeast(0),
        )
    }

    private fun resolveFloor(o: JsonObject, fallback: FloorInfo, allFloors: List<FloorInfo>): FloorInfo {
        val floorId = o.long("floorId", "floorid") ?: return fallback
        return allFloors.firstOrNull { it.id == floorId } ?: fallback
    }

    private fun JsonObject.fuzzyInt(token: String): Int? =
        entries.firstNotNullOfOrNull { (key, value) ->
            if (!key.lowercase().contains(token)) return@firstNotNullOfOrNull null
            (value as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
        }

    private suspend fun fetchSeatStatuses(token: String, roomId: Long, date: String): Map<Long, SeatStatus> {
        val body = JsonObject(
            mapOf(
                "beginMinute" to JsonPrimitive(-1),
                "endMinute" to JsonPrimitive(0),
                "minMinute" to JsonPrimitive(0),
            )
        )
        val data = post("/static/frontApi/res/freeSeatIdsDuration/$roomId/$date", token, body)
        return parseSeatStatusMap(data)
    }

    /** internal：解析 freeSeatIdsDuration 的“座位 id -> 状态”映射。 */
    internal fun parseSeatStatusMap(data: JsonElement?): Map<Long, SeatStatus> {
        val statuses = mutableMapOf<Long, SeatStatus>()
        when (data) {
            is JsonObject -> data.forEach { (key, value) ->
                val id = key.toLongOrNull() ?: return@forEach
                val o = value as? JsonObject
                val rawStatus = when (value) {
                    is JsonPrimitive -> value.contentOrNull
                    is JsonObject -> o?.str("status")
                    else -> null
                }
                val status = mapStatus(
                    rawStatus,
                    o?.bool("lock") == true,
                    o?.bool("scene") == true,
                )
                statuses[id] = if (status == SeatStatus.UNKNOWN) SeatStatus.FREE else status
            }

            is JsonArray -> data.forEach { el ->
                when (el) {
                    is JsonPrimitive -> el.contentOrNull?.toLongOrNull()?.let { statuses[it] = SeatStatus.FREE }
                    is JsonObject -> {
                        val id = el.long("id", "seatId") ?: return@forEach
                        val status = mapStatus(el.str("status"), false, false)
                        statuses[id] = if (status == SeatStatus.UNKNOWN) SeatStatus.FREE else status
                    }
                    else -> Unit
                }
            }

            else -> Unit
        }
        return statuses
    }

    private fun collectRoomObjects(el: JsonElement?, out: MutableList<JsonObject>) {
        when (el) {
            is JsonArray -> el.forEach { collectRoomObjects(it, out) }
            is JsonObject -> {
                if (el["seatTotal"] != null || el["seatFree"] != null ||
                    el["totalSeat"] != null || el["freeSeat"] != null
                ) {
                    out += el
                }
                el.values.forEach { collectRoomObjects(it, out) }
            }
            else -> Unit
        }
    }

    internal fun parseReservation(o: JsonObject?): Reservation? {        if (o == null || o.isEmpty()) return null
        val id = o.long("id") ?: o.long("makeId") ?: o.long("recordId") ?: 0L
        val location = o.str("location", "seatLocation", "loc")
            ?: listOfNotNull(o.str("buildingName"), o.str("roomName"), o.str("seatLabel"))
                .joinToString(" ")
                .ifBlank { "" }
        val date = o.str("makeDateStr", "makeDate", "onDate", "date", "makeDateDesc") ?: ""
        if (id == 0L && date.isBlank() && location.isBlank()) return null
        return Reservation(
            id = id,
            seatId = o.long("seatId", "seatID") ?: 0L,
            location = location.ifBlank { "图书馆座位" },
            date = date,
            begin = normalizeTime(
                o.str("makeBeginStr", "begin", "startTime", "makeBegin")
            ).orEmpty(),
            end = normalizeTime(
                o.str("makeEndStr", "end", "endTime", "makeEnd")
            ).orEmpty(),
            status = normalizeReservationStatus(
                o.str("status", "makeStatus", "state", "makeState", "useStatus", "stat")
            ).ifBlank { "RESERVE" },
            receipt = o.str("receipt", "makeNo", "orderNo").orEmpty(),
            message = o.str("message").orEmpty(),
        )
    }

    internal fun parseTimePairs(data: JsonElement?): List<TimeSlot> {
        val arr = data as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            when (item) {
                is JsonArray -> {
                    val value = item.getOrNull(0).asStringOrNull() ?: return@mapNotNull null
                    val label = item.getOrNull(1).asStringOrNull()
                    normalizeSlot(value, label)
                }
                is JsonObject -> {
                    val value = item.str("value") ?: item.str("id") ?: return@mapNotNull null
                    normalizeSlot(value, item.str("text") ?: item.str("label"))
                }
                else -> null
            }
        }.distinctBy { it.minutes }.sortedBy { it.minutes }
    }

    private fun normalizeSlot(value: String, label: String?): TimeSlot? {
        val minutes = when {
            value.equals("now", true) -> TimeText.nowRoundedToHalfHour()
            value.contains(":") -> TimeText.hhmmToMinutes(value)
            else -> value.toIntOrNull()
        } ?: return null
        return TimeSlot(minutes, label ?: TimeText.minutesToHhmm(minutes))
    }

    private fun normalizeTime(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val v = raw.replace(" ", "")
        return when {
            v.matches(Regex("\\d{1,2}:\\d{2}")) -> {
                val (h, m) = v.split(":")
                "%02d:%s".format(h.toIntOrNull() ?: 0, m)
            }
            v.toIntOrNull() != null -> TimeText.minutesToHhmm(v.toInt())
            else -> v
        }
    }

    private fun mapStatus(status: String?, locked: Boolean, localOnly: Boolean): SeatStatus {
        if (locked || localOnly) return SeatStatus.UNKNOWN
        return when (status?.uppercase()) {
            "FREE", "AVAILABLE", "IDLE" -> SeatStatus.FREE
            "AWAY", "LEAVE" -> SeatStatus.AWAY
            "IN_USE", "INUSE", "USED" -> SeatStatus.IN_USE
            "BOOKED", "RESERVED", "RESERVE", "FULL" -> SeatStatus.RESERVED
            else -> SeatStatus.UNKNOWN
        }
    }

    // ---------------- HTTP ----------------

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private suspend fun post(
        path: String,
        token: String,
        body: JsonObject = JsonObject(emptyMap()),
    ): JsonElement? {
        val base = baseUrlProvider().trim().ifBlank { DEFAULT_BASE_URL }
        val normalized = if (base.startsWith("http")) base else "https://$base"
        val root = normalized.trimEnd('/').removeSuffix("/jsq")
        val url = (root + "/jsq" + path).toHttpUrlOrNull()
            ?: throw ApiException(null, "服务器地址无效")

        val builder = Request.Builder()
            .url(url)
            .header("token", token)
            .header("Accept", "application/json")
            .header("loginType", "PC")
            .header("User-Agent", USER_AGENT)
        hmacHeaders("POST").forEach { (key, value) -> builder.header(key, value) }

        val request = builder
            .post(body.toString().toRequestBody(mediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
            if (!response.isSuccessful) {
                throw ApiException(
                    response.code.toString(),
                    parsed.asObject()?.str("message") ?: "网络请求失败（HTTP ${response.code}）",
                )
            }
            val rootObj = parsed.asObject()
                ?: throw ApiException(null, "服务器返回了无法解析的内容")
            val status = rootObj["status"]
            val ok = status?.jsonPrimitive?.booleanOrNull == true ||
                status?.jsonPrimitive?.contentOrNull == "success" ||
                status?.jsonPrimitive?.contentOrNull == "true"
            if (!ok) {
                val code = rootObj.str("code")
                val message = rootObj.str("message").orEmpty().ifBlank { "请求失败" }
                if (code == "20002" || code == "20003") {
                    throw SessionExpiredException(message)
                }
                throw ApiException(code, message)
            }
            return rootObj["data"]
        }
    }

    private fun hmacHeaders(method: String): Map<String, String> {
        val key = hmacKey?.takeIf { it.isNotBlank() } ?: return emptyMap()
        val requestId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val message = "seat::$requestId::$timestamp::${method.uppercase(Locale.US)}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return mapOf(
            "X-request-id" to requestId,
            "X-request-date" to timestamp.toString(),
            "X-hmac-request-key" to signature,
        )
    }

    private suspend fun loadHmacKey() {
        if (hmacKey != null) return
        runCatching {
            val root = baseRoot()
            val request = Request.Builder()
                .url("$root/jsq/static/public/cg/getSysSet/PC")
                .header("Accept", "application/json")
                .post("{}".toRequestBody(mediaType))
                .build()
            val text = client.newCall(request).execute().use { it.body?.string().orEmpty() }
            val obj = json.parseToJsonElement(text).jsonObject
            val data = obj["data"] as? JsonObject ?: return@runCatching
            val enabled = data.int("hmac") ?: 0
            val encrypted = data.str("hmacKey")
            hmacKey = if (enabled == 1 && encrypted != null) {
                decryptHmacKey(encrypted) ?: ""
            } else {
                ""
            }
        }
    }

    /** 前端 I.decrypt(hmacKey)：AES-128-CBC，key=server_date_time，iv=client_date_time。 */
    private fun decryptHmacKey(encrypted: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val key = SecretKeySpec("server_date_time".toByteArray(Charsets.UTF_8), "AES")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            IvParameterSpec("client_date_time".toByteArray(Charsets.UTF_8)),
        )
        String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), Charsets.UTF_8)
    }.getOrNull()

    private fun baseRoot(): String {
        val base = baseUrlProvider().trim().ifBlank { DEFAULT_BASE_URL }
        val normalized = if (base.startsWith("http")) base else "https://$base"
        return normalized.trimEnd('/').removeSuffix("/jsq")
    }

    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

    private fun JsonElement?.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.asIntOrNull(): Int? =
        (this as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

    private fun JsonElement?.asLongOrNull(): Long? =
        (this as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

    private fun JsonObject.str(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }

    private fun JsonObject.array(vararg keys: String): JsonArray? {
        keys.forEach { key ->
            when (val value = this[key]) {
                is JsonArray -> return value
                is JsonObject -> (value["list"] as? JsonArray)?.let { return it }
                else -> Unit
            }
        }
        return null
    }

    private fun JsonObject.long(vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { key ->
            (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
        }

    private fun JsonObject.int(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key ->
            (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
        }

    private fun JsonObject.bool(vararg keys: String): Boolean? =
        keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.booleanOrNull }

    private fun JsonObject.dbl(key: String): Double? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}

fun jsonSeatApiClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .build()
