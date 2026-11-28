package com.chronocapsule

import com.chronocapsule.ocr.TrailingPlaceholder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailingPlaceholderTest {
    private val width = 32
    private val height = 26
    private val left = 12
    private val right = 25
    private val top = 3
    private val bottom = 22

    private fun image(dark: Boolean = false, paint: (Int, Int) -> Boolean): IntArray {
        val ink = if (dark) 0x00f7eee9 else 0x0024192e
        val background = if (dark) 0x00201a29 else 0x00e4dbfa
        return IntArray(width * height) { i ->
            if (paint(i % width, i / width)) ink else background
        }
    }

    private fun outline(x: Int, y: Int) =
        (x in left..right && (y == top || y == bottom)) ||
            (y in top..bottom && (x == left || x == right))

    @Test fun findsHollowBoxOnePixelFromRealTextInBothThemes() {
        for (dark in listOf(false, true)) {
            val pixels = image(dark) { x, y -> outline(x, y) || (x == left - 2 && y in 4..22) }
            assertEquals(left, TrailingPlaceholder.find(pixels, width, height))
        }
    }

    @Test fun leavesRoundedOrAngledZeroInPlace() {
        val roundedZero = image { x, y ->
            outline(x, y) && !((x == left || x == right) && (y == top || y == bottom))
        }
        assertNull(TrailingPlaceholder.find(roundedZero, width, height))
    }

    @Test fun leavesBoxedTextAndTouchingGlyphsInPlace() {
        val boxedText = image { x, y -> outline(x, y) || (x == 18 && y in 7..18) }
        val touchingGlyph = image { x, y -> outline(x, y) || (x in 3 until left && y == 12) }
        assertNull(TrailingPlaceholder.find(boxedText, width, height))
        assertNull(TrailingPlaceholder.find(touchingGlyph, width, height))
    }
}
