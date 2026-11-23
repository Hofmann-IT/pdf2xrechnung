// Vorlagen-Assistent (ADR 0016, Phase 2): Bauplan des neuen Excel-Blatts im Aufbau des Profils
// "standard" – ohne Office, damit er sich testen lässt. Spalten, Zeilenhöhen und Beschriftungen
// entsprechen der mitgelieferten Rechnungsvorlage.xlsx; gerechnet wird mit denselben Formeln
// (Gesamt = RUNDEN(Menge × Einzelpreis; 2), USt = RUNDEN(Netto × Satz; 2)).
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text.RegularExpressions;

namespace HofmannIT.ERechnung.Verwaltung
{
    /// <summary>Rot: Pflichtangabe, die bisher fehlte. Gelb: vorhanden, aber geändert oder zu prüfen. Grün: empfohlen.</summary>
    public enum Mark { None, Red, Yellow, Green }

    public enum CellAlign { General, Left, Right }

    /// <summary>Ein Schreibvorgang im neuen Blatt.</summary>
    public sealed class CellOp
    {
        public string Range = "";
        public bool Merge;
        public string Text;
        public bool HasNumber;
        public decimal Number;
        public string Formula = "";
        public bool TextFormat;
        public bool AmountFormat;
        public bool Bold;
        public double FontSize;
        public CellAlign Align = CellAlign.General;
        public bool Wrap;
        public bool BorderTop;
        public bool BorderBottom;
        public Mark Mark = Mark.None;
        public string Note = "";
    }

    public sealed class ExcelPlan
    {
        /// <summary>Spaltenbreiten in Punkt: Positionen des Profils "standard" bei 56,7 pt linkem Rand.</summary>
        public static readonly KeyValuePair<string, double>[] ColumnWidths =
        {
            new KeyValuePair<string, double>("A", 33.3), new KeyValuePair<string, double>("B", 222), new KeyValuePair<string, double>("C", 44),
            new KeyValuePair<string, double>("D", 6), new KeyValuePair<string, double>("E", 36), new KeyValuePair<string, double>("F", 60),
            new KeyValuePair<string, double>("G", 38), new KeyValuePair<string, double>("H", 56)
        };
        public const double LeftMargin = 56.7;
        public const double RightMargin = 36;
        public const double TopMargin = 28.35;
        public const double BottomMargin = 28.35;
        /// <summary>Zeilen 1–7 je 15 pt, Zeile 8 16,5 pt: Zeile 9 (Anschrift) beginnt 150 pt unter dem Blattrand.</summary>
        public const int AddressRow = 9;
        public const double RowHeight = 14;

        public readonly List<CellOp> Ops = new List<CellOp>();
        /// <summary>Zeilen mit umbrochenem Text, deren Höhe Excel anpassen soll.</summary>
        public readonly List<int> AutoFitRows = new List<int>();
        /// <summary>Zeilen mit fester, abweichender Höhe in Punkt (verbundene Zellen passt Excel nicht selbst an).</summary>
        public readonly Dictionary<int, double> RowHeights = new Dictionary<int, double>();
        public int HeaderRow;
        public int FirstItemRow;
        public int SpareRow;
        public int NetRow;
        public int GrossRow;
        public int LastRow;
        /// <summary>Hinweise für den Benutzer (Platzhalter, fehlende Angaben).</summary>
        public readonly List<string> Notes = new List<string>();

        public CellOp Find(string range)
        {
            foreach (CellOp op in Ops)
            {
                if (op.Range == range) { return op; }
            }
            return null;
        }
    }

    public static class ExcelLayout
    {
        private static readonly CultureInfo Invariant = CultureInfo.InvariantCulture;

        public static ExcelPlan Build(Analysis a)
        {
            ExcelPlan p = new ExcelPlan();
            int row = ExcelPlan.AddressRow;

            // ---- Anschriftenfeld: Zeile 9 Name, 10 Straße, 11 PLZ Ort, 12 E-Mail
            FieldMatch name = a.Get(InvoiceField.BuyerName);
            Text(p, "B9", name.Value, name.Status == MatchStatus.Missing ? Mark.Red : (name.Note.Length > 0 ? Mark.Yellow : Mark.None),
                name.Status == MatchStatus.Missing ? "Pflicht: Name oder Firma des Kunden in der ersten Zeile des Anschriftenfelds."
                : (name.Note.Length > 0 ? "Geändert: " + name.Note + ". Der Name steht in der ersten Zeile des Anschriftenfelds." : ""));
            Text(p, "B10", a.Get(InvoiceField.BuyerStreet).Value, Mark.None, "");
            Text(p, "B11", a.Get(InvoiceField.BuyerCity).Value, Mark.None, "");
            FieldMatch mail = a.Get(InvoiceField.BuyerEmail);
            if (mail.Status == MatchStatus.Missing)
            {
                Text(p, "B12", "E-Mail:", Mark.Red, "NEU, Pflicht: E-Mail-Adresse des Kunden hinter „E-Mail:“ eintragen.");
                p.Notes.Add("Die E-Mail-Adresse des Kunden fehlt (Zelle B12, rot). Ohne sie bleibt die Rechnung in der manuellen Prüfung.");
            }
            else
            {
                Text(p, "B12", "E-Mail: " + mail.Value, mail.Place.Length == 0 ? Mark.Red : Mark.None,
                    mail.Place.Length == 0 ? "NEU, Pflicht: E-Mail-Adresse des Kunden (stand bisher nicht auf der Rechnung)." : "");
            }

            CellOp title = Text(p, "B15", "Rechnung", Mark.None, "");
            title.Bold = true;
            title.FontSize = 14;
            p.RowHeights[15] = 20;

            // ---- Angaben zur Rechnung: Beschriftung in C:F, Wert in G:H
            row = 17;
            row = Info(p, row, a.Get(InvoiceField.InvoiceNumber), true);
            row = Info(p, row, a.Get(InvoiceField.InvoiceDate), true);
            FieldMatch service = a.Get(InvoiceField.ServiceDate);
            DateTime ignored;
            if (service.Status != MatchStatus.Missing && Values.TryDate(service.Value, out ignored))
            {
                row = Info(p, row, service, false);
            }
            else
            {
                p.Notes.Add("Kein Leistungsdatum als Datum (TT.MM.JJJJ) auf der Rechnung" + (service.Value.Length > 0 ? " („" + service.Value + "“ bleibt als Text stehen)" : "")
                    + ". Empfohlen: eine Zeile „Leistungsdatum:“ unter dem Rechnungsdatum ergänzen.");
            }
            row = Info(p, row, a.Get(InvoiceField.CustomerNumber), true);
            if (a.Get(InvoiceField.OrderReference).Status != MatchStatus.Missing) { row = Info(p, row, a.Get(InvoiceField.OrderReference), false); }

            // ---- Zusatzzeilen des Kunden über der Tabelle
            row++;
            foreach (string extra in a.ExtraLines) { row = FreeLine(p, row, extra); }
            if (a.ExtraLines.Count > 0) { row++; }

            // ---- Positionstabelle
            p.HeaderRow = row;
            string[] head = { "A", "Pos.", "B", "Bezeichnung", "C", "Menge", "E", "Einheit", "F", "Einzelpreis", "G", "USt", "H", "Gesamt" };
            for (int i = 0; i < head.Length; i += 2)
            {
                CellOp h = Text(p, head[i] + row, head[i + 1], Mark.Yellow, i == 0 ? "Geändert: Kopfzeile und Spaltenfolge Pos. | Bezeichnung | Menge | Einheit | Einzelpreis | USt | Gesamt"
                    + (a.TableHeader.Length > 0 ? " (bisher: " + a.TableHeader + ")." : ".") : "");
                h.Bold = true;
                h.BorderBottom = true;
                h.Align = head[i] == "C" || head[i] == "F" || head[i] == "G" || head[i] == "H" ? CellAlign.Right : CellAlign.Left;
            }
            CellOp gap = Text(p, "D" + row, "", Mark.Yellow, "");
            gap.BorderBottom = true;
            row++;
            p.FirstItemRow = row;
            foreach (InvoiceItem item in a.Items) { row = Item(p, row, item); }
            p.SpareRow = row;   // leere Zeile für weitere Positionen; die Summenformel schließt sie ein
            row += 2;

            // ---- Summenblock
            string first = "H" + p.FirstItemRow;
            string spare = "H" + p.SpareRow;
            FieldMatch net = a.Get(InvoiceField.NetTotal);
            p.NetRow = row;
            CellOp sum = Total(p, row, "Summe netto:", "=SUM(" + first + ":" + spare + ")",
                net.Status == MatchStatus.Missing ? Mark.Red : Mark.Yellow,
                net.Status == MatchStatus.Missing ? "NEU, Pflicht: Zeile „Summe netto:“."
                : "Geändert: Beschriftung „Summe netto:“" + (net.SourceLabel.Length > 0 ? " (bisher „" + net.SourceLabel + "“)" : " (bisher ohne Beschriftung)") + ".");
            sum.BorderTop = true;
            p.Find("E" + row + ":G" + row).BorderTop = true;
            row++;
            int vatRow = 0;
            decimal rate;
            bool singleRate = a.VatRates.Count == 1 && decimal.TryParse(a.VatRates[0].Replace("%", "").Trim(), NumberStyles.Number, CultureInfo.GetCultureInfo("de-DE"), out rate);
            if (singleRate)
            {
                rate = decimal.Parse(a.VatRates[0].Replace("%", "").Trim(), NumberStyles.Number, CultureInfo.GetCultureInfo("de-DE"));
                Total(p, row, "Netto " + a.VatRates[0] + ":", "=H" + p.NetRow, Mark.Green, "Empfohlen: Steuerbasis je Steuersatz.");
                row++;
                vatRow = row;
                Total(p, row, "USt " + a.VatRates[0] + ":", "=ROUND(H" + (row - 1) + "*" + (rate / 100m).ToString(Invariant) + ",2)", Mark.Green, "Empfohlen: Steuerbetrag je Steuersatz.");
                row++;
            }
            int netTotalRow = row;
            Total(p, row, "Gesamt netto:", "=H" + p.NetRow, Mark.Red, "NEU, Pflicht: Zeile „Gesamt netto:“.");
            row++;
            int vatTotalRow = row;
            FieldMatch vat = a.Get(InvoiceField.VatAmount);
            if (singleRate)
            {
                Total(p, row, "USt gesamt:", "=H" + vatRow, Mark.Yellow, "Geändert: Beschriftung „USt gesamt:“" + (vat.SourceLabel.Length > 0 ? " (bisher „" + vat.SourceLabel + "“)." : "."));
            }
            else
            {
                decimal amount;
                bool known = Values.TryNumber(vat.Value, out amount);
                CellOp op = Total(p, row, "USt gesamt:", "", Mark.Red, a.VatRates.Count > 1
                    ? "Mehrere Steuersätze: bitte je Satz die Zeilen „Netto x %:“ und „USt x %:“ ergänzen und hier die Summe der Steuerbeträge eintragen."
                    : "Pflicht: Umsatzsteuer der Rechnung. Der Steuersatz war nicht erkennbar; bitte Betrag oder Formel eintragen.");
                if (known)
                {
                    op.HasNumber = true;
                    op.Number = amount;
                }
                p.Notes.Add(a.VatRates.Count > 1
                    ? "Mehrere Steuersätze: Die Umsatzsteuer wurde als fester Betrag übernommen (rot). Bitte die Zeilen je Steuersatz von Hand ergänzen."
                    : "Der Steuersatz war nicht erkennbar: „USt gesamt:“ und die Spalte „USt“ der Positionen bitte von Hand ausfüllen (rot).");
            }
            row++;
            p.GrossRow = row;
            FieldMatch gross = a.Get(InvoiceField.GrossTotal);
            CellOp g = Total(p, row, "Gesamtbetrag:", "=H" + netTotalRow + "+H" + vatTotalRow, gross.Status == MatchStatus.Missing ? Mark.Red : Mark.Yellow,
                gross.Status == MatchStatus.Missing ? "NEU, Pflicht: Zeile „Gesamtbetrag:“."
                : "Geändert: Beschriftung „Gesamtbetrag:“ mit Doppelpunkt, Betrag ohne „EUR“ dazwischen" + (gross.SourceLabel.Length > 0 ? " (bisher „" + gross.SourceLabel + "“)." : "."));
            g.Bold = true;
            p.Find("E" + row + ":G" + row).Bold = true;
            row++;
            FieldMatch payable = a.Get(InvoiceField.PayableAmount);
            bool payableOnInvoice = payable.Status == MatchStatus.Found && payable.Place.Length > 0;
            CellOp z = Total(p, row, "Zahlbetrag:", "=H" + p.GrossRow, payableOnInvoice ? Mark.Yellow : Mark.Red,
                payableOnInvoice ? "Geändert: Beschriftung „Zahlbetrag:“." : "NEU, Pflicht: Zeile „Zahlbetrag:“ (gleich dem Gesamtbetrag, solange nichts abgezogen wird).");
            z.Bold = true;
            p.Find("E" + row + ":G" + row).Bold = true;
            row += 2;

            // ---- Zahlungsbedingungen
            FieldMatch terms = a.Get(InvoiceField.PaymentTerms);
            FieldMatch due = a.Get(InvoiceField.DueDate);
            if (terms.Status != MatchStatus.Missing && terms.Value.Length > 0)
            {
                string text = terms.Value;
                if (due.Status != MatchStatus.Missing && !Regex.IsMatch(text, @"(zahlbar bis|fällig am|fälligkeit):?\s*\d{2}\.\d{2}\.\d{4}", RegexOptions.IgnoreCase))
                {
                    text = text.TrimEnd('.', ' ') + ", zahlbar bis " + due.Value;
                }
                Text(p, "B" + row, "Zahlungsbedingungen:", Mark.Green, "Empfohlen: Zeile „Zahlungsbedingungen:“ mit „zahlbar bis TT.MM.JJJJ“ (ergibt das Fälligkeitsdatum).");
                CellOp t = Text(p, "C" + row + ":H" + (row + 1), text, Mark.Green, "");
                t.Merge = true;
                t.Wrap = true;
                t.FontSize = 9;
                t.Align = CellAlign.Left;
                if (due.Status == MatchStatus.Missing)
                {
                    p.Notes.Add("In den Zahlungsbedingungen steht kein Fälligkeitsdatum. Empfohlen: „…, zahlbar bis TT.MM.JJJJ“ ergänzen.");
                }
                row += 3;
            }
            else
            {
                p.Notes.Add("Auf der Rechnung stehen keine Zahlungsbedingungen. Empfohlen: unter dem Summenblock eine Zeile „Zahlungsbedingungen: …, zahlbar bis TT.MM.JJJJ“ ergänzen.");
            }

            // ---- Schlusstexte des Kunden
            foreach (string closing in a.ClosingLines) { row = FreeLine(p, row, closing); }
            p.LastRow = row;
            return p;
        }

        private static CellOp Text(ExcelPlan p, string range, string text, Mark mark, string note)
        {
            CellOp op = new CellOp();
            op.Range = range;
            op.Text = text;
            op.TextFormat = true;
            op.Align = CellAlign.Left;
            op.Mark = mark;
            op.Note = note;
            p.Ops.Add(op);
            return op;
        }

        private static int Info(ExcelPlan p, int row, FieldMatch m, bool required)
        {
            string target = m.TargetLabel;
            bool missing = m.Status == MatchStatus.Missing || m.Value.Length == 0;
            bool relabelled = m.SourceLabel.Length > 0 && !string.Equals(target.TrimEnd(':'), m.SourceLabel.TrimEnd(':', ' '), StringComparison.OrdinalIgnoreCase);
            // ohne Fundstelle: vom Benutzer im Assistenten eingetragen, stand also nicht auf der bisherigen Rechnung
            bool added = !missing && m.Place.Length == 0;
            Mark mark = missing || added ? (required ? Mark.Red : Mark.Green) : (relabelled || m.Status == MatchStatus.Uncertain ? Mark.Yellow : Mark.None);
            string note = missing ? "Pflicht: „" + target + "“ muss ausgefüllt sein (stand bisher nicht auf der Rechnung)."
                : (added ? "NEU" + (required ? ", Pflicht" : "") + ": „" + target + "“ (stand bisher nicht auf der Rechnung)."
                : (relabelled ? "Geändert: eigene Zeile mit der Beschriftung „" + target + "“ (bisher „" + m.SourceLabel + "“)." : ""));
            CellOp label = Text(p, "C" + row + ":F" + row, target, mark, note);
            label.Merge = true;
            CellOp value = Text(p, "G" + row + ":H" + row, missing ? "" : m.Value, mark, "");
            value.Merge = true;
            if (missing && required)
            {
                p.Notes.Add("„" + target + "“ fehlt (Zeile " + row + ", rot). Ohne diese Angabe bleibt die Rechnung in der manuellen Prüfung.");
            }
            return row + 1;
        }

        /// <summary>Freie Textzeile des Kunden über die ganze Breite; lange Texte werden umbrochen.</summary>
        private static int FreeLine(ExcelPlan p, int row, string text)
        {
            CellOp op = Text(p, "B" + row + ":H" + row, text, Mark.None, "");
            op.Merge = true;
            op.Wrap = true;
            int lines = Math.Max(1, (int)Math.Ceiling(text.Length / 85.0));
            if (lines > 1) { p.RowHeights[row] = ExcelPlan.RowHeight * lines; }
            return row + 1;
        }

        private static int Item(ExcelPlan p, int row, InvoiceItem item)
        {
            decimal quantity, price, total;
            bool hasQuantity = Values.TryNumber(item.Quantity, out quantity);
            bool hasPrice = Values.TryNumber(item.UnitPrice, out price);
            bool hasTotal = Values.TryNumber(item.Total, out total);

            CellOp number = Text(p, "A" + row, item.Number, Mark.Yellow, "Geändert: nur die Zahl in der Spalte „Pos.“.");
            number.Align = CellAlign.Left;
            CellOp description = Text(p, "B" + row, item.Description.Count > 0 ? item.Description[0] : "", item.Description.Count == 0 ? Mark.Red : Mark.None,
                item.Description.Count == 0 ? "Pflicht: Bezeichnung der Position." : "");
            description.Wrap = true;
            p.AutoFitRows.Add(row);

            Amount(p, "C" + row, hasQuantity, quantity, hasQuantity ? Mark.Yellow : Mark.Red,
                hasQuantity ? "Geändert: Menge rechts der Bezeichnung." : "Pflicht: Menge der Position.");
            CellOp unit = Text(p, "E" + row, item.Unit, Mark.Yellow, "Einheit als Kurzform: Stk., Std., m, km, Tag, Pauschal.");
            unit.Align = CellAlign.Left;
            Amount(p, "F" + row, hasPrice, price, hasPrice ? Mark.Yellow : Mark.Red, hasPrice ? "Geändert: Einzelpreis ohne Euro-Zeichen." : "Pflicht: Einzelpreis (netto) der Position.");
            CellOp vat = Text(p, "G" + row, item.VatRate, item.VatRate.Length > 0 ? Mark.Yellow : Mark.Red,
                item.VatRate.Length > 0 ? "NEU: Spalte „USt“ mit dem Steuersatz der Position." : "Steuersatz der Position eintragen, z. B. 19 %.");
            vat.Align = CellAlign.Right;
            CellOp sum = Amount(p, "H" + row, false, 0m, Mark.Yellow, "Geändert: Gesamt = Menge × Einzelpreis, ohne Euro-Zeichen.");
            if (hasQuantity && hasPrice)
            {
                sum.Formula = "=ROUND(C" + row + "*F" + row + ",2)";
                if (hasTotal && Math.Abs(Math.Round(quantity * price, 2, MidpointRounding.AwayFromZero) - total) > 0.005m)
                {
                    sum.Mark = Mark.Red;
                    sum.Note = "Menge × Einzelpreis ergibt nicht den bisherigen Betrag " + item.Total + ". Bitte Menge und Einzelpreis prüfen.";
                    p.Notes.Add("Position " + item.Number + ": Menge × Einzelpreis ergibt nicht den bisherigen Betrag " + item.Total + " (Zelle H" + row + ", rot).");
                }
            }
            else
            {
                sum.HasNumber = hasTotal;
                sum.Number = total;
                sum.Mark = Mark.Red;
                sum.Note = "Menge oder Einzelpreis fehlt: Betrag fest übernommen. Bitte Menge und Einzelpreis eintragen und hier =RUNDEN(Menge*Einzelpreis;2) verwenden.";
                p.Notes.Add("Position " + item.Number + ": Menge oder Einzelpreis fehlt (Zeile " + row + ", rot).");
            }
            if (item.Notes.Exists(delegate(string n) { return n.StartsWith("Positionsnummer", StringComparison.Ordinal); }))
            {
                number.Note = "NEU: Positionsnummer ergänzt (stand nicht auf der Rechnung).";
            }
            row++;
            for (int i = 1; i < item.Description.Count; i++)
            {
                CellOp more = Text(p, "B" + row, item.Description[i], Mark.None, "");
                more.Wrap = true;
                foreach (string n in item.Notes)
                {
                    if (n.Contains("„" + item.Description[i] + "“"))
                    {
                        more.Mark = Mark.Yellow;
                        more.Note = "Geändert: Unterpunkt ohne eigenen Preis steht als Text in der Bezeichnung (keine eigenen Mengen-Zellen).";
                    }
                }
                p.AutoFitRows.Add(row);
                row++;
            }
            return row;
        }

        private static CellOp Amount(ExcelPlan p, string range, bool hasNumber, decimal number, Mark mark, string note)
        {
            CellOp op = new CellOp();
            op.Range = range;
            op.HasNumber = hasNumber;
            op.Number = number;
            op.AmountFormat = true;
            op.Align = CellAlign.Right;
            op.Mark = mark;
            op.Note = note;
            p.Ops.Add(op);
            return op;
        }

        private static CellOp Total(ExcelPlan p, int row, string label, string formula, Mark mark, string note)
        {
            CellOp l = Text(p, "E" + row + ":G" + row, label, mark, note);
            l.Merge = true;
            CellOp v = Amount(p, "H" + row, false, 0m, mark, "");
            v.Formula = formula;
            return v;
        }
    }

    /// <summary>Namen der erzeugten Dateien: neben dem Original, nie über eine vorhandene Datei.</summary>
    public sealed class OutputNames
    {
        public string Workbook = "";
        public string Marked = "";
        public string Pdf = "";

        public static OutputNames For(string directory, string sourceFileName, string extension)
        {
            string stem = Path.GetFileNameWithoutExtension(sourceFileName) + "_E-Rechnung";
            for (int n = 1; n < 1000; n++)
            {
                string suffix = n == 1 ? "" : " (" + n + ")";
                OutputNames names = new OutputNames();
                names.Workbook = Path.Combine(directory, stem + suffix + extension);
                names.Marked = Path.Combine(directory, stem + "_markiert" + suffix + extension);
                names.Pdf = Path.Combine(directory, stem + "_Test" + suffix + ".pdf");
                if (!File.Exists(names.Workbook) && !File.Exists(names.Marked) && !File.Exists(names.Pdf)) { return names; }
            }
            throw new IOException("Im Ordner " + directory + " liegen bereits zu viele umgebaute Fassungen dieser Datei.");
        }
    }
}
