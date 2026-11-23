// Vorlagen-Assistent (ADR 0016, Phase 3): baut aus einer Word-Rechnung des Kunden eine neue Datei im
// Aufbau des Profils "standard". Das Original wird schreibgeschützt geöffnet und unter neuem Namen
// gespeichert; gearbeitet wird nur in dieser Kopie. Ersetzt wird ausschließlich der Rechnungsteil
// (von der ersten erkannten Angabe bis zur letzten Summen- oder Zahlungszeile). Kopf- und Fußzeilen,
// Seitenränder, Bilder, Textfelder und alles, was vor oder nach dem Rechnungsteil steht, bleiben, wie
// sie sind. Absätze, an denen Bilder oder Textfelder verankert sind, werden nur geleert, nie gelöscht;
// ginge trotzdem ein Element verloren, bricht der Umbau ab.
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Reflection;

namespace HofmannIT.ERechnung.Verwaltung
{
    public static class WordConverter
    {
        private const int Red = 0xCEC7FF;      // BGR, wie in den mitgelieferten markierten Vorlagen
        private const int Yellow = 0x9CEBFF;
        private const int Green = 0xCEEFC6;
        private const int Automatic = -16777216;
        /// <summary>Linker Rand der Standardvorlage in Punkt; der Text der ersten Tabellenspalte beginnt 5,4 pt weiter rechts.</summary>
        private const double StandardLeft = 56.7;
        /// <summary>Oberkante der ersten Zeile des Anschriftenfelds, vom oberen Blattrand.</summary>
        private const double AddressTop = 146;
        private const double AddressLine = 14;
        private const string TemplateResource = "Rechnungsvorlage.docx";

        private sealed class Marked
        {
            public object Range;
            public Mark Mark;
            public string Note = "";
        }

        public static ConversionResult Convert(string path, Analysis analysis, string outputDirectory, int timeoutMillis)
        {
            if (!analysis.Convertible)
            {
                throw new OfficeException("Diese Rechnung kann nicht automatisch umgebaut werden: " + AnalysisReport.Summary(analysis));
            }
            if (analysis.RegionStart < 0 || analysis.RegionEnd <= analysis.RegionStart)
            {
                throw new OfficeException("Der Rechnungsteil ließ sich im Dokument nicht eingrenzen. Bitte die Vorlage nach der Anleitung „Rechnungsvorlage anpassen“ von Hand umbauen.");
            }
            string full = Path.GetFullPath(path);
            WordPlan plan = WordLayout.Build(analysis);
            OutputNames names = OutputNames.For(outputDirectory, Path.GetFileName(full), ".docx");
            string template = ExtractTemplate();
            bool done = false;
            try
            {
                ConversionResult result = OfficeAutomation.Run<ConversionResult>(false, timeoutMillis, "Der Umbau ist fehlgeschlagen: ", delegate(object app)
                {
                    return Build(app, full, template, analysis, plan, names);
                });
                done = true;
                return result;
            }
            finally
            {
                try { File.Delete(template); } catch (Exception) { }
                if (!done)
                {
                    // angefangene Ergebnisdateien dieses Laufs wieder entfernen (sie gab es vorher nicht)
                    foreach (string partial in new string[] { names.Workbook, names.Marked, names.Pdf })
                    {
                        try { File.Delete(partial); } catch (Exception) { }
                    }
                }
            }
        }

        private static string ExtractTemplate()
        {
            string target = Path.Combine(Path.GetTempPath(), "erechnung-vorlage-" + Guid.NewGuid().ToString("N") + ".docx");
            using (Stream source = Assembly.GetExecutingAssembly().GetManifestResourceStream(TemplateResource))
            {
                if (source == null) { throw new OfficeException("Die eingebaute Standardvorlage fehlt in dieser Programmversion."); }
                using (FileStream file = File.Create(target))
                {
                    source.CopyTo(file);
                }
            }
            return target;
        }

        private static double Number(object value)
        {
            return System.Convert.ToDouble(value, CultureInfo.InvariantCulture);
        }

        private static ConversionResult Build(object application, string path, string templatePath, Analysis analysis, WordPlan plan, OutputNames names)
        {
            dynamic app = application;
            ConversionResult result = new ConversionResult();
            result.Notes.AddRange(plan.Notes);
            // ConfirmConversions false, ReadOnly true, AddToRecentFiles false
            dynamic d = app.Documents.Open(path, false, true, false);
            dynamic template = null;
            try
            {
                d.SaveAs2(names.Workbook, 16);          // 16 = .docx; ab hier wird nur die Kopie bearbeitet
                template = app.Documents.Open(templatePath, false, true, false);
                dynamic positions = template.Tables[2];
                dynamic totals = template.Tables[3];
                double positionsX = Number(positions.Cell(1, 1).Range.Information(5));   // waagrechte Position auf der Seite
                double totalsX = Number(totals.Cell(1, 1).Range.Information(5));

                dynamic normal = d.Styles[-1];
                string fontName = (string)normal.Font.Name;
                double fontSize = Math.Min(Number(normal.Font.Size), 11);
                double leftMargin = Number(d.Sections[1].PageSetup.LeftMargin);
                double indent = StandardLeft - leftMargin;

                int shapesBefore = (int)d.Shapes.Count;
                // Lage der Bilder und Textfelder auf der Seite vor dem Umbau (für Formen, die am Absatz statt an der Seite hängen)
                d.Repaginate();
                Dictionary<string, double> pageTops = new Dictionary<string, double>();
                foreach (dynamic shape in d.Shapes)
                {
                    double top = PageTop(shape);
                    if (!double.IsNaN(top)) { pageTops[(string)shape.Name] = top; }
                }
                // Schriftgröße, in der der Rechnungsteil bisher überwiegend gesetzt war: so bleibt die Rechnung so lang wie vorher
                double bodySize = Math.Min(fontSize, DominantFontSize(d, analysis, fontSize));
                dynamic cursor = ClearRegion(d, analysis, result);
                List<Marked> marks = new List<Marked>();

                // ---- Anschriftenfeld: Textfeld an fester Stelle der Seite, unabhängig vom Textfluss
                cursor.InsertBefore("\r");
                dynamic anchor = cursor.Paragraphs[1];
                Format(anchor.Range, fontName, 1, false, indent);
                anchor.Format.LineSpacingRule = 4;      // genau
                anchor.Format.LineSpacing = 1;
                cursor.Collapse(0);
                double boxHeight = plan.Address.Count * AddressLine + 2;
                dynamic box = d.Shapes.AddTextbox(1, (float)StandardLeft, (float)AddressTop, 260f, (float)boxHeight, anchor.Range);
                box.Name = "Anschriftenfeld";
                box.RelativeHorizontalPosition = 1;      // Seite
                box.RelativeVerticalPosition = 1;
                box.Left = (float)StandardLeft;
                box.Top = (float)AddressTop;
                box.WrapFormat.Type = 4;                 // oben und unten: der Text läuft unter dem Feld weiter
                box.WrapFormat.DistanceTop = 0;
                box.WrapFormat.DistanceBottom = 6;
                box.Line.Visible = 0;
                box.Fill.Visible = 0;
                dynamic frame = box.TextFrame;
                frame.MarginLeft = 5.4f;
                frame.MarginRight = 0;
                frame.MarginTop = 0;
                frame.MarginBottom = 0;
                List<string> addressLines = new List<string>();
                foreach (PlanText line in plan.Address) { addressLines.Add(line.Line); }
                dynamic addressText = frame.TextRange;
                addressText.Text = string.Join("\r", addressLines.ToArray());
                Format(addressText, fontName, fontSize, false, 0);
                addressText.ParagraphFormat.LineSpacingRule = 4;
                addressText.ParagraphFormat.LineSpacing = (float)AddressLine;
                addressText.ParagraphFormat.SpaceAfter = 0;
                for (int i = 0; i < plan.Address.Count; i++) { Remember(marks, addressText.Paragraphs[i + 1].Range, plan.Address[i]); }

                // ---- Überschrift und Angaben zur Rechnung
                dynamic heading = Paragraph(cursor, "Rechnung", fontName, 14, true, indent);
                heading.Format.SpaceBefore = 6;
                heading.Format.SpaceAfter = 6;
                foreach (PlanText info in plan.Info)
                {
                    dynamic p = Paragraph(cursor, info.Line, fontName, fontSize, false, indent);
                    Remember(marks, p.Range, info);
                }
                Paragraph(cursor, "", fontName, fontSize, false, indent);
                foreach (string extra in plan.Extras) { Paragraph(cursor, extra, fontName, bodySize, false, indent); }
                if (plan.Extras.Count > 0) { Paragraph(cursor, "", fontName, fontSize, false, indent); }

                // ---- Positionstabelle aus der Standardvorlage (Spaltenbreiten des Profils)
                dynamic table = InsertTable(cursor, positions);
                while ((int)table.Rows.Count > 2) { table.Rows[(int)table.Rows.Count].Delete(); }
                for (int i = 0; i < plan.Items.Count; i++)
                {
                    if (i > 0) { table.Rows.Add(); }
                    dynamic row = table.Rows[i + 2];
                    for (int c = 0; c < 7; c++)
                    {
                        dynamic cell = row.Cells[c + 1];
                        cell.Range.Text = plan.Items[i][c].Value.Replace("\n", "\r");
                        Remember(marks, cell.Range, plan.Items[i][c]);
                    }
                }
                table.Range.Font.Name = fontName;
                table.Range.Font.Size = (float)bodySize;
                table.Range.ParagraphFormat.SpaceAfter = 0;
                table.Range.ParagraphFormat.SpaceBefore = 0;
                table.Range.ParagraphFormat.LineSpacingRule = 0;
                dynamic header = table.Rows[1];
                header.Range.Font.Size = (float)Math.Min(bodySize, 8.5);   // die Kopfzeile muss in einer Zeile bleiben
                header.Range.Font.Bold = -1;
                header.HeadingFormat = -1;               // Tabellenkopf auf jeder Folgeseite wiederholen
                table.Rows.AllowBreakAcrossPages = 0;
                PlanText headerMark = new PlanText("", "", Mark.Yellow, plan.HeaderNote);
                Remember(marks, header.Range, headerMark);
                Align(table, positionsX);
                Paragraph(cursor, "", fontName, fontSize, false, indent);

                // ---- Summenblock aus der Standardvorlage; in Word wird nicht gerechnet
                dynamic sums = InsertTable(cursor, totals);
                while ((int)sums.Rows.Count > plan.Totals.Count) { sums.Rows[(int)sums.Rows.Count].Delete(); }
                while ((int)sums.Rows.Count < plan.Totals.Count) { sums.Rows.Add(); }
                for (int i = 0; i < plan.Totals.Count; i++)
                {
                    dynamic row = sums.Rows[i + 1];
                    row.Cells[1].Range.Text = plan.Totals[i].Label;
                    row.Cells[2].Range.Text = plan.Totals[i].Value;
                    bool bold = plan.Totals[i].Label == "Gesamtbetrag:" || plan.Totals[i].Label == "Zahlbetrag:";
                    row.Range.Font.Bold = bold ? -1 : 0;
                    Remember(marks, row.Range, plan.Totals[i]);
                }
                sums.Range.Font.Name = fontName;
                sums.Range.Font.Size = (float)fontSize;
                sums.Range.ParagraphFormat.SpaceBefore = 0;
                sums.Range.ParagraphFormat.SpaceAfter = 0;
                sums.Range.ParagraphFormat.LineSpacingRule = 0;
                sums.Range.ParagraphFormat.KeepWithNext = -1;   // Summenblock nicht über den Seitenwechsel trennen
                sums.Rows[(int)sums.Rows.Count].Range.ParagraphFormat.KeepWithNext = 0;   // die letzte Zeile hält nichts Folgendes fest
                Align(sums, totalsX);
                if (plan.Payment != null || plan.Closing.Count > 0) { Paragraph(cursor, "", fontName, fontSize, false, indent); }

                if (plan.Payment != null)
                {
                    dynamic p = Paragraph(cursor, plan.Payment.Line, fontName, fontSize, false, indent);
                    Remember(marks, p.Range, plan.Payment);
                    Paragraph(cursor, "", fontName, fontSize, false, indent);
                }
                foreach (string closing in plan.Closing) { Paragraph(cursor, closing, fontName, bodySize, false, indent); }
                // der Absatz, der den neuen Teil abschließt, bleibt leer und so klein wie möglich (sonst kann er eine leere Seite erzeugen)
                dynamic tail = cursor.Paragraphs[1];
                Format(tail.Range, fontName, 1, false, indent);
                tail.Format.SpaceAfter = 0;
                tail.Format.LineSpacingRule = 4;
                tail.Format.LineSpacing = 1;

                template.Close(false);
                template = null;

                // ---- Kontrollen
                if ((int)d.Shapes.Count < shapesBefore + 1)
                {
                    throw new OfficeException("Beim Ersetzen des Rechnungsteils wäre ein Bild oder Textfeld des Briefkopfs verloren gegangen. "
                        + "Der Umbau wurde abgebrochen, es wurde nichts gespeichert. Bitte die Vorlage nach der Anleitung „Rechnungsvorlage anpassen“ von Hand umbauen.");
                }
                // Die Überschrift muss unter dem Anschriftenfeld stehen, auch wenn der Textkörper weiter oben beginnt:
                // Der Ankerabsatz wird in die Höhe des Felds gerückt; Word setzt ihn und alles Folgende dann darunter.
                d.Repaginate();                          // erst danach stimmen die gemessenen Positionen
                double anchorTop = Number(anchor.Range.Information(6));
                if (anchorTop > 0 && anchorTop < AddressTop)
                {
                    anchor.Format.SpaceBefore = (float)(AddressTop + 2 - anchorTop);
                }
                d.Repaginate();
                // Formen, die an einem Absatz hängen, wandern mit dem Text: alte Lage auf der Seite wiederherstellen
                foreach (dynamic shape in d.Shapes)
                {
                    string name = (string)shape.Name;
                    int relative = (int)shape.RelativeVerticalPosition;
                    double old;
                    if ((relative != 2 && relative != 3) || !pageTops.TryGetValue(name, out old)) { continue; }
                    double now = PageTop(shape);
                    if (double.IsNaN(now) || Math.Abs(now - old) < 0.5) { continue; }
                    if ((int)shape.Anchor.Information(3) != 1)   // Anker nicht mehr auf Seite 1
                    {
                        result.Notes.Add("„" + name + "“ hängt an einem Absatz, der jetzt auf einer anderen Seite steht. Bitte die Lage im Test-PDF prüfen.");
                        continue;
                    }
                    shape.Top = (float)(Number(shape.Top) + old - now);
                }
                CheckShapes(d, box, result);

                d.Save();
                // PDF/A, damit das Test-PDF auch zur Verarbeitung taugt
                d.ExportAsFixedFormat(OutputFileName: names.Pdf, ExportFormat: 17, OpenAfterExport: false, UseISO19005_1: true);
                ApplyMarks(d, marks);
                d.SaveAs2(names.Marked, 16);
                result.Workbook = names.Workbook;
                result.Marked = names.Marked;
                result.Pdf = names.Pdf;
            }
            finally
            {
                if (template != null) { try { template.Close(false); } catch (Exception) { } }
                try { d.Close(false); } catch (Exception) { }
            }
            return result;
        }

        /// <summary>Oberkante einer Form vom oberen Blattrand; NaN, wenn sie sich nicht bestimmen lässt.</summary>
        private static double PageTop(dynamic shape)
        {
            try
            {
                int relative = (int)shape.RelativeVerticalPosition;
                double top = Number(shape.Top);
                if (relative == 1) { return top; }                                           // Seite
                if (relative == 0) { return top + Number(shape.Anchor.Sections[1].PageSetup.TopMargin); }   // Seitenrand
                if (relative == 2 || relative == 3) { return top + Number(shape.Anchor.Information(6)); }   // Absatz, Zeile
            }
            catch (Exception)
            {
                // gemischte Abschnitte o. Ä.
            }
            return double.NaN;
        }

        /// <summary>Häufigste Schriftgröße des Rechnungsteils, gewichtet nach Textlänge (zwischen 8 und 11 pt).</summary>
        private static double DominantFontSize(dynamic d, Analysis analysis, double fallback)
        {
            Dictionary<int, int> weight = new Dictionary<int, int>();
            dynamic region = d.Range(analysis.RegionStart, analysis.RegionEnd);
            int count = Math.Min((int)region.Paragraphs.Count, 400);
            for (int i = 1; i <= count; i++)
            {
                dynamic range = region.Paragraphs[i].Range;
                int length = (int)range.End - (int)range.Start;
                double size = Number(range.Font.Size);
                if (length < 3 || size < 6 || size > 72) { continue; }   // gemischte Größen liefert Word als 9999999
                int key = (int)Math.Round(size * 2);
                int old;
                weight.TryGetValue(key, out old);
                weight[key] = old + length;
            }
            int best = 0, most = 0;
            foreach (KeyValuePair<int, int> w in weight)
            {
                if (w.Value > most) { most = w.Value; best = w.Key; }
            }
            return best == 0 ? fallback : Math.Max(8, Math.Min(11, best / 2.0));
        }

        /// <summary>
        /// Leert den Rechnungsteil und liefert die Einfügestelle. Tabellen, die nur teilweise zum Rechnungsteil
        /// gehören, werden an dessen Grenzen geteilt. Absätze und Tabellenzeilen mit verankerten Bildern oder
        /// Textfeldern bleiben stehen und werden nur geleert.
        /// </summary>
        private static dynamic ClearRegion(dynamic d, Analysis analysis, ConversionResult result)
        {
            dynamic first = d.Range(analysis.RegionStart, analysis.RegionStart);
            dynamic last = d.Range(analysis.RegionEnd - 1, analysis.RegionEnd - 1);
            try
            {
                if ((bool)first.Information(12))
                {
                    int row = (int)first.Information(13);
                    if (row > 1) { first.Tables[1].Split(row); }
                }
                if ((bool)last.Information(12))
                {
                    int row = (int)last.Information(13);
                    int rows = (int)last.Tables[1].Range.Information(15);
                    if (row < rows) { last.Tables[1].Split(row + 1); }
                }
            }
            catch (OfficeException) { throw; }
            catch (Exception e)
            {
                throw new OfficeException("Die Tabelle mit dem Rechnungsteil ließ sich nicht an dessen Grenzen teilen (verbundene Zellen?): " + e.Message);
            }
            int start = (bool)first.Information(12) ? (int)first.Tables[1].Range.Start : (int)first.Paragraphs[1].Range.Start;
            int end = (bool)last.Information(12) ? (int)last.Tables[1].Range.End : (int)last.Paragraphs[1].Range.End;
            dynamic region = d.Range(start, end);

            // Bilder und Textfelder, die im Rechnungsteil verankert sind (der Anker ist ein unsichtbares Zeichen im Text)
            List<object> anchors = new List<object>();
            foreach (dynamic shape in d.Shapes)
            {
                int position = (int)shape.Anchor.Start;
                if (position >= start && position < end) { anchors.Add(shape); }
            }
            int pictures = (int)region.InlineShapes.Count;
            if (pictures > 0)
            {
                result.Notes.Add(pictures + " Bild(er) standen im Rechnungsteil im Text (z. B. in einer Positionsbeschreibung) und sind in der neuen Datei nicht enthalten.");
            }

            for (int t = (int)region.Tables.Count; t >= 1; t--)
            {
                dynamic table = region.Tables[t];
                int tableStart = (int)table.Range.Start;
                int tableEnd = (int)table.Range.End;
                if (tableStart < (int)region.Start || tableEnd > (int)region.End) { continue; }
                HashSet<int> keep = new HashSet<int>();
                foreach (dynamic shape in anchors)
                {
                    dynamic a = shape.Anchor;
                    if ((int)a.Start >= tableStart && (int)a.Start < tableEnd) { keep.Add((int)a.Information(13)); }
                }
                if (keep.Count == 0)
                {
                    table.Delete();
                    continue;
                }
                int rows = (int)table.Range.Information(15);
                for (int r = rows; r >= 1; r--)
                {
                    if (keep.Contains(r)) { continue; }
                    foreach (dynamic cell in table.Range.Cells)
                    {
                        if ((int)cell.RowIndex == r)
                        {
                            cell.Delete(2);              // ganze Zeile löschen
                            break;
                        }
                    }
                }
                foreach (dynamic cell in table.Range.Cells) { EmptyParagraphs(d, cell.Range, anchors); }
                table.Range.Font.Size = 1;
                table.Range.ParagraphFormat.SpaceBefore = 0;
                table.Range.ParagraphFormat.SpaceAfter = 0;
                table.Range.ParagraphFormat.LineSpacingRule = 0;
                try
                {
                    table.Rows.HeightRule = 0;           // automatisch: die geleerte Zeile schrumpft auf die Schrifthöhe
                    table.Borders.Enable = 0;
                }
                catch (Exception) { }
            }
            // Absätze außerhalb von Tabellen
            for (int i = (int)region.Paragraphs.Count; i >= 1; i--)
            {
                dynamic p = region.Paragraphs[i];
                dynamic range = p.Range;
                if ((bool)range.Information(12)) { continue; }
                if (HoldsAnchor(range, anchors))
                {
                    EmptyParagraphs(d, range, anchors);
                    range.Font.Size = 1;
                    p.Format.SpaceBefore = 0;
                    p.Format.SpaceAfter = 0;
                    p.Format.LineSpacingRule = 4;
                    p.Format.LineSpacing = 1;
                }
                else if ((int)range.End < (int)d.Content.End)
                {
                    range.Delete();
                }
                else
                {
                    range.MoveEnd(1, -1);                 // letzter Absatz des Dokuments: nur den Text entfernen
                    if ((int)range.End > (int)range.Start) { range.Delete(); }
                }
            }

            // Einfügestelle: ein eigener leerer Absatz hinter dem, was vom Rechnungsteil stehen bleibt
            dynamic cursor = d.Range((int)region.End, (int)region.End);
            if ((bool)cursor.Information(12))
            {
                // die Stelle liegt am Anfang einer folgenden Tabelle: Word setzt beim Teilen vor Zeile 1 einen Absatz davor
                cursor.Tables[1].Split(1);
                cursor = d.Range((int)cursor.Tables[1].Range.Start - 1, (int)cursor.Tables[1].Range.Start - 1);
            }
            cursor.InsertParagraphBefore();
            cursor.Collapse(1);
            return cursor;
        }

        private static bool HoldsAnchor(dynamic range, List<object> anchors)
        {
            foreach (dynamic shape in anchors)
            {
                int position = (int)shape.Anchor.Start;
                if (position >= (int)range.Start && position < (int)range.End) { return true; }
            }
            return false;
        }

        /// <summary>
        /// Entfernt den Text. Absätze mit verankerten Formen behalten ihre Absatzmarke und die Ankerzeichen:
        /// gelöscht wird nur, was zwischen und hinter den Ankern steht – sonst verschwände die Form mit.
        /// </summary>
        private static void EmptyParagraphs(dynamic d, dynamic range, List<object> anchors)
        {
            for (int i = (int)range.Paragraphs.Count; i >= 1; i--)
            {
                dynamic text = range.Paragraphs[i].Range;
                bool lastInCell = i == (int)range.Paragraphs.Count;
                int start = (int)text.Start;
                int end = (int)text.End;
                List<int[]> held = new List<int[]>();
                foreach (dynamic shape in anchors)
                {
                    dynamic a = shape.Anchor;
                    if ((int)a.Start >= start && (int)a.Start < end) { held.Add(new int[] { (int)a.Start, (int)a.End }); }
                }
                if (held.Count == 0 && !lastInCell)
                {
                    text.Delete();
                    continue;
                }
                held.Sort(delegate(int[] x, int[] y) { return y[0].CompareTo(x[0]); });   // von hinten nach vorn
                int cut = end - 1;                       // Absatz- bzw. Zellenmarke bleibt stehen
                foreach (int[] anchor in held)
                {
                    if (cut > anchor[1]) { d.Range(anchor[1], cut).Delete(); }
                    cut = anchor[0];
                }
                if (cut > start) { d.Range(start, cut).Delete(); }
            }
        }

        private static void Format(dynamic range, string fontName, double size, bool bold, double indent)
        {
            range.Style = -1;                            // Standard
            range.Font.Name = fontName;
            range.Font.Size = (float)size;
            range.Font.Bold = bold ? -1 : 0;
            range.Font.Italic = 0;
            range.Font.Underline = 0;
            range.Font.Color = Automatic;
            dynamic format = range.ParagraphFormat;
            format.Alignment = 0;
            format.LeftIndent = (float)indent;
            format.RightIndent = 0;
            format.FirstLineIndent = 0;
            format.SpaceBefore = 0;
            format.SpaceAfter = 2;
            format.LineSpacingRule = 0;
            format.KeepWithNext = 0;
            format.PageBreakBefore = 0;
        }

        /// <summary>Fügt an der Einfügestelle einen Absatz ein und rückt die Stelle dahinter.</summary>
        private static dynamic Paragraph(dynamic cursor, string text, string fontName, double size, bool bold, double indent)
        {
            cursor.InsertBefore(text + "\r");
            dynamic paragraph = cursor.Paragraphs[1];
            Format(paragraph.Range, fontName, size, bold, indent);
            cursor.Collapse(0);
            return paragraph;
        }

        /// <summary>Überträgt eine Tabelle der Standardvorlage samt Formatierung (ohne Zwischenablage).</summary>
        private static dynamic InsertTable(dynamic cursor, dynamic source)
        {
            cursor.FormattedText = source.Range.FormattedText;
            dynamic table = cursor.Tables[1];
            cursor.SetRange((int)table.Range.End, (int)table.Range.End);
            return table;
        }

        /// <summary>Rückt die Tabelle so, dass ihr Text an derselben Stelle der Seite steht wie in der Standardvorlage.</summary>
        private static void Align(dynamic table, double targetX)
        {
            for (int i = 0; i < 3; i++)
            {
                double x = Number(table.Cell(1, 1).Range.Information(5));
                if (x < 0 || Math.Abs(x - targetX) < 0.3) { return; }
                table.Rows.LeftIndent = (float)(Number(table.Rows.LeftIndent) + targetX - x);
            }
        }

        private static void Remember(List<Marked> marks, dynamic range, PlanText text)
        {
            if (text.Mark == Mark.None) { return; }
            Marked m = new Marked();
            m.Range = range;
            m.Mark = text.Mark;
            m.Note = text.Note;
            marks.Add(m);
        }

        /// <summary>Textfelder des Kunden, die im Bereich des Anschriftenfelds liegen: Absenderzeile darüber setzen, sonst melden.</summary>
        private static void CheckShapes(dynamic d, dynamic addressBox, ConversionResult result)
        {
            foreach (dynamic shape in d.Shapes)
            {
                if ((string)shape.Name == (string)addressBox.Name) { continue; }
                if ((int)shape.RelativeVerticalPosition != 1 || (int)shape.RelativeHorizontalPosition != 1)
                {
                    continue;   // nicht an der Seite ausgerichtet: Lage hängt vom Textfluss ab und wird nicht verändert
                }
                double top = Number(shape.Top);
                double height = Number(shape.Height);
                double left = Number(shape.Left);
                if (top + height <= 148 || top >= 206 || left >= 310) { continue; }
                bool hasText = false;
                try { hasText = (int)shape.TextFrame.HasText != 0; } catch (Exception) { }
                if (hasText && height <= 40 && top < 160)
                {
                    double moved = top + height - 146;
                    shape.Top = (float)(top - moved);
                    result.Notes.Add("„" + (string)shape.Name + "“ (Absenderzeile) lag im Bereich des Anschriftenfelds und wurde um "
                        + moved.ToString("0", CultureInfo.InvariantCulture) + " pt nach oben gesetzt.");
                }
                else
                {
                    result.Notes.Add("„" + (string)shape.Name + "“ liegt im Bereich des Anschriftenfelds (5,3 bis 7,3 cm von oben, links). "
                        + "Steht dort Text, wird die Anschrift falsch gelesen – bitte im Test-PDF prüfen und das Element verschieben.");
                }
            }
        }

        private static void ApplyMarks(dynamic d, List<Marked> marks)
        {
            List<string> red = new List<string>(), yellow = new List<string>(), green = new List<string>();
            foreach (Marked m in marks)
            {
                dynamic range = m.Range;
                range.Shading.BackgroundPatternColor = m.Mark == Mark.Red ? Red
                    : (m.Mark == Mark.Yellow ? Yellow : Green);
                List<string> list = m.Mark == Mark.Red ? red : (m.Mark == Mark.Yellow ? yellow : green);
                if (m.Note.Length > 0 && !list.Contains(m.Note)) { list.Add(m.Note); }
            }
            List<string> legend = new List<string>();
            legend.Add("Legende zur markierten Fassung (diese Seite nicht mitsenden)");
            legend.Add("");
            legend.Add("ROT – Pflichtangabe, die auf der bisherigen Rechnung fehlte (leere rote Stellen bitte ausfüllen):");
            legend.AddRange(red);
            legend.Add("");
            legend.Add("GELB – war vorhanden, aber Beschriftung, Schreibweise oder Platz hat sich geändert (bitte prüfen):");
            legend.AddRange(yellow);
            legend.Add("");
            legend.Add("GRÜN – empfohlen:");
            legend.AddRange(green);
            legend.Add("");
            legend.Add("Nicht verändern: Position des Anschriftenfelds, Spaltenbreiten der Positionstabelle, die Beschriftungen mit Doppelpunkt.");
            legend.Add("Mehrseitige Rechnungen: Der Tabellenkopf wiederholt sich auf jeder Seite (Tabelleneigenschaften > Zeile > Gleiche Kopfzeile auf jeder Seite wiederholen). Das nicht abschalten.");
            legend.Add("Als PDF speichern: Datei > Speichern unter > PDF > Optionen > „ISO 19005-1-kompatibel (PDF/A)“.");
            legend.Add("Diese markierte Fassung dient nur zur Kontrolle; für Rechnungen die Datei ohne „_markiert“ verwenden.");
            // eigene Seite am Dokumentende (Seitenumbruch über das Absatzformat: funktioniert auch hinter Tabellen)
            int from = (int)d.Content.End;
            d.Content.InsertAfter("\r" + string.Join("\r", legend.ToArray()));
            dynamic end = d.Range(from, (int)d.Content.End);
            end.Style = -1;
            end.ParagraphFormat.PageBreakBefore = 0;
            // eigene Seite: der Absatz mit der Überschrift der Legende beginnt auf einer neuen Seite
            for (int i = (int)d.Paragraphs.Count; i >= 1; i--)
            {
                dynamic paragraph = d.Paragraphs[i];
                if (((string)paragraph.Range.Text).StartsWith(legend[0], StringComparison.Ordinal))
                {
                    paragraph.Format.PageBreakBefore = -1;
                    break;
                }
            }
            end.Font.Size = 10;
            end.Font.Bold = 0;
            end.ParagraphFormat.LeftIndent = 0;
            end.ParagraphFormat.SpaceAfter = 3;
            end.Shading.BackgroundPatternColor = Automatic;
        }
    }
}
