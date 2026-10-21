package io.github.anetesa.sellerflow.core.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * An amount in minor units (grosze for PLN) and an ISO 4217 currency. Minor units keep sums exact;
 * [toDecimal] gives the amount as written on invoices.
 */
data class Money(
    val minor: Long,
    val currency: String,
) {
    init {
        require(currency.length == CURRENCY_LENGTH && currency.all { it in 'A'..'Z' }) {
            "Currency must be an ISO 4217 code, got '$currency'"
        }
    }

    operator fun plus(other: Money): Money {
        require(currency == other.currency) { "Cannot add $currency and ${other.currency}" }
        return Money(minor + other.minor, currency)
    }

    operator fun times(quantity: Int): Money = Money(minor * quantity, currency)

    fun toDecimal(): BigDecimal = BigDecimal.valueOf(minor, SCALE)

    override fun toString(): String = "${toDecimal().toPlainString()} $currency"

    companion object {
        private const val SCALE = 2
        private const val CURRENCY_LENGTH = 3

        /** Parses an amount as Allegro and InPost send it, e.g. "123.45". */
        fun parse(
            amount: String,
            currency: String,
        ): Money {
            val minor = BigDecimal(amount).setScale(SCALE, RoundingMode.UNNECESSARY).unscaledValue().longValueExact()
            return Money(minor, currency)
        }

        fun zero(currency: String): Money = Money(0, currency)
    }
}
