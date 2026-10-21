package io.github.anetesa.sellerflow.core.model

/**
 * Polish tax identification number: 10 digits, the last one a checksum. Buyers type it with dashes,
 * spaces or a "PL" prefix; [parse] accepts those and keeps only the digits.
 */
@JvmInline
value class Nip private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        private val WEIGHTS = intArrayOf(6, 5, 7, 2, 3, 4, 5, 6, 7)
        private const val LENGTH = 10
        private const val MODULUS = 11

        /** The NIP, or null when it is not 10 digits with a valid checksum. */
        fun parse(raw: String): Nip? {
            val digits =
                raw
                    .trim()
                    .removePrefix("PL")
                    .removePrefix("pl")
                    .filter { it != '-' && it != ' ' }
            if (digits.length != LENGTH || !digits.all(Char::isDigit)) return null
            val sum = WEIGHTS.indices.sumOf { WEIGHTS[it] * digits[it].digitToInt() }
            val check = sum % MODULUS
            return if (check != MODULUS - 1 && check == digits.last().digitToInt()) Nip(digits) else null
        }
    }
}
