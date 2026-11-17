package io.github.anetesa.sellerflow.core.store

import io.github.anetesa.sellerflow.core.model.OrderId
import java.time.LocalDate
import java.time.YearMonth

/** An invoice KSeF accepted for an order. */
data class IssuedInvoice(
    val orderId: OrderId,
    val number: String,
    val ksefNumber: String,
    val issuedOn: LocalDate,
)

/**
 * Invoices issued per order and the monthly numbering. A number is only taken when an accepted invoice is recorded,
 * so a rejected invoice leaves no gap, and an order never gets a second invoice.
 */
interface InvoiceLedger {
    fun invoiceFor(order: OrderId): IssuedInvoice?

    /** The sequence the next invoice of [month] gets; nothing is reserved. */
    fun nextSequence(month: YearMonth): Int

    /**
     * Records [invoice] as the [sequence]-th of [month]. False, and nothing recorded, when the order already has an
     * invoice or [sequence] is no longer the next one (another invoice was recorded in between).
     */
    fun record(
        invoice: IssuedInvoice,
        month: YearMonth,
        sequence: Int,
    ): Boolean
}

class InMemoryInvoiceLedger : InvoiceLedger {
    private val invoices = mutableMapOf<OrderId, IssuedInvoice>()
    private val last = mutableMapOf<YearMonth, Int>()

    @Synchronized
    override fun invoiceFor(order: OrderId): IssuedInvoice? = invoices[order]

    @Synchronized
    override fun nextSequence(month: YearMonth): Int = (last[month] ?: 0) + 1

    @Synchronized
    override fun record(
        invoice: IssuedInvoice,
        month: YearMonth,
        sequence: Int,
    ): Boolean {
        if (invoice.orderId in invoices || sequence != nextSequence(month)) return false
        invoices[invoice.orderId] = invoice
        last[month] = sequence
        return true
    }
}
