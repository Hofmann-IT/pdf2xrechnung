/**
 * Modul {@code validation}: formatabhängige Validierung erzeugter oder empfangener E-Rechnungen
 * (Vorgabe Abschnitte 11 und 12).
 *
 * <ul>
 *   <li>KoSIT-Validator (lokal eingebundene Java-Bibliothek, lokale XRechnung-Konfiguration)</li>
 *   <li>Mustang-Validierung, soweit für das Format anwendbar</li>
 * </ul>
 *
 * <p>Beide Ergebnisse werden getrennt protokolliert. Eine für das Dokument nicht anwendbare
 * Validator-Kombination ist kein Validierungsfehler, sondern {@code NOT_APPLICABLE}.
 *
 * <p><b>Abhängigkeiten:</b> {@code configuration}, {@code security}. Kennt weder Pipeline noch
 * Ledger; wird von {@code watcher} (Ausgang) und {@code inboundvalidation} (Eingang) verwendet.
 *
 * <p>Implementierung: Phase 2/3 (siehe docs/adr/0002-validator-einbindung.md).
 */
package de.hofmannit.erechnung.validation;
