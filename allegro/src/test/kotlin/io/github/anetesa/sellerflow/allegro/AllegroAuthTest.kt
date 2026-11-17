package io.github.anetesa.sellerflow.allegro

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val NOW = Instant.parse("2026-11-17T12:00:00Z")
private val CLOCK = Clock.fixed(NOW, ZoneOffset.UTC)
private val CODE =
    DeviceCode("dev-code", "ABC123", "https://allegro.pl/skojarz-aplikacje", "https://x?code=ABC123", 3600, 5)

private fun HttpRequestData.form() = (body as FormDataContent).formData

class AllegroAuthTest :
    FunSpec({
        test("starts the device flow with Basic auth and the client id") {
            val allegro =
                FakeAllegro {
                    json(
                        """{"device_code":"dev-code","user_code":"ABC123","verification_uri":"https://allegro.pl/skojarz-aplikacje",
                           "verification_uri_complete":"https://allegro.pl/skojarz-aplikacje?code=ABC123",
                           "expires_in":3600,"interval":5}""",
                    )
                }
            val code = AllegroAuth(allegro.http, CLOCK).startDeviceFlow().shouldBeInstanceOf<Outcome.Ok<DeviceCode>>().value
            code.userCode shouldBe "ABC123"
            code.intervalSeconds shouldBe 5
            val request = allegro.requests.single()
            request.url.toString() shouldBe "https://allegro.pl.allegrosandbox.pl/auth/oauth/device"
            request.headers[HttpHeaders.Authorization] shouldStartWith "Basic "
            request.form()["client_id"] shouldBe "client-id"
        }

        test("waits while pending, slows down when asked, returns the tokens once approved") {
            val answers =
                ArrayDeque(
                    listOf(
                        """{"error":"authorization_pending"}""" to HttpStatusCode.BadRequest,
                        """{"error":"slow_down"}""" to HttpStatusCode.BadRequest,
                        """{"access_token":"at","refresh_token":"rt","expires_in":43199,"token_type":"bearer"}""" to HttpStatusCode.OK,
                    ),
                )
            val allegro = FakeAllegro { answers.removeFirst().let { (body, status) -> json(body, status) } }
            val waits = mutableListOf<Long>()
            val tokens =
                AllegroAuth(allegro.http, CLOCK)
                    .awaitAuthorization(CODE) { waits += it }
                    .shouldBeInstanceOf<Outcome.Ok<AllegroTokens>>()
                    .value
            tokens.accessToken shouldBe "at"
            tokens.refreshToken shouldBe "rt"
            tokens.expiresAt shouldBe NOW.plusSeconds(43199)
            waits shouldBe listOf(5L, 5L, 10L)
            allegro.requests.forEach {
                it.form()["grant_type"] shouldBe "urn:ietf:params:oauth:grant-type:device_code"
                it.form()["device_code"] shouldBe "dev-code"
            }
        }

        test("stops when the seller refuses") {
            val allegro = FakeAllegro { json("""{"error":"access_denied"}""", HttpStatusCode.BadRequest) }
            val error =
                AllegroAuth(allegro.http, CLOCK)
                    .awaitAuthorization(CODE) {}
                    .shouldBeInstanceOf<Outcome.Err>()
                    .error
            error.shouldBeInstanceOf<SellerFlowError.Auth>()
            error.message shouldContain "refused"
            allegro.requests.size shouldBe 1
        }

        test("gives up when the code expires before approval") {
            var now = NOW
            val clock =
                object : Clock() {
                    override fun getZone() = ZoneOffset.UTC

                    override fun withZone(zone: java.time.ZoneId?) = this

                    override fun instant() = now
                }
            val allegro = FakeAllegro { json("""{"error":"authorization_pending"}""", HttpStatusCode.BadRequest) }
            AllegroAuth(allegro.http, clock)
                .awaitAuthorization(CODE.copy(expiresInSeconds = 12)) { now = now.plusSeconds(it) }
                .shouldBeInstanceOf<Outcome.Err>()
                .error.message shouldContain "expired"
        }

        test("refresh sends the refresh token and returns the rotated pair") {
            val allegro = FakeAllegro { json("""{"access_token":"at2","refresh_token":"rt2","expires_in":43199}""") }
            val tokens =
                AllegroAuth(allegro.http, CLOCK).refresh("rt").shouldBeInstanceOf<Outcome.Ok<AllegroTokens>>().value
            tokens.refreshToken shouldBe "rt2"
            allegro.requests.single().form()["grant_type"] shouldBe "refresh_token"
            allegro.requests.single().form()["refresh_token"] shouldBe "rt"
        }

        test("a refused refresh asks to log in again") {
            val allegro = FakeAllegro { json("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest) }
            AllegroAuth(allegro.http, CLOCK)
                .refresh("old")
                .shouldBeInstanceOf<Outcome.Err>()
                .error.message shouldContain "Log in again"
        }

        test("tokens never show up in logs") {
            AllegroTokens("secret-access", "secret-refresh", NOW).toString() shouldNotContain "secret"
        }
    })
