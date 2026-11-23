// Vorlagen-Assistent (ADR 0016), Teil ohne Fenster und ohne Office: Momentaufnahme einer Word- oder
// Excel-Rechnung, Erkennung der Rechnungsangaben über bekannte Beschriftungen und Bericht.
// Hier wird nichts gerechnet und nichts entschieden, was eine fachliche Regel wäre: Fälle wie
// Abschlagsrechnung, § 13b UStG oder Skonto im Summenblock werden erkannt und abgelehnt.
using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace HofmannIT.ERechnung.Verwaltung
{
    /// <summary>Ein Textstück der Quelldatei: Excel-Zelle, Tabellenzelle oder (Teil eines) Absatz.</summary>
    public sealed class SourceCell
    {
        public int Column;
        public string Text = "";
        public string Place = "";

        public SourceCell(int column, string text, string place)
        {
            Column = column;
            Text = text == null ? "" : text.Trim();
            Place = place == null ? "" : place;
        }
    }

    /// <summary>Eine Zeile der Quelldatei in Lesereihenfolge.</summary>
    public sealed class SourceLine
    {
        public const string Header = "Kopfzeile";
        public const string Footer = "Fußzeile";

        public readonly List<SourceCell> Cells = new List<SourceCell>();
        /// <summary>Leer für Textkörper bzw. Tabellenblatt; sonst Kopfzeile, Fußzeile oder Name eines Textfelds.</summary>
        public string Container = "";
        public bool InTable;

        public SourceLine Add(int column, string text, string place)
        {
            if (text != null && text.Trim().Length > 0)
            {
                Cells.Add(new SourceCell(column, text, place));
            }
            return this;
        }

        public bool Body { get { return Container.Length == 0; } }

        public string Joined()
        {
            StringBuilder b = new StringBuilder();
            foreach (SourceCell c in Cells)
            {
                if (b.Length > 0) { b.Append(' '); }
                b.Append(c.Text);
            }
            return b.ToString();
        }

        public string Place { get { return Cells.Count == 0 ? "" : Cells[0].Place; } }
    }

    public sealed class SourceDocument
    {
        public string FileName = "";
        public bool Excel;
        public string Sheet = "";
        public readonly List<string> Sheets = new List<string>();
        public readonly List<SourceLine> Lines = new List<SourceLine>();
        /// <summary>Elemente, die der Assistent nicht anfasst (Bilder, Formen, Kopf- und Fußzeilen).</summary>
        public readonly List<string> Kept = new List<string>();
        public bool HasMacros;
        public bool Truncated;
    }

    public enum InvoiceField
    {
        InvoiceNumber, InvoiceDate, ServiceDate, CustomerNumber, OrderReference,
        BuyerName, BuyerStreet, BuyerCity, BuyerEmail,
        NetTotal, VatAmount, GrossTotal, PayableAmount, PaymentTerms, DueDate
    }

    public enum MatchStatus { Found, Uncertain, Missing }

    public sealed class FieldMatch
    {
        public InvoiceField Field;
        public MatchStatus Status = MatchStatus.Missing;
        public string Value = "";
        public string SourceLabel = "";
        public string Place = "";
        public string Note = "";

        /// <summary>
        /// Wert, den der Benutzer im Assistenten einträgt. War die Angabe nicht gefunden worden, bleibt die
        /// Fundstelle leer – daran erkennt der Umbau, dass sie auf der bisherigen Rechnung fehlte.
        /// </summary>
        public void Enter(string value)
        {
            string v = value == null ? "" : value.Trim();
            if (Status == MatchStatus.Missing) { Place = ""; SourceLabel = ""; }
            Value = v;
            Status = v.Length > 0 ? MatchStatus.Found : MatchStatus.Missing;
            Note = v.Length > 0 ? "von Ihnen eingetragen" : "";
        }

        public bool Required
        {
            get
            {
                switch (Field)
                {
                    case InvoiceField.ServiceDate:
                    case InvoiceField.OrderReference:
                    case InvoiceField.BuyerStreet:
                    case InvoiceField.BuyerCity:
                    case InvoiceField.PaymentTerms:
                    case InvoiceField.DueDate:
                        return false;
                    default:
                        return true;
                }
            }
        }

        /// <summary>Beschriftung, die das Profil "standard" auf der Rechnung erwartet.</summary>
        public string TargetLabel
        {
            get
            {
                switch (Field)
                {
                    case InvoiceField.InvoiceNumber: return "Rechnungsnummer:";
                    case InvoiceField.InvoiceDate: return "Rechnungsdatum:";
                    case InvoiceField.ServiceDate: return "Leistungsdatum:";
                    case InvoiceField.CustomerNumber: return "Kunden-Nr.:";
                    case InvoiceField.OrderReference: return "Ihre Bestellung:";
                    case InvoiceField.BuyerName: return "Anschrift, Zeile 1 (Firma/Name)";
                    case InvoiceField.BuyerStreet: return "Anschrift, Zeile 2 (Straße)";
                    case InvoiceField.BuyerCity: return "Anschrift, Zeile 3 (PLZ Ort)";
                    case InvoiceField.BuyerEmail: return "E-Mail:";
                    case InvoiceField.NetTotal: return "Summe netto: / Gesamt netto:";
                    case InvoiceField.VatAmount: return "USt gesamt:";
                    case InvoiceField.GrossTotal: return "Gesamtbetrag:";
                    case InvoiceField.PayableAmount: return "Zahlbetrag:";
                    case InvoiceField.PaymentTerms: return "Zahlungsbedingungen:";
                    default: return "zahlbar bis TT.MM.JJJJ";
                }
            }
        }
    }

    public sealed class InvoiceItem
    {
        public string Number = "";
        public readonly List<string> Description = new List<string>();
        public string Quantity = "";
        public string Unit = "";
        public string UnitPrice = "";
        public string VatRate = "";
        public string Total = "";
        public string Place = "";
        /// <summary>Hinweise zu dieser Position, z. B. übernommene Unterpunkte ohne eigenen Preis.</summary>
        public readonly List<string> Notes = new List<string>();

        public bool Complete
        {
            get { return Number.Length > 0 && Description.Count > 0 && Quantity.Length > 0 && UnitPrice.Length > 0 && Total.Length > 0; }
        }
    }

    public sealed class Analysis
    {
        public readonly List<FieldMatch> Fields = new List<FieldMatch>();
        public readonly List<InvoiceItem> Items = new List<InvoiceItem>();
        /// <summary>Gründe, aus denen die Datei nicht automatisch umgebaut wird (fachliche Regeln).</summary>
        public readonly List<string> Blockers = new List<string>();
        public readonly List<string> Warnings = new List<string>();
        public readonly List<string> VatRates = new List<string>();
        /// <summary>Textstücke, die einer Angabe, einer Position oder einer Summe zugeordnet wurden.</summary>
        public readonly HashSet<SourceCell> Used = new HashSet<SourceCell>();
        /// <summary>Textstücke oberhalb der ersten erkannten Angabe: Briefkopf, bleibt an seinem Platz.</summary>
        public readonly List<SourceCell> LetterheadCells = new List<SourceCell>();
        /// <summary>Nicht zugeordnete Zeilen zwischen Anschrift und Positionstabelle (z. B. „Bauvorhaben: …“).</summary>
        public readonly List<string> ExtraLines = new List<string>();
        /// <summary>Nicht zugeordnete Zeilen unter dem Summenblock (Grußformel, Hinweise).</summary>
        public readonly List<string> ClosingLines = new List<string>();
        public string TableHeader = "";
        public string TableHeaderPlace = "";

        public FieldMatch Get(InvoiceField field)
        {
            foreach (FieldMatch m in Fields)
            {
                if (m.Field == field) { return m; }
            }
            throw new ArgumentException("Feld fehlt: " + field);
        }

        public bool Convertible { get { return Blockers.Count == 0 && TableHeader.Length > 0 && Items.Count > 0; } }

        public int MissingRequired
        {
            get
            {
                int n = 0;
                foreach (FieldMatch m in Fields)
                {
                    if (m.Required && m.Status == MatchStatus.Missing) { n++; }
                }
                return n;
            }
        }
    }

    /// <summary>Beträge, Mengen und Datumsangaben in deutscher Schreibweise.</summary>
    public static class Values
    {
        private static readonly CultureInfo German = CultureInfo.GetCultureInfo("de-DE");
        private static readonly Regex Number = new Regex(@"^-?(\d{1,3}(\.\d{3})+|\d+)(,\d+)?$");
        private static readonly Regex AmountInText = new Regex(@"-?(?:\d{1,3}(?:\.\d{3})+|\d+),\d{2}(?!\d)");
        private static readonly Regex NumericDate = new Regex(@"(?<!\d)(\d{1,2})\.\s?(\d{1,2})\.\s?(\d{4}|\d{2})(?!\d)");
        private static readonly Regex LongDate = new Regex(@"(?<!\d)(\d{1,2})\.\s*(Januar|Februar|März|April|Mai|Juni|Juli|August|September|Oktober|November|Dezember)\s+(\d{4})", RegexOptions.IgnoreCase);
        private static readonly string[] Months = { "januar", "februar", "märz", "april", "mai", "juni", "juli", "august", "september", "oktober", "november", "dezember" };

        public static string StripCurrency(string text)
        {
            string t = (text == null ? "" : text).Replace("€", "").Replace(" ", " ");
            t = Regex.Replace(t, @"(?i)\b(EUR|Euro)\b", "");
            return t.Trim();
        }

        /// <summary>Liest eine Zahl wie 1.560,00 oder 2 oder "512,31 €".</summary>
        public static bool TryNumber(string text, out decimal value)
        {
            value = 0m;
            string t = StripCurrency(text).Replace(" ", "");
            if (!Number.IsMatch(t)) { return false; }
            return decimal.TryParse(t, NumberStyles.Number, German, out value);
        }

        /// <summary>Wie <see cref="TryNumber"/>, verlangt aber genau zwei Nachkommastellen (Geldbetrag).</summary>
        public static bool TryAmount(string text, out decimal value)
        {
            value = 0m;
            string t = StripCurrency(text).Replace(" ", "");
            Match m = AmountInText.Match(t);
            if (!m.Success || m.Length != t.Length) { return false; }
            return decimal.TryParse(t, NumberStyles.Number, German, out value);
        }

        /// <summary>Letzter Geldbetrag in einem Text, z. B. in "Endbetrag EUR 6.883,11".</summary>
        public static bool LastAmount(string text, out decimal value)
        {
            value = 0m;
            MatchCollection all = AmountInText.Matches(text == null ? "" : text);
            if (all.Count == 0) { return false; }
            return decimal.TryParse(all[all.Count - 1].Value, NumberStyles.Number, German, out value);
        }

        public static string Amount(decimal value)
        {
            return value.ToString("#,##0.00", German);
        }

        public static bool TryDate(string text, out DateTime date)
        {
            date = DateTime.MinValue;
            string t = text == null ? "" : text;
            Match m = NumericDate.Match(t);
            int day, month, year;
            if (m.Success)
            {
                day = int.Parse(m.Groups[1].Value);
                month = int.Parse(m.Groups[2].Value);
                year = int.Parse(m.Groups[3].Value);
                if (year < 100) { year += 2000; }
            }
            else
            {
                m = LongDate.Match(t);
                if (!m.Success) { return false; }
                day = int.Parse(m.Groups[1].Value);
                month = Array.IndexOf(Months, m.Groups[2].Value.ToLowerInvariant()) + 1;
                year = int.Parse(m.Groups[3].Value);
            }
            if (month < 1 || month > 12 || day < 1 || day > DateTime.DaysInMonth(year, month)) { return false; }
            date = new DateTime(year, month, day);
            return true;
        }

        public static string Date(DateTime date)
        {
            return date.ToString("dd.MM.yyyy", CultureInfo.InvariantCulture);
        }

        /// <summary>Kurzformen der Einheit, die das Profil "standard" auf UN/ECE-Codes abbildet.</summary>
        public static string Unit(string text)
        {
            string t = (text == null ? "" : text).Trim();
            string k = t.ToLowerInvariant().TrimEnd('.');
            if (k == "stück" || k == "stck" || k == "stk" || k == "st") { return "Stk."; }
            if (k == "std" || k == "stunde" || k == "stunden" || k == "h") { return "Std."; }
            if (k == "meter" || k == "lfm") { return "m"; }
            if (k == "tage" || k == "tag") { return "Tag"; }
            if (k == "psch" || k == "pauschal" || k == "pausch") { return "Pauschal"; }
            return t;
        }
    }

    /// <summary>Erkennt die Rechnungsangaben in einer Momentaufnahme.</summary>
    public static class TemplateAnalyzer
    {
        private const RegexOptions I = RegexOptions.IgnoreCase | RegexOptions.CultureInvariant;

        private static readonly Regex InvoiceNumberLabel = new Regex(@"^(rechnungsnummer|rechnungs?\s*-?\s*nr\.?|re\.?\s*-?\s*nr\.?|rechnung\b)\s*[:.]?\s*(.*)$", I);
        private static readonly Regex InvoiceDateLabel = new Regex(@"^(rechnungs-?datum|datum)\s*[:.]?\s*(.*)$", I);
        private static readonly Regex ServiceDateLabel = new Regex(@"^(leistungsdatum|lieferdatum|leistungszeitpunkt|leistungszeitraum|liefer-?\s*/?\s*leistungsdatum)\s*[:.]?\s*(.*)$", I);
        private static readonly Regex CustomerNumberLabel = new Regex(@"^(kunden\s*-?\s*nr\.?|kundennummer|kd\.?\s*-?\s*nr\.?)\s*[:.]?\s*(.*)$", I);
        private static readonly Regex OrderLabel = new Regex(@"^(ihre bestellung|bestell\s*-?\s*nr\.?|bestellnummer|ihre bestellnummer)\s*[:.]?\s*(.*)$", I);
        private static readonly Regex Email = new Regex(@"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}");
        private static readonly Regex PostCodeLine = new Regex(@"^(D-)?\d{5}\s+\S");
        private static readonly Regex Salutation = new Regex(@"^(herrn?|frau|firma|familie|an|eheleute)\.?$", I);
        private static readonly Regex NotAnAddress = new Regex(@"(\b(tel|telefon|telefax|fax|iban|bic|blz|bankverbindung|konto|kto|ust|steuernummer|amtsgericht|geschäftsführer)\b|www\.|https?:)", I);

        private static readonly Regex PosHeader = new Regex(@"^(pos\.?(\s*-?\s*nr\.?)?|position|lfd\.?\s*nr\.?|nr\.?)$", I);
        private static readonly Regex DescriptionHeader = new Regex(@"^(bezeichnung|beschreibung|artikel|leistung|leistungsbeschreibung|text|artikelbezeichnung)$", I);
        private static readonly Regex QuantityHeader = new Regex(@"^(menge|stück|stck\.?|stk\.?|anzahl|anz\.?)$", I);
        private static readonly Regex UnitHeader = new Regex(@"^(einheit|einh\.?|me)$", I);
        private static readonly Regex UnitPriceHeader = new Regex(@"^(ep|e\.?-?\s*preis|einzelpreis|preis|einheitspreis)(\s*(€|eur|netto|in €|in eur))*$", I);
        private static readonly Regex TotalHeader = new Regex(@"^(gp|g\.?-?\s*preis|ges\.?\s*-?\s*preis|gesamtpreis|gesamt|betrag|summe)(\s*(€|eur|netto|in €|in eur))*$", I);
        private static readonly Regex VatHeader = new Regex(@"^(ust\.?|mwst\.?|ust\s*%|mwst\s*%)$", I);
        private static readonly Regex ProgressHeader = new Regex(@"^ls\.?\s*%$", I);

        private static readonly Regex VatLine = new Regex(@"(mwst|ust|umsatzsteuer|mehrwertsteuer)\.?[^0-9%]*(\d{1,2}(?:,\d)?)\s*%", I);
        private static readonly Regex VatLineReversed = new Regex(@"(\d{1,2}(?:,\d)?)\s*%\s*(mwst|ust|umsatzsteuer|mehrwertsteuer)", I);
        private static readonly Regex PayableLine = new Regex(@"(zahlbetrag|zu zahlen|zu zahlender betrag)", I);
        private static readonly Regex GrossLine = new Regex(@"(endbetrag|gesamtbetrag|rechnungsbetrag|bruttobetrag|summe brutto|brutto|endsumme|rechnungssumme)", I);
        private static readonly Regex NetLine = new Regex(@"(summe netto|gesamt netto|nettobetrag|nettosumme|netto|zwischensumme|gesamtsumme|^summe)", I);
        private static readonly Regex DeductionLine = new Regex(@"(skonto|rabatt|nachlass|abzüglich|abzgl\.?|einbehalt|bereits (gezahlt|erhalten|berechnet)|abschlag|anzahlung)", I);
        private static readonly Regex PaymentLine = new Regex(@"(zahlungsbedingung|zahlbar|zahlungsziel|zahlung\s+(innerhalb|bis))", I);
        private static readonly Regex DueDateText = new Regex(@"(zahlbar bis|fällig am|bis zum|fälligkeit|spätestens (bis|am))", I);

        private static readonly Regex ReverseCharge = new Regex(@"(§\s*13\s*b|steuerschuldnerschaft|reverse[- ]?charge)", I);
        private static readonly Regex PartialInvoice = new Regex(@"(abschlagsrechnung|abschlagszahlung|schlussrechnung|schlußrechnung|teilrechnung|a\s*-?\s*conto|akonto)", I);

        private enum Role { None, Pos, Description, Quantity, Unit, UnitPrice, Vat, Total }

        public static Analysis Analyze(SourceDocument doc)
        {
            Analysis a = new Analysis();
            foreach (InvoiceField f in Enum.GetValues(typeof(InvoiceField)))
            {
                FieldMatch m = new FieldMatch();
                m.Field = f;
                a.Fields.Add(m);
            }
            List<SourceLine> body = new List<SourceLine>();
            List<SourceLine> searchable = new List<SourceLine>();
            foreach (SourceLine source in doc.Lines)
            {
                foreach (SourceLine line in Expand(source))
                {
                    if (line.Body) { body.Add(line); }
                    if (line.Container != SourceLine.Header && line.Container != SourceLine.Footer) { searchable.Add(line); }
                }
            }

            FindBlockers(a, searchable);
            Dictionary<Role, int> columns;
            bool pieces;
            int header = FindTableHeader(a, body, out columns, out pieces);
            int headerInSearchable = header < 0 ? searchable.Count : searchable.IndexOf(body[header]);

            Labelled(a, searchable, headerInSearchable, InvoiceField.InvoiceNumber, InvoiceNumberLabel, new Regex(@"^\S*\d\S*$"));
            LabelledDate(a, searchable, headerInSearchable, InvoiceField.InvoiceDate, InvoiceDateLabel);
            // der Leistungszeitpunkt steht oft unter den Summen oder in einem Textfeld: überall suchen
            LabelledDate(a, searchable, searchable.Count, InvoiceField.ServiceDate, ServiceDateLabel);
            Labelled(a, searchable, headerInSearchable, InvoiceField.CustomerNumber, CustomerNumberLabel, new Regex(@"^\S+$"));
            Labelled(a, searchable, headerInSearchable, InvoiceField.OrderReference, OrderLabel, new Regex(@"\S"));
            FindAddress(a, body, header < 0 ? body.Count : header);

            int end = header < 0 ? body.Count : ReadItems(a, body, header, columns, pieces);
            ReadTotals(a, body, end);
            FindPayment(a, searchable);
            CrossCheck(a);
            CollectRemaining(a, body, header, end);
            if (doc.HasMacros)
            {
                a.Warnings.Add("Die Datei enthält Makros. Sie gehen in der neuen Datei verloren (gespeichert wird ohne Makros).");
            }
            if (doc.Truncated)
            {
                a.Warnings.Add("Die Datei ist sehr groß; es wurde nur der Anfang ausgewertet.");
            }
            return a;
        }

        /// <summary>
        /// Eine Zeile, die nur aus einer Zelle mit mehreren Absätzen besteht (z. B. das Anschriftenfeld in
        /// einer Word-Tabellenzelle), wird in einzelne Zeilen zerlegt. Mehrzeilige Zellen neben anderen
        /// Zellen bleiben zusammen (mehrzeilige Bezeichnung einer Position).
        /// </summary>
        private static List<SourceLine> Expand(SourceLine source)
        {
            List<SourceLine> result = new List<SourceLine>();
            if (source.Cells.Count == 0) { return result; }
            if (source.Cells.Count > 1 || source.Cells[0].Text.IndexOf('\n') < 0)
            {
                result.Add(source);
                return result;
            }
            foreach (string part in source.Cells[0].Text.Split('\n'))
            {
                SourceLine line = new SourceLine();
                line.Container = source.Container;
                line.InTable = source.InTable;
                line.Add(source.Cells[0].Column, part, source.Cells[0].Place);
                if (line.Cells.Count > 0) { result.Add(line); }
            }
            return result;
        }

        private static void Use(Analysis a, SourceLine line)
        {
            foreach (SourceCell c in line.Cells) { a.Used.Add(c); }
        }

        /// <summary>
        /// Ordnet ein, was keiner Angabe zugeordnet wurde: oberhalb der ersten erkannten Angabe ist es Briefkopf,
        /// zwischen Anschrift und Tabelle eine Zusatzzeile, unter dem Summenblock ein Schlusstext.
        /// </summary>
        private static void CollectRemaining(Analysis a, List<SourceLine> body, int header, int end)
        {
            int limit = header < 0 ? body.Count : header;
            int first = limit;
            for (int i = 0; i < limit; i++)
            {
                bool used = false;
                foreach (SourceCell c in body[i].Cells) { if (a.Used.Contains(c)) { used = true; } }
                if (used) { first = i; break; }
            }
            for (int i = 0; i < first; i++)
            {
                foreach (SourceCell c in body[i].Cells) { a.LetterheadCells.Add(c); }
            }
            for (int i = first; i < limit; i++) { Remaining(a, body, i, a.ExtraLines); }
            if (header < 0) { return; }
            // Text zwischen Tabellenkopf und erster Position
            for (int i = header + 1; i < end; i++) { Remaining(a, body, i, a.ExtraLines); }
            for (int i = end; i < body.Count; i++) { Remaining(a, body, i, a.ClosingLines); }
        }

        private static void Remaining(Analysis a, List<SourceLine> body, int index, List<string> target)
        {
            SourceLine line = body[index];
            List<string> plain = new List<string>();
            for (int k = 0; k < line.Cells.Count; k++)
            {
                SourceCell c = line.Cells[k];
                if (a.Used.Contains(c)) { continue; }
                a.Used.Add(c);
                if (c.Text.EndsWith(":", StringComparison.Ordinal))
                {
                    // Beschriftung: Wert daneben oder darunter; ohne Wert entfällt sie
                    SourceCell value = null;
                    if (k + 1 < line.Cells.Count && !a.Used.Contains(line.Cells[k + 1]) && !line.Cells[k + 1].Text.EndsWith(":", StringComparison.Ordinal))
                    {
                        value = line.Cells[k + 1];
                    }
                    else if (index + 1 < body.Count)
                    {
                        foreach (SourceCell below in body[index + 1].Cells)
                        {
                            if (below.Column == c.Column && !a.Used.Contains(below) && !below.Text.EndsWith(":", StringComparison.Ordinal)) { value = below; }
                        }
                    }
                    if (value != null)
                    {
                        a.Used.Add(value);
                        target.Add(c.Text + " " + value.Text.Replace("\n", " "));
                    }
                    continue;
                }
                decimal number;
                if (Regex.IsMatch(c.Text, @"^(rechnung|gutschrift)$", I)) { continue; }   // Überschrift setzt der Umbau selbst
                if (Values.TryNumber(c.Text, out number))
                {
                    a.Warnings.Add("„" + c.Text + "“ (" + c.Place + ") ist eine Zahl ohne Beschriftung und wird nicht übernommen.");
                    continue;
                }
                plain.Add(c.Text.Replace("\n", " "));
            }
            if (plain.Count > 0) { target.Add(string.Join(" ", plain.ToArray())); }
        }

        private static void FindBlockers(Analysis a, List<SourceLine> lines)
        {
            bool reverse = false, partial = false;
            foreach (SourceLine line in lines)
            {
                string t = line.Joined();
                if (!reverse && ReverseCharge.IsMatch(t))
                {
                    reverse = true;
                    a.Blockers.Add("Hinweis auf § 13b UStG / Steuerschuldnerschaft des Leistungsempfängers (" + line.Place
                        + "). Solche Rechnungen deckt die Standardvorlage nicht ab; bitte mit Hofmann IT klären.");
                }
                if (!partial && PartialInvoice.IsMatch(t))
                {
                    partial = true;
                    a.Blockers.Add("Abschlags-, Teil- oder Schlussrechnung (" + line.Place
                        + "). Solche Rechnungen deckt die Standardvorlage nicht ab; bitte mit Hofmann IT klären.");
                }
            }
        }

        private static Role RoleOf(string text)
        {
            string t = Regex.Replace(text.Trim(), @"\s+", " ").TrimEnd(':');
            if (PosHeader.IsMatch(t)) { return Role.Pos; }
            if (DescriptionHeader.IsMatch(t)) { return Role.Description; }
            if (QuantityHeader.IsMatch(t)) { return Role.Quantity; }
            if (UnitHeader.IsMatch(t)) { return Role.Unit; }
            if (UnitPriceHeader.IsMatch(t)) { return Role.UnitPrice; }
            if (VatHeader.IsMatch(t)) { return Role.Vat; }
            if (TotalHeader.IsMatch(t)) { return Role.Total; }
            return Role.None;
        }

        private static int FindTableHeader(Analysis a, List<SourceLine> body, out Dictionary<Role, int> columns, out bool pieces)
        {
            columns = new Dictionary<Role, int>();
            pieces = false;
            for (int i = 0; i < body.Count; i++)
            {
                Dictionary<Role, int> found = new Dictionary<Role, int>();
                bool progress = false;
                foreach (SourceCell c in body[i].Cells)
                {
                    Role r = RoleOf(c.Text);
                    if (r == Role.Quantity && Regex.IsMatch(c.Text.Trim(), @"^(stück|stck\.?|stk\.?)$", I)) { pieces = true; }
                    if (r != Role.None && !found.ContainsKey(r)) { found[r] = c.Column; }
                    if (ProgressHeader.IsMatch(c.Text.Trim())) { progress = true; }
                }
                if (!found.ContainsKey(Role.Quantity)) { pieces = false; }
                bool money = found.ContainsKey(Role.UnitPrice) || found.ContainsKey(Role.Total);
                if (found.Count >= 3 && found.ContainsKey(Role.Description) && money)
                {
                    columns = found;
                    a.TableHeader = body[i].Joined();
                    a.TableHeaderPlace = body[i].Place;
                    if (progress)
                    {
                        a.Blockers.Add("Die Positionstabelle hat eine Spalte „LS. %“ (Leistungsstand). Das ist eine Abschlagsrechnung; bitte mit Hofmann IT klären.");
                    }
                    return i;
                }
            }
            a.Warnings.Add("Keine Positionstabelle erkannt. Erwartet wird eine Kopfzeile mit Bezeichnung und Preis, z. B. „Pos. | Bezeichnung | Menge | Einzelpreis | Gesamt“.");
            return -1;
        }

        /// <summary>
        /// Wert zu einer Beschriftung: im selben Textstück dahinter, sonst im nächsten Textstück der Zeile,
        /// sonst – wenn dort wieder eine Beschriftung steht oder nichts – in der Zeile darunter in derselben
        /// Spalte (Beschriftungen als Kopfzeile, Werte darunter).
        /// </summary>
        private static bool ValueAfter(List<SourceLine> lines, int index, Regex label, out string value, out string labelText, out string place,
            out SourceCell labelCell, out SourceCell valueCell)
        {
            value = "";
            labelText = "";
            place = "";
            labelCell = null;
            valueCell = null;
            SourceLine line = lines[index];
            for (int i = 0; i < line.Cells.Count; i++)
            {
                Match m = label.Match(line.Cells[i].Text);
                if (!m.Success) { continue; }
                string rest = m.Groups[m.Groups.Count - 1].Value.Trim();
                labelText = m.Groups[1].Value.Trim();
                place = line.Cells[i].Place;
                labelCell = line.Cells[i];
                if (rest.Length == 0 && i + 1 < line.Cells.Count && !line.Cells[i + 1].Text.EndsWith(":", StringComparison.Ordinal))
                {
                    rest = line.Cells[i + 1].Text;
                    place = line.Cells[i + 1].Place;
                    valueCell = line.Cells[i + 1];
                }
                else if (rest.Length == 0 && index + 1 < lines.Count && lines[index + 1].Container == line.Container)
                {
                    foreach (SourceCell below in lines[index + 1].Cells)
                    {
                        if (below.Column == line.Cells[i].Column && !below.Text.EndsWith(":", StringComparison.Ordinal))
                        {
                            rest = below.Text;
                            place = below.Place;
                            valueCell = below;
                        }
                    }
                }
                value = rest;
                return true;
            }
            return false;
        }

        private static void Labelled(Analysis a, List<SourceLine> lines, int limit, InvoiceField field, Regex label, Regex valid)
        {
            FieldMatch m = a.Get(field);
            for (int i = 0; i < lines.Count && i < limit; i++)
            {
                string value, labelText, place;
                SourceCell labelCell, valueCell;
                if (!ValueAfter(lines, i, label, out value, out labelText, out place, out labelCell, out valueCell) || !valid.IsMatch(value)) { continue; }
                a.Used.Add(labelCell);
                if (valueCell != null) { a.Used.Add(valueCell); }
                m.Status = MatchStatus.Found;
                m.Value = value;
                m.SourceLabel = labelText;
                m.Place = place;
                return;
            }
        }

        private static void LabelledDate(Analysis a, List<SourceLine> lines, int limit, InvoiceField field, Regex label)
        {
            FieldMatch m = a.Get(field);
            for (int i = 0; i < lines.Count && i < limit; i++)
            {
                string value, labelText, place;
                SourceCell labelCell, valueCell;
                if (!ValueAfter(lines, i, label, out value, out labelText, out place, out labelCell, out valueCell) || value.Length == 0) { continue; }
                DateTime date;
                m.SourceLabel = labelText;
                m.Place = place;
                if (Values.TryDate(value, out date))
                {
                    // nur ein gültiges Datum gilt als übernommen; „02/26“ bleibt als Text an seinem Platz stehen
                    a.Used.Add(labelCell);
                    if (valueCell != null) { a.Used.Add(valueCell); }
                    m.Status = MatchStatus.Found;
                    m.Value = Values.Date(date);
                    return;
                }
                if (m.Status == MatchStatus.Missing)
                {
                    m.Status = MatchStatus.Uncertain;
                    m.Value = value;
                    m.Note = "kein Datum in der Form TT.MM.JJJJ";
                }
            }
        }

        private static void FindAddress(Analysis a, List<SourceLine> body, int limit)
        {
            int best = -1;
            int candidates = 0;
            for (int i = 0; i < limit; i++)
            {
                if (body[i].InTable && body[i].Cells.Count > 2) { continue; }
                SourceCell c = body[i].Cells[0];
                if (!PostCodeLine.IsMatch(c.Text) || NotAnAddress.IsMatch(c.Text)) { continue; }
                // das Anschriftenfeld steht links; ein Briefkopf mit eigener Postleitzahl weiter rechts zählt nicht
                if (best < 0 || c.Column < body[best].Cells[0].Column)
                {
                    best = i;
                    candidates = 1;
                }
                else if (c.Column == body[best].Cells[0].Column)
                {
                    candidates++;
                }
            }
            if (best < 0)
            {
                return;
            }
            int column = body[best].Cells[0].Column;
            List<SourceCell> above = new List<SourceCell>();
            for (int i = best - 1; i >= 0 && above.Count < 4; i--)
            {
                SourceCell c = body[i].Cells[0];
                // Absenderzeile über dem Anschriftenfeld („Firma, Straße, 12345 Ort“) gehört nicht zum Empfänger
                if (c.Column != column || NotAnAddress.IsMatch(c.Text) || c.Text.Length > 60 || Regex.IsMatch(c.Text, @"\b\d{5}\b")) { break; }
                above.Insert(0, c);
            }
            a.Used.Add(body[best].Cells[0]);
            foreach (SourceCell used in above) { a.Used.Add(used); }
            Set(a, InvoiceField.BuyerCity, body[best].Cells[0].Text, body[best].Cells[0].Place, candidates > 1 ? "mehrere Zeilen mit Postleitzahl, bitte prüfen" : "");
            if (above.Count == 0)
            {
                return;
            }
            SourceCell street = above[above.Count - 1];
            above.RemoveAt(above.Count - 1);
            if (above.Count == 0)
            {
                // nur eine Zeile über PLZ/Ort: das ist der Name, die Straße fehlt
                Set(a, InvoiceField.BuyerName, street.Text, street.Place, "");
                return;
            }
            Set(a, InvoiceField.BuyerStreet, street.Text, street.Place, "");
            StringBuilder name = new StringBuilder();
            bool salutation = false;
            foreach (SourceCell c in above)
            {
                if (name.Length > 0) { name.Append(' '); }
                name.Append(c.Text);
                if (Salutation.IsMatch(c.Text)) { salutation = true; }
            }
            Set(a, InvoiceField.BuyerName, name.ToString(), above[0].Place,
                above.Count > 1 ? (salutation ? "Anrede und Name in eine Zeile zusammengezogen" : "mehrere Zeilen über der Straße in eine Zeile zusammengezogen") : "");
            // E-Mail des Kunden: nur im Anschriftenblock, nie aus dem Briefkopf
            for (int i = best + 1; i < limit && i <= best + 3; i++)
            {
                SourceCell c = body[i].Cells[0];
                Match mail = Email.Match(body[i].Joined());
                if (c.Column == column && mail.Success)
                {
                    foreach (SourceCell used in body[i].Cells) { a.Used.Add(used); }
                    Set(a, InvoiceField.BuyerEmail, mail.Value.ToLowerInvariant(), c.Place, "");
                    break;
                }
            }
        }

        private static void Set(Analysis a, InvoiceField field, string value, string place, string note)
        {
            FieldMatch m = a.Get(field);
            m.Value = value;
            m.Place = place;
            m.Note = note;
            m.Status = note.Length > 0 ? MatchStatus.Uncertain : MatchStatus.Found;
        }

        /// <summary>Zeile, die nur aus einem Betrag besteht: Zwischen- oder Nettosumme ohne Beschriftung.</summary>
        private static bool BareAmount(SourceLine line)
        {
            int content = 0;
            bool amount = false;
            foreach (SourceCell c in line.Cells)
            {
                if (Values.StripCurrency(c.Text).Length == 0) { continue; }
                content++;
                decimal v;
                amount = Values.TryAmount(c.Text, out v);
            }
            return content == 1 && amount;
        }

        private static bool TotalsStart(SourceLine line, bool haveItems)
        {
            string t = line.Joined();
            decimal amount;
            if (!Values.LastAmount(t, out amount)) { return false; }
            if (haveItems && BareAmount(line)) { return true; }
            // Beschriftung steht am Zeilenanfang oder in einer eigenen Zelle – nicht mitten in einer Bezeichnung
            foreach (SourceCell c in line.Cells)
            {
                string s = c.Text;
                if (s.Length > 40) { continue; }
                if (VatLine.IsMatch(s) || VatLineReversed.IsMatch(s) || Regex.IsMatch(s, @"^\W*" + GrossLine.ToString(), I)
                    || Regex.IsMatch(s, @"^\W*(summe netto|gesamt netto|nettobetrag|nettosumme|netto|zwischensumme|gesamtsumme|summe)\b", I)
                    || Regex.IsMatch(s, @"^\W*" + PayableLine.ToString(), I))
                {
                    return true;
                }
            }
            return false;
        }

        /// <summary>
        /// Die Textstücke einer Tabellenzeile, den Spalten der Kopfzeile zugeordnet. Eine Spalte reicht von
        /// ihrer Überschrift bis vor die nächste Überschrift: Unter „Menge“ stehen oft zwei Zellen (Zahl und
        /// Einheit), unter „GP“ der Betrag und daneben das Euro-Zeichen.
        /// </summary>
        private sealed class RowCells
        {
            private readonly Dictionary<Role, List<string>> texts = new Dictionary<Role, List<string>>();

            public RowCells(SourceLine line, Dictionary<Role, int> columns)
            {
                foreach (SourceCell c in line.Cells)
                {
                    if (Values.StripCurrency(c.Text).Length == 0) { continue; }   // nur „€“ oder „EUR“
                    Role role = Role.None;
                    int start = int.MinValue;
                    Role first = Role.None;
                    int firstColumn = int.MaxValue;
                    foreach (KeyValuePair<Role, int> k in columns)
                    {
                        if (k.Value <= c.Column && k.Value > start) { start = k.Value; role = k.Key; }
                        if (k.Value < firstColumn) { firstColumn = k.Value; first = k.Key; }
                    }
                    if (role == Role.None) { role = first; }
                    List<string> list;
                    if (!texts.TryGetValue(role, out list))
                    {
                        list = new List<string>();
                        texts[role] = list;
                    }
                    list.Add(c.Text);
                }
            }

            public string Text(Role role)
            {
                List<string> list;
                return texts.TryGetValue(role, out list) ? string.Join(" ", list.ToArray()) : "";
            }

            /// <summary>Erste Zahl der Spalte; <paramref name="rest"/> ist der übrige Text der Spalte (Einheit, „inkl.“).</summary>
            public bool Number(Role role, out decimal value, out string rest)
            {
                value = 0m;
                rest = "";
                List<string> list;
                if (!texts.TryGetValue(role, out list)) { return false; }
                bool found = false;
                List<string> others = new List<string>();
                foreach (string t in list)
                {
                    decimal v;
                    // „2 Stck.“ in einer Zelle: Zahl und kurze Einheit – aber kein Satz, der mit einer Zahl beginnt
                    Match split = Regex.Match(t, @"^(\d+(?:,\d+)?)\s+([^\s\d]\S{0,7})$");
                    if (!found && Values.TryNumber(t, out v))
                    {
                        value = v;
                        found = true;
                    }
                    else if (!found && split.Success && Values.TryNumber(split.Groups[1].Value, out v))
                    {
                        value = v;
                        found = true;
                        others.Add(split.Groups[2].Value.Trim());
                    }
                    else
                    {
                        others.Add(t);
                    }
                }
                rest = string.Join(" ", others.ToArray());
                return found;
            }
        }

        private static string Plain(decimal value)
        {
            return value == decimal.Truncate(value) ? decimal.Truncate(value).ToString(CultureInfo.InvariantCulture) : Values.Amount(value);
        }

        private static int ReadItems(Analysis a, List<SourceLine> body, int header, Dictionary<Role, int> columns, bool pieces)
        {
            InvoiceItem current = null;
            int i = header + 1;
            int preText = 0;
            Use(a, body[header]);
            for (; i < body.Count; i++)
            {
                SourceLine line = body[i];
                if (TotalsStart(line, current != null)) { break; }
                if (PaymentLine.IsMatch(line.Joined()) && current != null) { break; }
                RowCells cells = new RowCells(line, columns);
                string description = cells.Text(Role.Description);
                Match pos = Regex.Match(cells.Text(Role.Pos), @"^(?:pos\.?\s*)?0*(\d{1,4})\.?$", I);
                decimal total, price, quantity;
                string totalRest, priceRest, quantityRest;
                bool hasTotal = cells.Number(Role.Total, out total, out totalRest);
                bool hasPrice = cells.Number(Role.UnitPrice, out price, out priceRest);
                bool hasQuantity = cells.Number(Role.Quantity, out quantity, out quantityRest);
                string unitText = cells.Text(Role.Unit).Length > 0 ? cells.Text(Role.Unit) : (hasQuantity ? quantityRest : "");

                // vorbereitete Leerzeile einer Excel-Vorlage: nur Formeln, die 0,00 ergeben
                if (!pos.Success && description.Length == 0 && !hasQuantity && (!hasTotal || total == 0m) && (!hasPrice || price == 0m)
                    && (hasTotal || hasPrice))
                {
                    Use(a, line);
                    continue;
                }
                if (pos.Success || hasTotal)
                {
                    current = new InvoiceItem();
                    current.Place = line.Place;
                    current.Number = pos.Success ? pos.Groups[1].Value : (a.Items.Count + 1).ToString(CultureInfo.InvariantCulture);
                    if (!pos.Success) { current.Notes.Add("Positionsnummer ergänzt (stand nicht auf der Rechnung)"); }
                    AddDescription(current, description);
                    if (hasQuantity) { current.Quantity = Values.Amount(quantity); }
                    current.Unit = Values.Unit(unitText);
                    // Spalte heißt „Stück“ und es steht keine Einheit da: die Einheit ist Stück
                    if (current.Unit.Length == 0 && pieces && hasQuantity) { current.Unit = "Stk."; }
                    if (hasPrice) { current.UnitPrice = Values.Amount(price); }
                    if (hasTotal) { current.Total = Values.Amount(total); }
                    a.Items.Add(current);
                    Use(a, line);
                    continue;
                }
                if (current == null)
                {
                    preText++;
                    continue;
                }
                Use(a, line);
                if (hasQuantity || (!hasPrice && priceRest.Length > 0))
                {
                    // Unterpunkt ohne eigenen Preis: als Text in die Bezeichnung (im Bestätigungsschritt änderbar)
                    string prefix = !hasPrice && priceRest.Length > 0 ? priceRest.Trim() + " " : "inkl. ";
                    string sub = (prefix + (hasQuantity ? Plain(quantity) : "") + " " + unitText + " " + description).Trim();
                    sub = Regex.Replace(sub, @"\s+", " ");
                    current.Description.Add(sub);
                    current.Notes.Add("Unterpunkt ohne eigenen Preis als Text übernommen: „" + sub + "“");
                    continue;
                }
                AddDescription(current, description.Length > 0 ? description : line.Joined());
            }
            if (preText > 0 && a.Items.Count > 0)
            {
                a.Warnings.Add(preText + " Textzeile(n) zwischen Tabellenkopf und erster Position gehören zu keiner Position; sie werden über die Tabelle gesetzt.");
            }
            if (a.Items.Count == 0)
            {
                a.Warnings.Add("Unter der Kopfzeile „" + a.TableHeader + "“ wurde keine Position mit Betrag gefunden.");
            }
            foreach (InvoiceItem item in a.Items)
            {
                if (!item.Complete)
                {
                    a.Warnings.Add("Position " + item.Number + " (" + item.Place + ") ist unvollständig: "
                        + (item.Description.Count == 0 ? "Bezeichnung " : "") + (item.Quantity.Length == 0 ? "Menge " : "")
                        + (item.UnitPrice.Length == 0 ? "Einzelpreis " : "") + (item.Total.Length == 0 ? "Gesamt " : "") + "fehlt.");
                }
            }
            return i;
        }

        /// <summary>Eine Word-Tabellenzelle kann mehrere Absätze enthalten: jede Zeile wird eine Zeile der Bezeichnung.</summary>
        private static void AddDescription(InvoiceItem item, string text)
        {
            foreach (string part in text.Split('\n'))
            {
                if (part.Trim().Length > 0) { item.Description.Add(part.Trim()); }
            }
        }

        private static void ReadTotals(Analysis a, List<SourceLine> body, int start)
        {
            decimal vatSum = 0m;
            bool anyVat = false;
            string vatPlace = "", vatLabel = "";
            bool deduction = false;
            for (int i = start; i < body.Count; i++)
            {
                string t = body[i].Joined();
                decimal amount;
                if (!Values.LastAmount(t, out amount)) { continue; }
                if (PaymentLine.IsMatch(t) && !PayableLine.IsMatch(t)) { continue; }
                bool totalsLine = DeductionLine.IsMatch(t) || VatLine.IsMatch(t) || VatLineReversed.IsMatch(t) || PayableLine.IsMatch(t)
                    || GrossLine.IsMatch(t) || NetLine.IsMatch(t) || BareAmount(body[i]);
                if (totalsLine) { Use(a, body[i]); }
                if (!deduction && DeductionLine.IsMatch(t))
                {
                    deduction = true;
                    a.Blockers.Add("Im Summenblock steht ein Abzug oder eine Anrechnung (" + body[i].Place + ": „" + Shorten(t)
                        + "“). Rabatt, Skonto und Abschläge im Summenblock deckt die Standardvorlage nicht ab; bitte mit Hofmann IT klären.");
                    continue;
                }
                Match vat = VatLine.Match(t);
                string rate = vat.Success ? vat.Groups[2].Value : "";
                if (!vat.Success)
                {
                    vat = VatLineReversed.Match(t);
                    rate = vat.Success ? vat.Groups[1].Value : "";
                }
                if (vat.Success)
                {
                    rate = (rate.EndsWith(",0", StringComparison.Ordinal) ? rate.Substring(0, rate.Length - 2) : rate) + " %";
                    if (!a.VatRates.Contains(rate)) { a.VatRates.Add(rate); }
                    vatSum += amount;
                    anyVat = true;
                    vatPlace = body[i].Place;
                    vatLabel = Shorten(Regex.Replace(t, @"-?[\d.]+,\d{2}.*$", "").Trim());
                    continue;
                }
                if (PayableLine.IsMatch(t))
                {
                    Total(a, InvoiceField.PayableAmount, amount, body[i], t);
                }
                else if (GrossLine.IsMatch(t))
                {
                    Total(a, InvoiceField.GrossTotal, amount, body[i], t);
                }
                else if (NetLine.IsMatch(t))
                {
                    Total(a, InvoiceField.NetTotal, amount, body[i], t);
                }
                else if (BareAmount(body[i]) && !anyVat && a.Get(InvoiceField.NetTotal).Status == MatchStatus.Missing)
                {
                    FieldMatch net = a.Get(InvoiceField.NetTotal);
                    net.Status = MatchStatus.Uncertain;
                    net.Value = Values.Amount(amount);
                    net.Place = body[i].Place;
                    net.Note = "Betrag ohne Beschriftung über der Umsatzsteuer; als Nettosumme angenommen";
                }
            }
            if (anyVat)
            {
                FieldMatch m = a.Get(InvoiceField.VatAmount);
                m.Status = MatchStatus.Found;
                m.Value = Values.Amount(vatSum);
                m.Place = vatPlace;
                m.SourceLabel = vatLabel;
                if (a.VatRates.Count > 1)
                {
                    m.Note = "Summe aus " + a.VatRates.Count + " Steuersätzen";
                    a.Warnings.Add("Mehrere Steuersätze (" + string.Join(", ", a.VatRates.ToArray())
                        + "): Der Steuersatz je Position kann nicht automatisch zugeordnet werden und wird als Platzhalter markiert.");
                }
            }
            string single = a.VatRates.Count == 1 ? a.VatRates[0] : "";
            foreach (InvoiceItem item in a.Items)
            {
                item.VatRate = single;
            }
            FieldMatch payable = a.Get(InvoiceField.PayableAmount);
            FieldMatch gross = a.Get(InvoiceField.GrossTotal);
            if (payable.Status == MatchStatus.Missing && gross.Status == MatchStatus.Found)
            {
                payable.Status = MatchStatus.Uncertain;
                payable.Value = gross.Value;
                payable.Note = "Zeile fehlt auf der Rechnung; Vorschlag: gleich dem Gesamtbetrag (keine Abzüge erkannt)";
            }
        }

        private static void Total(Analysis a, InvoiceField field, decimal amount, SourceLine line, string text)
        {
            FieldMatch m = a.Get(field);
            if (m.Status != MatchStatus.Missing) { return; }
            m.Status = MatchStatus.Found;
            m.Value = Values.Amount(amount);
            m.Place = line.Place;
            m.SourceLabel = Shorten(Regex.Replace(Values.StripCurrency(Regex.Replace(text, @"-?[\d.]+,\d{2}.*$", "")), @"\s+", " ").Trim().TrimEnd(':'));
        }

        private static void FindPayment(Analysis a, List<SourceLine> lines)
        {
            FieldMatch terms = a.Get(InvoiceField.PaymentTerms);
            FieldMatch due = a.Get(InvoiceField.DueDate);
            foreach (SourceLine line in lines)
            {
                string t = line.Joined();
                if (terms.Status == MatchStatus.Missing && PaymentLine.IsMatch(t) && !PayableLine.IsMatch(t))
                {
                    terms.Status = MatchStatus.Found;
                    terms.Value = Regex.Replace(t, @"^\s*zahlungsbedingung(en)?\s*:?\s*", "", I);
                    terms.Place = line.Place;
                    Use(a, line);
                }
                Match d = DueDateText.Match(t);
                DateTime date;
                if (due.Status == MatchStatus.Missing && d.Success && Values.TryDate(t.Substring(d.Index), out date))
                {
                    due.Status = MatchStatus.Found;
                    due.Value = Values.Date(date);
                    due.Place = line.Place;
                    due.SourceLabel = d.Value;
                }
            }
        }

        private static void CrossCheck(Analysis a)
        {
            decimal sum = 0m;
            bool all = a.Items.Count > 0;
            foreach (InvoiceItem item in a.Items)
            {
                decimal v;
                if (Values.TryNumber(item.Total, out v)) { sum += v; } else { all = false; }
            }
            decimal net, vat, gross;
            bool hasNet = Values.TryNumber(a.Get(InvoiceField.NetTotal).Value, out net);
            bool hasVat = Values.TryNumber(a.Get(InvoiceField.VatAmount).Value, out vat);
            bool hasGross = Values.TryNumber(a.Get(InvoiceField.GrossTotal).Value, out gross);
            if (all && hasNet && Math.Abs(sum - net) > 0.01m)
            {
                a.Warnings.Add("Die Summe der erkannten Positionen (" + Values.Amount(sum) + ") passt nicht zum Nettobetrag (" + Values.Amount(net)
                    + "). Bitte die Positionen prüfen – vermutlich wurde eine Zeile nicht oder falsch erkannt.");
            }
            if (hasNet && hasVat && hasGross && Math.Abs(net + vat - gross) > 0.01m)
            {
                a.Warnings.Add("Netto (" + Values.Amount(net) + ") plus Umsatzsteuer (" + Values.Amount(vat) + ") ergibt nicht den Gesamtbetrag ("
                    + Values.Amount(gross) + "). Bitte die Summenzeilen prüfen.");
            }
        }

        private static string Shorten(string text)
        {
            return text.Length <= 60 ? text : text.Substring(0, 57) + "…";
        }
    }

    /// <summary>Lesbarer Bericht der Analyse (Assistent, Befehlszeile, Tests).</summary>
    public static class AnalysisReport
    {
        public static string StatusText(FieldMatch m)
        {
            switch (m.Status)
            {
                case MatchStatus.Found: return "gefunden";
                case MatchStatus.Uncertain: return "bitte prüfen";
                default: return m.Required ? "FEHLT (Pflicht)" : "fehlt";
            }
        }

        public static string Text(SourceDocument doc, Analysis a)
        {
            StringBuilder b = new StringBuilder();
            b.AppendLine("Vorlagen-Analyse: " + doc.FileName + (doc.Excel ? " (Excel, Blatt „" + doc.Sheet + "“)" : " (Word)"));
            b.AppendLine();
            if (a.Blockers.Count > 0)
            {
                b.AppendLine("NICHT AUTOMATISCH UMBAUBAR:");
                foreach (string s in a.Blockers) { b.AppendLine("  - " + s); }
                b.AppendLine();
            }
            b.AppendLine("Rechnungsangaben");
            foreach (FieldMatch m in a.Fields)
            {
                b.AppendLine("  " + m.TargetLabel.PadRight(34) + StatusText(m).PadRight(17) + m.Value
                    + (m.SourceLabel.Length > 0 ? "   [bisher: " + m.SourceLabel + "]" : "")
                    + (m.Place.Length > 0 ? "   (" + m.Place + ")" : "")
                    + (m.Note.Length > 0 ? "   – " + m.Note : ""));
            }
            b.AppendLine();
            b.AppendLine("Positionstabelle: " + (a.TableHeader.Length > 0 ? "„" + a.TableHeader + "“ (" + a.TableHeaderPlace + ")" : "nicht erkannt"));
            b.AppendLine("Positionen: " + a.Items.Count);
            foreach (InvoiceItem item in a.Items)
            {
                b.AppendLine("  " + item.Number.PadRight(5) + Cut(item.Description.Count > 0 ? item.Description[0] : "(ohne Bezeichnung)", 44).PadRight(46)
                    + item.Quantity.PadLeft(8) + " " + item.Unit.PadRight(9) + item.UnitPrice.PadLeft(11) + "  " + (item.VatRate.Length > 0 ? item.VatRate : "?").PadRight(6)
                    + item.Total.PadLeft(11));
                for (int i = 1; i < item.Description.Count; i++) { b.AppendLine("       " + Cut(item.Description[i], 90)); }
                foreach (string n in item.Notes) { b.AppendLine("       ! " + n); }
            }
            b.AppendLine();
            if (a.Warnings.Count > 0)
            {
                b.AppendLine("Hinweise");
                foreach (string s in a.Warnings) { b.AppendLine("  - " + s); }
                b.AppendLine();
            }
            if (doc.Kept.Count > 0)
            {
                b.AppendLine("Bleibt unverändert");
                foreach (string s in doc.Kept) { b.AppendLine("  - " + s); }
                b.AppendLine();
            }
            b.AppendLine("Ergebnis: " + Summary(a));
            return b.ToString();
        }

        /// <summary>Die gelesenen Zeilen, wie die Erkennung sie sieht (Diagnose, wenn etwas nicht gefunden wird).</summary>
        public static string Lines(SourceDocument doc)
        {
            StringBuilder b = new StringBuilder();
            foreach (SourceLine line in doc.Lines)
            {
                b.Append(line.Container.Length > 0 ? "[" + line.Container + "] " : (line.InTable ? "[Tabelle] " : ""));
                foreach (SourceCell c in line.Cells)
                {
                    b.Append(c.Place).Append('#').Append(c.Column).Append('=').Append(c.Text.Replace("\n", "⏎")).Append(" | ");
                }
                b.AppendLine();
            }
            return b.ToString();
        }

        public static string Summary(Analysis a)
        {
            if (a.Blockers.Count > 0) { return "nicht automatisch umbaubar (" + a.Blockers.Count + " Grund/Gründe)"; }
            if (!a.Convertible) { return "Positionstabelle nicht erkannt – Umbau nicht möglich"; }
            int uncertain = 0;
            foreach (FieldMatch m in a.Fields) { if (m.Status == MatchStatus.Uncertain) { uncertain++; } }
            return a.Items.Count + " Position(en) erkannt, " + a.MissingRequired + " Pflichtangabe(n) fehlen auf der Rechnung, " + uncertain + " Angabe(n) bitte prüfen";
        }

        private static string Cut(string text, int length)
        {
            return text.Length <= length ? text : text.Substring(0, length - 1) + "…";
        }
    }
}
