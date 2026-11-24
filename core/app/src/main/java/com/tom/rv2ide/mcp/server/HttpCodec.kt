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

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/**
 * A minimal HTTP/1.1 codec.
 *
 * Only what the MCP transport needs is implemented: request line, headers, `Content-Length` and
 * `Transfer-Encoding: chunked` bodies, and single-shot responses sent with `Connection: close`.
 *
 * This file intentionally has no Android dependencies so that it can be compiled and tested on a
 * plain JVM.
 *
 * @author AndroidCodeStudio
 */
class HttpProtocolException(val status: Int, message: String) : Exception(message)

/** A parsed HTTP request. */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
) {

  fun header(name: String): String? = headers[name.lowercase()]

  fun bodyText(): String = String(body, Charsets.UTF_8)

  fun queryParameter(name: String): String? = query[name]

  override fun toString(): String = "$method $path (${body.size} bytes)"
}

/** A response to be written back to the client. */
class HttpResponse(
    val status: Int,
    val body: ByteArray = ByteArray(0),
    val contentType: String? = "application/json; charset=utf-8",
    val headers: Map<String, String> = emptyMap(),
) {

  companion object {

    fun json(status: Int, text: String, headers: Map<String, String> = emptyMap()): HttpResponse {
      return HttpResponse(status, text.toByteArray(Charsets.UTF_8), JSON_CONTENT_TYPE, headers)
    }

    fun empty(status: Int, headers: Map<String, String> = emptyMap()): HttpResponse {
      return HttpResponse(status, ByteArray(0), null, headers)
    }

    fun unauthorized(message: String = "Missing or invalid access token"): HttpResponse {
      return json(
          401,
          """{"error":"unauthorized","message":"$message"}""",
          mapOf("WWW-Authenticate" to "Bearer"),
      )
    }

    fun notFound(message: String = "Not found"): HttpResponse {
      return json(404, """{"error":"not_found","message":"$message"}""")
    }

    fun badRequest(message: String): HttpResponse {
      return json(400, """{"error":"bad_request","message":"${escape(message)}"}""")
    }

    private fun escape(value: String): String {
      return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
  }
}

object HttpCodec {

  /** Maximum accepted request body size (8 MiB). */
  const val MAX_BODY_BYTES = 8 * 1024 * 1024

  private const val MAX_LINE_BYTES = 8192
  private const val MAX_HEADER_COUNT = 100

  private val REASONS =
      mapOf(
          200 to "OK",
          202 to "Accepted",
          204 to "No Content",
          400 to "Bad Request",
          401 to "Unauthorized",
          403 to "Forbidden",
          404 to "Not Found",
          405 to "Method Not Allowed",
          413 to "Payload Too Large",
          415 to "Unsupported Media Type",
          431 to "Request Header Fields Too Large",
          500 to "Internal Server Error",
          503 to "Service Unavailable",
      )

  /**
   * Reads a single request from the given stream.
   *
   * @throws HttpProtocolException if the request is malformed or too large.
   */
  fun readRequest(input: InputStream): HttpRequest {
    val requestLine = readLine(input) ?: throw HttpProtocolException(400, "Connection closed")
    if (requestLine.isBlank()) {
      throw HttpProtocolException(400, "Empty request line")
    }

    val parts = requestLine.split(' ')
    if (parts.size < 3) {
      throw HttpProtocolException(400, "Malformed request line")
    }

    val method = parts[0].uppercase()
    val target = parts[1]

    val headers = LinkedHashMap<String, String>()
    var count = 0
    while (true) {
      val line = readLine(input) ?: throw HttpProtocolException(400, "Unexpected end of headers")
      if (line.isEmpty()) break
      if (++count > MAX_HEADER_COUNT) {
        throw HttpProtocolException(431, "Too many headers")
      }
      val separator = line.indexOf(':')
      if (separator <= 0) {
        throw HttpProtocolException(400, "Malformed header: $line")
      }
      headers[line.substring(0, separator).trim().lowercase()] =
          line.substring(separator + 1).trim()
    }

    val queryMark = target.indexOf('?')
    val path = if (queryMark < 0) target else target.substring(0, queryMark)
    val query =
        if (queryMark < 0) emptyMap() else parseQuery(target.substring(queryMark + 1))

    val body = readBody(input, headers)
    return HttpRequest(
        method = method,
        path = path.ifEmpty { "/" },
        query = query,
        headers = headers,
        body = body,
    )
  }

  /** Reads a CRLF/LF terminated line, or returns `null` on a clean EOF. */
  fun readLine(input: InputStream, limit: Int = MAX_LINE_BYTES): String? {
    val buffer = ByteArrayOutputStream()
    var sawAny = false
    while (true) {
      val read = input.read()
      if (read == -1) {
        if (!sawAny && buffer.size() == 0) return null
        throw HttpProtocolException(400, "Unexpected EOF while reading a line")
      }
      sawAny = true
      if (read == '\n'.code) break
      if (read == '\r'.code) continue
      buffer.write(read)
      if (buffer.size() > limit) {
        throw HttpProtocolException(431, "Line too long")
      }
    }
    return buffer.toString("UTF-8")
  }

  fun parseQuery(raw: String): Map<String, String> {
    if (raw.isEmpty()) return emptyMap()
    val result = LinkedHashMap<String, String>()
    raw.split('&').filter { it.isNotEmpty() }.forEach { pair ->
      val separator = pair.indexOf('=')
      if (separator < 0) {
        result[decode(pair)] = ""
      } else {
        result[decode(pair.substring(0, separator))] = decode(pair.substring(separator + 1))
      }
    }
    return result
  }

  private fun decode(value: String): String {
    return try {
      URLDecoder.decode(value, "UTF-8")
    } catch (e: Exception) {
      value
    }
  }

  private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
    val transferEncoding = headers["transfer-encoding"]
    if (transferEncoding != null && transferEncoding.contains("chunked", ignoreCase = true)) {
      return readChunkedBody(input)
    }

    val declaredLength = headers["content-length"]?.toLongOrNull() ?: return ByteArray(0)
    if (declaredLength > MAX_BODY_BYTES) {
      throw HttpProtocolException(413, "Request body too large")
    }
    if (declaredLength <= 0L) return ByteArray(0)
    return readFully(input, declaredLength.toInt())
  }

  private fun readChunkedBody(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    while (true) {
      val sizeLine = readLine(input) ?: throw HttpProtocolException(400, "Truncated chunked body")
      val sizeText = sizeLine.substringBefore(';').trim()
      val size =
          sizeText.toIntOrNull(16)
              ?: throw HttpProtocolException(400, "Invalid chunk size: $sizeText")
      if (size == 0) {
        // Consume trailers.
        while (true) {
          val trailer = readLine(input) ?: break
          if (trailer.isEmpty()) break
        }
        break
      }
      if (output.size().toLong() + size > MAX_BODY_BYTES) {
        throw HttpProtocolException(413, "Request body too large")
      }
      output.write(readFully(input, size))
      readLine(input) // trailing CRLF of the chunk
    }
    return output.toByteArray()
  }

  private fun readFully(input: InputStream, length: Int): ByteArray {
    val buffer = ByteArray(length)
    var offset = 0
    while (offset < length) {
      val read = input.read(buffer, offset, length - offset)
      if (read == -1) throw HttpProtocolException(400, "Unexpected EOF in request body")
      offset += read
    }
    return buffer
  }

  /** Writes [response] and flushes it. The connection is closed by the caller. */
  fun writeResponse(output: OutputStream, response: HttpResponse) {
    val reason = REASONS[response.status] ?: "Unknown"
    val builder = StringBuilder()
    builder.append("HTTP/1.1 ").append(response.status).append(' ').append(reason).append("\r\n")

    response.headers.forEach { (name, value) ->
      builder.append(name).append(": ").append(value).append("\r\n")
    }
    response.contentType?.let { builder.append("Content-Type: ").append(it).append("\r\n") }
    builder.append("Content-Length: ").append(response.body.size).append("\r\n")
    builder.append("Cache-Control: no-store\r\n")
    builder.append("Connection: close\r\n")
    builder.append("\r\n")

    output.write(builder.toString().toByteArray(Charsets.UTF_8))
    if (response.body.isNotEmpty()) {
      output.write(response.body)
    }
    output.flush()
  }
}
