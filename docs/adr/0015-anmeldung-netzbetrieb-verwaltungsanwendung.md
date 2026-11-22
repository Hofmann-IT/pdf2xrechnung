# ADR 0015: Anmeldung für alle Funktionen, Netzbetrieb, Windows-Verwaltungsanwendung

- Status: Akzeptiert (2026-11-20); Freigaben F1–F4 vom Auftraggeber mit dem Plan „Dienst +
  Windows-Verwaltungsanwendung (Version 1.1.0)" erteilt. Umsetzung in vier Phasen; dieser ADR
  wird je Phase fortgeschrieben. **Stand: alle vier Phasen umgesetzt, ausgeliefert mit Version 1.1.0.**
- Betrifft: `db/migration/V6__user_account.sql`, Module `admin`, `configuration`, `ledger`, `web`; `pom.xml`;
  `deploy/windows/netzbetrieb-einrichten.ps1`, jlink-Modulliste in `installer/windows/build-installer.ps1`;
  neu `desktop/windows/` (Verwaltungsanwendung);
  löst die Anmeldung aus ADR 0012/0013 (HTTP Basic nur für die Verwaltung) ab

## Kontext

Die Oberfläche soll als Windows-Anwendung mit eigenem Fenster erscheinen und zusätzlich von
anderen PCs im Firmennetz erreichbar sein. Bisher war nur der Verwaltungsbereich geschützt
(HTTP Basic, ein gemeinsames Konto); alle anderen Seiten – einschließlich Export, Reprocess,
Versand und PDF-Upload – waren für jeden erreichbar, der die Adresse kannte, und der Bearbeiter
wurde als Freitext eingetragen. Für einen im Netz erreichbaren Dienst ist das nicht tragbar.

## Entscheidungen

### Freigaben (Stop-Bedingungen der Projektvorgabe)

| # | Thema | Entscheidung |
|---|---|---|
| F1 | Technik des Fensters | C#/.NET Framework 4.8 mit WebView2 (Phase C) |
| F2 | Neue Laufzeitabhängigkeit | `spring-boot-starter-security` (Apache 2.0), Version von Spring Boot 3.5.16 verwaltet |
| F3 | Schemaänderung | V6 `user_account`, Rollen Administrator und Sachbearbeiter |
| F4 | Verschlüsselung | HTTPS mit Zertifikat aus dem Windows-Zertifikatsspeicher, Fingerabdruck-Bindung in der Verwaltungsanwendung (Phase B/C) |

### Phase A – Anmeldung

1. **Benutzerkonten (V6):** Tabelle `user_account`, append-only wie V2–V5: jede Änderung
   (Passwort, Rolle, sperren/freigeben) ist ein neuer Datensatz, je Benutzername gilt der
   jüngste. Benutzernamen werden ohne Beachtung der Groß-/Kleinschreibung verglichen
   (`COLLATE NOCASE`). Gespeichert wird nur der PBKDF2-SHA256-Hash (120 000 Iterationen,
   Format unverändert gegenüber V5), Mindestlänge 10 Zeichen.
2. **Übernahme:** V6 kopiert den jüngsten Datensatz aus `admin_credential` als ersten
   Administrator; das bisherige Passwort gilt weiter. `admin_credential` bleibt als Historie
   stehen und wird nicht mehr beschrieben.
3. **Rollen:** `ADMIN` (Administrator) darf alles. `USER` (Sachbearbeiter) darf alles außer
   `/verwaltung/**`, `/rechnungen/export/einstellungen/**` und dem Einrichtungs-Assistenten.
   Der letzte aktive Administrator kann weder gesperrt noch herabgestuft werden.
4. **Betreiber-Zugang:** `ADMIN_PASSWORD` (Benutzer aus `app.admin.username`) bleibt als
   zusätzlicher Administrator-Zugang, z. B. bei vergessenem Passwort. Er ist kein gespeichertes
   Konto; sein Benutzername kann nicht als Konto angelegt werden.
5. **Formular-Anmeldung mit Sitzung:** `/anmelden` (Felder `benutzer`, `passwort`), Abmelden per
   POST `/abmelden`. Sitzungs-Cookie `ERECHNUNG_SESSION` (HttpOnly, SameSite=Strict), Ablauf
   nach 60 Minuten ohne Aktivität; Sitzungen liegen im Speicher und enden mit einem
   Dienst-Neustart. Frei erreichbar sind nur `/anmelden`, statische Ressourcen (`/css`,
   `/webjars`, Favicon), die Rechnungsvorlagen (`/vorlagen`) und die Fehlerseite.
6. **Einrichtungs-Assistent:** ohne Anmeldung nur erreichbar, solange noch **kein**
   Administrator existiert. Fehlt später nur der Mandant, verlangt der Assistent eine
   Administrator-Anmeldung (bisher war er dann offen und hätte ein neues Passwort setzen können).
7. **Sperre nach Fehlversuchen:** fünf Fehlversuche sperren den Benutzernamen für fünf Minuten
   (im Speicher, je Dienstlauf); ein Administrator hebt die Sperre durch Neuvergabe des
   Passworts auf. Unbekannte Benutzernamen werden mit gleichem Rechenaufwand geprüft.
8. **CSRF-Schutz** für alle schreibenden Anfragen. Das Token setzt Thymeleaf über `th:action`
   als verstecktes Feld; HTMX-Formulare senden es als Formularfeld mit. HTMX-Teilanfragen ohne
   gültige Sitzung erhalten 401 mit `HX-Redirect: /anmelden`.
9. **Sitzungsprüfung je Anfrage:** Wird ein Konto gesperrt oder seine Rolle geändert, endet die
   laufende Sitzung mit der nächsten Anfrage (eine Datenbankabfrage je Anfrage; bei SQLite und
   der erwarteten Nutzerzahl unkritisch).
10. **Bearbeiter aus der Anmeldung:** Export-Protokoll, Export-Einstellungen, Rechnungs-
    Zusatzfelder, Belegtransfer, Reprocess, Versand, PDF-Upload, Prüfprotokolle und die
    Einstellungs-Historie tragen den angemeldeten Benutzernamen. Die Freitextfelder „Benutzer"
    entfallen.
11. **Kein Spring-Security-Typ außerhalb von `web`:** `UserAccountService` (Modul `admin`)
    kennt nur eigene Typen; die Bindung an Spring Security liegt in `web/SecurityConfig`
    (eigener `AuthenticationProvider` als Bean, damit Spring Boot keinen Standardbenutzer mit
    generiertem Passwort anlegt). Die Modulmatrix bleibt unverändert.

### Phase B – Netzzugriff und Verschlüsselung

12. **Standard „nur dieser Computer":** Der HTTP-Zugang (`server.port`, 8080) wird an die
    Loopback-Adresse gebunden. Bis 1.0.0 lauschte der Dienst auf allen Schnittstellen.
13. **Drei Modi** (`app.network.mode`): `local`; `network` = zusätzlich ein HTTPS-Connector auf
    allen Schnittstellen (Port `app.network.port`, 8443; TLS 1.2 und 1.3), der lokale
    HTTP-Zugang bleibt bestehen; `open` = HTTP auf allen Schnittstellen wie bisher, nur für
    Container und Reverse Proxy auf einem anderen Rechner (Docker-Image setzt
    `APP_NETWORK_MODE=open`). In der Oberfläche wählbar sind nur `local` und `network`.
14. **Schlüssel im Windows-Zertifikatsspeicher (F4):** Der Dienst (Konto LocalSystem) liest
    Zertifikat und privaten Schlüssel aus `Windows-MY-LOCALMACHINE` (JDK-Provider SunMSCAPI,
    Modul `jdk.crypto.mscapi` in der jlink-Laufzeit). Der Schlüssel ist nicht exportierbar; es
    gibt keine Schlüsseldatei und kein Passwort. Auswahl über den Anzeigenamen
    „PDF-zu-ERechnung" (bei mehreren das am längsten gültige) oder über den Fingerabdruck.
    Vorab geprüft: TLS-1.2- und TLS-1.3-Handshake mit einem nicht exportierbaren
    RSA-3072-Schlüssel (CNG) aus dem Windows-Speicher auf der jlink-Laufzeit.
15. **Kein Rückfall auf unverschlüsselten Netzzugriff:** Ist `network` angefordert und das
    Zertifikat nicht nutzbar, startet der Dienst im Modus `local` und zeigt den Grund in
    Protokoll, Systemstatus und Verwaltung. Ein zeitlich abgelaufenes Zertifikat wird weiter
    verwendet und gemeldet (die Arbeitsplätze binden an den Fingerabdruck, Phase C).
16. **Einstellung mit Neustart:** Die Bindung steht beim Start fest. Verwaltung → Netzzugriff
    schreibt Modus und Port nach `config/network.yaml` (per `spring.config.import` eingebunden,
    vorhandene Datei wird vorher gesichert) und speichert den Netzzugriff nur, wenn das
    Zertifikat gefunden wird. Die Laufzeiteinstellungen in der Datenbank (ADR 0012) eignen sich
    dafür nicht, weil der Webserver vor der Datenbank konfiguriert wird.
17. **Zertifikat und Firewall außerhalb des Dienstes:** `netzbetrieb-einrichten.ps1` (als
    Administrator) erzeugt das selbstsignierte Zertifikat und die Firewall-Regel (nur Profile
    Domäne und Privat). Der Dienst selbst ändert weder Zertifikatsspeicher noch Firewall. Das
    Setup führt das Skript bei gewählter Aufgabe aus (Phase D).
18. **Sitzungs-Cookie:** über HTTPS ausgestellt mit `Secure`; lokaler HTTP-Zugang und
    Netzzugang haben getrennte Sitzungen (unterschiedlicher Host).

### Phase C – Windows-Verwaltungsanwendung

19. **Technik (F1):** `desktop/windows/`, C# für .NET Framework 4.8 mit WinForms und dem
    WebView2-Steuerelement. Übersetzt wird mit dem `csc.exe`, das zu Windows gehört
    (Sprachstand C# 5), ohne .NET SDK und ohne Projektdatei. Das WebView2-SDK (BSD-3-Clause)
    wird beim Build als NuGet-Paket mit fester Version bezogen; geprüft werden SHA-256 des
    Pakets und die Microsoft-Signatur der DLLs. Die Anwendung enthält keine Fachlogik: sie
    zeigt die Seiten des Dienstes.
20. **Verbindung und Fingerabdruck (F4):** lokal `http://localhost:<Port>`, im Netz
    ausschließlich `https://<Server>:<Port>`. Vor jeder Verbindung liest die Anwendung das
    Zertifikat des Servers, ohne Daten zu senden, und vergleicht den SHA-256-Fingerabdruck mit
    dem bestätigten Wert. Ohne Bestätigung oder bei Abweichung wird nicht verbunden; der neue
    Wert muss ausdrücklich bestätigt werden (Vorgabe im Dialog: Nein). Zertifikatsfehler, die
    WebView2 meldet (selbstsigniert), werden nur für den verbundenen Server und nur bei genau
    diesem Fingerabdruck zugelassen; früher erteilte Ausnahmen werden vor jeder Verbindung
    verworfen. Auch ein von Windows als vertrauenswürdig eingestuftes Zertifikat wird gebunden.
21. **Nur der Dienst im Fenster:** Navigation zu Adressen außerhalb von Schema, Rechner und
    Port des Dienstes wird abgebrochen; vom Benutzer angeklickte Webadressen öffnet der
    Standardbrowser. Entwicklerwerkzeuge, Statusleiste, Kennwortspeicherung und automatisches
    Ausfüllen sind abgeschaltet. Links mit neuem Fenster bleiben im selben Fenster
    (Leiste mit Zurück, Start, Neu laden).
22. **Keine Zugangsdaten auf dem Arbeitsplatz:** `%APPDATA%\PDF-zu-ERechnung\verwaltung.ini`
    enthält nur Verbindung, Fingerabdruck und Fensterlage. Die Anmeldung bleibt die des
    Dienstes (Sitzungs-Cookie im Profilordner der Anzeige unter `%LOCALAPPDATA%`).
23. **Dienststatus und -steuerung:** Auf dem Server liest die Anwendung den Zustand des
    Windows-Dienstes; Starten, Stoppen und Neustarten laufen über die vorhandene Steuerdatei
    (WinSW) mit der Rückfrage von Windows nach Administratorrechten. Auf Arbeitsplätzen zeigt
    das Symbol nur, ob der Server Verbindungen annimmt.
24. **Meldungen statt Fehlerseiten:** Dienst gestoppt, Dienst startet noch, Server nicht
    erreichbar, Zertifikat nicht bestätigt, WebView2 fehlt – jeweils mit dem nächsten Schritt.
    Wartet das Fenster auf den Dienst, verbindet es sich von selbst, sobald er antwortet.
25. **Rahmen nur aus derselben Anwendung:** `X-Frame-Options: SAMEORIGIN` statt `DENY`, weil
    die manuelle Prüfung die Original-PDF in einem Rahmen zeigt (Fehler aus Phase A).
26. **Diagnoselauf:** `/selbsttest <Ordner>` verbindet sich ohne Dialoge, schreibt Ergebnis
    und Bildschirmfotos und ändert keine Einstellungen. Damit wurden lokale Verbindung, HTTPS
    mit bestätigtem Fingerabdruck, Ablehnung bei falschem Fingerabdruck, Meldung bei nicht
    erreichbarem Dienst und die Sperre fremder Adressen geprüft.

### Phase D – Installationspaket (ergänzt ADR 0014)

27. **Zwei Installationsarten in einem Setup:** „Server" (Dienst, Verwaltungsanwendung,
    Handbuch) und „Arbeitsplatz" (Verwaltungsanwendung, Handbuch). „Server" steht an erster
    Stelle und ist damit die Vorgabe beim Update einer Installation 1.0.0. Datenordner und
    Dienst entstehen nur bei „Server". Eine Arbeitsplatz-Installation in einen Ordner mit
    vorhandenem Dienst lehnt das Setup ab, weil der Dienst sonst in der alten Version bliebe.
28. **Start über die Anwendung:** Startmenü- und Desktop-Verknüpfung sowie der Start nach der
    Installation führen zur Verwaltungsanwendung (mit den Rechten des angemeldeten Benutzers,
    nicht denen des Setups). Die Browser-Verknüpfung bleibt auf dem Server zusätzlich erhalten.
29. **Netzzugriff als Aufgabe, nicht als Vorgabe:** Nur wenn die Aufgabe angehakt ist, führt
    das Setup `netzbetrieb-einrichten.ps1` aus und schreibt `config\network.yaml` (eine
    vorhandene Datei wird als `.bak-setup` gesichert). Ohne die Aufgabe bleibt eine vorhandene
    Einstellung unverändert. Scheitert das Skript, wird die Einstellung nicht geschrieben.
30. **Deinstallation:** beendet die Verwaltungsanwendung, entfernt Dienst und Firewall-Regel.
    Zertifikat, Datenordner und die Einstellungen der Benutzer bleiben erhalten.
31. **WebView2:** Das Setup prüft die Laufzeit über den Registrierungseintrag des
    Edge-Updaters und weist bei Fehlen auf die Lösung hin; es lädt nichts nach (kein
    Netzzugriff durch das Setup).

## Konsequenzen

- Nach dem Update auf diese Version müssen sich alle Benutzer anmelden; bestehende
  Installationen behalten das Administrator-Konto aus der Einrichtung.
- Spring Security aktiviert zusätzlich Schutz-Header (u. a. `X-Frame-Options: DENY`,
  `Cache-Control: no-store`) und eine Web-Firewall, die Pfadwechsel wie `/..` bereits mit 400
  abweist, bevor die Anwendung sie sieht.
- CLI (`calibrate`, `reprocess`) und die Verarbeitung über den Watcher sind fachlich nicht
  betroffen; die Web-Sicherheit wird nur mit Web-Server aufgebaut (`CliContextTest`).
- Nach dem Update ist die Oberfläche von anderen PCs erst wieder erreichbar, wenn der
  Netzzugriff eingerichtet ist (Windows) bzw. `APP_NETWORK_MODE=open` gesetzt wird (Linux mit
  Reverse Proxy auf einem anderen Rechner). Docker ist durch das Image unverändert erreichbar.
- Im Browser warnen Arbeitsplätze bei einem selbstsignierten Zertifikat (die
  Verwaltungsanwendung bindet stattdessen an den Fingerabdruck); der Fingerabdruck steht in Verwaltung und Systemstatus.
- Nicht automatisch prüfbar: Zugriff des Dienstkontos auf den privaten Schlüssel im Speicher
  des lokalen Computers und die Firewall-Regel (beides verlangt Administratorrechte). Der
  Dienst meldet einen nicht lesbaren Schlüssel beim Start.

## Verworfene Alternativen

- **Schlüsseldatei (PKCS12) mit Passwort in der Konfiguration:** plattformunabhängig, aber ein
  Geheimnis in einer Datei – durch die Projektvorgabe ausgeschlossen.
- **Java-Tray und Edge im App-Modus (`msedge --app=`):** keine neue Technik, aber keine
  Bindung an den Fingerabdruck, kein eigener Umgang mit Downloads und Fehlern.
- **HTTPS auch für den lokalen Zugang:** einheitlich, aber jeder lokale Aufruf (Setup,
  Verknüpfung, Dienstprüfung) bräuchte das Zertifikat; der Loopback-Verkehr verlässt den
  Rechner nicht.
- **Dienst erzeugt Zertifikat und Firewall-Regel selbst:** ein Klick weniger, aber eine
  Web-Anwendung, die Sicherheitseinstellungen des Betriebssystems ändert.
- **Eigener Sitzungs-/CSRF-Filter ohne neue Abhängigkeit:** weniger Code im Classpath, aber
  Sitzungsfixierung, CSRF und Header müssten selbst gebaut und gepflegt werden.
- **Ein gemeinsames Konto für alle:** kein V6 nötig, aber keine Nachvollziehbarkeit, wer
  exportiert, erneut verarbeitet oder Einstellungen geändert hat.
- **HTTP Basic für alle Seiten:** kein Abmelden, Browser-Dialog statt Anmeldeseite, Passwort in
  jeder Anfrage.
