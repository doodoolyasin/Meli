package com.doodoolyasin.meli

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object MeliProto {
    const val MAGIC_BYTE: Byte = 0x4D // 'M'
    const val HEADER_LEN = 7

    data class Frame(
        val sessionId: Byte,
        val seq: Short,
        val frag: Byte,
        val tot: Byte,
        val flags: Byte,
        var payload: ByteArray
    ) {
        fun pack(): ByteArray {
            val buf = ByteBuffer.allocate(HEADER_LEN + payload.size)
            buf.put(MAGIC_BYTE)
            buf.put(sessionId)
            buf.putShort(seq)
            buf.put(frag)
            buf.put(tot)
            buf.put(flags)
            buf.put(payload)
            return buf.array()
        }
    }

    fun unpack(data: ByteArray): Frame? {
        if (data.size < HEADER_LEN) return null
        if (data[0] != MAGIC_BYTE) return null
        val buf = ByteBuffer.wrap(data)
        buf.get() // magic
        val sessionId = buf.get()
        val seq = buf.short
        val frag = buf.get()
        val tot = buf.get()
        val flags = buf.get()
        val payload = ByteArray(data.size - HEADER_LEN)
        buf.get(payload)
        return Frame(sessionId, seq, frag, tot, flags, payload)
    }

    fun compress(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val deflater = Deflater(Deflater.BEST_SPEED, true)
        deflater.setInput(data)
        deflater.finish()
        val output = ByteArrayOutputStream(data.size)
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            output.write(buf, 0, count)
        }
        deflater.end()
        val res = output.toByteArray()
        return if (res.size < data.size) res else data
    }

    fun decompress(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val inflater = Inflater(true)
        inflater.setInput(data)
        val output = ByteArrayOutputStream(data.size * 2)
        val buf = ByteArray(1024)
        try {
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count == 0 && inflater.needsInput()) break
                output.write(buf, 0, count)
            }
            inflater.end()
            return output.toByteArray()
        } catch (e: Exception) {
            inflater.end()
            return data
        }
    }

    fun deriveIV(sessionId: Byte, seq: Short, frag: Byte): ByteArray {
        val iv = ByteArray(16)
        iv[0] = MAGIC_BYTE
        iv[1] = sessionId
        iv[2] = ((seq.toInt() shr 8) and 0xFF).toByte()
        iv[3] = (seq.toInt() and 0xFF).toByte()
        iv[4] = frag
        for (i in 5 until 16) {
            iv[i] = (i * 17).toByte()
        }
        return iv
    }

    fun cryptAESCTR(keyBytes: ByteArray, iv: ByteArray, src: ByteArray): ByteArray {
        val aesKey = ByteArray(16)
        val len = minOf(keyBytes.size, 16)
        System.arraycopy(keyBytes, 0, aesKey, 0, len)

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val keySpec = SecretKeySpec(aesKey, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(src)
    }

    class Reassembler {
        private val sessions = ConcurrentHashMap<Int, MutableMap<Byte, ByteArray>>()

        fun add(frame: Frame): ByteArray? {
            if (frame.tot <= 1) return frame.payload

            val key = ((frame.sessionId.toInt() and 0xFF) shl 16) or (frame.seq.toInt() and 0xFFFF)
            val frags = sessions.computeIfAbsent(key) { ConcurrentHashMap() }
            frags[frame.frag] = frame.payload

            if (frags.size == frame.tot.toInt()) {
                sessions.remove(key)
                val out = ByteArrayOutputStream()
                for (i in 0 until frame.tot) {
                    val p = frags[i.toByte()] ?: return null
                    out.write(p)
                }
                return out.toByteArray()
            }
            return null
        }
    }
}