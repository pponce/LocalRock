package com.kodraliu.localrock.ui.vacuum

import androidx.compose.ui.geometry.Offset
import com.kodraliu.localrock.shared.vacuum.map.MapFurniture
import com.kodraliu.localrock.shared.vacuum.map.ParsedMap
import kotlin.test.Test
import kotlin.test.assertEquals

class MapFurnitureGeometryTest {
    @Test
    fun rotated_furniture_uses_the_map_origin_and_y_flip() {
        val coords = listOf(150, 350, 250, 250, 150, 150, 50, 250)
        val bytes = coords.flatMap { listOf(it and 255, it ushr 8) } + listOf(0, 0, 46, 1, 1, 5, 1)
        val map = ParsedMap(20, 10, ByteArray(200), emptyList(), 0.05f, pixelOffsetLeft = 1, pixelOffsetTop = 2)
        assertEquals(
            listOf(Offset(2f, 4f), Offset(4f, 6f), Offset(2f, 8f), Offset(0f, 6f)),
            furnitureCorners(map, MapFurniture(bytes)),
        )
    }
}
