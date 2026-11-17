package io.github.anetesa.sellerflow.allegro

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
import java.time.Instant

/** Allegro's checkout form as a marketplace-independent [Order]. */
internal fun CheckoutForm.toOrder(): Order =
    Order(
        id = OrderId(id),
        // Checkout forms have no creation time: the first purchase of a line is when the order was placed
        createdAt = lineItems.mapNotNull { it.boughtAt?.let(Instant::parse) }.minOrNull() ?: Instant.parse(updatedAt),
        status = orderStatus(status, fulfillment?.status),
        buyer = buyer.run { Buyer(login, firstName, lastName, email, phoneNumber, companyName) },
        lines = lineItems.map { OrderLine(it.offer.id, it.offer.name, it.quantity, it.price.toMoney()) },
        delivery = delivery.toDelivery(),
        total = summary.totalToPay.toMoney(),
        invoiceRequest = invoice?.toRequest(buyer),
    )

/**
 * Allegro keeps two statuses: the purchase (`status`) and the seller's handling (`fulfillment.status`). An order is
 * ready to ship once it is paid (READY_FOR_PROCESSING) and the seller has not sent it yet.
 */
internal fun orderStatus(
    status: String,
    fulfillment: String?,
): OrderStatus =
    when {
        status == "CANCELLED" || fulfillment == "CANCELLED" -> OrderStatus.CANCELLED
        status == "BOUGHT" || status == "FILLED_IN" -> OrderStatus.AWAITING_PAYMENT
        fulfillment in SHIPPED -> OrderStatus.SHIPPED
        else -> OrderStatus.READY_FOR_SHIPMENT
    }

private val SHIPPED = setOf("SENT", "READY_FOR_PICKUP", "PICKED_UP", "RETURNED")

private fun DeliveryDto?.toDelivery(): Delivery {
    val name = this?.method?.name.orEmpty()
    val inpost = name.contains("InPost", ignoreCase = true) || name.contains("Paczkomat", ignoreCase = true)
    val method =
        when {
            !inpost -> DeliveryMethod.OTHER
            this?.pickupPoint != null -> DeliveryMethod.INPOST_LOCKER
            else -> DeliveryMethod.INPOST_COURIER
        }
    return Delivery(
        method = method,
        methodName = name,
        pickupPointId = this?.pickupPoint?.id?.takeIf { method == DeliveryMethod.INPOST_LOCKER },
        address = this?.address?.run { address(street, zipCode, city, countryCode) },
    )
}

private fun InvoiceDto.toRequest(buyer: BuyerDto): InvoiceRequest? {
    val invoiceAddress = address?.takeIf { required }
    val where = invoiceAddress?.run { address(street, zipCode, city, countryCode) }
    return if (invoiceAddress == null || where == null) {
        null
    } else {
        val company = invoiceAddress.company
        val person = invoiceAddress.naturalPerson
        val name =
            company?.name
                ?: listOfNotNull(person?.firstName, person?.lastName).joinToString(" ").ifBlank { null }
                ?: listOfNotNull(buyer.firstName, buyer.lastName).joinToString(" ").ifBlank { buyer.login }
        // A NIP that fails its checksum is kept out: the invoice step asks the seller instead of issuing a wrong invoice
        InvoiceRequest(nip = company?.taxId?.let(Nip::parse), name = name, address = where)
    }
}

private fun address(
    street: String?,
    postCode: String?,
    city: String?,
    countryCode: String?,
): Address? =
    if (street == null || postCode == null || city == null) {
        null
    } else {
        Address(street = street, postCode = postCode, city = city, countryCode = countryCode ?: "PL")
    }

private fun AmountDto.toMoney(): Money = Money.parse(amount, currency)
