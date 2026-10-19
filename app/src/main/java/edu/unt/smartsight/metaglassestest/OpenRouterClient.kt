package edu.unt.smartsight.metaglassestest

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class OpenRouterRequest(
    val url: String,
    val apiKey: String,
    val body: String,
)

/**
 * Sends one JPEG and the final transcript to OpenRouter.
 * The API key is supplied by the caller and is never logged.
 */
internal class OpenRouterClient(
    private val apiKey: String,
    private val model: String,
    private val timeoutMs: Int = TIMEOUT_MS,
    private val post: suspend (OpenRouterRequest) -> String = { request ->
        postOpenRouter(request, timeoutMs)
    },
) {
    suspend fun complete(jpeg: ByteArray, transcript: String): Result<String> {
        if (apiKey.isBlank()) {
            return Result.failure(IOException("OpenRouter API key is not configured."))
        }
        val request = OpenRouterRequest(
            url = COMPLETIONS_URL,
            apiKey = apiKey,
            body = requestBody(model, transcript, jpeg),
        )
        return try {
            val raw = withTimeout(timeoutMs.toLong()) { post(request) }
            val text = parseContent(raw)
            if (text.isNullOrBlank()) {
                Result.failure(IOException("Could not answer."))
            } else {
                Result.success(text)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message = error.message?.takeIf { it.isNotBlank() } ?: "Could not answer."
            Result.failure(IOException(message, error))
        }
    }

    companion object {
        const val COMPLETIONS_URL = "https://openrouter.ai/api/v1/chat/completions"
        const val TIMEOUT_MS = 30_000

        fun requestBody(model: String, transcript: String, jpeg: ByteArray): String {
            val encoded = Base64.getEncoder().encodeToString(jpeg)
            val imageUrl = "data:image/jpeg;base64,$encoded"
            return buildString {
                append("{\"model\":")
                append(jsonString(model))
                append(",\"messages\":[{\"role\":\"user\",\"content\":[")
                append("{\"type\":\"text\",\"text\":")
                append(jsonString(transcript))
                append("},{\"type\":\"image_url\",\"image_url\":{\"url\":")
                append(jsonString(imageUrl))
                append("}}]}]}")
            }
        }

        fun parseContent(body: String): String? {
            val root = try {
                parseJson(body) as? Map<*, *>
            } catch (error: IllegalArgumentException) {
                return null
            } ?: return null
            val choices = root["choices"] as? List<*> ?: return null
            if (choices.isEmpty()) {
                return null
            }
            val message = (choices[0] as? Map<*, *>)?.get("message") as? Map<*, *> ?: return null
            return when (val content = message["content"]) {
                is String -> content.trim().takeIf { it.isNotEmpty() }
                is List<*> -> joinTextParts(content)
                else -> null
            }
        }

        private fun joinTextParts(content: List<*>): String? {
            val parts = content.mapNotNull { part ->
                val text = (part as? Map<*, *>)?.get("text") as? String
                text?.trim()?.takeIf { it.isNotEmpty() }
            }
            return parts.joinToString(" ").takeIf { it.isNotEmpty() }
        }
    }
}

private fun jsonString(value: String): String {
    return buildString {
        append('"')
        for (char in value) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
        append('"')
    }
}

private fun parseJson(body: String): Any? {
    val reader = JsonReader(body)
    val value = reader.parse()
    return value
}

private class JsonReader(private val text: String) {
    private var index = 0

    fun parse(): Any? {
        val value = readValue()
        skip()
        if (index != text.length) {
            throw IllegalArgumentException("Trailing JSON")
        }
        return value
    }

    private fun readValue(): Any? {
        skip()
        if (index >= text.length) {
            throw IllegalArgumentException("Unexpected end of JSON")
        }
        return when (text[index]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> readLiteral("true", true)
            'f' -> readLiteral("false", false)
            'n' -> readLiteral("null", null)
            else -> readNumber()
        }
    }

    private fun readObject(): Map<String, Any?> {
        expect('{')
        val map = linkedMapOf<String, Any?>()
        skip()
        if (peek('}')) {
            index += 1
            return map
        }
        while (index < text.length) {
            skip()
            val key = readString()
            skip()
            expect(':')
            map[key] = readValue()
            skip()
            when {
                peek('}') -> {
                    index += 1
                    return map
                }
                peek(',') -> index += 1
                else -> throw IllegalArgumentException("Expected , or }")
            }
        }
        throw IllegalArgumentException("Unclosed object")
    }

    private fun readArray(): List<Any?> {
        expect('[')
        val list = mutableListOf<Any?>()
        skip()
        if (peek(']')) {
            index += 1
            return list
        }
        while (index < text.length) {
            list += readValue()
            skip()
            when {
                peek(']') -> {
                    index += 1
                    return list
                }
                peek(',') -> index += 1
                else -> throw IllegalArgumentException("Expected , or ]")
            }
        }
        throw IllegalArgumentException("Unclosed array")
    }

    private fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (index < text.length) {
            val char = text[index]
            index += 1
            when (char) {
                '"' -> return out.toString()
                '\\' -> out.append(readEscape())
                else -> out.append(char)
            }
        }
        throw IllegalArgumentException("Unclosed string")
    }

    private fun readEscape(): Char {
        if (index >= text.length) {
            throw IllegalArgumentException("Bad escape")
        }
        val escaped = text[index]
        index += 1
        return when (escaped) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                if (index + 4 > text.length) {
                    throw IllegalArgumentException("Bad unicode escape")
                }
                val hex = text.substring(index, index + 4)
                index += 4
                hex.toInt(16).toChar()
            }
            else -> throw IllegalArgumentException("Bad escape")
        }
    }

    private fun readNumber(): String {
        val start = index
        if (peek('-')) {
            index += 1
        }
        while (index < text.length && text[index].isDigit()) {
            index += 1
        }
        if (peek('.')) {
            index += 1
            while (index < text.length && text[index].isDigit()) {
                index += 1
            }
        }
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            index += 1
            if (peek('+') || peek('-')) {
                index += 1
            }
            while (index < text.length && text[index].isDigit()) {
                index += 1
            }
        }
        if (start == index) {
            throw IllegalArgumentException("Expected JSON value")
        }
        return text.substring(start, index)
    }

    private fun readLiteral(literal: String, value: Any?): Any? {
        if (!text.startsWith(literal, index)) {
            throw IllegalArgumentException("Expected $literal")
        }
        index += literal.length
        return value
    }

    private fun skip() {
        while (index < text.length && text[index].isWhitespace()) {
            index += 1
        }
    }

    private fun expect(char: Char) {
        skip()
        if (index >= text.length || text[index] != char) {
            throw IllegalArgumentException("Expected $char")
        }
        index += 1
    }

    private fun peek(char: Char): Boolean = index < text.length && text[index] == char
}

private suspend fun postOpenRouter(request: OpenRouterRequest, timeoutMs: Int): String {
    return withContext(Dispatchers.IO) {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${request.apiKey}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { output ->
                output.write(request.body.toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("Could not answer.")
            }
            raw
        } finally {
            connection.disconnect()
        }
    }
}
