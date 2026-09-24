# ADR 0008: Web-Oberfläche

- Status: Akzeptiert (2026-09-24)
- Betrifft: Modul `web`, Vorgabe Abschnitte 24–31

## Kontext

Die Oberfläche soll wie eine moderne Business-Anwendung wirken, serverseitig gerendert sein
(Spring MVC, Thymeleaf, HTMX, kein SPA-Framework) und Nutzer sicher durch die Prozesse führen.

## Entscheidungen

1. **Rendering:** Thymeleaf-Templates mit gemeinsamem Layout-Fragment (`layout.html`), eigenes
   Stylesheet `static/css/app.css` mit den Farbwerten der Vorgabe (Deep Navy, Navy, Primary
   Blue, Cyan, Türkis, helle Flächen) als CSS-Variablen. Keine externen Ressourcen: HTMX wird aus
   dem WebJar lokal ausgeliefert, keine Web-Fonts, keine CDN-Aufrufe.
2. **HTMX** nur für progressive Interaktionen: Filtern der Rechnungsliste (Fragment `rows`),
   Upload-Ergebnis auf "E-Rechnung prüfen" und Profil-Test (Fragmente `result`, `test`). Jede
   Funktion arbeitet ohne JavaScript über normale Formulare weiter; die Drag-&-Drop-Zone ist
   ein kleines Inline-Skript, das nur das Dateifeld befüllt.
3. **Status** wird ausschließlich über `StatusProjection` aus Events abgeleitet; die Liste
   filtert nach Status in Java, alle anderen Filter in SQL (`LedgerRepository.listInvoices`).
4. **Downloads** nur aus dem Archiv über die `artifact`-Tabelle; der aufgelöste Pfad muss
   innerhalb der Archivwurzel liegen (Schutz vor Pfadmanipulation). PDFs der manuellen Prüfung
   werden nur aus dem Mandantenverzeichnis `manual-review/` geliefert, Verzeichnisnamen ohne
   Pfadtrenner oder `..`.
5. **"E-Rechnung prüfen":** Ergebnis prominent als Statuskarte mit Icon, Text und Farbe
   (gültig / nicht gültig / nicht prüfbar); Karten Dokument, Standard und Profil, Validatoren,
   Fehler, Warnungen, Technische Details (aufklappbar). Für Report-Downloads hält der
   Controller das Ergebnis maximal 60 Minuten bzw. 100 Einträge im Speicher; ohne
   `store-reports` bleibt nichts auf dem Dateisystem.
6. **Benutzeraktionen** (Reprocess, erneuter Versand) verlangen einen Benutzernamen als
   Formularfeld, da keine Anmeldung vorgesehen ist (siehe Offene Punkte). Reprocess läuft über
   `ReprocessService.reprocessAsync` im Mandanten-Executor, damit die Sequentialität je Mandant
   auch aus der Oberfläche erhalten bleibt; Vorbedingungen und Bestätigung werden synchron geprüft.
7. **Manuelle Prüfung** zeigt PDF (inline), extrahierte Werte, Plausibilitätsfehler und
   Fundstellen aus `extraction.json`. Es gibt bewusst keine Korrektur- oder Freigabefunktion.
8. **Profil-Test** nutzt Extraktion, Klassifizierung, Mapping und Plausibilität ohne Erzeugung,
   Ablage, Versand oder Ledger-Eintrag; temporäre Dateien werden sofort gelöscht.

## Offene Punkte

- **Zugriffsschutz:** Die Vorgabe definiert keine Anmeldung. Die Oberfläche ist im Netz des
  Unternehmens ohne Authentifizierung erreichbar; Benutzeraktionen tragen den frei eingegebenen
  Namen. Empfehlung vor Produktivbetrieb: Betrieb hinter einem Reverse Proxy mit Anmeldung oder
  Ergänzung einer einfachen Authentifizierung nach Freigabe.
- **Rechnungsausgangsbuch-Export** (CSV, DATEV) fehlt: Die DATEV-Zielvariante ist laut Vorgabe
  vor Umsetzung abzufragen (Stopp-Bedingung).
