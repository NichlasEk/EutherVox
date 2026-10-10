package se.euther.eutherbeam.androidtv

import java.io.Closeable
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.Socket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AndroidTvRemoteClient(private val identity: AndroidTvIdentity) : Closeable {
    private val connectionMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outputLock = Any()

    @Volatile private var socket: SSLSocket? = null
    @Volatile private var connectedHost: String? = null

    suspend fun connect(host: String) = connectionMutex.withLock {
        withContext(Dispatchers.IO) { ensureConnected(host) }
    }

    suspend fun isAvailable(host: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { it.connect(InetSocketAddress(host, AndroidTvProtocol.REMOTE_PORT), PROBE_TIMEOUT_MS) }
        }.isSuccess
    }

    suspend fun sendKey(host: String, key: AndroidTvKey) = connectionMutex.withLock {
        withContext(Dispatchers.IO) {
            var lastFailure: Throwable? = null
            repeat(2) {
                try {
                    val active = ensureConnected(host)
                    send(active, AndroidTvProtocol.remoteKey(key.code))
                    return@withContext
                } catch (failure: Throwable) {
                    lastFailure = failure
                    closeSocket()
                }
            }
            throw lastFailure ?: IllegalStateException("Android TV-kommandot misslyckades")
        }
    }

    private fun ensureConnected(host: String): SSLSocket {
        socket?.takeIf { connectedHost == host && !it.isClosed && it.isConnected }?.let { return it }
        closeSocket()
        val fresh = identity.sslContext().socketFactory.createSocket() as SSLSocket
        try {
            fresh.connect(InetSocketAddress(host, AndroidTvProtocol.REMOTE_PORT), CONNECT_TIMEOUT_MS)
            fresh.soTimeout = READ_TIMEOUT_MS
            fresh.startHandshake()
            var started = false
            for (ignored in 0 until MAX_HANDSHAKE_MESSAGES) {
                val payload = ProtoWire.readFrame(fresh.inputStream)
                val fields = AndroidTvProtocol.topLevelFields(payload)
                when {
                    1 in fields -> send(fresh, AndroidTvProtocol.remoteConfigure())
                    2 in fields -> send(fresh, AndroidTvProtocol.remoteSetActive())
                    8 in fields -> AndroidTvProtocol.pingValue(payload)?.let {
                        send(fresh, AndroidTvProtocol.remotePingResponse(it))
                    }
                }
                if (40 in fields) {
                    started = true
                    break
                }
            }
            check(started) { "Android TV blev inte redo för kommandon" }
            socket = fresh
            connectedHost = host
            scope.launch { readLoop(fresh) }
            return fresh
        } catch (throwable: Throwable) {
            runCatching { fresh.close() }
            throw throwable
        }
    }

    private fun readLoop(active: SSLSocket) {
        try {
            while (!active.isClosed) {
                val payload = ProtoWire.readFrame(active.inputStream)
                val fields = AndroidTvProtocol.topLevelFields(payload)
                when {
                    8 in fields -> AndroidTvProtocol.pingValue(payload)?.let {
                        send(active, AndroidTvProtocol.remotePingResponse(it))
                    }
                    2 in fields -> send(active, AndroidTvProtocol.remoteSetActive())
                    1 in fields -> send(active, AndroidTvProtocol.remoteConfigure())
                }
            }
        } catch (_: SocketException) {
            // Normal when reconnecting or leaving the app.
        } catch (_: Throwable) {
            // A later command reconnects and surfaces any persistent failure.
        } finally {
            if (socket === active) closeSocket()
        }
    }

    private fun send(target: SSLSocket, payload: ByteArray) = synchronized(outputLock) {
        ProtoWire.frame(target.outputStream, payload)
    }

    private fun closeSocket() {
        val previous = socket
        socket = null
        connectedHost = null
        runCatching { previous?.close() }
    }

    fun disconnect() = closeSocket()

    override fun close() {
        closeSocket()
        scope.cancel()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
        const val PROBE_TIMEOUT_MS = 400
        const val READ_TIMEOUT_MS = 12_000
        const val MAX_HANDSHAKE_MESSAGES = 12
    }
}
