package io.github.anetesa.sellerflow.ksef

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import io.github.anetesa.sellerflow.core.flatMap
import io.github.anetesa.sellerflow.core.map
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What KSeF gave back for an accepted invoice: its KSeF number and the official receipt (UPO, XML). */
data class KsefReceipt(
    val ksefNumber: String,
    val sessionReference: String,
    val invoiceReference: String,
    val upoXml: String?,
)

/** An open interactive session and the AES key its invoices are encrypted with (one fresh key per session). */
class OnlineSession internal constructor(
    val referenceNumber: String,
    internal val key: ByteArray,
    internal val iv: ByteArray,
)

/**
 * Sends FA(3) invoices to KSeF through an interactive (online) session: AES-256-CBC for the invoice, the AES key
 * encrypted with the Ministry's SymmetricKeyEncryption key, SHA-256 hashes and sizes of both, then polling until
 * KSeF assigns the KSeF number or says why it refused the invoice.
 */
class KsefInvoices(
    private val http: KsefHttp,
    private val auth: KsefAuth,
) {
    /** Opens a session, sends [xml], waits for the verdict, fetches the UPO and always closes the session. */
    suspend fun issue(
        accessToken: String,
        xml: String,
        sleep: suspend () -> Unit = { delay(POLL_MILLIS) },
    ): Outcome<KsefReceipt> =
        open(accessToken).flatMap { session ->
            val result =
                send(accessToken, session, xml).flatMap { invoice ->
                    await(accessToken, session, invoice, sleep).map { ksefNumber ->
                        KsefReceipt(
                            ksefNumber,
                            session.referenceNumber,
                            invoice,
                            upo(accessToken, session, invoice).let { (it as? Outcome.Ok)?.value },
                        )
                    }
                }
            close(accessToken, session)
            result
        }

    suspend fun open(accessToken: String): Outcome<OnlineSession> =
        auth.publicKey(KsefAuth.USAGE_SYMMETRIC_KEY).flatMap { mf ->
            val random = SecureRandom()
            val key = ByteArray(AES_KEY_BYTES).also(random::nextBytes)
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val request =
                OpenRequest(
                    FormCode("FA (3)", "1-0E", "FA"),
                    Encryption(b64(KsefAuth.encryptOaep(mf.key, key)), b64(iv), mf.id),
                )
            http
                .send {
                    method = HttpMethod.Post
                    url("${http.baseUrl}/sessions/online")
                    bearerAuth(accessToken)
                    contentType(ContentType.Application.Json)
                    setBody(JSON.encodeToString(OpenRequest.serializer(), request))
                }.flatMap { decode<OpenResponse>(it) }
                .map { OnlineSession(it.referenceNumber, key, iv) }
        }

    /** Encrypts and sends one invoice; the answer is its reference number in the session. */
    suspend fun send(
        accessToken: String,
        session: OnlineSession,
        xml: String,
    ): Outcome<String> {
        val plain = xml.toByteArray(Charsets.UTF_8)
        val encrypted = encryptAes(session.key, session.iv, plain)
        val request = SendRequest(sha256(plain), plain.size.toLong(), sha256(encrypted), encrypted.size.toLong(), b64(encrypted), false)
        return http
            .send {
                method = HttpMethod.Post
                url("${http.baseUrl}/sessions/online/${session.referenceNumber}/invoices")
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(JSON.encodeToString(SendRequest.serializer(), request))
            }.flatMap { decode<ReferenceResponse>(it) }
            .map { it.referenceNumber }
    }

    /** Polls the invoice until KSeF accepts it (its KSeF number) or rejects it (the reason). */
    suspend fun await(
        accessToken: String,
        session: OnlineSession,
        invoiceReference: String,
        sleep: suspend () -> Unit = { delay(POLL_MILLIS) },
    ): Outcome<String> {
        var status = status(accessToken, session, invoiceReference)
        var left = MAX_POLLS
        while (left > 0 && status is Outcome.Ok && status.value.status.code in PROCESSING) {
            sleep()
            status = status(accessToken, session, invoiceReference)
            left--
        }
        return status.flatMap { invoice -> verdict(invoice) }
    }

    suspend fun upo(
        accessToken: String,
        session: OnlineSession,
        invoiceReference: String,
    ): Outcome<String> =
        http
            .send {
                method = HttpMethod.Get
                url("${http.baseUrl}/sessions/${session.referenceNumber}/invoices/$invoiceReference/upo")
                bearerAuth(accessToken)
                header(HttpHeaders.Accept, "application/xml")
            }.flatMap { response ->
                if (response.status.value in HTTP_OK) {
                    Outcome.Ok(response.bodyAsText())
                } else {
                    Outcome.Err(SellerFlowError.Remote(KsefHttp.SERVICE, "KSeF gave no UPO yet: ${ksefErrorText(response.bodyAsText())}"))
                }
            }

    suspend fun close(
        accessToken: String,
        session: OnlineSession,
    ): Outcome<Unit> =
        http
            .send {
                method = HttpMethod.Post
                url("${http.baseUrl}/sessions/online/${session.referenceNumber}/close")
                bearerAuth(accessToken)
            }.map { }

    private suspend fun status(
        accessToken: String,
        session: OnlineSession,
        invoiceReference: String,
    ): Outcome<InvoiceStatusResponse> =
        http
            .send {
                method = HttpMethod.Get
                url("${http.baseUrl}/sessions/${session.referenceNumber}/invoices/$invoiceReference")
                bearerAuth(accessToken)
            }.flatMap { decode<InvoiceStatusResponse>(it) }

    private fun verdict(invoice: InvoiceStatusResponse): Outcome<String> {
        val status = invoice.status
        val ksefNumber = invoice.ksefNumber
        return when {
            status.code == SUCCESS && ksefNumber != null -> Outcome.Ok(ksefNumber)
            status.code in PROCESSING ->
                Outcome.Err(SellerFlowError.Remote(KsefHttp.SERVICE, "KSeF is still processing the invoice; check again in a minute."))
            status.code == DUPLICATE ->
                Outcome.Err(
                    SellerFlowError.AlreadyDone(
                        "KSeF already has this invoice" +
                            (
                                status.extensions?.get(
                                    "originalKsefNumber",
                                ) as? JsonPrimitive
                            )?.content?.let { " (KSeF number $it)" }.orEmpty() +
                            ".",
                    ),
                )
            else ->
                Outcome.Err(
                    SellerFlowError.Invalid(
                        "KSeF rejected the invoice: ${status.code} ${status.description}" +
                            status.details
                                .orEmpty()
                                .joinToString(
                                    "; ",
                                    prefix = ": ",
                                ).takeIf { status.details?.isNotEmpty() == true }
                                .orEmpty(),
                    ),
                )
        }
    }

    companion object {
        private const val AES_KEY_BYTES = 32
        private const val IV_BYTES = 16
        private const val SUCCESS = 200
        private const val DUPLICATE = 440
        private val PROCESSING = setOf(100, 150)
        private const val MAX_POLLS = 60
        private const val POLL_MILLIS = 1_000L
        private val HTTP_OK = 200..299

        /** AES-256-CBC with PKCS#7 padding (Java calls it PKCS5Padding for AES). */
        fun encryptAes(
            key: ByteArray,
            iv: ByteArray,
            plain: ByteArray,
        ): ByteArray =
            Cipher
                .getInstance("AES/CBC/PKCS5Padding")
                .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
                .doFinal(plain)

        private fun sha256(bytes: ByteArray) = b64(MessageDigest.getInstance("SHA-256").digest(bytes))

        private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    }
}

@Serializable
private data class FormCode(
    val systemCode: String,
    val schemaVersion: String,
    val value: String,
)

@Serializable
private data class Encryption(
    val encryptedSymmetricKey: String,
    val initializationVector: String,
    val publicKeyId: String,
)

@Serializable
private data class OpenRequest(
    val formCode: FormCode,
    val encryption: Encryption,
)

@Serializable
private data class OpenResponse(
    val referenceNumber: String,
)

@Serializable
private data class SendRequest(
    val invoiceHash: String,
    val invoiceSize: Long,
    val encryptedInvoiceHash: String,
    val encryptedInvoiceSize: Long,
    val encryptedInvoiceContent: String,
    val offlineMode: Boolean,
)

@Serializable
private data class ReferenceResponse(
    val referenceNumber: String,
)

@Serializable
internal data class InvoiceStatus(
    val code: Int,
    val description: String,
    val details: List<String>? = null,
    val extensions: JsonObject? = null,
)

@Serializable
internal data class InvoiceStatusResponse(
    val status: InvoiceStatus,
    val ksefNumber: String? = null,
    val invoiceNumber: String? = null,
)
