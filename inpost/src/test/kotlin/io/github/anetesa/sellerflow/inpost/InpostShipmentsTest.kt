package io.github.anetesa.sellerflow.inpost

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import io.github.anetesa.sellerflow.core.model.OrderId
import io.github.anetesa.sellerflow.core.model.ParcelSize
import io.github.anetesa.sellerflow.core.model.Shipment
import io.github.anetesa.sellerflow.core.model.ShipmentId
import io.github.anetesa.sellerflow.core.model.ShipmentStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val BASE = "https://sandbox-api-shipx-pl.easypack24.net"

private class FakeInpost(
    private val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val requests = mutableListOf<HttpRequestData>()
    val shipments =
        InpostShipments(
            (
                InpostHttp.create(
                    HttpClient(
                        MockEngine {
                            requests += it
                            handler(it)
                        },
                    ),
                    BASE,
                    "7281",
                    "token",
                ) as Outcome.Ok
            ).value,
        )
}

private fun MockRequestHandleScope.json(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(body, status, headersOf("Content-Type", "application/json"))

private fun shipment(
    status: String,
    tracking: String? = null,
    transactions: String = "[]",
) = """{"id":14690660,"status":"$status","tracking_number":${tracking?.let { "\"$it\"" } ?: "null"},"reference":"order-1",
    "parcels":[{"id":1,"template":"small"}],"custom_attributes":{"target_point":"KRA010","sending_method":"dispatch_order"},
    "transactions":$transactions}"""

private val REQUEST =
    LockerShipmentRequest(OrderId("order-1"), "Jan", "Testowy", "jan@example.com", "+48 500 600 700", "kra010", ParcelSize.B)

class InpostShipmentsTest :
    FunSpec({
        test("refuses to start without the token or the organization") {
            InpostHttp
                .create(HttpClient(MockEngine { error("no") }), BASE, null, " ")
                .shouldBeInstanceOf<Outcome.Err>()
                .error.message shouldContain "SELLERFLOW_INPOST_API_TOKEN"
        }

        test("creates a locker shipment the way ShipX expects it") {
            val inpost = FakeInpost { json(shipment("created"), HttpStatusCode.Created) }
            val created =
                inpost.shipments
                    .create(REQUEST)
                    .shouldBeInstanceOf<Outcome.Ok<Shipment>>()
                    .value
            created.id shouldBe ShipmentId("14690660")
            created.status shouldBe ShipmentStatus.CREATED
            created.orderId shouldBe OrderId("order-1")
            val request = inpost.requests.single()
            request.url.toString() shouldBe "$BASE/v1/organizations/7281/shipments"
            request.headers[HttpHeaders.Authorization] shouldBe "Bearer token"
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            body["service"]!!.jsonPrimitive.content shouldBe "inpost_locker_standard"
            body["reference"]!!.jsonPrimitive.content shouldBe "order-1"
            body["receiver"]!!.jsonObject["phone"]!!.jsonPrimitive.content shouldBe "500600700"
            body["custom_attributes"]!!.jsonObject["target_point"]!!.jsonPrimitive.content shouldBe "KRA010"
            body["parcels"].toString() shouldContain "medium"
        }

        test("a phone InPost cannot text is caught before calling InPost") {
            val inpost = FakeInpost { error("must not be called") }
            inpost.shipments
                .create(REQUEST.copy(receiverPhone = "+49 151 2345678"))
                .shouldBeInstanceOf<Outcome.Err>()
                .error.message shouldContain "Polish mobile"
            inpost.requests.shouldBeEmpty()
        }

        test("validation errors become one readable line") {
            val inpost =
                FakeInpost {
                    json(
                        """{"status":400,"error":"validation_failed","message":"There are some validation errors.",
                           "details":{"custom_attributes":[{"target_point":["does_not_exist"]}]}}""",
                        HttpStatusCode.BadRequest,
                    )
                }
            val error =
                inpost.shipments
                    .create(REQUEST)
                    .shouldBeInstanceOf<Outcome.Err>()
                    .error
            error.shouldBeInstanceOf<SellerFlowError.Invalid>()
            error.message shouldContain "custom_attributes.target_point: does_not_exist"
        }

        test("waits for confirmation and returns the tracking number") {
            val answers =
                ArrayDeque(listOf(shipment("created"), shipment("offer_selected"), shipment("confirmed", "6200000000000000000001")))
            val inpost = FakeInpost { json(answers.removeFirst()) }
            var waits = 0
            val confirmed =
                inpost.shipments
                    .awaitConfirmed(ShipmentId("14690660")) { waits++ }
                    .shouldBeInstanceOf<Outcome.Ok<Shipment>>()
                    .value
            confirmed.status shouldBe ShipmentStatus.CONFIRMED
            confirmed.trackingNumber shouldBe "6200000000000000000001"
            waits shouldBe 2
        }

        test("an account without funds is explained, as seen in the sandbox") {
            val inpost =
                FakeInpost {
                    json(
                        shipment(
                            "offer_selected",
                            transactions =
                                """[{"id":5893499,"status":"failure","offer_id":9134592,
                                   "details":{"status":422,"error":"debt_collection","message":"debt_collection","details":{}}}]""",
                        ),
                    )
                }
            inpost.shipments
                .awaitConfirmed(ShipmentId("14690660")) {}
                .shouldBeInstanceOf<Outcome.Err>()
                .error.message shouldContain "no funds"
        }

        test("gives up politely when InPost takes too long") {
            val inpost = FakeInpost { json(shipment("created")) }
            inpost.shipments
                .awaitConfirmed(ShipmentId("14690660"), attempts = 2) {}
                .shouldBeInstanceOf<Outcome.Err>()
                .error.message shouldContain "not confirmed"
            inpost.requests.size shouldBe 3
        }

        test("downloads the A6 label as PDF") {
            val inpost =
                FakeInpost { respond("%PDF-1.4 fake", HttpStatusCode.OK, headersOf("Content-Type", "application/pdf")) }
            val pdf =
                inpost.shipments
                    .label(ShipmentId("14690660"))
                    .shouldBeInstanceOf<Outcome.Ok<ByteArray>>()
                    .value
            String(pdf) shouldContain "%PDF"
            val url = inpost.requests.single().url
            url.encodedPath shouldBe "/v1/shipments/14690660/label"
            url.parameters["type"] shouldBe "A6"
        }

        test("a label for an unconfirmed shipment says why") {
            val inpost =
                FakeInpost {
                    json(
                        """{"status":400,"error":"invalid_action","message":"shipment_status_incorrect",
                           "details":{"action":"get_label","shipment_status":"offer_selected","shipment_id":14690660}}""",
                        HttpStatusCode.BadRequest,
                    )
                }
            inpost.shipments
                .label(ShipmentId("14690660"))
                .shouldBeInstanceOf<Outcome.Err>()
                .error.message shouldContain "shipment_status: offer_selected"
        }

        test("finds a locker, and says so when the name is unknown (ShipX answers 200 with status 404)") {
            val inpost =
                FakeInpost { request ->
                    if (request.url.encodedPath.endsWith("KRA010")) {
                        json(
                            """{"name":"KRA010","status":"Operating","location_description":"Siedziba Główna InPost",
                               "address_details":{"city":"Kraków","post_code":"30-552","street":"Malborska","building_number":"130"}}""",
                        )
                    } else {
                        json("""{"status":404,"error":"resource_not_found"}""")
                    }
                }
            val locker =
                inpost.shipments
                    .locker("kra010")
                    .shouldBeInstanceOf<Outcome.Ok<ParcelLocker>>()
                    .value
            locker.address shouldBe "Malborska 130, 30-552 Kraków"
            locker.operating shouldBe true
            inpost.shipments
                .locker("KRA010M")
                .shouldBeInstanceOf<Outcome.Err>()
                .error.message shouldContain "no parcel locker"
        }

        test("phone numbers") {
            listOf(
                "500600700" to "500600700",
                "+48 500 600 700" to "500600700",
                "0048500600700" to "500600700",
                "500-600-700" to "500600700",
                "+49 1512345678" to null,
                "000333666" to null,
            ).forEach { (raw, expected) -> polishMobile(raw) shouldBe expected }
        }

        test("statuses") {
            shipmentStatus("offer_selected") shouldBe ShipmentStatus.CREATED
            shipmentStatus("confirmed") shouldBe ShipmentStatus.CONFIRMED
            shipmentStatus("ready_to_pickup") shouldBe ShipmentStatus.IN_TRANSIT
            shipmentStatus("delivered") shouldBe ShipmentStatus.DELIVERED
            shipmentStatus("canceled") shouldBe ShipmentStatus.FAILED
        }
    })
