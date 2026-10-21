package io.github.anetesa.sellerflow.core.model

import java.time.LocalDate

@JvmInline
value class InvoiceId(
    val value: String,
) {
    override fun toString(): String = value
}

/** A sales invoice for one order; [ksefNumber] is assigned by KSeF once it accepts the invoice. */
data class Invoice(
    val id: InvoiceId,
    val orderId: OrderId,
    val number: String,
    val issueDate: LocalDate,
    val status: InvoiceStatus,
    val ksefNumber: String?,
)

enum class InvoiceStatus {
    DRAFT,
    SENT,
    ACCEPTED,
    REJECTED,
}

/** The seller's own data printed on every invoice. */
data class SellerProfile(
    val name: String,
    val nip: Nip,
    val address: Address,
    val email: String?,
    val bankAccount: String?,
)
