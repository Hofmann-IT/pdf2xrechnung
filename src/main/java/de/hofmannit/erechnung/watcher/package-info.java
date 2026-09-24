/**
 * Modul {@code watcher}: Überwachung des Verzeichnisses {@code inbox/} und Orchestrierung
 * der Ausgangsrechnungs-Pipeline.
 *
 * <p>Verantwortlich für:
 * <ul>
 *   <li>Erkennen vollständig geschriebener PDF-Dateien (Größe über zwei Prüfintervalle stabil,
 *       Datei vollständig lesbar), anschließend Move nach {@code processing/}</li>
 *   <li>Idempotenz: {@code tenant_id + SHA-256} darf durch den Watcher nie zweimal
 *       verarbeitet werden; Duplikate werden protokolliert</li>
 *   <li>Absturzsicherheit: Dateien in {@code processing/} werden nach Neustart erneut geprüft</li>
 *   <li>Sequentielle Verarbeitung je Mandant, konfigurierbare Parallelität zwischen Mandanten</li>
 * </ul>
 *
 * <p><b>Abhängigkeiten:</b> Orchestrierungsmodul der Ausgangs-Pipeline. Darf
 * {@code extraction}, {@code mapping}, {@code plausibility}, {@code generation},
 * {@code validation}, {@code ledger}, {@code archive}, {@code dispatch},
 * {@code configuration}, {@code security} verwenden. Niemals {@code inboundvalidation},
 * {@code web} oder {@code cli}.
 *
 * <p>Implementierung: Phase 2.
 */
package de.hofmannit.erechnung.watcher;
