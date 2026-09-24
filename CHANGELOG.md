# Changelog

Format nach [Keep a Changelog](https://keepachangelog.com/de/1.1.0/); Versionierung nach SemVer.
Die Versionsnummer im `pom.xml` bleibt bis zur ersten Freigabe `0.1.0-SNAPSHOT`.

## [Unreleased]

### Phase 6 – Betrieb (2026-09-24)
- Dockerfile (Multi-Stage, JRE 21, unprivilegierter Benutzer, Validierungsressourcen im Image),
  `docker-compose.yaml` mit den Volumes inbox, processing, output, archive, config, profiles, data
  sowie failed, manual-review, rejected, inbound-validation, logs.
- WinSW-Dienstkonfiguration (`deploy/windows/pdf-zu-erechnung.xml`) und systemd-Unit mit
  Härtung (`deploy/linux/pdf-zu-erechnung.service`, `pdf-zu-erechnung.env.example`).
- README mit Installation (Windows, Linux, Docker), Konfiguration, Profil-Einrichtung,
  Kalibrierung, Validierung, Reprocess, Versand, Export/DATEV, Betriebs-Checkliste, Backup,
  Restore, Troubleshooting; `THIRD-PARTY.md` mit den Lizenzen aller Laufzeitabhängigkeiten.
- Stabiler Artefaktname `target/pdf-zu-erechnung.jar` (`finalName` im POM).
- Docker-Image und Compose-Betrieb mit Docker Desktop/WSL 2 verifiziert: Dienstbenutzer auf
  UID/GID 10001 (1000 ist im Ubuntu-Basisimage vergeben).
- Behoben: Die Sperre für Shell-Interpreter im lokalen Kommando-Postprozess erkannte unter
  Linux keine Windows-Pfade (`C:\...\cmd.exe`), weil `\` dort kein Pfadtrenner ist; die
  Prüfung ist jetzt plattformunabhängig.

### Export und DATEV (Zwischenschritte nach Phase 5, 2026-09-24)
- Export des Rechnungsausgangsbuchs als CSV und als DATEV-Format „Buchungsstapel" (EXTF 700,
  Formatversion 13) mit Vorschau, Download und Exportprotokoll (ADR 0009).
- V2: Export-Einstellungen je Mandant in der Datenbank (Historie), Rechnungs-Zusatzfelder
  (#115/#116, #117, #40), Exportprotokoll; zusätzliche Ledger-Felder BT-9, BT-72, BT-48, BT-46
  (ADR 0010). Pflegeseiten in der Oberfläche.
- V3: Übergabe der ZUGFeRD-PDF an das lokale DATEV-Belegtransfer-Verzeichnis, automatisch bei
  Erstverarbeitung, manuell wiederholbar, nie überschreibend, protokolliert (ADR 0011).

### Phase 5 – Web-Oberfläche (2026-09-24)
- Dashboard, Ausgangsrechnungen mit Filtern und Detailansicht (Prozess-Timeline, Extraktion,
  Artefakte, Historie, Reprocess, Versand), E-Rechnung prüfen mit Upload, Manuelle Prüfung,
  Profile mit Profil-Test, Systemstatus. Spring MVC, Thymeleaf, HTMX (lokal), ohne externe
  Ressourcen (ADR 0008).

### Phase 4 – Postprozess und CLI (2026-09-24)
- Optionaler SMTP-Versand (Zugangsdaten nur aus Umgebungsvariablen), sichere lokale
  Prozessausführung ohne Shell, CLI `calibrate` und `reprocess`, Reprocess nur mit
  Begründung und Bestätigung, nie mit automatischem erneutem Versand (ADR 0007).

### Phase 3 – Inbound-Validierung (2026-09-24)
- Separate Prüfung empfangener E-Rechnungen (XRechnung CII/UBL, ZUGFeRD/Factur-X) mit KoSIT
  und Mustang; Originale werden nie verändert; optionales Prüfprotokoll (ADR 0005).

### Phase 2 – Ausgangs-Pipeline (2026-09-24)
- Watcher → Extraktion (PDFBox, positionsbasiert) → Mapping (Profile: anchor, regex, region,
  table, fixed) → Plausibilität → Erzeugung XRechnung CII/UBL und ZUGFeRD (Mustang) →
  Validierung (KoSIT, Mustang) → Ablage → Ledger → Events → Archiv; Idempotenz über
  Mandant + SHA-256; Wiederanlauf nach Neustart (ADR 0001–0004, 0006).

### Phase 1 – Architektur und Gerüst (2026-09-24)
- Maven-Projekt, Modulstruktur mit geprüfter Abhängigkeitsmatrix, Flyway-Basisschema mit
  Append-only-Triggern, Beispiel `config/tenant.yaml`, vollständig kommentiertes
  `profiles/standard.yaml`, ADRs.
