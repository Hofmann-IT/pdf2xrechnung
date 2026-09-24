# ADR 0005: Inbound-Validierung ("E-Rechnung prüfen")

- Status: Akzeptiert (2026-09-24)
- Betrifft: Modul `inboundvalidation`, Vorgabe Abschnitte 1B, 13, 29

## Kontext

Empfangene E-Rechnungen (XRechnung CII/UBL als XML, ZUGFeRD/Factur-X als PDF) sollen über die
Web-UI gezielt geprüft werden. Die Funktion ist strikt von der Ausgangs-Pipeline getrennt:
keine Weiterverarbeitung, keine Konvertierung, keine Änderung, kein Versand. Das Original bleibt
unverändert. Standardmäßig temporär, optional persistentes Prüfprotokoll.

## Entscheidung

1. **Eigenes Modul `inboundvalidation`**, das nur `validation`, `configuration`, `model` und
   `security` verwendet. Es hat keinen Zugriff auf `watcher`, `ledger`, `archive`, `dispatch`,
   `generation` (durch `ModuleDependencyTest` erzwungen). Ein Upload kann technisch nie in
   `inbox/` oder `processing/` gelangen.
2. **Verarbeitungskette:** Upload in ein temporäres, prozess-eigenes Verzeichnis → SHA-256 →
   Typerkennung anhand Inhalt (nicht Endung): PDF-Signatur bzw. XML-Wurzelelement
   (`CrossIndustryInvoice` → CII, `Invoice`/`CreditNote` im UBL-Namespace → UBL) →
   bei PDF: eingebettete Dateien lesen (PDFBox 3, Embedded Files Name Tree), E-Rechnungs-XML
   anhand bekannter Dateinamen (`factur-x.xml`, `zugferd-invoice.xml`, `xrechnung.xml`) bzw.
   AFRelationship und Wurzelelement identifizieren → Profil/Version aus BT-24
   (CustomizationID) und BT-23 (ProfileID) lesen, soweit vorhanden → Validatoren nach
   Anwendbarkeitsmatrix (ADR 0002) ausführen → Ergebnis darstellen.
   Die Profilerkennung gibt "nicht eindeutig bestimmbar" aus, wenn die Kennungen fehlen oder
   unbekannt sind; es wird kein Profil geraten.
3. **Ergebnisobjekt** enthält: Dokumenttyp, Format, Profil, Version, je Validator Ergebnis und
   Meldungen (`ValidationFinding` mit Schweregrad, Regel-ID, Beschreibung, XPath, Wert,
   Originalmeldung), verwendetes Regelwerk, Validierungszeit, SHA-256, bei ZUGFeRD: XML
   vorhanden, XML-Dateiname, erkanntes Profil, PDF/A-Befunde soweit zuverlässig prüfbar.
4. **Reports:** XML-Report (KoSIT-Report bzw. eigener XML-Report bei Mustang), HTML-Report
   (KoSIT-XSLT lokal; sonst serverseitig gerenderte HTML-Zusammenfassung) und
   menschenlesbare Zusammenfassung (Text). Download über die UI.
5. **Speicherung:** Standard `store-reports=false`: temporäre Dateien werden nach Abschluss
   der Anfrage gelöscht. Bei `true`: Ablage unter
   `inbound-validation/{jahr}/{monat}/{sha256-prefix}/` mit `original.<ext>`,
   `report.xml`, `report.html`, `summary.txt`; bei erneuter Prüfung desselben Hashes ein
   nummeriertes Unterverzeichnis (`check-002/`), nie überschreiben. Zusätzlich ein Datensatz in
   `inbound_validation` (append-only).
6. **Unveränderlichkeit des Originals:** Das Upload-Byte-Array wird nur gelesen; die
   Validatoren erhalten Kopien bzw. Pfade auf schreibgeschützte Temporärdateien. Der SHA-256
   wird vor und nach der Validierung verglichen und muss identisch sein (Test in Phase 3).
7. **Grenzen:** Uploadgröße `app.inbound-validation.max-upload-size`; XML nur nach
   `SecureXml`-Vorprüfung (kein DOCTYPE, wohlgeformt) an die Validatoren.

## Umsetzung vom 2026-09-24 (Phase 3)

- Klassen: `DocumentInspector` (Typerkennung anhand Inhalt, eingebettete Dateien über den
  PDFBox-Namensbaum, BT-24/BT-23 aus CII bzw. UBL), `ProfileCatalog` (Kennungen aus
  `org.mustangproject.ZUGFeRD.Profiles` der eingebundenen Version plus Peppol BIS Billing 3.0;
  XRechnung wird über das Präfix der Kennung erkannt, die Version daraus abgeleitet),
  `InboundValidationService`, `InboundReportWriter` (Zusammenfassung, HTML),
  `InboundValidationRepository` (eigene Tabelle, kein Bezug zu `ledger`).
- Anwendbarkeitsmatrix: XML mit XRechnung-Kennung → KoSIT verpflichtend, Mustang zusätzlich;
  XML ohne XRechnung-Kennung (z. B. Factur-X-Profile, Peppol) → Mustang verpflichtend, KoSIT
  zusätzlich (liefert `NOT_APPLICABLE`, wenn kein Szenario passt); ZUGFeRD-PDF → Mustang
  verpflichtend (PDF/A, eingebettete XML, Profilregeln), KoSIT auf die eingebettete XML
  (verpflichtend nur bei XRechnung-Profil). Gesamtergebnis `ERROR` ("nicht prüfbar") bei
  PDF ohne XML, unbekannter XML-Syntax, nicht wohlgeformter XML oder nicht unterstütztem Format.
- XML mit DOCTYPE wird durch `SecureXml` abgewiesen und als nicht wohlgeformt gemeldet.
- Ablage bei `store-reports=true`: `inbound-validation/{jahr}/{monat}/{sha8}/check-NNN/` mit
  `original.<xml|pdf|bin>`, `original.sha256`, `summary.txt`, `report.html`,
  `report-kosit.xml/.html`, `report-mustang.xml`; jede Wiederholung erhält ein neues
  `check-NNN`; Dateien werden schreibgeschützt; ein Datensatz in `inbound_validation`.
  Ohne Ablage bleibt kein Artefakt zurück (temporäres Verzeichnis unter `data/tmp` wird gelöscht).
- Die Tests der Vorgabe (gültige/ungültige CII und UBL, gültiges ZUGFeRD, ZUGFeRD ohne XML,
  beschädigte XML, nicht unterstütztes Format) erzeugen ihre Testdokumente aus der eigenen
  Ausgangs-Pipeline und weisen zusätzlich nach, dass weder `inbox/`, `processing/`,
  `output/` noch `processing_run` berührt werden.

## Alternativen

- **Wiederverwendung der Ausgangs-Pipeline mit Flag "nur validieren":** Kopplung, Risiko
  versehentlicher Weiterverarbeitung. Verworfen.
- **Dauerhafte Speicherung aller Uploads:** Datenschutz; Vorgabe verlangt temporär als Standard.
  Verworfen.

## Konsequenzen

- Zwei Aufrufer nutzen `validation` (Watcher und Inbound); der Validator-Code bleibt zustandslos.
- Die UI (Phase 5) benötigt für Nicht-Techniker verständliche Fehlertexte: Zuordnung von
  Regel-IDs zu Klartext wird als lokale Ressource gepflegt, Originalmeldung bleibt einsehbar.
