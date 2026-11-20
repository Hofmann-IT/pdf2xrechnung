// Tests der Kernlogik (Core.cs, ServiceControl.cs) ohne Fenster. Übersetzt und ausgeführt von
// build-desktop.ps1; ein fehlgeschlagener Test bricht den Build ab (Exitcode 1).
using System;
using System.Collections.Generic;
using System.IO;
using HofmannIT.ERechnung.Verwaltung;

internal static class Tests
{
    private static int failures;
    private static int count;

    private static void Check(bool condition, string name)
    {
        count++;
        if (!condition)
        {
            failures++;
            Console.WriteLine("FEHLGESCHLAGEN: " + name);
        }
    }

    private static void Equal(object expected, object actual, string name)
    {
        Check(object.Equals(expected, actual), name + " (erwartet: " + expected + ", erhalten: " + actual + ")");
    }

    private static int Main()
    {
        Settings();
        Targets();
        FingerprintTests();
        ServicePaths();
        CommandLines();
        Console.WriteLine(count + " Prüfungen, " + failures + " fehlgeschlagen");
        return failures == 0 ? 0 : 1;
    }

    private static void Settings()
    {
        AppSettings fresh = AppSettings.Parse("");
        Equal("", fresh.Mode, "Erster Start hat keinen Modus");
        Equal(8080, fresh.LocalPort, "Standard lokaler Port");
        Equal(8443, fresh.Port, "Standard HTTPS-Port");
        Check(!fresh.IsServer, "Erster Start ist kein Server-Modus");

        string fp = new string('a', 64);
        AppSettings s = new AppSettings();
        s.Mode = AppSettings.ModeServer;
        s.Host = "rechnungsserver";
        s.Port = 9443;
        s.Fingerprint = fp;
        s.WindowX = 10;
        s.WindowY = 20;
        s.WindowWidth = 1000;
        s.WindowHeight = 700;
        s.Maximized = true;
        s.TrayHintShown = true;
        AppSettings back = AppSettings.Parse(s.Serialize());
        Equal(AppSettings.ModeServer, back.Mode, "Modus bleibt erhalten");
        Equal("rechnungsserver", back.Host, "Servername bleibt erhalten");
        Equal(9443, back.Port, "Port bleibt erhalten");
        Equal(fp, back.Fingerprint, "Fingerabdruck bleibt erhalten");
        Equal(1000, back.WindowWidth, "Fensterbreite bleibt erhalten");
        Check(back.Maximized && back.TrayHintShown, "Schalter bleiben erhalten");

        string text = s.Serialize().ToLowerInvariant();
        Check(text.IndexOf("passwort=", StringComparison.Ordinal) < 0 && text.IndexOf("password", StringComparison.Ordinal) < 0
            && text.IndexOf("benutzer=", StringComparison.Ordinal) < 0, "Einstellungen enthalten kein Feld für Zugangsdaten");

        AppSettings broken = AppSettings.Parse("modus=server\nserver=https://boese.example/pfad\nport=99999\nfingerabdruck=abc\nfensterbreite=5\nunbekannt=1\nohne-gleich");
        Equal("", broken.Mode, "Server-Modus ohne gültigen Servernamen gilt als nicht eingerichtet");
        Equal("", broken.Host, "Ungültiger Servername wird verworfen");
        Equal(8443, broken.Port, "Ungültiger Port fällt auf Standard zurück");
        Equal("", broken.Fingerprint, "Ungültiger Fingerabdruck wird verworfen");
        Equal(1280, broken.WindowWidth, "Zu kleine Fensterbreite fällt auf Standard zurück");

        string dir = Path.Combine(Path.GetTempPath(), "erechnung-verwaltung-test-" + Guid.NewGuid().ToString("N"));
        string file = Path.Combine(dir, "unterordner", "verwaltung.ini");
        try
        {
            s.Save(file);
            Equal("rechnungsserver", AppSettings.Load(file).Host, "Speichern legt den Ordner an und Laden liest zurück");
            Equal("", AppSettings.Load(Path.Combine(dir, "gibt-es-nicht.ini")).Mode, "Fehlende Datei = erster Start");
        }
        finally
        {
            if (Directory.Exists(dir))
            {
                Directory.Delete(dir, true);
            }
        }
    }

    private static void Targets()
    {
        AppSettings local = new AppSettings();
        local.Mode = AppSettings.ModeLocal;
        local.LocalPort = 8080;
        Target t = Target.From(local);
        Equal("http://localhost:8080/", t.BaseUri.ToString(), "Lokale Adresse ist HTTP auf localhost");
        Check(!t.Secure, "Lokal ist unverschlüsselt (Loopback)");
        Check(t.IsInternal("http://localhost:8080/rechnungen?x=1"), "Seite des Dienstes ist intern");
        Check(t.IsInternal("HTTP://LOCALHOST:8080/"), "Schreibweise spielt keine Rolle");
        Check(!t.IsInternal("http://localhost:8081/"), "Anderer Port ist fremd");
        Check(!t.IsInternal("https://localhost:8080/"), "Anderes Schema ist fremd");
        Check(!t.IsInternal("http://localhost.evil.example:8080/"), "Ähnlicher Rechnername ist fremd");
        Check(!t.IsInternal("http://user@localhost:8080/"), "Adresse mit Benutzerangabe ist fremd");
        Check(!t.IsInternal("http://evil.example/?u=http://localhost:8080/"), "Fremde Adresse mit eingebetteter Dienstadresse ist fremd");
        Check(!t.IsInternal("javascript:alert(1)"), "Skript-Adresse ist fremd");
        Check(!t.IsInternal("file:///C:/Windows/win.ini"), "Datei-Adresse ist fremd");
        Check(!t.IsInternal(""), "Leere Adresse ist fremd");
        Check(!t.IsInternal(null), "Fehlende Adresse ist fremd");

        AppSettings server = new AppSettings();
        server.Mode = AppSettings.ModeServer;
        server.Host = "Rechnungsserver";
        server.Port = 8443;
        Target r = Target.From(server);
        Equal("https://rechnungsserver:8443/", r.BaseUri.ToString(), "Server-Adresse ist immer HTTPS");
        Check(r.Secure, "Server ist verschlüsselt");
        Check(r.IsInternal("https://rechnungsserver:8443/anmelden"), "Seite des Servers ist intern");
        Check(!r.IsInternal("http://rechnungsserver:8443/anmelden"), "Unverschlüsselte Adresse des Servers ist fremd");
        Check(!r.IsInternal("https://rechnungsserver/anmelden"), "Standardport ist nicht der Dienst");
        Equal("Rechnungsserver:8443", r.Display, "Anzeige des Servers");

        Check(Target.IsExternalWeb("https://www.hofmann-it.de/"), "Webadresse darf im Standardbrowser öffnen");
        Check(!Target.IsExternalWeb("file:///C:/x.exe"), "Datei-Adresse wird nicht geöffnet");
        Check(!Target.IsExternalWeb("ms-settings:"), "Protokoll-Adresse wird nicht geöffnet");
        Check(!Target.IsExternalWeb("javascript:alert(1)"), "Skript-Adresse wird nicht geöffnet");

        Check(Target.ValidHost("server-01.firma.local"), "Rechnername mit Domäne ist gültig");
        Check(Target.ValidHost("192.168.1.20"), "IPv4-Adresse ist gültig");
        Check(!Target.ValidHost("https://server"), "Schema im Servernamen ist ungültig");
        Check(!Target.ValidHost("server:8443"), "Port im Servernamen ist ungültig");
        Check(!Target.ValidHost("server/pfad"), "Pfad im Servernamen ist ungültig");
        Check(!Target.ValidHost("user@server"), "Benutzerangabe im Servernamen ist ungültig");
        Check(!Target.ValidHost("a..b"), "Doppelter Punkt ist ungültig");
        Check(!Target.ValidHost(" server"), "Leerzeichen ist ungültig");
        Check(!Target.ValidHost(""), "Leerer Servername ist ungültig");
    }

    private static void FingerprintTests()
    {
        string hex = "67c6429d7d60b3773e07b4bdce5180e4e381798ff11f9bb13cb3f2cfa4f8d8e7";
        string shown = "67:C6:42:9D:7D:60:B3:77:3E:07:B4:BD:CE:51:80:E4:E3:81:79:8F:F1:1F:9B:B1:3C:B3:F2:CF:A4:F8:D8:E7";
        Equal(hex, Fingerprints.Normalize(shown), "Anzeige des Dienstes wird normalisiert");
        Equal(shown, Fingerprints.Format(hex), "Format entspricht der Anzeige des Dienstes");
        Equal(hex, Fingerprints.Normalize(hex.ToUpperInvariant()), "Großbuchstaben werden normalisiert");
        Equal("", Fingerprints.Normalize(hex.Substring(2)), "Zu kurzer Fingerabdruck ist ungültig");
        Equal("", Fingerprints.Normalize(hex + "zz"), "Fremde Zeichen machen den Fingerabdruck ungültig");
        Equal("", Fingerprints.Normalize(null), "Fehlender Fingerabdruck ist ungültig");
        Check(Fingerprints.Matches(hex, shown), "Bestätigter und vorgezeigter Fingerabdruck passen");
        Check(!Fingerprints.Matches(hex, hex.Substring(0, 63) + "0"), "Abweichung in einem Zeichen passt nicht");
        Check(!Fingerprints.Matches("", ""), "Leerer bestätigter Fingerabdruck passt nie");
        Check(!Fingerprints.Matches("", hex), "Ohne Bestätigung passt kein Zertifikat");
        Equal(2, Fingerprints.FormatTwoLines(hex).Split('\n').Length, "Dialoganzeige in zwei Zeilen");
        // SHA-256 der leeren Eingabe (bekannter Prüfwert)
        Equal("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Fingerprints.Of(new byte[0]), "SHA-256-Berechnung");
    }

    private static void ServicePaths()
    {
        Equal(@"C:\Program Files\PDF-zu-ERechnung\app\pdf-zu-erechnung.exe",
            ServiceControl.ExecutableFromImagePath("\"C:\\Program Files\\PDF-zu-ERechnung\\app\\pdf-zu-erechnung.exe\""), "Pfad in Anführungszeichen");
        Equal(@"C:\Dienst\pdf-zu-erechnung.exe", ServiceControl.ExecutableFromImagePath(@"C:\Dienst\pdf-zu-erechnung.exe --x"), "Pfad mit Argumenten");
        Equal("", ServiceControl.ExecutableFromImagePath(null), "Fehlender Pfad");
        Equal("", ServiceControl.ExecutableFromImagePath("\"unvollstaendig"), "Unvollständige Anführungszeichen");
        Check(!ServiceControl.PortOpen("127.0.0.1", 1, 500), "Geschlossener Port wird als nicht erreichbar gemeldet");
        foreach (ServiceState s in Enum.GetValues(typeof(ServiceState)))
        {
            Check(ServiceControl.Describe(s).Length > 0, "Beschreibung für Status " + s);
        }
    }

    private static void CommandLines()
    {
        CommandLine c = CommandLine.Parse(new string[] { "/tray" });
        Check(c.StartInTray && c.SelfTestDirectory.Length == 0, "/tray startet im Infobereich");
        Check(CommandLine.Parse(new string[] { "--tray" }).StartInTray, "--tray wird ebenfalls verstanden");
        CommandLine d = CommandLine.Parse(new string[] { "/einstellungen", @"C:\x\a.ini", "/selbsttest", @"C:\y", "sonstiges" });
        Equal(@"C:\x\a.ini", d.SettingsPath, "Pfad der Einstellungen");
        Equal(@"C:\y", d.SelfTestDirectory, "Ordner des Diagnoselaufs");
        Equal(1, d.Unknown.Count, "Unbekannte Angaben werden gesammelt");
        Check(!CommandLine.Parse(new string[0]).StartInTray, "Ohne Angaben normales Fenster");
    }
}
