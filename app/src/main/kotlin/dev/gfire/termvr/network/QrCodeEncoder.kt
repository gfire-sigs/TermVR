package dev.gfire.termvr.network

import java.nio.charset.StandardCharsets

/**
 * Pure-Kotlin ISO/IEC 18004 byte-mode QR code matrix generator at error-correction level M.
 */
data class QrMatrix(
    val size: Int,
    val version: Int,
    private val modules: BooleanArray,
) {
    /**
     * Returns true when module at ([row], [col]) is dark.
     */
    fun isDark(row: Int, col: Int): Boolean {
        require(row in 0 until size && col in 0 until size) {
            "QR coordinate ($row, $col) out of bounds for size $size"
        }
        return modules[row * size + col]
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QrMatrix) return false
        return size == other.size && version == other.version && modules.contentEquals(other.modules)
    }

    override fun hashCode(): Int {
        var result = size
        result = 31 * result + version
        result = 31 * result + modules.contentHashCode()
        return result
    }
}

object QrCodeEncoder {
    private data class VersionSpec(
        val version: Int,
        val dataCodewords: Int,
        val ecPerBlock: Int,
        val group1Blocks: Int,
        val group1DataPerBlock: Int,
        val group2Blocks: Int = 0,
        val group2DataPerBlock: Int = 0,
        val alignmentCenters: IntArray,
        val remainderBits: Int,
    )

    private val LEVEL_M_SPECS = listOf(
        VersionSpec(1, 16, 10, 1, 16, alignmentCenters = intArrayOf(), remainderBits = 0),
        VersionSpec(2, 28, 16, 1, 28, alignmentCenters = intArrayOf(6, 18), remainderBits = 7),
        VersionSpec(3, 44, 26, 1, 44, alignmentCenters = intArrayOf(6, 22), remainderBits = 7),
        VersionSpec(4, 64, 18, 2, 32, alignmentCenters = intArrayOf(6, 26), remainderBits = 7),
        VersionSpec(5, 86, 24, 2, 43, alignmentCenters = intArrayOf(6, 30), remainderBits = 7),
        VersionSpec(6, 108, 16, 4, 27, alignmentCenters = intArrayOf(6, 34), remainderBits = 7),
        VersionSpec(7, 124, 18, 4, 31, alignmentCenters = intArrayOf(6, 22, 38), remainderBits = 0),
        VersionSpec(8, 154, 22, 2, 38, group2Blocks = 2, group2DataPerBlock = 39, alignmentCenters = intArrayOf(6, 24, 42), remainderBits = 0),
        VersionSpec(9, 182, 22, 3, 36, group2Blocks = 2, group2DataPerBlock = 37, alignmentCenters = intArrayOf(6, 26, 46), remainderBits = 0),
        VersionSpec(10, 216, 26, 4, 43, group2Blocks = 1, group2DataPerBlock = 44, alignmentCenters = intArrayOf(6, 28, 50), remainderBits = 0),
    )

    private val GF_EXP = IntArray(512)
    private val GF_LOG = IntArray(256)

    init {
        var value = 1
        for (i in 0 until 255) {
            GF_EXP[i] = value
            GF_LOG[value] = i
            value = value shl 1
            if (value and 0x100 != 0) {
                value = value xor 0x11D
            }
        }
        for (i in 255 until 512) {
            GF_EXP[i] = GF_EXP[i - 255]
        }
    }

    /**
     * Encodes [text] as UTF-8 bytes into an ISO/IEC 18004 Level M [QrMatrix].
     */
    fun encode(text: String): QrMatrix {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val spec = selectVersion(bytes.size)
        val dataCodewords = encodeDataCodewords(bytes, spec)
        val finalStream = buildInterleavedCodewords(dataCodewords, spec)
        return buildBestMatrix(finalStream, spec)
    }

    private fun selectVersion(byteCount: Int): VersionSpec {
        for (spec in LEVEL_M_SPECS) {
            val countBits = if (spec.version <= 9) 8 else 16
            val neededBits = 4 + countBits + byteCount * 8
            if (neededBits <= spec.dataCodewords * 8) {
                return spec
            }
        }
        throw IllegalArgumentException("QR payload exceeds version 10 Level M byte capacity: $byteCount bytes")
    }

    private fun encodeDataCodewords(bytes: ByteArray, spec: VersionSpec): IntArray {
        val bits = ArrayList<Int>(spec.dataCodewords * 8)
        appendBits(bits, 0b0100, 4)
        val countBits = if (spec.version <= 9) 8 else 16
        appendBits(bits, bytes.size, countBits)
        for (b in bytes) {
            appendBits(bits, b.toInt() and 0xFF, 8)
        }
        val capacityBits = spec.dataCodewords * 8
        val terminatorBits = (capacityBits - bits.size).coerceIn(0, 4)
        appendBits(bits, 0, terminatorBits)
        while (bits.size % 8 != 0) {
            bits.add(0)
        }
        val codewords = IntArray(spec.dataCodewords)
        val byteLen = bits.size / 8
        for (i in 0 until byteLen) {
            var word = 0
            for (bit in 0 until 8) {
                word = (word shl 1) or bits[i * 8 + bit]
            }
            codewords[i] = word
        }
        var padToggle = 0
        for (i in byteLen until spec.dataCodewords) {
            codewords[i] = if (padToggle % 2 == 0) 0xEC else 0x11
            padToggle++
        }
        return codewords
    }

    private fun buildInterleavedCodewords(data: IntArray, spec: VersionSpec): List<Int> {
        val dataBlocks = ArrayList<IntArray>()
        val ecBlocks = ArrayList<IntArray>()
        val generator = rsGeneratorPoly(spec.ecPerBlock)
        var offset = 0

        repeat(spec.group1Blocks) {
            val block = data.copyOfRange(offset, offset + spec.group1DataPerBlock)
            offset += spec.group1DataPerBlock
            dataBlocks.add(block)
            ecBlocks.add(rsComputeRemainder(block, generator, spec.ecPerBlock))
        }
        repeat(spec.group2Blocks) {
            val block = data.copyOfRange(offset, offset + spec.group2DataPerBlock)
            offset += spec.group2DataPerBlock
            dataBlocks.add(block)
            ecBlocks.add(rsComputeRemainder(block, generator, spec.ecPerBlock))
        }

        val maxDataLen = maxOf(spec.group1DataPerBlock, spec.group2DataPerBlock)
        val result = ArrayList<Int>(data.size + dataBlocks.size * spec.ecPerBlock)
        for (i in 0 until maxDataLen) {
            for (block in dataBlocks) {
                if (i < block.size) result.add(block[i])
            }
        }
        for (i in 0 until spec.ecPerBlock) {
            for (ec in ecBlocks) {
                result.add(ec[i])
            }
        }
        return result
    }

    private fun buildBestMatrix(codewords: List<Int>, spec: VersionSpec): QrMatrix {
        val size = spec.version * 4 + 17
        val baseModules = BooleanArray(size * size)
        val isFunction = BooleanArray(size * size)

        placeFunctionPatterns(size, spec, baseModules, isFunction)
        placeDataBits(size, codewords, spec.remainderBits, baseModules, isFunction)

        var bestPenalty = Int.MAX_VALUE
        var bestModules = baseModules
        for (mask in 0..7) {
            val candidate = baseModules.copyOf()
            applyMask(size, mask, candidate, isFunction)
            placeFormatInfo(size, mask, candidate)
            if (spec.version >= 7) {
                placeVersionInfo(size, spec.version, candidate)
            }
            val penalty = computePenalty(size, candidate)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestModules = candidate
            }
        }
        return QrMatrix(size = size, version = spec.version, modules = bestModules)
    }

    private fun placeFunctionPatterns(
        size: Int,
        spec: VersionSpec,
        modules: BooleanArray,
        isFunction: BooleanArray,
    ) {
        drawFinderPattern(size, 0, 0, modules, isFunction)
        drawFinderPattern(size, 0, size - 7, modules, isFunction)
        drawFinderPattern(size, size - 7, 0, modules, isFunction)

        for (i in 8 until size - 8) {
            val dark = (i % 2) == 0
            setFunctionModule(size, 6, i, dark, modules, isFunction)
            setFunctionModule(size, i, 6, dark, modules, isFunction)
        }

        for (rCenter in spec.alignmentCenters) {
            for (cCenter in spec.alignmentCenters) {
                val overlapsFinder =
                    (rCenter <= 8 && cCenter <= 8) ||
                        (rCenter <= 8 && cCenter >= size - 9) ||
                        (rCenter >= size - 9 && cCenter <= 8)
                if (!overlapsFinder) {
                    drawAlignmentPattern(size, rCenter, cCenter, modules, isFunction)
                }
            }
        }

        // Reserve format information strips and place fixed dark module.
        for (i in 0..8) {
            if (i != 6) {
                reserveFunction(size, 8, i, isFunction)
                reserveFunction(size, i, 8, isFunction)
            }
        }
        for (i in size - 8 until size) {
            reserveFunction(size, 8, i, isFunction)
            reserveFunction(size, i, 8, isFunction)
        }
        setFunctionModule(size, 4 * spec.version + 9, 8, true, modules, isFunction)

        if (spec.version >= 7) {
            for (i in 0 until 6) {
                for (j in 0 until 3) {
                    reserveFunction(size, i, size - 11 + j, isFunction)
                    reserveFunction(size, size - 11 + j, i, isFunction)
                }
            }
        }
    }

    private fun drawFinderPattern(
        size: Int,
        topRow: Int,
        leftCol: Int,
        modules: BooleanArray,
        isFunction: BooleanArray,
    ) {
        for (dr in -1..7) {
            for (dc in -1..7) {
                val r = topRow + dr
                val c = leftCol + dc
                if (r !in 0 until size || c !in 0 until size) continue
                val dark = dr in 0..6 && dc in 0..6 && (
                    dr == 0 || dr == 6 || dc == 0 || dc == 6 || (dr in 2..4 && dc in 2..4)
                    )
                setFunctionModule(size, r, c, dark, modules, isFunction)
            }
        }
    }

    private fun drawAlignmentPattern(
        size: Int,
        centerRow: Int,
        centerCol: Int,
        modules: BooleanArray,
        isFunction: BooleanArray,
    ) {
        for (dr in -2..2) {
            for (dc in -2..2) {
                val dist = maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc))
                setFunctionModule(size, centerRow + dr, centerCol + dc, dist != 1, modules, isFunction)
            }
        }
    }

    private fun placeDataBits(
        size: Int,
        codewords: List<Int>,
        remainderBits: Int,
        modules: BooleanArray,
        isFunction: BooleanArray,
    ) {
        val totalBits = codewords.size * 8 + remainderBits
        var bitIndex = 0
        var upward = true
        var rightCol = size - 1
        while (rightCol >= 1) {
            if (rightCol == 6) rightCol--
            for (step in 0 until size) {
                val row = if (upward) size - 1 - step else step
                for (colOffset in 0..1) {
                    val col = rightCol - colOffset
                    val idx = row * size + col
                    if (!isFunction[idx]) {
                        val bit = if (bitIndex < codewords.size * 8) {
                            val word = codewords[bitIndex / 8]
                            ((word ushr (7 - (bitIndex % 8))) and 1) == 1
                        } else {
                            false
                        }
                        if (bitIndex < totalBits) {
                            modules[idx] = bit
                            bitIndex++
                        }
                    }
                }
            }
            upward = !upward
            rightCol -= 2
        }
    }

    private fun applyMask(size: Int, mask: Int, modules: BooleanArray, isFunction: BooleanArray) {
        for (r in 0 until size) {
            for (c in 0 until size) {
                val idx = r * size + c
                if (isFunction[idx]) continue
                val invert = when (mask) {
                    0 -> (r + c) % 2 == 0
                    1 -> r % 2 == 0
                    2 -> c % 3 == 0
                    3 -> (r + c) % 3 == 0
                    4 -> ((r / 2) + (c / 3)) % 2 == 0
                    5 -> ((r * c) % 2 + (r * c) % 3) == 0
                    6 -> (((r * c) % 2 + (r * c) % 3) % 2) == 0
                    else -> (((r + c) % 2 + (r * c) % 3) % 2) == 0
                }
                if (invert) {
                    modules[idx] = !modules[idx]
                }
            }
        }
    }

    private fun placeFormatInfo(size: Int, mask: Int, modules: BooleanArray) {
        // Error correction level M uses format indicator bits 00.
        val data = mask and 0x7
        var rem = data shl 10
        for (i in 4 downTo 0) {
            if ((rem ushr (i + 10)) != 0) {
                rem = rem xor (0x537 shl i)
            }
        }
        val bits = ((data shl 10) or rem) xor 0x5412

        for (i in 0..5) {
            modules[i * size + 8] = ((bits ushr i) and 1) == 1
        }
        modules[7 * size + 8] = ((bits ushr 6) and 1) == 1
        modules[8 * size + 8] = ((bits ushr 7) and 1) == 1
        modules[8 * size + 7] = ((bits ushr 8) and 1) == 1
        for (i in 9..14) {
            modules[8 * size + (14 - i)] = ((bits ushr i) and 1) == 1
        }

        for (i in 0..7) {
            modules[8 * size + (size - 1 - i)] = ((bits ushr i) and 1) == 1
        }
        for (i in 8..14) {
            modules[(size - 15 + i) * size + 8] = ((bits ushr i) and 1) == 1
        }
    }

    private fun placeVersionInfo(size: Int, version: Int, modules: BooleanArray) {
        var rem = version shl 12
        for (i in 5 downTo 0) {
            if ((rem ushr (i + 12)) != 0) {
                rem = rem xor (0x1F25 shl i)
            }
        }
        val bits = (version shl 12) or rem
        for (i in 0 until 18) {
            val dark = ((bits ushr i) and 1) == 1
            val a = i / 3
            val b = size - 11 + (i % 3)
            modules[a * size + b] = dark
            modules[b * size + a] = dark
        }
    }

    private fun computePenalty(size: Int, modules: BooleanArray): Int {
        var penalty = 0
        for (r in 0 until size) {
            penalty += linePenalty { c -> modules[r * size + c] }.invoke(size)
        }
        for (c in 0 until size) {
            penalty += linePenalty { r -> modules[r * size + c] }.invoke(size)
        }
        for (r in 0 until size - 1) {
            for (c in 0 until size - 1) {
                val color = modules[r * size + c]
                if (
                    modules[r * size + c + 1] == color &&
                    modules[(r + 1) * size + c] == color &&
                    modules[(r + 1) * size + c + 1] == color
                ) {
                    penalty += 3
                }
            }
        }
        var darkCount = 0
        for (m in modules) if (m) darkCount++
        val total = size * size
        val percentDiff = kotlin.math.abs((darkCount * 100) / total - 50) / 5
        penalty += percentDiff * 10
        return penalty
    }

    private inline fun linePenalty(crossinline getter: (Int) -> Boolean): (Int) -> Int = { size ->
        var score = 0
        var runColor = getter(0)
        var runLen = 1
        val history = IntArray(7)
        var histLen = 0
        for (i in 1 until size) {
            val color = getter(i)
            if (color == runColor) {
                runLen++
            } else {
                if (runLen >= 5) score += 3 + (runLen - 5)
                if (histLen < 7) {
                    history[histLen++] = runLen
                } else {
                    System.arraycopy(history, 1, history, 0, 6)
                    history[6] = runLen
                }
                if (histLen >= 5 && isFinderLike(history, histLen, !runColor)) {
                    score += 40
                }
                runColor = color
                runLen = 1
            }
        }
        if (runLen >= 5) score += 3 + (runLen - 5)
        score
    }

    private fun isFinderLike(history: IntArray, len: Int, endsOnDark: Boolean): Boolean {
        if (!endsOnDark || len < 5) return false
        val unit = history[len - 5]
        if (unit <= 0) return false
        return history[len - 4] == unit &&
            history[len - 3] == 3 * unit &&
            history[len - 2] == unit &&
            history[len - 1] == unit
    }

    private fun rsGeneratorPoly(degree: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(poly.size + 1)
            val factor = GF_EXP[i]
            for (j in poly.indices) {
                next[j] = next[j] xor poly[j]
                next[j + 1] = next[j + 1] xor gfMul(poly[j], factor)
            }
            poly = next
        }
        return poly
    }

    private fun rsComputeRemainder(data: IntArray, generator: IntArray, ecCount: Int): IntArray {
        val rem = IntArray(ecCount)
        for (b in data) {
            val factor = b xor rem[0]
            System.arraycopy(rem, 1, rem, 0, ecCount - 1)
            rem[ecCount - 1] = 0
            for (i in 0 until ecCount) {
                rem[i] = rem[i] xor gfMul(generator[i + 1], factor)
            }
        }
        return rem
    }

    private fun gfMul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return GF_EXP[GF_LOG[a] + GF_LOG[b]]
    }

    private fun setFunctionModule(
        size: Int,
        row: Int,
        col: Int,
        dark: Boolean,
        modules: BooleanArray,
        isFunction: BooleanArray,
    ) {
        val idx = row * size + col
        modules[idx] = dark
        isFunction[idx] = true
    }

    private fun reserveFunction(size: Int, row: Int, col: Int, isFunction: BooleanArray) {
        isFunction[row * size + col] = true
    }

    private fun appendBits(target: MutableList<Int>, value: Int, bitCount: Int) {
        for (i in bitCount - 1 downTo 0) {
            target.add((value ushr i) and 1)
        }
    }
}
