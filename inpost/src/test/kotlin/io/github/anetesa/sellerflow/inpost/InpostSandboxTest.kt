package io.github.anetesa.sellerflow.inpost

import io.github.anetesa.sellerflow.core.Outcome
import io.github.anetesa.sellerflow.core.config.Environment
import io.github.anetesa.sellerflow.core.model.OrderId
import io.github.anetesa.sellerflow.core.model.ParcelSize
import io.github.anetesa.sellerflow.core.model.Shipment
import io.github.anetesa.sellerflow.core.model.ShipmentStatus
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

/**
 * Talks to the real InPost sandbox: run with `./gradlew :inpost:test -Pintegration` and
 * SELLERFLOW_INPOST_ORGANIZATION_ID / SELLERFLOW_INPOST_API_TOKEN set. Creates one sandbox shipment per run.
 */
class InpostSandboxTest :
    FunSpec({
        tags(NamedTag("Integration"))

        val shipments =
            InpostShipments(
                (
                    InpostHttp.create(
                        HttpClient(CIO),
                        Environment.SANDBOX.endpoints.inpostApi,
                        System.getenv("SELLERFLOW_INPOST_ORGANIZATION_ID"),
                        System.getenv("SELLERFLOW_INPOST_API_TOKEN"),
                    ) as Outcome.Ok
                ).value,
            )

        test("finds a sandbox locker and rejects an unknown one") {
            shipments
                .locker("KRA010")
                .shouldBeInstanceOf<Outcome.Ok<ParcelLocker>>()
                .value.operating shouldBe true
            shipments.locker("NOSUCH99").shouldBeInstanceOf<Outcome.Err>()
        }

        test("creates a locker shipment; it is confirmed, or the reason is explained") {
            val created =
                shipments
                    .create(
                        LockerShipmentRequest(
                            OrderId("SF-IT-${System.currentTimeMillis()}"),
                            "Jan",
                            "Testowy",
                            "jan.testowy@example.com",
                            "500600700",
                            "KRA010",
                            ParcelSize.A,
                        ),
                    ).shouldBeInstanceOf<Outcome.Ok<Shipment>>()
                    .value
            created.status shouldBe ShipmentStatus.CREATED
            when (val result = shipments.awaitConfirmed(created.id)) {
                is Outcome.Ok -> {
                    result.value.trackingNumber shouldBe result.value.trackingNumber!!
                    String(
                        shipments
                            .label(created.id)
                            .shouldBeInstanceOf<Outcome.Ok<ByteArray>>()
                            .value
                            .copyOf(4),
                    ) shouldBe "%PDF"
                }
                // A prepaid sandbox account with no funds cannot buy the shipment: the message must say so
                is Outcome.Err -> result.error.message shouldContain "no funds"
            }
        }
    })
