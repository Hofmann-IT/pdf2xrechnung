# ADR 0011: V3 – Übergabe an DATEV Belegtransfer (Variante „Unternehmen online")

- Status: Akzeptiert (2026-09-24), Schemaänderung V3 und Umfang (nur ZUGFeRD-PDF) vom Auftraggeber freigegeben
- Betrifft: `db/migration/V3__belegtransfer.sql`, Module `export`, `ledger`, `watcher`, `web`; ergänzt ADR 0009/0010

## Kontext

Der Auftraggeber möchte Ausgangsrechnungen nach DATEV Unternehmen online bringen, ohne dass
diese Anwendung eine Netzwerkverbindung aufbaut (Vorgabe: kein Netz außer SMTP). DATEV
Belegtransfer (Wissensplattform Dok.-Nr. 1020025, Stand 16.09.2026) ist ein lokal installierter
DATEV-Client, der vom Anwender angelegte Verzeichnisse je Unternehmen und Belegtyp überwacht,
Dateien (PDF, GIF, JPG, TIF, PNG, XML; max. 20 MB) nach DATEV Unternehmen online überträgt und
hochgeladene Dateien anschließend in einen eigenen Archivordner verschiebt. Der Upload und die
Anmeldung liegen damit vollständig beim DATEV-Client.

## Entscheidungen

1. **Nur die ZUGFeRD-PDF** wird übergeben: Sie ist Belegbild und strukturierte Rechnung in einer
   Datei. XRechnung-XML wird nicht kopiert (Entscheidung des Auftraggebers).
2. **Konfiguration je Mandant** in den Export-Einstellungen (Datenbank, sonst
   `config/tenant.yaml` → `export.belegtransfer`): `enabled` und `directory`. Das Verzeichnis
   muss absolut und lokal sein; UNC-/Netzwerkpfade werden abgewiesen. Es wird beim Start bzw.
   beim Speichern geprüft, seine Existenz erst bei der Übergabe (Fehler wird protokolliert).
3. **Zeitpunkt:** automatisch nach erfolgreicher **Erstverarbeitung** (wie Versand und
   Kommando: nie automatisch bei Reprocess oder Wiederanlauf). Manuell jederzeit über die
   Rechnungsdetailseite mit Benutzername, nur für Runs mit Ergebnis `SUCCESS`.
4. **Nie überschreiben:** Existiert die Zieldatei mit identischem Hash, gilt die Übergabe als
   erledigt (`SKIPPED`). Existiert sie mit anderem Inhalt, wird `<name>_<sha8>.pdf` verwendet;
   existiert auch diese mit anderem Inhalt, wird abgebrochen (`FAILED`). Kopiert wird zuerst
   unter `.<name>.part` und dann umbenannt, damit der DATEV-Client keine unvollständige Datei liest.
5. **Protokoll in `belegtransfer_transfer`** (append-only): Run, Artefakt, Zielpfad, Hash,
   Ergebnis `COPIED`/`SKIPPED`/`FAILED`, Meldung, Akteur, Zeitpunkt. Bewusst **kein neuer
   Ereignistyp** in `processing_event`: Dessen `CHECK`-Liste ließe sich in SQLite nur durch
   Neuaufbau der revisionsrelevanten Ereignistabelle erweitern. Der Status der Rechnung
   (Projektion aus Events) bleibt von der Übergabe unberührt.
6. **Fehler stoppen nichts:** Ein fehlgeschlagener Kopiervorgang ändert weder Run-Ergebnis noch
   Status; er ist im Protokoll und auf der Detailseite sichtbar und manuell wiederholbar.
7. **Modulmatrix:** `watcher` darf zusätzlich `export` verwenden (Aufruf nach Archivierung).

## Alternativen

- Eigener Upload über die DATEV-Schnittstellen (DATEV XML-Schnittstelle online, Datenservices):
  verworfen, verlangt Netzwerk, OAuth und Registrierung; der Belegtransfer-Client leistet das lokal.
- Übergabe der Datei aus `output/` statt aus dem Archiv: verworfen, `output/` ist Übergabeordner
  für Dritte und kann geleert sein; das Archiv ist die verlässliche Quelle.

## Konsequenzen

- Flyway-Stand 3; `ExportSettingsRow` hat zwei weitere Felder.
- Für die Nutzung muss der DATEV-Belegtransfer-Client auf demselben System installiert und das
  Verzeichnis dort für das Unternehmen und den Belegtyp Rechnungsausgang angelegt sein
  (Betriebsdokumentation Phase 6).

## Offene Punkte

- **Belegtyp-Zuordnung** (Rechnungsausgang vs. Rechnungseingang) erfolgt allein über das
  gewählte Verzeichnis; die Anwendung kennt die Belegtransfer-Konfiguration nicht.
- **Rückmeldung des Uploads** (ob der DATEV-Client die Datei tatsächlich übertragen hat) ist
  nur im Protokoll des DATEV-Clients sichtbar, nicht in dieser Anwendung.
