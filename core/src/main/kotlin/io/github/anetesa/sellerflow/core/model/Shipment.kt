package io.github.anetesa.sellerflow.core.model

@JvmInline
value class ShipmentId(
    val value: String,
) {
    override fun toString(): String = value
}

data class Shipment(
    val id: ShipmentId,
    val orderId: OrderId,
    val status: ShipmentStatus,
    val parcelSize: ParcelSize,
    val targetPointId: String?,
    /** Known only after the carrier confirms the shipment, which can take a few seconds. */
    val trackingNumber: String?,
)

enum class ShipmentStatus {
    CREATED,
    CONFIRMED,
    IN_TRANSIT,
    DELIVERED,
    FAILED,
}

/** InPost locker parcel sizes: A (8 cm), B (19 cm), C (41 cm) high. */
enum class ParcelSize {
    A,
    B,
    C,
}
