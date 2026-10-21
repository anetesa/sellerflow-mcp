package io.github.anetesa.sellerflow.core.model

import java.time.Instant

@JvmInline
value class OrderId(
    val value: String,
) {
    override fun toString(): String = value
}

/** An order as the seller sees it, independent of the marketplace it came from. */
data class Order(
    val id: OrderId,
    val createdAt: Instant,
    val status: OrderStatus,
    val buyer: Buyer,
    val lines: List<OrderLine>,
    val delivery: Delivery,
    val total: Money,
    /** Set when the buyer asked for an invoice; null means a receipt is enough. */
    val invoiceRequest: InvoiceRequest?,
)

enum class OrderStatus {
    /** Paid, waiting to be shipped. */
    READY_FOR_SHIPMENT,

    /** Not paid yet: nothing to ship or invoice. */
    AWAITING_PAYMENT,

    SHIPPED,
    CANCELLED,
}

data class OrderLine(
    val offerId: String,
    val name: String,
    val quantity: Int,
    val unitPrice: Money,
) {
    val total: Money get() = unitPrice * quantity
}

data class Buyer(
    val login: String,
    val firstName: String?,
    val lastName: String?,
    val email: String?,
    val phone: String?,
    val companyName: String?,
)

data class Address(
    val street: String,
    val postCode: String,
    val city: String,
    /** ISO 3166-1 alpha-2, e.g. "PL". */
    val countryCode: String,
)

data class Delivery(
    val method: DeliveryMethod,
    /** The method as the marketplace names it, kept for messages and for methods we do not handle. */
    val methodName: String,
    /** InPost locker the buyer chose, e.g. "KRA010M"; only for [DeliveryMethod.INPOST_LOCKER]. */
    val pickupPointId: String?,
    val address: Address?,
)

enum class DeliveryMethod {
    INPOST_LOCKER,
    INPOST_COURIER,

    /** Anything else: SellerFlow does not create shipments for it. */
    OTHER,
}

/** What the buyer gave to be invoiced: a company with a NIP, or a private person. */
data class InvoiceRequest(
    val nip: Nip?,
    val name: String,
    val address: Address,
)
