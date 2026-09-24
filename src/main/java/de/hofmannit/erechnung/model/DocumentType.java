package de.hofmannit.erechnung.model;

/** Fachlicher Dokumenttyp einer erkannten Rechnung (Vorgabe Abschnitt 6). */
public enum DocumentType {
    /** Rechnung, BT-3 = 380. */
    INVOICE("380"),
    /** Gutschrift, BT-3 = 381. */
    CREDIT_NOTE("381");

    private final String defaultTypeCode;

    DocumentType(String defaultTypeCode) {
        this.defaultTypeCode = defaultTypeCode;
    }

    /** Standardwert für BT-3 (UNTDID 1001), sofern das Profil nichts anderes vorgibt. */
    public String defaultTypeCode() {
        return defaultTypeCode;
    }
}
