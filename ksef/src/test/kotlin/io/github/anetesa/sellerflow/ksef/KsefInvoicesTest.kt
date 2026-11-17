package io.github.anetesa.sellerflow.ksef

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import java.security.MessageDigest
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

private const val BASE = "https://api-test.ksef.mf.gov.pl/v2"
private val JSON_TYPE = headersOf("Content-Type", "application/json")
private const val XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Faktura>zażółć</Faktura>"

class KsefInvoicesTest :
    FunSpec({
        val mf = TestCertificates.person("0000000000")
        val certificates =
            """[{"certificate":"${Base64.getEncoder().encodeToString(mf.certificate.encoded)}","certificateId":"c2",
               "publicKeyId":"key-for-invoices","usage":["SymmetricKeyEncryption"],"validFrom":"2025-09-29T00:00:00Z","validTo":"2099-01-01T00:00:00Z"}]"""

        fun ksef(statuses: ArrayDeque<String>): Pair<KsefInvoices, MutableList<HttpRequestData>> {
            val requests = mutableListOf<HttpRequestData>()
            val http =
                KsefHttp(
                    HttpClient(
                        MockEngine { request ->
                            requests += request
                            val path = request.url.encodedPath
                            when {
                                path.endsWith("/security/public-key-certificates") -> respond(certificates, HttpStatusCode.OK, JSON_TYPE)
                                path.endsWith("/sessions/online") ->
                                    respond(
                                        """{"referenceNumber":"S-1","validUntil":"2026-11-18T05:00:00Z"}""",
                                        HttpStatusCode.Created,
                                        JSON_TYPE,
                                    )
                                path.endsWith(
                                    "/sessions/online/S-1/invoices",
                                ) -> respond("""{"referenceNumber":"I-1"}""", HttpStatusCode.Accepted, JSON_TYPE)
                                path.endsWith("/sessions/S-1/invoices/I-1") -> respond(statuses.removeFirst(), HttpStatusCode.OK, JSON_TYPE)
                                path.endsWith(
                                    "/upo",
                                ) ->
                                    respond(
                                        "<UPO>5252674798-20261117-ABC</UPO>",
                                        HttpStatusCode.OK,
                                        headersOf("Content-Type", "application/xml"),
                                    )
                                path.endsWith("/close") -> respond("", HttpStatusCode.NoContent)
                                else -> error("unexpected $path")
                            }
                        },
                    ),
                    BASE,
                )
            return KsefInvoices(http, KsefAuth(http)) to requests
        }

        fun status(
            code: Int,
            extra: String = "",
        ) = """{"ordinalNumber":1,"referenceNumber":"I-1","status":{"code":$code,"description":"d$code"$extra}}"""

        test("encrypts the invoice with a fresh AES key that only the Ministry's key opens, and returns the KSeF number") {
            val (invoices, requests) =
                ksef(
                    ArrayDeque(
                        listOf(
                            status(100),
                            status(150),
                            """{"status":{"code":200,"description":"Sukces"},"ksefNumber":"5252674798-20261117-ABC"}""",
                        ),
                    ),
                )
            val receipt = invoices.issue("access", XML) {}.shouldBeInstanceOf<Outcome.Ok<KsefReceipt>>().value
            receipt.ksefNumber shouldBe "5252674798-20261117-ABC"
            receipt.upoXml shouldBe "<UPO>5252674798-20261117-ABC</UPO>"

            val open =
                Json
                    .parseToJsonElement(
                        (requests.first { it.url.encodedPath.endsWith("/sessions/online") }.body as TextContent).text,
                    ).jsonObject
            open["formCode"].toString() shouldBe """{"systemCode":"FA (3)","schemaVersion":"1-0E","value":"FA"}"""
            val encryption = open["encryption"]!!.jsonObject
            encryption["publicKeyId"]!!.jsonPrimitive.content shouldBe "key-for-invoices"
            val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
            rsa.init(
                Cipher.DECRYPT_MODE,
                mf.keys.private,
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
            )
            val key = rsa.doFinal(Base64.getDecoder().decode(encryption["encryptedSymmetricKey"]!!.jsonPrimitive.content))
            val iv = Base64.getDecoder().decode(encryption["initializationVector"]!!.jsonPrimitive.content)
            key.size shouldBe 32
            iv.size shouldBe 16

            val sent =
                Json
                    .parseToJsonElement(
                        (requests.first { it.url.encodedPath.endsWith("/S-1/invoices") }.body as TextContent).text,
                    ).jsonObject
            val encrypted = Base64.getDecoder().decode(sent["encryptedInvoiceContent"]!!.jsonPrimitive.content)
            val aes =
                Cipher
                    .getInstance(
                        "AES/CBC/PKCS5Padding",
                    ).apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
            String(aes.doFinal(encrypted), Charsets.UTF_8) shouldBe XML

            fun sha(bytes: ByteArray) = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
            sent["invoiceHash"]!!.jsonPrimitive.content shouldBe sha(XML.toByteArray())
            sent["invoiceSize"]!!.jsonPrimitive.content shouldBe XML.toByteArray().size.toString()
            sent["encryptedInvoiceHash"]!!.jsonPrimitive.content shouldBe sha(encrypted)

            requests.filter { !it.url.encodedPath.endsWith("certificates") }.forEach {
                it.headers[HttpHeaders.Authorization] shouldBe
                    "Bearer access"
            }
            requests.last().url.encodedPath shouldBe "/v2/sessions/online/S-1/close"
        }

        test("a rejected invoice says why, and the session is still closed") {
            val (invoices, requests) = ksef(ArrayDeque(listOf(status(450, ""","details":["P_15: suma się nie zgadza"]"""))))
            val error = invoices.issue("access", XML) {}.shouldBeInstanceOf<Outcome.Err>().error
            error.shouldBeInstanceOf<SellerFlowError.Invalid>()
            error.message shouldContain "450"
            error.message shouldContain "P_15"
            requests.last().url.encodedPath shouldBe "/v2/sessions/online/S-1/close"
        }

        test("a duplicate points at the KSeF number it already has") {
            val (invoices, _) = ksef(ArrayDeque(listOf(status(440, ""","extensions":{"originalKsefNumber":"5252674798-20261116-XYZ"}"""))))
            val error = invoices.issue("access", XML) {}.shouldBeInstanceOf<Outcome.Err>().error
            error.shouldBeInstanceOf<SellerFlowError.AlreadyDone>()
            error.message shouldContain "5252674798-20261116-XYZ"
        }
    })
