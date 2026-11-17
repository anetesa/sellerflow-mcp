package io.github.anetesa.sellerflow.ksef

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import io.github.anetesa.sellerflow.core.map
import io.github.anetesa.sellerflow.core.model.Nip
import io.github.anetesa.sellerflow.core.store.CredentialStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The seller's KSeF login. The access token (minutes) is refreshed with the refresh token (days) and both are kept
 * in the [CredentialStore]; only when the refresh token has run out does SellerFlow log in again with the KSeF token.
 */
class KsefSession(
    private val auth: KsefAuth,
    private val store: CredentialStore,
    private val nip: Nip,
    private val ksefToken: String?,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutex = Mutex()

    suspend fun accessToken(): Outcome<String> =
        mutex.withLock {
            val saved = load()
            val now = clock.instant()
            val tokens: Outcome<KsefTokens> =
                when {
                    saved != null && saved.accessValidUntil.isAfter(now.plus(MARGIN)) -> Outcome.Ok(saved)
                    saved != null && saved.refreshValidUntil.isAfter(now.plus(MARGIN)) -> auth.refresh(saved)
                    ksefToken == null ->
                        Outcome.Err(
                            SellerFlowError.Config(
                                "SELLERFLOW_KSEF_TOKEN is not set. Generate a KSeF token with the InvoiceWrite permission in the " +
                                    "Ministry's taxpayer application and put it in that variable.",
                            ),
                        )
                    else -> auth.loginWithToken(nip, ksefToken)
                }
            tokens.map { save(it).accessToken }
        }

    private fun save(tokens: KsefTokens): KsefTokens {
        val saved = Saved(tokens.accessToken, tokens.accessValidUntil.toString(), tokens.refreshToken, tokens.refreshValidUntil.toString())
        store.put(key(), JSON.encodeToString(Saved.serializer(), saved))
        return tokens
    }

    private fun load(): KsefTokens? =
        store.get(key())?.let { text ->
            runCatching { JSON.decodeFromString(Saved.serializer(), text) }.getOrNull()?.let {
                KsefTokens(it.accessToken, Instant.parse(it.accessValidUntil), it.refreshToken, Instant.parse(it.refreshValidUntil))
            }
        }

    /** Per NIP, so switching the seller in the config never reuses another company's login. */
    private fun key() = "ksef.tokens.${nip.value}"

    @Serializable
    private data class Saved(
        val accessToken: String,
        val accessValidUntil: String,
        val refreshToken: String,
        val refreshValidUntil: String,
    )

    private companion object {
        val MARGIN: Duration = Duration.ofMinutes(1)
    }
}
