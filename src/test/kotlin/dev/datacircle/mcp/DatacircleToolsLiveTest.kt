package dev.datacircle.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each tool once on the live API, through an app's server over each transport. Runs with DATACIRCLE_LIVE=1. */
class DatacircleToolsLiveTest {

    @Test
    fun callsEachToolOnTheLiveApi() {
        if (System.getProperty("datacircle.live") != "1") {
            println("skipped: set DATACIRCLE_LIVE=1 to call the live API")
            return
        }
        for (transport in TRANSPORTS) {
            withClient(transport, DatacircleTools()) { client ->
                val apis = client.callTool(DatacircleTools.LIST_APIS, mapOf("query" to "email"))
                val best = client.callTool(DatacircleTools.BEST_PLANS, mapOf("api" to "email-finder", "limit" to 3))
                val search = client.callTool(
                    DatacircleTools.SEARCH_PLANS,
                    mapOf("api" to "email-finder", "query" to "hunter", "sort" to "price", "limit" to 2),
                )
                val provider = client.callTool(DatacircleTools.PROVIDER, mapOf("provider" to "Bright Data"))
                val missing = client.callTool(DatacircleTools.BEST_PLANS, mapOf("api" to "no-such-api"))
                listOf(apis, best, search, provider, missing).forEach { println("[$transport] ${it.text()}\n") }

                listOf(apis, best, search, provider).forEach { assertEquals(false, it.isError, it.text()) }
                assertTrue(apis.text().contains("(email-finder)"), apis.text())
                assertTrue(best.text().startsWith("Employee email (email-finder): the best 3 of "), best.text())
                assertTrue(best.text().contains("https://datacircle.dev/"), best.text())
                assertTrue(search.text().startsWith("Found "), search.text())
                assertTrue(search.text().contains("Hunter"), search.text())
                assertTrue(provider.text().startsWith("Bright Data (https://brightdata.com) sells "), provider.text())
                assertEquals(true, missing.isError, missing.text())
                assertTrue(missing.text().contains("HTTP 404"), missing.text())
            }
        }
    }
}
