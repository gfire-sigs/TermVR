package dev.gfire.termvr.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QrCodeEncoderTest {
    @Test
    fun encodesLoopbackAndLanUrlsWithValidFinderAndTimingPatterns() {
        val url = "http://192.168.1.42:8080"
        val matrix = QrCodeEncoder.encode(url)

        assertEquals(matrix.version * 4 + 17, matrix.size)
        assertTrue(matrix.version >= 2)
        assertFinderSquare(matrix, topRow = 0, leftCol = 0)
        assertFinderSquare(matrix, topRow = 0, leftCol = matrix.size - 7)
        assertFinderSquare(matrix, topRow = matrix.size - 7, leftCol = 0)

        // Fixed dark module at (4 * version + 9, 8)
        assertTrue(matrix.isDark(4 * matrix.version + 9, 8))

        // Alternating timing patterns at row 6 and col 6 between finders
        for (i in 8 until matrix.size - 8) {
            assertEquals(i % 2 == 0, matrix.isDark(6, i))
            assertEquals(i % 2 == 0, matrix.isDark(i, 6))
        }
    }

    @Test
    fun encodesShortPayloadIntoVersionOneAndRejectsOversizePayload() {
        val v1 = QrCodeEncoder.encode("http://1.1:8")
        assertEquals(1, v1.version)
        assertEquals(21, v1.size)

        val repeatLong = "a".repeat(300)
        assertThrows(IllegalArgumentException::class.java) {
            QrCodeEncoder.encode(repeatLong)
        }
    }

    private fun assertFinderSquare(matrix: QrMatrix, topRow: Int, leftCol: Int) {
        for (r in 0..6) {
            for (c in 0..6) {
                val expectedDark = r == 0 || r == 6 || c == 0 || c == 6 || (r in 2..4 && c in 2..4)
                if (expectedDark) {
                    assertTrue(matrix.isDark(topRow + r, leftCol + c))
                } else {
                    assertFalse(matrix.isDark(topRow + r, leftCol + c))
                }
            }
        }
    }
}
