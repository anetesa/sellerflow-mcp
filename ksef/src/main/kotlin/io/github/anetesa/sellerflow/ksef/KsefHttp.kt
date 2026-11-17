package io.github.anetesa.sellerflow.ksef

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

/** Calls to the KSeF 2.0 API of one environment (TEST or PROD). */
class KsefHttp(
    private val client: HttpClient,
    baseUrl: String,
) {
    val baseUrl: String = baseUrl.trimEnd('/')

    internal suspend fun send(configure: HttpRequestBuilder.() -> Unit): Outcome<HttpResponse> =
        try {
            // Explicit receiver: a bare call would resolve to HttpRequestBuilder.build()
            Outcome.Ok(client.request { configure(this) })
        } catch (e: IOException) {
            Outcome.Err(SellerFlowError.Remote(SERVICE, "KSeF is unreachable (${e.message}). Try again in a moment."))
        }

    companion object {
        internal const val SERVICE = "KSeF"
    }
}

internal val JSON = Json { ignoreUnknownKeys = true }

/** The body as [T], or KSeF's error (`exception.exceptionDetailList`) as a message. */
internal suspend inline fun <reified T> decode(response: HttpResponse): Outcome<T> {
    val text = response.bodyAsText()
    return when {
        response.status == HttpStatusCode.Unauthorized ->
            Outcome.Err(SellerFlowError.Auth(KsefHttp.SERVICE, "KSeF rejected the credentials (${ksefErrorText(text)}). Log in again."))
        response.status.value !in HTTP_OK ->
            Outcome.Err(
                SellerFlowError.Remote(KsefHttp.SERVICE, "KSeF answered ${response.status.value}: ${ksefErrorText(text)}"),
            )
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
    Outcome.Err(SellerFlowError.Remote(KsefHttp.SERVICE, "KSeF sent an answer SellerFlow cannot read: ${e.message}"))

/** `{"exception":{"exceptionDetailList":[{"exceptionCode":21401,"exceptionDescription":"…","details":["…"]}]}}` → text. */
internal fun ksefErrorText(text: String): String {
    val details =
        runCatching {
            ((JSON.parseToJsonElement(text) as JsonObject)["exception"] as JsonObject)["exceptionDetailList"] as JsonArray
        }.getOrNull()
    return details
        ?.joinToString("; ") { detail ->
            val d = detail as JsonObject
            listOfNotNull(
                (d["exceptionCode"] as? JsonPrimitive)?.content,
                (d["exceptionDescription"] as? JsonPrimitive)?.content,
                (d["details"] as? JsonArray)?.joinToString(", ") { (it as JsonPrimitive).content },
            ).joinToString(" ")
        }?.ifBlank { null }
        ?: text.take(ERROR_EXCERPT)
}

private val HTTP_OK = 200..299
private const val ERROR_EXCERPT = 300
