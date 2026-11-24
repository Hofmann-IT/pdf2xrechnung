// Tests der Kernlogik (Core.cs, ServiceControl.cs, TemplateModel.cs) ohne Fenster. Übersetzt und ausgeführt von
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
        TemplateValues();
        TemplateExcelLayout();
        TemplateWordLayout();
        TemplateRefusals();
        TemplateLayoutVariants();
        TemplateExcelPlan();
        ProfileTests();
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
        Equal(@"C:\z", CommandLine.Parse(new string[] { "/vorlagentest", @"C:\z" }).TemplateTestDirectory, "Ordner des Vorlagentests");
    }

    // ---- Vorlagen-Assistent: Erkennung an nachgebauten Fremd-Layouts (erfundene Daten) --------

    private static void TemplateValues()
    {
        decimal v;
        Check(Values.TryNumber("1.560,00", out v) && v == 1560.00m, "Betrag mit Tausenderpunkt");
        Check(Values.TryNumber("512,31 €", out v) && v == 512.31m, "Betrag mit Euro-Zeichen");
        Check(Values.TryNumber("EUR 12,5", out v) && v == 12.5m, "Betrag mit EUR davor");
        Check(Values.TryNumber("2", out v) && v == 2m, "Ganze Zahl");
        Check(!Values.TryNumber("inkl.", out v), "Text ist keine Zahl");
        Check(!Values.TryNumber("1,560.00", out v), "Englische Schreibweise wird nicht geraten");
        Check(Values.TryAmount("6.883,11", out v) && v == 6883.11m, "Geldbetrag");
        Check(!Values.TryAmount("19", out v), "Geldbetrag braucht zwei Nachkommastellen");
        Check(Values.LastAmount("Endbetrag EUR 6.883,11", out v) && v == 6883.11m, "Letzter Betrag einer Zeile");
        Check(Values.LastAmount("+ 19 % MwSt 97,34 €", out v) && v == 97.34m, "Betrag hinter Prozentangabe");
        Check(!Values.LastAmount("zahlbar in 14 Tagen", out v), "Zeile ohne Betrag");
        Equal("1.560,00", Values.Amount(1560m), "Betrag formatieren");
        DateTime d;
        Check(Values.TryDate("26.02.2026", out d) && Values.Date(d) == "26.02.2026", "Datum TT.MM.JJJJ");
        Check(Values.TryDate("3.3.26", out d) && Values.Date(d) == "03.03.2026", "Datum kurz");
        Check(Values.TryDate("den 11. Februar 2026", out d) && Values.Date(d) == "11.02.2026", "Datum mit Monatsname");
        Check(!Values.TryDate("02/26", out d), "Monat/Jahr ist kein Datum");
        Check(!Values.TryDate("31.02.2026", out d), "Unmögliches Datum");
        Equal("Stk.", Values.Unit("Stck."), "Einheit Stück");
        Equal("Std.", Values.Unit("Stunden"), "Einheit Stunden");
        Equal("m²", Values.Unit("m²"), "Unbekannte Einheit bleibt");
    }

    private static SourceLine Line(params object[] columnAndText)
    {
        SourceLine line = new SourceLine();
        for (int i = 0; i + 1 < columnAndText.Length; i += 2)
        {
            int column = (int)columnAndText[i];
            line.Add(column, (string)columnAndText[i + 1], "S" + column);
        }
        return line;
    }

    private static string Field(Analysis a, InvoiceField f)
    {
        return a.Get(f).Value;
    }

    private static void TemplateExcelLayout()
    {
        // Excel-Blatt: Briefkopf in Zellen rechts, "RECHNUNG <Nr>", "Datum:", Spalten Pos. | Stück | Bezeichnung | EP | GP
        SourceDocument doc = new SourceDocument();
        doc.FileName = "fremd.xls";
        doc.Excel = true;
        doc.Sheet = "Rechnung";
        doc.Lines.Add(Line(6, "Muster Bau GmbH"));
        doc.Lines.Add(Line(6, "Werkstr. 9"));
        doc.Lines.Add(Line(6, "80999 München"));
        doc.Lines.Add(Line(6, "Telefon 089/123456"));
        doc.Lines.Add(Line(2, "Muster Bau GmbH, Werkstr. 9, 80999 München"));
        doc.Lines.Add(Line(2, "Herr"));
        doc.Lines.Add(Line(2, "Max Beispiel"));
        doc.Lines.Add(Line(2, "Hauptstr. 5"));
        doc.Lines.Add(Line(2, "80331 München"));
        doc.Lines.Add(Line(1, "RECHNUNG 5001", 6, "Datum:", 7, "03.03.2026"));
        doc.Lines.Add(Line(1, "Leistungszeitpunkt 02/26", 6, "Kd.-Nr.:"));
        doc.Lines.Add(Line(1, "Bauvorhaben: Wohnung 2. OG"));
        doc.Lines.Add(Line(1, "Pos.", 2, "Stück", 3, "Bezeichnung", 6, "EP", 7, "GP"));
        doc.Lines.Add(Line(1, "Pos. 1", 2, "1", 3, "Reparatur einer Zimmertür", 6, "512,31 €", 7, "512,31 €"));
        doc.Lines.Add(Line(3, "Größe ca. 500 x 1400 mm"));
        doc.Lines.Add(Line(2, "2 Stck.", 3, "An-/Abfahrtskosten", 6, "inkl."));
        doc.Lines.Add(Line(1, "Pos. 2", 2, "3", 3, "Türgriff", 6, "10,00 €", 7, "30,00 €"));
        doc.Lines.Add(Line(6, "Summe netto", 7, "542,31 €"));
        doc.Lines.Add(Line(6, "+ 19 % MwSt", 7, "103,04 €"));
        doc.Lines.Add(Line(6, "Gesamtbetrag EUR", 7, "645,35 €"));
        doc.Lines.Add(Line(1, "Zahlbar innerhalb von 14 Tagen ohne Abzug."));
        doc.Kept.Add("Bild „Logo“");

        Analysis a = TemplateAnalyzer.Analyze(doc);
        Equal(0, a.Blockers.Count, "Excel-Layout: kein Ausschlussgrund");
        Equal("5001", Field(a, InvoiceField.InvoiceNumber), "Excel-Layout: Rechnungsnummer aus „RECHNUNG 5001“");
        Equal("RECHNUNG", a.Get(InvoiceField.InvoiceNumber).SourceLabel, "Excel-Layout: bisherige Beschriftung der Rechnungsnummer");
        Equal("03.03.2026", Field(a, InvoiceField.InvoiceDate), "Excel-Layout: Datum aus der Nachbarzelle von „Datum:“");
        Equal(MatchStatus.Uncertain, a.Get(InvoiceField.ServiceDate).Status, "Excel-Layout: „02/26“ ist kein Leistungsdatum");
        Equal(MatchStatus.Missing, a.Get(InvoiceField.CustomerNumber).Status, "Excel-Layout: leere Kunden-Nr. fehlt");
        Equal("Herr Max Beispiel", Field(a, InvoiceField.BuyerName), "Excel-Layout: Anrede und Name in einer Zeile");
        Equal(MatchStatus.Uncertain, a.Get(InvoiceField.BuyerName).Status, "Excel-Layout: zusammengezogener Name ist zu prüfen");
        Equal("Hauptstr. 5", Field(a, InvoiceField.BuyerStreet), "Excel-Layout: Straße");
        Equal("80331 München", Field(a, InvoiceField.BuyerCity), "Excel-Layout: Ort des Kunden, nicht des Briefkopfs");
        Equal(MatchStatus.Missing, a.Get(InvoiceField.BuyerEmail).Status, "Excel-Layout: E-Mail des Kunden fehlt");
        Equal(2, a.Items.Count, "Excel-Layout: zwei Positionen");
        Equal("1", a.Items[0].Number, "Excel-Layout: „Pos. 1“ wird zur Zahl");
        Equal("1,00", a.Items[0].Quantity, "Excel-Layout: Menge");
        Equal("Stk.", a.Items[0].Unit, "Excel-Layout: Einheit aus der Spaltenüberschrift „Stück“");
        Equal("512,31", a.Items[0].UnitPrice, "Excel-Layout: Einzelpreis ohne Euro-Zeichen");
        Equal("512,31", a.Items[0].Total, "Excel-Layout: Gesamt ohne Euro-Zeichen");
        Equal("19 %", a.Items[0].VatRate, "Excel-Layout: Steuersatz aus dem Summenblock (genau ein Satz)");
        Equal(3, a.Items[0].Description.Count, "Excel-Layout: Bezeichnung mit Folgezeile und Unterpunkt");
        Equal("inkl. 2 Stck. An-/Abfahrtskosten", a.Items[0].Description[2], "Excel-Layout: Unterpunkt ohne Preis als Text");
        Equal(1, a.Items[0].Notes.Count, "Excel-Layout: Unterpunkt wird als Hinweis gemeldet");
        Equal("30,00", a.Items[1].Total, "Excel-Layout: zweite Position");
        Equal("542,31", Field(a, InvoiceField.NetTotal), "Excel-Layout: Netto");
        Equal("103,04", Field(a, InvoiceField.VatAmount), "Excel-Layout: Umsatzsteuer");
        Equal("645,35", Field(a, InvoiceField.GrossTotal), "Excel-Layout: Gesamtbetrag");
        Equal(MatchStatus.Uncertain, a.Get(InvoiceField.PayableAmount).Status, "Excel-Layout: Zahlbetrag ist nur ein Vorschlag");
        Equal("645,35", Field(a, InvoiceField.PayableAmount), "Excel-Layout: Vorschlag Zahlbetrag");
        Equal(MatchStatus.Found, a.Get(InvoiceField.PaymentTerms).Status, "Excel-Layout: Zahlungsbedingungen");
        Equal(MatchStatus.Missing, a.Get(InvoiceField.DueDate).Status, "Excel-Layout: kein Fälligkeitsdatum");
        Equal(0, a.Warnings.Count, "Excel-Layout: keine Hinweise (Summen stimmen)");
        Equal(2, a.MissingRequired, "Excel-Layout: zwei Pflichtangaben fehlen (Kunden-Nr., E-Mail)");
        Check(a.Convertible, "Excel-Layout: umbaubar");
        string report = AnalysisReport.Text(doc, a);
        Check(report.Contains("Positionen: 2") && report.Contains("FEHLT (Pflicht)") && report.Contains("Bild „Logo“"), "Excel-Layout: Bericht nennt Positionen, Fehlendes und Unverändertes");

        // eine Position geht verloren: Die Summenprüfung meldet es
        doc.Lines.RemoveAt(16);
        Analysis b = TemplateAnalyzer.Analyze(doc);
        Equal(1, b.Items.Count, "Excel-Layout ohne zweite Position");
        Check(b.Warnings.Count == 1 && b.Warnings[0].Contains("passt nicht zum Nettobetrag"), "Abweichung zwischen Positionen und Netto wird gemeldet");
    }

    private static void TemplateWordLayout()
    {
        // Word: Absätze und Tabelle "Pos.-Nr. | Stück | Bezeichnung | EP | GP", Position "001", Bezeichnung in Folgezeilen
        SourceDocument doc = new SourceDocument();
        doc.FileName = "fremd.doc";
        SourceLine head = Line(1, "Briefkopf Muster Fensterbau");
        head.Container = SourceLine.Header;
        doc.Lines.Add(head);
        SourceLine box = Line(1, "Muster Fensterbau GmbH, Telefon 0841/1234, rechnung@muster-fensterbau.example");
        box.Container = "Textfeld 3";
        doc.Lines.Add(box);
        doc.Lines.Add(Line(1, "Beispiel Handel SE & Co. KG"));
        doc.Lines.Add(Line(1, "Industriestr. 2"));
        doc.Lines.Add(Line(1, "86150 Augsburg"));
        doc.Lines.Add(Line(1, "RECHNUNG", 2, "7002"));
        doc.Lines.Add(Line(1, "Datum: 26. Februar 2026"));
        doc.Lines.Add(Line(1, "Kunden-Nr.: 10002"));
        doc.Lines.Add(Line(1, "Projekt Nr.: 231123"));
        SourceLine header = Line(1, "Pos.-Nr.", 2, "Stück", 3, "Bezeichnung", 4, "EP", 5, "GP");
        header.InTable = true;
        doc.Lines.Add(header);
        SourceLine row = Line(1, "001", 2, "1", 4, "5.784,13 €", 5, "5.784,13 €");
        row.InTable = true;
        doc.Lines.Add(row);
        doc.Lines.Add(Line(3, "2-teiliges Fensterelement aus Aluminium"));
        doc.Lines.Add(Line(3, "Elementmaß 1740 mm x 1350 mm"));
        doc.Lines.Add(Line(4, "Gesamtsumme", 5, "5.784,13 €"));
        doc.Lines.Add(Line(4, "MwSt. 19 %", 5, "1.098,98 €"));
        doc.Lines.Add(Line(4, "Endbetrag", 5, "6.883,11 €"));
        doc.Lines.Add(Line(1, "Zahlbar bis zum 12.03.2026 ohne Abzug."));
        doc.Lines.Add(Line(1, "Mit freundlichen Grüßen"));

        Analysis a = TemplateAnalyzer.Analyze(doc);
        Equal(0, a.Blockers.Count, "Word-Layout: kein Ausschlussgrund");
        Equal("7002", Field(a, InvoiceField.InvoiceNumber), "Word-Layout: Rechnungsnummer hinter Tabulator");
        Equal("26.02.2026", Field(a, InvoiceField.InvoiceDate), "Word-Layout: Datum mit Monatsname");
        Equal("10002", Field(a, InvoiceField.CustomerNumber), "Word-Layout: Kunden-Nr.");
        Equal("Beispiel Handel SE & Co. KG", Field(a, InvoiceField.BuyerName), "Word-Layout: Name");
        Equal(MatchStatus.Found, a.Get(InvoiceField.BuyerName).Status, "Word-Layout: Name ist sicher");
        Equal("86150 Augsburg", Field(a, InvoiceField.BuyerCity), "Word-Layout: Ort");
        Equal(MatchStatus.Missing, a.Get(InvoiceField.BuyerEmail).Status, "Word-Layout: E-Mail aus dem Briefkopf ist nicht die des Kunden");
        Equal(1, a.Items.Count, "Word-Layout: eine Position");
        Equal("1", a.Items[0].Number, "Word-Layout: „001“ wird zu 1");
        Equal(2, a.Items[0].Description.Count, "Word-Layout: Bezeichnung aus den Folgezeilen");
        Equal("2-teiliges Fensterelement aus Aluminium", a.Items[0].Description[0], "Word-Layout: erste Zeile der Bezeichnung");
        Equal("5.784,13", a.Items[0].UnitPrice, "Word-Layout: Einzelpreis");
        Check(a.Items[0].Complete, "Word-Layout: Position vollständig");
        Equal("5.784,13", Field(a, InvoiceField.NetTotal), "Word-Layout: „Gesamtsumme“ ist der Nettobetrag");
        Equal("Gesamtsumme", a.Get(InvoiceField.NetTotal).SourceLabel, "Word-Layout: bisherige Beschriftung Netto");
        Equal("1.098,98", Field(a, InvoiceField.VatAmount), "Word-Layout: Umsatzsteuer");
        Equal("6.883,11", Field(a, InvoiceField.GrossTotal), "Word-Layout: „Endbetrag“ ist der Gesamtbetrag");
        Equal("12.03.2026", Field(a, InvoiceField.DueDate), "Word-Layout: Fälligkeit aus „Zahlbar bis zum“");
        Equal(0, a.Warnings.Count, "Word-Layout: keine Hinweise");
        Equal(1, a.MissingRequired, "Word-Layout: nur die E-Mail fehlt");
    }

    private static void TemplateRefusals()
    {
        SourceDocument doc = new SourceDocument();
        doc.FileName = "abschlag.xls";
        doc.Excel = true;
        doc.Lines.Add(Line(1, "2. Abschlagsrechnung Nr. 4862"));
        doc.Lines.Add(Line(1, "Pos.", 2, "Bezeichnung", 3, "Menge", 4, "LS. %", 5, "EP", 6, "GP"));
        doc.Lines.Add(Line(1, "1", 2, "Fassade", 3, "1", 4, "50", 5, "1.000,00", 6, "500,00"));
        doc.Lines.Add(Line(5, "Summe netto", 6, "500,00"));
        doc.Lines.Add(Line(5, "abzgl. 1. Abschlag", 6, "200,00"));
        doc.Lines.Add(Line(1, "Steuerschuldnerschaft des Leistungsempfängers nach § 13b UStG"));
        Analysis a = TemplateAnalyzer.Analyze(doc);
        Equal(4, a.Blockers.Count, "Abschlagsrechnung nach § 13b: vier Ausschlussgründe (Abschlag, § 13b, LS. %, Abzug im Summenblock)");
        Check(!a.Convertible, "Abschlagsrechnung ist nicht umbaubar");
        Check(AnalysisReport.Summary(a).StartsWith("nicht automatisch umbaubar"), "Zusammenfassung nennt die Ablehnung");

        SourceDocument skonto = new SourceDocument();
        skonto.Lines.Add(Line(1, "Rechnung Nr. 12"));
        skonto.Lines.Add(Line(1, "Pos.", 2, "Bezeichnung", 3, "Menge", 4, "Einzelpreis", 5, "Gesamt"));
        skonto.Lines.Add(Line(1, "1", 2, "Wartung", 3, "1", 4, "100,00", 5, "100,00"));
        skonto.Lines.Add(Line(4, "Netto", 5, "100,00"));
        skonto.Lines.Add(Line(4, "abzüglich 2 % Skonto", 5, "2,00"));
        Analysis s = TemplateAnalyzer.Analyze(skonto);
        Equal(1, s.Blockers.Count, "Skonto im Summenblock wird abgelehnt");
        Equal("12", s.Get(InvoiceField.InvoiceNumber).Value, "„Rechnung Nr. 12“");

        SourceDocument terms = new SourceDocument();
        terms.Lines.Add(Line(1, "Rechnungsnummer: 2026-0001"));
        terms.Lines.Add(Line(1, "Pos.", 2, "Bezeichnung", 3, "Menge", 4, "Einzelpreis", 5, "Gesamt"));
        terms.Lines.Add(Line(1, "1", 2, "Wartung", 3, "1", 4, "100,00", 5, "100,00"));
        terms.Lines.Add(Line(4, "Netto", 5, "100,00"));
        terms.Lines.Add(Line(1, "Zahlbar innerhalb von 8 Tagen mit 2 % Skonto, 30 Tage netto."));
        Analysis t = TemplateAnalyzer.Analyze(terms);
        Equal(0, t.Blockers.Count, "Skonto in den Zahlungsbedingungen ist kein Ausschlussgrund");
        Equal("2026-0001", t.Get(InvoiceField.InvoiceNumber).Value, "Standardbeschriftung wird erkannt");

        SourceDocument nothing = new SourceDocument();
        nothing.Lines.Add(Line(1, "Sehr geehrte Damen und Herren,"));
        Analysis n = TemplateAnalyzer.Analyze(nothing);
        Check(!n.Convertible && n.Warnings.Count == 1, "Dokument ohne Positionstabelle ist nicht umbaubar");

        SourceDocument rates = new SourceDocument();
        rates.Lines.Add(Line(1, "Pos.", 2, "Bezeichnung", 3, "Menge", 4, "Einzelpreis", 5, "Gesamt"));
        rates.Lines.Add(Line(1, "1", 2, "Buch", 3, "1", 4, "10,00", 5, "10,00"));
        rates.Lines.Add(Line(1, "2", 2, "Stift", 3, "1", 4, "10,00", 5, "10,00"));
        rates.Lines.Add(Line(4, "Summe netto", 5, "20,00"));
        rates.Lines.Add(Line(4, "MwSt 7 %", 5, "0,70"));
        rates.Lines.Add(Line(4, "MwSt 19 %", 5, "1,90"));
        rates.Lines.Add(Line(4, "Rechnungsbetrag", 5, "22,60"));
        Analysis r = TemplateAnalyzer.Analyze(rates);
        Equal(2, r.VatRates.Count, "Zwei Steuersätze erkannt");
        Equal("", r.Items[0].VatRate, "Bei zwei Steuersätzen wird der Satz je Position nicht geraten");
        Equal("2,60", r.Get(InvoiceField.VatAmount).Value, "Umsatzsteuer gesamt aus beiden Zeilen");
    }

    private static void TemplateLayoutVariants()
    {
        // Muster aus echten Rechnungen (Daten erfunden): Beschriftungen als Kopfzeile mit den Werten darunter,
        // Einheit in einer zweiten Zelle unter "Menge", Nettosumme ohne Beschriftung, Euro-Zeichen in eigener Zelle
        SourceDocument doc = new SourceDocument();
        doc.Excel = true;
        doc.Lines.Add(Line(2, "Frau"));
        doc.Lines.Add(Line(2, "Erika Muster"));
        doc.Lines.Add(Line(2, "Ringweg 3"));
        doc.Lines.Add(Line(2, "90402 Nürnberg"));
        doc.Lines.Add(Line(2, "RECHNUNG", 5, "8001"));
        doc.Lines.Add(Line(2, "Auftrags-Nr.:", 5, "Kunden-Nr.:", 7, "Telefon-Nr.:", 9, "Datum:"));
        doc.Lines.Add(Line(7, "Mobil 0170/000000", 9, "05.05.2026"));
        doc.Lines.Add(Line(2, "Pos.", 3, "Menge", 5, "Bezeichnung", 8, "E.Preis", 9, "Ges.Preis"));
        doc.Lines.Add(Line(2, "Pos. 1", 3, "1,00", 4, "Stck.", 5, "Glasscheibe", 8, "200,00", 9, "200,00 €"));
        doc.Lines.Add(Line(3, "2,00", 4, "Stck.", 5, "Anfahrt"));
        doc.Lines.Add(Line(8, "0,00", 9, "0,00 €"));   // vorbereitete Leerzeile mit Formeln
        doc.Lines.Add(Line(9, "200,00 €"));
        doc.Lines.Add(Line(7, "+19 % MWST", 9, "38,00 €"));
        doc.Lines.Add(Line(7, "Gesamtbetrag", 8, "EUR", 9, "238,00"));
        doc.Lines.Add(Line(2, "Leistungszeitpunkt 05/26"));
        Analysis a = TemplateAnalyzer.Analyze(doc);
        Equal("8001", Field(a, InvoiceField.InvoiceNumber), "Variante: Rechnungsnummer in der übernächsten Zelle");
        Equal("05.05.2026", Field(a, InvoiceField.InvoiceDate), "Variante: Datum steht unter der Beschriftung");
        Equal(MatchStatus.Missing, a.Get(InvoiceField.CustomerNumber).Status, "Variante: Die Nachbar-Beschriftung ist kein Wert der Kunden-Nr.");
        Equal("Frau Erika Muster", Field(a, InvoiceField.BuyerName), "Variante: Name");
        Equal(1, a.Items.Count, "Variante: Die Nettosumme ohne Beschriftung ist keine Position");
        Equal("Stk.", a.Items[0].Unit, "Variante: Einheit aus der zweiten Zelle unter „Menge“");
        Equal("200,00", a.Items[0].Total, "Variante: Spalte „Ges.Preis“");
        Equal("inkl. 2 Stck. Anfahrt", a.Items[0].Description[1], "Variante: Unterpunkt mit Menge und Einheit");
        Equal("200,00", Field(a, InvoiceField.NetTotal), "Variante: Betrag ohne Beschriftung als Nettosumme");
        Equal(MatchStatus.Uncertain, a.Get(InvoiceField.NetTotal).Status, "Variante: angenommene Nettosumme ist zu prüfen");
        Equal("238,00", Field(a, InvoiceField.GrossTotal), "Variante: „EUR“ zwischen Beschriftung und Betrag");
        Equal(MatchStatus.Uncertain, a.Get(InvoiceField.ServiceDate).Status, "Variante: Leistungszeitpunkt unter den Summen wird gefunden");
        Equal(0, a.Warnings.Count, "Variante: Summen stimmen");

        // Word: Anschrift in einer Tabellenzelle mit mehreren Absätzen, Datenzeile mit mehr Zellen als die Kopfzeile,
        // Bezeichnung als eine mehrzeilige Zelle in der Folgezeile
        SourceDocument word = new SourceDocument();
        SourceLine address = Line(1, "Beispiel Markt GmbH\nMarktplatz 1\n\n97070 Würzburg");
        address.InTable = true;
        word.Lines.Add(address);
        word.Lines.Add(Line(1, "Projekt Nr.:", 2, "Ihr Telefon:", 9, "Datum:"));
        word.Lines.Add(Line(1, "4711", 9, "07.07.2026"));
        word.Lines.Add(Line(1, "RECHNUNG   9002"));
        word.Lines.Add(Line(1, "Pos.-Nr.", 2, "Stück", 3, "Bezeichnung", 4, "EP", 5, "GP"));
        word.Lines.Add(Line(1, "001", 2, "1", 4, "1.000,00", 5, "€", 6, "1.000,00", 7, "€"));
        word.Lines.Add(Line(2, "Fensterelement aus Aluminium\nElementmaß 1000 mm x 1200 mm\n1 Stück Fenster DK links\nGlas: klar"));
        word.Lines.Add(Line(1, "Gesamtsumme", 4, "1.000,00", 5, "€"));
        word.Lines.Add(Line(1, "zuzüglich: 19,0% Mehrwertsteuer", 4, "190,00", 5, "€"));
        word.Lines.Add(Line(1, "Endbetrag", 4, "1.190,00", 5, "€"));
        Analysis w = TemplateAnalyzer.Analyze(word);
        Equal("9002", Field(w, InvoiceField.InvoiceNumber), "Word-Variante: „RECHNUNG   9002“");
        Equal("07.07.2026", Field(w, InvoiceField.InvoiceDate), "Word-Variante: Datum unter der Beschriftung");
        Equal("Beispiel Markt GmbH", Field(w, InvoiceField.BuyerName), "Word-Variante: Name aus mehrzeiliger Zelle");
        Equal("Marktplatz 1", Field(w, InvoiceField.BuyerStreet), "Word-Variante: Straße aus mehrzeiliger Zelle");
        Equal("97070 Würzburg", Field(w, InvoiceField.BuyerCity), "Word-Variante: Ort aus mehrzeiliger Zelle");
        Equal(1, w.Items.Count, "Word-Variante: eine Position");
        Equal("1.000,00", w.Items[0].UnitPrice, "Word-Variante: Einzelpreis");
        Equal("1.000,00", w.Items[0].Total, "Word-Variante: Gesamt steht hinter der Zelle mit dem Euro-Zeichen");
        Equal(4, w.Items[0].Description.Count, "Word-Variante: vier Zeilen Bezeichnung");
        Equal("1 Stück Fenster DK links", w.Items[0].Description[2], "Word-Variante: Text, der mit einer Zahl beginnt, bleibt unverändert");
        Equal(0, w.Items[0].Notes.Count, "Word-Variante: kein Unterpunkt erfunden");
        Equal("19 %", w.Items[0].VatRate, "Word-Variante: „19,0%“ wird zu 19 %");
        Equal("1.190,00", Field(w, InvoiceField.GrossTotal), "Word-Variante: Endbetrag");
        Equal(0, w.Warnings.Count, "Word-Variante: Summen stimmen");
    }

    private static void ProfileTests()
    {
        // Antwort des Skripts, das im Fenster die Funktion „Profil testen“ des Dienstes aufruft
        string ok = ProfileTestOutcome.Marker + "\tabc123\nstate\tok\npages\t1\nprofile\tstandard\nclassification\tINVOICE, Geschäftsfall DOMESTIC_STANDARD\n"
            + "plausibility\tbestanden\npositions\t3";
        Equal("abc123", ProfileTestOutcome.MessageId(ok), "Profiltest: Kennung der Nachricht");
        Equal("", ProfileTestOutcome.MessageId("etwas anderes\tabc"), "Profiltest: fremde Nachricht hat keine Kennung");
        Equal("", ProfileTestOutcome.MessageId(null), "Profiltest: leere Nachricht");
        ProfileTestOutcome passed = ProfileTestOutcome.Parse(ok);
        Check(passed.Passed, "Profiltest: bestanden");
        Equal("standard", passed.Profile, "Profiltest: erkanntes Profil");
        Equal("3", passed.Positions, "Profiltest: Positionen");
        Check(passed.Describe().StartsWith("BESTANDEN") && passed.Describe().Contains("Positionen: 3"), "Profiltest: Text bei bestandenem Test");

        ProfileTestOutcome failed = ProfileTestOutcome.Parse(ProfileTestOutcome.Marker + "\tx\nstate\tok\npages\t1\nprofile\tstandard\nplausibility\tbestanden\npositions\t1\n"
            + "problem\tBT-10 Käuferreferenz: Pflichtfeld nicht gefunden\nproblem\tBT-49 Elektronische Adresse des Käufers: Pflichtfeld nicht gefunden");
        Check(!failed.Passed, "Profiltest: fehlendes Pflichtfeld ist nicht bestanden, auch wenn die Plausibilität passt");
        Equal(2, failed.Problems.Count, "Profiltest: zwei Angaben mit Fehler");
        Check(failed.Describe().StartsWith("NICHT BESTANDEN") && failed.Describe().Contains("BT-49"), "Profiltest: Text nennt die fehlenden Angaben");

        ProfileTestOutcome sums = ProfileTestOutcome.Parse(ProfileTestOutcome.Marker + "\tx\nstate\tok\nplausibility\tnicht bestanden\nissue\tLINE_SUM: Summe der Positionen weicht ab");
        Check(!sums.Passed && sums.Issues.Count == 1, "Profiltest: Plausibilität nicht bestanden");
        Check(!ProfileTestOutcome.Parse(ProfileTestOutcome.Marker + "\tx\nstate\tok\nerror\tBitte eine PDF-Datei auswählen.").Passed, "Profiltest: Meldung des Dienstes");
        Check(ProfileTestOutcome.Parse(ProfileTestOutcome.Marker + "\tx\nstate\tlogin").Describe().Contains("nicht angemeldet"), "Profiltest: nicht angemeldet");
        Check(ProfileTestOutcome.Failed(ProfileTestOutcome.Offline, "").Describe().Contains("nicht mit dem Dienst verbunden"), "Profiltest: keine Verbindung");
        ProfileTestOutcome tenants = ProfileTestOutcome.Parse(ProfileTestOutcome.Marker + "\tx\nstate\ttenants\ntenant\thofmann-it|Hofmann IT\ntenant\tzwei|Zweite Firma | Nord");
        Equal(2, tenants.TenantList.Count, "Profiltest: Mandantenliste");
        Equal("hofmann-it", tenants.TenantList[0].Key, "Profiltest: Kennung des Mandanten");
        Equal("Zweite Firma | Nord", tenants.TenantList[1].Value, "Profiltest: Name mit Trennzeichen");

        Equal("'a\\'b\\\\c\\n\\u003cscript\\u003e'", ProfileTestScript.JsString("a'b\\c\n<script>"), "Profiltest: Zeichenkette sicher in das Skript einsetzen");
        Equal("''", ProfileTestScript.JsString(null), "Profiltest: leere Zeichenkette");
        string script = ProfileTestScript.Test("id1", new byte[] { 0x25, 0x50, 0x44, 0x46 }, "Rechnung 'Test'.pdf", "hofmann-it");
        Check(script.Contains("atob('JVBERg==')") && script.Contains("'Rechnung \\'Test\\'.pdf'") && script.Contains("fetch('/profile/test'")
            && script.Contains("'_csrf'") && script.Contains("'HX-Request'"), "Profiltest: Skript lädt das PDF mit Sitzung und CSRF-Schutz hoch");
        Check(!script.Contains("http://") && !script.Contains("https://"), "Profiltest: Skript spricht nur die verbundene Adresse an");
        Check(ProfileTestScript.ListTenants("id2").Contains("state\\ttenants"), "Profiltest: Skript für die Mandantenliste");
        Equal(@"C:\t\a.pdf", CommandLine.Parse(new string[] { "/selbsttest", @"C:\t", "/testpdf", @"C:\t\a.pdf" }).SelfTestPdf, "PDF für den Diagnoselauf des Profiltests");
    }

    private static void TemplateExcelPlan()
    {
        // Bauplan des neuen Excel-Blatts aus einer erkannten Rechnung (Daten erfunden)
        SourceDocument doc = new SourceDocument();
        doc.Excel = true;
        doc.Lines.Add(Line(6, "Muster Glas GmbH"));
        doc.Lines.Add(Line(6, "Telefon 0911/000000"));
        doc.Lines.Add(Line(2, "Frau"));
        doc.Lines.Add(Line(2, "Erika Muster"));
        doc.Lines.Add(Line(2, "Ringweg 3"));
        doc.Lines.Add(Line(2, "90402 Nürnberg"));
        doc.Lines.Add(Line(2, "RECHNUNG", 5, "8001"));
        doc.Lines.Add(Line(2, "Auftrags-Nr.:", 5, "Kunden-Nr.:", 7, "Telefon-Nr.:", 9, "Datum:"));
        doc.Lines.Add(Line(7, "Mobil 0170/000000", 9, "05.05.2026"));
        doc.Lines.Add(Line(2, "Bauvorhaben:", 4, "Wohnung 2. OG"));
        doc.Lines.Add(Line(2, "Pos.", 3, "Menge", 5, "Bezeichnung", 8, "E.Preis", 9, "Ges.Preis"));
        doc.Lines.Add(Line(2, "Pos. 1", 3, "1,00", 4, "Stck.", 5, "Glasscheibe", 8, "200,00", 9, "200,00 €"));
        doc.Lines.Add(Line(3, "2,00", 4, "Stck.", 5, "Anfahrt"));
        doc.Lines.Add(Line(9, "200,00 €"));
        doc.Lines.Add(Line(7, "+19 % MWST", 9, "38,00 €"));
        doc.Lines.Add(Line(7, "Gesamtbetrag", 8, "EUR", 9, "238,00"));
        doc.Lines.Add(Line(2, "Mit freundlichen Grüßen"));
        doc.Lines.Add(Line(2, "Leistungszeitpunkt 05/26"));
        for (int i = 0; i < doc.Lines.Count; i++)
        {
            doc.Lines[i].Start = i * 10;
            doc.Lines[i].End = i * 10 + 9;
        }
        Analysis a = TemplateAnalyzer.Analyze(doc);
        Equal(20, a.RegionStart, "Plan: Der Rechnungsteil beginnt mit der ersten Zeile der Anschrift (Briefkopf davor bleibt)");
        Equal(159, a.RegionEnd, "Plan: Der Rechnungsteil endet mit der letzten Summenzeile (Schlusstexte danach bleiben)");
        Equal(0, a.ClosingInsideRegion, "Plan: Schlusstexte hinter dem Rechnungsteil werden in Word nicht neu gesetzt");
        Equal(2, a.LetterheadCells.Count, "Plan: Zellen über der Anschrift sind Briefkopf");
        Equal(2, a.ExtraLines.Count, "Plan: zwei Zusatzzeilen über der Tabelle (leere Beschriftung „Auftrags-Nr.:“ entfällt)");
        Equal("Telefon-Nr.: Mobil 0170/000000", a.ExtraLines[0], "Plan: Beschriftung mit dem Wert darunter");
        Equal("Bauvorhaben: Wohnung 2. OG", a.ExtraLines[1], "Plan: Beschriftung mit dem Wert daneben");
        Equal(2, a.ClosingLines.Count, "Plan: zwei Schlusszeilen (Gruß, Leistungszeitpunkt als Text)");

        FieldMatch customer = a.Get(InvoiceField.CustomerNumber);
        customer.Enter(" 10001 ");
        Check(customer.Status == MatchStatus.Found && customer.Value == "10001" && customer.Place.Length == 0, "Plan: eingetragene Kunden-Nr. hat keine Fundstelle");
        a.Get(InvoiceField.BuyerEmail).Enter("kunde@beispiel.example");
        FieldMatch number = a.Get(InvoiceField.InvoiceNumber);
        string place = number.Place;
        number.Enter("8001-A");
        Equal(place, number.Place, "Plan: korrigierter Wert behält seine Fundstelle");
        Equal(0, a.MissingRequired, "Plan: nach der Eingabe fehlt keine Pflichtangabe");

        ExcelPlan p = ExcelLayout.Build(a);
        Equal("Frau Erika Muster", p.Find("B9").Text, "Plan: Name in Zeile 9 (150 pt vom Blattrand)");
        Equal(Mark.Yellow, p.Find("B9").Mark, "Plan: zusammengezogener Name ist gelb");
        Equal("Ringweg 3", p.Find("B10").Text, "Plan: Straße in Zeile 10");
        Equal("90402 Nürnberg", p.Find("B11").Text, "Plan: Ort in Zeile 11");
        Equal("E-Mail: kunde@beispiel.example", p.Find("B12").Text, "Plan: E-Mail in Zeile 12");
        Equal(Mark.Red, p.Find("B12").Mark, "Plan: neu eingetragene E-Mail ist rot (fehlte bisher)");
        Equal("Rechnungsnummer:", p.Find("C17:F17").Text, "Plan: Beschriftung Rechnungsnummer");
        Equal("8001-A", p.Find("G17:H17").Text, "Plan: Wert Rechnungsnummer");
        Equal(Mark.Yellow, p.Find("C17:F17").Mark, "Plan: umbenannte Beschriftung ist gelb");
        Equal("Rechnungsdatum:", p.Find("C18:F18").Text, "Plan: Rechnungsdatum folgt");
        Equal("Kunden-Nr.:", p.Find("C19:F19").Text, "Plan: ohne gültiges Leistungsdatum folgt die Kunden-Nr.");
        Equal(Mark.Red, p.Find("C19:F19").Mark, "Plan: neu eingetragene Kunden-Nr. ist rot");
        Equal("Telefon-Nr.: Mobil 0170/000000", p.Find("B21:H21").Text, "Plan: Zusatzzeile über der Tabelle");
        Equal(24, p.HeaderRow, "Plan: Kopfzeile der Tabelle");
        Equal("Bezeichnung", p.Find("B24").Text, "Plan: Spaltenkopf");
        Equal("1", p.Find("A25").Text, "Plan: Positionsnummer als Zahl");
        Check(p.Find("C25").HasNumber && p.Find("C25").Number == 1m, "Plan: Menge als Zahl");
        Equal("Stk.", p.Find("E25").Text, "Plan: Einheit");
        Check(p.Find("F25").HasNumber && p.Find("F25").Number == 200m, "Plan: Einzelpreis als Zahl");
        Equal("19 %", p.Find("G25").Text, "Plan: Steuersatz der Position");
        Equal("=ROUND(C25*F25,2)", p.Find("H25").Formula, "Plan: Gesamt = RUNDEN(Menge × Einzelpreis; 2)");
        Equal("inkl. 2 Stck. Anfahrt", p.Find("B26").Text, "Plan: Unterpunkt in der Folgezeile");
        Equal(Mark.Yellow, p.Find("B26").Mark, "Plan: Unterpunkt ist gelb");
        Equal(27, p.SpareRow, "Plan: Leerzeile für weitere Positionen");
        Equal("=SUM(H25:H27)", p.Find("H29").Formula, "Plan: Summe netto schließt die Leerzeile ein");
        Equal("Netto 19 %:", p.Find("E30:G30").Text, "Plan: Steuerbasis je Satz");
        Equal("=ROUND(H30*0.19,2)", p.Find("H31").Formula, "Plan: USt = RUNDEN(Netto × Satz; 2)");
        Equal("=H29", p.Find("H32").Formula, "Plan: Gesamt netto");
        Equal("=H31", p.Find("H33").Formula, "Plan: USt gesamt");
        Equal("=H32+H33", p.Find("H34").Formula, "Plan: Gesamtbetrag");
        Equal(34, p.GrossRow, "Plan: Zeile des Gesamtbetrags");
        Equal("=H34", p.Find("H35").Formula, "Plan: Zahlbetrag");
        Equal(Mark.Red, p.Find("E35:G35").Mark, "Plan: neue Pflichtzeile Zahlbetrag ist rot");
        Equal("Mit freundlichen Grüßen", p.Find("B37:H37").Text, "Plan: Schlusstext unter dem Summenblock");
        Equal(39, p.LastRow, "Plan: letzte Zeile");
        Check(p.Notes.Count == 2 && p.Notes[0].Contains("Leistungsdatum") && p.Notes[1].Contains("Zahlungsbedingungen"), "Plan: Hinweise auf Leistungsdatum und Zahlungsbedingungen");

        // dieselbe Rechnung als Bauplan für Word: Beträge werden übernommen, nichts wird gerechnet
        WordPlan word = WordLayout.Build(a);
        Equal(4, word.Address.Count, "Word-Plan: vier Zeilen im Anschriftenfeld");
        Equal("Frau Erika Muster", word.Address[0].Line, "Word-Plan: Name in der ersten Zeile");
        Equal("90402 Nürnberg", word.Address[2].Line, "Word-Plan: Ort in der dritten Zeile");
        Equal("E-Mail: kunde@beispiel.example", word.Address[3].Line, "Word-Plan: E-Mail-Zeile");
        Equal(Mark.Red, word.Address[3].Mark, "Word-Plan: neu eingetragene E-Mail ist rot");
        Equal(3, word.Info.Count, "Word-Plan: drei Angaben zur Rechnung");
        Equal("Rechnungsnummer: 8001-A", word.Info[0].Line, "Word-Plan: Beschriftung und Wert in einer Zeile");
        Equal("Kunden-Nr.: 10001", word.Info[2].Line, "Word-Plan: Kunden-Nr.");
        Equal(2, word.Extras.Count, "Word-Plan: Zusatzzeilen");
        Equal(1, word.Items.Count, "Word-Plan: eine Position");
        Equal("Glasscheibe\ninkl. 2 Stck. Anfahrt", word.Items[0][1].Value, "Word-Plan: mehrzeilige Bezeichnung in einer Zelle");
        Equal("1,00", word.Items[0][2].Value, "Word-Plan: Menge");
        Equal("200,00", word.Items[0][6].Value, "Word-Plan: Betrag der Position wird übernommen");
        Equal(7, word.Totals.Count, "Word-Plan: sieben Summenzeilen bei einem Steuersatz");
        Equal("Summe netto:", word.Totals[0].Label, "Word-Plan: erste Summenzeile");
        Equal("200,00", word.Totals[0].Value, "Word-Plan: Nettobetrag der bisherigen Rechnung");
        Equal("USt 19 %:", word.Totals[2].Label, "Word-Plan: Steuerbetrag je Satz");
        Equal("38,00", word.Totals[2].Value, "Word-Plan: Umsatzsteuer der bisherigen Rechnung");
        Equal("238,00", word.Totals[6].Value, "Word-Plan: Zahlbetrag als Vorschlag gleich dem Gesamtbetrag");
        Equal(Mark.Red, word.Totals[6].Mark, "Word-Plan: neue Pflichtzeile Zahlbetrag ist rot");
        Check(word.Payment == null && word.Closing.Count == 0, "Word-Plan: keine Zahlungsbedingungen, Schlusstexte bleiben im Dokument");
        Check(word.Notes.Exists(delegate(string n) { return n.Contains("nicht gerechnet"); }), "Word-Plan: Hinweis, dass Word nicht rechnet");

        // zwei Steuersätze: nichts wird geraten
        SourceDocument rates = new SourceDocument();
        rates.Excel = true;
        rates.Lines.Add(Line(1, "Rechnung Nr. 5"));
        rates.Lines.Add(Line(1, "Pos.", 2, "Bezeichnung", 3, "Menge", 4, "Einzelpreis", 5, "Gesamt"));
        rates.Lines.Add(Line(1, "1", 2, "Buch", 3, "1", 4, "10,00", 5, "10,00"));
        rates.Lines.Add(Line(1, "2", 2, "Stift", 3, "1", 4, "10,00", 5, "12,00"));
        rates.Lines.Add(Line(4, "Summe netto", 5, "22,00"));
        rates.Lines.Add(Line(4, "MwSt 7 %", 5, "0,70"));
        rates.Lines.Add(Line(4, "MwSt 19 %", 5, "2,28"));
        rates.Lines.Add(Line(4, "Rechnungsbetrag", 5, "24,98"));
        rates.Lines.Add(Line(1, "Zahlbar bis zum 01.06.2026 ohne Abzug."));
        ExcelPlan r = ExcelLayout.Build(TemplateAnalyzer.Analyze(rates));
        int net = r.NetRow;
        Equal("Gesamt netto:", r.Find("E" + (net + 1) + ":G" + (net + 1)).Text, "Zwei Sätze: keine Zeilen je Steuersatz");
        CellOp vat = r.Find("H" + (net + 2));
        Check(vat.Formula.Length == 0 && vat.HasNumber && vat.Number == 2.98m && vat.Mark == Mark.Red, "Zwei Sätze: Umsatzsteuer als fester, rot markierter Betrag");
        Equal(Mark.Red, r.Find("G" + r.FirstItemRow).Mark, "Zwei Sätze: Steuersatz der Position ist rot und leer");
        Equal(Mark.Red, r.Find("H" + (r.FirstItemRow + 1)).Mark, "Menge × Preis weicht vom bisherigen Betrag ab: rot");
        CellOp terms = r.Find("C" + (r.GrossRow + 3) + ":H" + (r.GrossRow + 4));
        Check(terms != null && terms.Text.EndsWith(", zahlbar bis 01.06.2026"), "Zahlungsbedingungen bekommen „zahlbar bis“ mit dem erkannten Datum");

        string dir = Path.Combine(Path.GetTempPath(), "erechnung-vorlagen-test-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        try
        {
            OutputNames first = OutputNames.For(dir, "Rechnung 1.xls", ".xlsx");
            Equal("Rechnung 1_E-Rechnung.xlsx", Path.GetFileName(first.Workbook), "Dateiname der umgebauten Rechnung");
            Equal("Rechnung 1_E-Rechnung_markiert.xlsx", Path.GetFileName(first.Marked), "Dateiname der markierten Fassung");
            Equal("Rechnung 1_E-Rechnung_Test.pdf", Path.GetFileName(first.Pdf), "Dateiname des Test-PDF");
            File.WriteAllText(first.Pdf, "x");
            OutputNames second = OutputNames.For(dir, "Rechnung 1.xls", ".xlsx");
            Equal("Rechnung 1_E-Rechnung (2).xlsx", Path.GetFileName(second.Workbook), "Vorhandene Dateien werden nicht überschrieben");
            Equal("Rechnung 1_E-Rechnung_Test (2).pdf", Path.GetFileName(second.Pdf), "Alle drei Namen bekommen dieselbe Nummer");
        }
        finally
        {
            Directory.Delete(dir, true);
        }
    }
}
