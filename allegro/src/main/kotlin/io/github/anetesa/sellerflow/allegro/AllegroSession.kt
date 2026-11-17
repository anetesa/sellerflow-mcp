package io.github.anetesa.sellerflow.allegro

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import io.github.anetesa.sellerflow.core.store.CredentialStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The seller's Allegro login, kept in the [CredentialStore] between runs. [accessToken] refreshes the token shortly
 * before it expires and saves the rotated pair, so callers never deal with expiry themselves.
 */
class AllegroSession(
    private val auth: AllegroAuth,
    private val store: CredentialStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutex = Mutex()

    val isLoggedIn: Boolean get() = load() != null

    /** When the saved login stops working without a refresh; null when there is none. */
    val expiresAt: Instant? get() = load()?.let { Instant.parse(it.expiresAt) }

    suspend fun accessToken(): Outcome<String> =
        mutex.withLock {
            val saved = load() ?: return@withLock notLoggedIn()
            if (Instant.parse(saved.expiresAt).isAfter(clock.instant().plus(REFRESH_MARGIN))) {
                Outcome.Ok(saved.accessToken)
            } else {
                when (val refreshed = auth.refresh(saved.refreshToken)) {
                    is Outcome.Ok -> Outcome.Ok(save(refreshed.value).accessToken)
                    is Outcome.Err -> refreshed.also { store.remove(KEY) }
                }
            }
        }

    fun save(tokens: AllegroTokens): AllegroTokens {
        store.put(KEY, JSON.encodeToString(Saved.serializer(), Saved(tokens.accessToken, tokens.refreshToken, tokens.expiresAt.toString())))
        return tokens
    }

    fun logOut() = store.remove(KEY)

    private fun load(): Saved? = store.get(KEY)?.let { runCatching { JSON.decodeFromString(Saved.serializer(), it) }.getOrNull() }

    @Serializable
    private data class Saved(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: String,
    )

    private companion object {
        const val KEY = "allegro.tokens"
        val REFRESH_MARGIN: Duration = Duration.ofMinutes(5)

        fun notLoggedIn() =
            Outcome.Err(SellerFlowError.Auth(AllegroHttp.SERVICE, "Not logged in to Allegro. Call allegro_login and approve the link."))
    }
}
