package io.github.anetesa.sellerflow.allegro

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import io.github.anetesa.sellerflow.core.model.DeliveryMethod
import io.github.anetesa.sellerflow.core.model.Money
import io.github.anetesa.sellerflow.core.model.Order
import io.github.anetesa.sellerflow.core.model.OrderStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.time.Instant

private val FIXTURE = AllegroOrdersTest::class.java.getResource("/checkout-forms.json")!!.readText()

class AllegroOrdersTest :
    FunSpec({
        test("lists paid orders waiting for shipment with the seller's token") {
            val allegro = FakeAllegro { json(FIXTURE) }
            val orders = AllegroOrders(allegro.http) { Outcome.Ok("seller-token") }.list()
            orders.shouldBeInstanceOf<Outcome.Ok<List<Order>>>().value shouldHaveSize 2
            val request = allegro.requests.single()
            request.url.encodedPath shouldBe "/order/checkout-forms"
            request.url.parameters.getAll("status") shouldBe listOf("READY_FOR_PROCESSING")
            request.url.parameters.getAll("fulfillment.status") shouldBe listOf("NEW", "PROCESSING")
            request.headers[HttpHeaders.Authorization] shouldBe "Bearer seller-token"
            request.headers[HttpHeaders.Accept] shouldBe "application/vnd.allegro.public.v1+json"
        }

        test("maps a locker order with a company invoice") {
            val allegro = FakeAllegro { json(FIXTURE) }
            val order = (AllegroOrders(allegro.http) { Outcome.Ok("t") }.list() as Outcome.Ok).value.first()
            order.status shouldBe OrderStatus.READY_FOR_SHIPMENT
            order.createdAt shouldBe Instant.parse("2026-11-16T12:18:33.434Z")
            order.delivery.method shouldBe DeliveryMethod.INPOST_LOCKER
            order.delivery.pickupPointId shouldBe "POZ08A"
            order.lines.single().total shouldBe Money.parse("152.00", "PLN")
            order.total shouldBe Money.parse("167.87", "PLN")
            order.buyer.email shouldBe "buyer@example.com"
            val invoice = order.invoiceRequest!!
            invoice.name shouldBe "Allegro"
            invoice.nip?.value shouldBe "5252674798"
            invoice.address.postCode shouldBe "60-166"
        }

        test("an order without an invoice, delivered by another carrier") {
            val allegro = FakeAllegro { json(FIXTURE) }
            val order = (AllegroOrders(allegro.http) { Outcome.Ok("t") }.list() as Outcome.Ok).value[1]
            order.invoiceRequest shouldBe null
            order.delivery.method shouldBe DeliveryMethod.OTHER
            order.delivery.pickupPointId shouldBe null
            order.createdAt shouldBe Instant.parse("2026-11-16T15:00:00Z")
        }

        test("reads every page up to the limit") {
            val allegro =
                FakeAllegro { request ->
                    val offset = request.url.parameters["offset"]!!.toInt()
                    val limit = request.url.parameters["limit"]!!.toInt()
                    val forms =
                        (offset until minOf(offset + limit, 150)).joinToString(",") {
                            """{"id":"o$it","status":"READY_FOR_PROCESSING","buyer":{"login":"b"},
                               "summary":{"totalToPay":{"amount":"1.00","currency":"PLN"}},"updatedAt":"2026-11-16T10:00:00Z"}"""
                        }
                    json("""{"checkoutForms":[$forms],"count":0,"totalCount":150}""")
                }
            val orders = (AllegroOrders(allegro.http) { Outcome.Ok("t") }.list() as Outcome.Ok).value
            orders shouldHaveSize 150
            allegro.requests.map { it.url.parameters["offset"] } shouldBe listOf("0", "100")
            (AllegroOrders(allegro.http) { Outcome.Ok("t") }.list(OrderQuery(max = 30)) as Outcome.Ok).value shouldHaveSize 30
        }

        test("an expired token is an auth problem, not a crash") {
            val allegro = FakeAllegro { json("""{"error":"invalid_token"}""", HttpStatusCode.Unauthorized) }
            AllegroOrders(allegro.http) { Outcome.Ok("old") }
                .list()
                .shouldBeInstanceOf<Outcome.Err>()
                .error
                .shouldBeInstanceOf<SellerFlowError.Auth>()
        }

        test("statuses") {
            listOf(
                Triple("READY_FOR_PROCESSING", "NEW", OrderStatus.READY_FOR_SHIPMENT),
                Triple("READY_FOR_PROCESSING", "PROCESSING", OrderStatus.READY_FOR_SHIPMENT),
                Triple("READY_FOR_PROCESSING", "SENT", OrderStatus.SHIPPED),
                Triple("READY_FOR_PROCESSING", "PICKED_UP", OrderStatus.SHIPPED),
                Triple("BOUGHT", null, OrderStatus.AWAITING_PAYMENT),
                Triple("FILLED_IN", "NEW", OrderStatus.AWAITING_PAYMENT),
                Triple("CANCELLED", "NEW", OrderStatus.CANCELLED),
            ).forEach { (status, fulfillment, expected) -> orderStatus(status, fulfillment) shouldBe expected }
        }
    })
