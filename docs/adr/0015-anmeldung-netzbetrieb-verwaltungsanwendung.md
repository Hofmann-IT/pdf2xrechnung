# ADR 0015: Anmeldung für alle Funktionen, Netzbetrieb, Windows-Verwaltungsanwendung

- Status: Akzeptiert (2026-11-20); Freigaben F1–F4 vom Auftraggeber mit dem Plan „Dienst +
  Windows-Verwaltungsanwendung (Version 1.1.0)" erteilt. Umsetzung in vier Phasen; dieser ADR
  wird je Phase fortgeschrieben. **Stand: Phase A (Anmeldung) umgesetzt.**
- Betrifft: `db/migration/V6__user_account.sql`, Module `admin`, `ledger`, `web`; `pom.xml`;
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

## Konsequenzen

- Nach dem Update auf diese Version müssen sich alle Benutzer anmelden; bestehende
  Installationen behalten das Administrator-Konto aus der Einrichtung.
- Spring Security aktiviert zusätzlich Schutz-Header (u. a. `X-Frame-Options: DENY`,
  `Cache-Control: no-store`) und eine Web-Firewall, die Pfadwechsel wie `/..` bereits mit 400
  abweist, bevor die Anwendung sie sieht.
- CLI (`calibrate`, `reprocess`) und die Verarbeitung über den Watcher sind nicht betroffen.
- Offen bis Phase B: Der Dienst lauscht weiterhin unverschlüsselt auf allen Schnittstellen
  (Port 8080). Anmeldedaten sollten bis dahin nicht über fremde Netze übertragen werden.

## Verworfene Alternativen

- **Eigener Sitzungs-/CSRF-Filter ohne neue Abhängigkeit:** weniger Code im Classpath, aber
  Sitzungsfixierung, CSRF und Header müssten selbst gebaut und gepflegt werden.
- **Ein gemeinsames Konto für alle:** kein V6 nötig, aber keine Nachvollziehbarkeit, wer
  exportiert, erneut verarbeitet oder Einstellungen geändert hat.
- **HTTP Basic für alle Seiten:** kein Abmelden, Browser-Dialog statt Anmeldeseite, Passwort in
  jeder Anfrage.
