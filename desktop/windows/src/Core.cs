// Kernlogik der Verwaltungsanwendung ohne Fenster (ADR 0015, Phase C): Einstellungen, Zieladresse,
// Fingerabdruck des Serverzertifikats. Diese Datei wird auch von den Tests (tests/Tests.cs) übersetzt.
// Sprachstand C# 5 (Compiler aus dem .NET Framework von Windows).
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.RegularExpressions;

namespace HofmannIT.ERechnung.Verwaltung
{
    /// <summary>
    /// Einstellungen je Windows-Benutzer (%APPDATA%\PDF-zu-ERechnung\verwaltung.ini).
    /// Enthält nie Passwörter: nur Serveradresse, bestätigter Fingerabdruck und Fensterlage.
    /// </summary>
    public sealed class AppSettings
    {
        public const string ModeLocal = "lokal";
        public const string ModeServer = "server";

        /// <summary>Leer = noch nie verbunden (erster Start).</summary>
        public string Mode = "";
        public string Host = "";
        public int Port = 8443;
        public int LocalPort = 8080;
        /// <summary>SHA-256 des bestätigten Serverzertifikats, 64 Hex-Zeichen klein, ohne Trenner.</summary>
        public string Fingerprint = "";
        public int WindowX = -1;
        public int WindowY = -1;
        public int WindowWidth = 1280;
        public int WindowHeight = 860;
        public bool Maximized = false;
        public bool TrayHintShown = false;

        public static string DefaultPath()
        {
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PDF-zu-ERechnung", "verwaltung.ini");
        }

        public bool IsServer
        {
            get { return Mode == ModeServer; }
        }

        public static AppSettings Parse(string text)
        {
            AppSettings s = new AppSettings();
            if (text == null)
            {
                return s;
            }
            foreach (string raw in text.Split('\n'))
            {
                string line = raw.Trim();
                int eq = line.IndexOf('=');
                if (line.Length == 0 || line[0] == '#' || line[0] == ';' || eq <= 0)
                {
                    continue;
                }
                string key = line.Substring(0, eq).Trim().ToLowerInvariant();
                string value = line.Substring(eq + 1).Trim();
                switch (key)
                {
                    case "modus":
                        s.Mode = value == ModeServer ? ModeServer : (value == ModeLocal ? ModeLocal : "");
                        break;
                    case "server":
                        s.Host = Target.ValidHost(value) ? value : "";
                        break;
                    case "port":
                        s.Port = Int(value, 8443, 1, 65535);
                        break;
                    case "lokalerport":
                        s.LocalPort = Int(value, 8080, 1, 65535);
                        break;
                    case "fingerabdruck":
                        s.Fingerprint = Fingerprints.Normalize(value);
                        break;
                    case "fensterx":
                        s.WindowX = Int(value, -1, -32000, 32000);
                        break;
                    case "fenstery":
                        s.WindowY = Int(value, -1, -32000, 32000);
                        break;
                    case "fensterbreite":
                        s.WindowWidth = Int(value, 1280, 640, 16000);
                        break;
                    case "fensterhoehe":
                        s.WindowHeight = Int(value, 860, 480, 16000);
                        break;
                    case "maximiert":
                        s.Maximized = value == "ja";
                        break;
                    case "hinweisinfobereich":
                        s.TrayHintShown = value == "ja";
                        break;
                }
            }
            // Ein Server ohne Namen ist keine gültige Verbindung: wie erster Start behandeln
            if (s.Mode == ModeServer && s.Host.Length == 0)
            {
                s.Mode = "";
            }
            return s;
        }

        public string Serialize()
        {
            StringBuilder sb = new StringBuilder();
            sb.Append("# PDF-zu-E-Rechnung Verwaltung - Einstellungen dieses Windows-Benutzers.\r\n");
            sb.Append("# Hier stehen keine Passwoerter; die Anmeldung erfolgt in der Anwendung.\r\n");
            sb.Append("modus=").Append(Mode).Append("\r\n");
            sb.Append("server=").Append(Host).Append("\r\n");
            sb.Append("port=").Append(Port.ToString(CultureInfo.InvariantCulture)).Append("\r\n");
            sb.Append("lokalerport=").Append(LocalPort.ToString(CultureInfo.InvariantCulture)).Append("\r\n");
            sb.Append("fingerabdruck=").Append(Fingerprint).Append("\r\n");
            sb.Append("fensterx=").Append(WindowX.ToString(CultureInfo.InvariantCulture)).Append("\r\n");
            sb.Append("fenstery=").Append(WindowY.ToString(CultureInfo.InvariantCulture)).Append("\r\n");
            sb.Append("fensterbreite=").Append(WindowWidth.ToString(CultureInfo.InvariantCulture)).Append("\r\n");
            sb.Append("fensterhoehe=").Append(WindowHeight.ToString(CultureInfo.InvariantCulture)).Append("\r\n");
            sb.Append("maximiert=").Append(Maximized ? "ja" : "nein").Append("\r\n");
            sb.Append("hinweisinfobereich=").Append(TrayHintShown ? "ja" : "nein").Append("\r\n");
            return sb.ToString();
        }

        public static AppSettings Load(string path)
        {
            try
            {
                return File.Exists(path) ? Parse(File.ReadAllText(path, Encoding.UTF8)) : new AppSettings();
            }
            catch (IOException)
            {
                return new AppSettings();
            }
            catch (UnauthorizedAccessException)
            {
                return new AppSettings();
            }
        }

        public void Save(string path)
        {
            string dir = Path.GetDirectoryName(path);
            if (!string.IsNullOrEmpty(dir))
            {
                Directory.CreateDirectory(dir);
            }
            File.WriteAllText(path, Serialize(), new UTF8Encoding(false));
        }

        private static int Int(string value, int fallback, int min, int max)
        {
            int n;
            if (!int.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out n) || n < min || n > max)
            {
                return fallback;
            }
            return n;
        }
    }

    /// <summary>Zieladresse der Oberfläche und die Regel, welche Adressen im Fenster bleiben dürfen.</summary>
    public sealed class Target
    {
        private static readonly Regex HostPattern = new Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$", RegexOptions.CultureInvariant);

        public readonly bool Secure;
        public readonly string Host;
        public readonly int Port;

        private Target(bool secure, string host, int port)
        {
            Secure = secure;
            Host = host;
            Port = port;
        }

        /// <summary>Lokal: unverschlüsselt über die Loopback-Adresse; Server: immer HTTPS.</summary>
        public static Target From(AppSettings s)
        {
            return s.IsServer ? new Target(true, s.Host, s.Port) : new Target(false, "localhost", s.LocalPort);
        }

        public Uri BaseUri
        {
            get { return new Uri((Secure ? "https://" : "http://") + Host + ":" + Port.ToString(CultureInfo.InvariantCulture) + "/"); }
        }

        public string Display
        {
            get { return Secure ? Host + ":" + Port.ToString(CultureInfo.InvariantCulture) : "Dieser Computer"; }
        }

        /// <summary>Rechnername oder IPv4-Adresse, ohne Schema, Pfad, Benutzerangaben oder Leerzeichen.</summary>
        public static bool ValidHost(string host)
        {
            return host != null && HostPattern.IsMatch(host) && host.IndexOf("..", StringComparison.Ordinal) < 0;
        }

        /// <summary>Gehört die Adresse zum verbundenen Dienst (gleiches Schema, gleicher Rechner, gleicher Port)?</summary>
        public bool IsInternal(string uri)
        {
            Uri u;
            if (string.IsNullOrEmpty(uri) || !Uri.TryCreate(uri, UriKind.Absolute, out u))
            {
                return false;
            }
            Uri b = BaseUri;
            return string.Equals(u.Scheme, b.Scheme, StringComparison.OrdinalIgnoreCase)
                && string.Equals(u.Host, b.Host, StringComparison.OrdinalIgnoreCase)
                && u.Port == b.Port
                && string.IsNullOrEmpty(u.UserInfo);
        }

        /// <summary>Fremde Adresse, die im Standardbrowser geöffnet werden darf (nur http/https).</summary>
        public static bool IsExternalWeb(string uri)
        {
            Uri u;
            if (string.IsNullOrEmpty(uri) || !Uri.TryCreate(uri, UriKind.Absolute, out u))
            {
                return false;
            }
            return u.Scheme == Uri.UriSchemeHttp || u.Scheme == Uri.UriSchemeHttps;
        }
    }

    /// <summary>SHA-256-Fingerabdruck eines Zertifikats, wie ihn der Dienst unter Verwaltung → Netzzugriff anzeigt.</summary>
    public static class Fingerprints
    {
        /// <summary>Entfernt Trenner und Leerzeichen; Ergebnis 64 Hex-Zeichen klein oder leer bei ungültiger Eingabe.</summary>
        public static string Normalize(string value)
        {
            if (value == null)
            {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            foreach (char c in value)
            {
                if (Uri.IsHexDigit(c))
                {
                    sb.Append(char.ToLowerInvariant(c));
                }
                else if (c != ':' && c != ' ' && c != '-')
                {
                    return "";
                }
            }
            return sb.Length == 64 ? sb.ToString() : "";
        }

        /// <summary>Zweiergruppen mit Doppelpunkt in Großbuchstaben.</summary>
        public static string Format(string hex)
        {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hex.Length; i += 2)
            {
                if (i > 0)
                {
                    sb.Append(':');
                }
                sb.Append(hex.Substring(i, Math.Min(2, hex.Length - i)).ToUpperInvariant());
            }
            return sb.ToString();
        }

        /// <summary>Für Dialoge: zwei Zeilen zu je 16 Gruppen.</summary>
        public static string FormatTwoLines(string hex)
        {
            string f = Format(hex);
            return f.Length == 95 ? f.Substring(0, 47) + "\r\n" + f.Substring(48) : f;
        }

        public static string Of(byte[] derEncodedCertificate)
        {
            using (SHA256 sha = SHA256.Create())
            {
                byte[] hash = sha.ComputeHash(derEncodedCertificate);
                StringBuilder sb = new StringBuilder(64);
                foreach (byte b in hash)
                {
                    sb.Append(b.ToString("x2", CultureInfo.InvariantCulture));
                }
                return sb.ToString();
            }
        }

        public static string Of(X509Certificate certificate)
        {
            return Of(certificate.GetRawCertData());
        }

        /// <summary>Vergleich ohne Rücksicht auf Schreibweise; ein leerer bestätigter Wert passt nie.</summary>
        public static bool Matches(string confirmed, string presented)
        {
            string a = Normalize(confirmed);
            string b = Normalize(presented);
            return a.Length == 64 && a == b;
        }
    }

    /// <summary>Ergebnis der Zertifikatsabfrage beim Server.</summary>
    public sealed class ProbeResult
    {
        public string Fingerprint = "";
        public string Subject = "";
        public DateTime NotAfter;
        /// <summary>Windows vertraut dem Zertifikat (Kette und Name passen) – z. B. Zertifikat der Firmen-CA.</summary>
        public bool TrustedByWindows;
    }

    /// <summary>
    /// Liest das Zertifikat, das der Server vorzeigt, ohne ihm zu vertrauen: es werden keine Daten
    /// gesendet. Ob die Verbindung genutzt wird, entscheidet danach der Vergleich des Fingerabdrucks.
    /// </summary>
    public static class CertificateProbe
    {
        public static ProbeResult Fetch(string host, int port, int timeoutMillis)
        {
            ProbeResult result = new ProbeResult();
            using (TcpClient client = new TcpClient())
            {
                IAsyncResult pending = client.BeginConnect(host, port, null, null);
                if (!pending.AsyncWaitHandle.WaitOne(timeoutMillis))
                {
                    throw new TimeoutException("Keine Antwort von " + host + ":" + port.ToString(CultureInfo.InvariantCulture));
                }
                client.EndConnect(pending);
                client.ReceiveTimeout = timeoutMillis;
                client.SendTimeout = timeoutMillis;
                RemoteCertificateValidationCallback capture = delegate(object sender, X509Certificate certificate, X509Chain chain, SslPolicyErrors errors)
                {
                    if (certificate != null)
                    {
                        X509Certificate2 c2 = new X509Certificate2(certificate);
                        result.Fingerprint = Fingerprints.Of(c2);
                        result.Subject = c2.Subject;
                        result.NotAfter = c2.NotAfter;
                        result.TrustedByWindows = errors == SslPolicyErrors.None;
                    }
                    // Nur lesen: der Handshake wird abgeschlossen, danach wird die Verbindung sofort geschlossen
                    return true;
                };
                using (SslStream ssl = new SslStream(client.GetStream(), false, capture))
                {
                    ssl.AuthenticateAsClient(host, null, SslProtocols.Tls12 | (SslProtocols)12288 /* Tls13 */, false);
                }
            }
            if (result.Fingerprint.Length != 64)
            {
                throw new InvalidOperationException("Der Server hat kein Zertifikat vorgezeigt");
            }
            return result;
        }
    }

    /// <summary>Befehlszeile: /tray, /einstellungen &lt;Datei&gt;, /selbsttest &lt;Ordner&gt;, /vorlagentest &lt;Ordner&gt; (Diagnose).</summary>
    public sealed class CommandLine
    {
        public bool StartInTray;
        public string SettingsPath = "";
        public string SelfTestDirectory = "";
        /// <summary>Vorlagen-Assistent ohne Fenster: alle Word-/Excel-Dateien dieses Ordners auswerten (Diagnose, Tests).</summary>
        public string TemplateTestDirectory = "";
        /// <summary>Nur mit /selbsttest: dieses PDF über das Fenster gegen das Profil prüfen (Diagnose des Profiltests).</summary>
        public string SelfTestPdf = "";
        public readonly List<string> Unknown = new List<string>();

        public static CommandLine Parse(string[] args)
        {
            CommandLine c = new CommandLine();
            for (int i = 0; i < args.Length; i++)
            {
                string a = args[i].ToLowerInvariant().Replace("--", "/").Replace('-', '/');
                if (a == "/tray")
                {
                    c.StartInTray = true;
                }
                else if (a == "/einstellungen" && i + 1 < args.Length)
                {
                    c.SettingsPath = args[++i];
                }
                else if (a == "/selbsttest" && i + 1 < args.Length)
                {
                    c.SelfTestDirectory = args[++i];
                }
                else if (a == "/vorlagentest" && i + 1 < args.Length)
                {
                    c.TemplateTestDirectory = args[++i];
                }
                else if (a == "/testpdf" && i + 1 < args.Length)
                {
                    c.SelfTestPdf = args[++i];
                }
                else
                {
                    c.Unknown.Add(args[i]);
                }
            }
            return c;
        }
    }
}
