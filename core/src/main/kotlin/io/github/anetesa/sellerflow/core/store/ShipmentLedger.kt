package io.github.anetesa.sellerflow.core.store

import io.github.anetesa.sellerflow.core.model.OrderId
import io.github.anetesa.sellerflow.core.model.ShipmentId

/** Which shipment was made for which order: the guard that keeps an order from getting two shipments. */
interface ShipmentLedger {
    fun shipmentFor(order: OrderId): ShipmentId?

    fun record(
        order: OrderId,
        shipment: ShipmentId,
    )
}

class InMemoryShipmentLedger : ShipmentLedger {
    private val shipments = mutableMapOf<OrderId, ShipmentId>()

    @Synchronized
    override fun shipmentFor(order: OrderId): ShipmentId? = shipments[order]

    @Synchronized
    override fun record(
        order: OrderId,
        shipment: ShipmentId,
    ) {
        shipments.putIfAbsent(order, shipment)
    }
}
