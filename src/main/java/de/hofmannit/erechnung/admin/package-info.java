/**
 * Modul {@code admin}: Verwaltungsbereich (ADR 0012).
 *
 * <p>Lädt beim Start den jüngsten in {@code app_settings} gespeicherten Stand der
 * Laufzeiteinstellungen, prüft und speichert Änderungen aus der Oberfläche und übergibt sie an
 * {@code RuntimeSettings}, damit Verzeichnisse, Watcher, Parallelität und SMTP-Server ohne
 * Neustart wirksam werden. Zugangsdaten werden hier nie gespeichert oder angezeigt.
 *
 * <p><b>Abhängigkeiten:</b> {@code ledger}, {@code configuration}, {@code model}, {@code security}.
 */
package de.hofmannit.erechnung.admin;
