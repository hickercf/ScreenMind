package com.chronocapsule.ocr

import kotlin.math.abs

/** Locates the left edge of a detached, hollow missing-glyph box at the right end of a text line. */
internal object TrailingPlaceholder {
    private const val BACKGROUND_TOLERANCE = 24
    private const val INK_CONTRAST = 65

    fun find(pixels: IntArray, width: Int, height: Int): Int? {
        val background = pixels[width - 1]
        if (difference(pixels.last(), background) > BACKGROUND_TOLERANCE) return null

        val ink = BooleanArray(pixels.size) { difference(pixels[it], background) > INK_CONTRAST }
        fun hasInk(x: Int, y: Int): Boolean = ink[y * width + x]
        val columns = BooleanArray(width) { x -> (0 until height).any { y -> hasInk(x, y) } }
        val right = columns.indexOfLast { it }
        if (right !in 1 until width - 1) return null
        val gapEnd = (right - 1 downTo 0).firstOrNull { !columns[it] } ?: return null
        val left = gapEnd + 1
        if (gapEnd == 0) return null

        val top = (0 until height).first { hasInk(left, it) }
        val bottom = (height - 1 downTo 0).first { hasInk(left, it) }
        val glyphWidth = right - left + 1
        val glyphHeight = bottom - top + 1
        if (glyphWidth < 9 || glyphHeight < 12 || glyphWidth * 10 !in glyphHeight * 6..glyphHeight * 9 ||
            top == 0 || bottom == height - 1
        ) return null

        val horizontalEdges = (left..right).all { hasInk(it, top) && hasInk(it, bottom) }
        val verticalEdges = (top..bottom).all { hasInk(left, it) && hasInk(right, it) }
        if (!horizontalEdges || !verticalEdges ||
            (left..right).any { x -> (0 until top).any { hasInk(x, it) } ||
                (bottom + 1 until height).any { hasInk(x, it) } }
        ) return null

        // Leave only room for the strokes of the outline. A filled icon or a
        // character inside a frame is meaningful image content and should stay.
        val inset = minOf(glyphWidth, glyphHeight) / 4
        val emptyCenter = (left + inset..right - inset).all { x ->
            (top + inset..bottom - inset).none { y -> hasInk(x, y) }
        }
        return left.takeIf { emptyCenter }
    }

    private fun difference(a: Int, b: Int): Int = maxOf(
        abs((a shr 16 and 255) - (b shr 16 and 255)),
        abs((a shr 8 and 255) - (b shr 8 and 255)),
        abs((a and 255) - (b and 255)),
    )
}
