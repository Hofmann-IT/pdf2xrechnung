/**
 * Modul {@code dispatch}: optionaler Postprozess je Profil (Vorgabe Abschnitte 22 und 23).
 *
 * <ul>
 *   <li>SMTP-Versand (einzige erlaubte ausgehende Netzwerkverbindung); Credentials nur über
 *       Umgebungsvariablen</li>
 *   <li>Sichere lokale Prozessausführung über {@code ProcessBuilder}, Executable und Argumente
 *       getrennt, keine Shell-Interpolation, Timeout, Exit-Code und stdout/stderr protokolliert</li>
 * </ul>
 *
 * <p>Versand erfolgt niemals bei Validierungsfehler, REVIEW oder FAILED. Ein erneuter Versand ist
 * eine eigene Benutzeraktion und erzeugt neue Events.
 *
 * <p><b>Abhängigkeiten:</b> {@code ledger}, {@code configuration}, {@code security}.
 *
 * <p>Implementierung: Phase 4.
 */
package de.hofmannit.erechnung.dispatch;
