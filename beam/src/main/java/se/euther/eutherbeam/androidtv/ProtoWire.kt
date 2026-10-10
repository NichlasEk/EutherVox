package se.euther.eutherbeam.androidtv

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

internal data class ProtoField(val number: Int, val wireType: Int, val varint: Long? = null, val bytes: ByteArray? = null)

internal object ProtoWire {
    fun message(build: Writer.() -> Unit): ByteArray = Writer().apply(build).toByteArray()

    fun frame(output: OutputStream, payload: ByteArray) {
        writeVarint(output, payload.size.toLong())
        output.write(payload)
        output.flush()
    }

    fun readFrame(input: InputStream, maximumSize: Int = 1_048_576): ByteArray {
        val size = readVarint(input).toInt()
        require(size in 0..maximumSize) { "Ogiltig Android TV-meddelandelängd: $size" }
        val payload = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(payload, offset, size - offset)
            if (count < 0) throw EOFException("Android TV stängde anslutningen")
            offset += count
        }
        return payload
    }

    fun fields(payload: ByteArray): List<ProtoField> {
        val result = mutableListOf<ProtoField>()
        var offset = 0
        while (offset < payload.size) {
            val (tag, afterTag) = readVarint(payload, offset)
            offset = afterTag
            val number = (tag ushr 3).toInt()
            val wireType = (tag and 7).toInt()
            require(number > 0) { "Ogiltigt protobuf-fält" }
            when (wireType) {
                0 -> {
                    val (value, next) = readVarint(payload, offset)
                    result += ProtoField(number, wireType, varint = value)
                    offset = next
                }
                1 -> {
                    require(offset + 8 <= payload.size) { "Avkortat protobuf-fält" }
                    offset += 8
                }
                2 -> {
                    val (length, afterLength) = readVarint(payload, offset)
                    require(length <= Int.MAX_VALUE) { "För stort protobuf-fält" }
                    val end = afterLength + length.toInt()
                    require(end <= payload.size) { "Avkortat protobuf-fält" }
                    result += ProtoField(number, wireType, bytes = payload.copyOfRange(afterLength, end))
                    offset = end
                }
                5 -> {
                    require(offset + 4 <= payload.size) { "Avkortat protobuf-fält" }
                    offset += 4
                }
                else -> error("Protobuf wire type $wireType stöds inte")
            }
        }
        return result
    }

    fun nested(payload: ByteArray, fieldNumber: Int): ByteArray? =
        fields(payload).firstOrNull { it.number == fieldNumber && it.wireType == 2 }?.bytes

    fun varint(payload: ByteArray, fieldNumber: Int): Long? =
        fields(payload).firstOrNull { it.number == fieldNumber && it.wireType == 0 }?.varint

    private fun readVarint(input: InputStream): Long {
        var result = 0L
        for (shift in 0 until 64 step 7) {
            val byte = input.read()
            if (byte < 0) throw EOFException("Android TV stängde anslutningen")
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return result
        }
        error("För lång protobuf-varint")
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var offset = start
        for (shift in 0 until 64 step 7) {
            require(offset < bytes.size) { "Avkortad protobuf-varint" }
            val byte = bytes[offset++].toInt() and 0xff
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return result to offset
        }
        error("För lång protobuf-varint")
    }

    private fun writeVarint(output: OutputStream, rawValue: Long) {
        var value = rawValue
        while (true) {
            if (value and -128L == 0L) {
                output.write(value.toInt())
                return
            }
            output.write((value.toInt() and 0x7f) or 0x80)
            value = value ushr 7
        }
    }

    internal class Writer {
        private val output = ByteArrayOutputStream()

        fun uint(fieldNumber: Int, value: Long) {
            writeVarint(output, (fieldNumber.toLong() shl 3))
            writeVarint(output, value)
        }

        fun string(fieldNumber: Int, value: String) = bytes(fieldNumber, value.toByteArray(Charsets.UTF_8))

        fun bytes(fieldNumber: Int, value: ByteArray) {
            writeVarint(output, (fieldNumber.toLong() shl 3) or 2)
            writeVarint(output, value.size.toLong())
            output.write(value)
        }

        fun nested(fieldNumber: Int, build: Writer.() -> Unit) = bytes(fieldNumber, message(build))

        fun toByteArray(): ByteArray = output.toByteArray()
    }
}
