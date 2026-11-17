package io.github.anetesa.sellerflow.ksef

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.config.Environment
import io.github.anetesa.sellerflow.core.model.Nip
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.random.Random

/**
 * The whole KSeF TEST login against the real environment: `./gradlew :ksef:test -Pintegration` with
 * SELLERFLOW_KSEF_TEST=1. A random NIP signs in with a self-signed XAdES certificate (allowed only in TEST), issues a
 * KSeF token, and SellerFlow's own token login then uses it. With SELLERFLOW_KSEF_BOOTSTRAP_OUT set, the NIP and the
 * token are written to that file (owner-only) for later sandbox runs.
 */
class KsefTestEnvironmentTest :
    FunSpec({
        tags(NamedTag("Integration"))
        val enabled = System.getenv("SELLERFLOW_KSEF_TEST") == "1"

        test("XAdES bootstrap, KSeF token issued, token login and refresh").config(enabled = enabled) {
            val client = HttpClient(CIO)
            val base = Environment.SANDBOX.endpoints.ksefApi
            val auth = KsefAuth(KsefHttp(client, base))
            val nip = randomNip()

            val challenge = auth.challenge().shouldBeInstanceOf<Outcome.Ok<ChallengeResponse>>().value
            val signed = XadesSigner.sign(XadesSigner.authTokenRequest(challenge.challenge, nip.value), TestCertificates.person(nip.value))
            val owner = auth.loginWithSignedXml(signed).shouldBeInstanceOf<Outcome.Ok<KsefTokens>>().value

            val issued =
                Json
                    .parseToJsonElement(
                        client
                            .post("$base/tokens") {
                                bearerAuth(owner.accessToken)
                                contentType(ContentType.Application.Json)
                                setBody("""{"permissions":["InvoiceRead","InvoiceWrite"],"description":"SellerFlow integration test"}""")
                            }.bodyAsText(),
                    ).jsonObject
            val reference = issued["referenceNumber"]!!.jsonPrimitive.content
            val ksefToken = issued["token"]!!.jsonPrimitive.content
            var status = ""
            repeat(30) {
                if (status != "Active") {
                    delay(1_000)
                    status =
                        Json
                            .parseToJsonElement(client.get("$base/tokens/$reference") { bearerAuth(owner.accessToken) }.bodyAsText())
                            .jsonObject["status"]!!
                            .jsonPrimitive.content
                }
            }
            status shouldBe "Active"

            val tokens = auth.loginWithToken(nip, ksefToken).shouldBeInstanceOf<Outcome.Ok<KsefTokens>>().value
            auth.refresh(tokens).shouldBeInstanceOf<Outcome.Ok<KsefTokens>>()

            System.getenv("SELLERFLOW_KSEF_BOOTSTRAP_OUT")?.let { path ->
                File(path).apply {
                    writeText("SELLERFLOW_KSEF_NIP=${nip.value}\nSELLERFLOW_KSEF_TOKEN=$ksefToken\n")
                    setReadable(false, false)
                    setReadable(true, true)
                    setWritable(false, false)
                    setWritable(true, true)
                }
            }
            client.close()
        }
    })

/** A random NIP with a valid checksum: KSeF TEST data is shared, so tests must not use real companies. */
private fun randomNip(): Nip =
    generateSequence { (1..9).map { if (it == 1) Random.nextInt(1, 10) else Random.nextInt(0, 10) }.joinToString("") }
        .mapNotNull { digits -> (0..9).asSequence().mapNotNull { Nip.parse("$digits$it") }.firstOrNull() }
        .first()
