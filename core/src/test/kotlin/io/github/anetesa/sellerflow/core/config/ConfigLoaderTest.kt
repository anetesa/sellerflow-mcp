package io.github.anetesa.sellerflow.core.config

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.SellerFlowError
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

class ConfigLoaderTest :
    FunSpec({
        fun loader(
            dir: File,
            vararg env: Pair<String, String>,
        ) = ConfigLoader(env = env.toMap()::get, workingDir = dir.toPath(), home = dir.toPath())

        test("defaults to the sandbox with no file and no variables") {
            val config = loader(tempdir()).load().shouldBeInstanceOf<Outcome.Ok<SellerFlowConfig>>().value
            config.environment shouldBe Environment.SANDBOX
            config.endpoints.allegroApi shouldBe "https://api.allegro.pl.allegrosandbox.pl"
        }

        test("reads the file, environment variables win") {
            val dir = tempdir()
            File(dir, "sellerflow.toml").writeText(
                """
                data_dir = "~/sf"

                [allegro]
                client_id = "from-file"
                user_agent = "SellerFlow/1.0 (+https://example.com)"

                [inpost]
                organization_id = "123"
                """.trimIndent(),
            )
            val config =
                loader(dir, ConfigLoader.ALLEGRO_CLIENT_ID to "from-env")
                    .load()
                    .shouldBeInstanceOf<Outcome.Ok<SellerFlowConfig>>()
                    .value
            config.allegro.clientId shouldBe "from-env"
            config.allegro.userAgent shouldBe "SellerFlow/1.0 (+https://example.com)"
            config.inpost.organizationId shouldBe "123"
            config.dataDir shouldBe dir.toPath().resolve("sf")
        }

        test("production only through SELLERFLOW_ENV") {
            val config = loader(tempdir(), ConfigLoader.ENV to "prod").load().shouldBeInstanceOf<Outcome.Ok<SellerFlowConfig>>().value
            config.endpoints.inpostApi shouldBe "https://api-shipx-pl.easypack24.net"
            loader(tempdir(), ConfigLoader.ENV to "production").load().shouldBeInstanceOf<Outcome.Err>()
        }

        test("reads the seller and refuses an invalid NIP") {
            val dir = tempdir()
            File(dir, "sellerflow.toml").writeText(
                """
                [seller]
                name = "Sklep Testowy"
                nip = "525-267-47-98"
                street = "ul. Babiego Lata 19A"
                post_code = "53-024"
                city = "Wrocław"
                """.trimIndent(),
            )
            val seller =
                loader(dir)
                    .load()
                    .shouldBeInstanceOf<Outcome.Ok<SellerFlowConfig>>()
                    .value.seller!!
            seller.profile.nip.value shouldBe "5252674798"
            seller.vatRate shouldBe 23
            seller.invoicePrefix shouldBe "FV"
            File(
                dir,
                "sellerflow.toml",
            ).writeText("[seller]\nname = \"X\"\nnip = \"1234567890\"\nstreet = \"a\"\npost_code = \"b\"\ncity = \"c\"\n")
            loader(dir)
                .load()
                .shouldBeInstanceOf<Outcome.Err>()
                .error.message shouldContain "not a valid NIP"
        }

        test("refuses secrets in the file and says where they belong") {
            val dir = tempdir()
            File(dir, "sellerflow.toml").writeText("[allegro]\nclient_secret = \"x\"\n")
            val error = loader(dir).load().shouldBeInstanceOf<Outcome.Err>().error
            error.shouldBeInstanceOf<SellerFlowError.Config>()
            error.message shouldContain Secrets.ALLEGRO_CLIENT_SECRET
        }

        test("reports unknown keys instead of ignoring a typo") {
            val dir = tempdir()
            File(dir, "sellerflow.toml").writeText("dta_dir = \"/tmp\"\n")
            loader(dir)
                .load()
                .shouldBeInstanceOf<Outcome.Err>()
                .error.message shouldContain "sellerflow.toml"
        }
    })
