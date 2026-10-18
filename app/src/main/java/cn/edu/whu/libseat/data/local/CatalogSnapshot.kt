package cn.edu.whu.libseat.data.local

import cn.edu.whu.libseat.domain.model.Building
import cn.edu.whu.libseat.domain.model.FloorInfo
import cn.edu.whu.libseat.domain.model.RoomInfo
import kotlinx.serialization.Serializable

/**
 * 基础数据缓存：图书馆、房间、可预约日期。
 *
 * Ham 在进入图书馆页面时优先使用本地房间数据，再尝试从系统更新，
 * 这里保留同样的思路，避免每次进入首页都请求 filters。
 */
@Serializable
data class CatalogSnapshot(
    val source: String,
    val buildings: List<Building>,
    val rooms: List<RoomInfo>,
    val dates: List<String> = emptyList(),
    val updatedAt: Long = 0L,
    val floors: List<FloorInfo> = emptyList(),
) {
    companion object {
        const val SOURCE_DEMO = "demo"
        const val SOURCE_REAL = "real"
        const val SOURCE_JSQ = "jsq"
        const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }

    fun isFresh(now: Long = System.currentTimeMillis()): Boolean = now - updatedAt < MAX_AGE_MS

    fun floorsOf(buildingId: Long): List<Int> {
        if (floors.isNotEmpty()) {
            return floors.filter { it.buildingId == buildingId }
                .map { it.number }
                .distinct()
                .sorted()
        }
        return rooms.filter { it.buildingId == buildingId }.map { it.floor }.distinct().sorted()
    }

    fun floorInfo(buildingId: Long, number: Int): FloorInfo? =
        floors.firstOrNull { it.buildingId == buildingId && it.number == number }

    fun roomsOf(buildingId: Long, floor: Int?): List<RoomInfo> =
        rooms.filter { it.buildingId == buildingId && (floor == null || it.floor == floor) }
            .sortedBy { it.id }
}
