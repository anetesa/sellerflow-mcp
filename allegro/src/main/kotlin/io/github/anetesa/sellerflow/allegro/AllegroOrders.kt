package io.github.anetesa.sellerflow.allegro

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import io.github.anetesa.sellerflow.core.flatMap
import io.github.anetesa.sellerflow.core.map
import io.github.anetesa.sellerflow.core.model.Order
import io.github.anetesa.sellerflow.core.model.OrderId
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart

/** Which orders to list. The default is what the seller has to ship: paid and not yet sent. */
data class OrderQuery(
    val statuses: List<String> = listOf("READY_FOR_PROCESSING"),
    val fulfillmentStatuses: List<String> = listOf("NEW", "PROCESSING"),
    /** Orders changed at or after this time (ISO 8601), e.g. to look at yesterday only. */
    val updatedFrom: String? = null,
    /** At most this many orders; Allegro serves 100 per page and 10 000 in total. */
    val max: Int = DEFAULT_MAX,
) {
    companion object {
        const val DEFAULT_MAX = 500
    }
}

/** The seller's Allegro orders. [accessToken] gives a valid token of the logged-in seller (refreshed by the caller). */
class AllegroOrders(
    private val http: AllegroHttp,
    private val accessToken: suspend () -> Outcome<String>,
) {
    suspend fun list(query: OrderQuery = OrderQuery()): Outcome<List<Order>> {
        val orders = mutableListOf<Order>()
        var offset = 0
        while (orders.size < query.max) {
            val limit = minOf(PAGE, query.max - orders.size)
            when (val page = page(query, offset, limit)) {
                is Outcome.Err -> return page
                is Outcome.Ok -> {
                    orders += page.value.checkoutForms.map { it.toOrder() }
                    offset += page.value.checkoutForms.size
                    if (page.value.checkoutForms.isEmpty() || offset >= page.value.totalCount) break
                }
            }
        }
        return Outcome.Ok(orders)
    }

    /** One order by its Allegro id, whatever its status. */
    suspend fun get(id: OrderId): Outcome<Order> =
        accessToken()
            .flatMap { token ->
                http.send {
                    method = HttpMethod.Get
                    url("${http.endpoints.allegroApi}/order/checkout-forms/${id.value.encodeURLPathPart()}")
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(HttpHeaders.Accept, PUBLIC_V1)
                }
            }.flatMap { response ->
                // Allegro answers 404 for an unknown order and 422 ("Not valid time UUID") for something that is no order id
                if (response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.UnprocessableEntity) {
                    Outcome.Err(
                        SellerFlowError.Invalid("Allegro has no order $id on this account (order ids look like 2f9a1c40-9584-11e8-…)."),
                    )
                } else {
                    decode<CheckoutForm>(response).map { it.toOrder() }
                }
            }

    private suspend fun page(
        query: OrderQuery,
        offset: Int,
        limit: Int,
    ): Outcome<CheckoutFormsPage> =
        accessToken()
            .flatMap { token ->
                http.send {
                    method = HttpMethod.Get
                    url("${http.endpoints.allegroApi}/order/checkout-forms")
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(HttpHeaders.Accept, PUBLIC_V1)
                    query.statuses.forEach { parameter("status", it) }
                    query.fulfillmentStatuses.forEach { parameter("fulfillment.status", it) }
                    query.updatedFrom?.let { parameter("updatedAt.gte", it) }
                    parameter("limit", limit)
                    parameter("offset", offset)
                }
            }.flatMap { decode<CheckoutFormsPage>(it) }

    private companion object {
        const val PAGE = 100
        const val PUBLIC_V1 = "application/vnd.allegro.public.v1+json"
    }
}
