package attractor.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ApiClient(private val ctx: CliContext) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private fun url(path: String) = "${ctx.baseUrl.trimEnd('/')}${path}"

    private fun assertOk(code: Int, body: String, @Suppress("UNUSED_PARAMETER") path: String) {
        if (code in 200..299) return
        val msg = try {
            val obj = json.parseToJsonElement(body).jsonObject
            obj["error"]?.jsonPrimitive?.content ?: "HTTP $code"
        } catch (_: Exception) {
            "HTTP $code"
        }
        throw CliException(msg, 1)
    }

    fun get(path: String): String {
        val req = Request.Builder().url(url(path)).get().build()
        return try {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                assertOk(resp.code, body, path)
                body
            }
        } catch (e: CliException) {
            throw e
        } catch (e: Exception) {
            throw CliException("Cannot connect to ${ctx.baseUrl}: ${e.message}", 1)
        }
    }

    fun post(path: String, body: String = "{}"): String {
        val reqBody = body.toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url(path)).post(reqBody).build()
        return try {
            http.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                assertOk(resp.code, respBody, path)
                respBody
            }
        } catch (e: CliException) {
            throw e
        } catch (e: Exception) {
            throw CliException("Cannot connect to ${ctx.baseUrl}: ${e.message}", 1)
        }
    }

    fun patch(path: String, body: String): String {
        val reqBody = body.toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url(path)).patch(reqBody).build()
        return try {
            http.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                assertOk(resp.code, respBody, path)
                respBody
            }
        } catch (e: CliException) {
            throw e
        } catch (e: Exception) {
            throw CliException("Cannot connect to ${ctx.baseUrl}: ${e.message}", 1)
        }
    }

    fun put(path: String, body: String): String {
        val reqBody = body.toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url(path)).put(reqBody).build()
        return try {
            http.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                assertOk(resp.code, respBody, path)
                respBody
            }
        } catch (e: CliException) {
            throw e
        } catch (e: Exception) {
            throw CliException("Cannot connect to ${ctx.baseUrl}: ${e.message}", 1)
        }
    }

    fun delete(path: String): String {
        val req = Request.Builder().url(url(path)).delete().build()
        return try {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                assertOk(resp.code, body, path)
                body
            }
        } catch (e: CliException) {
            throw e
        } catch (e: Exception) {
            throw CliException("Cannot connect to ${ctx.baseUrl}: ${e.message}", 1)
        }
    }

    fun getBinary(path: String): ByteArray {
        val req = Request.Builder().url(url(path)).get().build()
        return try {
            http.newCall(req).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: byteArrayOf()
                if (resp.code !in 200..299) {
                    val msg = try {
                        val obj = json.parseToJsonElement(bytes.decodeToString()).jsonObject
                        obj["error"]?.jsonPrimitive?.content ?: "HTTP ${resp.code}"
                    } catch (_: Exception) { "HTTP ${resp.code}" }
                    throw CliException(msg, 1)
                }
                bytes
            }
        } catch (e: CliException) {
            throw e
        } catch (e: Exception) {
            throw CliException("Cannot connect to ${ctx.baseUrl}: ${e.message}", 1)
        }
    }

    fun postBinary(path: String, data: ByteArray, query: String = ""): String {
        val fullPath = if (query.isNotBlank()) "$path?$query" else path
        val reqBody = data.toRequestBody("application/zip".toMediaType())
        val req = Request.Builder().url(url(fullPath)).post(reqBody).build()
        return try {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                assertOk(resp.code, body, path)
                body
            }
        } catch (e: CliException) {
            throw e
        } catch (e: Exception) {
            throw CliException("Cannot connect to ${ctx.baseUrl}: ${e.message}", 1)
        }
    }

    /** Reads an SSE stream, yielding the payload of each "data: ..." line. */
    fun getStream(path: String): Sequence<String> = sequence {
        val req = Request.Builder().url(url(path)).get().build()
        val resp = try {
            http.newCall(req).execute()
        } catch (e: Exception) {
            throw CliException("Cannot connect to ${ctx.baseUrl}: ${e.message}", 1)
        }
        if (resp.code !in 200..299) {
            val body = resp.body?.string() ?: ""
            resp.close()
            val msg = try {
                json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content ?: "HTTP ${resp.code}"
            } catch (_: Exception) { "HTTP ${resp.code}" }
            throw CliException(msg, 1)
        }
        resp.body?.use { body ->
            val reader = body.byteStream().bufferedReader()
            var line = reader.readLine()
            while (line != null) {
                if (line.startsWith("data:")) {
                    yield(line.removePrefix("data:").trim())
                }
                line = reader.readLine()
            }
        }
    }
}
