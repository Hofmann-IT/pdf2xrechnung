/**
 * Modul {@code model}: gemeinsames fachliches Vokabular ohne Logik und ohne Abhängigkeiten
 * (Business Terms, Regelarten, Ausgabeformate, Artefakttypen).
 *
 * <p>Diese Typen werden sowohl von der Konfiguration (Profilmodell) als auch von den
 * Pipeline-Modulen benötigt. Sie liegen deshalb in einem eigenen Basismodul, damit
 * {@code configuration} nicht von {@code mapping}/{@code generation}/{@code ledger} abhängt
 * und keine Zyklen entstehen.
 *
 * <p><b>Abhängigkeiten:</b> keine.
 */
package de.hofmannit.erechnung.model;
