package dev.datacircle.mcp

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The tools on an app's server, beside its own, called by the SDK's client over each transport. */
class McpServersTest {

    private lateinit var api: LocalApi

    @BeforeTest
    fun start() {
        api = LocalApi()
    }

    @AfterTest
    fun stop() {
        api.close()
    }

    @Test
    fun listsAndCallsTheToolsOverEachTransport() {
        for (transport in TRANSPORTS) {
            withClient(transport, DatacircleTools(api.baseUrl, 5.seconds)) { client ->
                val listed = client.listTools().tools
                assertEquals(
                    listOf("app_echo") + DatacircleTools(api.baseUrl).tools.map { it.name },
                    listed.map { it.name },
                    transport,
                )
                val best = listed.single { it.name == DatacircleTools.BEST_PLANS }
                assertEquals(listOf("api"), best.inputSchema.required, transport)
                assertEquals(true, best.annotations?.readOnlyHint, transport)
                assertEquals("Best plans of a GTM API", best.title, transport)

                assertEquals("echo", client.callTool("app_echo", emptyMap()).text(), transport)
                val answers = mapOf(
                    DatacircleTools.LIST_APIS to mapOf<String, Any?>("query" to "email"),
                    DatacircleTools.BEST_PLANS to mapOf("api" to "email-finder", "limit" to 3),
                    DatacircleTools.SEARCH_PLANS to mapOf("query" to "hunter", "offset" to 1),
                    DatacircleTools.PROVIDER to mapOf("provider" to "Bright Data"),
                ).mapValues { (name, arguments) -> client.callTool(name, arguments) }
                answers.forEach { (name, result) -> assertEquals(false, result.isError, "$transport $name") }
                assertTrue(answers.getValue(DatacircleTools.LIST_APIS).text().contains("1 of 2 APIs."), transport)
                assertEquals(LocalApi.BEST_ANSWER, answers.getValue(DatacircleTools.BEST_PLANS).text(), transport)
                assertTrue(answers.getValue(DatacircleTools.SEARCH_PLANS).text().startsWith("Found 2 plans"), transport)
                assertTrue(answers.getValue(DatacircleTools.PROVIDER).text().startsWith("Bright Data"), transport)

                val failed = client.callTool(DatacircleTools.BEST_PLANS, mapOf("api" to "nope"))
                assertEquals(true, failed.isError, transport)
                assertTrue(failed.text().endsWith("Call datacircle_list_apis for every API's slug."), transport)
            }
        }
    }
}
