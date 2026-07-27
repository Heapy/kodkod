package io.heapy.kodkod

import java.io.ByteArrayOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets

/**
 * The production [DockerTransport]: speaks HTTP/1.1 directly over the unix domain socket
 * (`/var/run/docker.sock`) using only the JDK. Each call opens a fresh connection and sends
 * `Connection: close`, so there is no keep-alive state to manage; the response is read until the
 * socket closes and returned raw (still chunked if the server chunked it) for [DockerApi] to parse.
 */
class UnixSocketTransport(private val socketPath: String) : DockerTransport {
    override fun exchange(
        method: String,
        path: String,
        body: ByteArray?,
        headers: Map<String, String>,
        readTimeoutMs: Long,
    ): ByteArray {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(socketPath))

            val head = StringBuilder()
                .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: localhost\r\n")
                .apply { headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") } }
                .apply {
                    if (body != null || (method != "GET" && method != "HEAD")) {
                        append("Content-Length: ").append(body?.size ?: 0).append("\r\n")
                    }
                }
                .append("Connection: close\r\n\r\n")
                .toString()
                .toByteArray(StandardCharsets.US_ASCII)

            channel.configureBlocking(true)
            writeFully(channel, ByteBuffer.wrap(head))
            if (body != null && body.isNotEmpty()) writeFully(channel, ByteBuffer.wrap(body))

            return readUntilClose(channel, readTimeoutMs)
        }
    }

    private fun writeFully(channel: SocketChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    private fun readUntilClose(channel: SocketChannel, readTimeoutMs: Long): ByteArray {
        channel.configureBlocking(false)
        Selector.open().use { selector ->
            channel.register(selector, SelectionKey.OP_READ)
            val out = ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(16 * 1024)
            while (true) {
                if (selector.select(readTimeoutMs) == 0) {
                    throw DockerException(-1, "read timed out after ${readTimeoutMs}ms")
                }
                selector.selectedKeys().clear()
                buffer.clear()
                val read = channel.read(buffer)
                if (read < 0) break
                if (read > 0) out.write(buffer.array(), 0, read)
            }
            return out.toByteArray()
        }
    }
}
