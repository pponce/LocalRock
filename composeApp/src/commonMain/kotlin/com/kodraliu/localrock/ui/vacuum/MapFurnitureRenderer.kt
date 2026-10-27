package com.kodraliu.localrock.ui.vacuum

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import com.kodraliu.localrock.shared.vacuum.map.MapFurniture
import com.kodraliu.localrock.shared.vacuum.map.ParsedMap

/** The same mm -> cell transform as restrictions, paths and the robot, including the Y flip. */
internal fun furnitureCorners(map: ParsedMap, item: MapFurniture): List<Offset> =
    item.cornersMm.map { map.mmToCell(it.x, it.y) }

/**
 * Neutral footprints deliberately avoid a guessed furniture catalogue. All four corners are used
 * so an angled chair remains angled. Translucent fill leaves scanned geometry visible beneath it.
 */
internal fun drawMapFurniture(canvas: Canvas, map: ParsedMap, dark: Boolean) {
    val fill = Paint().apply { color = if (dark) Color(0x333E494B) else Color(0x33566569) }
    val outline = Paint().apply {
        color = if (dark) Color(0xFF98AAAE) else Color(0xFF687D82)
        style = PaintingStyle.Stroke
        strokeWidth = 0.65f
        isAntiAlias = true
    }
    map.furniture.filter { it.isVisible }.forEach { item ->
        val corners = furnitureCorners(map, item)
        val path = Path().apply {
            moveTo(corners[0].x, corners[0].y)
            corners.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, outline)
    }
}
