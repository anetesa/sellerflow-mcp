package io.github.anetesa.sellerflow.core.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class NipTest :
    FunSpec({
        test("accepts a valid NIP written the way buyers type it") {
            Nip.parse("PL 526-104-08-28")?.value shouldBe "5261040828"
            Nip.parse("5261040828")?.value shouldBe "5261040828"
        }

        test("rejects a wrong checksum, wrong length and letters") {
            Nip.parse("5261040829").shouldBeNull()
            Nip.parse("526104082").shouldBeNull()
            Nip.parse("52610408AB").shouldBeNull()
        }
    })
