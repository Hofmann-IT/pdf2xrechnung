package de.hofmannit.erechnung.model;

/**
 * Konfigurierbare Ausgabeformate je Mandantenprofil (Vorgabe Abschnitte 1 und 10).
 * Die Namen entsprechen der Schreibweise in {@code profiles/*.yaml}.
 */
public enum OutputFormat {
    /** XRechnung, Syntax UN/CEFACT CII. */
    XRECHNUNG_CII,
    /** XRechnung, Syntax OASIS UBL. */
    XRECHNUNG_UBL,
    /** ZUGFeRD/Factur-X PDF/A-3, Profil EN 16931. */
    ZUGFERD_EN16931,
    /** ZUGFeRD/Factur-X PDF/A-3, Profil XRECHNUNG. */
    ZUGFERD_XRECHNUNG
}
