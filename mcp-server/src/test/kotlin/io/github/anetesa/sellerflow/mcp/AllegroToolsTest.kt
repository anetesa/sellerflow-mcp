package io.github.anetesa.sellerflow.mcp

import io.github.anetesa.sellerflow.allegro.AllegroAuth
import io.github.anetesa.sellerflow.allegro.AllegroHttp
import io.github.anetesa.sellerflow.allegro.AllegroOrders
import io.github.anetesa.sellerflow.allegro.AllegroSession
import io.github.anetesa.sellerflow.allegro.AllegroTokens
import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.config.AllegroSettings
import io.github.anetesa.sellerflow.core.config.Environment
import io.github.anetesa.sellerflow.core.config.InpostSettings
import io.github.anetesa.sellerflow.core.config.SellerFlowConfig
import io.github.anetesa.sellerflow.core.store.InMemoryCredentialStore
import io.github.anetesa.sellerflow.mcp.tools.AllegroTools
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path
import java.time.Instant

private val CONFIG =
    SellerFlowConfig(Environment.SANDBOX, Path.of("/tmp/sf"), AllegroSettings("id", "SF/1.0 (+https://x.y)"), InpostSettings(null))

private const val ORDER =
    """{"checkoutForms":[{"id":"o-1","status":"READY_FOR_PROCESSING","fulfillment":{"status":"NEW"},
    "buyer":{"login":"jan","firstName":"Jan","lastName":"Kowalski"},
    "delivery":{"method":{"name":"Allegro Paczkomaty InPost"},"pickupPoint":{"id":"KRA010M"}},
    "invoice":{"required":true,"address":{"street":"Długa 1","city":"Kraków","zipCode":"31-001","countryCode":"PL",
    "company":{"name":"Firma","taxId":"5252674798"}}},
    "lineItems":[{"offer":{"id":"77","name":"Adapter ELM327"},"quantity":1,"price":{"amount":"49.00","currency":"PLN"},
    "boughtAt":"2026-11-16T10:00:00Z"}],
    "summary":{"totalToPay":{"amount":"59.99","currency":"PLN"}},"updatedAt":"2026-11-16T10:05:00Z"}],"count":1,"totalCount":1}"""

@OptIn(ExperimentalMcpApi::class)
class AllegroToolsTest :
    FunSpec({
        suspend fun client(
            tools: AllegroTools?,
            problem: String = "Allegro is not set up: missing the User-Agent (SELLERFLOW_ALLEGRO_USER_AGENT).",
        ): Client {
            val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
            SellerFlowServer.create(CONFIG, tools, problem).createSession(serverTransport)
            return Client(Implementation("test", "1.0")).apply { connect(clientTransport) }
        }

        suspend fun Client.text(
            tool: String,
            args: Map<String, Any?> = emptyMap(),
        ) = (callTool(tool, args).content.single() as TextContent).text

        fun tools(scope: CoroutineScope): Pair<AllegroTools, AllegroSession> {
            val http =
                (
                    AllegroHttp.create(
                        HttpClient(MockEngine { respond(ORDER, HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }),
                        CONFIG.endpoints,
                        "id",
                        "secret",
                        "SF/1.0 (+https://x.y)",
                    ) as Outcome.Ok
                ).value
            val session = AllegroSession(AllegroAuth(http), InMemoryCredentialStore())
            return AllegroTools(AllegroAuth(http), session, AllegroOrders(http) { session.accessToken() }, scope) to session
        }

        test("both Allegro tools are listed, and say what is missing when Allegro is not set up") {
            val client = client(null)
            client.listTools().tools.map { it.name } shouldContainAll listOf("allegro_login", "allegro_list_orders")
            client.text("allegro_list_orders") shouldContain "SELLERFLOW_ALLEGRO_USER_AGENT"
            client.close()
        }

        test("without a login, listing orders asks to log in first") {
            val client = client(tools(this).first)
            client.text("allegro_list_orders") shouldContain "allegro_login"
            client.close()
        }

        test("lists orders in a form the agent can act on") {
            val (tools, session) = tools(this)
            session.save(AllegroTokens("at", "rt", Instant.now().plusSeconds(3600)))
            val client = client(tools)
            val text = client.text("allegro_list_orders", mapOf("max" to 10))
            text shouldContain "1 order(s)"
            text shouldContain "locker KRA010M"
            text shouldContain "1 × Adapter ELM327"
            text shouldContain "NIP 5252674798"
            client.text("allegro_login") shouldContain "Logged in"
            client.close()
        }

        test("the order description stays readable without optional data") {
            val (tools, session) = tools(this)
            session.save(AllegroTokens("at", "rt", Instant.now().plusSeconds(3600)))
            tools
                .listOrders(
                    io.github.anetesa.sellerflow.allegro
                        .OrderQuery(),
                ).lines()
                .first() shouldBe "1 order(s):"
        }
    })
