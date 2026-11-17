package io.github.anetesa.sellerflow.invoice

import org.w3c.dom.ls.DOMImplementationLS
import org.w3c.dom.ls.LSInput
import org.w3c.dom.ls.LSResourceResolver
import org.xml.sax.SAXParseException
import java.io.InputStream
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.Schema
import javax.xml.validation.SchemaFactory

/**
 * The Ministry's FA(3) schema, loaded from the copies in this module: imports that point at crd.gov.pl are served
 * locally, so validation never needs the network.
 */
object Fa3Schema {
    private const val DIR = "/io/github/anetesa/sellerflow/invoice/xsd/"

    private val schema: Schema by lazy {
        val factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
        // External fetches stay off: every import is served by LocalResolver
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        factory.resourceResolver = LocalResolver
        factory.newSchema(StreamSource(StringReader(relaxedCounts(text(MAIN))), MAIN))
    }

    private const val MAIN = "schemat_FA3_v1-0E.xsd"

    /** The largest maxOccurs in FA(3) (lines of an invoice, FaWiersz), checked by [Fa3InvoiceBuilder] itself. */
    const val MAX_LINES = 10_000

    private val LARGE_MAX_OCCURS = Regex("""maxOccurs="(\d{4,})"""")

    /**
     * FA(3) allows up to 10000 occurrences of some elements. The JDK's validator expands such counts into a state
     * machine that exceeds both its secure-processing limit (5000) and the heap; written as "unbounded" the structure
     * is checked the same way at constant cost. SellerFlow writes one of each such element except invoice lines,
     * whose count the builder checks against [MAX_LINES].
     */
    private fun relaxedCounts(xsd: String): String = LARGE_MAX_OCCURS.replace(xsd, """maxOccurs="unbounded"""")

    private fun text(name: String): String = resource(name).use { String(it.readAllBytes(), Charsets.UTF_8) }

    /** Null when [xml] is a valid FA(3) invoice, otherwise the first problem with its line. */
    fun problem(xml: String): String? =
        try {
            val validator = schema.newValidator()
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            validator.validate(StreamSource(StringReader(xml)))
            null
        } catch (e: SAXParseException) {
            "line ${e.lineNumber}: ${e.message}"
        }

    private fun resource(name: String): InputStream =
        requireNotNull(Fa3Schema::class.java.getResourceAsStream(DIR + name)) { "Missing bundled schema $name" }

    private object LocalResolver : LSResourceResolver {
        private val ls by lazy {
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .domImplementation
                .getFeature("LS", "3.0") as DOMImplementationLS
        }

        override fun resolveResource(
            type: String?,
            namespaceURI: String?,
            publicId: String?,
            systemId: String?,
            baseURI: String?,
        ): LSInput? =
            systemId?.substringAfterLast('/')?.let { name ->
                ls.createLSInput().apply {
                    byteStream = resource(name)
                    this.systemId = name
                }
            }
    }
}
