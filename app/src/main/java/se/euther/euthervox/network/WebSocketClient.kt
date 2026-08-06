package se.euther.euthervox.network

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.SSLSocketFactory

interface VoiceTransport {
    suspend fun connect(url: String, listener: Listener)
    suspend fun sendText(text: String): Boolean
    fun trySendAudio(audio: ByteArray): Boolean
    suspend fun close()

    interface Listener {
        suspend fun onOpen()
        suspend fun onText(text: String)
        suspend fun onBinary(data: ByteArray)
        suspend fun onClosed(cause: Throwable?)
    }
}

/** Dependency-free RFC 6455 client. One bounded writer queue keeps network stalls off AudioRecord. */
class WebSocketClient(private val scope: CoroutineScope, private val bearerToken: String? = null) : VoiceTransport {
    private data class Frame(val opcode: Int, val payload: ByteArray)

    private var outgoing = Channel<Frame>(capacity = 12)
    private var socket: Socket? = null
    private var writerJob: Job? = null
    @Volatile private var connected = false

    override suspend fun connect(url: String, listener: VoiceTransport.Listener) = withContext(Dispatchers.IO) {
        var failure: Throwable? = null
        try {
            val writeQueue = Channel<Frame>(capacity = 12)
            outgoing = writeQueue
            val uri = URI(url)
            require(uri.scheme == "ws" || uri.scheme == "wss") { "Adressen måste börja med ws:// eller wss://" }
            val port = if (uri.port >= 0) uri.port else if (uri.scheme == "wss") 443 else 80
            val transport = if (uri.scheme == "wss") {
                SSLSocketFactory.getDefault().createSocket(uri.host, port)
            } else Socket(uri.host, port)
            transport.tcpNoDelay = true
            socket = transport
            val input = BufferedInputStream(transport.getInputStream())
            val output = BufferedOutputStream(transport.getOutputStream())
            performHandshake(uri, input, output)
            connected = true
            writerJob = scope.launch(Dispatchers.IO) {
                for (frame in writeQueue) writeFrame(output, frame)
            }
            listener.onOpen()
            while (isActive && connected) {
                when (val frame = readFrame(input)) {
                    null -> break
                    else -> when (frame.opcode) {
                        0x1 -> listener.onText(frame.payload.toString(Charsets.UTF_8))
                        0x2 -> listener.onBinary(frame.payload)
                        0x8 -> break
                        0x9 -> outgoing.send(Frame(0xA, frame.payload))
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failure = error
        } finally {
            connected = false
            writerJob?.cancel()
            socket?.runCatching { close() }
            socket = null
            listener.onClosed(failure)
        }
    }

    override suspend fun sendText(text: String): Boolean {
        if (!connected) return false
        outgoing.send(Frame(0x1, text.toByteArray(Charsets.UTF_8)))
        return true
    }

    override fun trySendAudio(audio: ByteArray): Boolean =
        connected && outgoing.trySend(Frame(0x2, audio.copyOf())).isSuccess

    override suspend fun close() {
        if (connected) outgoing.trySend(Frame(0x8, ByteArray(0)))
        connected = false
        socket?.runCatching { close() }
        writerJob?.cancel()
    }

    private fun performHandshake(uri: URI, input: BufferedInputStream, output: BufferedOutputStream) {
        val nonce = ByteArray(16).also(SecureRandom()::nextBytes)
        val key = Base64.encodeToString(nonce, Base64.NO_WRAP)
        val path = buildString {
            append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
            if (uri.rawQuery != null) append('?').append(uri.rawQuery)
        }
        val host = if (uri.port >= 0) "${uri.host}:${uri.port}" else uri.host
        require(bearerToken?.contains('\r') != true && bearerToken?.contains('\n') != true) { "Ogiltig app-token" }
        val authorization = bearerToken?.takeIf(String::isNotBlank)?.let { "Authorization: Bearer $it\r\n" }.orEmpty()
        val request = "GET $path HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n$authorization\r\n"
        output.write(request.toByteArray(Charsets.US_ASCII))
        output.flush()
        val response = readHttpHeaders(input)
        val status = response.lineSequence().first()
        require(status.contains(" 101 ")) { "WebSocket handshake avvisades (${status.substringAfter(' ').substringBefore(' ')})" }
        val accept = response.lineSequence().firstOrNull { it.startsWith("Sec-WebSocket-Accept:", true) }
            ?.substringAfter(':')?.trim()
        val expected = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
            Base64.NO_WRAP,
        )
        require(accept == expected) { "Felaktigt WebSocket handshake-svar" }
    }

    private fun readHttpHeaders(input: BufferedInputStream): String {
        val bytes = ArrayList<Byte>()
        var tail = 0
        while (bytes.size < 16_384) {
            val byte = input.read()
            if (byte < 0) throw EOFException("Anslutningen stängdes under handshake")
            bytes.add(byte.toByte())
            tail = ((tail shl 8) or byte) and -1
            if (bytes.size >= 4 && tail == 0x0D0A0D0A) break
        }
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }

    private fun writeFrame(output: BufferedOutputStream, frame: Frame) {
        val length = frame.payload.size
        output.write(0x80 or frame.opcode)
        when {
            length < 126 -> output.write(0x80 or length)
            length <= 0xFFFF -> {
                output.write(0x80 or 126)
                output.write(length ushr 8)
                output.write(length)
            }
            else -> {
                output.write(0x80 or 127)
                repeat(4) { output.write(0) }
                output.write(length ushr 24)
                output.write(length ushr 16)
                output.write(length ushr 8)
                output.write(length)
            }
        }
        val mask = ByteArray(4).also(SecureRandom()::nextBytes)
        output.write(mask)
        frame.payload.forEachIndexed { index, value -> output.write(value.toInt() xor mask[index % 4].toInt()) }
        output.flush()
    }

    private fun readFrame(input: BufferedInputStream): Frame? {
        val first = input.read()
        if (first < 0) return null
        val second = input.read()
        if (second < 0) throw EOFException()
        require(first and 0x80 != 0) { "Fragmenterade serverframes stöds inte i prototypen" }
        val masked = second and 0x80 != 0
        var length = (second and 0x7F).toLong()
        if (length == 126L) length = ((input.read() shl 8) or input.read()).toLong()
        if (length == 127L) {
            length = 0
            repeat(8) { length = (length shl 8) or input.read().toLong() }
        }
        require(length <= 1_048_576) { "Inkommande frame är för stor" }
        val mask = if (masked) input.readExactly(4) else null
        val payload = input.readExactly(length.toInt())
        if (mask != null) payload.indices.forEach { payload[it] = (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        return Frame(first and 0x0F, payload)
    }

    private fun BufferedInputStream.readExactly(size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(result, offset, size - offset)
            if (count < 0) throw EOFException()
            offset += count
        }
        return result
    }
}
