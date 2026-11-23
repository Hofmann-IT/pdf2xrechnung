# ADR 0016: Vorlagen-Assistent – Word-/Excel-Rechnung importieren und in den Standardaufbau bringen

- Status: Akzeptiert (2026-11-23); Freigaben F1–F5 vom Auftraggeber mit dem Plan „Vorlagen-Assistent
  (Version 1.2.0)" erteilt. Umsetzung in vier Phasen; dieser ADR wird je Phase fortgeschrieben.
  **Stand: Phase 1 (Import und Analyse) umgesetzt; noch keine Konvertierung.**
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

## Tests

- `tests/Tests.cs` (bei jedem Bau): Erkennung an nachgebauten Fremd-Layouts mit erfundenen Daten,
  Ausschlussfälle, Zahlen- und Datumsformate.
- `tests/vorlagen-e2e.ps1` (braucht Office, nicht Teil von `mvn verify`): erzeugt Fremd-Layouts als
  `.xlsx`, `.xls`, `.docx`, `.doc`, wertet sie über `/vorlagentest` aus und prüft die Berichte.
- Kundendateien werden nur lokal geprüft und nicht ins Repository übernommen.

## Konsequenzen und Grenzen

- Der Assistent braucht Word bzw. Excel auf dem Arbeitsplatz; ohne Office gibt es eine Meldung.
- Die Erkennungsquote hängt vom Layout ab. Neue Schreibweisen werden als Synonym in
  `TemplateModel.cs` ergänzt und mit einem Test abgesichert.
- Rechnungen, deren Angaben in Bildern stehen oder deren Tabelle keine Kopfzeile hat, werden nicht
  erkannt.
