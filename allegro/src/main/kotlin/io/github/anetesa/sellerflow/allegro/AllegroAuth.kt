package io.github.anetesa.sellerflow.allegro

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import io.github.anetesa.sellerflow.core.flatMap
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant

/** What the seller sees while logging in: open [verificationUriComplete] (or [verificationUri] and type [userCode]). */
data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

/** The seller's tokens. The access token lives 12 hours, the refresh token 3 months and rotates on every refresh. */
data class AllegroTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Instant,
) {
    override fun toString(): String = "AllegroTokens(expiresAt=$expiresAt)"
}

/** One poll of the token endpoint while the seller has not finished logging in yet. */
sealed interface DevicePoll {
    data object Pending : DevicePoll

    /** Polled too often: wait longer before the next poll. */
    data object SlowDown : DevicePoll

    data class Authorized(
        val tokens: AllegroTokens,
    ) : DevicePoll
}

/**
 * OAuth device flow, the login Allegro offers to apps without a browser of their own: SellerFlow shows a link,
 * the seller approves the app on allegro.pl, SellerFlow polls until the tokens arrive.
 */
class AllegroAuth(
    private val http: AllegroHttp,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun startDeviceFlow(): Outcome<DeviceCode> =
        post("device", Parameters.build { append("client_id", http.clientId) })
            .flatMap { response -> decode<DeviceCodeDto>(response) }
            .let { outcome ->
                when (outcome) {
                    is Outcome.Ok ->
                        Outcome.Ok(
                            outcome.value.run {
                                DeviceCode(deviceCode, userCode, verificationUri, verificationUriComplete, expiresIn, interval)
                            },
                        )
                    is Outcome.Err -> outcome
                }
            }

    suspend fun poll(deviceCode: String): Outcome<DevicePoll> =
        post(
            "token",
            Parameters.build {
                append("grant_type", DEVICE_GRANT)
                append("device_code", deviceCode)
            },
        ).flatMap { response ->
            if (response.status == HttpStatusCode.OK) {
                decode<TokenDto>(response).flatMap { tokens(it) }.let { result ->
                    when (result) {
                        is Outcome.Ok -> Outcome.Ok(DevicePoll.Authorized(result.value))
                        is Outcome.Err -> result
                    }
                }
            } else {
                when (errorCode(response)) {
                    "authorization_pending" -> Outcome.Ok(DevicePoll.Pending)
                    "slow_down" -> Outcome.Ok(DevicePoll.SlowDown)
                    "access_denied" -> auth("The seller refused access on allegro.pl. Start the login again to retry.")
                    else -> auth("The login code expired or was already used. Start the login again.")
                }
            }
        }

    /**
     * Polls every [DeviceCode.intervalSeconds] (longer after a slow_down) until the seller approves, refuses or the
     * code expires. [sleep] is replaceable so tests do not wait.
     */
    suspend fun awaitAuthorization(
        code: DeviceCode,
        sleep: suspend (Long) -> Unit = { delay(it * MILLIS) },
    ): Outcome<AllegroTokens> {
        val deadline = clock.instant().plusSeconds(code.expiresInSeconds)
        var interval = code.intervalSeconds.coerceAtLeast(1)
        var result: Outcome<AllegroTokens>? = null
        while (result == null && clock.instant().isBefore(deadline)) {
            sleep(interval)
            when (val polled = poll(code.deviceCode)) {
                is Outcome.Err -> result = polled
                is Outcome.Ok ->
                    when (val state = polled.value) {
                        is DevicePoll.Authorized -> result = Outcome.Ok(state.tokens)
                        DevicePoll.SlowDown -> interval += SLOW_DOWN_STEP
                        DevicePoll.Pending -> Unit
                    }
            }
        }
        return result ?: auth("The login code expired before it was approved. Start the login again.")
    }

    /** New tokens for a refresh token; the old refresh token stops working a minute later. */
    suspend fun refresh(refreshToken: String): Outcome<AllegroTokens> =
        post(
            "token",
            Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
            },
        ).flatMap { response ->
            if (response.status == HttpStatusCode.OK) {
                decode<TokenDto>(response).flatMap { tokens(it) }
            } else {
                auth("Allegro no longer accepts the saved login (it lasts 3 months). Log in again.")
            }
        }

    private suspend fun post(
        path: String,
        form: Parameters,
    ): Outcome<HttpResponse> =
        http.send {
            method = HttpMethod.Post
            url("${http.endpoints.allegroAuth}/$path")
            header(HttpHeaders.Authorization, http.basicAuth)
            setBody(FormDataContent(form))
        }

    private fun tokens(dto: TokenDto): Outcome<AllegroTokens> {
        val refresh = dto.refreshToken ?: return auth("Allegro returned no refresh token. Log in again.")
        return Outcome.Ok(AllegroTokens(dto.accessToken, refresh, clock.instant().plusSeconds(dto.expiresIn)))
    }

    /** The OAuth error code of a refused request; null when the body is not the usual JSON. */
    private suspend fun errorCode(response: HttpResponse): String? {
        val text = response.bodyAsText()
        return runCatching { JSON.decodeFromString<ErrorDto>(text).error }.getOrNull()
    }

    @Serializable
    private data class DeviceCodeDto(
        @SerialName("device_code") val deviceCode: String,
        @SerialName("user_code") val userCode: String,
        @SerialName("verification_uri") val verificationUri: String,
        @SerialName("verification_uri_complete") val verificationUriComplete: String,
        @SerialName("expires_in") val expiresIn: Long,
        val interval: Long = DEFAULT_INTERVAL,
    )

    @Serializable
    private data class TokenDto(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long,
    )

    @Serializable
    private data class ErrorDto(
        val error: String? = null,
    )

    private companion object {
        const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        const val DEFAULT_INTERVAL = 5L
        const val SLOW_DOWN_STEP = 5L
        const val MILLIS = 1000L
    }
}

internal val JSON = Json { ignoreUnknownKeys = true }

private fun auth(message: String) = Outcome.Err(SellerFlowError.Auth(AllegroHttp.SERVICE, message))

internal suspend inline fun <reified T> decode(response: HttpResponse): Outcome<T> {
    val text = response.bodyAsText()
    return when {
        response.status == HttpStatusCode.Unauthorized ->
            auth("Allegro rejected the credentials. Check the client id and secret, or log in again.")
        response.status.value !in HTTP_OK_RANGE ->
            Outcome.Err(
                SellerFlowError.Remote(AllegroHttp.SERVICE, "Allegro answered ${response.status.value}: ${text.take(ERROR_EXCERPT)}"),
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
    Outcome.Err(SellerFlowError.Remote(AllegroHttp.SERVICE, "Allegro sent an answer SellerFlow cannot read: ${e.message}"))

private val HTTP_OK_RANGE = 200..299
private const val ERROR_EXCERPT = 300
