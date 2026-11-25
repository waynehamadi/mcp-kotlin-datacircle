# Datacircle tools for the MCP Kotlin SDK

Find the best GTM APIs. We benchmark all the GTM APIs and we put the results on our website.

Our website: https://datacircle.dev

This library gives a server of the [MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk) four tools that call Datacircle's open API, https://api.datacircle.dev/v1. `server.addDatacircleTools()` adds them beside your server's own tools. You need no key and no account.

| Tool | What the tool gives | Call |
| --- | --- | --- |
| `datacircle_list_apis` | Every GTM API on Datacircle, with the API's slug, number of plans, number of providers and best plan | `GET /apis` |
| `datacircle_best_plans` | One API's plans, sorted by score out of 100, best first. Each plan comes with a rank and a price per 1,000 calls | `GET /apis/{slug}/best` |
| `datacircle_search_plans` | Plans that match a provider or plan name, on all APIs or on one API. You can sort them by score, provider or price | `GET /plans` |
| `datacircle_provider` | The APIs a provider sells, and the provider's rank on each API | `GET /providers/{slug}` |

Each tool is read-only.

## Install

The library is on [JitPack](https://jitpack.io/#waynehamadi/mcp-kotlin-datacircle). With Gradle:

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.16.0")
    implementation("com.github.waynehamadi:mcp-kotlin-datacircle:0.1.0")
}
```

With Maven:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.waynehamadi</groupId>
    <artifactId>mcp-kotlin-datacircle</artifactId>
    <version>0.1.0</version>
</dependency>
```

The library uses the MCP Kotlin SDK your app already has, 0.11.0 or later (`kotlin-sdk-server`, or `kotlin-sdk`), on Java 11 or later. It adds no dependency but Kotlin's standard library.

## Add the tools to your server

```kotlin
import dev.datacircle.mcp.addDatacircleTools
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

val server = Server(
    Implementation(name = "my-server", version = "1.0.0"),
    ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())),
)
server.addDatacircleTools()
```

The server must have the tools capability. You can add your own tools before or after.

## A server on STDIO

A whole server, which an MCP client starts with `java -jar`:

```kotlin
import dev.datacircle.mcp.addDatacircleTools
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

fun main(): Unit = runBlocking {
    val server = Server(
        Implementation(name = "datacircle", version = "0.1.0"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())),
    )
    server.addDatacircleTools()
    val transport = StdioServerTransport(System.`in`.asSource().buffered(), System.out.asSink().buffered())
    val session = server.createSession(transport)
    val done = Job()
    session.onClose { done.complete() }
    done.join()
}
```

## A server on Streamable HTTP

With Ktor (`io.ktor:ktor-server-cio`):

```kotlin
import dev.datacircle.mcp.addDatacircleTools
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

fun main() {
    embeddedServer(CIO, host = "127.0.0.1", port = 3001) {
        mcpStreamableHttp {
            Server(
                Implementation(name = "datacircle", version = "0.1.0"),
                ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())),
            ).apply { addDatacircleTools() }
        }
    }.start(wait = true)
}
```

Its MCP endpoint is http://127.0.0.1:3001/mcp.

## Call a tool yourself

`DatacircleTools().tools` gives the four `Tool` definitions, and `call(name, arguments)` runs one:

```kotlin
val result = DatacircleTools().call(
    DatacircleTools.BEST_PLANS,
    buildJsonObject {
        put("api", "email-finder")
        put("limit", 3)
    },
)
```

`registeredTools` gives the tools with their handlers, for `server.addTools(...)`.

## What the tools answer

Each tool answers in short text, one API or plan a line, with each plan's page on datacircle.dev. For example, `datacircle_best_plans` with `{"api": "email-finder", "limit": 3}` answers:

```text
Employee email (email-finder): the best 3 of 5,444 plans. Sheet: https://datacircle.dev/email-finder-api-pricing
1. Emelia, 100,000 credits per month: score 76.6/100, $4.99 per 1,000 calls, $0 per 1,000 not found, monthly. https://datacircle.dev/find-email-apis/emelia/19-monthly
2. ...
```

When a call fails, the tool answers a result with `isError` set and a line that starts with `Error:` and says what to do, for example when a slug does not exist. It does not throw.

## Options

`DatacircleTools(baseUrl, timeout)` sets another base URL or another timeout, as in `server.addDatacircleTools(DatacircleTools(timeout = 10.seconds))`. The defaults are `https://api.datacircle.dev/v1` and 30 seconds.

The API allows 60 calls a minute from one address. Its spec is at https://api.datacircle.dev/v1/openapi.json.

## Tests

```bash
./gradlew test                              # offline: a local API, and a server called by the SDK's client over STDIO and Streamable HTTP
DATACIRCLE_LIVE=1 ./gradlew test            # also calls each tool once on the live API, over each transport
./gradlew clean test -Pmcp.version=0.16.0   # on another version of the MCP Kotlin SDK (0.11.0 by default)
```

## License

MIT
