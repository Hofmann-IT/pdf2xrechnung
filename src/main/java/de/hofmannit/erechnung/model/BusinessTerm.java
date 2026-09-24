package de.hofmannit.erechnung.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Business Terms des semantischen Datenmodells EN 16931-1, soweit sie in diesem Projekt
 * verwendet werden.
 *
 * <p>Die Nummern und Bezeichnungen wurden am 2026-09-24 gegen die Peppol-BIS-Billing-3.0-
 * Syntaxdokumentation (Element-zu-BT-Zuordnung der UBL-Invoice-Syntax) verifiziert.
 * Es wurden ausschließlich dort bestätigte Nummern aufgenommen.
 * Positionsbezogene Terms (BG-25 "Invoice line") gelten je Rechnungsposition.
 */
public enum BusinessTerm {

    // ---- Rechnungskopf (BG-1 .. BG-3) ----
    BT_1("BT-1", "Invoice number", "Rechnungsnummer"),
    BT_2("BT-2", "Invoice issue date", "Rechnungsdatum"),
    BT_3("BT-3", "Invoice type code", "Rechnungstyp-Code (UNTDID 1001, z. B. 380 Rechnung, 381 Gutschrift)"),
    BT_5("BT-5", "Invoice currency code", "Rechnungswährung (ISO 4217)"),
    BT_6("BT-6", "VAT accounting currency code", "Währung der Umsatzsteuerbuchung"),
    BT_7("BT-7", "Value added tax point date", "Datum der Steuerfälligkeit"),
    BT_9("BT-9", "Payment due date", "Fälligkeitsdatum"),
    BT_10("BT-10", "Buyer reference", "Käuferreferenz (XRechnung: Leitweg-ID)"),
    BT_11("BT-11", "Project reference", "Projektreferenz"),
    BT_12("BT-12", "Contract reference", "Vertragsreferenz"),
    BT_13("BT-13", "Purchase order reference", "Bestellreferenz"),
    BT_20("BT-20", "Payment terms", "Zahlungsbedingungen"),
    BT_22("BT-22", "Invoice note", "Bemerkung zur Rechnung"),
    BT_24("BT-24", "Specification identifier", "Spezifikationskennung (CustomizationID)"),
    BT_25("BT-25", "Preceding Invoice reference", "Referenz auf vorausgegangene Rechnung"),

    // ---- Verkäufer (BG-4, BG-5, BG-6) ----
    BT_27("BT-27", "Seller name", "Name des Verkäufers"),
    BT_28("BT-28", "Seller trading name", "Handelsname des Verkäufers"),
    BT_29("BT-29", "Seller identifier", "Kennung des Verkäufers"),
    BT_30("BT-30", "Seller legal registration identifier", "Registernummer des Verkäufers"),
    BT_31("BT-31", "Seller VAT identifier", "USt-IdNr. des Verkäufers"),
    BT_32("BT-32", "Seller tax registration identifier", "Steuernummer des Verkäufers"),
    BT_33("BT-33", "Seller additional legal information", "Weitere rechtliche Informationen des Verkäufers"),
    BT_34("BT-34", "Seller electronic address", "Elektronische Adresse des Verkäufers"),
    BT_35("BT-35", "Seller address line 1", "Straße/Hausnummer des Verkäufers"),
    BT_36("BT-36", "Seller address line 2", "Adresszusatz des Verkäufers"),
    BT_37("BT-37", "Seller city", "Ort des Verkäufers"),
    BT_38("BT-38", "Seller post code", "Postleitzahl des Verkäufers"),
    BT_40("BT-40", "Seller country code", "Ländercode des Verkäufers (ISO 3166-1)"),
    BT_41("BT-41", "Seller contact point", "Kontaktstelle des Verkäufers"),
    BT_42("BT-42", "Seller contact telephone number", "Telefon des Verkäufers"),
    BT_43("BT-43", "Seller contact email address", "E-Mail des Verkäufers"),

    // ---- Käufer (BG-7, BG-8, BG-9) ----
    BT_44("BT-44", "Buyer name", "Name des Käufers"),
    BT_45("BT-45", "Buyer trading name", "Handelsname des Käufers"),
    BT_46("BT-46", "Buyer identifier", "Kennung des Käufers"),
    BT_47("BT-47", "Buyer legal registration identifier", "Registernummer des Käufers"),
    BT_48("BT-48", "Buyer VAT identifier", "USt-IdNr. des Käufers"),
    BT_49("BT-49", "Buyer electronic address", "Elektronische Adresse des Käufers"),
    BT_50("BT-50", "Buyer address line 1", "Straße/Hausnummer des Käufers"),
    BT_51("BT-51", "Buyer address line 2", "Adresszusatz des Käufers"),
    BT_52("BT-52", "Buyer city", "Ort des Käufers"),
    BT_53("BT-53", "Buyer post code", "Postleitzahl des Käufers"),
    BT_55("BT-55", "Buyer country code", "Ländercode des Käufers (ISO 3166-1)"),
    BT_56("BT-56", "Buyer contact point", "Kontaktstelle des Käufers"),
    BT_57("BT-57", "Buyer contact telephone number", "Telefon des Käufers"),
    BT_58("BT-58", "Buyer contact email address", "E-Mail des Käufers"),

    // ---- Lieferung (BG-13, BG-14) ----
    BT_72("BT-72", "Actual delivery date", "Tatsächliches Lieferdatum"),
    BT_73("BT-73", "Invoicing period start date", "Beginn des Abrechnungszeitraums"),
    BT_74("BT-74", "Invoicing period end date", "Ende des Abrechnungszeitraums"),

    // ---- Zahlungsinformationen (BG-16, BG-17) ----
    BT_81("BT-81", "Payment means type code", "Zahlungsart-Code (UNTDID 4461)"),
    BT_82("BT-82", "Payment means text", "Zahlungsart (Text)"),
    BT_83("BT-83", "Remittance information", "Verwendungszweck"),
    BT_84("BT-84", "Payment account identifier", "Kontonummer/IBAN"),
    BT_85("BT-85", "Payment account name", "Kontoinhaber"),
    BT_86("BT-86", "Payment service provider identifier", "BIC"),

    // ---- Summen (BG-22) ----
    BT_106("BT-106", "Sum of Invoice line net amount", "Summe der Positionsnettobeträge"),
    BT_107("BT-107", "Sum of allowances on document level", "Summe der Nachlässe auf Dokumentebene"),
    BT_108("BT-108", "Sum of charges on document level", "Summe der Zuschläge auf Dokumentebene"),
    BT_109("BT-109", "Invoice total amount without VAT", "Gesamtbetrag ohne Umsatzsteuer"),
    BT_110("BT-110", "Invoice total VAT amount", "Gesamtbetrag der Umsatzsteuer"),
    BT_111("BT-111", "Invoice total VAT amount in accounting currency", "Umsatzsteuer in Buchungswährung"),
    BT_112("BT-112", "Invoice total amount with VAT", "Gesamtbetrag mit Umsatzsteuer (Brutto)"),
    BT_113("BT-113", "Paid amount", "Bereits gezahlter Betrag"),
    BT_114("BT-114", "Rounding amount", "Rundungsbetrag"),
    BT_115("BT-115", "Amount due for payment", "Fälliger Zahlbetrag"),

    // ---- Umsatzsteueraufschlüsselung (BG-23), je Steuersatz ----
    BT_116("BT-116", "VAT category taxable amount", "Steuerbasis je Steuerkategorie"),
    BT_117("BT-117", "VAT category tax amount", "Steuerbetrag je Steuerkategorie"),
    BT_118("BT-118", "VAT category code", "Steuerkategorie-Code (UNTDID 5305)"),
    BT_119("BT-119", "VAT category rate", "Steuersatz in Prozent"),
    BT_120("BT-120", "VAT exemption reason text", "Grund der Steuerbefreiung (Text)"),
    BT_121("BT-121", "VAT exemption reason code", "Grund der Steuerbefreiung (Code)"),

    // ---- Rechnungsposition (BG-25 ff.), je Position ----
    BT_126("BT-126", "Invoice line identifier", "Positionsnummer"),
    BT_127("BT-127", "Invoice line note", "Bemerkung zur Position"),
    BT_128("BT-128", "Invoice line object identifier", "Objektkennung der Position"),
    BT_129("BT-129", "Invoiced quantity", "Menge"),
    BT_130("BT-130", "Invoiced quantity unit of measure code", "Mengeneinheit (UN/ECE Rec. 20)"),
    BT_131("BT-131", "Invoice line net amount", "Nettobetrag der Position"),
    BT_146("BT-146", "Item net price", "Nettopreis je Einheit"),
    BT_149("BT-149", "Item price base quantity", "Basismenge des Preises"),
    BT_151("BT-151", "Invoiced item VAT category code", "Steuerkategorie-Code der Position"),
    BT_152("BT-152", "Invoiced item VAT rate", "Steuersatz der Position"),
    BT_153("BT-153", "Item name", "Artikelbezeichnung"),
    BT_154("BT-154", "Item description", "Artikelbeschreibung"),
    BT_155("BT-155", "Item Seller's identifier", "Artikelnummer des Verkäufers");

    private final String id;
    private final String englishName;
    private final String germanLabel;

    BusinessTerm(String id, String englishName, String germanLabel) {
        this.id = id;
        this.englishName = englishName;
        this.germanLabel = germanLabel;
    }

    /** Offizielle Kennung, z. B. {@code BT-1}. */
    public String id() {
        return id;
    }

    /** Offizielle englische Bezeichnung laut EN 16931-1. */
    public String englishName() {
        return englishName;
    }

    /** Deutsche Anzeigebezeichnung für die Oberfläche. */
    public String germanLabel() {
        return germanLabel;
    }

    /** Positionsbezogene Terms (BG-25 ff.) gelten je Rechnungsposition. */
    public boolean isLineLevel() {
        return ordinal() >= BT_126.ordinal();
    }

    /**
     * Liefert den Business Term zu einer Kennung wie {@code BT-1} (Groß-/Kleinschreibung egal,
     * {@code _} und {@code -} gleichwertig).
     */
    public static Optional<BusinessTerm> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.trim().toUpperCase(Locale.ROOT).replace('_', '-');
        for (BusinessTerm bt : values()) {
            if (bt.id.equals(normalized)) {
                return Optional.of(bt);
            }
        }
        return Optional.empty();
    }
}
