package io.github.anetesa.sellerflow.allegro

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import io.github.anetesa.sellerflow.core.config.Endpoints
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import java.io.IOException
import java.util.Base64

/**
 * Every request SellerFlow sends to Allegro goes through here, so every one carries the app's User-Agent:
 * Allegro blocks the API key of an app that calls without it. [create] refuses to build the client while the
 * client id, the secret or the User-Agent is missing, so nothing is ever sent without them.
 */
class AllegroHttp private constructor(
    private val client: HttpClient,
    val endpoints: Endpoints,
    internal val clientId: String,
    private val clientSecret: String,
    val userAgent: String,
) {
    /** `Authorization: Basic` with the client id and secret, as the OAuth endpoints expect. */
    internal val basicAuth: String
        get() = "Basic " + Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())

    /** Sends the request with the User-Agent set; network failures become [SellerFlowError.Remote]. */
    internal suspend fun send(configure: HttpRequestBuilder.() -> Unit): Outcome<HttpResponse> =
        try {
            Outcome.Ok(
                client.request {
                    // Called with an explicit receiver: a bare call would resolve to HttpRequestBuilder.build()
                    configure(this)
                    header(HttpHeaders.UserAgent, userAgent)
                },
            )
        } catch (e: IOException) {
            Outcome.Err(SellerFlowError.Remote(SERVICE, "Allegro is unreachable (${e.message}). Try again in a moment."))
        }

    companion object {
        internal const val SERVICE = "Allegro"

        /** Allegro's format: `Name/version (+https://where-the-app-is-described)`. */
        private val USER_AGENT = Regex("""^[A-Za-z0-9._-]+/\S+ \(\+https?://\S+\)$""")

        fun create(
            client: HttpClient,
            endpoints: Endpoints,
            clientId: String?,
            clientSecret: String?,
            userAgent: String?,
        ): Outcome<AllegroHttp> {
            val missing =
                listOfNotNull(
                    "the client id (SELLERFLOW_ALLEGRO_CLIENT_ID)".takeIf { clientId.isNullOrBlank() },
                    "the client secret (SELLERFLOW_ALLEGRO_CLIENT_SECRET)".takeIf { clientSecret.isNullOrBlank() },
                    "the User-Agent (SELLERFLOW_ALLEGRO_USER_AGENT)".takeIf { userAgent.isNullOrBlank() },
                )
            return when {
                missing.isNotEmpty() ->
                    Outcome.Err(SellerFlowError.Config("Allegro is not set up: missing ${missing.joinToString()}."))
                !USER_AGENT.matches(userAgent!!.trim()) ->
                    Outcome.Err(
                        SellerFlowError.Config(
                            "SELLERFLOW_ALLEGRO_USER_AGENT '$userAgent' is not in Allegro's format 'Name/version (+https://…)'. " +
                                "Copy it from the User-Agent generator on the Allegro developer portal.",
                        ),
                    )
                else -> Outcome.Ok(AllegroHttp(client, endpoints, clientId!!.trim(), clientSecret!!.trim(), userAgent.trim()))
            }
        }
    }
}
