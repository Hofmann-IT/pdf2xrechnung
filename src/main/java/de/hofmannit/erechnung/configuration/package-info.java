/**
 * Modul {@code configuration}: Laden und Validieren der gesamten fachlichen und technischen
 * Konfiguration.
 *
 * <ul>
 *   <li>{@code config/application.yaml}: Pfade, Ports, Intervalle, Parallelität, SMTP (ohne Secrets)</li>
 *   <li>{@code config/tenant.yaml}: Mandanten und deren Fixed Values (Firmenangaben, IBAN, USt-ID ...)</li>
 *   <li>{@code profiles/*.yaml}: Mandantenprofile (Klassifizierung, Mapping-Regeln, Plausibilität,
 *       Erzeugung, Dateinamen, Postprozess)</li>
 * </ul>
 *
 * <p>Keine fachliche Konfiguration liegt in der Datenbank. Secrets werden ausschließlich über
 * Umgebungsvariablen referenziert.
 *
 * <p><b>Abhängigkeiten:</b> Dieses Modul ist ein Basismodul und hängt von keinem anderen
 * fachlichen Modul ab (nur {@code security} für Hashing). Alle anderen Module dürfen es verwenden.
 */
package de.hofmannit.erechnung.configuration;
