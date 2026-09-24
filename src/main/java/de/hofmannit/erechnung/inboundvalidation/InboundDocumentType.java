package de.hofmannit.erechnung.inboundvalidation;

/**
 * Erkannter Dokumenttyp einer hochgeladenen E-Rechnung.
 */
public enum InboundDocumentType {
    /** XML mit CII-Wurzelelement (CrossIndustryInvoice). */
    CII_XML,
    /** XML mit UBL-Wurzelelement (Invoice oder CreditNote). */
    UBL_XML,
    /** PDF mit eingebetteter E-Rechnungs-XML (ZUGFeRD/Factur-X). */
    ZUGFERD_PDF,
    /** PDF ohne eingebettete E-Rechnungs-XML. */
    PDF_WITHOUT_XML,
    /** XML, das nicht als EN-16931-Syntax erkannt werden konnte. */
    UNKNOWN_XML,
    /** XML ist nicht wohlgeformt. */
    MALFORMED_XML,
    /** Weder XML noch PDF. */
    UNSUPPORTED
}
