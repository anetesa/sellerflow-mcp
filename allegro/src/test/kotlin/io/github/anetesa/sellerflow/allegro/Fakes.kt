package io.github.anetesa.sellerflow.allegro

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.config.Environment
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

const val USER_AGENT = "SellerFlow/1.0 (+https://github.com/anetesa/sellerflow-mcp)"

/** An Allegro that answers from [handler] and remembers every request it got. */
class FakeAllegro(
    private val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val requests = mutableListOf<HttpRequestData>()

    val http: AllegroHttp =
        (
            AllegroHttp.create(
                HttpClient(
                    MockEngine { request ->
                        requests += request
                        handler(request)
                    },
                ),
                Environment.SANDBOX.endpoints,
                clientId = "client-id",
                clientSecret = "client-secret",
                userAgent = USER_AGENT,
            ) as Outcome.Ok
        ).value
}

fun MockRequestHandleScope.json(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(body, status, headersOf("Content-Type", "application/json"))
