package io.github.anetesa.sellerflow.core.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MoneyTest :
    FunSpec({
        test("parses decimal amounts into minor units") {
            Money.parse("123.45", "PLN") shouldBe Money(12345, "PLN")
            Money.parse("7", "PLN").toString() shouldBe "7.00 PLN"
        }

        test("adds and multiplies exactly") {
            (Money.parse("0.10", "PLN") * 3 + Money.parse("0.20", "PLN")) shouldBe Money(50, "PLN")
        }

        test("refuses to mix currencies or drop fractions of a grosz") {
            shouldThrow<IllegalArgumentException> { Money(1, "PLN") + Money(1, "EUR") }
            shouldThrow<ArithmeticException> { Money.parse("1.005", "PLN") }
        }
    })
