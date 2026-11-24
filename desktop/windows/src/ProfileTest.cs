// Vorlagen-Assistent (ADR 0016, Phase 4): prüft ein Test-PDF mit der vorhandenen Funktion
// "Profil testen" des Dienstes – in der angemeldeten Sitzung des Fensters, ohne eigenen Zugang.
// Hier: das Skript, das im Fenster läuft, und die Auswertung seiner Antwort (ohne Fenster, testbar).
// Der Dienst speichert bei diesem Test nichts (wie beim Test von Hand).
using System;
using System.Collections.Generic;
using System.Text;

namespace HofmannIT.ERechnung.Verwaltung
{
    public sealed class ProfileTestOutcome
    {
        public const string Marker = "erechnung-profiltest";
        public const string Ok = "ok";
        public const string Tenants = "tenants";
        public const string Login = "login";
        public const string Unavailable = "unavailable";
        public const string Offline = "offline";

        public string State = "";
        public string Error = "";
        public string Pages = "";
        public string Profile = "";
        public string Classification = "";
        public string Plausibility = "";
        public string Positions = "";
        public readonly List<string> Issues = new List<string>();
        public readonly List<string> Problems = new List<string>();
        /// <summary>Mandanten als Paare aus Kennung und Name.</summary>
        public readonly List<KeyValuePair<string, string>> TenantList = new List<KeyValuePair<string, string>>();

        public static ProfileTestOutcome Failed(string state, string error)
        {
            ProfileTestOutcome o = new ProfileTestOutcome();
            o.State = state;
            o.Error = error;
            return o;
        }

        /// <summary>Kennung einer Nachricht des Skripts; leer, wenn die Nachricht nicht vom Profiltest stammt.</summary>
        public static string MessageId(string message)
        {
            if (message == null) { return ""; }
            string first = message.Split('\n')[0];
            string[] parts = first.Split('\t');
            return parts.Length == 2 && parts[0] == Marker ? parts[1] : "";
        }

        public static ProfileTestOutcome Parse(string message)
        {
            ProfileTestOutcome o = new ProfileTestOutcome();
            string[] lines = (message == null ? "" : message).Split('\n');
            for (int i = 1; i < lines.Length; i++)
            {
                int tab = lines[i].IndexOf('\t');
                if (tab < 0) { continue; }
                string key = lines[i].Substring(0, tab);
                string value = lines[i].Substring(tab + 1).Trim();
                switch (key)
                {
                    case "state": o.State = value; break;
                    case "error": o.Error = value; break;
                    case "pages": o.Pages = value; break;
                    case "profile": o.Profile = value; break;
                    case "classification": o.Classification = value; break;
                    case "plausibility": o.Plausibility = value; break;
                    case "positions": o.Positions = value; break;
                    case "issue": o.Issues.Add(value); break;
                    case "problem": o.Problems.Add(value); break;
                    case "tenant":
                        int bar = value.IndexOf('|');
                        if (bar > 0) { o.TenantList.Add(new KeyValuePair<string, string>(value.Substring(0, bar), value.Substring(bar + 1))); }
                        break;
                }
            }
            return o;
        }

        /// <summary>Die Rechnung läuft automatisch durch: Profil erkannt, kein Pflichtfeld fehlt, Plausibilität bestanden.</summary>
        public bool Passed
        {
            get { return State == Ok && Error.Length == 0 && Problems.Count == 0 && Plausibility == "bestanden"; }
        }

        public string Describe()
        {
            StringBuilder b = new StringBuilder();
            if (State == Login)
            {
                return "Sie sind im Hauptfenster nicht angemeldet. Bitte dort anmelden und den Test erneut starten.";
            }
            if (State == Offline)
            {
                return "Das Hauptfenster ist nicht mit dem Dienst verbunden" + (Error.Length > 0 ? " (" + Error + ")" : "") + ". Der Test ist erst mit Verbindung möglich; "
                    + "Sie können das Test-PDF auch später unter Profile → Profil testen von Hand prüfen.";
            }
            if (State == Unavailable)
            {
                return "Die Seite „Profile“ des Dienstes ist nicht erreichbar (z. B. Ersteinrichtung noch nicht abgeschlossen).";
            }
            if (State != Ok)
            {
                return "Der Test konnte nicht ausgeführt werden" + (Error.Length > 0 ? ": " + Error : ".");
            }
            if (Error.Length > 0)
            {
                return "Der Dienst meldet: " + Error;
            }
            b.AppendLine(Passed ? "BESTANDEN – Rechnungen aus dieser Vorlage laufen automatisch durch." : "NICHT BESTANDEN – so bliebe die Rechnung in der manuellen Prüfung.");
            b.AppendLine();
            b.AppendLine("Erkanntes Profil: " + (Profile.Length > 0 ? Profile : "–"));
            if (Classification.Length > 0) { b.AppendLine("Klassifizierung: " + Classification); }
            b.AppendLine("Seiten: " + Pages + ", Positionen: " + (Positions.Length > 0 ? Positions : "–"));
            b.AppendLine("Plausibilität: " + (Plausibility.Length > 0 ? Plausibility : "–"));
            foreach (string issue in Issues) { b.AppendLine("  • " + issue); }
            // was schon bei der Plausibilität steht, nicht noch einmal nennen
            List<string> further = Problems.FindAll(delegate(string p) { return !Issues.Contains(p); });
            if (further.Count > 0)
            {
                b.AppendLine();
                b.AppendLine("Angaben mit Fehler oder Warnung:");
                foreach (string problem in further) { b.AppendLine("  • " + problem); }
            }
            return b.ToString().TrimEnd();
        }
    }

    public static class ProfileTestScript
    {
        /// <summary>Zeichenkette als JavaScript-Literal (einfache Anführungszeichen).</summary>
        public static string JsString(string value)
        {
            StringBuilder b = new StringBuilder("'");
            foreach (char c in value == null ? "" : value)
            {
                if (c == '\\' || c == '\'') { b.Append('\\').Append(c); }
                else if (c == '\n') { b.Append("\\n"); }
                else if (c == '\r') { b.Append("\\r"); }
                else if (c < ' ' || c == '<' || c == '>' || c == (char)0x2028 || c == (char)0x2029) { b.Append("\\u").Append(((int)c).ToString("x4")); }
                else { b.Append(c); }
            }
            return b.Append('\'').ToString();
        }

        /// <summary>Fragt die Mandanten ab, die die Seite „Profile“ anbietet.</summary>
        public static string ListTenants(string id)
        {
            return Build(id, "send(['state\\ttenants'].concat(tenants)); return;");
        }

        /// <summary>Lädt das PDF in der angemeldeten Sitzung nach /profile/test hoch und liest das Ergebnis.</summary>
        public static string Test(string id, byte[] pdf, string fileName, string tenant)
        {
            string work =
                "var csrf = form.querySelector('input[name=\"_csrf\"]');\n" +
                "var raw = atob(" + JsString(Convert.ToBase64String(pdf)) + ");\n" +
                "var bytes = new Uint8Array(raw.length);\n" +
                "for (var i = 0; i < raw.length; i++) { bytes[i] = raw.charCodeAt(i); }\n" +
                "var data = new FormData();\n" +
                "data.append('file', new Blob([bytes], {type: 'application/pdf'}), " + JsString(fileName) + ");\n" +
                "data.append('tenant', " + JsString(tenant) + ");\n" +
                "if (csrf) { data.append('_csrf', csrf.value); }\n" +
                "var r = await fetch('/profile/test', {method: 'POST', body: data, credentials: 'same-origin', headers: {'HX-Request': 'true'}});\n" +
                "if (!r.ok) { send(['state\\thttp', 'error\\tHTTP ' + r.status]); return; }\n" +
                "var res = new DOMParser().parseFromString(await r.text(), 'text/html');\n" +
                "var lines = ['state\\tok'];\n" +
                "var err = res.querySelector('.notice.error');\n" +
                "if (err) { lines.push('error\\t' + clean(err.textContent)); }\n" +
                "var dts = res.querySelectorAll('dl.kv dt');\n" +
                "for (var k = 0; k < dts.length; k++) {\n" +
                "  var key = clean(dts[k].textContent), dd = dts[k].nextElementSibling;\n" +
                "  if (!dd) { continue; }\n" +
                "  if (key === 'Seiten') { lines.push('pages\\t' + clean(dd.textContent)); }\n" +
                "  if (key === 'Erkanntes Profil') { lines.push('profile\\t' + clean(dd.textContent)); }\n" +
                "  if (key === 'Klassifizierung') { var c = dd.querySelector('span span'); if (c) { lines.push('classification\\t' + clean(c.textContent)); } }\n" +
                "  if (key === 'Plausibilität') {\n" +
                "    var badge = dd.querySelector('.badge'); if (badge) { lines.push('plausibility\\t' + clean(badge.textContent)); }\n" +
                "    var items = dd.querySelectorAll('li'); for (var j = 0; j < items.length; j++) { lines.push('issue\\t' + clean(items[j].textContent)); }\n" +
                "  }\n" +
                "}\n" +
                "var tables = res.querySelectorAll('table.data');\n" +
                "if (tables.length > 0) {\n" +
                "  var rows = tables[0].querySelectorAll('tbody tr');\n" +
                "  for (var n = 0; n < rows.length; n++) {\n" +
                "    var td = rows[n].querySelectorAll('td'); if (td.length < 8) { continue; }\n" +
                "    var b = td[7].querySelector('.badge'), status = b ? clean(b.textContent) : '';\n" +
                "    if (status !== 'ERROR' && status !== 'WARNING') { continue; }\n" +
                "    var m = td[7].querySelector('div');\n" +
                "    lines.push('problem\\t' + clean(td[0].textContent) + ' ' + clean(td[1].textContent) + ': ' + (m ? clean(m.textContent) : status));\n" +
                "  }\n" +
                "}\n" +
                "var heads = res.querySelectorAll('h2');\n" +
                "for (var h = 0; h < heads.length; h++) { var t = /^Positionen \\((\\d+)\\)/.exec(clean(heads[h].textContent)); if (t) { lines.push('positions\\t' + t[1]); } }\n" +
                "send(lines);";
            return Build(id, work);
        }

        private static string Build(string id, string work)
        {
            return "(function () {\n" +
                "function send(lines) { window.chrome.webview.postMessage(['" + ProfileTestOutcome.Marker + "\\t' + " + JsString(id) + "].concat(lines).join('\\n')); }\n" +
                "function clean(s) { return (s || '').replace(/\\s+/g, ' ').trim(); }\n" +
                "(async function () {\n" +
                "var page = await fetch('/profile', {credentials: 'same-origin', cache: 'no-store'});\n" +
                "if (!page.ok) { send(['state\\thttp', 'error\\tHTTP ' + page.status]); return; }\n" +
                "var doc = new DOMParser().parseFromString(await page.text(), 'text/html');\n" +
                "var form = doc.querySelector('form[hx-post=\"/profile/test\"]');\n" +
                "if (!form) { send(['state\\t' + (doc.querySelector('input[name=\"passwort\"]') ? 'login' : 'unavailable')]); return; }\n" +
                "var tenants = Array.prototype.map.call(form.querySelectorAll('select[name=\"tenant\"] option'), function (o) { return 'tenant\\t' + o.value + '|' + clean(o.textContent); });\n" +
                work + "\n" +
                "})().catch(function (e) { send(['state\\terror', 'error\\t' + clean(String(e))]); });\n" +
                "})();";
        }
    }
}
