package io.github.anetesa.sellerflow.allegro

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.store.InMemoryCredentialStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.HttpStatusCode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val NOW = Instant.parse("2026-11-17T12:00:00Z")
private val CLOCK = Clock.fixed(NOW, ZoneOffset.UTC)

class AllegroSessionTest :
    FunSpec({
        test("uses the saved token while it is fresh, without calling Allegro") {
            val allegro = FakeAllegro { error("no refresh expected") }
            val session = AllegroSession(AllegroAuth(allegro.http, CLOCK), InMemoryCredentialStore(), CLOCK)
            session.save(AllegroTokens("at", "rt", NOW.plusSeconds(3600)))
            session.accessToken() shouldBe Outcome.Ok("at")
            allegro.requests.shouldBeEmpty()
        }

        test("refreshes a token about to expire and keeps the rotated pair") {
            val allegro = FakeAllegro { json("""{"access_token":"at2","refresh_token":"rt2","expires_in":43199}""") }
            val store = InMemoryCredentialStore()
            val session = AllegroSession(AllegroAuth(allegro.http, CLOCK), store, CLOCK)
            session.save(AllegroTokens("at", "rt", NOW.plusSeconds(60)))
            session.accessToken() shouldBe Outcome.Ok("at2")
            AllegroSession(AllegroAuth(allegro.http, CLOCK), store, CLOCK).accessToken() shouldBe Outcome.Ok("at2")
            allegro.requests.size shouldBe 1
        }

        test("a refused refresh forgets the login and asks to log in again") {
            val allegro = FakeAllegro { json("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest) }
            val session = AllegroSession(AllegroAuth(allegro.http, CLOCK), InMemoryCredentialStore(), CLOCK)
            session.save(AllegroTokens("at", "rt", NOW.minusSeconds(1)))
            session.accessToken().shouldBeInstanceOf<Outcome.Err>()
            session.isLoggedIn shouldBe false
        }

        test("without a login it says how to log in") {
            val session = AllegroSession(AllegroAuth(FakeAllegro { error("no") }.http, CLOCK), InMemoryCredentialStore(), CLOCK)
            session
                .accessToken()
                .shouldBeInstanceOf<Outcome.Err>()
                .error.message shouldContain "allegro_login"
        }
    })
