package io.github.anetesa.sellerflow.mcp

import io.github.anetesa.sellerflow.allegro.AllegroHttp
import io.github.anetesa.sellerflow.allegro.AllegroOrders
import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.config.AllegroSettings
import io.github.anetesa.sellerflow.core.config.Environment
import io.github.anetesa.sellerflow.core.config.InpostSettings
import io.github.anetesa.sellerflow.core.config.SellerFlowConfig
import io.github.anetesa.sellerflow.core.config.SellerSettings
import io.github.anetesa.sellerflow.core.model.Address
import io.github.anetesa.sellerflow.core.model.Nip
import io.github.anetesa.sellerflow.core.model.SellerProfile
import io.github.anetesa.sellerflow.core.store.InMemoryCredentialStore
import io.github.anetesa.sellerflow.core.store.InMemoryInvoiceLedger
import io.github.anetesa.sellerflow.ksef.KsefAuth
import io.github.anetesa.sellerflow.ksef.KsefHttp
import io.github.anetesa.sellerflow.ksef.KsefInvoices
import io.github.anetesa.sellerflow.ksef.KsefSession
import io.github.anetesa.sellerflow.mcp.confirm.ConfirmTokens
import io.github.anetesa.sellerflow.mcp.tools.KsefTools
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.Date

private val JSON_TYPE = headersOf("Content-Type", "application/json")
private val SELLER =
    SellerSettings(
        SellerProfile("Sklep Testowy", Nip.parse("5252674798")!!, Address("ul. Babiego Lata 19A", "53-024", "Wrocław", "PL"), null, null),
        23,
        "FV",
    )
private val CONFIG =
    SellerFlowConfig(
        Environment.SANDBOX,
        Path.of("/tmp/sf"),
        AllegroSettings("id", "SF/1.0 (+https://x.y)"),
        InpostSettings(null),
        SELLER,
    )

private fun order(
    id: String,
    invoice: Boolean = true,
) = """{"id":"$id","status":"READY_FOR_PROCESSING","fulfillment":{"status":"NEW"},
    "buyer":{"login":"jan","email":"jan@allegromail.pl","firstName":"Jan","lastName":"Kowalski"},
    "delivery":{"method":{"name":"Allegro Paczkomaty InPost"},"pickupPoint":{"id":"KRA010"}},
    "invoice":{"required":$invoice,"address":{"street":"Długa 1","city":"Kraków","zipCode":"31-001","countryCode":"PL",
      "company":{"name":"Firma Kupująca","taxId":"3861610227"}}},
    "lineItems":[{"offer":{"id":"77","name":"Adapter ELM327"},"quantity":1,"price":{"amount":"49.00","currency":"PLN"},
      "boughtAt":"2026-11-16T10:00:00Z"}],
    "summary":{"totalToPay":{"amount":"59.99","currency":"PLN"}},"updatedAt":"2026-11-16T10:05:00Z"}"""

/** A stand-in for the Ministry's certificate, so KsefAuth can encrypt; the fake KSeF never decrypts. */
private fun mfCertificate(): String {
    val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val name = X500Name("CN=MF test")
    val holder =
        JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(0), Date(4_102_444_800_000), name, keys.public)
            .build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private))
    return Base64.getEncoder().encodeToString(JcaX509CertificateConverter().getCertificate(holder).encoded)
}

/** KSeF as far as the tool needs it: token login, one session, every invoice accepted with a numbered KSeF number. */
private fun fakeKsef(
    certificate: String,
    sent: MutableList<String>,
): MockEngine {
    val routes =
        listOf<Pair<(String) -> Boolean, (String) -> String>>(
            { p: String -> p.endsWith("/security/public-key-certificates") } to { _ ->
                """[{"certificate":"$certificate","certificateId":"c","publicKeyId":"k",
                   "usage":["KsefTokenEncryption","SymmetricKeyEncryption"],"validFrom":"2025-01-01T00:00:00Z",
                   "validTo":"2099-01-01T00:00:00Z"}]"""
            },
            { p: String -> p.endsWith("/auth/challenge") } to { _ -> """{"challenge":"c","timestamp":"t","timestampMs":1}""" },
            { p: String -> p.endsWith("/auth/ksef-token") } to { _ ->
                """{"referenceNumber":"A","authenticationToken":{"token":"op","validUntil":"2099-01-01T00:00:00Z"}}"""
            },
            { p: String -> p.endsWith("/auth/A") } to { _ -> """{"status":{"code":200,"description":"ok"}}""" },
            { p: String -> p.endsWith("/auth/token/redeem") } to { _ ->
                """{"accessToken":{"token":"access","validUntil":"2099-01-01T00:00:00Z"},
                   "refreshToken":{"token":"refresh","validUntil":"2099-01-01T00:00:00Z"}}"""
            },
            { p: String -> p.endsWith("/sessions/online") } to { _ -> """{"referenceNumber":"S"}""" },
            { p: String -> p.endsWith("/sessions/online/S/invoices") } to { p ->
                sent += p
                """{"referenceNumber":"I${sent.size}"}"""
            },
            { p: String -> p.contains("/sessions/S/invoices/I") && p.endsWith("/upo") } to { _ -> "<UPO/>" },
            { p: String -> p.contains("/sessions/S/invoices/I") } to { p ->
                """{"status":{"code":200,"description":"Sukces"},"ksefNumber":"5252674798-20261117-K${p.last()}"}"""
            },
            { p: String -> p.endsWith("/close") } to { _ -> "{}" },
        )
    return MockEngine { request ->
        val path = request.url.encodedPath
        val route = routes.firstOrNull { it.first(path) } ?: error("unexpected $path")
        respond(route.second(path), HttpStatusCode.OK, JSON_TYPE)
    }
}

@OptIn(ExperimentalMcpApi::class)
class KsefToolsTest :
    FunSpec({
        val certificate = mfCertificate()

        fun client(upo: File): Pair<Client, MutableList<String>> {
            val sent = mutableListOf<String>()
            val allegro =
                (
                    AllegroHttp.create(
                        HttpClient(
                            MockEngine { request ->
                                val id = request.url.encodedPath.substringAfterLast('/')
                                respond(order(id, invoice = id != "no-invoice"), HttpStatusCode.OK, JSON_TYPE)
                            },
                        ),
                        CONFIG.endpoints,
                        "id",
                        "secret",
                        "SF/1.0 (+https://x.y)",
                    ) as Outcome.Ok
                ).value
            val ksefHttp = KsefHttp(HttpClient(fakeKsef(certificate, sent)), "https://api-test.ksef.mf.gov.pl/v2")
            val auth = KsefAuth(ksefHttp)
            val tools =
                KsefTools(
                    seller = SELLER,
                    environment = Environment.SANDBOX,
                    orders = AllegroOrders(allegro) { Outcome.Ok("seller-token") },
                    session = KsefSession(auth, InMemoryCredentialStore(), SELLER.profile.nip, "ksef-token"),
                    invoices = KsefInvoices(ksefHttp, auth),
                    ledger = InMemoryInvoiceLedger(),
                    tokens = ConfirmTokens(),
                    upoDir = upo.toPath(),
                    clock = Clock.fixed(Instant.parse("2026-11-17T12:00:00Z"), ZoneId.of("Europe/Warsaw")),
                )
            val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
            kotlinx.coroutines.runBlocking { SellerFlowServer.create(CONFIG, ksef = tools).createSession(serverTransport) }
            return Client(Implementation("test", "1.0")).apply { kotlinx.coroutines.runBlocking { connect(clientTransport) } } to sent
        }

        suspend fun Client.text(args: Map<String, Any?>) = (callTool("ksef_issue_invoice", args).content.single() as TextContent).text

        test("preview with the next number, the real invoice only with its token, never twice, numbers without gaps") {
            val upo = tempdir()
            val (client, sent) = client(upo)
            client.listTools().tools.map { it.name } shouldContain "ksef_issue_invoice"

            val preview = client.text(mapOf("order_id" to "o-1"))
            preview shouldStartWith "PREVIEW"
            preview shouldContain "KSeF TEST"
            preview shouldContain "Number: FV/1/11/2026"
            preview shouldContain "Firma Kupująca, NIP 3861610227"
            preview shouldContain "VAT 23 %: net 48.77 PLN, VAT 11.22 PLN, gross 59.99 PLN"
            sent shouldBe emptyList()

            client.text(mapOf("order_id" to "o-1", "dry_run" to false)) shouldContain "needs the confirm_token"
            val token = preview.substringAfter("confirm_token=").substringBefore(" ")
            val issued = client.text(mapOf("order_id" to "o-1", "dry_run" to false, "confirm_token" to token))
            issued shouldContain "Issued invoice FV/1/11/2026"
            issued shouldContain "KSeF number 5252674798-20261117-K1"
            File(upo, "UPO-5252674798-20261117-K1.xml").exists() shouldBe true

            client.text(mapOf("order_id" to "o-1")) shouldContain "already has invoice FV/1/11/2026"
            client.text(mapOf("order_id" to "o-2")) shouldContain "Number: FV/2/11/2026"
            client.text(mapOf("order_id" to "no-invoice")) shouldContain "did not ask for an invoice"
            sent.size shouldBe 1
            client.close()
        }
    })
