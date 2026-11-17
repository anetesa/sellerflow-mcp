package io.github.anetesa.sellerflow.allegro

import kotlinx.serialization.Serializable

// Allegro's order ("checkout form") as GET /order/checkout-forms returns it. Only the fields SellerFlow uses are
// declared; everything optional in Allegro's documentation is nullable here, unknown fields are ignored.

@Serializable
internal data class CheckoutFormsPage(
    val checkoutForms: List<CheckoutForm> = emptyList(),
    val count: Int = 0,
    val totalCount: Int = 0,
)

@Serializable
internal data class CheckoutForm(
    val id: String,
    val status: String,
    val buyer: BuyerDto,
    val fulfillment: FulfillmentDto? = null,
    val delivery: DeliveryDto? = null,
    val invoice: InvoiceDto? = null,
    val lineItems: List<LineItemDto> = emptyList(),
    val summary: SummaryDto,
    val updatedAt: String,
)

@Serializable
internal data class AmountDto(
    val amount: String,
    val currency: String,
)

@Serializable
internal data class BuyerDto(
    val login: String,
    val email: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val companyName: String? = null,
    val phoneNumber: String? = null,
)

@Serializable
internal data class FulfillmentDto(
    val status: String? = null,
)

@Serializable
internal data class DeliveryDto(
    val method: DeliveryMethodDto? = null,
    val pickupPoint: PickupPointDto? = null,
    val address: DeliveryAddressDto? = null,
)

@Serializable
internal data class DeliveryMethodDto(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
internal data class PickupPointDto(
    val id: String,
)

@Serializable
internal data class DeliveryAddressDto(
    val firstName: String? = null,
    val lastName: String? = null,
    val phoneNumber: String? = null,
    val street: String? = null,
    val city: String? = null,
    val zipCode: String? = null,
    val countryCode: String? = null,
)

@Serializable
internal data class InvoiceDto(
    val required: Boolean = false,
    val address: InvoiceAddressDto? = null,
)

@Serializable
internal data class InvoiceAddressDto(
    val street: String? = null,
    val city: String? = null,
    val zipCode: String? = null,
    val countryCode: String? = null,
    val company: CompanyDto? = null,
    val naturalPerson: NaturalPersonDto? = null,
)

@Serializable
internal data class CompanyDto(
    val name: String? = null,
    val taxId: String? = null,
)

@Serializable
internal data class NaturalPersonDto(
    val firstName: String? = null,
    val lastName: String? = null,
)

@Serializable
internal data class LineItemDto(
    val offer: OfferDto,
    val quantity: Int,
    val price: AmountDto,
    val boughtAt: String? = null,
)

@Serializable
internal data class OfferDto(
    val id: String,
    val name: String,
)

@Serializable
internal data class SummaryDto(
    val totalToPay: AmountDto,
)
