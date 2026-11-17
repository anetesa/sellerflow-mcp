package io.github.anetesa.sellerflow.mcp

import io.github.anetesa.sellerflow.allegro.AllegroHttp
import io.github.anetesa.sellerflow.allegro.AllegroOrders
import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.config.AllegroSettings
import io.github.anetesa.sellerflow.core.config.Environment
import io.github.anetesa.sellerflow.core.config.InpostSettings
import io.github.anetesa.sellerflow.core.config.SellerFlowConfig
import io.github.anetesa.sellerflow.core.store.InMemoryShipmentLedger
import io.github.anetesa.sellerflow.inpost.InpostHttp
import io.github.anetesa.sellerflow.inpost.InpostShipments
import io.github.anetesa.sellerflow.mcp.confirm.ConfirmTokens
import io.github.anetesa.sellerflow.mcp.tools.InpostTools
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.io.File
import java.nio.file.Path

private val CONFIG =
    SellerFlowConfig(Environment.SANDBOX, Path.of("/tmp/sf"), AllegroSettings("id", "SF/1.0 (+https://x.y)"), InpostSettings("7281"))
private val JSON_TYPE = headersOf("Content-Type", "application/json")

private fun order(
    id: String,
    method: String = "Allegro Paczkomaty InPost",
    point: String? = "KRA010",
) = """{"id":"$id","status":"READY_FOR_PROCESSING","fulfillment":{"status":"NEW"},
    "buyer":{"login":"jan","email":"jan@allegromail.pl","firstName":"Jan","lastName":"Kowalski"},
    "delivery":{"method":{"name":"$method"},${point?.let { "\"pickupPoint\":{\"id\":\"$it\"}," }.orEmpty()}
      "address":{"firstName":"Jan","lastName":"Kowalski","phoneNumber":"+48 500 600 700","street":"Długa 1","city":"Kraków","zipCode":"31-001"}},
    "lineItems":[{"offer":{"id":"77","name":"Adapter ELM327"},"quantity":1,"price":{"amount":"49.00","currency":"PLN"}}],
    "summary":{"totalToPay":{"amount":"59.99","currency":"PLN"}},"updatedAt":"2026-11-16T10:05:00Z"}"""

@OptIn(ExperimentalMcpApi::class)
class InpostToolsTest :
    FunSpec({
        class World(
            labels: File,
        ) {
            val inpostCalls = mutableListOf<HttpRequestData>()
            val allegro =
                (
                    AllegroHttp.create(
                        HttpClient(
                            MockEngine { request ->
                                val id = request.url.encodedPath.substringAfterLast('/')
                                val body = if (id == "courier-order") order(id, "Allegro Kurier DPD", null) else order(id)
                                respond(body, HttpStatusCode.OK, JSON_TYPE)
                            },
                        ),
                        CONFIG.endpoints,
                        "id",
                        "secret",
                        "SF/1.0 (+https://x.y)",
                    ) as Outcome.Ok
                ).value
            val inpost =
                (
                    InpostHttp.create(
                        HttpClient(
                            MockEngine { request ->
                                inpostCalls += request
                                val path = request.url.encodedPath
                                when {
                                    path.startsWith("/v1/points/") ->
                                        respond(
                                            """{"name":"KRA010","status":"Operating","location_description":"Siedziba Główna InPost",
                                               "address_details":{"city":"Kraków","post_code":"30-552",
                                               "street":"Malborska","building_number":"130"}}""",
                                            HttpStatusCode.OK,
                                            JSON_TYPE,
                                        )
                                    request.method == HttpMethod.Post ->
                                        respond("""{"id":555,"status":"created","reference":"o-1"}""", HttpStatusCode.Created, JSON_TYPE)
                                    path.endsWith(
                                        "/label",
                                    ) -> respond("%PDF-1.4", HttpStatusCode.OK, headersOf("Content-Type", "application/pdf"))
                                    else ->
                                        respond(
                                            """{"id":555,"status":"confirmed","tracking_number":"620000111","reference":"o-1"}""",
                                            HttpStatusCode.OK,
                                            JSON_TYPE,
                                        )
                                }
                            },
                        ),
                        CONFIG.endpoints.inpostApi,
                        "7281",
                        "token",
                    ) as Outcome.Ok
                ).value
            val tools =
                InpostTools(
                    shipments = InpostShipments(inpost),
                    orders = AllegroOrders(allegro) { Outcome.Ok("seller-token") },
                    ledger = InMemoryShipmentLedger(),
                    tokens = ConfirmTokens(),
                    labelsDir = labels.toPath(),
                )

            suspend fun client(): Client {
                val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
                SellerFlowServer.create(CONFIG, inpost = tools).createSession(serverTransport)
                return Client(Implementation("test", "1.0")).apply { connect(clientTransport) }
            }
        }

        suspend fun Client.text(
            tool: String,
            args: Map<String, Any?>,
        ) = (callTool(tool, args).content.single() as TextContent).text

        test("the InPost tools are listed and explain a missing setup") {
            val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
            SellerFlowServer.create(CONFIG, inpostProblem = "InPost is not set up: missing the API token").createSession(serverTransport)
            val client = Client(Implementation("test", "1.0")).apply { connect(clientTransport) }
            client.listTools().tools.map { it.name } shouldContainAll
                listOf("inpost_find_locker", "inpost_create_shipment", "inpost_get_label")
            client.text("inpost_find_locker", mapOf("name" to "KRA010")) shouldContain "missing the API token"
            client.close()
        }

        test("preview first, the real shipment only with its token, and never twice for one order") {
            val world = World(tempdir())
            val client = world.client()

            val preview = client.text("inpost_create_shipment", mapOf("order_id" to "o-1", "size" to "B"))
            preview shouldStartWith "PREVIEW"
            preview shouldContain "Malborska 130"
            preview shouldContain "500600700"
            world.inpostCalls.none { it.method == HttpMethod.Post } shouldBe true
            val token = preview.substringAfter("confirm_token=").substringBefore(" ")

            client.text("inpost_create_shipment", mapOf("order_id" to "o-1", "size" to "B", "dry_run" to false)) shouldContain
                "needs the confirm_token"
            client.text(
                "inpost_create_shipment",
                mapOf("order_id" to "o-1", "size" to "C", "dry_run" to false, "confirm_token" to token),
            ) shouldContain "different"
            world.inpostCalls.none { it.method == HttpMethod.Post } shouldBe true

            val created =
                client.text(
                    "inpost_create_shipment",
                    mapOf("order_id" to "o-1", "size" to "B", "dry_run" to false, "confirm_token" to token),
                )
            created shouldContain "Created InPost shipment 555"
            created shouldContain "620000111"
            world.inpostCalls.count { it.method == HttpMethod.Post } shouldBe 1

            client.text("inpost_create_shipment", mapOf("order_id" to "o-1", "size" to "B")) shouldContain "already has InPost shipment 555"
            client.close()
        }

        test("refuses an order that does not go to an InPost locker") {
            val world = World(tempdir())
            val client = world.client()
            client.text("inpost_create_shipment", mapOf("order_id" to "courier-order", "size" to "A")) shouldContain
                "not an InPost locker"
            client.close()
        }

        test("saves the label as a PDF file") {
            val labels = tempdir()
            val client = World(labels).client()
            client.text("inpost_get_label", mapOf("shipment_id" to "555")) shouldContain "inpost-555-A6.pdf"
            File(labels, "inpost-555-A6.pdf").readText() shouldStartWith "%PDF"
            client.close()
        }
    })
