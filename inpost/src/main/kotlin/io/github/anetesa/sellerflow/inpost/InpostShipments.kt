package io.github.anetesa.sellerflow.inpost

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import io.github.anetesa.sellerflow.core.flatMap
import io.github.anetesa.sellerflow.core.map
import io.github.anetesa.sellerflow.core.model.OrderId
import io.github.anetesa.sellerflow.core.model.ParcelSize
import io.github.anetesa.sellerflow.core.model.Shipment
import io.github.anetesa.sellerflow.core.model.ShipmentId
import io.github.anetesa.sellerflow.core.model.ShipmentStatus
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/** A locker parcel to create: who picks it up, where, and how big it is. [orderId] goes to InPost as the reference. */
data class LockerShipmentRequest(
    val orderId: OrderId,
    val receiverFirstName: String?,
    val receiverLastName: String?,
    val receiverEmail: String,
    val receiverPhone: String,
    val targetPoint: String,
    val size: ParcelSize,
)

/** A parcel locker as InPost describes it. */
data class ParcelLocker(
    val name: String,
    val operating: Boolean,
    val address: String,
    val description: String?,
)

enum class LabelFormat(
    internal val type: String,
) {
    /** One label per A6 page, for label printers. */
    A6("A6"),

    /** A4 page, for office printers. */
    A4("normal"),
}

/** InPost ShipX: lockers, locker shipments and their labels. */
class InpostShipments(
    private val http: InpostHttp,
) {
    /** The locker, or [SellerFlowError.Invalid] when InPost does not know the name. */
    suspend fun locker(name: String): Outcome<ParcelLocker> =
        http
            .send {
                method = HttpMethod.Get
                url("${http.baseUrl}/v1/points/${name.trim().uppercase()}")
            }.flatMap { decode<PointDto>(it) }
            .flatMap { point ->
                // ShipX answers 200 with {"status":404} for a name it does not know
                if (point.name == null) {
                    Outcome.Err(SellerFlowError.Invalid("InPost has no parcel locker named '$name'."))
                } else {
                    Outcome.Ok(
                        ParcelLocker(
                            name = point.name,
                            operating = point.status?.content == "Operating",
                            address = point.addressDetails?.run { "$street $buildingNumber, $postCode $city" }.orEmpty(),
                            description = point.locationDescription,
                        ),
                    )
                }
            }

    /**
     * Creates the shipment. InPost answers at once with status `created` and buys the offer in the background:
     * [awaitConfirmed] tells when the tracking number and the label are ready.
     */
    suspend fun create(request: LockerShipmentRequest): Outcome<Shipment> {
        val phone =
            polishMobile(request.receiverPhone)
                ?: return Outcome.Err(
                    SellerFlowError.Invalid(
                        "InPost needs a Polish mobile number (9 digits) for the locker SMS; the order has '${request.receiverPhone}'.",
                    ),
                )
        val body =
            CreateDto(
                receiver = ReceiverDto(request.receiverFirstName, request.receiverLastName, request.receiverEmail, phone),
                parcels = listOf(ParcelDto(TEMPLATES.getValue(request.size))),
                customAttributes = AttributesDto(targetPoint = request.targetPoint.trim().uppercase(), sendingMethod = "dispatch_order"),
                service = "inpost_locker_standard",
                reference = request.orderId.value,
            )
        return http
            .send {
                method = HttpMethod.Post
                url("${http.baseUrl}/v1/organizations/${http.organizationId}/shipments")
                contentType(ContentType.Application.Json)
                setBody(JSON.encodeToString(CreateDto.serializer(), body))
            }.flatMap { decode<ShipmentDto>(it) }
            .map { it.toShipment() }
    }

    suspend fun get(id: ShipmentId): Outcome<Shipment> =
        fetch(id).flatMap { dto ->
            failure(dto)?.let { Outcome.Err(it) }
                ?: Outcome.Ok(dto.toShipment())
        }

    /**
     * Polls until InPost confirms the shipment (tracking number assigned) or reports why it could not buy it,
     * e.g. no funds on a prepaid account. [sleep] is replaceable so tests do not wait.
     */
    suspend fun awaitConfirmed(
        id: ShipmentId,
        attempts: Int = DEFAULT_ATTEMPTS,
        sleep: suspend () -> Unit = { delay(POLL_MILLIS) },
    ): Outcome<Shipment> {
        var last: Outcome<Shipment> = get(id)
        var left = attempts
        while (left > 0 && last is Outcome.Ok && last.value.status == ShipmentStatus.CREATED) {
            sleep()
            last = get(id)
            left--
        }
        return when {
            last is Outcome.Ok && last.value.status == ShipmentStatus.CREATED ->
                Outcome.Err(
                    SellerFlowError.Remote(
                        InpostHttp.SERVICE,
                        "InPost has not confirmed shipment $id yet. Check it again in a minute; the label is available once confirmed.",
                    ),
                )
            else -> last
        }
    }

    /** The label as a PDF; InPost gives it only for a confirmed shipment. */
    suspend fun label(
        id: ShipmentId,
        format: LabelFormat = LabelFormat.A6,
    ): Outcome<ByteArray> =
        http
            .send {
                method = HttpMethod.Get
                url("${http.baseUrl}/v1/shipments/$id/label")
                parameter("format", "Pdf")
                parameter("type", format.type)
                header(HttpHeaders.Accept, "application/pdf")
            }.flatMap { response ->
                if (response.status.value in HTTP_OK) {
                    Outcome.Ok(response.bodyAsBytes())
                } else {
                    decode<Unit>(response).flatMap { Outcome.Err(SellerFlowError.Invalid("InPost gave no label for $id.")) }
                }
            }

    private suspend fun fetch(id: ShipmentId): Outcome<ShipmentDto> =
        http
            .send {
                method = HttpMethod.Get
                url("${http.baseUrl}/v1/shipments/$id")
            }.flatMap { decode<ShipmentDto>(it) }

    /** A purchase InPost tried and failed, as a message the seller can act on; null while nothing failed. */
    private fun failure(dto: ShipmentDto): SellerFlowError? {
        val failed =
            dto.transactions
                .lastOrNull { it.status == "failure" }
                ?.takeIf { dto.status == "offer_selected" || dto.status == "offers_prepared" }
        return when {
            failed == null -> null
            failed.details?.error == "debt_collection" ->
                SellerFlowError.Invalid(
                    "InPost could not pay for shipment ${dto.id}: the InPost account has no funds. Top it up in the parcel " +
                        "manager (Płatności) or ask InPost for invoice billing, then create the shipment again.",
                )
            else ->
                SellerFlowError.Remote(
                    InpostHttp.SERVICE,
                    "InPost could not buy shipment ${dto.id}: ${failed.details?.message ?: failed.details?.error ?: "unknown reason"}.",
                )
        }
    }

    private companion object {
        const val DEFAULT_ATTEMPTS = 10
        const val POLL_MILLIS = 2_000L
        val TEMPLATES = mapOf(ParcelSize.A to "small", ParcelSize.B to "medium", ParcelSize.C to "large")
    }
}

private val HTTP_OK = 200..299

/** `+48 500 600 700`, `0048500600700` or `500-600-700` → `500600700`; null when it is not a Polish 9-digit number. */
fun polishMobile(raw: String): String? {
    val digits = raw.filter(Char::isDigit)
    val national =
        when {
            digits.length == PHONE_DIGITS -> digits
            digits.length == PHONE_DIGITS + 2 && digits.startsWith("48") -> digits.drop(2)
            digits.length == PHONE_DIGITS + 4 && digits.startsWith("0048") -> digits.drop(4)
            else -> null
        }
    return national?.takeIf { it.first() != '0' }
}

private const val PHONE_DIGITS = 9

internal fun ShipmentDto.toShipment(): Shipment =
    Shipment(
        id = ShipmentId(id.toString()),
        orderId = OrderId(reference.orEmpty()),
        status = shipmentStatus(status),
        parcelSize =
            when (parcels.firstOrNull()?.template) {
                "medium" -> ParcelSize.B
                "large" -> ParcelSize.C
                else -> ParcelSize.A
            },
        targetPointId = customAttributes?.targetPoint,
        trackingNumber = trackingNumber,
    )

/** ShipX has dozens of statuses; SellerFlow needs to know only how far along the parcel is. */
internal fun shipmentStatus(status: String): ShipmentStatus =
    when (status) {
        "created", "offers_prepared", "offer_selected" -> ShipmentStatus.CREATED
        "confirmed" -> ShipmentStatus.CONFIRMED
        "delivered" -> ShipmentStatus.DELIVERED
        "canceled", "cancelled", "returned_to_sender", "claimed", "missing", "rejected_by_receiver", "undelivered" ->
            ShipmentStatus.FAILED
        else -> ShipmentStatus.IN_TRANSIT
    }

@Serializable
private data class CreateDto(
    val receiver: ReceiverDto,
    val parcels: List<ParcelDto>,
    @SerialName("custom_attributes") val customAttributes: AttributesDto,
    val service: String,
    val reference: String,
)

@Serializable
private data class ReceiverDto(
    @SerialName("first_name") val firstName: String?,
    @SerialName("last_name") val lastName: String?,
    val email: String,
    val phone: String,
)

@Serializable
internal data class ParcelDto(
    val template: String? = null,
)

@Serializable
internal data class AttributesDto(
    @SerialName("target_point") val targetPoint: String? = null,
    @SerialName("sending_method") val sendingMethod: String? = null,
)

@Serializable
internal data class ShipmentDto(
    val id: Long,
    val status: String,
    @SerialName("tracking_number") val trackingNumber: String? = null,
    val reference: String? = null,
    val parcels: List<ParcelDto> = emptyList(),
    @SerialName("custom_attributes") val customAttributes: AttributesDto? = null,
    val transactions: List<TransactionDto> = emptyList(),
)

@Serializable
internal data class TransactionDto(
    val status: String,
    val details: TransactionDetailsDto? = null,
)

@Serializable
internal data class TransactionDetailsDto(
    val error: String? = null,
    val message: String? = null,
)

@Serializable
private data class PointDto(
    val name: String? = null,
    // "Operating" for a locker, the number 404 for a name ShipX does not know
    val status: JsonPrimitive? = null,
    @SerialName("location_description") val locationDescription: String? = null,
    @SerialName("address_details") val addressDetails: AddressDto? = null,
)

@Serializable
private data class AddressDto(
    val city: String? = null,
    @SerialName("post_code") val postCode: String? = null,
    val street: String? = null,
    @SerialName("building_number") val buildingNumber: String? = null,
)
