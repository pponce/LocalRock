package com.kodraliu.localrock.ui.vacuum

import androidx.compose.ui.graphics.Color
import com.kodraliu.localrock.shared.vacuum.map.ParsedMap

internal data class RoomMapColor(val pale: Color, val active: Color, val outline: Color)

internal class MapPalette(
    val wall: Color,
    val floor: Color,
    val rooms: List<RoomMapColor>,
    val roomOutline: Color,
    val path: Color,
    val charger: Color,
    val robot: Color,
    val markerRing: Color,
)

internal val LightMapPalette = MapPalette(
    wall = Color(0xFF9BAAAE), floor = Color(0xFFF0F3F2), roomOutline = Color(0xFFBDC8CA),
    rooms = listOf(
        RoomMapColor(Color(0xFFE4F0EE), Color(0xFFAFD4CE), Color(0xFF527E77)),
        RoomMapColor(Color(0xFFE8EFF8), Color(0xFFB5CEE9), Color(0xFF5A789D)),
        RoomMapColor(Color(0xFFEAF2E4), Color(0xFFC4DDB0), Color(0xFF6F875D)),
        RoomMapColor(Color(0xFFF5EEDD), Color(0xFFE8D39E), Color(0xFF98814C)),
        RoomMapColor(Color(0xFFEEE8F6), Color(0xFFCEBDE5), Color(0xFF80699D)),
        RoomMapColor(Color(0xFFE2F0F4), Color(0xFFAED4DE), Color(0xFF527D8A)),
        RoomMapColor(Color(0xFFF7EAE4), Color(0xFFE7C0AF), Color(0xFF9B7562)),
        RoomMapColor(Color(0xFFEDF1E5), Color(0xFFD2DDB8), Color(0xFF7C8964)),
    ),
    path = Color(0xB3768D96), charger = Color(0xFF3975AD), robot = Color(0xFF216C70), markerRing = Color.White,
)

internal val DarkMapPalette = MapPalette(
    wall = Color(0xFF66777D), floor = Color(0xFF293638), roomOutline = Color(0xFF4D6168),
    rooms = listOf(
        RoomMapColor(Color(0xFF314A47), Color(0xFF416D65), Color(0xFF263D38)),
        RoomMapColor(Color(0xFF344454), Color(0xFF486786), Color(0xFF273A50)),
        RoomMapColor(Color(0xFF3A4C38), Color(0xFF526D49), Color(0xFF2C4026)),
        RoomMapColor(Color(0xFF514A38), Color(0xFF786B48), Color(0xFF453D28)),
        RoomMapColor(Color(0xFF463F52), Color(0xFF68597F), Color(0xFF3D324E)),
        RoomMapColor(Color(0xFF314A50), Color(0xFF416D79), Color(0xFF263F48)),
        RoomMapColor(Color(0xFF52423D), Color(0xFF805E50), Color(0xFF4D352C)),
        RoomMapColor(Color(0xFF454C39), Color(0xFF64714A), Color(0xFF394228)),
    ),
    path = Color(0xB3D5E3E5), charger = Color(0xFF82B5E0), robot = Color(0xFF80D0CD), markerRing = Color(0xFF172327),
)

/** Use only scanned room floor, never a room id in an empty cell or the robot's position. */
internal fun ParsedMap.roomIdAtCell(x: Int, y: Int): Int? {
    if (x !in 0 until width || y !in 0 until height) return null
    val index = y * width + x
    val raw = originalGrid
    if (raw != null) {
        val value = raw[index].toInt() and 255
        return if (value and 7 == 7) (value ushr 3).takeIf { it > 0 } else null
    }
    return (grid[index].toInt() and 255).takeIf { it in 1..126 }
}

/** Edges of the actual grid cells; no smoothing, dilation or removal of small obstacles. */
internal inline fun ParsedMap.forEachRoomEdge(draw: (id: Int, x0: Float, y0: Float, x1: Float, y1: Float) -> Unit) {
    for (y in 0 until height) for (x in 0 until width) {
        val id = roomIdAtCell(x, y) ?: continue
        val top = (height - 1 - y).toFloat()
        if (roomIdAtCell(x - 1, y) != id) draw(id, x.toFloat(), top, x.toFloat(), top + 1)
        if (roomIdAtCell(x + 1, y) != id) draw(id, x + 1f, top, x + 1f, top + 1)
        if (roomIdAtCell(x, y + 1) != id) draw(id, x.toFloat(), top, x + 1f, top)
        if (roomIdAtCell(x, y - 1) != id) draw(id, x.toFloat(), top + 1, x + 1f, top + 1)
    }
}
