/**
 * Modul {@code security}: sicherheitsrelevante Querschnittsfunktionen.
 *
 * <ul>
 *   <li>Sicher konfigurierte XML-Parser (kein XXE, keine externen DTDs/Schemata, Limits)</li>
 *   <li>SHA-256-Hashing für Quelldokumente, Artefakte und Profile</li>
 *   <li>Sichere Dateinamen-Bereinigung (später: Generierung/Archiv)</li>
 *   <li>Sichere lokale Prozessausführung ohne Shell-Interpolation (Phase 4)</li>
 * </ul>
 *
 * <p><b>Abhängigkeiten:</b> Basismodul ohne Abhängigkeiten zu anderen fachlichen Modulen.
 */
package de.hofmannit.erechnung.security;
