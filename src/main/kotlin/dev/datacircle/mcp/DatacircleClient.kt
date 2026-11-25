package dev.datacircle.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import javax.net.ssl.SSLException
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** Raised when a Datacircle API request fails. [statusCode] is the HTTP status, or -1 when no HTTP answer came. */
internal class DatacircleException(message: String, val statusCode: Int = -1, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Low-level client for the open Datacircle API at `https://api.datacircle.dev/v1`.
 *
 * The API needs no key and no account. It allows 60 calls a minute from one address.
 */
internal class DatacircleClient(baseUrl: String, private val timeout: Duration) {

    init {
        require(timeout.isPositive()) { "timeout must be positive" }
    }

    private val baseUrl = normalizeBaseUrl(baseUrl)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeout.toJavaDuration())
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** `GET /apis`: every API on the sheet, in its order, an array. */
    suspend fun listApis(): JsonElement = get("/apis")

    /** `GET /apis/{slug}/best`: one API's plans, best first by score. */
    suspend fun bestPlans(slug: String?, limit: Int?): JsonElement =
        get("/apis/" + encodePath(normalizeSlug(slug, "api")) + "/best?limit=" + normalizeLimit(limit))

    /** `GET /plans`: the plans of every API, or of one, by words in the provider's or plan's name. */
    suspend fun searchPlans(api: String?, query: String?, sort: String?, limit: Int?, offset: Int?): JsonElement {
        val path = StringBuilder("/plans?sort=")
            .append(normalizeSort(sort))
            .append("&limit=")
            .append(normalizeLimit(limit))
            .append("&offset=")
            .append(normalizeOffset(offset))
        appendOptional(path, "api", api)
        appendOptional(path, "query", query)
        return get(path.toString())
    }

    /** `GET /providers/{slug}`: one provider's plans and rank on each API it sells. */
    suspend fun provider(name: String?): JsonElement = get("/providers/" + encodePath(providerSlug(name)))

    private suspend fun get(path: String): JsonElement {
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(timeout.toJavaDuration())
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val response = try {
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DatacircleException(transportErrorMessage(e), cause = e)
        }
        val status = response.statusCode()
        val body: String? = response.body()
        if (status !in 200..299) {
            throw DatacircleException(httpErrorMessage(status, body), status)
        }
        if (body.isNullOrBlank()) {
            throw DatacircleException("The Datacircle API returned an empty response.")
        }
        return try {
            Json.parseToJsonElement(body)
        } catch (e: SerializationException) {
            throw DatacircleException("The Datacircle API returned invalid JSON.", cause = e)
        }
    }

    /** Says why no answer came: a timeout, an unknown host, a TLS failure, a refused or a lost connection. */
    private fun transportErrorMessage(error: Throwable): String {
        val causes = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(MAX_CAUSES).toList()
        return when {
            causes.any { it is HttpTimeoutException } ->
                "The Datacircle API did not answer within ${timeout.inWholeSeconds} s. Retry in a moment."
            causes.any { it is UnknownHostException || it is UnresolvedAddressException } ->
                "The Datacircle API's host ${URI.create(baseUrl).host} could not be found. " +
                    "Check the network and the base URL."
            causes.any { it is SSLException } ->
                "The secure connection to the Datacircle API failed (TLS). Check the network and the base URL."
            causes.any { it is ConnectException } ->
                "The Datacircle API refused the connection. Check the network and the base URL."
            causes.any { it is IOException } ->
                "The connection to the Datacircle API was lost before it answered. Retry in a moment."
            else -> "The Datacircle API request failed: ${error.javaClass.simpleName}."
        }
    }

    internal companion object {
        const val DEFAULT_BASE_URL = "https://api.datacircle.dev/v1"
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 50
        val SORTS = listOf("price", "provider", "score")

        private const val USER_AGENT = "mcp-kotlin-datacircle/0.1.0"
        private const val MAX_ERROR_LENGTH = 300
        private const val MAX_CAUSES = 10

        /** Turns a provider's name into its slug: accents dropped, lowercase letters and digits, the rest a hyphen. */
        fun providerSlug(name: String?): String {
            val value = ensureNotBlank(name, "provider")
            val slug = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replace(Regex("\\p{M}"), "")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
            require(slug.isNotEmpty()) {
                "Datacircle writes each provider's name in Latin letters, so \"${value.trim()}\" finds none. Pass " +
                    "the name in Latin letters or its website's domain, like ihuyi for ihuyi.com, or find the " +
                    "provider with datacircle_search_plans."
            }
            return slug
        }

        private fun httpErrorMessage(statusCode: Int, body: String?): String {
            val sentence = errorSentence(body)
            val reason = when {
                sentence == null -> when (statusCode) {
                    400 -> "Invalid request. Check the tool arguments."
                    404 -> "Not found."
                    429 -> RATE_LIMIT
                    else -> "Request could not be completed."
                }
                statusCode == 429 -> "$sentence $RATE_LIMIT"
                else -> sentence
            }
            return "Datacircle API request failed: HTTP $statusCode. $reason"
        }

        private const val RATE_LIMIT = "The API allows 60 calls a minute from one address. Retry in a minute."

        /** The API's own one-line reason, from its `{"error": "..."}` body, or null. */
        private fun errorSentence(body: String?): String? {
            if (body.isNullOrBlank()) {
                return null
            }
            val parsed = try {
                Json.parseToJsonElement(body)
            } catch (e: SerializationException) {
                return null
            }
            val error = ((parsed as? JsonObject)?.get("error") as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.trim()
            if (error.isNullOrEmpty()) {
                return null
            }
            val sentence = if (error.length > MAX_ERROR_LENGTH) error.take(MAX_ERROR_LENGTH - 3) + "..." else error
            return if (sentence.endsWith(".")) sentence else "$sentence."
        }

        private fun appendOptional(path: StringBuilder, name: String, value: String?) {
            if (!value.isNullOrBlank()) {
                path.append('&').append(name).append('=').append(encode(value.trim()))
            }
        }

        private fun normalizeLimit(limit: Int?): Int {
            val value = limit ?: DEFAULT_LIMIT
            require(value in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
            return value
        }

        private fun normalizeOffset(offset: Int?): Int {
            val value = offset ?: 0
            require(value >= 0) { "offset must be 0 or more" }
            return value
        }

        private fun normalizeSort(sort: String?): String {
            if (sort.isNullOrBlank()) {
                return "score"
            }
            val normalized = sort.trim().lowercase(Locale.ROOT)
            require(normalized in SORTS) { "sort must be one of $SORTS" }
            return normalized
        }

        private fun normalizeSlug(slug: String?, name: String): String =
            ensureNotBlank(slug, name).trim().lowercase(Locale.ROOT)

        private fun ensureNotBlank(value: String?, name: String): String {
            require(!value.isNullOrBlank()) { "$name must not be empty" }
            return value
        }

        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

        private fun encodePath(value: String): String = encode(value).replace("+", "%20")

        private fun normalizeBaseUrl(baseUrl: String): String {
            val value = baseUrl.trim().trimEnd('/').ifEmpty { DEFAULT_BASE_URL }
            val uri = try {
                URI.create(value)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("baseUrl is not a valid URL", e)
            }
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            require(scheme == "http" || scheme == "https") { "baseUrl must use HTTP or HTTPS" }
            require(uri.host != null) { "baseUrl must include a host" }
            return value
        }
    }
}
