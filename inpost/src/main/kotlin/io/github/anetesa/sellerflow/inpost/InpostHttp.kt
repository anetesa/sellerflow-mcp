package io.github.anetesa.sellerflow.inpost

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

/** Calls to InPost ShipX with the organization's API token; built only when the token and organization id are set. */
class InpostHttp private constructor(
    private val client: HttpClient,
    val baseUrl: String,
    val organizationId: String,
    private val token: String,
) {
    internal suspend fun send(configure: HttpRequestBuilder.() -> Unit): Outcome<HttpResponse> =
        try {
            Outcome.Ok(
                client.request {
                    // Explicit receiver: a bare call would resolve to HttpRequestBuilder.build()
                    configure(this)
                    header(HttpHeaders.Authorization, "Bearer $token")
                },
            )
        } catch (e: IOException) {
            Outcome.Err(SellerFlowError.Remote(SERVICE, "InPost is unreachable (${e.message}). Try again in a moment."))
        }

    companion object {
        internal const val SERVICE = "InPost"

        fun create(
            client: HttpClient,
            baseUrl: String,
            organizationId: String?,
            token: String?,
        ): Outcome<InpostHttp> {
            val missing =
                listOfNotNull(
                    "the organization id (SELLERFLOW_INPOST_ORGANIZATION_ID)".takeIf { organizationId.isNullOrBlank() },
                    "the API token (SELLERFLOW_INPOST_API_TOKEN)".takeIf { token.isNullOrBlank() },
                )
            return if (missing.isEmpty()) {
                Outcome.Ok(InpostHttp(client, baseUrl.trimEnd('/'), organizationId!!.trim(), token!!.trim()))
            } else {
                Outcome.Err(SellerFlowError.Config("InPost is not set up: missing ${missing.joinToString()}."))
            }
        }
    }
}

internal val JSON = Json { ignoreUnknownKeys = true }

/** The body as [T], or a message built from ShipX's error format (`error`, `message`, `details`). */
internal suspend inline fun <reified T> decode(response: HttpResponse): Outcome<T> {
    val text = response.bodyAsText()
    return when {
        response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
            Outcome.Err(
                SellerFlowError.Auth(
                    InpostHttp.SERVICE,
                    "InPost rejected the API token. Generate a new one in the parcel manager (Moje konto → API).",
                ),
            )
        response.status.value !in HTTP_OK -> Outcome.Err(shipxError(response.status.value, text))
        else ->
            try {
                Outcome.Ok(JSON.decodeFromString<T>(text))
            } catch (e: SerializationException) {
                unreadable(e)
            } catch (e: IllegalArgumentException) {
                unreadable(e)
            }
    }
}

internal fun unreadable(e: Exception) =
    Outcome.Err(SellerFlowError.Remote(InpostHttp.SERVICE, "InPost sent an answer SellerFlow cannot read: ${e.message}"))

/** ShipX errors: validation problems are the seller's to fix, the rest are InPost's. */
internal fun shipxError(
    status: Int,
    text: String,
): SellerFlowError {
    val body = runCatching { JSON.parseToJsonElement(text) as? JsonObject }.getOrNull()
    val code = (body?.get("error") as? JsonPrimitive)?.content
    val details =
        body
            ?.get("details")
            ?.let { flatten(it) }
            ?.joinToString("; ")
            .orEmpty()
    val message = (body?.get("message") as? JsonPrimitive)?.content
    return if (code == "validation_failed" || code == "invalid_action") {
        SellerFlowError.Invalid(
            "InPost refused the request: ${details.ifBlank { message ?: code }}",
        )
    } else {
        SellerFlowError.Remote(InpostHttp.SERVICE, "InPost answered $status: ${text.take(ERROR_EXCERPT)}")
    }
}

/** `{"receiver":[{"phone":["invalid"]}]}` → `receiver.phone: invalid`. */
internal fun flatten(
    element: JsonElement,
    path: String = "",
): List<String> =
    when (element) {
        is JsonObject -> element.entries.flatMap { (key, value) -> flatten(value, if (path.isEmpty()) key else "$path.$key") }
        is JsonArray ->
            if (element.all { it is JsonPrimitive }) {
                listOf("$path: " + element.joinToString(", ") { (it as JsonPrimitive).content })
            } else {
                element.flatMap { flatten(it, path) }
            }
        is JsonPrimitive -> listOf("$path: ${element.content}")
    }

private val HTTP_OK = 200..299
private const val ERROR_EXCERPT = 300
