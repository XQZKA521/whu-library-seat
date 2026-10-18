package cn.edu.whu.libseat

import cn.edu.whu.libseat.data.remote.JsqLibraryApi
import cn.edu.whu.libseat.domain.model.FloorInfo
import cn.edu.whu.libseat.domain.model.SeatStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC 端模拟新版座位系统的真实返回结构，验证解析链路。
 * 不依赖 Android / 模拟器 / 网络。
 */
class JsqParserTest {

    private val api = JsqLibraryApi(
        client = OkHttpClient(),
        baseUrlProvider = { "https://seat.lib.whu.edu.cn" },
    )

    @Test
    fun catalogParsesRealBuildingFloorShape() {
        val json = Json.parseToJsonElement(
            """
            {
              "buildings": [
                {"id": "1812737769937670144", "name": "总馆", "floors": [
                  {"id": "1812741698201161728", "buildingId": "1812737769937670144", "name": "1楼"},
                  {"id": "1812742101630291968", "buildingId": "1812737769937670144", "name": "2楼"},
                  {"id": "1812742169687068672", "buildingId": "1812737769937670144", "name": "3楼"}
                ]},
                {"id": "1812738485913751552", "name": "信息分馆", "floors": [
                  {"id": "1812743000000000001", "buildingId": "1812738485913751552", "name": "1楼"}
                ]}
              ],
              "dates": ["2026-10-18", "2026-10-19"]
            }
            """.trimIndent()
        )
        val catalog = api.parseCatalogData(json)
        assertEquals(2, catalog.buildings.size)
        assertEquals(1812737769937670144L, catalog.buildings[0].id)
        assertEquals("总馆", catalog.buildings[0].name)
        assertEquals(3, catalog.buildings[0].floorCount)
        assertEquals(
            1812741698201161728L,
            catalog.floors.first { it.buildingId == 1812737769937670144L }.id,
        )
        assertEquals(listOf(1, 2, 3), catalog.floors.filter { it.buildingId == 1812737769937670144L }.map { it.number })
        assertEquals(listOf("2026-10-18", "2026-10-19"), catalog.dates)
    }

    @Test
    fun roomsPageListParsesRealRoomShape() {
        val floor = FloorInfo(1812742257205415936L, 1812737769937670144L, "4楼", 4)
        val json = Json.parseToJsonElement(
            """
            {
              "showPage": [1], "currentPage": 1, "pageSize": 100,
              "totalCount": 2, "totalPage": 1,
              "pageList": [
                {"id": "1812762179998420992", "buildingId": "1812737769937670144",
                 "floorId": "1812742257205415936", "name": "A4",
                 "seatTotal": 354, "seatFree": 293},
                {"id": "1812762375121637376", "buildingId": "1812737769937670144",
                 "floorId": "1812742257205415936", "name": "B4(现场选座)",
                 "seatTotal": 44, "seatFree": 31}
              ]
            }
            """.trimIndent()
        )
        val rooms = api.parseRoomsPage(json, 1812737769937670144L, floor, listOf(floor))
        assertEquals(2, rooms.size)
        assertEquals(1812762179998420992L, rooms[0].room.id)
        assertEquals("A4", rooms[0].room.name)
        assertEquals(354, rooms[0].total)
        assertEquals(293, rooms[0].free)
        assertTrue(rooms[0].countsKnown)
        assertEquals(4, rooms[0].room.floor)
    }

    @Test
    fun roomsWithoutCountFieldsStillReturned() {
        val floor = FloorInfo(11L, 1L, "1楼", 1)
        val json = Json.parseToJsonElement(
            """{"pageList": [{"roomId": "103", "roomName": "1楼 C区", "floorId": "11"}]}"""
        )
        val rooms = api.parseRoomsPage(json, 1L, floor, listOf(floor))
        assertEquals(1, rooms.size)
        assertEquals(103L, rooms[0].room.id)
        assertFalse(rooms[0].countsKnown)
    }

    @Test
    fun roomQueryBodyUsesStringFloorIdAndPaging() {
        val body = api.roomQueryBody(1812742257205415936L)
        assertEquals(1, body["currentPage"]!!.jsonPrimitive.int)
        assertEquals(100, body["pageSize"]!!.jsonPrimitive.int)
        assertEquals("1812742257205415936", body["floorId"]!!.jsonPrimitive.content)
        assertEquals(0, body["minMinute"]!!.jsonPrimitive.int)
        assertEquals("", body["sortField"]!!.jsonPrimitive.content)
    }

    @Test
    fun plainLayoutWithVersionMarkerDecodes() {
        val raw = """{"objects":[]}_updVersion_5.2.1"""
        assertEquals("""{"objects":[]}""", api.decodeLayoutPayload(raw))
    }

    @Test
    fun seatLayoutParsesRealFabricObjectsAndStatusMap() {
        val layout = """
            {
              "version": "5.2.1",
              "objects": [
                {"type":"labeledImage","left":95.2632814185041,"top":100.08636743859755,
                 "width":90,"height":90,"scaleX":0.12827216585187845,"scaleY":0.12827216585187853,
                 "seat":{"label":"002","name":"3行1列","direction":"left",
                          "id":"1814198114095042560","lock":false,"scene":false,
                          "power":true,"computer":false,"window":true}},
                {"type":"labeledImage","left":108.0,"top":100.0,
                 "width":90,"height":90,"scaleX":0.12827216585187845,"scaleY":0.12827216585187853,
                 "seat":{"label":"004","name":"1行1列","direction":"left",
                          "id":"1814198114095042561","lock":false,"scene":false,
                          "power":false,"computer":false,"window":false}},
                {"type":"labeledImage","left":95.2632814185041,"top":112.8,
                 "width":90,"height":90,"scaleX":0.12827216585187845,"scaleY":0.12827216585187853,
                 "seat":{"label":"003","name":"2行1列","direction":"left",
                          "id":"1814198114095042562","lock":false,"scene":false,
                          "power":false,"computer":false,"window":false}},
                {"type":"i-text","left":0,"top":0,"text":"4楼"}
              ]
            }
        """.trimIndent()
        val statuses = mapOf(
            1814198114095042560L to SeatStatus.FREE,
            1814198114095042561L to SeatStatus.IN_USE,
            1814198114095042562L to SeatStatus.AWAY,
        )
        val map = api.parseSeatMapJson(layout, 1812762179998420992L, statuses)
        assertEquals(3, map.seats.size)
        assertEquals(SeatStatus.FREE, map.seatById(1814198114095042560L)!!.status)
        assertEquals(SeatStatus.IN_USE, map.seatById(1814198114095042561L)!!.status)
        assertEquals(SeatStatus.AWAY, map.seatById(1814198114095042562L)!!.status)
        assertTrue(map.seatById(1814198114095042560L)!!.nearWindow)
        assertTrue(map.seatById(1814198114095042560L)!!.hasPower)
        // 座位实际宽度 90 * 0.128 ≈ 11.5 -> 步长 12，三座落在 2x2 网格
        assertEquals("rows=${map.rows} cols=${map.cols} seats=${map.seats.map { it.row to it.col }}", 2, map.rows)
        assertEquals(2, map.cols)
        assertEquals(1, map.seatById(1814198114095042560L)!!.row)
        assertEquals(1, map.seatById(1814198114095042560L)!!.col)
    }

    @Test
    fun reservationStatusMapsCheckIn() {
        val json = Json.parseToJsonElement(
            """
            {"id":123,"makeStatus":"CHECK_IN","makeDate":"2026-10-18",
             "makeBeginStr":"13:07","makeEndStr":"18:00",
             "location":"总馆|4楼|A4","seatLabel":"A4",
             "seatId":"1814198114095042560"}
            """.trimIndent()
        ).jsonObject
        val reservation = api.parseReservation(json)
        assertNotNull(reservation)
        assertEquals("IN_USE", reservation!!.status)
        assertEquals("13:07", reservation.begin)
        assertEquals("18:00", reservation.end)
        assertEquals("总馆|4楼|A4", reservation.location)
        assertEquals(1814198114095042560L, reservation.seatId)
    }

    @Test
    fun real354SeatLayoutParsesWithNoCellCollision() {
        val layout = javaClass.classLoader!!.getResource("real_layout.json")!!.readText()
        val statusJson = Json.parseToJsonElement(
            javaClass.classLoader!!.getResource("real_statuses.json")!!.readText()
        )
        val statuses = api.parseSeatStatusMap(statusJson)
        assertEquals(354, statuses.size)

        val map = api.parseSeatMapJson(layout, 1812762179998420992L, statuses)
        assertEquals(354, map.seats.size)
        assertEquals(293, map.seats.count { it.status == SeatStatus.FREE })
        assertEquals(59, map.seats.count { it.status == SeatStatus.IN_USE })
        assertEquals(2, map.seats.count { it.status == SeatStatus.AWAY })
        // 354 个座位必须落在 354 个不同格子里，不能因网格步长丢座
        assertEquals(354, map.seats.map { it.row to it.col }.toSet().size)
        assertTrue(map.rows >= 20)
        assertTrue(map.cols >= 15)
    }

    @Test
    fun realCurrentReservationParsesCheckIn() {
        val json = Json.parseToJsonElement(
            """
            {"id":"2111685580542832640","roomId":"1812762179998420992",
             "seatId":"1814198114095042639","seatLabel":"077","status":"CHECK_IN",
             "makeDate":"1792252800000","makeDateStr":"2026-10-18",
             "makeBegin":787,"makeBeginStr":"13:07","makeEnd":1080,"makeEndStr":"18:00",
             "location":"总馆|4楼|A4","receipt":"1009-0640-077",
             "message":"用户通过闸机签到(在馆未出)"}
            """.trimIndent()
        ).jsonObject
        val reservation = api.parseReservation(json)!!
        assertEquals(2111685580542832640L, reservation.id)
        assertEquals(1814198114095042639L, reservation.seatId)
        assertEquals("IN_USE", reservation.status)
        assertEquals("2026-10-18", reservation.date)
        assertEquals("13:07", reservation.begin)
        assertEquals("18:00", reservation.end)
        assertEquals("总馆|4楼|A4", reservation.location)
    }

    @Test
    fun realCatalogAndRoomsResponseParse() {
        val catalog = api.parseCatalogData(
            Json.parseToJsonElement(resource("real_catalog.json"))
        )
        assertEquals(4, catalog.buildings.size)
        val main = catalog.buildings.first { it.name == "总馆" }
        assertEquals(1812737769937670144L, main.id)
        assertEquals(7, catalog.floors.count { it.buildingId == main.id })
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), catalog.floors.filter { it.buildingId == main.id }.map { it.number })

        val floor4 = catalog.floors.first { it.buildingId == main.id && it.number == 4 }
        val rooms = api.parseRoomsPage(
            Json.parseToJsonElement(resource("real_rooms.json")),
            main.id,
            floor4,
            catalog.floors,
        )
        assertEquals(4, rooms.size)
        val a4 = rooms.first { it.room.name == "A4" }
        assertEquals(354, a4.total)
        assertEquals(293, a4.free)
        assertEquals(4, a4.room.floor)
    }

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResource(name)!!.readText()

    @Test
    fun timePairsParse() {
        val json = Json.parseToJsonElement("""[["600","10:00"],["660","11:00"]]""")
        val slots = api.parseTimePairs(json)
        assertEquals(2, slots.size)
        assertEquals(600, slots[0].minutes)
        assertEquals("11:00", slots[1].label)
    }

    @Test
    fun compressedSeatLayoutEndToEnd() {
        val compressed =
            "N4Ig9gRgVgpgxgFwM4gFwG1QIJ4AcZogA2AhhDETACYCSAtiQOYEA0xMAZgmgIwBMABjYIwuNAA4hIJDBLdUoAJZVC4gJw8BINqXJFCAgT23SEcgK4pUIAGIAlAKIOTAd0UA7KmBdoEAJ3MYNlxvGD80DhIiGTYqRT94BEUwd0IIMAQROhAAXxyAXTZ3EjoCawAWG0B0ZUAuZVygA==="
        val layoutJson = cn.edu.whu.libseat.core.codec.LzString.decompressFromBase64(compressed)!!
        val map = api.parseSeatMapJson(layoutJson, 21L, mapOf(8910L to SeatStatus.FREE))
        assertEquals(1, map.seats.size)
        assertEquals("001", map.seats[0].name)
        assertEquals(SeatStatus.FREE, map.seats[0].status)
        assertTrue(map.seats[0].nearWindow)
    }
}
