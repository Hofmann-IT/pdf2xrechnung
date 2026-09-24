/**
 * Modul {@code ledger}: Quelldokumente, Processing-Runs, append-only Rechnungsausgangsbuch,
 * append-only Events, Artefakt-Register und Status-Projektion (Vorgabe Abschnitte 15–20).
 *
 * <p>Grundsätze:
 * <ul>
 *   <li>{@code tenant_id + sha256} eines Quelldokuments ist eindeutig</li>
 *   <li>Jeder Run ist unveränderlich; ein Reprocess erzeugt einen neuen Run mit {@code parent_run_id}</li>
 *   <li>Ledger-Einträge, Events und Artefakte werden niemals per UPDATE/DELETE verändert
 *       (durch Datenbank-Trigger erzwungen, siehe {@code db/migration/V1__base_schema.sql})</li>
 *   <li>Der angezeigte Status ist eine Projektion der Event-Historie, kein gespeichertes Feld</li>
 * </ul>
 *
 * <p><b>Abhängigkeiten:</b> {@code configuration}, {@code security}. Kennt keine Pipeline-Module.
 *
 * <p>Implementierung der Repositories: Phase 2 (siehe docs/adr/0003-persistenz-ledger-modell.md).
 */
package de.hofmannit.erechnung.ledger;
