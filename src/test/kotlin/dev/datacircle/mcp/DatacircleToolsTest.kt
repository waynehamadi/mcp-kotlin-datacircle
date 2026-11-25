package dev.datacircle.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DatacircleToolsTest {

    private lateinit var api: LocalApi
    private lateinit var tools: DatacircleTools

    @BeforeTest
    fun start() {
        api = LocalApi()
        tools = DatacircleTools(api.baseUrl, 5.seconds)
    }

    @AfterTest
    fun stop() {
        api.close()
    }

    private fun answer(tool: String, arguments: JsonObject? = null): String = runBlocking {
        val result = tools.call(tool, arguments)
        assertNotEquals(true, result.isError, result.text())
        result.text()
    }

    private fun error(tool: String, arguments: JsonObject? = null): String = runBlocking {
        val result = tools.call(tool, arguments)
        assertEquals(true, result.isError, result.text())
        result.text()
    }

    @Test
    fun definesFourReadOnlyTools() {
        assertEquals(
            listOf("datacircle_list_apis", "datacircle_best_plans", "datacircle_search_plans", "datacircle_provider"),
            tools.tools.map { it.name },
        )
        tools.tools.forEach {
            assertEquals(true, it.annotations?.readOnlyHint, it.name)
            assertEquals(false, it.annotations?.destructiveHint, it.name)
            assertEquals("object", it.inputSchema.type)
        }
        assertEquals(listOf("api"), tools.tools[1].inputSchema.required)
        assertEquals(listOf("provider"), tools.tools[3].inputSchema.required)
        assertEquals(tools.tools, tools.registeredTools.map { it.tool })
    }

    @Test
    fun listsApisAndFiltersThemByWords() {
        assertEquals(
            "- Employee email (email-finder): 5,444 plans from 300 providers. Best: Emelia, 100,000 credits per " +
                "month, score 76.6/100, \$4.99 per 1,000.\n" +
                "- Company profile (linkedin-company): 1 plan from 1 provider.\n" +
                "2 of 2 APIs. Pass a slug to datacircle_best_plans or datacircle_search_plans.",
            answer(DatacircleTools.LIST_APIS),
        )
        assertTrue(answer(DatacircleTools.LIST_APIS, args("query", "EMAIL finder")).contains("1 of 2 APIs."))
        assertEquals(
            "No API's name or slug holds every word of \"zzz\". Leave the query empty to list 2 APIs.",
            answer(DatacircleTools.LIST_APIS, args("query", " zzz ")),
        )
    }

    @Test
    fun givesTheBestPlans() {
        assertEquals(LocalApi.BEST_ANSWER, answer(DatacircleTools.BEST_PLANS, args("api", " Email-Finder ", "limit", 3)))
        assertEquals(LocalApi.BEST_ANSWER, answer(DatacircleTools.BEST_PLANS, args("api", "email-finder", "limit", 4.0)))
        assertEquals(LocalApi.BEST_ANSWER, answer(DatacircleTools.BEST_PLANS, args("api", "email-finder", "limit", "5")))
        assertEquals(
            listOf("/v1/apis/email-finder/best?limit=3", "/v1/apis/email-finder/best?limit=4", "/v1/apis/email-finder/best?limit=5"),
            api.requests,
        )
        answer(DatacircleTools.BEST_PLANS, args("api", "email-finder"))
        assertEquals("/v1/apis/email-finder/best?limit=10", api.requests.last())
    }

    @Test
    fun searchesPlans() {
        assertEquals(
            "Found 2 plans; showing 2 to 2.\n" +
                "- Hunter, Starter (People search): rank 18, not scored, no listed price. https://datacircle.dev/p/hunter",
            answer(
                DatacircleTools.SEARCH_PLANS,
                args("api", "People search", "query", "hunter", "sort", "PRICE", "limit", 1, "offset", 1),
            ),
        )
        assertEquals("/v1/plans?sort=price&limit=1&offset=1&api=People+search&query=hunter", api.requests.single())
        answer(DatacircleTools.SEARCH_PLANS)
        assertEquals("/v1/plans?sort=score&limit=10&offset=0", api.requests.last())
    }

    @Test
    fun givesAProvidersRanks() {
        assertEquals(
            "Bright Data (https://brightdata.com) sells 2 APIs. Its rank among each API's providers:\n" +
                "- Employee profile (linkedin-profile): rank 3, 2 plans. Best: Growth, score 71.3/100, " +
                "\$1.05 per 1,000 records.\n" +
                "- SERP (serp): not ranked, 0 plans.",
            answer(DatacircleTools.PROVIDER, args("provider", "Bright Data")),
        )
        answer(DatacircleTools.PROVIDER, args("provider", "brïght-data"))
        assertEquals(listOf("/v1/providers/bright-data", "/v1/providers/bright-data"), api.requests)
    }

    @Test
    fun answersErrorsAsResultsWithWhatToDo() {
        assertEquals(
            "Error: Datacircle API request failed: HTTP 404. no API has the slug nope. Call datacircle_list_apis for " +
                "every API's slug.",
            error(DatacircleTools.BEST_PLANS, args("api", "nope")),
        )
        assertEquals(
            "Error: Datacircle API request failed: HTTP 400. api must be one of: Employee email. Call " +
                "datacircle_list_apis for every API's name and slug.",
            error(DatacircleTools.SEARCH_PLANS, args("api", "nope")),
        )
        assertEquals(
            "Error: Datacircle API request failed: HTTP 429. too many calls. The API allows 60 calls a minute from " +
                "one address. Retry in a minute.",
            error(DatacircleTools.SEARCH_PLANS, args("query", "busy")),
        )
        assertEquals("Error: The Datacircle API returned invalid JSON.", error(DatacircleTools.BEST_PLANS, args("api", "broken")))
        assertEquals("Error: api must not be empty", error(DatacircleTools.BEST_PLANS))
        assertEquals("Error: limit must be between 1 and 50", error(DatacircleTools.BEST_PLANS, args("api", "x", "limit", 0)))
        assertEquals("Error: limit must be a whole number", error(DatacircleTools.BEST_PLANS, args("api", "x", "limit", 2.5)))
        assertEquals("Error: sort must be one of [price, provider, score]", error(DatacircleTools.SEARCH_PLANS, args("sort", "best")))
        assertEquals("Error: offset must be 0 or more", error(DatacircleTools.SEARCH_PLANS, args("offset", -1)))
        assertTrue(error(DatacircleTools.PROVIDER, args("provider", "互亿无线")).contains("Latin letters"))
        assertTrue(error("nope").startsWith("Error: no Datacircle tool is named \"nope\"."))
        val listArg = buildJsonObject { putJsonArray("query") { add(JsonPrimitive("a")) } }
        assertEquals("Error: query must be text", error(DatacircleTools.LIST_APIS, listArg))
        assertTrue(api.requests.size == 4, api.requests.toString())
    }

    @Test
    fun saysWhenNoAnswerCame() {
        val port = ServerSocket(0).use { it.localPort }
        val refused = DatacircleTools("http://127.0.0.1:$port/v1", 5.seconds)
        val result = runBlocking { refused.call(DatacircleTools.LIST_APIS) }
        assertEquals(true, result.isError)
        assertEquals("Error: The Datacircle API refused the connection. Check the network and the base URL.", result.text())
    }

    @Test
    fun checksItsOptions() {
        assertFailsWith<IllegalArgumentException> { DatacircleTools("ftp://example.com") }
        assertFailsWith<IllegalArgumentException> { DatacircleTools(timeout = 0.seconds) }
        DatacircleTools("")
    }

    private fun args(vararg pairs: Any): JsonObject = buildJsonObject {
        pairs.toList().chunked(2).forEach { (name, value) ->
            when (value) {
                is String -> put(name as String, value)
                is Number -> put(name as String, value)
                else -> error("unexpected $value")
            }
        }
    }
}
