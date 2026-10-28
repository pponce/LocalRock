package com.kodraliu.localrock.ui.vacuum

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import com.kodraliu.localrock.shared.vacuum.map.MapFurniture
import com.kodraliu.localrock.shared.vacuum.map.ParsedMap
import kotlin.math.cos
import kotlin.math.sin

/** Uses the shared map origin and Y flip, including for rotated furniture. */
internal fun furnitureCorners(map: ParsedMap, item: MapFurniture): List<Offset> =
    item.cornersMm.map { map.mmToCell(it.x, it.y) }

internal enum class FurnitureSymbol {
    SOFA, DOUBLE_SOFA, ARMCHAIR, CHAISE_LEFT, CHAISE_RIGHT, DINING_SET, BED, WIDE_BED,
    TV_STAND, SQUARE_TABLE, COFFEE_TABLE, SHOE_RACK, WARDROBE, NIGHTSTAND,
    LITTER_BOX, COVERED_LITTER_BOX, PET_CAGE, PET_BED, PET_BOWLS, MIRROR, CAT_TOWER, UNKNOWN,
}

/** All 21 type/subtype pairs were observed in the OEM picker and save commands. */
internal fun furnitureSymbol(type: Int, subtype: Int): FurnitureSymbol = when (type to subtype) {
    45 to 2 -> FurnitureSymbol.WIDE_BED
    45 to 1 -> FurnitureSymbol.BED
    43 to 0 -> FurnitureSymbol.TV_STAND
    46 to 3 -> FurnitureSymbol.SOFA
    46 to 2 -> FurnitureSymbol.DOUBLE_SOFA
    46 to 1 -> FurnitureSymbol.ARMCHAIR
    46 to 4 -> FurnitureSymbol.CHAISE_LEFT
    46 to 5 -> FurnitureSymbol.CHAISE_RIGHT
    47 to 0 -> FurnitureSymbol.DINING_SET
    48 to 2 -> FurnitureSymbol.SQUARE_TABLE
    48 to 1 -> FurnitureSymbol.COFFEE_TABLE
    49 to 0 -> FurnitureSymbol.SHOE_RACK
    51 to 0 -> FurnitureSymbol.WARDROBE
    50 to 0 -> FurnitureSymbol.NIGHTSTAND
    52 to 0 -> FurnitureSymbol.LITTER_BOX
    53 to 0 -> FurnitureSymbol.COVERED_LITTER_BOX
    54 to 0 -> FurnitureSymbol.PET_CAGE
    55 to 0 -> FurnitureSymbol.PET_BED
    56 to 0 -> FurnitureSymbol.PET_BOWLS
    57 to 0 -> FurnitureSymbol.MIRROR
    58 to 0 -> FurnitureSymbol.CAT_TOWER
    else -> FurnitureSymbol.UNKNOWN
}

/** Bilinear detail coordinates keep the record's exact footprint and corner order. */
internal fun furniturePoint(corners: List<Offset>, x: Float, y: Float): Offset =
    corners[0] * ((1 - x) * (1 - y)) + corners[1] * (x * (1 - y)) +
        corners[2] * (x * y) + corners[3] * ((1 - x) * y)

internal fun drawMapFurniture(canvas: Canvas, map: ParsedMap, dark: Boolean) {
    map.furniture.filter { it.isVisible }.forEach {
        drawFurnitureSymbol(canvas, furnitureCorners(map, it), it.type, it.subtype, dark)
    }
}

/** Shared LocalRock vector artwork for the map, editor and catalog; no OEM assets. */
internal fun drawFurnitureSymbol(
    canvas: Canvas, corners: List<Offset>, type: Int, subtype: Int, dark: Boolean, strokeWidth: Float = .5f,
) {
    val stroke = if (dark) Color(0xFFE0E7E8) else Color(0xFF485C64)
    val fabric = if (dark) Color(0xFF6B8794) else Color(0xFFAAC7D1)
    val cushion = if (dark) Color(0xFF9CB2BB) else Color(0xFFDCE9ED)
    val wood = if (dark) Color(0xFFA68A66) else Color(0xFFD6B382)
    val glass = if (dark) Color(0xFF81B5BD) else Color(0xFFCCE8E9)
    val fill = Paint().apply { isAntiAlias = true }
    val outline = Paint().apply {
        color = stroke; style = PaintingStyle.Stroke; this.strokeWidth = strokeWidth; isAntiAlias = true
    }
    fun shape(points: List<Pair<Float, Float>>, color: Color) {
        val path = Path()
        points.map { (x, y) -> furniturePoint(corners, x, y) }.forEachIndexed { index, p ->
            if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        path.close(); fill.color = color
        canvas.drawPath(path, fill); canvas.drawPath(path, outline)
    }
    fun box(left: Float, top: Float, right: Float, bottom: Float, color: Color) =
        shape(listOf(left to top, right to top, right to bottom, left to bottom), color)
    fun oval(left: Float, top: Float, right: Float, bottom: Float, color: Color) =
        shape((0 until 32).map { i ->
            val angle = i * 2 * kotlin.math.PI / 32
            ((left + right) / 2 + cos(angle).toFloat() * (right - left) / 2) to
                ((top + bottom) / 2 + sin(angle).toFloat() * (bottom - top) / 2)
        }, color)
    val symbol = furnitureSymbol(type, subtype)
    when (symbol) {
        FurnitureSymbol.SOFA, FurnitureSymbol.DOUBLE_SOFA, FurnitureSymbol.ARMCHAIR,
        FurnitureSymbol.CHAISE_LEFT, FurnitureSymbol.CHAISE_RIGHT -> {
            val chaise = symbol == FurnitureSymbol.CHAISE_LEFT || symbol == FurnitureSymbol.CHAISE_RIGHT
            val bottom = if (chaise) .66f else .94f
            box(.04f, .08f, .96f, bottom, fabric)
            box(.1f, .08f, .9f, .27f, cushion)
            val seats = when (symbol) { FurnitureSymbol.ARMCHAIR -> 1; FurnitureSymbol.DOUBLE_SOFA -> 2; else -> 3 }
            for (i in 0 until seats) {
                val left = .2f + i * .6f / seats
                box(left, .32f, left + .6f / seats, bottom - .06f, cushion)
            }
            box(.04f, .24f, .18f, bottom, fabric)
            box(.82f, .24f, .96f, bottom, fabric)
            if (chaise) {
                val left = if (symbol == FurnitureSymbol.CHAISE_LEFT) .04f else .66f
                box(left, .4f, left + .3f, .96f, fabric)
                box(left + .04f, .44f, left + .26f, .91f, cushion)
            }
        }
        FurnitureSymbol.BED, FurnitureSymbol.WIDE_BED -> {
            box(.08f, .02f, .92f, .98f, wood)
            box(.14f, .1f, .86f, .94f, cushion)
            if (symbol == FurnitureSymbol.WIDE_BED) {
                box(.2f, .14f, .47f, .34f, Color(0xFFF4F1E9))
                box(.53f, .14f, .8f, .34f, Color(0xFFF4F1E9))
            } else box(.25f, .14f, .75f, .34f, Color(0xFFF4F1E9))
            box(.14f, .4f, .86f, .94f, fabric)
            box(.14f, .4f, .86f, .49f, cushion)
        }
        FurnitureSymbol.DINING_SET -> {
            for (top in listOf(.2f, .62f)) {
                box(.02f, top, .23f, top + .18f, cushion)
                box(.77f, top, .98f, top + .18f, cushion)
            }
            box(.36f, .02f, .64f, .18f, cushion)
            box(.36f, .82f, .64f, .98f, cushion)
            box(.24f, .2f, .76f, .8f, wood)
        }
        FurnitureSymbol.TV_STAND -> {
            box(.02f, .05f, .98f, .95f, wood)
            box(.07f, .5f, .48f, .9f, cushion); box(.52f, .5f, .93f, .9f, cushion)
            box(.18f, .12f, .82f, .32f, stroke); box(.44f, .32f, .56f, .52f, stroke)
        }
        FurnitureSymbol.SQUARE_TABLE -> {
            box(.06f, .06f, .94f, .94f, wood); box(.14f, .14f, .86f, .86f, cushion)
        }
        FurnitureSymbol.COFFEE_TABLE -> {
            oval(.02f, .06f, .98f, .94f, wood); oval(.12f, .18f, .88f, .82f, glass)
        }
        FurnitureSymbol.SHOE_RACK -> {
            box(.03f, .05f, .97f, .95f, wood)
            for (y in listOf(.12f, .4f, .68f)) {
                box(.1f, y, .9f, y + .2f, cushion)
                oval(.16f, y + .03f, .39f, y + .17f, fabric)
                oval(.55f, y + .03f, .78f, y + .17f, fabric)
            }
        }
        FurnitureSymbol.WARDROBE -> {
            box(.02f, .04f, .98f, .96f, wood)
            box(.07f, .1f, .49f, .9f, cushion); box(.51f, .1f, .93f, .9f, cushion)
            box(.41f, .38f, .44f, .62f, stroke); box(.56f, .38f, .59f, .62f, stroke)
        }
        FurnitureSymbol.NIGHTSTAND -> {
            box(.05f, .05f, .95f, .95f, wood)
            for (y in listOf(.14f, .52f)) {
                box(.12f, y, .88f, y + .32f, cushion)
                oval(.44f, y + .1f, .56f, y + .21f, stroke)
            }
        }
        FurnitureSymbol.LITTER_BOX -> {
            oval(.05f, .03f, .95f, .97f, fabric); oval(.16f, .14f, .84f, .86f, wood)
            for (x in listOf(.34f, .56f)) oval(x, .4f, x + .1f, .51f, cushion)
        }
        FurnitureSymbol.COVERED_LITTER_BOX -> {
            oval(.04f, .03f, .96f, .97f, fabric)
            oval(.17f, .14f, .83f, .87f, cushion); oval(.3f, .53f, .7f, .94f, stroke)
        }
        FurnitureSymbol.PET_CAGE -> {
            box(.03f, .03f, .97f, .97f, cushion)
            for (p in listOf(.22f, .42f, .62f, .82f)) {
                box(p, .05f, p + .025f, .95f, fabric)
                box(.05f, p, .95f, p + .025f, fabric)
            }
            box(.3f, .75f, .7f, .97f, wood)
        }
        FurnitureSymbol.PET_BED -> {
            oval(.02f, .04f, .98f, .96f, fabric); oval(.15f, .18f, .85f, .82f, cushion)
            oval(.39f, .4f, .61f, .64f, fabric)
        }
        FurnitureSymbol.PET_BOWLS -> {
            oval(.02f, .08f, .48f, .92f, wood); oval(.52f, .08f, .98f, .92f, wood)
            oval(.1f, .23f, .4f, .77f, glass); oval(.6f, .23f, .9f, .77f, fabric)
        }
        FurnitureSymbol.MIRROR -> {
            box(.06f, .06f, .94f, .94f, wood); box(.14f, .14f, .86f, .86f, glass)
            shape(listOf(.18f to .18f, .6f to .18f, .18f to .72f), Color.White.copy(alpha = .6f))
        }
        FurnitureSymbol.CAT_TOWER -> {
            box(.04f, .04f, .96f, .96f, wood)
            oval(.1f, .1f, .58f, .58f, cushion); oval(.5f, .46f, .93f, .9f, fabric)
            oval(.25f, .26f, .41f, .42f, wood); oval(.65f, .6f, .79f, .74f, wood)
            box(.16f, .64f, .42f, .91f, fabric)
        }
        FurnitureSymbol.UNKNOWN -> box(0f, 0f, 1f, 1f, stroke.copy(alpha = .2f))
    }
}
