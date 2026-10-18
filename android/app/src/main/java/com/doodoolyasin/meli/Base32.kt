package com.doodoolyasin.meli

object Base32 {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    private const val ALPHABET_BYTES = "abcdefghijklmnopqrstuvwxyz234567".toByteArray()
    
    // Decode map
    private val DECODE_MAP = IntArray(256) { -1 }.apply {
        ALPHABET.toUpperCase().toCharArray().forEachIndexed { i, c ->
            this[c.toInt()] = i
            this[c.toLowerCase().toInt()] = i
        }
    }

    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        
        val bits = StringBuilder(data.size * 8)
        for (b in data) {
            for (i in 7 downTo 0) {
                bits.append(if ((b shr i and 1) == 1) '1' else '0')
            }
        }
        
        // Pad to multiple of 5
        val padding = (5 - bits.length % 5) % 5
        repeat(padding) { bits.append('0') }
        
        val result = StringBuilder()
        for (i in 0 until bits.length step 5) {
            val chunk = bits.substring(i, i + 5).toInt(2)
            result.append(ALPHABET[chunk])
        }
        
        // Add padding chars if needed (optional for DNS labels)
        return result.toString()
    }

    fun decode(encoded: String): ByteArray {
        val clean = encoded.filter { it.isLetterOrDigit() }
        if (clean.isEmpty()) return ByteArray(0)
        
        val bits = StringBuilder()
        for (c in clean) {
            val value = DECODE_MAP[c.toInt()]
            if (value >= 0) {
                for (i in 4 downTo 0) {
                    bits.append(if ((value shr i and 1) == 1) '1' else '0')
                }
            }
        }
        
        // Remove padding bits
        val byteCount = bits.length / 8
        val result = ByteArray(byteCount)
        for (i in 0 until byteCount) {
            var b = 0
            for (j in 0..7) {
                if (bits[i * 8 + j] == '1') {
                    b = b or (1 shl (7 - j))
                }
            }
            result[i] = b.toByte()
        }
        
        return result
    }
}