package io.github.anetesa.sellerflow.ksef

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.config.Environment
import io.github.anetesa.sellerflow.core.model.Address
import io.github.anetesa.sellerflow.core.model.Buyer
import io.github.anetesa.sellerflow.core.model.Delivery
import io.github.anetesa.sellerflow.core.model.DeliveryMethod
import io.github.anetesa.sellerflow.core.model.InvoiceRequest
import io.github.anetesa.sellerflow.core.model.Money
import io.github.anetesa.sellerflow.core.model.Nip
import io.github.anetesa.sellerflow.core.model.Order
import io.github.anetesa.sellerflow.core.model.OrderId
import io.github.anetesa.sellerflow.core.model.OrderLine
import io.github.anetesa.sellerflow.core.model.OrderStatus
import io.github.anetesa.sellerflow.core.model.SellerProfile
import io.github.anetesa.sellerflow.invoice.Fa3Document
import io.github.anetesa.sellerflow.invoice.Fa3InvoiceBuilder
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.time.Instant
import java.time.LocalDate

/**
 * Sends a real FA(3) invoice to KSeF TEST: `./gradlew :ksef:test -Pintegration` with SELLERFLOW_KSEF_NIP and
 * SELLERFLOW_KSEF_TOKEN from the bootstrap (KsefTestEnvironmentTest). The seller is the test NIP, the buyer a
 * fixed sample company; every run uses a new invoice number.
 */
class KsefSendTestEnvironmentTest :
    FunSpec({
        tags(NamedTag("Integration"))
        val nip = System.getenv("SELLERFLOW_KSEF_NIP")?.let(Nip::parse)
        val token = System.getenv("SELLERFLOW_KSEF_TOKEN")

        test("an FA(3) invoice gets a KSeF number and a UPO").config(enabled = nip != null && token != null) {
            val client = HttpClient(CIO)
            val http = KsefHttp(client, Environment.SANDBOX.endpoints.ksefApi)
            val auth = KsefAuth(http)
            val access =
                auth
                    .loginWithToken(nip!!, token!!)
                    .shouldBeInstanceOf<Outcome.Ok<KsefTokens>>()
                    .value.accessToken

            val seller = SellerProfile("SellerFlow Test", nip, Address("ul. Testowa 1", "00-001", "Warszawa", "PL"), null, null)
            val order =
                Order(
                    id = OrderId("it-${System.currentTimeMillis()}"),
                    createdAt = Instant.now(),
                    status = OrderStatus.READY_FOR_SHIPMENT,
                    buyer = Buyer("buyer", "Jan", "Kowalski", null, null, null),
                    lines = listOf(OrderLine("1", "Adapter ELM327", 1, Money.parse("49.00", "PLN"))),
                    delivery = Delivery(DeliveryMethod.INPOST_LOCKER, "Allegro Paczkomaty InPost", "KRA010", null),
                    total = Money.parse("59.99", "PLN"),
                    invoiceRequest =
                        InvoiceRequest(
                            Nip.parse("5252674798"),
                            "Kupujący sp. z o.o.",
                            Address("Długa 1", "31-001", "Kraków", "PL"),
                        ),
                )
            val document =
                Fa3InvoiceBuilder(seller)
                    .build(order, "SF-IT/${System.currentTimeMillis()}", LocalDate.now())
                    .shouldBeInstanceOf<Outcome.Ok<Fa3Document>>()
                    .value

            val receipt = KsefInvoices(http, auth).issue(access, document.xml).shouldBeInstanceOf<Outcome.Ok<KsefReceipt>>().value
            receipt.ksefNumber shouldStartWith nip.value
            receipt.upoXml.orEmpty() shouldContain receipt.ksefNumber
            client.close()
        }
    })
