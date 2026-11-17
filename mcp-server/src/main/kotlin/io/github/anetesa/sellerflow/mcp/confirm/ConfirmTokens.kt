package io.github.anetesa.sellerflow.mcp.confirm

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Tokens that tie a real action to the preview the user saw. A token is issued with the preview, names the action
 * and a digest of exactly what it will do, expires after [ttl] and works once. The key lives only in this process,
 * so a restart invalidates every outstanding token.
 */
class ConfirmTokens(
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofMinutes(DEFAULT_MINUTES),
) {
    private val key = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
    private val used = mutableSetOf<String>()

    fun issue(
        action: String,
        what: String,
    ): String {
        val expires = clock.instant().plus(ttl).epochSecond
        val body = "$action|$expires|${digest(what)}"
        return encode(body.toByteArray()) + "." + encode(sign(body))
    }

    /** Ok only for an unused, unexpired token issued for this [action] and exactly this [what]. */
    @Synchronized
    fun redeem(
        token: String?,
        action: String,
        what: String,
    ): Outcome<Unit> {
        val parts = token?.split(".")
        val body = parts?.takeIf { it.size == 2 }?.let { runCatching { String(Base64.getUrlDecoder().decode(it[0])) }.getOrNull() }
        val genuine =
            body != null &&
                MessageDigest.isEqual(sign(body), runCatching { Base64.getUrlDecoder().decode(parts[1]) }.getOrDefault(ByteArray(0)))
        val fields = body?.split("|")
        return when {
            !genuine || fields == null || fields.size != FIELDS ->
                refuse(
                    "The confirm_token is not valid. Run the preview again (dry_run=true).",
                )
            fields[0] != action || fields[2] != digest(what) ->
                refuse("The confirm_token was issued for a different action or different details. Run the preview again.")
            Instant.ofEpochSecond(fields[1].toLong()).isBefore(clock.instant()) ->
                refuse("The confirm_token expired. Run the preview again and confirm within ${ttl.toMinutes()} minutes.")
            !used.add(token) -> refuse("This confirm_token was already used.")
            else -> Outcome.Ok(Unit)
        }
    }

    private fun sign(body: String): ByteArray = Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }.doFinal(body.toByteArray())

    private fun digest(what: String) = encode(MessageDigest.getInstance("SHA-256").digest(what.toByteArray()))

    private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun refuse(message: String) = Outcome.Err(SellerFlowError.Invalid(message))

    private companion object {
        const val HMAC = "HmacSHA256"
        const val KEY_BYTES = 32
        const val FIELDS = 3
        const val DEFAULT_MINUTES = 15L
    }
}
