/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.mcp.server

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/**
 * A small blocking HTTP/1.1 server used by the MCP transport.
 *
 * A dedicated accept thread hands every connection to a fixed size worker pool. Each connection
 * serves exactly one request and is then closed, which keeps the implementation simple and is
 * sufficient for local MCP clients.
 *
 * `com.sun.net.httpserver` is not available on Android, hence this small implementation.
 *
 * @author AndroidCodeStudio
 */
class McpHttpServer(
    private val host: String,
    private val port: Int,
    private val handler: (HttpRequest) -> HttpResponse,
) {

  private var serverSocket: ServerSocket? = null
  private var executor: ExecutorService? = null
  private var acceptThread: Thread? = null
  private val requestCounter = AtomicLong(0)

  val isRunning: Boolean
    @Synchronized get() = serverSocket?.isClosed == false

  val boundPort: Int
    @Synchronized get() = serverSocket?.localPort ?: -1

  val requestsServed: Long
    get() = requestCounter.get()

  /**
   * Binds the socket and starts accepting connections.
   *
   * @throws java.net.BindException when the address is already in use or not permitted.
   */
  @Synchronized
  fun start() {
    if (serverSocket != null) return

    val socket = ServerSocket()
    socket.reuseAddress = true
    socket.bind(InetSocketAddress(host, port))

    val pool =
        Executors.newFixedThreadPool(POOL_SIZE) { runnable ->
          Thread(runnable, "mcp-http-worker").apply { isDaemon = true }
        }

    serverSocket = socket
    executor = pool
    acceptThread =
        Thread({ acceptLoop(socket, pool) }, "mcp-http-accept").apply {
          isDaemon = true
          start()
        }
  }

  @Synchronized
  fun stop() {
    val socket = serverSocket
    serverSocket = null

    try {
      socket?.close()
    } catch (_: Exception) {}

    executor?.shutdownNow()
    executor = null

    acceptThread?.let { thread ->
      runCatching { thread.join(500) }
    }
    acceptThread = null
  }

  private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
    while (!socket.isClosed) {
      val client =
          try {
            socket.accept()
          } catch (_: Exception) {
            break
          }

      try {
        pool.execute { serve(client) }
      } catch (_: RejectedExecutionException) {
        closeQuietly(client)
      }
    }
  }

  private fun serve(client: Socket) {
    try {
      client.soTimeout = SOCKET_TIMEOUT_MS
      client.tcpNoDelay = true

      val input = client.getInputStream().buffered()
      val output = client.getOutputStream().buffered()

      val response =
          try {
            val request = HttpCodec.readRequest(input)
            requestCounter.incrementAndGet()
            handler(request)
          } catch (e: HttpProtocolException) {
            HttpResponse.json(
                e.status,
                """{"error":"bad_request","message":"${escape(e.message ?: "bad request")}"}""",
            )
          } catch (e: Exception) {
            HttpResponse.json(
                500,
                """{"error":"internal_error","message":"${escape(e.message ?: "internal error")}"}""",
            )
          }

      HttpCodec.writeResponse(output, response)
    } catch (_: Exception) {
      // Client went away or the socket timed out; nothing to do.
    } finally {
      closeQuietly(client)
    }
  }

  private fun closeQuietly(socket: Socket) {
    try {
      socket.close()
    } catch (_: Exception) {}
  }

  private fun escape(value: String): String {
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
  }

  companion object {

    private const val POOL_SIZE = 8
    private const val SOCKET_TIMEOUT_MS = 30_000
  }
}
