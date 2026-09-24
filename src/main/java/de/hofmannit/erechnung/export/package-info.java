/**
 * Modul {@code export}: Export des Rechnungsausgangsbuchs (Vorgabe Abschnitt 33).
 *
 * <ul>
 *   <li>Einfache CSV mit allen Ledger-Daten (deutsches Format: Semikolon, Dezimalkomma)</li>
 *   <li>DATEV-Format „Buchungsstapel" (EXTF, Version 700, Formatversion 13) nach der
 *       Referenz in {@code docs/datev-format-referenz.md}; jedes Feld wird gegen die dort
 *       dokumentierten regulären Ausdrücke geprüft, bevor die Datei ausgegeben wird</li>
 *   <li>Export-Einstellungen und Rechnungs-Zusatzfelder aus der Datenbank (ADR 0010)</li>
 *   <li>Übergabe der ZUGFeRD-PDF an das DATEV-Belegtransfer-Verzeichnis (ADR 0011)</li>
 * </ul>
 *
 * <p>Die Exporte lesen ausschließlich; der Belegtransfer kopiert in ein lokales Verzeichnis und
 * überschreibt nie.
 *
 * <p><b>Abhängigkeiten:</b> {@code ledger}, {@code configuration}, {@code model}, {@code security}.
 */
package de.hofmannit.erechnung.export;
