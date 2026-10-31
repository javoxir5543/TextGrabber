package com.textgrabber.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

object EdgeTtsTokenGenerator {
    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val SEC_MS_GEC_VERSION = "2-143.0.3650.75"
    private const val WIN_EPOCH = 11644473600.0
    private const val MASK = 0xFFFFFFFFL

    private val SHA256_INITIAL = longArrayOf(
        0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL,
        0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L
    )
    private val SHA256_STATE = longArrayOf(
        0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL,
        0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L
    )
    private val SHA256_SCHEDULE = longArrayOf(
        0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL,
        0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L, 0xF9E70AB6L,
        0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L,
        0xD258B060L, 0x72766564L
    )
    private val SHA256_ROUND_CONSTANTS = longArrayOf(
        0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L, 0x3956C25BL, 0x59F111F1L,
        0x923F82A4L, 0xAB1C5ED5L, 0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L,
        0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L, 0xE49B69C1L, 0xEFBE4786L,
        0x0FC19DC6L, 0x240CA1CCL, 0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL,
        0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L, 0xC6E00BF3L, 0xD5A79147L,
        0x06CA6351L, 0x14292967L, 0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L,
        0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L, 0xA2BFE8A1L, 0xA81A664BL,
        0xC24B8B70L, 0xC76C51A3L, 0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L,
        0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L, 0x391C0CB3L, 0x4ED8AA4AL,
        0x5B9CCA4FL, 0x682E6FF3L, 0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L,
        0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L
    )
    private const val SHA256_ADJUSTMENT = 0xC9DC06A7L
    private const val SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80L
    private const val SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0L
    private val SHA256_BLOCK_CONSTANTS: LongArray = SHA256_ROUND_CONSTANTS.copyOf().also { it[14] = SHA256_ADJUSTMENT }

    private val CS64_COEFFICIENTS = arrayOf(
        longArrayOf(0xE82E91B1L, 0xB32700D0L), longArrayOf(0x44DE24C6L, 0x88DFF51DL),
        longArrayOf(0x3BEB65BCL, 0x29514038L), longArrayOf(0xC89C5933L, 0xB328CBDFL),
        longArrayOf(0x8D1EC211L, 0xD405B4D5L), longArrayOf(0x314EF5D5L, 0x26E55F3CL),
        longArrayOf(0xE92491B1L, 0x607801C6L), longArrayOf(0xF0A5B9D5L, 0x82B2C1FBL),
        longArrayOf(0x06064CF3L, 0xFA51A3DAL), longArrayOf(0xDA79FA60L, 0x207CC464L),
        longArrayOf(0xBED20560L, 0x3C3C74F3L), longArrayOf(0x89F9C9F8L, 0xD2D14B61L),
        longArrayOf(0x35FA38C6L, 0xBB04A2C1L), longArrayOf(0xCB49E3C6L, 0xC5626167L)
    )
    private const val CS64_FACTOR = 0x82893933L
    private const val CS64_ADJUSTMENT0 = 0xF2F90B1AL
    private const val CS64_ADJUSTMENT1 = 0x0B1A0000L

    fun getVersion(): String = SEC_MS_GEC_VERSION

    fun generateV2Token(unixTimestamp: Double? = null): String {
        val ut = unixTimestamp ?: (System.currentTimeMillis() / 1000.0)
        val totalSec = ut + WIN_EPOCH
        val rounded = Math.floor(totalSec / 300.0).toLong() * 300L
        val ticksNs = rounded * 10_000_000L
        val data = "${ticksNs}${TRUSTED_CLIENT_TOKEN}".toByteArray(Charsets.US_ASCII)
        return tokenHash(data)
    }

    private fun tokenHash(data: ByteArray): String {
        val digest = sha256Digest(data)
        val (a, b) = cs64Digest(data, digest)
        val cs64 = packLeIIHex(a, b)
        val sha = digest.joinToString("") { String.format("%02X", it.toInt() and 0xFF) }
        return "${cs64}-${sha}"
    }

    private fun packLeIIHex(a: Long, b: Long): String {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(a.toInt()); buf.putInt(b.toInt())
        return buf.array().joinToString("") { String.format("%02X", it.toInt() and 0xFF) }
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7) { "data too short" }
        val length = 57 + data.size
        val dataRest = data.copyOfRange(7, data.size)
        var padLen = (55 - length) % 64
        if (padLen < 0) padLen += 64
        val padded = ByteArray(dataRest.size + 1 + padLen + 8)
        System.arraycopy(dataRest, 0, padded, 0, dataRest.size)
        padded[dataRest.size] = 0x80.toByte()
        val lenBits = length.toLong() * 8
        for (i in 0 until 8) padded[padded.size - 8 + i] = ((lenBits ushr (56 - i * 8)) and 0xFF).toByte()
        var state = sha256Init(data.copyOfRange(0, 7))
        var offset = 0
        while (offset < padded.size) {
            val words = LongArray(64)
            for (i in 0 until 16) {
                var w = 0L
                for (j in 0 until 4) w = (w shl 8) or (padded[offset + i * 4 + j].toLong() and 0xFF)
                words[i] = w
            }
            expandSha256Schedule(words, 16)
            val working = sha256Rounds(state, words, 0)
            state = LongArray(8) { (state[it] + working[it]) and MASK }
            offset += 64
        }
        val result = ByteArray(32)
        for (i in 0 until 8) {
            val v = state[i]
            result[i * 4] = ((v ushr 24) and 0xFF).toByte()
            result[i * 4 + 1] = ((v ushr 16) and 0xFF).toByte()
            result[i * 4 + 2] = ((v ushr 8) and 0xFF).toByte()
            result[i * 4 + 3] = (v and 0xFF).toByte()
        }
        return result
    }

    private fun sha256Init(data: ByteArray): LongArray {
        val words = LongArray(64)
        var w14 = 0L
        for (i in 0 until 3) w14 = (w14 shl 8) or (data[i].toLong() and 0xFF)
        words[14] = w14
        var w15 = 0L
        for (i in 3 until 7) w15 = (w15 shl 8) or (data[i].toLong() and 0xFF)
        words[15] = w15
        for (index in 16 until 30) {
            var value = SHA256_SCHEDULE[index - 16]
            if (index - 2 >= 14) {
                val s1 = sigma1_32(words[index - 2])
                value += if (index == 16) (s1 xor SHA256_SIGMA1_ADJUSTMENT) else s1
            }
            if (index - 7 >= 14) value += words[index - 7]
            if (index - 15 >= 14) {
                val s0 = sigma0_32(words[index - 15])
                value += if (index == 29) (s0 xor SHA256_SIGMA0_ADJUSTMENT) else s0
            }
            words[index] = value and MASK
        }
        words[30] = (sigma1_32(words[28]) + words[23] + sigma0_32(words[15]) + words[14] +
                     SHA256_ADJUSTMENT - SHA256_ROUND_CONSTANTS[14]) and MASK
        expandSha256Schedule(words, 31)
        val working = sha256Rounds(SHA256_STATE, words, 14)
        return LongArray(8) { (SHA256_INITIAL[it] + working[it]) and MASK }
    }

    private fun expandSha256Schedule(words: LongArray, firstWord: Int) {
        for (i in firstWord until 64) {
            val x = words[i - 2]; val y = words[i - 15]
            val s1 = (x ushr 17) xor (x shl 15) xor (x ushr 19) xor (x shl 13) xor (x ushr 10)
            val s0 = (y ushr 7) xor (y shl 25) xor (y ushr 18) xor (y shl 14) xor (y ushr 3)
            words[i] = (s1 + words[i - 7] + s0 + words[i - 16]) and MASK
        }
    }

    private fun sigma0_32(v: Long): Long =
        ((v ushr 7) xor (v shl 25) xor (v ushr 18) xor (v shl 14) xor (v ushr 3)) and MASK
    private fun sigma1_32(v: Long): Long =
        ((v ushr 17) xor (v shl 15) xor (v ushr 19) xor (v shl 13) xor (v ushr 10)) and MASK

    private fun sha256Rounds(state: LongArray, words: LongArray, firstRound: Int): LongArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        val constants = if (firstRound == 14) SHA256_BLOCK_CONSTANTS else SHA256_ROUND_CONSTANTS
        val aligned = minOf(64, ((firstRound + 7) / 8) * 8)
        var idx = firstRound
        while (idx < aligned) {
            val rotE = e * 0x100000001L; val rotA = a * 0x100000001L
            val t1 = h + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                     (g xor (e and (f xor g))) + constants[idx] + words[idx]
            val t2 = ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) + ((a and b) or (c and (a or b)))
            val oldA = a; val oldB = b; val oldC = c; val oldD = d
            val oldE = e; val oldF = f; val oldG = g
            a = (t1 + t2) and MASK; b = oldA; c = oldB; d = oldC
            e = (oldD + t1) and MASK; f = oldE; g = oldF; h = oldG
            idx++
        }
        idx = aligned
        while (idx < 64) {
            var rotE = e * 0x100000001L; var rotA = a * 0x100000001L
            var t1 = h + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                     (g xor (e and (f xor g))) + constants[idx] + words[idx]
            d = (d + t1) and MASK
            h = (t1 + ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) + ((a and b) or (c and (a or b)))) and MASK
            rotE = d * 0x100000001L; rotA = h * 0x100000001L
            t1 = g + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                 (f xor (d and (e xor f))) + constants[idx + 1] + words[idx + 1]
            c = (c + t1) and MASK
            g = (t1 + ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) + ((h and a) or (b and (h or a)))) and MASK
            rotE = c * 0x100000001L; rotA = g * 0x100000001L
            t1 = f + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                 (e xor (c and (d xor e))) + constants[idx + 2] + words[idx + 2]
            b = (b + t1) and MASK
            f = (t1 + ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) + ((g and h) or (a and (g or h)))) and MASK
            rotE = b * 0x100000001L; rotA = f * 0x100000001L
            t1 = e + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                 (d xor (b and (c xor d))) + constants[idx + 3] + words[idx + 3]
            a = (a + t1) and MASK
            e = (t1 + ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) + ((f and g) or (h and (f or g)))) and MASK
            rotE = a * 0x100000001L; rotA = e * 0x100000001L
            t1 = d + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                 (c xor (a and (b xor c))) + constants[idx + 4] + words[idx + 4]
            h = (h + t1) and MASK
            d = (t1 + ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) + ((e and f) or (g and (e or f)))) and MASK
            rotE = h * 0x100000001L; rotA = d * 0x100000001L
            t1 = c + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                 (b xor (h and (a xor b))) + constants[idx + 5] + words[idx + 5]
            g = (g + t1) and MASK
            c = (t1 + ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) + ((d and e) or (f and (d or e)))) and MASK
            rotE = g * 0x100000001L; rotA = c * 0x100000001L
            t1 = b + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                 (a xor (g and (h xor a))) + constants[idx + 6] + words[idx + 6]
            f = (f + t1) and MASK
            b = (t1 + ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) + ((c and d) or (e and (c or d)))) and MASK
            rotE = f * 0x100000001L; rotA = b * 0x100000001L
            t1 = a + ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                 (h xor (f and (g xor h))) + constants[idx + 7] + words[idx + 7]
            e = (e + t1) and MASK
            a = (t1 + ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) + ((b and c) or (d and (b or c)))) and MASK
            idx += 8
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun cs64Digest(data: ByteArray, digest: ByteArray): Pair<Long, Long> {
        val buf = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN)
        var keyA = buf.int.toLong() and MASK
        var keyB = buf.int.toLong() and MASK
        keyA = keyA or 1L; keyB = keyB or 1L
        val factors = longArrayOf((keyA * CS64_FACTOR) and MASK, (keyB * CS64_FACTOR) and MASK)
        val terms = ArrayList<Long>()
        for (i in CS64_COEFFICIENTS.indices) terms.add((CS64_COEFFICIENTS[i][0] * factors[i % 2]) and MASK)
        val offsets = ArrayList<Long>()
        for (i in CS64_COEFFICIENTS.indices) offsets.add(CS64_COEFFICIENTS[i][1])
        val combined = ByteArray(1 + data.size)
        System.arraycopy(data, 0, combined, 1, data.size)
        val paddedLen = ((combined.size + 3) / 4) * 4
        val padded = ByteArray(paddedLen)
        System.arraycopy(combined, 0, padded, 0, combined.size)
        val wordCount = paddedLen / 4
        val wordsArr = LongArray(if (wordCount % 2 == 0) wordCount else wordCount + 1)
        for (i in 0 until wordCount) {
            wordsArr[i] = ByteBuffer.wrap(padded, i * 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and MASK
        }
        for ((index, word) in wordsArr.withIndex()) {
            var term = word * (if (index % 2 == 0) keyA else keyB)
            var offset = rol16(word) * (if (index % 2 == 0) 0xEF0569FBL else 0xC31713DBL)
            if (index == 0) { term += CS64_ADJUSTMENT0 * factors[0]; offset += CS64_ADJUSTMENT1 }
            terms.add(term and MASK); offsets.add(offset and MASK)
        }
        var swapAcc = 0L; var swapLast = 0L; var state = 0L; var acc = 0L
        var i = 0
        while (i < terms.size) {
            val x = terms[i]; val y = terms[i + 1]
            var t = rol16((offsets[i] + x) and MASK)
            var q = (t * 0x76F50F34L) and MASK
            swapAcc += (rol16(q) * 0xC3EFEA97L + q) and MASK
            t = rol16((offsets[i + 1] + y) and MASK)
            q = (t * 0x1881CE3CL) and MASK
            swapLast = (rol16(q) * 0x35BD1EC9L + q) and MASK
            swapAcc = (swapAcc + swapLast) and MASK
            state = (state * keyA + x) and MASK
            state = (rol16(state) * 0xCF98B111L) and MASK
            state = (rol16(state) * 0x87085B9FL) and MASK
            state = (rol16(state) * 0x12CEB96DL) and MASK
            state = (rol16(state) * 0x257E1D83L) and MASK
            acc = (acc + state) and MASK
            t = (state * keyB + y) and MASK
            t = (rol16(t) * 0xA27416F5L) and MASK
            t = (rol16(t) * 0xD38396FFL) and MASK
            state = (rol16((rol16(t) * 0x7C932B89L) and MASK) * 0xBFA49F69L) and MASK
            acc = (acc + state) and MASK
            i += 2
        }
        return Pair(swapLast xor state, swapAcc xor acc)
    }

    private fun rol16(v: Long): Long {
        val m = v and MASK
        return ((m shl 16) or (m ushr 16)) and MASK
    }
}
