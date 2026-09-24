/**
 * Modul {@code inboundvalidation}: gezielte Validierung empfangener E-Rechnungen
 * ("E-Rechnung prüfen", Vorgabe Abschnitte 1B, 13 und 29).
 *
 * <p>Vollständig getrennt von der Ausgangsrechnungs-Pipeline: keine Weiterverarbeitung,
 * keine Konvertierung, keine Änderung, kein Versand, kein Ledger-Eintrag der Produktionspipeline.
 * Das Original wird niemals verändert. Standardmäßig temporäre Prüfung; optionale Ablage des
 * Prüfprotokolls unter {@code inbound-validation/{jahr}/{monat}/{sha256-prefix}/}.
 *
 * <p><b>Abhängigkeiten:</b> {@code validation}, {@code configuration}, {@code security}.
 * Niemals {@code watcher}, {@code ledger}, {@code archive}, {@code dispatch}, {@code generation}.
 *
 * <p>Implementierung: Phase 3 (siehe docs/adr/0005-inbound-validierung.md).
 */
package de.hofmannit.erechnung.inboundvalidation;
