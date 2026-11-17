package io.github.anetesa.sellerflow.storage

import io.github.anetesa.sellerflow.core.Outcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.sql.DriverManager

private const val KEY = "a-long-enough-store-key"

class SqliteCredentialStoreTest :
    FunSpec({
        fun open(
            file: File,
            key: String? = KEY,
        ) = SqliteCredentialStore.open(file.toPath(), key)

        test("keeps values between openings") {
            val file = File(tempdir(), "db/sellerflow.db")
            open(file).shouldBeInstanceOf<Outcome.Ok<SqliteCredentialStore>>().value.use { it.put("allegro", "tokens-1") }
            open(file).shouldBeInstanceOf<Outcome.Ok<SqliteCredentialStore>>().value.use { store ->
                store.get("allegro") shouldBe "tokens-1"
                store.put("allegro", "tokens-2")
                store.get("allegro") shouldBe "tokens-2"
                store.remove("allegro")
                store.get("allegro") shouldBe null
            }
        }

        test("only the owner can read the database") {
            val file = File(tempdir(), "sellerflow.db")
            open(file).shouldBeInstanceOf<Outcome.Ok<SqliteCredentialStore>>().value.close()
            java.nio.file.attribute.PosixFilePermissions
                .toString(
                    java.nio.file.Files
                        .getPosixFilePermissions(file.toPath()),
                ) shouldBe "rw-------"
        }

        test("stores nothing readable on disk") {
            val file = File(tempdir(), "sellerflow.db")
            open(file).shouldBeInstanceOf<Outcome.Ok<SqliteCredentialStore>>().value.use { it.put("allegro", "very-secret-refresh-token") }
            file.readBytes().toString(Charsets.ISO_8859_1).contains("very-secret") shouldBe false
        }

        test("a wrong key is reported instead of returning garbage") {
            val file = File(tempdir(), "sellerflow.db")
            open(file).shouldBeInstanceOf<Outcome.Ok<SqliteCredentialStore>>().value.use { it.put("allegro", "x") }
            open(file, "another-long-store-key")
                .shouldBeInstanceOf<Outcome.Err>()
                .error.message shouldContain "does not open the existing database"
        }

        test("a missing or short key says how to make one") {
            open(File(tempdir(), "a.db"), null).shouldBeInstanceOf<Outcome.Err>().error.message shouldContain "openssl rand"
            open(File(tempdir(), "b.db"), "short").shouldBeInstanceOf<Outcome.Err>()
        }

        test("values cannot be moved to another name") {
            val file = File(tempdir(), "sellerflow.db")
            open(file).shouldBeInstanceOf<Outcome.Ok<SqliteCredentialStore>>().value.use { it.put("allegro", "tokens") }
            DriverManager.getConnection("jdbc:sqlite:$file").use {
                it.createStatement().executeUpdate("UPDATE credentials SET name = 'inpost' WHERE name = 'allegro'")
            }
            open(file).shouldBeInstanceOf<Outcome.Ok<SqliteCredentialStore>>().value.use { it.get("inpost") shouldBe null }
        }
    })
