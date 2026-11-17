package io.github.anetesa.sellerflow.ksef

import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

/** A self-signed person's certificate with the NIP in serialNumber, as KSeF TEST accepts in place of a qualified one. */
class TestCertificate(
    val certificate: X509Certificate,
    val keys: KeyPair,
)

object TestCertificates {
    fun person(nip: String): TestCertificate {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()
        val name =
            X500NameBuilder(BCStyle.INSTANCE)
                .addRDN(BCStyle.GIVENNAME, "Jan")
                .addRDN(BCStyle.SURNAME, "Testowy")
                .addRDN(BCStyle.SERIALNUMBER, "TINPL-$nip")
                .addRDN(BCStyle.CN, "Jan Testowy")
                .addRDN(BCStyle.C, "PL")
                .build()
        val now = Instant.now()
        val holder =
            JcaX509v3CertificateBuilder(
                name,
                BigInteger.valueOf(now.toEpochMilli()),
                Date.from(now.minus(1, ChronoUnit.HOURS)),
                Date.from(now.plus(1, ChronoUnit.DAYS)),
                name,
                keys.public,
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private))
        return TestCertificate(JcaX509CertificateConverter().getCertificate(holder), keys)
    }

    private const val KEY_BITS = 2048
}
