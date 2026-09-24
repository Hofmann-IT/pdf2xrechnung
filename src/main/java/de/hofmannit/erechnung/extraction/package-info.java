/**
 * Modul {@code extraction}: positionsbasierte Textextraktion aus PDF-Dateien mit Apache PDFBox 3.x.
 *
 * <p>Liefert kein reines Fließtext-Ergebnis, sondern Textfragmente mit Seite und Bounding Box
 * (siehe {@link de.hofmannit.erechnung.extraction.PositionedText}), damit Anchor-, Region- und
 * Tabellen-Mapping möglich sind und jede Fundstelle nachvollziehbar bleibt.
 *
 * <p><b>Abhängigkeiten:</b> nur {@code configuration} und {@code security}.
 * Kennt keine Business Terms und keine Mapping-Regeln.
 *
 * <p>Implementierung: Phase 2 (siehe docs/adr/0001-extraktionsstrategie.md).
 */
package de.hofmannit.erechnung.extraction;
