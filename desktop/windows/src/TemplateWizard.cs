// Vorlagen-Assistent (ADR 0016), Dialog: Word- oder Excel-Rechnung wählen, Erkennung ansehen und
// korrigieren. Die Quelldatei wird nur gelesen.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace HofmannIT.ERechnung.Verwaltung
{
    public sealed class TemplateWizard : Form
    {
        private const int ReadTimeoutMillis = 120000;
        private const int ConvertTimeoutMillis = 300000;
        private static readonly Color MissingColor = Color.FromArgb(255, 199, 206);
        private static readonly Color UncertainColor = Color.FromArgb(255, 235, 156);

        private readonly TextBox file = new TextBox();
        private readonly Button choose = new Button();
        private readonly Label sheetLabel = new Label();
        private readonly ComboBox sheet = new ComboBox();
        private readonly Label summary = new Label();
        private readonly DataGridView fields = new DataGridView();
        private readonly DataGridView items = new DataGridView();
        private readonly TextBox hints = new TextBox();
        private readonly Button save = new Button();
        private readonly Button convert = new Button();
        private readonly Button close = new Button();

        private readonly Func<IWin32Window, string, Task<ProfileTestOutcome>> tester;
        private SourceDocument document;
        private Analysis analysis;
        private bool loading;
        private bool busy;

        public TemplateWizard() : this(null)
        {
        }

        /// <param name="tester">Prüft ein PDF gegen das Profil des Dienstes (Hauptfenster); null = kein Test möglich.</param>
        public TemplateWizard(Func<IWin32Window, string, Task<ProfileTestOutcome>> tester)
        {
            this.tester = tester;
            Text = "Vorlage importieren – PDF-zu-E-Rechnung";
            StartPosition = FormStartPosition.CenterParent;
            ShowInTaskbar = false;
            MinimizeBox = false;
            Font = SystemFonts.MessageBoxFont;
            AutoScaleMode = AutoScaleMode.Dpi;
            ClientSize = new Size(1000, 840);
            MinimumSize = new Size(760, 560);

            TableLayoutPanel layout = new TableLayoutPanel();
            layout.Dock = DockStyle.Fill;
            layout.Padding = new Padding(12);
            layout.ColumnCount = 1;
            layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100f));
            layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            layout.RowStyles.Add(new RowStyle(SizeType.Percent, 46f));
            layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            layout.RowStyles.Add(new RowStyle(SizeType.Percent, 30f));
            layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            layout.RowStyles.Add(new RowStyle(SizeType.Percent, 24f));
            layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));

            Label intro = new Label();
            intro.AutoSize = true;
            intro.MaximumSize = new Size(900, 0);
            intro.Margin = new Padding(0, 0, 0, 8);
            intro.Text = "Wählen Sie eine bestehende Rechnung oder Rechnungsvorlage aus Word oder Excel. Der Assistent zeigt, welche Angaben er "
                + "erkennt und was für die automatische Verarbeitung fehlt. Die Datei wird dabei nur gelesen, nicht verändert.";

            FlowLayoutPanel top = new FlowLayoutPanel();
            top.AutoSize = true;
            top.WrapContents = false;
            top.Margin = new Padding(0, 0, 0, 8);
            file.ReadOnly = true;
            file.Width = 520;
            file.Margin = new Padding(0, 3, 8, 0);
            choose.Text = "Datei wählen …";
            choose.AutoSize = true;
            choose.Click += delegate { ChooseFile(); };
            sheetLabel.Text = "Blatt:";
            sheetLabel.AutoSize = true;
            sheetLabel.Margin = new Padding(16, 7, 4, 0);
            sheetLabel.Visible = false;
            sheet.DropDownStyle = ComboBoxStyle.DropDownList;
            sheet.Width = 170;
            sheet.Margin = new Padding(0, 3, 0, 0);
            sheet.Visible = false;
            sheet.SelectedIndexChanged += delegate
            {
                if (!loading && document != null && sheet.SelectedItem != null) { Analyze(file.Text, (string)sheet.SelectedItem); }
            };
            top.Controls.AddRange(new Control[] { file, choose, sheetLabel, sheet });

            summary.AutoSize = true;
            summary.MaximumSize = new Size(900, 0);
            summary.Font = new Font(Font, FontStyle.Bold);
            summary.Margin = new Padding(0, 0, 0, 8);
            summary.Text = "Noch keine Datei gewählt.";

            SetupGrid(fields);
            fields.Columns.Add(Column("Angabe (künftige Beschriftung)", 24, true));
            fields.Columns.Add(Column("Wert (änderbar)", 26, false));
            fields.Columns.Add(Column("Bisher", 14, true));
            fields.Columns.Add(Column("Fundstelle", 11, true));
            fields.Columns.Add(Column("Status", 25, true));
            fields.Columns[4].DefaultCellStyle.WrapMode = DataGridViewTriState.True;
            fields.AutoSizeRowsMode = DataGridViewAutoSizeRowsMode.AllCells;
            fields.CellEndEdit += delegate(object sender, DataGridViewCellEventArgs e) { FieldEdited(e.RowIndex); };

            Label itemsLabel = new Label();
            itemsLabel.AutoSize = true;
            itemsLabel.Margin = new Padding(0, 10, 0, 4);
            itemsLabel.Text = "Positionen (änderbar)";
            SetupGrid(items);
            items.Columns.Add(Column("Pos.", 6, false));
            items.Columns.Add(Column("Bezeichnung", 44, false));
            items.Columns.Add(Column("Menge", 9, false));
            items.Columns.Add(Column("Einheit", 9, false));
            items.Columns.Add(Column("Einzelpreis", 11, false));
            items.Columns.Add(Column("USt", 8, false));
            items.Columns.Add(Column("Gesamt", 13, false));
            items.Columns[1].DefaultCellStyle.WrapMode = DataGridViewTriState.True;
            items.AutoSizeRowsMode = DataGridViewAutoSizeRowsMode.AllCells;
            items.CellEndEdit += delegate(object sender, DataGridViewCellEventArgs e) { ItemEdited(e.RowIndex); };

            Label hintsLabel = new Label();
            hintsLabel.AutoSize = true;
            hintsLabel.Margin = new Padding(0, 10, 0, 4);
            hintsLabel.Text = "Hinweise";
            hints.Multiline = true;
            hints.ReadOnly = true;
            hints.ScrollBars = ScrollBars.Vertical;
            hints.Dock = DockStyle.Fill;
            hints.BackColor = SystemColors.Window;

            FlowLayoutPanel buttons = new FlowLayoutPanel();
            buttons.FlowDirection = FlowDirection.RightToLeft;
            buttons.AutoSize = true;
            buttons.Dock = DockStyle.Fill;
            buttons.Margin = new Padding(0, 10, 0, 0);
            close.Text = "Schließen";
            close.AutoSize = true;
            close.DialogResult = DialogResult.Cancel;
            save.Text = "Bericht speichern …";
            save.AutoSize = true;
            save.Enabled = false;
            save.Click += delegate { SaveReport(); };
            convert.Text = "Umbauen …";
            convert.AutoSize = true;
            convert.Enabled = false;
            convert.Click += delegate { Convert(); };
            buttons.Controls.AddRange(new Control[] { close, convert, save });
            CancelButton = close;

            layout.Controls.Add(intro, 0, 0);
            layout.Controls.Add(top, 0, 1);
            layout.Controls.Add(summary, 0, 2);
            layout.Controls.Add(fields, 0, 3);
            layout.Controls.Add(itemsLabel, 0, 4);
            layout.Controls.Add(items, 0, 5);
            layout.Controls.Add(hintsLabel, 0, 6);
            layout.Controls.Add(hints, 0, 7);
            layout.Controls.Add(buttons, 0, 8);
            Controls.Add(layout);
        }

        protected override void OnLoad(EventArgs e)
        {
            base.OnLoad(e);
            // auf kleinen Bildschirmen (Notebook) nicht höher als der Arbeitsbereich
            Rectangle area = Screen.FromControl(this).WorkingArea;
            if (Height > area.Height - 40 && area.Height - 40 >= MinimumSize.Height)
            {
                Height = area.Height - 40;
                if (StartPosition != FormStartPosition.Manual) { Top = area.Top + 20; }
            }
        }

        /// <summary>Ergebnis nach dem Schließen, mit den Korrekturen des Benutzers; null, wenn nichts ausgewertet wurde.</summary>
        public Analysis Result { get { return analysis; } }

        public SourceDocument Source { get { return document; } }

        private static void SetupGrid(DataGridView grid)
        {
            grid.Dock = DockStyle.Fill;
            grid.AllowUserToAddRows = false;
            grid.AllowUserToDeleteRows = false;
            grid.AllowUserToResizeRows = false;
            grid.RowHeadersVisible = false;
            grid.SelectionMode = DataGridViewSelectionMode.CellSelect;
            grid.AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill;
            grid.BackgroundColor = SystemColors.Window;
            grid.BorderStyle = BorderStyle.FixedSingle;
            grid.EditMode = DataGridViewEditMode.EditOnKeystrokeOrF2;
            grid.ColumnHeadersHeightSizeMode = DataGridViewColumnHeadersHeightSizeMode.AutoSize;
            grid.DefaultCellStyle.Alignment = DataGridViewContentAlignment.TopLeft;
        }

        private static DataGridViewTextBoxColumn Column(string title, int weight, bool readOnly)
        {
            DataGridViewTextBoxColumn c = new DataGridViewTextBoxColumn();
            c.HeaderText = title;
            c.FillWeight = weight;
            c.ReadOnly = readOnly;
            c.SortMode = DataGridViewColumnSortMode.NotSortable;
            if (readOnly) { c.DefaultCellStyle.ForeColor = SystemColors.GrayText; }
            return c;
        }

        private void ChooseFile()
        {
            if (busy) { return; }
            using (OpenFileDialog dialog = new OpenFileDialog())
            {
                dialog.Title = "Rechnung oder Rechnungsvorlage wählen";
                dialog.Filter = "Word und Excel (*.docx;*.doc;*.xlsx;*.xls)|*.docx;*.doc;*.xlsx;*.xls|Alle Dateien (*.*)|*.*";
                dialog.CheckFileExists = true;
                if (file.Text.Length > 0)
                {
                    try { dialog.InitialDirectory = Path.GetDirectoryName(file.Text); } catch (ArgumentException) { }
                }
                if (dialog.ShowDialog(this) != DialogResult.OK) { return; }
                Analyze(dialog.FileName, "");
            }
        }

        private async void Analyze(string path, string sheetName)
        {
            if (busy) { return; }
            busy = true;
            choose.Enabled = false;
            sheet.Enabled = false;
            save.Enabled = false;
            convert.Enabled = false;
            UseWaitCursor = true;
            file.Text = path;
            summary.Text = (OfficeAutomation.IsExcelFile(path) ? "Excel" : "Word") + " liest die Datei … (kann beim ersten Mal etwas dauern)";
            SourceDocument read = null;
            string error = null;
            try
            {
                read = await Task.Run(delegate { return OfficeAutomation.Read(path, sheetName, ReadTimeoutMillis); });
            }
            catch (OfficeException e)
            {
                error = e.Message;
            }
            UseWaitCursor = false;
            choose.Enabled = true;
            sheet.Enabled = true;
            busy = false;
            if (IsDisposed) { return; }
            if (read == null)
            {
                document = null;
                analysis = null;
                fields.Rows.Clear();
                items.Rows.Clear();
                hints.Text = "";
                summary.Text = "Die Datei konnte nicht gelesen werden.";
                MessageBox.Show(this, error, Text, MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            document = read;
            analysis = TemplateAnalyzer.Analyze(read);
            ShowResult(read, analysis);
        }

        private void ShowResult(SourceDocument doc, Analysis a)
        {
            loading = true;
            sheet.Items.Clear();
            foreach (string s in doc.Sheets) { sheet.Items.Add(s); }
            sheet.SelectedItem = doc.Sheet;
            sheetLabel.Visible = doc.Excel && doc.Sheets.Count > 1;
            sheet.Visible = sheetLabel.Visible;

            fields.Rows.Clear();
            foreach (FieldMatch m in a.Fields)
            {
                int row = fields.Rows.Add(m.TargetLabel, m.Value, m.SourceLabel, m.Place, StatusWithNote(m));
                PaintField(fields.Rows[row], m);
            }
            items.Rows.Clear();
            foreach (InvoiceItem item in a.Items)
            {
                int row = items.Rows.Add(item.Number, string.Join("\r\n", item.Description.ToArray()), item.Quantity, item.Unit, item.UnitPrice, item.VatRate, item.Total);
                PaintItem(items.Rows[row], item);
            }
            loading = false;
            save.Enabled = true;
            UpdateHints(doc, a);
        }

        private void UpdateHints(SourceDocument doc, Analysis a)
        {
            convert.Enabled = a.Convertible && !busy;
            summary.Text = "Ergebnis: " + AnalysisReport.Summary(a);
            summary.ForeColor = a.Convertible ? SystemColors.ControlText : Color.FromArgb(156, 0, 6);
            StringBuilder b = new StringBuilder();
            foreach (string s in a.Blockers) { b.AppendLine("NICHT UMBAUBAR: " + s); }
            foreach (string s in a.Warnings) { b.AppendLine("• " + s); }
            foreach (InvoiceItem item in a.Items)
            {
                foreach (string n in item.Notes) { b.AppendLine("• Position " + item.Number + ": " + n); }
            }
            if (a.MissingRequired > 0)
            {
                b.AppendLine("• Rot markierte Pflichtangaben stehen auf der bisherigen Rechnung nicht. Sie können den Wert hier eintragen; "
                    + "sonst wird später ein markierter Platzhalter eingefügt.");
            }
            if (doc.Kept.Count > 0)
            {
                b.AppendLine();
                b.AppendLine("Bleibt unverändert (Briefkopf, Bilder, Kopf- und Fußzeile):");
                foreach (string s in doc.Kept) { b.AppendLine("   " + s); }
            }
            hints.Text = b.ToString();
        }

        private static string StatusWithNote(FieldMatch m)
        {
            return AnalysisReport.StatusText(m) + (m.Note.Length > 0 ? " – " + m.Note : "");
        }

        private static void PaintField(DataGridViewRow row, FieldMatch m)
        {
            Color back = m.Status == MatchStatus.Uncertain ? UncertainColor
                : (m.Status == MatchStatus.Missing && m.Required ? MissingColor : SystemColors.Window);
            row.DefaultCellStyle.BackColor = back;
            row.DefaultCellStyle.SelectionBackColor = back == SystemColors.Window ? SystemColors.Highlight : back;
            row.DefaultCellStyle.SelectionForeColor = back == SystemColors.Window ? SystemColors.HighlightText : SystemColors.ControlText;
        }

        private static void PaintItem(DataGridViewRow row, InvoiceItem item)
        {
            Color back = !item.Complete ? MissingColor : (item.Notes.Count > 0 || item.VatRate.Length == 0 ? UncertainColor : SystemColors.Window);
            row.DefaultCellStyle.BackColor = back;
            row.DefaultCellStyle.SelectionBackColor = back == SystemColors.Window ? SystemColors.Highlight : back;
            row.DefaultCellStyle.SelectionForeColor = back == SystemColors.Window ? SystemColors.HighlightText : SystemColors.ControlText;
        }

        private static string CellText(DataGridViewRow row, int column)
        {
            object v = row.Cells[column].Value;
            return v == null ? "" : v.ToString().Trim();
        }

        private void FieldEdited(int rowIndex)
        {
            if (loading || analysis == null || rowIndex < 0 || rowIndex >= analysis.Fields.Count) { return; }
            FieldMatch m = analysis.Fields[rowIndex];
            string value = CellText(fields.Rows[rowIndex], 1);
            if (value == m.Value) { return; }
            m.Enter(value);
            fields.Rows[rowIndex].Cells[4].Value = StatusWithNote(m);
            PaintField(fields.Rows[rowIndex], m);
            UpdateHints(document, analysis);
        }

        private void ItemEdited(int rowIndex)
        {
            if (loading || analysis == null || rowIndex < 0 || rowIndex >= analysis.Items.Count) { return; }
            InvoiceItem item = analysis.Items[rowIndex];
            DataGridViewRow row = items.Rows[rowIndex];
            item.Number = CellText(row, 0);
            item.Description.Clear();
            foreach (string line in CellText(row, 1).Replace("\r", "").Split('\n'))
            {
                if (line.Trim().Length > 0) { item.Description.Add(line.Trim()); }
            }
            item.Quantity = CellText(row, 2);
            item.Unit = CellText(row, 3);
            item.UnitPrice = CellText(row, 4);
            item.VatRate = CellText(row, 5);
            item.Total = CellText(row, 6);
            PaintItem(row, item);
        }

        /// <summary>Baut die Excel-Datei mit den angezeigten (ggf. korrigierten) Werten um; neue Dateien neben dem Original.</summary>
        private async void Convert()
        {
            if (busy || document == null || analysis == null || !analysis.Convertible) { return; }
            fields.EndEdit();
            items.EndEdit();
            string path = file.Text;
            string directory = Path.GetDirectoryName(path);
            bool excel = document.Excel;
            string extension = excel ? ".xlsx" : ".docx";
            string question = "Aus „" + document.FileName + "“ werden im selben Ordner drei neue Dateien erstellt:\r\n\r\n"
                + "  • die umgebaute Rechnung (…_E-Rechnung" + extension + ")\r\n"
                + "  • dieselbe Datei mit farbigen Markierungen der Änderungen (…_E-Rechnung_markiert" + extension + ")\r\n"
                + "  • ein Test-PDF (…_E-Rechnung_Test.pdf)\r\n\r\n"
                + "Ihre bisherige Datei bleibt unverändert, vorhandene Dateien werden nicht überschrieben."
                + (analysis.MissingRequired > 0 ? "\r\n\r\n" + analysis.MissingRequired + " Pflichtangabe(n) fehlen noch. Sie werden als rot markierte leere Felder angelegt." : "")
                + "\r\n\r\nJetzt umbauen?";
            if (MessageBox.Show(this, question, Text, MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) { return; }
            busy = true;
            choose.Enabled = false;
            sheet.Enabled = false;
            save.Enabled = false;
            convert.Enabled = false;
            UseWaitCursor = true;
            string before = summary.Text;
            summary.Text = (excel ? "Excel" : "Word") + " baut die Datei um …";
            ConversionResult result = null;
            string error = null;
            try
            {
                Analysis a = analysis;
                string sheetName = document.Sheet;
                result = await Task.Run(delegate
                {
                    return excel ? TemplateConverter.ConvertExcel(path, sheetName, a, directory, ConvertTimeoutMillis)
                        : WordConverter.Convert(path, a, directory, ConvertTimeoutMillis);
                });
            }
            catch (OfficeException e)
            {
                error = e.Message;
            }
            catch (IOException e)
            {
                error = e.Message;
            }
            UseWaitCursor = false;
            choose.Enabled = true;
            sheet.Enabled = true;
            save.Enabled = true;
            busy = false;
            if (IsDisposed) { return; }
            summary.Text = before;
            convert.Enabled = true;
            if (result == null)
            {
                MessageBox.Show(this, error, Text, MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            StringBuilder b = new StringBuilder();
            b.AppendLine("Erstellt im Ordner " + directory + ":");
            b.AppendLine();
            b.AppendLine("  • " + Path.GetFileName(result.Workbook));
            b.AppendLine("  • " + Path.GetFileName(result.Marked));
            b.AppendLine("  • " + Path.GetFileName(result.Pdf));
            if (result.Notes.Count > 0)
            {
                b.AppendLine();
                b.AppendLine("Bitte beachten:");
                foreach (string note in result.Notes) { b.AppendLine("  • " + note); }
            }
            b.AppendLine();
            b.AppendLine("Nächster Schritt: „Test-PDF jetzt prüfen“ – die Anwendung prüft das Test-PDF gegen das Profil des Dienstes.");
            using (ConversionResultDialog dialog = new ConversionResultDialog(b.ToString(), result, tester))
            {
                dialog.ShowDialog(this);
            }
        }

        private void SaveReport()
        {
            if (document == null || analysis == null) { return; }
            using (SaveFileDialog dialog = new SaveFileDialog())
            {
                dialog.Title = "Bericht speichern";
                dialog.Filter = "Textdatei (*.txt)|*.txt";
                dialog.FileName = Path.GetFileNameWithoutExtension(document.FileName) + "_Analyse.txt";
                try { dialog.InitialDirectory = Path.GetDirectoryName(file.Text); } catch (ArgumentException) { }
                if (dialog.ShowDialog(this) != DialogResult.OK) { return; }
                try
                {
                    File.WriteAllText(dialog.FileName, AnalysisReport.Text(document, analysis), new UTF8Encoding(true));
                }
                catch (Exception e)
                {
                    MessageBox.Show(this, "Der Bericht konnte nicht gespeichert werden:\r\n" + e.Message, Text, MessageBoxButtons.OK, MessageBoxIcon.Warning);
                }
            }
        }

        /// <summary>Diagnose (/vorlagentest): zeigt ein Ergebnis im Dialog und speichert dessen Bild, ohne Office.</summary>
        public static void SavePreview(SourceDocument doc, Analysis a, string pngPath)
        {
            using (TemplateWizard wizard = new TemplateWizard())
            {
                wizard.StartPosition = FormStartPosition.Manual;
                wizard.Location = new Point(-4000, -4000);
                wizard.Show();
                wizard.document = doc;
                wizard.analysis = a;
                wizard.file.Text = doc.FileName;
                wizard.ShowResult(doc, a);
                wizard.fields.ClearSelection();
                wizard.items.ClearSelection();
                Application.DoEvents();
                using (Bitmap image = new Bitmap(wizard.Width, wizard.Height))
                {
                    wizard.DrawToBitmap(image, new Rectangle(0, 0, wizard.Width, wizard.Height));
                    image.Save(pngPath, System.Drawing.Imaging.ImageFormat.Png);
                }
                wizard.Close();
            }
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            // während Office liest, bleibt der Dialog offen (das Lesen endet spätestens mit dem Zeitlimit)
            if (busy) { e.Cancel = true; }
            base.OnFormClosing(e);
        }
    }

    /// <summary>Ergebnis des Umbaus mit der Möglichkeit, das Test-PDF sofort gegen das Profil des Dienstes zu prüfen.</summary>
    public sealed class ConversionResultDialog : Form
    {
        private readonly TextBox text = new TextBox();
        private readonly Button test = new Button();
        private readonly Button folder = new Button();
        private readonly Button close = new Button();
        private readonly ConversionResult result;
        private readonly Func<IWin32Window, string, Task<ProfileTestOutcome>> tester;
        private bool busy;

        public ConversionResultDialog(string message, ConversionResult result, Func<IWin32Window, string, Task<ProfileTestOutcome>> tester)
        {
            this.result = result;
            this.tester = tester;
            Text = "Umbau abgeschlossen – PDF-zu-E-Rechnung";
            StartPosition = FormStartPosition.CenterParent;
            ShowInTaskbar = false;
            MinimizeBox = false;
            Font = SystemFonts.MessageBoxFont;
            AutoScaleMode = AutoScaleMode.Dpi;
            ClientSize = new Size(760, 520);
            MinimumSize = new Size(560, 360);

            text.Multiline = true;
            text.ReadOnly = true;
            text.ScrollBars = ScrollBars.Vertical;
            text.Dock = DockStyle.Fill;
            text.BackColor = SystemColors.Window;
            text.Text = message;
            text.Select(0, 0);

            FlowLayoutPanel buttons = new FlowLayoutPanel();
            buttons.FlowDirection = FlowDirection.RightToLeft;
            buttons.AutoSize = true;
            buttons.Dock = DockStyle.Bottom;
            buttons.Padding = new Padding(0, 8, 0, 0);
            close.Text = "Schließen";
            close.AutoSize = true;
            close.DialogResult = DialogResult.Cancel;
            folder.Text = "Ordner öffnen";
            folder.AutoSize = true;
            folder.Click += delegate
            {
                try { Process.Start("explorer.exe", "/select,\"" + this.result.Workbook + "\""); } catch (Exception) { }
            };
            test.Text = "Test-PDF jetzt prüfen";
            test.AutoSize = true;
            test.Enabled = tester != null;
            test.Click += delegate { RunTest(); };
            buttons.Controls.AddRange(new Control[] { close, folder, test });
            CancelButton = close;
            AcceptButton = test;

            Panel panel = new Panel();
            panel.Dock = DockStyle.Fill;
            panel.Padding = new Padding(12);
            panel.Controls.Add(text);
            panel.Controls.Add(buttons);
            Controls.Add(panel);
        }

        /// <summary>Diagnose (/vorlagentest): Bild des Dialogs speichern.</summary>
        public static void SavePreview(string message, ConversionResult result, string pngPath)
        {
            using (ConversionResultDialog dialog = new ConversionResultDialog(message, result, null))
            {
                dialog.StartPosition = FormStartPosition.Manual;
                dialog.Location = new Point(-4000, -4000);
                dialog.Show();
                dialog.text.AppendText("\r\n\r\n──────── Profiltest: Beispiel ────────\r\n"
                    + ProfileTestOutcome.Failed(ProfileTestOutcome.Offline, "").Describe() + "\r\n");
                Application.DoEvents();
                using (Bitmap image = new Bitmap(dialog.Width, dialog.Height))
                {
                    dialog.DrawToBitmap(image, new Rectangle(0, 0, dialog.Width, dialog.Height));
                    image.Save(pngPath, System.Drawing.Imaging.ImageFormat.Png);
                }
                dialog.Close();
            }
        }

        private async void RunTest()
        {
            if (busy || tester == null) { return; }
            busy = true;
            test.Enabled = false;
            UseWaitCursor = true;
            ProfileTestOutcome outcome;
            try
            {
                outcome = await tester(this, result.Pdf);
            }
            catch (Exception e)
            {
                outcome = ProfileTestOutcome.Failed("error", e.Message);
            }
            UseWaitCursor = false;
            busy = false;
            if (IsDisposed) { return; }
            test.Enabled = true;
            text.AppendText("\r\n\r\n──────── Profiltest: " + Path.GetFileName(result.Pdf) + " ────────\r\n" + outcome.Describe().Replace("\r\n", "\n").Replace("\n", "\r\n") + "\r\n");
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (busy) { e.Cancel = true; }
            base.OnFormClosing(e);
        }
    }

    /// <summary>Auswahl des Mandanten, wenn der Dienst mehrere führt.</summary>
    public static class TenantDialog
    {
        /// <returns>Kennung des gewählten Mandanten; null bei Abbruch.</returns>
        public static string Choose(IWin32Window owner, List<KeyValuePair<string, string>> tenants)
        {
            using (Form form = new Form())
            {
                form.Text = "Mandant wählen";
                form.FormBorderStyle = FormBorderStyle.FixedDialog;
                form.MaximizeBox = false;
                form.MinimizeBox = false;
                form.ShowInTaskbar = false;
                form.StartPosition = FormStartPosition.CenterParent;
                form.Font = SystemFonts.MessageBoxFont;
                form.ClientSize = new Size(420, 260);
                Label label = new Label();
                label.Text = "Für welchen Mandanten soll das Test-PDF geprüft werden?";
                label.SetBounds(12, 12, 396, 20);
                ListBox list = new ListBox();
                list.SetBounds(12, 38, 396, 170);
                foreach (KeyValuePair<string, string> t in tenants) { list.Items.Add(t.Value + "  (" + t.Key + ")"); }
                list.SelectedIndex = 0;
                Button ok = new Button();
                ok.Text = "Prüfen";
                ok.SetBounds(216, 220, 92, 28);
                ok.DialogResult = DialogResult.OK;
                Button cancel = new Button();
                cancel.Text = "Abbrechen";
                cancel.SetBounds(316, 220, 92, 28);
                cancel.DialogResult = DialogResult.Cancel;
                form.AcceptButton = ok;
                form.CancelButton = cancel;
                form.Controls.AddRange(new Control[] { label, list, ok, cancel });
                list.DoubleClick += delegate { form.DialogResult = DialogResult.OK; };
                if (form.ShowDialog(owner) != DialogResult.OK || list.SelectedIndex < 0) { return null; }
                return tenants[list.SelectedIndex].Key;
            }
        }
    }

    /// <summary>Vorlagen-Assistent ohne Fenster (/vorlagentest): wertet alle Word-/Excel-Dateien eines Ordners aus.</summary>
    public static class TemplateBatch
    {
        /// <returns>0, wenn alle Dateien gelesen werden konnten; sonst 1.</returns>
        public static int Run(string directory)
        {
            string output = Path.Combine(directory, "ergebnis");
            Directory.CreateDirectory(output);
            StringBuilder overview = new StringBuilder();
            int failed = 0;
            List<string> files = new List<string>(Directory.GetFiles(directory));
            files.Sort(StringComparer.OrdinalIgnoreCase);
            foreach (string path in files)
            {
                string name = Path.GetFileName(path);
                if (name.StartsWith("~$", StringComparison.Ordinal) || !(OfficeAutomation.IsExcelFile(path) || OfficeAutomation.IsWordFile(path))) { continue; }
                try
                {
                    SourceDocument doc = OfficeAutomation.Read(path, "", 180000);
                    Analysis a = TemplateAnalyzer.Analyze(doc);
                    File.WriteAllText(Path.Combine(output, name + ".analyse.txt"), AnalysisReport.Text(doc, a), new UTF8Encoding(true));
                    File.WriteAllText(Path.Combine(output, name + ".zeilen.txt"), AnalysisReport.Lines(doc), new UTF8Encoding(true));
                    overview.AppendLine(name + ": " + AnalysisReport.Summary(a));
                    TemplateWizard.SavePreview(doc, a, Path.Combine(output, name + ".dialog.png"));
                    if (a.Convertible)
                    {
                        // Angaben, die im Assistenten der Benutzer einträgt: Datei <Name>.werte.txt mit Zeilen Feld=Wert
                        string valuesFile = path + ".werte.txt";
                        if (File.Exists(valuesFile))
                        {
                            foreach (string line in File.ReadAllLines(valuesFile, Encoding.UTF8))
                            {
                                int eq = line.IndexOf('=');
                                InvoiceField field;
                                if (eq > 0 && Enum.TryParse(line.Substring(0, eq).Trim(), out field)) { a.Get(field).Enter(line.Substring(eq + 1)); }
                            }
                        }
                        ConversionResult result = doc.Excel ? TemplateConverter.ConvertExcel(path, doc.Sheet, a, output, 300000)
                            : WordConverter.Convert(path, a, output, 300000);
                        StringBuilder notes = new StringBuilder();
                        notes.AppendLine(Path.GetFileName(result.Workbook));
                        notes.AppendLine(Path.GetFileName(result.Marked));
                        notes.AppendLine(Path.GetFileName(result.Pdf));
                        foreach (string note in result.Notes) { notes.AppendLine("- " + note); }
                        File.WriteAllText(Path.Combine(output, name + ".umbau.txt"), notes.ToString(), new UTF8Encoding(true));
                        ConversionResultDialog.SavePreview(notes.ToString(), result, Path.Combine(output, name + ".ergebnis.png"));
                        overview.AppendLine("   umgebaut: " + Path.GetFileName(result.Workbook) + " (" + result.Notes.Count + " Hinweis(e))");
                    }
                }
                catch (Exception e)
                {
                    failed++;
                    overview.AppendLine(name + ": FEHLER – " + e.Message);
                    File.WriteAllText(Path.Combine(output, name + ".fehler.txt"), e.ToString(), new UTF8Encoding(true));
                }
            }
            File.WriteAllText(Path.Combine(output, "uebersicht.txt"), overview.ToString(), new UTF8Encoding(true));
            return failed == 0 ? 0 : 1;
        }
    }
}
