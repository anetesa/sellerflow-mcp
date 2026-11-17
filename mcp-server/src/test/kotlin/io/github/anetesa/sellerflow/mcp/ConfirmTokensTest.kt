package io.github.anetesa.sellerflow.mcp

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.mcp.confirm.ConfirmTokens
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class ConfirmTokensTest :
    FunSpec({
        test("works once, for the same action and details") {
            val tokens = ConfirmTokens()
            val token = tokens.issue("ship", "order-1|B|KRA010")
            tokens.redeem(token, "ship", "order-1|B|KRA010") shouldBe Outcome.Ok(Unit)
            tokens
                .redeem(token, "ship", "order-1|B|KRA010")
                .shouldBeInstanceOf<Outcome.Err>()
                .error.message shouldContain "already used"
        }

        test("refuses other details, other actions, forgeries and other processes' tokens") {
            val tokens = ConfirmTokens()
            val token = tokens.issue("ship", "order-1|B|KRA010")
            tokens
                .redeem(token, "ship", "order-1|C|KRA010")
                .shouldBeInstanceOf<Outcome.Err>()
                .error.message shouldContain "different"
            tokens.redeem(token, "invoice", "order-1|B|KRA010").shouldBeInstanceOf<Outcome.Err>()
            tokens.redeem(token.dropLast(2) + "xx", "ship", "order-1|B|KRA010").shouldBeInstanceOf<Outcome.Err>()
            tokens.redeem(null, "ship", "x").shouldBeInstanceOf<Outcome.Err>()
            ConfirmTokens().redeem(token, "ship", "order-1|B|KRA010").shouldBeInstanceOf<Outcome.Err>()
        }

        test("expires") {
            var now = Instant.parse("2026-11-17T12:00:00Z")
            val clock =
                object : Clock() {
                    override fun getZone(): ZoneId = ZoneOffset.UTC

                    override fun withZone(zone: ZoneId?): Clock = this

                    override fun instant(): Instant = now
                }
            val tokens = ConfirmTokens(clock)
            val token = tokens.issue("ship", "x")
            now = now.plusSeconds(16 * 60)
            tokens
                .redeem(token, "ship", "x")
                .shouldBeInstanceOf<Outcome.Err>()
                .error.message shouldContain "expired"
        }
    })
