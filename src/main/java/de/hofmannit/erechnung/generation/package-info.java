/**
 * Modul {@code generation}: Erzeugung von XRechnung (CII/UBL) und ZUGFeRD/Factur-X (PDF/A-3 mit
 * eingebetteter XML) mit Mustangproject (Vorgabe Abschnitt 10).
 *
 * <p>Regeln: Original-PDF bleibt unverändert, ZUGFeRD-Ausgabe ist eine neue Datei, Dateinamen
 * folgen dem Profil-Template, Kollisionen sind Fehler (nie überschreiben).
 *
 * <p><b>Abhängigkeiten:</b> {@code mapping}, {@code configuration}, {@code security}.
 *
 * <p>Implementierung: Phase 2.
 */
package de.hofmannit.erechnung.generation;
