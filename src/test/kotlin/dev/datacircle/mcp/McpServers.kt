package dev.datacircle.mcp

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.PipedInputStream
import java.io.PipedOutputStream
import io.ktor.client.engine.cio.CIO as ClientCIO

/** An app's MCP server: one tool of its own, and the Datacircle tools beside it. */
fun appServer(tools: DatacircleTools): Server = Server(
    Implementation(name = "app", version = "1.0.0"),
    ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = null))),
).apply {
    addTool(name = "app_echo", description = "The app's own tool", inputSchema = ToolSchema()) {
        CallToolResult(content = listOf(TextContent(text = "echo")))
    }
    addDatacircleTools(tools)
}

/** Runs [block] with an SDK client connected to [appServer] over the given transport. */
fun <T> withClient(transport: String, tools: DatacircleTools, block: suspend (Client) -> T): T = when (transport) {
    "stdio" -> withStdioClient(tools, block)
    "streamable-http" -> withStreamableHttpClient(tools, block)
    else -> error("no transport $transport")
}

val TRANSPORTS = listOf("stdio", "streamable-http")

private fun <T> withStdioClient(tools: DatacircleTools, block: suspend (Client) -> T): T = runBlocking {
    val toServer = PipedOutputStream()
    val serverIn = PipedInputStream(toServer, PIPE_SIZE)
    val toClient = PipedOutputStream()
    val clientIn = PipedInputStream(toClient, PIPE_SIZE)
    val server = appServer(tools)
    server.createSession(StdioServerTransport(serverIn.asSource().buffered(), toClient.asSink().buffered()))
    val client = Client(clientInfo = Implementation(name = "test", version = "1.0.0"))
    client.connect(StdioClientTransport(input = clientIn.asSource().buffered(), output = toServer.asSink().buffered()))
    try {
        block(client)
    } finally {
        client.close()
        server.close()
    }
}

private fun <T> withStreamableHttpClient(tools: DatacircleTools, block: suspend (Client) -> T): T {
    val engine = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
        mcpStreamableHttp { appServer(tools) }
    }.start(wait = false)
    try {
        return runBlocking {
            val port = engine.engine.resolvedConnectors().first().port
            val http = HttpClient(ClientCIO) { install(SSE) }
            val client = Client(clientInfo = Implementation(name = "test", version = "1.0.0"))
            client.connect(StreamableHttpClientTransport(http, "http://127.0.0.1:$port/mcp"))
            try {
                block(client)
            } finally {
                client.close()
                http.close()
            }
        }
    } finally {
        engine.stop(0, 1000)
    }
}

private const val PIPE_SIZE = 1 shl 20
