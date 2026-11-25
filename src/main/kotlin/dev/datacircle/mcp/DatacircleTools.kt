package dev.datacircle.mcp

import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.math.BigDecimal
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Adds the four Datacircle tools to this server, beside its own.
 *
 * The server must have the tools capability, as `ServerCapabilities(tools = ServerCapabilities.Tools())`.
 *
 * ```kotlin
 * val server = Server(
 *     Implementation(name = "my-server", version = "1.0.0"),
 *     ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())),
 * )
 * server.addDatacircleTools()
 * ```
 *
 * @param tools the tools to add, on `https://api.datacircle.dev/v1` with a 30 s timeout unless set otherwise
 */
public fun Server.addDatacircleTools(tools: DatacircleTools = DatacircleTools()) {
    tools.registeredTools.forEach { addTool(it.tool, it.handler) }
}

/**
 * MCP tools for the open [Datacircle](https://datacircle.dev) API, which benchmarks the GTM APIs (email finders,
 * company enrichment, LinkedIn data and more) and ranks every plan of each by its score out of 100 and its price per
 * 1,000.
 *
 * The API needs no key and no account. It allows 60 calls a minute from one address. Its spec is at
 * `https://api.datacircle.dev/v1/openapi.json`.
 *
 * @param baseUrl the API's base URL
 * @param timeout the connection and answer timeout of each call
 */
public class DatacircleTools(
    baseUrl: String = DEFAULT_BASE_URL,
    timeout: Duration = 30.seconds,
) {

    private val client = DatacircleClient(baseUrl, timeout)

    /** The four tools, in the order list, best, search, provider. */
    public val tools: List<Tool> = listOf(
        tool(
            LIST_APIS,
            "List the GTM APIs",
            "List the GTM APIs that Datacircle ranks (email finder, company enrichment, LinkedIn profile and more), " +
                "each with its slug, how many plans and providers it has, and its best plan.",
            buildJsonObject {
                putJsonObject("query") {
                    put("type", "string")
                    put(
                        "description",
                        "Words that must all appear in the API's name or slug, like email or linkedin. Leave empty " +
                            "to list every API",
                    )
                }
            },
        ),
        tool(
            BEST_PLANS,
            "Best plans of a GTM API",
            "Get the best plans of one GTM API, best first by Datacircle's score out of 100, with each plan's price " +
                "per 1,000 and its page.",
            buildJsonObject {
                putJsonObject("api") {
                    put("type", "string")
                    put("description", "The API's slug, as $LIST_APIS gives it, like email-finder")
                }
                put("limit", limitSchema())
            },
            required = listOf("api"),
        ),
        tool(
            SEARCH_PLANS,
            "Search the plans of the GTM APIs",
            "Search the plans of the GTM APIs on Datacircle by API, and by words in the provider's or plan's name, " +
                "sorted by score (best first), provider (A to Z) or price (lowest $ per 1,000 first).",
            buildJsonObject {
                putJsonObject("api") {
                    put("type", "string")
                    put(
                        "description",
                        "The API's name or slug, like email-finder. A provider's name goes in query. Defaults to " +
                            "every API",
                    )
                }
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Words that must all appear in the provider's or plan's name, like hunter")
                }
                putJsonObject("sort") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add(JsonPrimitive("score"))
                        add(JsonPrimitive("provider"))
                        add(JsonPrimitive("price"))
                    }
                    put("description", "Sort: score, provider or price. Defaults to score")
                }
                put("limit", limitSchema())
                putJsonObject("offset") {
                    put("type", "integer")
                    put("minimum", 0)
                    put("description", "Number of found plans to skip first. Defaults to 0")
                }
            },
        ),
        tool(
            PROVIDER,
            "A provider's ranks",
            "Get one provider's rank on each GTM API it sells on Datacircle, with its best plan there. A provider is " +
                "a company that sells the APIs, like Hunter or Bright Data.",
            buildJsonObject {
                putJsonObject("provider") {
                    put("type", "string")
                    put("description", "The provider's name or slug, like Bright Data or bright-data")
                }
            },
            required = listOf("provider"),
        ),
    )

    /** The four tools with their handlers, for `Server.addTools(...)` or a server's own registry. */
    public val registeredTools: List<RegisteredTool> =
        tools.map { tool -> RegisteredTool(tool) { request -> call(tool.name, request.arguments) } }

    /**
     * Calls one of the tools, as an MCP server's `tools/call` does.
     *
     * A failed call answers a result with `isError` set and a line that starts with `Error:` and says what to do.
     * It does not throw.
     *
     * @param name one of [LIST_APIS], [BEST_PLANS], [SEARCH_PLANS] and [PROVIDER]
     * @param arguments the call's arguments, or null for none
     * @return the tool's answer, short text
     */
    public suspend fun call(name: String, arguments: JsonObject? = null): CallToolResult {
        val args = arguments ?: JsonObject(emptyMap())
        return try {
            val text = when (name) {
                LIST_APIS -> {
                    val query = text(args, "query")
                    Answers.formatApis(client.listApis(), query)
                }
                BEST_PLANS -> Answers.formatBestPlans(client.bestPlans(text(args, "api"), number(args, "limit")))
                SEARCH_PLANS -> {
                    val offset = number(args, "offset")
                    Answers.formatSearch(
                        client.searchPlans(
                            text(args, "api"),
                            text(args, "query"),
                            text(args, "sort"),
                            number(args, "limit"),
                            offset,
                        ),
                        offset ?: 0,
                    )
                }
                PROVIDER -> Answers.formatProvider(client.provider(text(args, "provider")))
                else -> throw IllegalArgumentException(
                    "no Datacircle tool is named \"$name\". The tools are $LIST_APIS, $BEST_PLANS, $SEARCH_PLANS, " +
                        "$PROVIDER.",
                )
            }
            result(text, isError = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            result(Answers.formatError(e) + hint(name, args, e), isError = true)
        }
    }

    public companion object {
        /** Every GTM API, with its slug, its counts and its best plan. */
        public const val LIST_APIS: String = "datacircle_list_apis"

        /** One API's best plans, best first by score. */
        public const val BEST_PLANS: String = "datacircle_best_plans"

        /** The plans of every API, or of one, by provider or plan name. */
        public const val SEARCH_PLANS: String = "datacircle_search_plans"

        /** One provider's rank on each API it sells. */
        public const val PROVIDER: String = "datacircle_provider"

        /** The open API's address. */
        public const val DEFAULT_BASE_URL: String = DatacircleClient.DEFAULT_BASE_URL

        private fun limitSchema(): JsonObject = buildJsonObject {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", DatacircleClient.MAX_LIMIT)
            put("description", "Number of plans from 1 through ${DatacircleClient.MAX_LIMIT}. Defaults to 10")
        }

        private fun tool(
            name: String,
            title: String,
            description: String,
            properties: JsonObject,
            required: List<String>? = null,
        ): Tool = Tool(
            name = name,
            inputSchema = schema(properties, required),
            description = description,
            title = title,
            annotations = ToolAnnotations(
                title = title,
                readOnlyHint = true,
                destructiveHint = false,
                idempotentHint = true,
                openWorldHint = true,
            ),
        )

        /**
         * An input schema, read by the SDK's own serializer: `ToolSchema`'s constructor changed in 0.12.0, its JSON
         * did not, so one jar works on every SDK from 0.11.0.
         */
        private fun schema(properties: JsonObject, required: List<String>?): ToolSchema =
            SCHEMA_JSON.decodeFromJsonElement(
                ToolSchema.serializer(),
                buildJsonObject {
                    put("type", "object")
                    put("properties", properties)
                    if (required != null) {
                        putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
                    }
                },
            )

        private val SCHEMA_JSON = Json { ignoreUnknownKeys = true }

        private fun result(text: String, isError: Boolean): CallToolResult =
            CallToolResult(content = listOf(TextContent(text = text)), isError = isError)

        /** What to call next after a failure, when the slug or name passed may be wrong. */
        private fun hint(name: String, args: JsonObject, error: Exception): String {
            if (name == BEST_PLANS && Answers.isStatus(error, 404)) {
                return " Call $LIST_APIS for every API's slug."
            }
            val api = (args["api"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            if (name == SEARCH_PLANS && api.isNotBlank() &&
                (Answers.isStatus(error, 400) || Answers.isStatus(error, 404))
            ) {
                return " Call $LIST_APIS for every API's name and slug."
            }
            return ""
        }

        private fun text(args: JsonObject, name: String): String? = when (val value = args[name]) {
            null, JsonNull -> null
            is JsonPrimitive -> value.content
            is JsonObject, is JsonArray -> throw IllegalArgumentException("$name must be text")
        }

        /** A whole number, from a JSON number or from text that holds one; null when absent. */
        private fun number(args: JsonObject, name: String): Int? {
            val value = when (val element = args[name]) {
                null, JsonNull -> return null
                is JsonPrimitive -> element.content.trim()
                is JsonObject, is JsonArray -> throw IllegalArgumentException("$name must be a whole number")
            }
            if (value.isEmpty()) {
                return null
            }
            return try {
                BigDecimal(value).intValueExact()
            } catch (e: ArithmeticException) {
                throw IllegalArgumentException("$name must be a whole number")
            } catch (e: NumberFormatException) {
                throw IllegalArgumentException("$name must be a whole number")
            }
        }
    }
}
