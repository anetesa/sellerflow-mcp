package io.github.anetesa.sellerflow.allegro

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import io.github.anetesa.sellerflow.core.config.Environment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders

class AllegroHttpTest :
    FunSpec({
        test("refuses to exist without a User-Agent, so no request can go out without one") {
            val sent = mutableListOf<HttpRequestData>()
            val client =
                HttpClient(
                    MockEngine {
                        sent += it
                        error("must not be called")
                    },
                )
            val result = AllegroHttp.create(client, Environment.SANDBOX.endpoints, "id", "secret", userAgent = " ")
            val error = result.shouldBeInstanceOf<Outcome.Err>().error
            error.shouldBeInstanceOf<SellerFlowError.Config>()
            error.message shouldContain "SELLERFLOW_ALLEGRO_USER_AGENT"
            sent.shouldBeEmpty()
        }

        test("rejects a User-Agent that is not in Allegro's format") {
            AllegroHttp
                .create(HttpClient(MockEngine { error("no") }), Environment.SANDBOX.endpoints, "id", "secret", "curl/8.0")
                .shouldBeInstanceOf<Outcome.Err>()
                .error.message shouldContain "Name/version (+https://"
        }

        test("names every missing setting at once") {
            val message =
                AllegroHttp
                    .create(HttpClient(MockEngine { error("no") }), Environment.SANDBOX.endpoints, null, null, null)
                    .shouldBeInstanceOf<Outcome.Err>()
                    .error.message
            message shouldContain "SELLERFLOW_ALLEGRO_CLIENT_ID"
            message shouldContain "SELLERFLOW_ALLEGRO_CLIENT_SECRET"
            message shouldContain "SELLERFLOW_ALLEGRO_USER_AGENT"
        }

        test("sends the User-Agent on every request, OAuth and REST alike") {
            val allegro =
                FakeAllegro { request ->
                    if (request.url.encodedPath.endsWith("/device")) {
                        json(
                            """{"device_code":"d","user_code":"U","verification_uri":"https://x","verification_uri_complete":"https://x?c=U",
                               "expires_in":3600,"interval":5}""",
                        )
                    } else {
                        json("""{"checkoutForms":[],"count":0,"totalCount":0}""")
                    }
                }
            AllegroAuth(allegro.http).startDeviceFlow().shouldBeInstanceOf<Outcome.Ok<DeviceCode>>()
            AllegroOrders(allegro.http) { Outcome.Ok("token") }.list().shouldBeInstanceOf<Outcome.Ok<*>>()
            allegro.requests.size shouldBe 2
            allegro.requests.forEach { it.headers[HttpHeaders.UserAgent] shouldBe USER_AGENT }
        }
    })
