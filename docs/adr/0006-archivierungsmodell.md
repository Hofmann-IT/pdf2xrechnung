# ADR 0006: Archivierungsmodell

- Status: Akzeptiert (2026-09-24)
- Betrifft: Module `archive`, `generation`, Vorgabe Abschnitte 10, 12, 14, 20, 21, 41

## Kontext

Alle Artefakte eines Runs (Original-PDF, XML, ZUGFeRD-PDF, Validierungsreports,
Extraktionsreport) müssen revisionsnah abgelegt werden: je Run ein eigenes Verzeichnis, jede
Datei mit SHA-256, nach Ablage schreibgeschützt, nie überschreiben oder löschen. Dateinamen der
Ausgangsdokumente folgen einem Profil-Template; Kollisionen sind Fehler.

## Entscheidung

1. **Struktur:** `archive/{jahr}/{monat}/{rechnungsnummer}/run-{NNN}/` mit festen
   Dateinamen: `original.pdf`, `invoice-cii.xml`, `invoice-ubl.xml`, `invoice-zugferd.pdf`,
   `extraction.json`, `validation-kosit-<format>.xml/.html`, `validation-mustang-<format>.xml`.
   Jahr/Monat aus dem Rechnungsdatum (BT-2). Die Rechnungsnummer wird für den Pfad sicher
   bereinigt (nur `[A-Za-z0-9._-]`, andere Zeichen → `_`, Länge begrenzt); die unbereinigte
   Nummer steht im Ledger. Runs, die keine Rechnungsnummer ermitteln konnten (REVIEW/REJECTED),
   werden unter `archive/{jahr}/{monat}/_unbekannt/{sha256-prefix}/run-{NNN}/` abgelegt, damit
   auch fehlgeschlagene Läufe vollständig nachvollziehbar sind.
2. **Output:** `output/` erhält nur erfolgreich validierte Dokumente unter dem Profil-Template
   (`{invoiceNumber}_{invoiceDate:yyyyMMdd}_{customerName}` + Endung). Platzhalter werden
   bereinigt; existiert die Zieldatei bereits, ist der Run `FAILED` mit Begründung
   "Dateinamenskollision" (kein Überschreiben, kein stilles Suffix). `failed/`,
   `manual-review/`, `rejected/` erhalten die Quell-PDF mit Hash-Suffix im Namen, um auch dort
   Kollisionen auszuschließen.
3. **Atomare Ablage:** Dateien werden zuerst in ein temporäres Verzeichnis neben dem Ziel
   geschrieben, gehasht und anschließend per `Files.move(..., ATOMIC_MOVE)` verschoben;
   existiert das Ziel, schlägt der Move fehl (kein `REPLACE_EXISTING`). Erst nach erfolgreichem
   Move wird der `artifact`-Datensatz geschrieben; `UNIQUE(path)` verhindert doppelte
   Registrierung.
4. **Schreibschutz:** Nach Ablage wird das Schreibrecht entfernt: POSIX `r--r--r--` bzw. auf
   Windows das Read-only-Attribut (`DosFileAttributeView`). Beides ist Best-Effort im Sinne der
   Vorgabe ("soweit das Betriebssystem dies zuverlässig unterstützt"); Fehlschlag wird als
   Warnung protokolliert, nicht als Run-Fehler. Der Schutz gegen Überschreiben durch die
   Anwendung selbst wird unabhängig davon durch Punkt 3 garantiert.
5. **Kein Löschen, kein Überschreiben** durch Anwendungscode, auch nicht in Tests: Tests
   arbeiten ausschließlich in `@TempDir`. Es gibt keine Aufräumfunktion für `archive/`.
6. **Integrität:** Jede Archivdatei ist über `artifact.sha256` prüfbar; eine
   Integritätsprüfung (Rehash und Vergleich) wird als Systemstatus-Funktion (Phase 5)
   vorgesehen, verändert nichts.

## Ergänzung vom 2026-09-24 (Phase 2)

- **Kollision gleicher Rechnungsnummern:** Zwei verschiedene Quell-PDFs (unterschiedlicher
  SHA-256) mit derselben Rechnungsnummer würden dasselbe `run-NNN` beanspruchen, da Run-Nummern
  je Quelldokument zählen. In diesem Fall verwendet das Archiv `run-NNN_<sha256-prefix>` und
  protokolliert eine Warnung; der tatsächliche Pfad steht im Ledger (`artifact.path`). Fachlich
  ist das ein Hinweis auf eine doppelt vergebene Rechnungsnummer; die Verarbeitung scheitert
  in `output/` an der Dateinamenskollision (FAILED), das Archiv bleibt aber vollständig.
- **Fehlerpfade sind best effort getrennt:** Archivierung, Ledger-Eintrag und Ablage in
  `failed/`/`manual-review/`/`rejected/` werden unabhängig voneinander versucht, damit ein
  Fehler in einem Schritt nie dazu führt, dass das Original in `processing/` verbleibt.
- **Bei REVIEW/FAILED/REJECTED** wird das Original in das Archiv kopiert und nach
  `manual-review/<sha8>_<name>/`, `failed/<sha8>_<name>/` bzw. `rejected/` verschoben; dort
  liegen zusätzlich `pruefung.txt` bzw. `fehler.txt` mit Korrelations-ID und Begründung.

## Alternativen

- **Archiv als ZIP je Run:** kompakter, aber Einzelzugriff und Schreibschutz schwieriger;
  Vorgabe beschreibt Einzeldateien. Verworfen.
- **Kollisionen mit Suffix auflösen:** "keine stillen Kollisionen" verlangt Fehler. Verworfen.
- **Content-addressed Storage (Pfad = Hash):** revisionssicher, aber für Menschen nicht
  navigierbar; Vorgabe schreibt Struktur vor. Verworfen.

## Konsequenzen

- Der Archivpfad ist aus Ledger-Daten rekonstruierbar; das Ledger speichert ihn dennoch explizit.
- Speicherbedarf wächst monoton; Backup/Restore (Phase 6) behandelt `archive/` und `data/`
  als zusammengehörige Einheit.
