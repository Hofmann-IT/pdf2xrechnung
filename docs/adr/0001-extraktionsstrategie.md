# ADR 0001: Extraktionsstrategie

- Status: Akzeptiert (2026-09-24)
- Betrifft: Module `extraction`, `mapping`, Vorgabe Abschnitte 3, 7, 8

## Kontext

Fertige PDF-Rechnungen (aus Word/Excel gedruckt) sollen ohne OCR und ohne externe Dienste auf
EN-16931-Business-Terms abgebildet werden. Reine Fließtext-Extraktion verliert das Layout
(Spalten, Anschriftenfeld, Tabellen) und macht Fundstellen nicht nachvollziehbar. Die Vorgabe
verlangt positionsbasierte Extraktion mit Apache PDFBox 3.x sowie je Feld Seite, Position,
Quelltext, Regel und Transformation.

## Entscheidung

1. **Positionsbasierte Extraktion mit PDFBox 3.x.** Der Text jeder Seite wird als Liste von
   Textfragmenten mit Bounding Box (`PositionedText`: Seite, x, y, Breite, Höhe, Text)
   gewonnen. Grundlage ist der Zeichen-/Wort-Strom, den PDFBox über `PDFTextStripper` bzw.
   dessen Erweiterungspunkt für Textpositionen (`TextPosition`) liefert. Fragmente werden zu
   Wörtern und Zeilen gruppiert (gleiche Grundlinie ± Toleranz, Wortabstand relativ zur
   Schriftgröße). Die konkreten PDFBox-Klassen werden in Phase 2 gegen die eingebundene Version
   3.0.5 verifiziert, bevor sie verwendet werden.
2. **Koordinatensystem:** PDF-Punkte, Ursprung links oben, y nach unten. PDFBox liefert
   Koordinaten mit Ursprung links unten; die Umrechnung erfolgt einmalig in `extraction`, damit
   Profile intuitiv (wie am Bildschirm) konfiguriert werden können. Rotierte Seiten werden
   normalisiert.
3. **Nur Textebene, kein OCR.** PDFs ohne Textebene (reine Scans) werden nicht extrahiert; sie
   führen zu `REVIEW` mit Begründung "keine Textebene". Ein lokales OCR ist nicht Teil des
   Stacks und wird nicht eigenmächtig ergänzt.
4. **Mapping-Regeln arbeiten ausschließlich auf dem Positionsmodell:**
   - `anchor`: Label-Fragment(e) suchen, Wert rechts (gleiche Zeile) oder unterhalb (nächste
     Zeile) innerhalb `maxDistance`.
   - `regex`: Regex über den zeilenweise rekonstruierten Seitentext; Fundstelle = Bounding Box
     der beteiligten Fragmente.
   - `region`: alle Fragmente, deren Mittelpunkt im Rechteck liegt; Modus FIRST_LINE /
     JOIN_LINES / JOIN_WORDS.
   - `table`: Spaltenzuordnung über x-Bereiche, Zeilenbeginn über Schlüsselspalte,
     Fortsetzungsseiten über Start-/End-Anker und wiederholten Kopf, mehrzeilige Bezeichnung.
   - `fixed`: Wert aus `config/tenant.yaml`; Fundstelle = "tenant.yaml", keine Seite.
5. **Nachvollziehbarkeit:** Jedes Feld wird als `FieldEvidence` protokolliert und als
   `extraction.json` (Artefakt `EXTRACTION_LOG`) archiviert.
6. **Transformationen** sind eine feste, im Profil dokumentierte Liste (`trim`, `upper`,
   `removeWhitespace`, `date:<pattern>`, `decimal:de|en`, `percent:de`, `regexReplace`).
   Keine Skriptsprache, keine Ausdrucksauswertung.

## Alternativen

- **Fließtext + Regex:** einfach, aber Spalten/Tabellen unzuverlässig, keine Fundstellen. Verworfen.
- **Externe Tabellen-Extraktoren (tabula, camelot):** zusätzliche Abhängigkeiten bzw. Python;
  nicht im Stack. Verworfen.
- **OCR (Tesseract):** nur für Scans nötig, nicht im Stack, nicht plattformneutral ohne
  native Binaries. Verworfen; bei Bedarf gesonderte Freigabe.

## Konsequenzen

- Profile enthalten Koordinaten; sie werden mit der CLI `calibrate` (Phase 4) ermittelt.
- Layoutänderungen des Briefpapiers erfordern Profilanpassung (erkennbar über `profile_hash`).
- Die Extraktion ist deterministisch und offline; Ergebnisse sind reproduzierbar (Reprocess).

## Offene Punkte

- Verhalten bei mehreren Treffern eines Ankers (erster / eindeutigster) wird in Phase 2 mit
  Testdaten festgelegt und im Profil konfigurierbar gemacht, falls erforderlich.
