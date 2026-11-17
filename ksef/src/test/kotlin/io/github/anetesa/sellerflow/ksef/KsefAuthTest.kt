package io.github.anetesa.sellerflow.ksef

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import io.github.anetesa.sellerflow.core.model.Nip
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

private const val BASE = "https://api-test.ksef.mf.gov.pl/v2"
private val JSON_TYPE = headersOf("Content-Type", "application/json")
private val NIP = Nip.parse("5252674798")!!

class KsefAuthTest :
    FunSpec({
        val mf = TestCertificates.person("0000000000")
        val certificates =
            """[{"certificate":"${Base64.getEncoder().encodeToString(mf.certificate.encoded)}","certificateId":"c1",
               "publicKeyId":"key-for-tokens","usage":["KsefTokenEncryption"],"validFrom":"2025-09-29T00:00:00Z","validTo":"2099-01-01T00:00:00Z"}]"""

        fun ksef(statuses: ArrayDeque<String>): Pair<KsefAuth, MutableList<HttpRequestData>> {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        requests += request
                        val path = request.url.encodedPath
                        when {
                            path.endsWith("/auth/challenge") ->
                                respond(
                                    """{"challenge":"20261117-CR-38557B3000-EC7CEBBCE2-E8","timestamp":"2026-11-17T16:24:30Z",
                                       "timestampMs":1794932670387}""",
                                    HttpStatusCode.OK,
                                    JSON_TYPE,
                                )
                            path.endsWith("/security/public-key-certificates") -> respond(certificates, HttpStatusCode.OK, JSON_TYPE)
                            path.endsWith("/auth/ksef-token") ->
                                respond(
                                    """{"referenceNumber":"20261117-AU-1",
                                       "authenticationToken":{"token":"op-jwt","validUntil":"2026-11-17T17:00:00Z"}}""",
                                    HttpStatusCode.Accepted,
                                    JSON_TYPE,
                                )
                            path.endsWith("/auth/20261117-AU-1") -> respond(statuses.removeFirst(), HttpStatusCode.OK, JSON_TYPE)
                            path.endsWith("/auth/token/redeem") ->
                                respond(
                                    """{"accessToken":{"token":"access-jwt","validUntil":"2026-11-17T16:40:00Z"},
                                       "refreshToken":{"token":"refresh-jwt","validUntil":"2026-11-24T16:25:00Z"}}""",
                                    HttpStatusCode.OK,
                                    JSON_TYPE,
                                )
                            path.endsWith("/auth/token/refresh") ->
                                respond(
                                    """{"accessToken":{"token":"access-2","validUntil":"2026-11-17T16:55:00Z"}}""",
                                    HttpStatusCode.OK,
                                    JSON_TYPE,
                                )
                            else -> error("unexpected $path")
                        }
                    },
                )
            return KsefAuth(KsefHttp(client, BASE)) to requests
        }

        fun status(
            code: Int,
            description: String,
        ) = """{"startDate":"2026-11-17T16:24:31Z","authenticationMethod":"Token","status":{"code":$code,"description":"$description"}}"""

        test("logs in with a KSeF token encrypted for the Ministry's key, waits for the check, redeems the tokens") {
            val (auth, requests) =
                ksef(
                    ArrayDeque(listOf(status(100, "Uwierzytelnianie w toku"), status(200, "Uwierzytelnianie zakończone sukcesem"))),
                )
            val tokens = auth.loginWithToken(NIP, "ksef-token-123") {}.shouldBeInstanceOf<Outcome.Ok<KsefTokens>>().value
            tokens.accessToken shouldBe "access-jwt"
            tokens.refreshToken shouldBe "refresh-jwt"

            val body =
                Json
                    .parseToJsonElement(
                        (requests.first { it.url.encodedPath.endsWith("/ksef-token") }.body as TextContent).text,
                    ).jsonObject
            body["challenge"]!!.jsonPrimitive.content shouldBe "20261117-CR-38557B3000-EC7CEBBCE2-E8"
            body["publicKeyId"]!!.jsonPrimitive.content shouldBe "key-for-tokens"
            body["contextIdentifier"]!!.jsonObject["value"]!!.jsonPrimitive.content shouldBe "5252674798"
            val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                mf.keys.private,
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
            )
            String(cipher.doFinal(Base64.getDecoder().decode(body["encryptedToken"]!!.jsonPrimitive.content))) shouldBe
                "ksef-token-123|1794932670387"

            requests.filter { it.url.encodedPath.contains("/auth/2026") || it.url.encodedPath.endsWith("/redeem") }.forEach {
                it.headers[HttpHeaders.Authorization] shouldBe "Bearer op-jwt"
            }
        }

        test("a refused login says why and never redeems") {
            val (auth, requests) =
                ksef(
                    ArrayDeque(listOf(status(450, "Uwierzytelnianie zakończone niepowodzeniem z powodu błędnego tokenu"))),
                )
            val error = auth.loginWithToken(NIP, "bad") {}.shouldBeInstanceOf<Outcome.Err>().error
            error.shouldBeInstanceOf<SellerFlowError.Auth>()
            error.message shouldContain "450"
            requests.none { it.url.encodedPath.endsWith("/redeem") } shouldBe true
        }

        test("refresh replaces only the access token, with the refresh token as bearer") {
            val (auth, requests) = ksef(ArrayDeque())
            val old = KsefTokens("access-1", java.time.Instant.EPOCH, "refresh-jwt", java.time.Instant.MAX)
            val fresh = auth.refresh(old).shouldBeInstanceOf<Outcome.Ok<KsefTokens>>().value
            fresh.accessToken shouldBe "access-2"
            fresh.refreshToken shouldBe "refresh-jwt"
            requests.single().headers[HttpHeaders.Authorization] shouldBe "Bearer refresh-jwt"
        }

        test("KSeF errors read as one line; tokens stay out of logs") {
            ksefErrorText(
                """{"exception":{"exceptionDetailList":
                   [{"exceptionCode":21405,"exceptionDescription":"Błąd walidacji","details":["NIP"]}]}}""",
            ) shouldBe
                "21405 Błąd walidacji NIP"
            KsefTokens("secret-a", java.time.Instant.EPOCH, "secret-r", java.time.Instant.EPOCH).toString() shouldNotContain "secret"
        }
    })
