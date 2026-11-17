package io.github.anetesa.sellerflow.invoice

import io.github.anetesa.sellerflow.core.Outcome
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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import java.time.LocalDate

private fun pln(amount: String) = Money.parse(amount, "PLN")

private val SELLER =
    SellerProfile(
        name = "Sklep Testowy & Syn",
        nip = Nip.parse("5252674798")!!,
        address = Address("ul. Babiego Lata 19A", "53-024", "Wrocław", "PL"),
        email = "sklep@example.com",
        bankAccount = null,
    )

private fun order(invoice: InvoiceRequest?) =
    Order(
        id = OrderId("ffc396b0-9584-11e8-8d53-07c966f77738"),
        createdAt = Instant.parse("2026-11-16T22:30:00Z"),
        status = OrderStatus.READY_FOR_SHIPMENT,
        buyer = Buyer("jan", "Jan", "Kowalski", "jan@allegromail.pl", null, null),
        lines =
            listOf(
                OrderLine("1", "Adapter ELM327 <BLE>", 2, pln("49.00")),
                OrderLine("2", "Kabel OBD", 1, pln("9.99")),
            ),
        delivery = Delivery(DeliveryMethod.INPOST_LOCKER, "Allegro Paczkomaty InPost", "KRA010", null),
        total = pln("121.98"),
        invoiceRequest = invoice,
    )

private val COMPANY =
    InvoiceRequest(Nip.parse("PL 525-267-47-98"), "Firma Kupująca sp. z o.o.", Address("Długa 1", "31-001", "Kraków", "PL"))
private val PERSON = InvoiceRequest(null, "Jan Kowalski", Address("Krótka 2", "00-001", "Warszawa", "PL"))

class Fa3InvoiceTest :
    FunSpec({
        val builder = Fa3InvoiceBuilder(SELLER)

        test("the bundled schema accepts the Ministry's own FA(3) sample and rejects a broken one") {
            val sample = Fa3InvoiceTest::class.java.getResource("/mf-sample-fa3.xml")!!.readText()
            Fa3Schema.problem(sample).shouldBeNull()
            Fa3Schema.problem(sample.replace("<P_15>13102.43</P_15>", "")).shouldNotBeNull()
        }

        test("a company invoice: NIP, gross lines, delivery line, VAT from the gross total, valid FA(3)") {
            val doc =
                builder
                    .build(order(COMPANY), "FV/1/11/2026", LocalDate.parse("2026-11-17"), Instant.parse("2026-11-17T10:00:00Z"))
                    .shouldBeInstanceOf<Outcome.Ok<Fa3Document>>()
                    .value
            doc.lines.map { it.name } shouldBe listOf("Adapter ELM327 <BLE>", "Kabel OBD", "Dostawa")
            doc.lines.last().gross shouldBe pln("13.99")
            doc.gross shouldBe pln("121.98")
            // 121.98 × 23/123 = 22.81 VAT, 99.17 net
            doc.totals.single() shouldBe RateTotal(23, pln("99.17"), pln("22.81"), pln("121.98"))
            doc.xml shouldContain "<NIP>5252674798</NIP><Nazwa>Firma Kupująca sp. z o.o.</Nazwa>"
            doc.xml shouldContain "<P_13_1>99.17</P_13_1><P_14_1>22.81</P_14_1><P_15>121.98</P_15>"
            doc.xml shouldContain "<P_9B>49.00</P_9B><P_11A>98.00</P_11A><P_12>23</P_12>"
            doc.xml shouldContain "<P_6>2026-11-16</P_6>"
            doc.xml shouldContain "Adapter ELM327 &lt;BLE&gt;"
            doc.xml shouldContain "Sklep Testowy &amp; Syn"
        }

        test("a private buyer without NIP gets BrakID") {
            val doc = (builder.build(order(PERSON), "FV/2/11/2026", LocalDate.parse("2026-11-17")) as Outcome.Ok).value
            doc.xml shouldContain "<BrakID>1</BrakID><Nazwa>Jan Kowalski</Nazwa>"
            doc.xml shouldNotContain "<Podmiot2><DaneIdentyfikacyjne><NIP>"
        }

        test("the sale date is the order's day in Poland, not in UTC") {
            val late = order(COMPANY).copy(createdAt = Instant.parse("2026-11-16T23:30:00Z"))
            (builder.build(late, "FV/3/11/2026", LocalDate.parse("2026-11-17")) as Outcome.Ok).value.xml shouldContain
                "<P_6>2026-11-17</P_6>"
        }

        test("no invoice when the buyer did not ask for one") {
            builder.build(order(null), "FV/4/11/2026", LocalDate.parse("2026-11-17")).shouldBeInstanceOf<Outcome.Err>()
        }

        test("8 % goods land in P_13_2 and P_14_2") {
            val doc =
                (
                    Fa3InvoiceBuilder(
                        SELLER,
                        vatRate = 8,
                    ).build(order(COMPANY), "FV/5/11/2026", LocalDate.parse("2026-11-17")) as Outcome.Ok
                ).value
            // 121.98 × 8/108 = 9.04 VAT
            doc.xml shouldContain "<P_13_2>112.94</P_13_2><P_14_2>9.04</P_14_2>"
        }
    })
