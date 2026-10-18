package cn.edu.whu.libseat.data.remote

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
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 武汉大学图书馆座位预约系统（seat.lib.whu.edu.cn）真实接口实现。
 *
 * 该系统的接口并非官方公开 SDK，Ham 也是通过同样的 REST 端点完成预约的：
 *   GET  /rest/auth?username=&password=                 -> data.token
 *   GET  /rest/v2/free/filters                          -> buildings / rooms / dates
 *   GET  /rest/v2/room/stats2/{buildingId}              -> 每个房间的空闲数
 *   GET  /rest/v2/room/layoutByDate/{roomId}/{date}     -> 座位图（rows/cols/layout）
 *   GET  /rest/v2/startTimesForSeat/{seatId}/{date}
 *   GET  /rest/v2/endTimesForSeat/{seatId}/{date}/{start}
 *   POST /rest/v2/freeBook                              -> 预约
 *   POST /rest/v2/cancel/{id}                           -> 取消
 *   GET  /rest/v2/user/reservations                     -> 当前预约
 *   GET  /rest/v2/history/{page}/{count}                -> 历史预约
 *
 * 所有 v2 请求都同时携带 token header 与 token query，兼容不同部署版本。
 */
class WhuLibraryApi(
    private val client: OkHttpClient,
    private val baseUrlProvider: () -> String,
) : LibraryApi {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun login(username: String, password: String): String = io {
        val form = mapOf("username" to username, "password" to password)
        val data = try {
            unwrap(request("/rest/auth", null, form))
        } catch (e: ApiException) {
            // 部分版本只接受 GET 查询参数登录
            if (e.code == "404" || e.code == "405") {
                val q = URLEncoder.encode(username, "UTF-8")
                val p = URLEncoder.encode(password, "UTF-8")
                unwrap(request("/rest/auth?username=$q&password=$p", null, null))
            } else {
                throw e
            }
        }
        data.asObject()?.string("token")?.takeIf { it.isNotBlank() }
            ?: throw ApiException(null, "登录响应异常：未获取到 token")
    }

    override suspend fun user(token: String): UserProfile = io {
        val data = unwrap(request("/rest/v2/user", token, null))
        val o = data.asObject()
        UserProfile(
            name = o?.string("name").orEmpty(),
            username = o?.string("username").orEmpty(),
            violationCount = o?.int("violationCount") ?: 0,
            checkedIn = o?.bool("checkedIn") ?: false,
        )
    }

    override suspend fun catalog(token: String): CatalogData = io {
        val data = try {
            unwrap(request("/rest/v2/free/filters", token, null))
        } catch (e: ApiException) {
            unwrap(request("/rest/v2/filters", token, null))
        }
        val o = data.asObject() ?: throw ApiException(null, "未获取到图书馆基础数据")

        var buildings = parseBuildings(o["buildings"])
        val rooms = parseRooms(o["rooms"], buildings.firstOrNull()?.id ?: 0)
        if (buildings.isEmpty() && rooms.isNotEmpty()) {
            buildings = rooms.map { it.buildingId }.distinct().map { id ->
                Building(id, if (id == rooms.first().buildingId) "图书馆" else "分馆$id")
            }
        }
        val dates = (o["dates"] as? JsonArray)
            ?.mapNotNull { it.asString() }
            ?.filter { it.length >= 10 }
            .orEmpty()
        val floors = rooms
            .map { FloorInfo(id = it.floor * 10000L + it.buildingId, buildingId = it.buildingId, name = "${it.floor}F", number = it.floor) }
            .distinctBy { it.buildingId to it.number }
            .sortedWith(compareBy({ it.buildingId }, { it.number }))
        CatalogData(buildings = buildings, rooms = rooms, dates = dates, floors = floors)
    }

    override suspend fun roomStats(token: String, buildingId: Long): List<RoomStats> = io {
        val data = unwrap(request("/rest/v2/room/stats2/$buildingId", token, null))
        val arr = data as? JsonArray ?: return@io emptyList()
        arr.mapNotNull { el ->
            val o = el.asObject() ?: return@mapNotNull null
            val roomId = o.long("roomId") ?: return@mapNotNull null
            val roomName = o.string("room") ?: "区域$roomId"
            val floor = o.int("floor") ?: 0
            RoomStats(
                room = RoomInfo(roomId, roomName, buildingId, floor),
                free = o.int("free") ?: 0,
                total = o.int("totalSeats") ?: 0,
                inUse = o.int("inUse") ?: 0,
                away = o.int("away") ?: 0,
                reserved = o.int("reserved") ?: 0,
            )
        }
    }

    override suspend fun seatMap(token: String, roomId: Long, date: String): SeatMap = io {
        val data = unwrap(request("/rest/v2/room/layoutByDate/$roomId/$date", token, null))
        val o = data.asObject() ?: throw ApiException(null, "该区域暂无座位数据")

        val layout = o["layout"] as? JsonObject ?: JsonObject(emptyMap())
        val seats = mutableListOf<Seat>()
        for ((key, value) in layout) {
            val seatObj = value.asObject() ?: continue
            val type = seatObj.string("type")
            val id = seatObj.long("id")
            if (id == null) continue
            if (type != null && type != "seat") continue
            val cell = parseCell(key) ?: continue
            seats += Seat(
                id = id,
                name = seatObj.string("name") ?: id.toString(),
                row = cell.first,
                col = cell.second,
                status = parseSeatStatus(seatObj.string("status")),
                nearWindow = seatObj.bool("window") ?: false,
                hasPower = seatObj.bool("power") ?: false,
            )
        }

        val rows = maxOf(o.int("rows") ?: 0, (seats.maxOfOrNull { it.row } ?: 0) + 1)
        val cols = maxOf(o.int("cols") ?: 0, (seats.maxOfOrNull { it.col } ?: 0) + 1)
        SeatMap(
            roomId = roomId,
            roomName = o.string("name") ?: "",
            rows = rows,
            cols = cols,
            seats = seats,
        )
    }

    override suspend fun book(
        token: String,
        seatId: Long,
        date: String,
        start: Int,
        end: Int,
    ): Reservation = io {
        val form = mapOf(
            "t" to "1",
            "startTime" to start.toString(),
            "endTime" to end.toString(),
            "seat" to seatId.toString(),
            "date" to date,
            "t2" to "2",
        )
        val data = unwrap(request("/rest/v2/freeBook", token, form))
        val o = data.asObject()
        Reservation(
            id = o?.long("id") ?: 0L,
            seatId = seatId,
            location = o?.string("location").orEmpty(),
            date = o?.string("onDate") ?: date,
            begin = normalizeClock(o?.string("begin")) ?: TimeText.minutesToHhmm(start),
            end = normalizeClock(o?.string("end")) ?: TimeText.minutesToHhmm(end),
            status = "RESERVE",
            receipt = o?.string("receipt").orEmpty(),
            message = o?.string("message").orEmpty(),
        )
    }

    override suspend fun reservations(token: String): List<Reservation> = io {
        val data = unwrap(request("/rest/v2/user/reservations", token, null))
        when (data) {
            null, JsonNull -> emptyList()
            is JsonArray -> data.mapNotNull { parseReservation(it.asObject()) }
            is JsonObject -> listOfNotNull(parseReservation(data))
            else -> emptyList()
        }
    }

    override suspend fun cancel(token: String, reservationId: Long): Unit = io {
        unwrap(request("/rest/v2/cancel/$reservationId", token, emptyMap()))
        Unit
    }

    override suspend fun history(token: String): List<HistoryItem> = io {
        val data = unwrap(request("/rest/v2/history/1/20", token, null))
        val arr = data.asObject()?.get("reservations") as? JsonArray ?: return@io emptyList()
        arr.mapNotNull { el ->
            val o = el.asObject() ?: return@mapNotNull null
            HistoryItem(
                id = o.long("id") ?: 0L,
                date = o.string("date") ?: o.string("onDate").orEmpty(),
                begin = normalizeClock(o.string("begin")).orEmpty(),
                end = normalizeClock(o.string("end")).orEmpty(),
                location = o.string("loc") ?: o.string("location").orEmpty(),
                status = o.string("stat") ?: o.string("status").orEmpty(),
            )
        }
    }

    override suspend fun startTimes(token: String, seatId: Long, date: String): List<TimeSlot> = io {
        val data = unwrap(request("/rest/v2/startTimesForSeat/$seatId/$date", token, null))
        parseSlots(data.asObject()?.get("startTimes")).ifEmpty {
            TimeText.buildDaySlots(7 * 60, 21 * 60)
        }
    }

    override suspend fun endTimes(token: String, seatId: Long, date: String, start: Int): List<TimeSlot> = io {
        val data = unwrap(request("/rest/v2/endTimesForSeat/$seatId/$date/$start", token, null))
        parseSlots(data.asObject()?.get("endTimes")).ifEmpty {
            TimeText.buildDaySlots(start + 30, minOf(start + 8 * 60, 22 * 60))
        }
    }

    // ---------------- HTTP ----------------

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun request(path: String, token: String?, form: Map<String, String>?): JsonElement {
        val base = baseUrlProvider().trim().ifBlank { DEFAULT_BASE_URL }
        val normalized = if (base.startsWith("http://") || base.startsWith("https://")) base else "https://$base"
        val url = (normalized.trimEnd('/') + path).toHttpUrlOrNull()
            ?: throw ApiException(null, "服务器地址无效：$base")
        val withToken = url.newBuilder().apply {
            if (!token.isNullOrBlank()) addQueryParameter("token", token)
        }.build()

        val builder = Request.Builder()
            .url(withToken)
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", USER_AGENT)
        if (!token.isNullOrBlank()) builder.header("token", token)

        if (form != null) {
            val bodyBuilder = FormBody.Builder()
            form.forEach { (k, v) -> bodyBuilder.add(k, v) }
            builder.post(bodyBuilder.build())
        } else {
            builder.get()
        }

        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
            if (!response.isSuccessful) {
                val message = parsed.asObject()?.string("message")
                    ?: "网络请求失败（HTTP ${response.code}）"
                throw ApiException(response.code.toString(), message)
            }
            if (parsed == null) {
                val contentType = response.header("Content-Type").orEmpty()
                val html = contentType.contains("text/html", true) ||
                    text.trimStart().startsWith("<")
                if (html) {
                    throw ApiException(
                        "NEW_SYSTEM",
                        "该校座位系统已升级到新版（/jsq），Ham 使用的经典 /rest 接口已停用。" +
                            "可先用演示模式体验，或把服务器地址改为仍运行旧版接口的部署。",
                    )
                }
                throw ApiException(null, "服务器返回了无法解析的内容")
            }
            return parsed
        }
    }

    /** 校验 {status, code, message, data} 信封，并返回 data。 */
    private fun unwrap(root: JsonElement): JsonElement? {
        val obj = root.asObject() ?: return root
        if (!obj.containsKey("status") && !obj.containsKey("code")) return root

        val status = obj["status"].asString()
        val code = obj.string("code")
        val ok = status == "success" || status == "true" ||
            (status == null && (code == null || code == "0"))
        if (ok) return obj["data"]

        val message = obj.string("message").orEmpty().ifBlank { "请求失败" }
        if (code == "12" || code == "20002") throw SessionExpiredException(message)
        val friendly = when (code) {
            "10" -> "座位系统正在维护，请稍后再试"
            "13" -> "账号或密码不正确"
            "14" -> "该座位当前不可预约"
            else -> message
        }
        throw ApiException(code, friendly)
    }

    // ---------------- 解析 ----------------

    private fun parseBuildings(el: JsonElement?): List<Building> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            when (item) {
                is JsonArray -> {
                    val id = item.getOrNull(0)?.asLong() ?: return@mapNotNull null
                    val name = item.getOrNull(1)?.asString() ?: "图书馆$id"
                    val count = item.getOrNull(2)?.asInt() ?: 0
                    Building(id, name, count)
                }
                is JsonObject -> {
                    val id = item.long("id") ?: return@mapNotNull null
                    Building(id, item.string("name") ?: "图书馆$id", item.int("floors") ?: 0)
                }
                else -> null
            }
        }
    }

    private fun parseRooms(el: JsonElement?, fallbackBuildingId: Long): List<RoomInfo> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            when (item) {
                is JsonArray -> {
                    val id = item.getOrNull(0)?.asLong() ?: return@mapNotNull null
                    val name = item.getOrNull(1)?.asString() ?: "区域$id"
                    val buildingId = item.getOrNull(2)?.asLong() ?: fallbackBuildingId
                    val floor = item.getOrNull(3)?.asInt() ?: 0
                    RoomInfo(id, name, buildingId, floor)
                }
                is JsonObject -> {
                    val id = item.long("id") ?: item.long("roomId") ?: return@mapNotNull null
                    RoomInfo(
                        id = id,
                        name = item.string("name") ?: item.string("room") ?: "区域$id",
                        buildingId = item.long("buildingId") ?: fallbackBuildingId,
                        floor = item.int("floor") ?: 0,
                    )
                }
                else -> null
            }
        }
    }

    private fun parseReservation(o: JsonObject?): Reservation? {
        if (o == null) return null
        return Reservation(
            id = o.long("id") ?: 0L,
            seatId = o.long("seatId") ?: o.long("seatID") ?: 0L,
            location = o.string("location") ?: o.string("loc").orEmpty(),
            date = o.string("onDate") ?: o.string("date").orEmpty(),
            begin = normalizeClock(o.string("begin")).orEmpty(),
            end = normalizeClock(o.string("end")).orEmpty(),
            status = o.string("status") ?: "RESERVE",
            receipt = o.string("receipt").orEmpty(),
            message = o.string("message").orEmpty(),
        )
    }

    private fun parseSeatStatus(raw: String?): SeatStatus = when (raw?.uppercase()) {
        "FREE" -> SeatStatus.FREE
        "IN_USE", "USED", "INUSE" -> SeatStatus.IN_USE
        "AWAY" -> SeatStatus.AWAY
        "RESERVED", "BOOKED", "RESERVE" -> SeatStatus.RESERVED
        else -> SeatStatus.UNKNOWN
    }

    private fun parseCell(key: String): Pair<Int, Int>? {
        val parts = key.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            val r = parts[0].toIntOrNull() ?: return null
            val c = parts[1].toIntOrNull() ?: return null
            return r to c
        }
        if (key.length >= 3 && key.all { it.isDigit() }) {
            val r = key.dropLast(2).toIntOrNull() ?: return null
            val c = key.takeLast(2).toIntOrNull() ?: return null
            return r to c
        }
        return null
    }

    private fun parseSlots(el: JsonElement?): List<TimeSlot> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            val o = item.asObject() ?: return@mapNotNull null
            val id = o["id"].asString()?.toIntOrNull() ?: return@mapNotNull null
            val value = o.string("value") ?: TimeText.minutesToHhmm(id)
            TimeSlot(id, value)
        }
    }

    private fun normalizeClock(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.replace(" ", "")
        return if (cleaned.matches(Regex("\\d{1,2}:\\d{2}"))) {
            val (h, m) = cleaned.split(":")
            "%02d:%s".format(h.toIntOrNull() ?: 0, m)
        } else {
            raw
        }
    }

    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

    private fun JsonElement?.asString(): String? {
        val p = this as? JsonPrimitive ?: return null
        return p.contentOrNull
    }

    private fun JsonElement?.asInt(): Int? {
        val p = this as? JsonPrimitive ?: return null
        return p.intOrNull ?: p.contentOrNull?.toIntOrNull()
    }

    private fun JsonElement?.asLong(): Long? {
        val p = this as? JsonPrimitive ?: return null
        return p.longOrNull ?: p.contentOrNull?.toLongOrNull()
    }

    private fun JsonObject.string(key: String): String? = this[key].asString()

    private fun JsonObject.int(key: String): Int? = this[key].asInt()

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36"
    }
}

fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()
