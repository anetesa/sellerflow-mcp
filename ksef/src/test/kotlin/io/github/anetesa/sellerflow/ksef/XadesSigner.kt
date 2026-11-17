package io.github.anetesa.sellerflow.ksef

import eu.europa.esig.dss.enumerations.DigestAlgorithm
import eu.europa.esig.dss.enumerations.MimeTypeEnum
import eu.europa.esig.dss.enumerations.SignatureLevel
import eu.europa.esig.dss.enumerations.SignaturePackaging
import eu.europa.esig.dss.model.InMemoryDocument
import eu.europa.esig.dss.model.x509.CertificateToken
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier
import eu.europa.esig.dss.token.KSPrivateKeyEntry
import eu.europa.esig.dss.token.Pkcs12SignatureToken
import eu.europa.esig.dss.xades.XAdESSignatureParameters
import eu.europa.esig.dss.xades.signature.XAdESService
import java.io.ByteArrayOutputStream
import java.security.KeyStore

/** XAdES-BASELINE-B, enveloped, as the official MF Java client signs `AuthTokenRequest`. Tests only. */
object XadesSigner {
    fun sign(
        xml: String,
        cert: TestCertificate,
    ): String {
        val password = "test".toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        store.setKeyEntry("k", cert.keys.private, password, arrayOf(cert.certificate))
        val p12 = ByteArrayOutputStream().also { store.store(it, password) }.toByteArray()
        Pkcs12SignatureToken(p12, KeyStore.PasswordProtection(password)).use { token ->
            val key = token.keys.first() as KSPrivateKeyEntry
            val parameters =
                XAdESSignatureParameters().apply {
                    signaturePackaging = SignaturePackaging.ENVELOPED
                    signatureLevel = SignatureLevel.XAdES_BASELINE_B
                    digestAlgorithm = DigestAlgorithm.SHA256
                    setEn319132(false)
                    signingCertificate = CertificateToken(cert.certificate)
                }
            val service = XAdESService(CommonCertificateVerifier())
            val document = InMemoryDocument(xml.toByteArray(), null, MimeTypeEnum.XML)
            val toSign = service.getDataToSign(document, parameters)
            val value = token.sign(toSign, parameters.digestAlgorithm, key)
            return String(service.signDocument(document, parameters, value).openStream().readAllBytes())
        }
    }

    fun authTokenRequest(
        challenge: String,
        nip: String,
    ) = """<?xml version="1.0" encoding="utf-8"?>
<AuthTokenRequest xmlns="http://ksef.mf.gov.pl/auth/token/2.0">
  <Challenge>$challenge</Challenge>
  <ContextIdentifier>
    <Nip>$nip</Nip>
  </ContextIdentifier>
  <SubjectIdentifierType>certificateSubject</SubjectIdentifierType>
</AuthTokenRequest>"""
}
