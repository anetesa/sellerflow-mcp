package io.github.anetesa.sellerflow.ksef

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import io.github.anetesa.sellerflow.core.flatMap
import io.github.anetesa.sellerflow.core.map
import io.github.anetesa.sellerflow.core.model.Nip
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.MGF1ParameterSpec
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/** KSeF's access token (minutes) and refresh token (up to 7 days), both JWT. */
data class KsefTokens(
    val accessToken: String,
    val accessValidUntil: Instant,
    val refreshToken: String,
    val refreshValidUntil: Instant,
) {
    override fun toString(): String = "KsefTokens(accessValidUntil=$accessValidUntil, refreshValidUntil=$refreshValidUntil)"
}

/** A public key of the Ministry of Finance and the id KSeF knows it by. */
data class KsefPublicKey(
    val id: String,
    val key: PublicKey,
)

/** An authentication KSeF accepted for processing; [operationToken] is used to poll it and to redeem the tokens. */
data class AuthOperation(
    val referenceNumber: String,
    val operationToken: String,
)

/**
 * KSeF 2.0 authentication with a KSeF token, the way an integration logs in without a qualified signature: the token
 * and the challenge's timestamp are encrypted with the Ministry's public key (RSA-OAEP, SHA-256), KSeF checks them
 * asynchronously, and the operation is redeemed for an access and a refresh token.
 */
class KsefAuth(
    private val http: KsefHttp,
) {
    suspend fun loginWithToken(
        nip: Nip,
        ksefToken: String,
        sleep: suspend () -> Unit = { delay(POLL_MILLIS) },
    ): Outcome<KsefTokens> =
        challenge()
            .flatMap { challenge ->
                publicKey(USAGE_TOKEN).flatMap { key ->
                    val encrypted = encryptOaep(key.key, "$ksefToken|${challenge.timestampMs}".toByteArray())
                    post(
                        "/auth/ksef-token",
                        JSON.encodeToString(
                            TokenAuthRequest.serializer(),
                            TokenAuthRequest(
                                challenge.challenge,
                                ContextDto("Nip", nip.value),
                                Base64.getEncoder().encodeToString(encrypted),
                                key.id,
                            ),
                        ),
                    )
                }
            }.flatMap { complete(it, sleep) }

    /** Signed `AuthTokenRequest` XML (XAdES); used to bootstrap the first KSeF token, e.g. in the TEST environment. */
    suspend fun loginWithSignedXml(
        signedXml: String,
        sleep: suspend () -> Unit = { delay(POLL_MILLIS) },
    ): Outcome<KsefTokens> =
        http
            .send {
                method = HttpMethod.Post
                url("${http.baseUrl}/auth/xades-signature")
                contentType(ContentType.Application.Xml)
                setBody(signedXml)
            }.flatMap { decode<InitResponse>(it) }
            .map { AuthOperation(it.referenceNumber, it.authenticationToken.token) }
            .flatMap { complete(it, sleep) }

    suspend fun challenge(): Outcome<ChallengeResponse> =
        http
            .send {
                method = HttpMethod.Post
                url("${http.baseUrl}/auth/challenge")
            }.flatMap { decode<ChallengeResponse>(it) }

    /** The Ministry's key for [usage] (`KsefTokenEncryption` or `SymmetricKeyEncryption`). */
    suspend fun publicKey(usage: String): Outcome<KsefPublicKey> =
        http
            .send {
                method = HttpMethod.Get
                url("${http.baseUrl}/security/public-key-certificates")
            }.flatMap { decode<List<PublicKeyCertificateDto>>(it) }
            .flatMap { certificates ->
                val now = Instant.now()
                val certificate =
                    certificates.firstOrNull { usage in it.usage && Instant.parse(it.validTo).isAfter(now) }
                        ?: return@flatMap Outcome.Err(SellerFlowError.Remote(KsefHttp.SERVICE, "KSeF published no valid key for $usage."))
                val x509 =
                    CertificateFactory
                        .getInstance("X.509")
                        .generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(certificate.certificate))) as X509Certificate
                Outcome.Ok(KsefPublicKey(certificate.publicKeyId, x509.publicKey))
            }

    /** A new access token for a refresh token that is still valid. */
    suspend fun refresh(tokens: KsefTokens): Outcome<KsefTokens> =
        http
            .send {
                method = HttpMethod.Post
                url("${http.baseUrl}/auth/token/refresh")
                bearerAuth(tokens.refreshToken)
            }.flatMap { decode<RefreshResponse>(it) }
            .map { tokens.copy(accessToken = it.accessToken.token, accessValidUntil = Instant.parse(it.accessToken.validUntil)) }

    private suspend fun post(
        path: String,
        body: String,
    ): Outcome<AuthOperation> =
        http
            .send {
                method = HttpMethod.Post
                url("${http.baseUrl}$path")
                contentType(ContentType.Application.Json)
                setBody(body)
            }.flatMap { decode<InitResponse>(it) }
            .map { AuthOperation(it.referenceNumber, it.authenticationToken.token) }

    /** Polls the operation until KSeF finishes it (code 200), then redeems it for the tokens. */
    private suspend fun complete(
        operation: AuthOperation,
        sleep: suspend () -> Unit,
    ): Outcome<KsefTokens> {
        var status = status(operation)
        var left = MAX_POLLS
        while (left > 0 && status is Outcome.Ok && status.value.code == IN_PROGRESS) {
            sleep()
            status = status(operation)
            left--
        }
        return status.flatMap { info ->
            when (info.code) {
                DONE -> redeem(operation)
                IN_PROGRESS ->
                    Outcome.Err(
                        SellerFlowError.Remote(KsefHttp.SERVICE, "KSeF is still checking the login; try again in a minute."),
                    )
                else ->
                    Outcome.Err(
                        SellerFlowError.Auth(
                            KsefHttp.SERVICE,
                            "KSeF refused the login: ${info.code} ${info.description}" +
                                info.details
                                    .orEmpty()
                                    .joinToString("; ", prefix = " ")
                                    .trimEnd(),
                        ),
                    )
            }
        }
    }

    private suspend fun status(operation: AuthOperation): Outcome<StatusInfo> =
        http
            .send {
                method = HttpMethod.Get
                url("${http.baseUrl}/auth/${operation.referenceNumber}")
                bearerAuth(operation.operationToken)
            }.flatMap { decode<StatusResponse>(it) }
            .map { it.status }

    private suspend fun redeem(operation: AuthOperation): Outcome<KsefTokens> =
        http
            .send {
                method = HttpMethod.Post
                url("${http.baseUrl}/auth/token/redeem")
                bearerAuth(operation.operationToken)
            }.flatMap { decode<TokensResponse>(it) }
            .map {
                KsefTokens(
                    it.accessToken.token,
                    Instant.parse(it.accessToken.validUntil),
                    it.refreshToken.token,
                    Instant.parse(it.refreshToken.validUntil),
                )
            }

    companion object {
        const val USAGE_TOKEN = "KsefTokenEncryption"
        const val USAGE_SYMMETRIC_KEY = "SymmetricKeyEncryption"
        private const val IN_PROGRESS = 100
        private const val DONE = 200
        private const val MAX_POLLS = 30
        private const val POLL_MILLIS = 1_000L

        /** RSA-OAEP with SHA-256 and MGF1-SHA-256, as KSeF requires for tokens and symmetric keys. */
        fun encryptOaep(
            key: PublicKey,
            plain: ByteArray,
        ): ByteArray {
            val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                key,
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
            )
            return cipher.doFinal(plain)
        }
    }
}

@Serializable
data class ChallengeResponse(
    val challenge: String,
    val timestamp: String,
    val timestampMs: Long,
)

@Serializable
private data class ContextDto(
    val type: String,
    val value: String,
)

@Serializable
private data class TokenAuthRequest(
    val challenge: String,
    val contextIdentifier: ContextDto,
    val encryptedToken: String,
    val publicKeyId: String,
)

@Serializable
internal data class TokenInfo(
    val token: String,
    val validUntil: String,
)

@Serializable
private data class InitResponse(
    val referenceNumber: String,
    val authenticationToken: TokenInfo,
)

@Serializable
internal data class StatusInfo(
    val code: Int,
    val description: String,
    val details: List<String>? = null,
)

@Serializable
private data class StatusResponse(
    val status: StatusInfo,
)

@Serializable
private data class TokensResponse(
    val accessToken: TokenInfo,
    val refreshToken: TokenInfo,
)

@Serializable
private data class RefreshResponse(
    val accessToken: TokenInfo,
)

@Serializable
private data class PublicKeyCertificateDto(
    val certificate: String,
    val publicKeyId: String,
    val usage: List<String>,
    val validTo: String,
)
