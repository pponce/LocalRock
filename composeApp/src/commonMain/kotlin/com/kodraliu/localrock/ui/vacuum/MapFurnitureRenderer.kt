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

internal enum class FurnitureSymbol { SOFA, ARMCHAIR, DINING_SET, BED, UNKNOWN }

/** These exact legacy type/subtype pairs were matched against S7 MaxV OEM furniture. */
internal fun furnitureSymbol(type: Int, subtype: Int): FurnitureSymbol = when (type to subtype) {
    46 to 3 -> FurnitureSymbol.SOFA
    46 to 1 -> FurnitureSymbol.ARMCHAIR
    47 to 0 -> FurnitureSymbol.DINING_SET
    45 to 1 -> FurnitureSymbol.BED
    else -> FurnitureSymbol.UNKNOWN
}

/** Preserve all four corners, rotation and saved dimensions, without inventing a physical size. */
internal fun furniturePoint(corners: List<Offset>, x: Float, y: Float): Offset =
    corners[0] * ((1 - x) * (1 - y)) + corners[1] * (x * (1 - y)) +
        corners[2] * (x * y) + corners[3] * ((1 - x) * y)

/** Read-only top-down symbols. Unknown types retain their neutral footprint. */
internal fun drawMapFurniture(canvas: Canvas, map: ParsedMap, dark: Boolean) {
    val stroke = if (dark) Color(0xFFE0E7E8) else Color(0xFF485C64)
    val fabric = if (dark) Color(0xFF6B8794) else Color(0xFFAAC7D1)
    val cushion = if (dark) Color(0xFF9CB2BB) else Color(0xFFDCE9ED)
    val wood = if (dark) Color(0xFFA68A66) else Color(0xFFD6B382)
    val fill = Paint().apply { isAntiAlias = true }
    val outline = Paint().apply {
        color = stroke; style = PaintingStyle.Stroke; strokeWidth = .5f; isAntiAlias = true
    }
    map.furniture.filter { it.isVisible }.forEach { item ->
        val corners = furnitureCorners(map, item)
        fun box(left: Float, top: Float, right: Float, bottom: Float, color: Color) {
            val points = listOf(left to top, right to top, right to bottom, left to bottom)
                .map { (x, y) -> furniturePoint(corners, x, y) }
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            fill.color = color
            canvas.drawPath(path, fill)
            canvas.drawPath(path, outline)
        }
        when (furnitureSymbol(item.type, item.subtype)) {
            FurnitureSymbol.SOFA, FurnitureSymbol.ARMCHAIR -> {
                box(.04f, .08f, .96f, .94f, fabric)
                // Back, upholstered seats and two arms distinguish seating from a plain rectangle.
                box(.1f, .08f, .9f, .27f, cushion)
                val seats = if (furnitureSymbol(item.type, item.subtype) == FurnitureSymbol.SOFA) 3 else 1
                for (i in 0 until seats) {
                    val left = .2f + i * .6f / seats
                    box(left, .32f, left + .6f / seats, .87f, cushion)
                }
                box(.04f, .24f, .18f, .94f, fabric)
                box(.82f, .24f, .96f, .94f, fabric)
            }
            FurnitureSymbol.BED -> {
                box(.08f, .02f, .92f, .98f, wood)
                box(.14f, .1f, .86f, .94f, cushion)
                box(.2f, .14f, .47f, .34f, Color(0xFFF4F1E9))
                box(.53f, .14f, .8f, .34f, Color(0xFFF4F1E9))
                box(.14f, .4f, .86f, .94f, fabric)
                box(.14f, .4f, .86f, .49f, cushion)
            }
            FurnitureSymbol.DINING_SET -> {
                // Four side chairs and two end chairs fit within the saved dining-set footprint.
                for (top in listOf(.2f, .62f)) {
                    box(.02f, top, .23f, top + .18f, cushion)
                    box(.77f, top, .98f, top + .18f, cushion)
                }
                box(.36f, .02f, .64f, .18f, cushion)
                box(.36f, .82f, .64f, .98f, cushion)
                box(.24f, .2f, .76f, .8f, wood)
            }
            FurnitureSymbol.UNKNOWN -> box(0f, 0f, 1f, 1f, stroke.copy(alpha = .2f))
        }
    }
}
