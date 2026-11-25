package dev.datacircle.mcp

import com.sun.net.httpserver.HttpServer
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** A local stand-in for the Datacircle API, answering the shapes of its spec. */
class LocalApi : AutoCloseable {

    val requests: MutableList<String> = CopyOnWriteArrayList()

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/v1/") { exchange ->
            val uri = exchange.requestURI
            val target = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
            requests.add(target)
            val (status, body) = when {
                uri.path == "/v1/apis" -> 200 to APIS
                uri.path == "/v1/apis/email-finder/best" -> 200 to BEST
                uri.path == "/v1/apis/broken/best" -> 200 to "{\"api\": "
                uri.path == "/v1/plans" && target.contains("query=busy") -> 429 to "{\"error\": \"too many calls\"}"
                uri.path == "/v1/plans" && target.contains("api=nope") ->
                    400 to "{\"error\": \"api must be one of: Employee email\"}"
                uri.path == "/v1/plans" -> 200 to PLANS
                uri.path == "/v1/providers/bright-data" -> 200 to PROVIDER
                else -> 404 to "{\"error\": \"no API has the slug nope\", \"slugs\": [\"email-finder\"]}"
            }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    val baseUrl: String = "http://127.0.0.1:${server.address.port}/v1/"

    override fun close() {
        server.stop(0)
    }

    companion object {
        const val APIS = """
            [{"api": "Employee email", "slug": "email-finder", "plans": 5444, "providers": 300,
              "best": {"provider": "Emelia", "plan": "100,000 credits per month", "score": 76.6, "perThousand": 4.99}},
             {"api": "Company profile", "slug": "linkedin-company", "plans": 1, "providers": 1, "best": null}]
        """
        const val BEST = """
            {"api": "Employee email", "slug": "email-finder", "total": 5444,
             "sheetPage": "https://datacircle.dev/email-finder-api-pricing",
             "plans": [{"provider": "Emelia", "plan": "Pro", "api": "Employee email", "rank": 1, "score": 76.64,
                        "perThousand": 4.99, "per": "call", "notFoundPerThousand": 0, "billing": "monthly",
                        "latencyMs": 812, "page": "https://datacircle.dev/find-email-apis/emelia/pro"}]}
        """
        const val PLANS = """
            {"total": 2, "offset": 1, "plans": [{"provider": "Hunter", "plan": "Starter", "api": "People search",
             "rank": 18, "score": null, "perThousand": null, "page": "https://datacircle.dev/p/hunter"}]}
        """
        const val PROVIDER = """
            {"provider": "Bright Data", "slug": "bright-data", "website": "https://brightdata.com",
             "apis": [{"api": "Employee profile", "slug": "linkedin-profile", "rank": 3,
                       "plans": [{"plan": "Pay as you go", "score": 60, "perThousand": 1.5, "per": "record"},
                                 {"plan": "Growth", "score": 71.25, "perThousand": 1.05, "per": "record"}]},
                      {"api": "SERP", "slug": "serp", "rank": null, "plans": []}]}
        """
        const val BEST_ANSWER = "Employee email (email-finder): the best 1 of 5,444 plans. " +
            "Sheet: https://datacircle.dev/email-finder-api-pricing\n" +
            "1. Emelia, Pro: score 76.6/100, \$4.99 per 1,000 calls, \$0 per 1,000 not found, monthly, " +
            "median 812 ms. https://datacircle.dev/find-email-apis/emelia/pro"
    }
}

fun CallToolResult.text(): String = content.joinToString("\n") { (it as? TextContent)?.text ?: it.toString() }
