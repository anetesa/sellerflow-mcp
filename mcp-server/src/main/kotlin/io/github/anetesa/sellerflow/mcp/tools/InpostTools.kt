package io.github.anetesa.sellerflow.mcp.tools

import io.github.anetesa.sellerflow.allegro.AllegroOrders
import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import io.github.anetesa.sellerflow.core.flatMap
import io.github.anetesa.sellerflow.core.map
import io.github.anetesa.sellerflow.core.model.DeliveryMethod
import io.github.anetesa.sellerflow.core.model.Order
import io.github.anetesa.sellerflow.core.model.OrderId
import io.github.anetesa.sellerflow.core.model.OrderStatus
import io.github.anetesa.sellerflow.core.model.ParcelSize
import io.github.anetesa.sellerflow.core.model.ShipmentId
import io.github.anetesa.sellerflow.core.store.ShipmentLedger
import io.github.anetesa.sellerflow.inpost.InpostShipments
import io.github.anetesa.sellerflow.inpost.LabelFormat
import io.github.anetesa.sellerflow.inpost.LockerShipmentRequest
import io.github.anetesa.sellerflow.inpost.polishMobile
import io.github.anetesa.sellerflow.mcp.confirm.ConfirmTokens
import java.nio.file.Files
import java.nio.file.Path

/**
 * InPost for the agent: find a locker, ship an Allegro order to the locker its buyer chose (preview first, then the
 * real shipment only with the preview's confirm_token, never twice for one order), and save labels.
 */
class InpostTools(
    private val shipments: InpostShipments,
    private val orders: AllegroOrders?,
    private val ledger: ShipmentLedger,
    private val tokens: ConfirmTokens,
    private val labelsDir: Path,
) {
    internal suspend fun findLocker(name: String): String =
        when (val found = shipments.locker(name)) {
            is Outcome.Err -> found.error.message
            is Outcome.Ok ->
                found.value.run {
                    "$name: ${if (operating) "operating" else "NOT operating"}, $address" + description?.let { " ($it)" }.orEmpty()
                }
        }

    internal suspend fun createShipment(
        orderId: OrderId,
        size: ParcelSize,
        dryRun: Boolean,
        confirmToken: String?,
    ): String =
        plan(orderId, size)
            .flatMap { (order, request) ->
                val what =
                    listOf(
                        request.orderId,
                        request.size,
                        request.targetPoint,
                        request.receiverPhone,
                        request.receiverEmail,
                    ).joinToString("|")
                if (dryRun) preview(order, request, tokens.issue(ACTION, what)) else create(request, confirmToken, what)
            }.text()

    /** The order and the shipment it needs, or why it cannot be shipped (already shipped, not a locker order…). */
    private suspend fun plan(
        orderId: OrderId,
        size: ParcelSize,
    ): Outcome<Pair<Order, LockerShipmentRequest>> {
        val existing = ledger.shipmentFor(orderId)
        return when {
            existing != null ->
                Outcome.Err(
                    SellerFlowError.AlreadyDone(
                        "Order $orderId already has InPost shipment $existing; SellerFlow never creates a second one. " +
                            "Use inpost_get_label with shipment_id $existing.",
                    ),
                )
            orders == null -> Outcome.Err(SellerFlowError.Config("Allegro is not set up, so SellerFlow cannot read order $orderId."))
            else -> orders.get(orderId).flatMap { order -> request(order, size).map { order to it } }
        }
    }

    private suspend fun preview(
        order: Order,
        request: LockerShipmentRequest,
        token: String,
    ): Outcome<String> =
        shipments.locker(request.targetPoint).flatMap { locker ->
            if (!locker.operating) {
                Outcome.Err(SellerFlowError.Invalid("Locker ${locker.name} is not operating. Ask the buyer for another one."))
            } else {
                Outcome.Ok(
                    buildString {
                        appendLine("PREVIEW, nothing was created yet. InPost locker shipment for Allegro order ${order.id}:")
                        val name = listOfNotNull(request.receiverFirstName, request.receiverLastName).joinToString(" ")
                        appendLine("  To: $name, ${request.receiverPhone}, ${request.receiverEmail}")
                        appendLine("  Locker: ${locker.name}, ${locker.address}" + locker.description?.let { " ($it)" }.orEmpty())
                        appendLine("  Size: ${request.size} (${SIZE_HINT.getValue(request.size)}), service inpost_locker_standard")
                        appendLine("  Items: " + order.lines.joinToString { "${it.quantity} × ${it.name}" })
                        appendLine("InPost charges the seller's account when the shipment is created.")
                        append("To create it, call inpost_create_shipment again with dry_run=false and confirm_token=$token ")
                        append("(valid 15 minutes, once).")
                    },
                )
            }
        }

    private suspend fun create(
        request: LockerShipmentRequest,
        confirmToken: String?,
        what: String,
    ): Outcome<String> =
        if (confirmToken.isNullOrBlank()) {
            Outcome.Err(
                SellerFlowError.Invalid(
                    "A real shipment needs the confirm_token from a preview: call with dry_run=true first and show it to the seller.",
                ),
            )
        } else {
            tokens
                .redeem(confirmToken, ACTION, what)
                .flatMap { shipments.create(request) }
                .map { created -> created.also { ledger.record(request.orderId, it.id) } }
                .map { created ->
                    when (val confirmed = shipments.awaitConfirmed(created.id)) {
                        is Outcome.Ok ->
                            "Created InPost shipment ${created.id} for order ${request.orderId}, tracking number " +
                                "${confirmed.value.trackingNumber}. Get the label with inpost_get_label."
                        is Outcome.Err ->
                            "Created InPost shipment ${created.id} for order ${request.orderId}, but: ${confirmed.error.message}"
                    }
                }
        }

    internal suspend fun label(
        id: ShipmentId,
        format: LabelFormat,
    ): String =
        when (val pdf = shipments.label(id, format)) {
            is Outcome.Err -> pdf.error.message
            is Outcome.Ok -> {
                Files.createDirectories(labelsDir)
                val file = labelsDir.resolve("inpost-$id-${format.name}.pdf")
                Files.write(file, pdf.value)
                "Saved the ${format.name} label of shipment $id to $file (${pdf.value.size} bytes)."
            }
        }

    private fun request(
        order: Order,
        size: ParcelSize,
    ): Outcome<LockerShipmentRequest> {
        val point = order.delivery.pickupPointId
        val rawPhone = order.delivery.recipient?.phone ?: order.buyer.phone
        // The number as InPost will get it, so the preview shows exactly what is sent
        val phone = rawPhone?.let(::polishMobile)
        val email = order.buyer.email
        val problem =
            when {
                order.status != OrderStatus.READY_FOR_SHIPMENT -> "order ${order.id} is ${order.status}, not waiting for shipment"
                order.delivery.method != DeliveryMethod.INPOST_LOCKER || point == null ->
                    "the buyer chose '${order.delivery.methodName}', not an InPost locker"
                rawPhone == null -> "the order has no phone number for the locker SMS"
                phone == null -> "InPost needs a Polish mobile number for the locker SMS, the order has '$rawPhone'"
                email == null -> "the order has no e-mail for the buyer"
                else -> null
            }
        return if (problem != null) {
            Outcome.Err(
                io.github.anetesa.sellerflow.core.SellerFlowError
                    .Invalid("Cannot ship order ${order.id}: $problem."),
            )
        } else {
            Outcome.Ok(
                LockerShipmentRequest(
                    orderId = order.id,
                    receiverFirstName = order.delivery.recipient?.firstName ?: order.buyer.firstName,
                    receiverLastName = order.delivery.recipient?.lastName ?: order.buyer.lastName,
                    receiverEmail = email!!,
                    receiverPhone = phone!!,
                    targetPoint = point!!,
                    size = size,
                ),
            )
        }
    }

    private companion object {
        const val ACTION = "inpost_create_shipment"
        val SIZE_HINT =
            mapOf(
                ParcelSize.A to "up to 8 × 38 × 64 cm",
                ParcelSize.B to "up to 19 × 38 × 64 cm",
                ParcelSize.C to "up to 41 × 38 × 64 cm",
            )
    }
}

private fun Outcome<String>.text(): String =
    when (this) {
        is Outcome.Ok -> value
        is Outcome.Err -> error.message
    }
