// Vorlagen-Assistent (ADR 0016): liest eine Word- oder Excel-Datei über das auf diesem Computer
// installierte Office (COM, spät gebunden) in eine Momentaufnahme. Die Datei wird schreibgeschützt
// geöffnet und nicht verändert. Office läuft unsichtbar in einem eigenen Thread mit Zeitlimit;
// reagiert es nicht (z. B. wegen eines verdeckten Dialogs), wird genau der dafür gestartete
// Office-Prozess beendet.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

namespace HofmannIT.ERechnung.Verwaltung
{
    public sealed class OfficeException : Exception
    {
        public OfficeException(string message) : base(message) { }
        public OfficeException(string message, Exception inner) : base(message, inner) { }
    }

    public static class OfficeAutomation
    {
        private const int MaxRows = 400;
        private const int MaxColumns = 40;
        private const int MaxParagraphs = 1500;

        public static bool IsExcelFile(string path)
        {
            string e = Path.GetExtension(path).ToLowerInvariant();
            return e == ".xlsx" || e == ".xls" || e == ".xlsm";
        }

        public static bool IsWordFile(string path)
        {
            string e = Path.GetExtension(path).ToLowerInvariant();
            return e == ".docx" || e == ".doc" || e == ".docm";
        }

        public static bool Installed(bool excel)
        {
            return Type.GetTypeFromProgID(excel ? "Excel.Application" : "Word.Application") != null;
        }

        /// <summary>
        /// Liest die Datei. <paramref name="sheet"/>: Name des Excel-Blatts, leer = das beim Speichern aktive Blatt.
        /// Blockiert bis zum Ergebnis oder Zeitlimit; nicht im Fenster-Thread aufrufen.
        /// </summary>
        public static SourceDocument Read(string path, string sheet, int timeoutMillis)
        {
            if (!File.Exists(path)) { throw new OfficeException("Die Datei wurde nicht gefunden: " + path); }
            bool excel = IsExcelFile(path);
            if (!excel && !IsWordFile(path))
            {
                throw new OfficeException("Dieser Dateityp wird nicht unterstützt. Möglich sind Word (.docx, .doc) und Excel (.xlsx, .xls).");
            }
            if (!Installed(excel))
            {
                throw new OfficeException((excel ? "Excel" : "Word") + " ist auf diesem Computer nicht installiert. "
                    + "Der Vorlagen-Assistent braucht das Programm, mit dem die Rechnung geschrieben wurde.");
            }
            string full = Path.GetFullPath(path);
            return Run(excel, timeoutMillis, delegate(object app)
            {
                return excel ? ReadExcel(app, full, sheet == null ? "" : sheet) : ReadWord(app, full);
            });
        }

        private delegate SourceDocument Work(object application);

        private static SourceDocument Run(bool excel, int timeoutMillis, Work work)
        {
            string processName = excel ? "EXCEL" : "WINWORD";
            HashSet<int> before = ProcessIds(processName);
            SourceDocument result = null;
            Exception failure = null;
            int ownProcess = 0;
            Thread thread = new Thread(delegate()
            {
                object app = null;
                try
                {
                    app = Activator.CreateInstance(Type.GetTypeFromProgID(excel ? "Excel.Application" : "Word.Application"));
                    foreach (int id in ProcessIds(processName))
                    {
                        if (!before.Contains(id)) { ownProcess = id; }
                    }
                    dynamic a = app;
                    a.Visible = false;
                    if (excel) { a.DisplayAlerts = false; } else { a.DisplayAlerts = 0; }
                    try { a.AutomationSecurity = 3; } catch (Exception) { }   // Makros der Datei nicht ausführen
                    result = work(app);
                }
                catch (Exception e)
                {
                    failure = e;
                }
                finally
                {
                    if (app != null)
                    {
                        try { ((dynamic)app).Quit(); } catch (Exception) { }
                        try { Marshal.FinalReleaseComObject(app); } catch (Exception) { }
                    }
                }
            });
            thread.IsBackground = true;
            thread.SetApartmentState(ApartmentState.STA);
            thread.Start();
            if (!thread.Join(timeoutMillis))
            {
                Kill(ownProcess);
                throw new OfficeException((excel ? "Excel" : "Word") + " hat nicht rechtzeitig geantwortet und wurde beendet. "
                    + "Häufige Ursachen: Die Datei ist kennwortgeschützt, wird gerade von jemandem bearbeitet oder "
                    + (excel ? "Excel" : "Word") + " wartet auf eine Eingabe (z. B. Aktivierung). Bitte die Datei einmal von Hand öffnen und prüfen.");
            }
            if (failure != null)
            {
                OfficeException known = failure as OfficeException;
                if (known != null) { throw known; }
                throw new OfficeException("Die Datei konnte nicht gelesen werden: " + failure.Message, failure);
            }
            return result;
        }

        private static HashSet<int> ProcessIds(string name)
        {
            HashSet<int> ids = new HashSet<int>();
            foreach (Process p in Process.GetProcessesByName(name))
            {
                ids.Add(p.Id);
                p.Dispose();
            }
            return ids;
        }

        private static void Kill(int processId)
        {
            if (processId == 0) { return; }
            try
            {
                using (Process p = Process.GetProcessById(processId))
                {
                    p.Kill();
                }
            }
            catch (Exception)
            {
                // bereits beendet
            }
        }

        private static string Str(object value)
        {
            return value == null ? "" : value.ToString();
        }

        private static string Clean(object text)
        {
            string t = text == null ? "" : text.ToString();
            t = t.Replace("\r", " ").Replace("\v", " ").Replace("\f", " ").Replace(" ", " ");
            // Steuerzeichen von Word (Zellenende, Feldmarken, verankerte Objekte) sind kein Text
            System.Text.StringBuilder b = new System.Text.StringBuilder(t.Length);
            foreach (char c in t)
            {
                if (c >= ' ' || c == '\n' || c == '\t') { b.Append(c); }
            }
            return b.ToString().Trim();
        }

        // ---- Excel ----------------------------------------------------------------------------

        private static SourceDocument ReadExcel(object application, string path, string sheetName)
        {
            dynamic app = application;
            SourceDocument doc = new SourceDocument();
            doc.FileName = Path.GetFileName(path);
            doc.Excel = true;
            // UpdateLinks 0 (keine Verknüpfungen aktualisieren), ReadOnly true
            dynamic wb = app.Workbooks.Open(path, 0, true);
            try
            {
                try { doc.HasMacros = (bool)wb.HasVBProject; } catch (Exception) { }
                dynamic ws = null;
                foreach (dynamic s in wb.Worksheets)
                {
                    string name = (string)s.Name;
                    doc.Sheets.Add(name);
                    if (sheetName.Length > 0 && string.Equals(name, sheetName, StringComparison.OrdinalIgnoreCase)) { ws = s; }
                }
                if (ws == null)
                {
                    if (sheetName.Length > 0) { throw new OfficeException("Das Blatt „" + sheetName + "“ gibt es in dieser Datei nicht."); }
                    dynamic active = wb.ActiveSheet;
                    string activeName = (string)active.Name;
                    ws = doc.Sheets.Contains(activeName) ? active : wb.Worksheets[1];
                }
                doc.Sheet = (string)ws.Name;

                dynamic used = ws.UsedRange;
                int firstRow = (int)used.Row;
                int firstColumn = (int)used.Column;
                int lastRow = firstRow + (int)used.Rows.Count - 1;
                int lastColumn = firstColumn + (int)used.Columns.Count - 1;
                if (lastRow > MaxRows) { lastRow = MaxRows; doc.Truncated = true; }
                if (lastColumn > MaxColumns) { lastColumn = MaxColumns; doc.Truncated = true; }
                for (int r = 1; r <= lastRow; r++)
                {
                    SourceLine line = new SourceLine();
                    for (int c = 1; c <= lastColumn; c++)
                    {
                        dynamic cell = ws.Cells[r, c];
                        string text = Clean(cell.Text);
                        if (text.Length > 0) { line.Add(c, text, (string)cell.Address(false, false)); }
                    }
                    if (line.Cells.Count > 0) { doc.Lines.Add(line); }
                }

                foreach (dynamic shape in ws.Shapes)
                {
                    int type = (int)shape.Type;
                    string name = (string)shape.Name;
                    string text = "";
                    try { text = Clean(shape.TextFrame2.TextRange.Text); } catch (Exception) { }
                    if (type == 13 || type == 11)
                    {
                        doc.Kept.Add("Bild „" + name + "“");
                    }
                    else if (type == 8 || type == 12)
                    {
                        doc.Kept.Add("Steuerelement „" + name + "“ (wird nicht übernommen)");
                    }
                    else
                    {
                        doc.Kept.Add("Form/Textfeld „" + name + "“" + (text.Length > 0 ? ": " + Shorten(text) : ""));
                    }
                    if (text.Length > 0)
                    {
                        foreach (string part in text.Split('\n'))
                        {
                            SourceLine line = new SourceLine();
                            line.Container = "Textfeld " + name;
                            line.Add(1, part, "Textfeld „" + name + "“");
                            if (line.Cells.Count > 0) { doc.Lines.Add(line); }
                        }
                    }
                }
                dynamic setup = ws.PageSetup;
                string header = Clean(setup.LeftHeader) + " " + Clean(setup.CenterHeader) + " " + Clean(setup.RightHeader);
                string footer = Clean(setup.LeftFooter) + " " + Clean(setup.CenterFooter) + " " + Clean(setup.RightFooter);
                if (header.Trim().Length > 0) { doc.Kept.Add("Kopfzeile des Blatts: " + Shorten(header.Trim())); }
                if (footer.Trim().Length > 0) { doc.Kept.Add("Fußzeile des Blatts: " + Shorten(footer.Trim())); }
            }
            finally
            {
                try { wb.Close(false); } catch (Exception) { }
            }
            return doc;
        }

        // ---- Word -----------------------------------------------------------------------------

        private sealed class Ordered
        {
            public int Start;
            public SourceLine Line;
        }

        private static SourceDocument ReadWord(object application, string path)
        {
            dynamic app = application;
            SourceDocument doc = new SourceDocument();
            doc.FileName = Path.GetFileName(path);
            // ConfirmConversions false, ReadOnly true, AddToRecentFiles false
            dynamic d = app.Documents.Open(path, false, true, false);
            try
            {
                try { doc.HasMacros = (bool)d.HasVBProject; } catch (Exception) { }
                List<Ordered> items = new List<Ordered>();

                // Tabellen: je Zeile eine Zeile der Momentaufnahme, Zellen in ihrer Spalte
                int tableNumber = 0;
                foreach (dynamic table in d.Tables)
                {
                    tableNumber++;
                    Dictionary<int, Ordered> rows = new Dictionary<int, Ordered>();
                    foreach (dynamic cell in table.Range.Cells)
                    {
                        int row = (int)cell.RowIndex;
                        int column = (int)cell.ColumnIndex;
                        Ordered o;
                        if (!rows.TryGetValue(row, out o))
                        {
                            o = new Ordered();
                            o.Start = (int)cell.Range.Start;
                            o.Line = new SourceLine();
                            o.Line.InTable = true;
                            rows[row] = o;
                            items.Add(o);
                        }
                        // Absätze innerhalb einer Zelle bleiben in der Zelle (mehrzeilige Bezeichnung)
                        string text = Clean(((string)cell.Range.Text).Replace("\r", "\n"));
                        o.Line.Add(column, text, "Tabelle " + tableNumber + ", Zeile " + row);
                    }
                }

                // Absätze außerhalb von Tabellen; Tabulatoren trennen Spalten
                int count = 0;
                int number = 0;
                foreach (dynamic p in d.Paragraphs)
                {
                    number++;
                    if (++count > MaxParagraphs) { doc.Truncated = true; break; }
                    dynamic range = p.Range;
                    if ((bool)range.Information(12)) { continue; }   // wdWithInTable
                    string raw = Str(range.Text);
                    string[] parts = raw.Split('\t');
                    Ordered o = new Ordered();
                    o.Start = (int)range.Start;
                    o.Line = new SourceLine();
                    for (int i = 0; i < parts.Length; i++)
                    {
                        o.Line.Add(i + 1, Clean(parts[i]), "Absatz " + number);
                    }
                    if (o.Line.Cells.Count > 0) { items.Add(o); }
                }
                items.Sort(delegate(Ordered x, Ordered y) { return x.Start.CompareTo(y.Start); });
                foreach (Ordered o in items)
                {
                    if (o.Line.Cells.Count > 0) { doc.Lines.Add(o.Line); }
                }

                foreach (dynamic shape in d.Shapes)
                {
                    DescribeWordShape(doc, shape, "");
                }
                int inline = (int)d.InlineShapes.Count;
                if (inline > 0) { doc.Kept.Add(inline + " Bild(er) im Text"); }
                foreach (dynamic section in d.Sections)
                {
                    for (int kind = 1; kind <= 3; kind++)   // Standard, erste Seite, gerade Seiten
                    {
                        HeaderFooter(doc, section.Headers[kind], SourceLine.Header);
                        HeaderFooter(doc, section.Footers[kind], SourceLine.Footer);
                    }
                    break;   // der Briefkopf steht im ersten Abschnitt
                }
            }
            finally
            {
                try { d.Close(false); } catch (Exception) { }
            }
            return doc;
        }

        private static void DescribeWordShape(SourceDocument doc, dynamic shape, string where)
        {
            int type = (int)shape.Type;
            string name = Str(shape.Name);
            string text = "";
            try
            {
                if ((int)shape.TextFrame.HasText != 0) { text = Str(shape.TextFrame.TextRange.Text); }
            }
            catch (Exception) { }
            string shown = Clean(text);
            if (type == 13 || type == 11)
            {
                doc.Kept.Add("Bild „" + name + "“" + where);
            }
            else
            {
                doc.Kept.Add("Form/Textfeld „" + name + "“" + where + (shown.Length > 0 ? ": " + Shorten(shown) : ""));
            }
            if (where.Length > 0) { return; }
            foreach (string part in text.Split('\r'))
            {
                SourceLine line = new SourceLine();
                line.Container = "Textfeld " + name;
                string[] cells = part.Split('\t');
                for (int i = 0; i < cells.Length; i++) { line.Add(i + 1, Clean(cells[i]), "Textfeld „" + name + "“"); }
                if (line.Cells.Count > 0) { doc.Lines.Add(line); }
            }
        }

        private static void HeaderFooter(SourceDocument doc, dynamic part, string container)
        {
            if (!(bool)part.Exists) { return; }
            string text = Clean(part.Range.Text);
            string where = container == SourceLine.Header ? " in der Kopfzeile" : " in der Fußzeile";
            if (text.Length > 0)
            {
                string entry = container + ": " + Shorten(text);
                if (!doc.Kept.Contains(entry)) { doc.Kept.Add(entry); }
                SourceLine line = new SourceLine();
                line.Container = container;
                line.Add(1, text, container);
                doc.Lines.Add(line);
            }
            foreach (dynamic shape in part.Shapes)
            {
                string shapeName = Str(shape.Name);
                string entry = "Form/Bild „" + shapeName + "“" + where;
                // Formen der Kopfzeile werden von Word in jeder Kopf-/Fußzeilenart gemeldet
                bool known = false;
                foreach (string k in doc.Kept) { if (k.StartsWith(entry, StringComparison.Ordinal) || k.Contains("„" + shapeName + "“" + " in der")) { known = true; } }
                if (!known) { DescribeWordShape(doc, shape, where); }
            }
        }

        private static string Shorten(string text)
        {
            string t = text.Replace("\n", " / ");
            return t.Length <= 80 ? t : t.Substring(0, 77) + "…";
        }
    }
}
