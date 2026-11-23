# ADR 0016: Vorlagen-Assistent – Word-/Excel-Rechnung importieren und in den Standardaufbau bringen

- Status: Akzeptiert (2026-11-23); Freigaben F1–F5 vom Auftraggeber mit dem Plan „Vorlagen-Assistent
  (Version 1.2.0)" erteilt. Umsetzung in vier Phasen; dieser ADR wird je Phase fortgeschrieben.
  **Stand: Phasen 1 bis 3 umgesetzt (Analyse, Umbau von Excel- und Word-Dateien); offen ist Phase 4 (automatischer Test, Handbuch, Version 1.2.0).**
- Betrifft: `desktop/windows/` (Verwaltungsanwendung). Der Dienst (Java), das Profil `standard`,
  die Datenbank und der Installer bleiben unverändert.

## Kontext

Beim ersten Kundentest scheiterten alle Rechnungen am eigenen Layout des Kunden: andere
Beschriftungen („RECHNUNG 4725", „Datum:", „Endbetrag"), andere Spaltenfolge, fehlende
Pflichtangaben (Kunden-E-Mail, Kunden-Nr.). Die Vorlagen wurden von Hand umgebaut; die Anleitung
„Rechnungsvorlage anpassen" beschreibt denselben Weg für Kunden. Gewünscht ist eine Funktion, die
eine bestehende Word- oder Excel-Rechnung importiert und den Rechnungsteil in den Aufbau des
Profils `standard` bringt, ohne Logo, Bilder, Briefkopf und Fußzeile zu verändern.

## Entscheidungen

### Freigaben (Stop-Bedingungen der Projektvorgabe)

| # | Thema | Entscheidung |
|---|---|---|
| F1 | Technik | Assistent in der Windows-Verwaltungsanwendung; steuert das lokal installierte Word/Excel (COM, spät gebunden über `dynamic`). Einziger neuer Verweis: `Microsoft.CSharp.dll` aus .NET Framework 4.8 – nichts wird mitgeliefert. Keine neue Laufzeitabhängigkeit im Dienst. |
| F2 | Rechenregeln der erzeugten Excel-Datei | wie in `Rechnungsvorlage.xlsx`: Gesamt = `RUNDEN(Menge×Einzelpreis;2)`, USt = `RUNDEN(Netto×Satz;2)`. In Word wird nicht gerechnet. |
| F3 | Unterpunkte ohne eigenen Preis | Vorschlag „als Text in die Bezeichnung", markiert, im Bestätigungsschritt änderbar. |
| F4 | USt je Position, wenn die Rechnung keine Spalte hat | nur bei genau einem Steuersatz im Summenblock; sonst Platzhalter. |
| F5 | Version | 1.2.0. |

### Grundsätze

1. **Vorschlag und Bestätigung, keine Vollautomatik.** Der Assistent zeigt, was er erkannt hat;
   der Benutzer korrigiert Werte, bevor etwas erzeugt wird.
2. **Das Original wird nie verändert.** Lesen schreibgeschützt; Ergebnisse (ab Phase 2) als neue
   Dateien neben dem Original, ohne Überschreiben.
3. **Nichts wird erfunden.** Angaben, die auf der Rechnung fehlen, werden als fehlend gemeldet
   (ab Phase 2: markierte Platzhalter). Der „Zahlbetrag" wird nur als Vorschlag gleich dem
   Gesamtbetrag gesetzt und als zu prüfen markiert.
4. **Fachliche Sonderfälle werden abgelehnt, nicht entschieden:** Abschlags-, Teil- und
   Schlussrechnungen, § 13b UStG / Reverse Charge, Spalte „LS. %", Rabatt/Skonto/Abzüge im
   Summenblock. Skonto in den Zahlungsbedingungen ist kein Ausschlussgrund.
5. **Alles bleibt auf dem Computer.** Kein Netzzugriff; ab Phase 4 geht nur die Test-PDF an den
   eigenen Dienst (vorhandene Funktion „Profil testen").

### Phase 1 – Import und Analyse

- `TemplateModel.cs` (ohne Fenster, ohne Office): Momentaufnahme (`SourceDocument`: Zeilen aus
  Textstücken mit Spalte und Fundstelle; Textkörper, Textfelder, Kopf-/Fußzeile getrennt),
  `TemplateAnalyzer` (Erkennung), `AnalysisReport` (Bericht, Rohansicht der Zeilen), `Values`
  (Beträge, Datum, Einheiten in deutscher Schreibweise).
- Erkennung über Synonyme der Beschriftungen; Wert hinter der Beschriftung, in der Nachbarzelle
  oder – wenn Beschriftungen als Kopfzeile stehen – in der Zeile darunter. Anschrift über die
  Zeile „PLZ Ort" links (Briefkopf mit eigener Postleitzahl rechts und Absenderzeile zählen
  nicht). Tabellenkopf: mindestens drei bekannte Spalten, darunter Bezeichnung und ein Preis. Eine
  Spalte reicht von ihrer Überschrift bis vor die nächste (Zahl und Einheit unter „Menge", Betrag
  und Euro-Zeichen unter „GP"). Summenblock: Netto, Umsatzsteuer je Satz, Brutto, Zahlbetrag; ein
  Betrag ohne Beschriftung über der Umsatzsteuer gilt als Nettosumme „bitte prüfen".
- Gegenprüfung ohne Entscheidung: Summe der Positionen gegen Netto, Netto + USt gegen Brutto –
  Abweichungen werden als Hinweis gemeldet.
- `OfficeAutomation.cs`: Lesen über Word/Excel in eigenem STA-Thread mit Zeitlimit; Makros der
  Datei werden nicht ausgeführt (`AutomationSecurity = 3`); antwortet Office nicht, wird genau der
  dafür gestartete Prozess beendet.
- `TemplateWizard.cs`: Dialog (Datei wählen, bei Excel Blatt wählen, Angaben und Positionen mit
  änderbaren Werten, Hinweise, „Bericht speichern"). Aufruf über „Vorlage importieren …" in der
  Leiste und im Menü des Infobereichs.
- Befehlszeile `/vorlagentest <Ordner>`: wertet alle Word-/Excel-Dateien des Ordners ohne Fenster
  aus und schreibt je Datei Bericht, Rohansicht und ein Bild des Dialogs nach `<Ordner>\ergebnis`.

### Phase 2 – Umbau von Excel-Dateien (`.xlsx`, `.xls`)

- `TemplateLayout.cs` (ohne Office, getestet): Bauplan des neuen Blatts (`ExcelLayout.Build`) als Liste von
  Schreibvorgängen – Anschrift ab Zeile 9 (150 pt vom Blattrand), Angaben zur Rechnung, Zusatzzeilen des
  Kunden, Positionstabelle, Summenblock mit den Formeln aus F2, Zahlungsbedingungen, Schlusstexte – samt
  Markierung (rot/gelb/grün) und Kommentar je Zelle. `OutputNames`: Dateinamen ohne Überschreiben.
- Die Analyse merkt sich, welche Textstücke verwendet wurden, und ordnet den Rest ein: oberhalb der ersten
  erkannten Angabe **Briefkopf**, zwischen Anschrift und Tabelle **Zusatzzeile** (Beschriftung mit Wert daneben
  oder darunter; Beschriftungen ohne Wert entfallen), unter dem Summenblock **Schlusstext**. Eine Zahl ohne
  Beschriftung wird nicht übernommen und gemeldet.
- `TemplateConverter.cs`: Kopie des Blatts in eine neue Mappe; Formularsteuerelemente und Kommentare
  entfallen; Bilder, Formen und Textfelder werden frei verankert und behalten ihre Druckposition (Ausgleich
  der geänderten Seitenränder); Zelltexte des Briefkopfs werden Textfelder an gleicher Stelle; danach Raster
  des Profils, Bauplan, Seitenränder, Druckbereich und Drucktitel (Tabellenkopf auf Folgeseiten).
- Die Grundschrift der neuen Mappe ist Arial 10: Excel rechnet Spaltenbreiten beim Drucken über die
  Grundschrift um; nur so liegen die Spalten im PDF an den Koordinaten des Profils. Textfelder und Bilder
  behalten ihre Schrift.
- Eine Absenderzeile (Textfeld oder frühere Zelle), die im Bereich des neuen Anschriftenfelds läge, wird
  darüber gesetzt und gemeldet; andere Elemente in diesem Bereich werden nur gemeldet.
- Nach dem Umbau werden „Summe netto" und „Gesamtbetrag" der neuen Datei mit den Beträgen der bisherigen
  Rechnung verglichen; eine Abweichung wird als „ACHTUNG" gemeldet, nichts wird angeglichen.
- Ergebnis neben dem Original: `<Name>_E-Rechnung.xlsx`, `<Name>_E-Rechnung_markiert.xlsx` (Farben,
  Kommentare, Legende außerhalb des Druckbereichs) und `<Name>_E-Rechnung_Test.pdf`.
- Excel bietet für den PDF-Export per Automatisierung keinen Schalter für PDF/A: Das Test-PDF dient nur
  der Profilprüfung; für Rechnungen bleibt der Weg Speichern unter → PDF → Optionen → PDF/A.
- Im Dialog: Schaltfläche „Umbauen …" mit Rückfrage und Ergebnisliste.

### Phase 3 – Umbau von Word-Dateien (`.docx`, `.doc`)

- Das Original wird schreibgeschützt geöffnet und sofort unter dem neuen Namen als `.docx` gespeichert;
  gearbeitet wird nur in dieser Kopie. Schlägt der Umbau fehl, werden die angefangenen Dateien entfernt.
- Ersetzt wird **nur der Rechnungsteil**: von der ersten erkannten Angabe bis zur letzten Summen- oder
  Zahlungszeile (`Analysis.RegionStart`/`RegionEnd`, Zeichenpositionen aus dem Lesen). Kopf- und Fußzeilen,
  Seitenränder, Abschnitte und alles davor (Briefkopf) und danach (Schlusstexte) bleiben unangetastet.
  Tabellen, die nur teilweise zum Rechnungsteil gehören, werden an dessen Grenzen geteilt.
- **Verankerte Bilder und Textfelder:** In Word ist der Anker einer Form ein unsichtbares Zeichen im Text.
  Liegt er im Rechnungsteil (häufig: Logo und Briefkopf hängen am ersten Absatz der Anschrift), wird der
  Absatz bzw. die Tabellenzeile nicht gelöscht, sondern nur der Text zwischen und hinter den Ankerzeichen;
  der Rest schrumpft auf 1 pt. Zur Sicherheit wird die Zahl der Formen vor und nach dem Umbau verglichen –
  fehlt eine, bricht der Umbau ab und speichert nichts. Formen, die an einem Absatz statt an der Seite
  ausgerichtet sind, bekommen nach dem Umbau ihre vorherige Lage auf der Seite zurück.
- **Anschriftenfeld** als Textfeld an fester Stelle der Seite (56,7 pt von links, 146 pt von oben,
  Zeilenabstand genau 14 pt, Umbruch „oben und unten"): Die Lage hängt damit nicht vom Textfluss, von den
  Seitenrändern oder der Kopfzeile des Kunden ab. Beginnt der Textkörper oberhalb, wird der Ankerabsatz in
  die Höhe des Felds gerückt, sodass Word alles Folgende darunter setzt.
- Überschrift, Angaben zur Rechnung (je eine Zeile „Beschriftung: Wert"), Zusatzzeilen, dann die
  **Positionstabelle und der Summenblock aus der mitgelieferten `Rechnungsvorlage.docx`** (in die EXE
  eingebettet, Übertragung per `FormattedText` ohne Zwischenablage). Die Tabellen werden so eingerückt, dass
  ihr Text an derselben Stelle der Seite steht wie in der Standardvorlage (gemessen, nicht gerechnet);
  Absätze erhalten den Einzug 56,7 pt minus linker Seitenrand.
- Schrift: Standardschrift des Dokuments (höchstens 11 pt); Positionen in der Schriftgröße, in der der
  Rechnungsteil bisher überwiegend gesetzt war (8 bis 11 pt), damit die Rechnung nicht länger wird.
- In Word wird nicht gerechnet (F2): Beträge werden übernommen; „Gesamt netto" erhält den Nettobetrag,
  „Zahlbetrag" den Vorschlag aus der Analyse; fehlende Beträge bleiben leer und rot.
- Bilder, die im Rechnungsteil im Text stehen (z. B. eine Skizze in der Positionsbeschreibung), sind in der
  neuen Datei nicht enthalten; das wird gemeldet.
- Das Test-PDF aus Word ist PDF/A (`UseISO19005_1`). Die markierte Fassung erhält Schattierungen und eine
  Legende auf einer eigenen Seite am Ende.

## Tests

- `tests/Tests.cs` (bei jedem Bau): Erkennung an nachgebauten Fremd-Layouts mit erfundenen Daten,
  Ausschlussfälle, Zahlen- und Datumsformate.
- `tests/vorlagen-e2e.ps1` (braucht Office, nicht Teil von `mvn verify`): erzeugt Fremd-Layouts als
  `.xlsx`, `.xls`, `.docx`, `.doc`, wertet sie über `/vorlagentest` aus und prüft die Berichte. Die
  Excel- und Word-Dateien werden umgebaut; ihr Test-PDF muss mit `calibrate` gegen `profiles/standard.yaml`
  bestehen (richtige Anschrift, Nummer, Positionen, Beträge; Excel zusätzlich zweiseitig mit 45 Positionen).
- Kundendateien werden nur lokal geprüft und nicht ins Repository übernommen.

## Konsequenzen und Grenzen

- Der Assistent braucht Word bzw. Excel auf dem Arbeitsplatz; ohne Office gibt es eine Meldung.
- Die Erkennungsquote hängt vom Layout ab. Neue Schreibweisen werden als Synonym in
  `TemplateModel.cs` ergänzt und mit einem Test abgesichert.
- Rechnungen, deren Angaben in Bildern stehen oder deren Tabelle keine Kopfzeile hat, werden nicht
  erkannt.
