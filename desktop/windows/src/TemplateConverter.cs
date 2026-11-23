// Vorlagen-Assistent (ADR 0016, Phase 2): baut aus einer Excel-Rechnung des Kunden eine neue Datei
// im Aufbau des Profils "standard". Gearbeitet wird in einer Kopie des Blatts in einer neuen Mappe;
// das Original wird schreibgeschützt geöffnet und nicht verändert. Bilder, Formen und Textfelder
// bleiben an ihrem Platz auf dem Blatt, Zelltexte des Briefkopfs werden zu Textfeldern an gleicher
// Stelle, der Rechnungsteil wird nach dem Bauplan (TemplateLayout.cs) neu geschrieben.
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;

namespace HofmannIT.ERechnung.Verwaltung
{
    public sealed class ConversionResult
    {
        public string Workbook = "";
        public string Marked = "";
        public string Pdf = "";
        /// <summary>Hinweise: fehlende Angaben, verschobene Elemente, Abweichungen der neu berechneten Summen.</summary>
        public readonly List<string> Notes = new List<string>();
    }

    public static class TemplateConverter
    {
        private const int Red = 0xCEC7FF;      // BGR, wie in den mitgelieferten markierten Vorlagen
        private const int Yellow = 0x9CEBFF;
        private const int Green = 0xCEEFC6;
        private const int XlLeft = -4131;
        private const int XlRight = -4152;
        private const int XlTop = -4160;
        private const int XlCenter = -4108;

        private sealed class Kept
        {
            public object Shape;
            public double Left, Top, Width, Height;
            public bool HasText;
            public string Name = "";
        }

        private sealed class HeadCell
        {
            public string Text = "";
            public string Place = "";
            public double Left, Top, Width, Height;
            public string Font = "";
            public double Size;
            public bool Bold, Italic, Wrap;
            public int Color;
            public int Align;
            public bool InAddress;
        }

        /// <summary>
        /// Baut die Datei um. <paramref name="analysis"/> enthält die Erkennung samt Korrekturen des Benutzers.
        /// Blockiert bis zum Ergebnis oder Zeitlimit; nicht im Fenster-Thread aufrufen.
        /// </summary>
        public static ConversionResult ConvertExcel(string path, string sheet, Analysis analysis, string outputDirectory, int timeoutMillis)
        {
            if (!analysis.Convertible)
            {
                throw new OfficeException("Diese Rechnung kann nicht automatisch umgebaut werden: " + AnalysisReport.Summary(analysis));
            }
            string full = Path.GetFullPath(path);
            ExcelPlan plan = ExcelLayout.Build(analysis);
            OutputNames names = OutputNames.For(outputDirectory, Path.GetFileName(full), ".xlsx");
            return OfficeAutomation.Run<ConversionResult>(true, timeoutMillis, "Der Umbau ist fehlgeschlagen: ", delegate(object app)
            {
                return Build(app, full, sheet, analysis, plan, names);
            });
        }

        private static ConversionResult Build(object application, string path, string sheetName, Analysis analysis, ExcelPlan plan, OutputNames names)
        {
            dynamic app = application;
            ConversionResult result = new ConversionResult();
            result.Notes.AddRange(plan.Notes);
            dynamic source = app.Workbooks.Open(path, 0, true);
            dynamic wb = null;
            try
            {
                dynamic original = source.Worksheets[sheetName];
                List<HeadCell> letterhead = ReadLetterhead(original, analysis);
                original.Copy();                       // neue Mappe mit einer Kopie des Blatts
                wb = app.ActiveWorkbook;
                source.Close(false);
                source = null;
                BreakLinks(wb);
                dynamic ws = wb.Worksheets[1];
                try { if ((bool)ws.ProtectContents) { ws.Unprotect(); } }
                catch (Exception) { throw new OfficeException("Das Blatt ist mit einem Kennwort geschützt. Bitte den Blattschutz aufheben und erneut versuchen."); }

                Rebuild(wb, ws, plan, letterhead, result);
                app.Calculate();
                CompareTotals(ws, plan, analysis, result);

                ws.ExportAsFixedFormat(0, names.Pdf);  // 0 = PDF
                ws.Range["A1"].Select();
                wb.SaveAs(names.Workbook, 51);         // 51 = .xlsx ohne Makros
                ApplyMarks(ws, plan);
                wb.SaveAs(names.Marked, 51);
                result.Workbook = names.Workbook;
                result.Marked = names.Marked;
                result.Pdf = names.Pdf;
            }
            finally
            {
                if (source != null) { try { source.Close(false); } catch (Exception) { } }
                if (wb != null) { try { wb.Close(false); } catch (Exception) { } }
            }
            return result;
        }

        private static void BreakLinks(dynamic wb)
        {
            try
            {
                object links = wb.LinkSources(1);      // 1 = Excel-Verknüpfungen
                Array list = links as Array;
                if (list == null) { return; }
                foreach (object link in list) { wb.BreakLink((string)link, 1); }
            }
            catch (Exception)
            {
                // ohne Verknüpfungen liefert Excel "leer"
            }
        }

        private static List<HeadCell> ReadLetterhead(dynamic ws, Analysis analysis)
        {
            List<HeadCell> cells = new List<HeadCell>();
            foreach (SourceCell c in analysis.LetterheadCells)
            {
                dynamic cell = ws.Range[c.Place];
                dynamic area = cell.MergeArea;
                HeadCell h = new HeadCell();
                h.Text = Convert.ToString(cell.Text, CultureInfo.InvariantCulture);
                h.Place = c.Place;
                h.Left = (double)area.Left;
                h.Top = (double)area.Top;
                h.Width = (double)area.Width;
                h.Height = (double)area.Height;
                h.Font = Convert.ToString(cell.Font.Name, CultureInfo.InvariantCulture);
                h.Size = ToDouble(cell.Font.Size, 10);
                h.Bold = IsTrue(cell.Font.Bold);
                h.Italic = IsTrue(cell.Font.Italic);
                h.Wrap = IsTrue(cell.WrapText);
                h.Color = (int)ToDouble(cell.Font.Color, 0);
                h.Align = (int)ToDouble(cell.HorizontalAlignment, 1);
                if (h.Text.Trim().Length > 0) { cells.Add(h); }
            }
            return cells;
        }

        private static bool IsTrue(object value)
        {
            return value is bool && (bool)value;
        }

        private static double ToDouble(object value, double fallback)
        {
            try { return value == null || value is DBNull ? fallback : Convert.ToDouble(value, CultureInfo.InvariantCulture); }
            catch (Exception) { return fallback; }
        }

        private static void Rebuild(dynamic wb, dynamic ws, ExcelPlan plan, List<HeadCell> letterhead, ConversionResult result)
        {
            dynamic setup = ws.PageSetup;
            double oldLeft = (double)setup.LeftMargin;
            double oldTop = (double)setup.TopMargin;
            // Druckposition erhalten: Formen sitzen relativ zum Blattanfang, der mit den Rändern wandert
            double shiftX = oldLeft - ExcelPlan.LeftMargin;
            double shiftY = oldTop - ExcelPlan.TopMargin;
            object zoom = setup.Zoom;
            if (!(zoom is bool) && Math.Abs(ToDouble(zoom, 100) - 100) > 0.5)
            {
                result.Notes.Add("Das Blatt wurde bisher mit " + ToDouble(zoom, 100).ToString("0", CultureInfo.InvariantCulture)
                    + " % gedruckt; die neue Datei druckt mit 100 %. Bitte die Lage von Logo und Briefkopf im Test-PDF prüfen.");
            }
            else if (zoom is bool)
            {
                result.Notes.Add("Das Blatt wurde bisher „an Seite angepasst“ gedruckt; die neue Datei druckt mit 100 %. Bitte die Lage von Logo und Briefkopf im Test-PDF prüfen.");
            }

            List<Kept> kept = new List<Kept>();
            List<object> remove = new List<object>();
            foreach (dynamic shape in ws.Shapes)
            {
                int type = (int)shape.Type;
                if (type == 8 || type == 12 || type == 4)    // Formularsteuerelement, ActiveX, Kommentar
                {
                    remove.Add(shape);
                    continue;
                }
                Kept k = new Kept();
                k.Shape = shape;
                k.Name = (string)shape.Name;
                k.Left = (double)shape.Left;
                k.Top = (double)shape.Top;
                k.Width = (double)shape.Width;
                k.Height = (double)shape.Height;
                try { k.HasText = ((string)shape.TextFrame2.TextRange.Text).Trim().Length > 0; } catch (Exception) { }
                kept.Add(k);
            }
            foreach (dynamic shape in remove)
            {
                try { shape.Delete(); } catch (Exception) { }
            }
            foreach (Kept k in kept) { ((dynamic)k.Shape).Placement = 3; }   // frei verankert: wandert nicht mit den Zellen

            dynamic cells = ws.Cells;
            ws.Rows.Hidden = false;
            ws.Columns.Hidden = false;
            cells.UnMerge();
            cells.Clear();
            try { cells.FormatConditions.Delete(); } catch (Exception) { }
            try { cells.Validation.Delete(); } catch (Exception) { }
            try { ws.ResetAllPageBreaks(); } catch (Exception) { }
            // Grundschrift der Mappe: Excel rechnet Spaltenbreiten beim Drucken über sie um
            dynamic normal = wb.Styles["Normal"];
            normal.Font.Name = "Arial";
            normal.Font.Size = 10;
            cells.Font.Name = "Arial";
            cells.Font.Size = 10;
            cells.VerticalAlignment = XlTop;
            cells.RowHeight = ExcelPlan.RowHeight;
            foreach (KeyValuePair<string, double> column in ExcelPlan.ColumnWidths) { SetWidth(ws.Columns[column.Key], column.Value); }
            for (int r = 1; r <= 7; r++) { ws.Rows[r].RowHeight = 15; }
            ws.Rows[8].RowHeight = 16.5;

            foreach (Kept k in kept)
            {
                dynamic shape = k.Shape;
                double left = Math.Max(0, k.Left + shiftX);
                double top = Math.Max(0, k.Top + shiftY);
                // Absenderzeile über dem Anschriftenfeld: muss oberhalb der ersten Empfängerzeile (150 pt vom Blattrand) enden
                double pageTop = top + ExcelPlan.TopMargin;
                double pageLeft = left + ExcelPlan.LeftMargin;
                bool inAddress = pageTop + k.Height > 148 && pageTop < 206 && pageLeft < 310;
                // ein flaches Textfeld, das über dem Anschriftenfeld beginnt, ist die Absenderzeile
                if (inAddress && k.HasText && k.Height <= 40 && pageTop < 160)
                {
                    double moved = pageTop + k.Height - 146;
                    top = Math.Max(0, top - moved);
                    result.Notes.Add("„" + k.Name + "“ (Absenderzeile) lag im Bereich des Anschriftenfelds und wurde um "
                        + moved.ToString("0", CultureInfo.InvariantCulture) + " pt nach oben gesetzt.");
                }
                else if (inAddress)
                {
                    result.Notes.Add("„" + k.Name + "“ (" + (pageTop / 28.35).ToString("0.0", CultureInfo.GetCultureInfo("de-DE")) + " cm von oben, "
                        + (k.Height / 28.35).ToString("0.0", CultureInfo.GetCultureInfo("de-DE")) + " cm hoch) liegt im Bereich des Anschriftenfelds (5,3 bis 7,3 cm von oben, links). "
                        + "Steht dort Text, wird die Anschrift falsch gelesen – bitte im Test-PDF prüfen und das Element verschieben.");
                }
                shape.Left = (float)left;
                shape.Top = (float)top;
                shape.Width = (float)k.Width;
                shape.Height = (float)k.Height;
            }

            // Briefkopf-Zeilen aus Zellen, die im Bereich des neuen Anschriftenfelds landen würden (typisch: die
            // Absenderzeile direkt über dem Empfänger), gemeinsam so weit nach oben setzen, dass sie darüber enden
            double lift = 0;
            int lifted = 0;
            foreach (HeadCell h in letterhead)
            {
                double pageTop = Math.Max(0, h.Top + shiftY) + ExcelPlan.TopMargin;
                h.InAddress = pageTop + h.Height > 148 && pageTop < 206 && Math.Max(0, h.Left + shiftX) + ExcelPlan.LeftMargin < 310;
                if (h.InAddress)
                {
                    lift = Math.Max(lift, pageTop + h.Height - 146);
                    lifted++;
                }
            }
            if (lifted > 0)
            {
                result.Notes.Add(lifted + " Briefkopf-Zeile(n) lagen im Bereich des Anschriftenfelds (z. B. die Absenderzeile) und wurden um "
                    + lift.ToString("0", CultureInfo.InvariantCulture) + " pt nach oben gesetzt. Bitte im Test-PDF prüfen.");
            }
            foreach (HeadCell h in letterhead)
            {
                double top = Math.Max(0, h.Top + shiftY - (h.InAddress ? lift : 0));
                double left = Math.Max(0, h.Left + shiftX);
                dynamic box = ws.Shapes.AddTextbox(1, (float)left, (float)top, (float)Math.Max(h.Width, 20), (float)Math.Max(h.Height, 10));
                box.Placement = 3;
                box.Line.Visible = 0;
                box.Fill.Visible = 0;
                dynamic frame = box.TextFrame2;
                frame.MarginLeft = 2;
                frame.MarginRight = 2;
                frame.MarginTop = 0;
                frame.MarginBottom = 0;
                frame.WordWrap = h.Wrap ? -1 : 0;
                dynamic range = frame.TextRange;
                range.Text = h.Text;
                range.Font.Name = h.Font;
                range.Font.Size = (float)h.Size;
                range.Font.Bold = h.Bold ? -1 : 0;
                range.Font.Italic = h.Italic ? -1 : 0;
                range.Font.Fill.ForeColor.RGB = h.Color;
                range.ParagraphFormat.Alignment = h.Align == XlRight ? 3 : (h.Align == XlCenter ? 2 : 1);
                if (!h.Wrap && h.Align != XlRight && h.Align != XlCenter) { frame.AutoSize = 1; }   // Breite folgt dem Text
                box.Name = "Briefkopf " + h.Place;
            }
            if (letterhead.Count > 0)
            {
                result.Notes.Add(letterhead.Count + " Text(e) des Briefkopfs standen in Zellen und sind jetzt Textfelder an gleicher Stelle (Namen „Briefkopf …“).");
            }

            // Excel deutet Formatmuster in der eingestellten Sprache: Trennzeichen aus Excel selbst lesen
            string decimalSeparator = System.Convert.ToString(ws.Application.International[3], CultureInfo.InvariantCulture);
            string thousandsSeparator = System.Convert.ToString(ws.Application.International[4], CultureInfo.InvariantCulture);
            string amountFormat = "#" + thousandsSeparator + "##0" + decimalSeparator + "00";
            foreach (CellOp op in plan.Ops) { Apply(ws, op, amountFormat); }
            foreach (int row in plan.AutoFitRows)
            {
                dynamic r = ws.Rows[row];
                r.AutoFit();
                if ((double)r.RowHeight < ExcelPlan.RowHeight) { r.RowHeight = ExcelPlan.RowHeight; }
            }
            foreach (KeyValuePair<int, double> height in plan.RowHeights) { ws.Rows[height.Key].RowHeight = height.Value; }

            setup.Zoom = 100;
            setup.LeftMargin = ExcelPlan.LeftMargin;
            setup.RightMargin = ExcelPlan.RightMargin;
            setup.TopMargin = ExcelPlan.TopMargin;
            setup.BottomMargin = ExcelPlan.BottomMargin;
            setup.CenterHorizontally = false;
            setup.CenterVertically = false;
            setup.Orientation = 1;
            if ((int)setup.PaperSize != 9)
            {
                setup.PaperSize = 9;
                result.Notes.Add("Das Papierformat wurde auf A4 gestellt.");
            }
            setup.PrintArea = "A1:H" + (plan.LastRow + 1);
            // Tabellenkopf auf jeder Folgeseite wiederholen – sonst liest die Anwendung dort keine Positionen
            setup.PrintTitleRows = "$" + plan.HeaderRow + ":$" + plan.HeaderRow;
        }

        /// <summary>Spaltenbreite in Punkt einstellen (Excel rechnet in Zeichenbreiten; deshalb annähern).</summary>
        private static void SetWidth(dynamic column, double points)
        {
            double width = points / 5.25;
            for (int i = 0; i < 12; i++)
            {
                column.ColumnWidth = width;
                double actual = (double)column.Width;
                if (Math.Abs(actual - points) < 0.4) { break; }
                width = width * points / actual;
            }
        }

        private static void Apply(dynamic ws, CellOp op, string amountFormat)
        {
            dynamic range = ws.Range[op.Range];
            if (op.Merge) { range.Merge(); }
            dynamic target = op.Merge ? range.Cells[1, 1] : range;
            if (op.TextFormat) { range.NumberFormat = "@"; }
            if (op.AmountFormat) { range.NumberFormatLocal = amountFormat; }
            if (op.Formula.Length > 0)
            {
                target.Formula = op.Formula;
            }
            else if (op.HasNumber)
            {
                target.Value2 = (double)op.Number;
            }
            else if (!string.IsNullOrEmpty(op.Text))
            {
                target.Value2 = op.Text;
            }
            if (op.Bold) { range.Font.Bold = true; }
            if (op.FontSize > 0) { range.Font.Size = op.FontSize; }
            if (op.Align == CellAlign.Left) { range.HorizontalAlignment = XlLeft; }
            if (op.Align == CellAlign.Right) { range.HorizontalAlignment = XlRight; }
            if (op.Wrap) { range.WrapText = true; }
            if (op.BorderTop) { range.Borders[8].LineStyle = 1; }
            if (op.BorderBottom) { range.Borders[9].LineStyle = 1; }
        }

        /// <summary>Die neu berechneten Summen gegen die der bisherigen Rechnung halten – nur melden, nichts anpassen.</summary>
        private static void CompareTotals(dynamic ws, ExcelPlan plan, Analysis analysis, ConversionResult result)
        {
            Compare(ws, "H" + plan.NetRow, analysis.Get(InvoiceField.NetTotal), "Summe netto", result);
            Compare(ws, "H" + plan.GrossRow, analysis.Get(InvoiceField.GrossTotal), "Gesamtbetrag", result);
        }

        private static void Compare(dynamic ws, string address, FieldMatch expected, string name, ConversionResult result)
        {
            decimal old;
            if (!Values.TryNumber(expected.Value, out old)) { return; }
            decimal computed = Math.Round((decimal)ToDouble(ws.Range[address].Value2, 0), 2, MidpointRounding.AwayFromZero);
            if (Math.Abs(computed - old) > 0.005m)
            {
                result.Notes.Add("ACHTUNG: „" + name + "“ ergibt in der neuen Datei " + Values.Amount(computed) + ", auf der bisherigen Rechnung stand "
                    + Values.Amount(old) + ". Bitte Positionen, Mengen und Preise prüfen, bevor die Datei verwendet wird.");
            }
        }

        private static void ApplyMarks(dynamic ws, ExcelPlan plan)
        {
            foreach (CellOp op in plan.Ops)
            {
                if (op.Mark == Mark.None) { continue; }
                dynamic range = ws.Range[op.Range];
                range.Interior.Color = op.Mark == Mark.Red ? Red : (op.Mark == Mark.Yellow ? Yellow : Green);
                if (op.Note.Length > 0)
                {
                    try { range.Cells[1, 1].AddComment(op.Note); } catch (Exception) { }
                }
            }
            // Legende rechts neben dem Druckbereich (wird nicht gedruckt)
            ws.Columns["J"].ColumnWidth = 3;
            ws.Columns["K"].ColumnWidth = 78;
            int row = ExcelPlan.AddressRow;
            ws.Range["K" + row].Value2 = "Legende (wird nicht gedruckt)";
            ws.Range["K" + row].Font.Bold = true;
            string[] legend =
            {
                "Pflichtangabe, die auf der bisherigen Rechnung fehlte – leere rote Felder bitte ausfüllen",
                "War vorhanden, aber Beschriftung, Schreibweise oder Platz hat sich geändert – bitte prüfen",
                "Empfohlen (nicht zwingend)"
            };
            int[] colors = { Red, Yellow, Green };
            for (int i = 0; i < legend.Length; i++)
            {
                ws.Range["J" + (row + 1 + i)].Interior.Color = colors[i];
                ws.Range["K" + (row + 1 + i)].Value2 = legend[i];
            }
            string[] hints =
            {
                "Details zu jeder Markierung: Mauszeiger auf die Zelle (Kommentar).",
                "Spaltenbreiten und die Zeilenhöhen 1 bis 14 nicht ändern (die Anwendung liest nach Position).",
                "Weitere Positionen: ganze Zeilen innerhalb der Tabelle einfügen und die Formel aus Spalte H kopieren.",
                "Der Tabellenkopf wiederholt sich auf Folgeseiten (Seitenlayout > Drucktitel). Das nicht abschalten.",
                "Als PDF speichern: Datei > Speichern unter > PDF > Optionen > „ISO 19005-1-kompatibel (PDF/A)“.",
                "Diese markierte Fassung dient nur zur Kontrolle; für Rechnungen die Datei ohne „_markiert“ verwenden."
            };
            for (int i = 0; i < hints.Length; i++) { ws.Range["K" + (row + 5 + i)].Value2 = hints[i]; }
        }
    }
}
