# DATEV-Format (EXTF) – Referenz für den Export

Quelle: DATEV Developer Portal, „DATEV-Format" (abgerufen 2026-09-24):
- https://developer.datev.de/de/file-format/details/datev-format/getting-started
- https://developer.datev.de/de/file-format/details/datev-format/format-description/header
- https://developer.datev.de/de/file-format/details/datev-format/format-description/booking-batch

Diese Datei fasst die für den Export „Buchungsstapel" verwendeten Regeln zusammen. Alle
Angaben stammen aus den genannten Seiten; nichts wurde ergänzt.

## Dateiaufbau

- CSV mit Dateinamens-Präfix `EXTF_…csv`
- Zeile 1: Kopfzeile, Zeile 2: Überschriftszeile, ab Zeile 3: Nutzdaten
- Felder durch Semikolon getrennt, Zeilenende CR/LF
- Textfelder in doppelten Anführungszeichen, enthaltene Anführungszeichen verdoppelt,
  keine Steuerzeichen (z. B. Zeilenumbruch) in Textfeldern
- Dezimaltrennzeichen Komma, keine Tausendertrennzeichen
- Volumengrenze: maximal 99.999 Buchungen je Datei; Empfehlung: eine Datei je Buchungsperiode

Beispiel-Kopfzeile aus der Dokumentation:

```
"EXTF";700;21;"Buchungsstapel";13;20240130140440439;;"RE";"";"";29098;55003;20240101;4;20240101;20240831;"Buchungsstapel";"WD";1;0;0;"EUR";;"";;;"03";;;"";""
```

## Kopfzeile (31 Felder)

| # | Feld | Ausdruck | Verwendung im Export |
|---|------|----------|----------------------|
| 1 | Kennzeichen | `^["](EXTF\|DTVF)["]$` | `"EXTF"` |
| 2 | Versionsnummer | `^(700)$` | `700` |
| 3 | Formatkategorie | `^(16\|20\|21\|46\|48\|65)$` | `21` (Buchungsstapel) |
| 4 | Formatname | Buchungsstapel u. a. | `"Buchungsstapel"` |
| 5 | Formatversion | `^(2\|4\|5\|13)$` | `13` (Buchungsstapel) |
| 6 | Erzeugt am | `YYYYMMDDHHMMSSFFF` | Exportzeitpunkt |
| 7 | Importiert | leer | leer |
| 8 | Herkunft | `^["]\w{0,2}["]$` | konfigurierbar, Standard `"RE"` |
| 9 | Exportiert von | `^["]\w{0,25}["]$` | konfigurierbar |
| 10 | Importiert von | `^["]\w{0,25}["]$` | `""` |
| 11 | Beraternummer | `^(\d{4,6}\|\d{7})$`, Bereich 1001–9999999 | konfigurierbar (Pflicht) |
| 12 | Mandantennummer | `^\d{1,5}$`, Bereich 1–99999 | konfigurierbar (Pflicht) |
| 13 | WJ-Beginn | `YYYYMMDD` | aus Konfiguration (Monat/Tag) und Exportjahr |
| 14 | Sachkontenlänge | `^[4-8]$` | konfigurierbar |
| 15 | Datum von | `YYYYMMDD` | Beginn des Exportzeitraums |
| 16 | Datum bis | `YYYYMMDD` | Ende des Exportzeitraums |
| 17 | Bezeichnung | `^["][\w.-/ ]{0,30}["]$` | `"Rechnungsausgang MM/JJJJ"` |
| 18 | Diktatkürzel | `^["]([A-Z]{2}){0,2}["]$` | konfigurierbar |
| 19 | Buchungstyp | `^[1-2]$` | `1` (Finanzbuchführung) |
| 20 | Rechnungslegungszweck | `^(0\|30\|40\|50\|64)$` | `0` |
| 21 | Festschreibung | `^(0\|1)$` | konfigurierbar, Standard `1` |
| 22 | WKZ | `^["]([A-Z]{3})["]$` | `"EUR"` |
| 23 | Reserviert | leer | leer |
| 24 | Derivatskennzeichen | `""` | `""` |
| 25, 26 | Reserviert | leer | leer |
| 27 | Sachkontenrahmen | `^["](\d{2}){0,2}["]$` | konfigurierbar (`"03"` oder `"04"`) |
| 28 | ID der Branchenlösung | `^\d{0,4}$` | leer |
| 29 | Reserviert | leer | leer |
| 30 | Reserviert | `""` | `""` |
| 31 | Anwendungsinformation | `^["].{0,16}["]$` | `"PDF-zu-ERechnung"` |

## Datensatz Buchungsstapel (125 Felder, verwendete Felder)

| # | Feld | Ausdruck | Verwendung im Export |
|---|------|----------|----------------------|
| 1 | Umsatz (ohne Soll/Haben-Kz) | `^(?!0{1,10}\,00)\d{1,10}\,\d{2}$` | Bruttobetrag je Steuerzeile, positiv |
| 2 | Soll-/Haben-Kennzeichen | `^["](S\|H)["]$` | `"S"` bei Rechnung, `"H"` bei Gutschrift (bezogen auf #7) |
| 3 | WKZ Umsatz | `^["]([A-Z]{3})["]$` | Rechnungswährung |
| 7 | Konto | `^(?!0{1,9}$)(\d{1,9})$` | Debitorenkonto (Sammeldebitor oder Konto je Kunde) |
| 8 | Gegenkonto (ohne BU-Schlüssel) | `^(?!0{1,9}$)(\d{1,9})$` | Erlöskonto je Geschäftsfall/Steuersatz |
| 9 | BU-Schlüssel | `^(["]\d{4}["])$` | konfigurierbar je Erlöskonto, sonst `""` |
| 10 | Belegdatum | `^(\d{4})$`, Format TTMM | Rechnungsdatum; Jahr aus Kopfzeile #13 |
| 11 | Belegfeld 1 | `^(["][\w$&%*+\-\/]{0,36}["])$` | Rechnungsnummer (unzulässige Zeichen werden ersetzt, siehe Exportbericht) |
| 12 | Belegfeld 2 | wie #11 | leer |
| 14 | Buchungstext | `^(["].{0,60}["])$` | Template, Standard „Rechnung {invoiceNumber} {customerName}" |
| 40 | EU-Mitgliedstaat u. UStID (Bestimmung) | `^(["].{0,15}["])$` | Länderkürzel (2 Zeichen; Griechenland `EL`, Nordirland `XI`) + USt-IdNr. (max. 13), z. B. `"DE133546770"`. Nur bei EU-Geschäftsfällen; Benutzerangabe vor BT-48 |
| 114 | Festschreibung | `^(0\|1)$` | wie Kopfzeile #21 |
| 115 | Leistungsdatum | `TTMMJJJJ` | nur aus Benutzerangabe und nur zusammen mit #116; Einsatz laut DATEV in Absprache mit dem Steuerberater |
| 116 | Datum Zuord. Steuerperiode | `TTMMJJJJ` | Pflicht, sobald #115 befüllt ist; nur aus Benutzerangabe |
| 117 | Fälligkeit | `TTMMJJJJ` | nur bei OPOS relevant; Benutzerangabe vor BT-9. Belegfeld 2 (#12) bleibt leer |

Alle übrigen Felder werden leer (Zahlenfelder) bzw. `""` (Textfelder) ausgegeben, entsprechend
der Beispieldatei der Dokumentation.

Hinweise aus der Dokumentation, die der Export beachtet:
- #11 Belegfeld 1 dient als Schlüssel für den Ausgleich offener Posten; erlaubt sind
  Buchstaben, Ziffern, `_` und `$ & % * + - /`.
- Die Personenkontenlänge darf nur eine Stelle länger sein als die Sachkontenlänge.
- Das Jahr des Belegdatums ergibt sich aus dem Wirtschaftsjahresbeginn der Kopfzeile; ein
  Stapel darf daher nur Belege eines Wirtschaftsjahres enthalten.
