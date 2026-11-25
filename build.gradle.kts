import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.10"
    `java-library`
    `maven-publish`
}

group = "com.github.waynehamadi"
version = "0.1.0"

// The MCP Kotlin SDK the tests run on: ./gradlew test -Pmcp.version=0.16.0
val mcpVersion = providers.gradleProperty("mcp.version").getOrElse("0.11.0")

// The Ktor of that SDK, for the tests' own HTTP server and client.
val ktorVersion = providers.gradleProperty("ktor.version").getOrElse(
    when (mcpVersion.split(".").getOrNull(1)?.toIntOrNull() ?: 0) {
        in 0..12 -> "3.3.3"
        13, 14 -> "3.4.3"
        15 -> "3.5.1"
        else -> "3.5.2"
    },
)

repositories {
    mavenCentral()
}

dependencies {
    // The app brings its own SDK (0.11.0 or later), so the library adds no dependency but Kotlin's own.
    compileOnly("io.modelcontextprotocol:kotlin-sdk-server:0.11.0")

    testImplementation("io.modelcontextprotocol:kotlin-sdk-server:$mcpVersion")
    testImplementation("io.modelcontextprotocol:kotlin-sdk-client:$mcpVersion")
    testImplementation(platform("io.ktor:ktor-bom:$ktorVersion"))
    testImplementation("io.ktor:ktor-server-cio")
    testImplementation("io.ktor:ktor-client-cio")
    testImplementation("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(kotlin("test"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("datacircle.live", System.getenv("DATACIRCLE_LIVE") ?: "")
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "mcp-kotlin-datacircle"
            pom {
                name.set("mcp-kotlin-datacircle")
                description.set(
                    "Four MCP Kotlin SDK tools on Datacircle's open API, no key: the GTM APIs, each one's best plans, " +
                        "plan search, a provider's ranks.",
                )
                url.set("https://datacircle.dev")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://github.com/waynehamadi/mcp-kotlin-datacircle/blob/main/LICENSE")
                    }
                }
                scm {
                    url.set("https://github.com/waynehamadi/mcp-kotlin-datacircle")
                }
            }
        }
    }
}
