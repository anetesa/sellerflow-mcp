package io.github.anetesa.sellerflow.invoice

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import io.github.anetesa.sellerflow.core.model.Address
import io.github.anetesa.sellerflow.core.model.InvoiceRequest
import io.github.anetesa.sellerflow.core.model.Money
import io.github.anetesa.sellerflow.core.model.Order
import io.github.anetesa.sellerflow.core.model.SellerProfile
import java.io.StringWriter
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

/** One line as it goes on the invoice: prices are gross, as the marketplace shows them to the buyer. */
data class InvoiceLine(
    val name: String,
    val quantity: Int,
    val unitGross: Money,
    val vatRate: Int,
) {
    val gross: Money get() = unitGross * quantity
}

/** Net and VAT of one rate, computed from that rate's gross total (art. 106e ust. 7 of the VAT act). */
data class RateTotal(
    val rate: Int,
    val net: Money,
    val vat: Money,
    val gross: Money,
)

/** An FA(3) invoice ready to send: the XML, valid against the Ministry's schema, and the totals it states. */
data class Fa3Document(
    val number: String,
    val xml: String,
    val lines: List<InvoiceLine>,
    val totals: List<RateTotal>,
    val gross: Money,
)

/**
 * Builds the FA(3) VAT invoice for a marketplace order. Lines are gross (P_9B, P_11A); a delivery the buyer paid for
 * is its own line at the goods' rate. A buyer with a NIP is identified by it, a private person by BrakID.
 */
class Fa3InvoiceBuilder(
    private val seller: SellerProfile,
    private val vatRate: Int = DEFAULT_RATE,
    private val zone: ZoneId = ZoneId.of("Europe/Warsaw"),
    private val systemInfo: String = "SellerFlow",
) {
    fun build(
        order: Order,
        number: String,
        issueDate: LocalDate,
        createdAt: Instant = Instant.now(),
    ): Outcome<Fa3Document> {
        val request = order.invoiceRequest
        val lines = lines(order)
        return when {
            request == null -> Outcome.Err(SellerFlowError.Invalid("The buyer of order ${order.id} did not ask for an invoice."))
            lines.size > Fa3Schema.MAX_LINES ->
                Outcome.Err(
                    SellerFlowError.Invalid("FA(3) allows at most ${Fa3Schema.MAX_LINES} lines; order ${order.id} has ${lines.size}."),
                )
            else -> {
                val gross = lines.map { it.gross }.reduce(Money::plus)
                val totals =
                    lines
                        .groupBy { it.vatRate }
                        .map { (rate, inRate) -> rateTotal(rate, inRate.map { it.gross }.reduce(Money::plus)) }
                        .sortedBy { RATE_FIELDS.keys.indexOf(it.rate) }
                val xml = xml(Header(order, request, number, issueDate, createdAt), lines, totals, gross)
                when (val problem = Fa3Schema.problem(xml)) {
                    null -> Outcome.Ok(Fa3Document(number, xml, lines, totals, gross))
                    else ->
                        Outcome.Err(
                            SellerFlowError.Invalid("The invoice for order ${order.id} does not match the FA(3) schema: $problem"),
                        )
                }
            }
        }
    }

    private class Header(
        val order: Order,
        val request: InvoiceRequest,
        val number: String,
        val issueDate: LocalDate,
        val createdAt: Instant,
    )

    private fun xml(
        header: Header,
        lines: List<InvoiceLine>,
        totals: List<RateTotal>,
        gross: Money,
    ): String =
        write { w ->
            w.element("Naglowek") {
                w.element("KodFormularza", "FA", "kodSystemowy" to "FA (3)", "wersjaSchemy" to "1-0E")
                w.element("WariantFormularza", "3")
                w.element("DataWytworzeniaFa", header.createdAt.toString())
                w.element("SystemInfo", systemInfo)
            }
            w.element("Podmiot1") {
                w.element("DaneIdentyfikacyjne") {
                    w.element("NIP", seller.nip.value)
                    w.element("Nazwa", seller.name)
                }
                w.address(seller.address)
                seller.email?.let { email -> w.element("DaneKontaktowe") { w.element("Email", email) } }
            }
            w.buyer(header)
            w.element("Fa") {
                w.element("KodWaluty", gross.currency)
                w.element("P_1", header.issueDate.toString())
                w.element("P_2", header.number)
                w.element(
                    "P_6",
                    header.order.createdAt
                        .atZone(zone)
                        .toLocalDate()
                        .toString(),
                )
                totals.forEach { total ->
                    val suffix = RATE_FIELDS.getValue(total.rate)
                    w.element("P_13_$suffix", amount(total.net))
                    w.element("P_14_$suffix", amount(total.vat))
                }
                w.element("P_15", amount(gross))
                w.annotations()
                w.element("RodzajFaktury", "VAT")
                lines.forEachIndexed { index, line -> w.line(index + 1, line) }
            }
        }

    private fun XMLStreamWriter.buyer(header: Header) =
        element("Podmiot2") {
            element("DaneIdentyfikacyjne") {
                val nip = header.request.nip
                if (nip != null) element("NIP", nip.value) else element("BrakID", "1")
                element("Nazwa", header.request.name)
            }
            address(header.request.address)
            header.order.buyer.email
                ?.let { email -> element("DaneKontaktowe") { element("Email", email) } }
            element("JST", "2")
            element("GV", "2")
        }

    private fun XMLStreamWriter.line(
        number: Int,
        line: InvoiceLine,
    ) = element("FaWiersz") {
        element("NrWierszaFa", number.toString())
        element("P_7", line.name)
        element("P_8A", "szt.")
        element("P_8B", line.quantity.toString())
        element("P_9B", amount(line.unitGross))
        element("P_11A", amount(line.gross))
        element("P_12", line.vatRate.toString())
    }

    private fun lines(order: Order): List<InvoiceLine> {
        val goods = order.lines.map { InvoiceLine(it.name, it.quantity, it.unitPrice, vatRate) }
        val goodsTotal = goods.map { it.gross }.reduce(Money::plus)
        // What the buyer paid beyond the goods is the delivery (Allegro's total includes it)
        val delivery = order.total.minor - goodsTotal.minor
        return if (delivery > 0) goods + InvoiceLine("Dostawa", 1, Money(delivery, order.total.currency), vatRate) else goods
    }

    private fun rateTotal(
        rate: Int,
        gross: Money,
    ): RateTotal {
        val vat =
            gross
                .toDecimal()
                .multiply(BigDecimal(rate))
                .divide(BigDecimal(HUNDRED + rate), 2, RoundingMode.HALF_UP)
        val vatMoney = Money(vat.unscaledValue().longValueExact(), gross.currency)
        return RateTotal(rate, Money(gross.minor - vatMoney.minor, gross.currency), vatMoney, gross)
    }

    private companion object {
        const val DEFAULT_RATE = 23
        const val HUNDRED = 100

        /** The FA(3) total fields of each rate: P_13_1/P_14_1 for 23 % (or 22 %), _2 for 8 % (7 %), _3 for 5 %. */
        val RATE_FIELDS = linkedMapOf(23 to "1", 22 to "1", 8 to "2", 7 to "2", 5 to "3")
    }
}

private const val NAMESPACE = "http://crd.gov.pl/wzor/2025/06/25/13775/"

private fun amount(money: Money): String = money.toDecimal().toPlainString()

private fun write(body: (XMLStreamWriter) -> Unit): String {
    val out = StringWriter()
    val w = XMLOutputFactory.newInstance().createXMLStreamWriter(out)
    w.writeStartDocument("UTF-8", "1.0")
    w.writeStartElement("Faktura")
    w.writeDefaultNamespace(NAMESPACE)
    body(w)
    w.writeEndElement()
    w.writeEndDocument()
    w.close()
    return out.toString()
}

private fun XMLStreamWriter.element(
    name: String,
    content: () -> Unit,
) {
    writeStartElement(name)
    content()
    writeEndElement()
}

private fun XMLStreamWriter.element(
    name: String,
    text: String,
    vararg attributes: Pair<String, String>,
) {
    writeStartElement(name)
    attributes.forEach { (key, value) -> writeAttribute(key, value) }
    writeCharacters(text)
    writeEndElement()
}

private fun XMLStreamWriter.address(address: Address) =
    element("Adres") {
        element("KodKraju", address.countryCode)
        element("AdresL1", address.street)
        element("AdresL2", "${address.postCode} ${address.city}")
    }

/**
 * The annotations of an ordinary invoice: no cash accounting, no self-billing, no reverse charge, no split payment,
 * no exemption, no new means of transport, no simplified triangular procedure, no margin scheme.
 */
private fun XMLStreamWriter.annotations() =
    element("Adnotacje") {
        element("P_16", "2")
        element("P_17", "2")
        element("P_18", "2")
        element("P_18A", "2")
        element("Zwolnienie") { element("P_19N", "1") }
        element("NoweSrodkiTransportu") { element("P_22N", "1") }
        element("P_23", "2")
        element("PMarzy") { element("P_PMarzyN", "1") }
    }
