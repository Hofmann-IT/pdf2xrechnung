# ADR 0009: Export des Rechnungsausgangsbuchs (CSV und DATEV-Buchungsstapel)

- Status: Akzeptiert (2026-09-24)
- Betrifft: Modul `export`, Vorgabe Abschnitt 33; löst den offenen Punkt aus ADR 0008 ab

## Kontext

Die Vorgabe verlangt einen Export des Rechnungsausgangsbuchs als CSV und in einer DATEV-Variante,
deren konkrete Ausprägung vor Umsetzung abzufragen war. Entscheidung des Auftraggebers:
einfache CSV plus DATEV-Format „Buchungsstapel" (EXTF-Datei) jetzt; „DATEV Unternehmen online"
später in einem eigenen Schritt, da es eine Netzwerkverbindung erfordert und damit eine
ausdrückliche Ausnahme von der Netzwerksperre braucht. Kontenrahmen, Sachkontenlänge,
Debitorenstrategie, Erlöskonten, Berater- und Mandantennummer kennt nur der Kunde bzw. sein
Steuerberater und müssen daher konfigurierbar sein.

## Entscheidungen

1. **Zwei Varianten, lokale Dateien:** `CSV` (immer verfügbar) und `DATEV_BUCHUNGSSTAPEL`
   (nur wenn `export.datev.enabled` im Mandanten gesetzt ist). Beide werden ausschließlich als
   Download über die Oberfläche erzeugt (Ausgangsrechnungen → Export), nichts wird auf dem
   Dateisystem abgelegt; der Export ist rein lesend.
2. **Auswahl der Rechnungen:** je Quelldokument der jüngste Run mit Ergebnis `SUCCESS`.
   Ein Reprocess ersetzt damit den Vorgänger im Export, ohne etwas zu überschreiben. Runs mit
   `REVIEW`, `FAILED`, `REJECTED` oder ohne Rechnungsnummer werden mit Begründung in der
   Vorschau als „übersprungen" gelistet. Dieselbe Rechnungsnummer aus zwei Quelldokumenten wird
   exportiert, aber als Hinweis gemeldet.
3. **DATEV-Format nur nach Dokumentation:** Kopfzeile (31 Felder) und Datensatz (125 Felder)
   folgen der DATEV-Formatbeschreibung Version 700 / Formatversion 13; Quelle und die
   verwendeten Feldregeln stehen in `docs/datev-format-referenz.md`. Der Exporter prüft jedes
   befüllte Feld gegen die dort dokumentierten regulären Ausdrücke und bricht bei Verstoß ab
   (kein „bestmöglicher" Export). Zeichensatz Windows-1252, Semikolon, CR/LF, Textfelder in
   Anführungszeichen, Dezimalkomma.
4. **Buchungslogik:** je Rechnung und Steuerzeile eine Buchung mit Umsatz = Brutto der
   Steuerzeile, Konto (#7) = Debitor, Gegenkonto (#8) = Erlöskonto, Soll/Haben `S` (Gutschrift
   `H`), BU-Schlüssel (#9) nur, wenn für das Erlöskonto konfiguriert. Erlöskonto-Schlüssel:
   `<Geschäftsfall>:<Steuersatz>` mit Rückfall auf `<Geschäftsfall>`. Debitor wahlweise
   Sammeldebitor oder Konto je Kunde (Zuordnung über den exakten Kundennamen BT-44; fehlende
   Zuordnung überspringt die Rechnung mit Hinweis).
5. **Wirtschaftsjahr:** Das Belegdatum (#10) trägt nur Tag und Monat; das Jahr ergibt sich
   aus dem Wirtschaftsjahresbeginn der Kopfzeile. Ein Export darf deshalb nur einen Zeitraum
   innerhalb eines Wirtschaftsjahres umfassen und verlangt „von" und „bis".
6. **Belegfeld 1:** Rechnungsnummer; Zeichen außerhalb des von DATEV erlaubten Vorrats
   (`[A-Za-z0-9_$&%*+-/]`) werden durch `-` ersetzt, Länge auf 36 gekürzt, jede Änderung wird
   als Hinweis in der Vorschau angezeigt, weil das Feld der OP-Ausgleichsschlüssel ist.
7. **Konfiguration wird beim Start geprüft** (`ProfileRegistry.validateDatev`): Beraternummer
   4–7 Ziffern, Mandantennummer 1–5 Ziffern, Sachkontenlänge 4–8, Sachkontenrahmen
   zweistellig, Konten 1–9 Ziffern, BU-Schlüssel vierstellig, Diktatkürzel 2 oder 4
   Großbuchstaben. Die Beispielkonfiguration enthält ausdrücklich als Platzhalter markierte
   SKR03-Konten und ist standardmäßig deaktiviert.
8. **Protokoll:** Jeder Export wird mit Variante, Mandant, Zeitraum, Anzahl und SHA-256 des
   Inhalts geloggt; der Download trägt den Hash zusätzlich als Antwortkopf `X-Content-SHA256`.

## Alternativen

- Export per CLI oder als Datei in `output/`: verworfen, weil kein Anwendungsfall ohne
  Oberfläche vorliegt und die Ablage weiterer Dateien neben den E-Rechnungen die Ausgangs-
  und Archivsemantik verwässern würde. Kann bei Bedarf ergänzt werden.
- Umsatzsteuer als eigene Buchungszeile statt Automatikkonto/BU-Schlüssel: verworfen; das
  DATEV-Format sieht die Steuerermittlung über Konto oder Schlüssel vor, und die Kontenwahl
  liegt beim Steuerberater.

## Konsequenzen

- Neues Modul `export` mit Abhängigkeiten `ledger`, `configuration`, `model`, `security`;
  Matrix und `ModuleDependencyTest` erweitert.
- `TenantProperties.Tenant` erhält den optionalen Block `export.datev`; Tests, die
  `Tenant` direkt konstruieren, übergeben `null`.
- Keine Schemaänderung: es wird keine Exporttabelle angelegt (siehe Offene Punkte).

## Offene Punkte

- **Exportprotokoll in der Datenbank** (welcher Zeitraum wurde wann mit welchem Hash
  exportiert): sinnvoll für Nachvollziehbarkeit gegenüber dem Steuerberater, erfordert aber
  eine V2-Migration und damit Freigabe.
- **Leistungsdatum (#115/#116), Fälligkeit (#117), USt-IdNr. (#40):** bewusst leer, bis mit
  dem Steuerberater geklärt ist, ob und wie sie belegt werden sollen.
- **DATEV Unternehmen online:** eigener Schritt nach Klärung der Netzwerkausnahme.
- **Debitorenzuordnung über Kundennamen** ist fragil bei abweichender Schreibweise; eine
  Zuordnung über Kundennummer (BT-46) wäre robuster, sobald diese extrahiert wird.
