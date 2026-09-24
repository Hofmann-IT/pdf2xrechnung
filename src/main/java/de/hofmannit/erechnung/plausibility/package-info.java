/**
 * Modul {@code plausibility}: rechnerische Prüfung der gemappten Rechnung vor der Erzeugung
 * (Vorgabe Abschnitt 9).
 *
 * <p>Geprüft werden mindestens: Summe der Positionen, Nettosummen, Steuerbasis und Steuerbetrag
 * je Steuersatz, Gesamtsteuer, Bruttobetrag, Zahlbetrag. Toleranz: 0,01 EUR
 * ({@link de.hofmannit.erechnung.plausibility.PlausibilityTolerance}), sofern kein Profil
 * ausdrücklich eine andere Rundungsregel definiert.
 *
 * <p>Bei Abweichung: Datei nach {@code manual-review/}, Status {@code REVIEW}, keine Erzeugung.
 *
 * <p><b>Abhängigkeiten:</b> {@code mapping}, {@code configuration}.
 *
 * <p>Implementierung: Phase 2.
 */
package de.hofmannit.erechnung.plausibility;
