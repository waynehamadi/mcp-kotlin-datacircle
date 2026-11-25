package dev.datacircle.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.util.Locale

/**
 * Writes the API's answers as the short text each tool gives: one API or plan a line, with its page on
 * datacircle.dev.
 */
internal object Answers {

    private const val MAX_TEXT_LENGTH = 120
    private const val MAX_PROVIDER_APIS = 100

    fun formatApis(apis: JsonElement, query: String?): String {
        if (apis !is JsonArray) {
            throw unexpected("the list of APIs is not a list")
        }
        if (apis.isEmpty()) {
            return "The Datacircle API returned no APIs."
        }
        val words = words(query)
        val lines = mutableListOf<String>()
        for (api in apis) {
            val name = required(api, "api")
            val slug = required(api, "slug")
            if (!matchesAll("$name $slug", words)) {
                continue
            }
            val line = StringBuilder("- ")
                .append(truncate(name))
                .append(" (")
                .append(slug)
                .append("): ")
                .append(count(field(api, "plans"), "plan"))
                .append(" from ")
                .append(count(field(api, "providers"), "provider"))
                .append('.')
            val best = field(api, "best")
            if (best is JsonObject) {
                line.append(" Best: ")
                    .append(truncate(required(best, "provider")))
                    .append(", ")
                    .append(truncate(required(best, "plan")))
                    .append(", ")
                    .append(score(field(best, "score")))
                    .append(", ")
                    .append(price(field(best, "perThousand"), null))
                    .append('.')
            }
            lines.add(line.toString())
        }
        if (lines.isEmpty()) {
            return "No API's name or slug holds every word of \"${query.orEmpty().trim()}\". Leave the query empty " +
                "to list ${apis.size} APIs."
        }
        return lines.joinToString("\n") + "\n${lines.size} of ${apis.size} APIs. Pass a slug to " +
            "${DatacircleTools.BEST_PLANS} or ${DatacircleTools.SEARCH_PLANS}."
    }

    fun formatBestPlans(response: JsonElement): String {
        val plans = list(response, "plans")
        val result = StringBuilder()
            .append(required(response, "api"))
            .append(" (")
            .append(required(response, "slug"))
            .append("): ")
        if (plans.isEmpty()) {
            return result.append("no plans found.").toString()
        }
        result.append("the best ")
            .append(plans.size)
            .append(" of ")
            .append(count(field(response, "total"), "plan"))
            .append('.')
        appendIfPresent(result, "Sheet", text(response, "sheetPage"))
        for (plan in plans) {
            result.append('\n').append(formatPlan(plan, withApi = false))
        }
        return result.toString()
    }

    fun formatSearch(response: JsonElement, requestedOffset: Int): String {
        val plans = list(response, "plans")
        val total = number(field(response, "total"))
        val offset = number(field(response, "offset"))?.toInt() ?: requestedOffset
        if (plans.isEmpty()) {
            if (total != null && total.signum() > 0) {
                return "Found ${count(field(response, "total"), "plan")}, but the offset $offset is past the last " +
                    "one. Use an offset below ${total.toPlainString()}."
            }
            return "No plans found."
        }
        val result = StringBuilder("Found ")
            .append(count(field(response, "total"), "plan"))
            .append("; showing ")
            .append(offset + 1)
            .append(" to ")
            .append(offset + plans.size)
            .append('.')
        appendIfPresent(result, "Sheet", text(response, "sheetPage"))
        for (plan in plans) {
            result.append('\n').append(formatPlan(plan, withApi = true))
        }
        return result.toString()
    }

    fun formatProvider(response: JsonElement): String {
        val apis = list(response, "apis")
        val result = StringBuilder(required(response, "provider"))
        val website = text(response, "website")
        if (website.isNotEmpty()) {
            result.append(" (").append(website).append(')')
        }
        if (apis.isEmpty()) {
            return result.append(" sells no API on Datacircle.").toString()
        }
        result.append(" sells ").append(plural(apis.size.toLong(), "API")).append(". Its rank among each API's providers:")
        for ((shown, api) in apis.withIndex()) {
            if (shown == MAX_PROVIDER_APIS) {
                result.append('\n')
                    .append("And ")
                    .append(plural((apis.size - shown).toLong(), "more API"))
                    .append('.')
                break
            }
            val rank = number(field(api, "rank"))
            val plans = list(api, "plans")
            result.append('\n')
                .append("- ")
                .append(truncate(required(api, "api")))
                .append(" (")
                .append(required(api, "slug"))
                .append("): ")
                .append(if (rank != null) "rank ${rank.toInt()}" else "not ranked")
                .append(", ")
                .append(plural(plans.size.toLong(), "plan"))
                .append('.')
            val best = plans.filter { number(field(it, "score")) != null }.maxByOrNull { number(field(it, "score"))!! }
            if (best != null) {
                result.append(" Best: ")
                    .append(truncate(required(best, "plan")))
                    .append(", ")
                    .append(score(field(best, "score")))
                    .append(", ")
                    .append(price(field(best, "perThousand"), field(best, "per")))
                    .append('.')
            }
        }
        return result.toString()
    }

    fun formatError(error: Throwable): String {
        val message = error.message
        return if (message.isNullOrBlank()) "Error: the Datacircle API request failed." else "Error: $message"
    }

    fun isStatus(error: Throwable, statusCode: Int): Boolean =
        error is DatacircleException && error.statusCode == statusCode

    private fun formatPlan(plan: JsonElement, withApi: Boolean): String {
        val line = StringBuilder()
        val rank = number(field(plan, "rank"))
        line.append(if (!withApi && rank != null) "${rank.toInt()}. " else "- ")
            .append(truncate(required(plan, "provider")))
            .append(", ")
            .append(truncate(required(plan, "plan")))
        if (withApi) {
            line.append(" (").append(truncate(required(plan, "api"))).append(')')
        }
        line.append(": ")
        if (withApi && rank != null) {
            line.append("rank ").append(rank.toInt()).append(", ")
        }
        line.append(score(field(plan, "score")))
            .append(", ")
            .append(price(field(plan, "perThousand"), field(plan, "per")))
        number(field(plan, "notFoundPerThousand"))?.let {
            line.append(", ").append(dollars(it)).append(" per 1,000 not found")
        }
        val billing = text(plan, "billing")
        if (billing.isNotEmpty()) {
            line.append(", ").append(billing)
        }
        number(field(plan, "latencyMs"))?.let {
            line.append(", median ").append(it.toLong()).append(" ms")
        }
        line.append('.')
        val page = text(plan, "page")
        if (page.isNotEmpty()) {
            line.append(' ').append(page)
        }
        return line.toString()
    }

    private fun score(score: JsonElement?): String =
        number(score)?.let { "score " + it.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "/100" }
            ?: "not scored"

    private fun price(perThousand: JsonElement?, per: JsonElement?): String {
        val amount = number(perThousand) ?: return "no listed price"
        val word = (per as? JsonPrimitive)?.takeIf { it.isString }?.content
        val unit = if (word.isNullOrBlank()) "" else " ${word}s"
        return "${dollars(amount)} per 1,000$unit"
    }

    private fun dollars(amount: BigDecimal): String = "$" + amount.stripTrailingZeros().toPlainString()

    private fun count(value: JsonElement?, word: String): String =
        number(value)?.let { plural(it.toLong(), word) } ?: "? ${word}s"

    private fun plural(count: Long, word: String): String =
        String.format(Locale.ROOT, "%,d", count) + " " + word + if (count == 1L) "" else "s"

    private fun words(query: String?): List<String> =
        if (query.isNullOrBlank()) emptyList() else fold(query).trim().split(Regex("[\\s-]+"))

    private fun matchesAll(text: String, words: List<String>): Boolean {
        val folded = fold(text)
        return words.all { folded.contains(it) }
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKD).replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)

    private fun appendIfPresent(result: StringBuilder, label: String, value: String) {
        if (value.isNotEmpty()) {
            result.append(' ').append(label).append(": ").append(value)
        }
    }

    private fun field(node: JsonElement?, name: String): JsonElement? =
        (node as? JsonObject)?.get(name)?.takeUnless { it is JsonNull }

    /** A JSON number, or null when the field is absent, null or not a number. */
    private fun number(value: JsonElement?): BigDecimal? =
        (value as? JsonPrimitive)?.takeUnless { it.isString || it is JsonNull }?.content?.toBigDecimalOrNull()

    private fun required(node: JsonElement?, name: String): String {
        val value = text(node, name)
        if (value.isEmpty()) {
            throw unexpected("$name is missing")
        }
        return value
    }

    private fun list(node: JsonElement?, name: String): JsonArray =
        field(node, name) as? JsonArray ?: throw unexpected("$name is not a list")

    private fun text(node: JsonElement?, name: String): String =
        (field(node, name) as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content ?: ""

    private fun unexpected(what: String): DatacircleException = DatacircleException(
        "The Datacircle API returned an answer of an unexpected shape: $what. Retry later, or read " +
            "https://api.datacircle.dev/v1/openapi.json.",
    )

    private fun truncate(text: String): String =
        if (text.length <= MAX_TEXT_LENGTH) text else text.take(MAX_TEXT_LENGTH - 3) + "..."
}
