/**
 * Modul {@code export}: Export des Rechnungsausgangsbuchs (Vorgabe Abschnitt 33).
 *
 * <ul>
 *   <li>Einfache CSV mit allen Ledger-Daten (deutsches Format: Semikolon, Dezimalkomma)</li>
 *   <li>DATEV-Format „Buchungsstapel" (EXTF, Version 700, Formatversion 13) nach der
 *       Referenz in {@code docs/datev-format-referenz.md}; jedes Feld wird gegen die dort
 *       dokumentierten regulären Ausdrücke geprüft, bevor die Datei ausgegeben wird</li>
 * </ul>
 *
 * <p>Der Export liest ausschließlich; nichts wird verändert oder abgelegt.
 *
 * <p><b>Abhängigkeiten:</b> {@code ledger}, {@code configuration}, {@code model}, {@code security}.
 */
package de.hofmannit.erechnung.export;
