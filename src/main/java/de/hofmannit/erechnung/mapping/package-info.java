/**
 * Modul {@code mapping}: Abbildung extrahierter PDF-Inhalte auf EN-16931-Business-Terms
 * anhand des Mandantenprofils.
 *
 * <p>Unterstützte Regelarten (siehe {@code profiles/standard.yaml}):
 * <ul>
 *   <li>Anchor Mapping (Label + relative Position)</li>
 *   <li>Regex Mapping</li>
 *   <li>Region Mapping (Bereich auf der Seite)</li>
 *   <li>Tabellen-Mapping (Positionen, mehrseitig, Fortsetzungsseiten)</li>
 *   <li>Fixed Values (aus {@code config/tenant.yaml})</li>
 * </ul>
 *
 * <p>Jedes gemappte Feld wird als {@link de.hofmannit.erechnung.mapping.FieldEvidence}
 * mit Regel, Seite, Bounding Box, Quelltext, Transformation und Status festgehalten.
 *
 * <p><b>Abhängigkeiten:</b> {@code extraction}, {@code configuration}, {@code model}, {@code security}.
 *
 * <p>Implementierung: Phase 2.
 */
package de.hofmannit.erechnung.mapping;
