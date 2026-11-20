// Verbindungsdialog: "Dieser Computer" oder Server im Firmennetz, mit Bestätigung des Fingerabdrucks.
using System;
using System.Drawing;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace HofmannIT.ERechnung.Verwaltung
{
    public sealed class ConnectionDialog : Form
    {
        private readonly RadioButton local = new RadioButton();
        private readonly RadioButton server = new RadioButton();
        private readonly TextBox host = new TextBox();
        private readonly NumericUpDown port = new NumericUpDown();
        private readonly NumericUpDown localPort = new NumericUpDown();
        private readonly Label hint = new Label();
        private readonly Button ok = new Button();
        private readonly Button cancel = new Button();
        private readonly AppSettings settings;

        /// <summary>Übernimmt die Auswahl bei OK direkt in die übergebenen Einstellungen.</summary>
        public ConnectionDialog(AppSettings settings, bool localServiceInstalled)
        {
            this.settings = settings;
            Text = "Verbindung – PDF-zu-E-Rechnung";
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            ShowInTaskbar = false;
            StartPosition = FormStartPosition.CenterParent;
            Font = SystemFonts.MessageBoxFont;
            AutoScaleMode = AutoScaleMode.Font;
            ClientSize = new Size(470, 300);

            Label intro = new Label();
            intro.Text = "Mit welchem PDF-zu-E-Rechnung-Dienst soll sich diese Anwendung verbinden?";
            intro.SetBounds(16, 14, 440, 20);

            local.Text = "Dieser Computer" + (localServiceInstalled ? "" : " (hier ist kein Dienst installiert)");
            local.SetBounds(16, 44, 440, 22);
            Label lp = new Label();
            lp.Text = "Port:";
            lp.SetBounds(36, 72, 60, 20);
            localPort.Minimum = 1;
            localPort.Maximum = 65535;
            localPort.Value = settings.LocalPort;
            localPort.SetBounds(100, 69, 80, 24);

            server.Text = "Server im Firmennetz";
            server.SetBounds(16, 106, 440, 22);
            Label hl = new Label();
            hl.Text = "Servername:";
            hl.SetBounds(36, 134, 80, 20);
            host.Text = settings.Host;
            host.MaxLength = 253;
            host.SetBounds(120, 131, 190, 24);
            Label pl = new Label();
            pl.Text = "Port:";
            pl.SetBounds(322, 134, 36, 20);
            port.Minimum = 1;
            port.Maximum = 65535;
            port.Value = settings.Port;
            port.SetBounds(360, 131, 80, 24);

            hint.Text = "Servername und Port stehen auf dem Server unter Verwaltung → Netzzugriff. Die Verbindung ist verschlüsselt (HTTPS). "
                + "Beim ersten Verbinden wird der Fingerabdruck des Serverzertifikats angezeigt; bitte mit der Anzeige auf dem Server vergleichen. "
                + "Passwörter werden von dieser Anwendung nicht gespeichert.";
            hint.SetBounds(16, 170, 440, 74);
            hint.ForeColor = SystemColors.GrayText;

            ok.Text = "Verbinden";
            ok.SetBounds(254, 258, 96, 28);
            ok.Click += OnOk;
            cancel.Text = "Abbrechen";
            cancel.SetBounds(360, 258, 96, 28);
            cancel.DialogResult = DialogResult.Cancel;
            AcceptButton = ok;
            CancelButton = cancel;

            bool preferServer = settings.IsServer || (settings.Mode.Length == 0 && !localServiceInstalled);
            server.Checked = preferServer;
            local.Checked = !preferServer;
            local.CheckedChanged += delegate { Toggle(); };
            Toggle();

            Controls.AddRange(new Control[] { intro, local, lp, localPort, server, hl, host, pl, port, hint, ok, cancel });
        }

        private void Toggle()
        {
            host.Enabled = server.Checked;
            port.Enabled = server.Checked;
            localPort.Enabled = local.Checked;
        }

        private async void OnOk(object sender, EventArgs e)
        {
            if (local.Checked)
            {
                settings.Mode = AppSettings.ModeLocal;
                settings.LocalPort = (int)localPort.Value;
                DialogResult = DialogResult.OK;
                return;
            }
            string name = host.Text.Trim();
            if (!Target.ValidHost(name))
            {
                MessageBox.Show(this, "Bitte den Namen des Servers eingeben, z. B. rechnungsserver (ohne https:// und ohne Port).", Text,
                    MessageBoxButtons.OK, MessageBoxIcon.Information);
                host.Focus();
                return;
            }
            int p = (int)port.Value;
            ok.Enabled = false;
            UseWaitCursor = true;
            ProbeResult probe = null;
            string error = null;
            try
            {
                probe = await Task.Run(delegate { return CertificateProbe.Fetch(name, p, 8000); });
            }
            catch (Exception ex)
            {
                error = ex.Message;
            }
            UseWaitCursor = false;
            ok.Enabled = true;
            if (probe == null)
            {
                MessageBox.Show(this, "Der Server " + name + ":" + p + " ist nicht erreichbar.\r\n\r\n" + error
                    + "\r\n\r\nBitte prüfen: Servername und Port, läuft der Dienst, ist auf dem Server der Zugriff aus dem Firmennetz eingeschaltet?",
                    Text, MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            bool known = Fingerprints.Matches(settings.Fingerprint, probe.Fingerprint)
                && string.Equals(settings.Host, name, StringComparison.OrdinalIgnoreCase) && settings.Port == p;
            if (!known && !ConfirmFingerprint(this, name + ":" + p, probe, false))
            {
                return;
            }
            settings.Mode = AppSettings.ModeServer;
            settings.Host = name;
            settings.Port = p;
            settings.Fingerprint = probe.Fingerprint;
            DialogResult = DialogResult.OK;
        }

        /// <summary>
        /// Fragt, ob dem vorgezeigten Zertifikat vertraut werden soll. <paramref name="changed"/>: es gab
        /// bereits einen bestätigten Fingerabdruck, der nicht mehr passt. Vorgabe ist immer "Nein".
        /// </summary>
        public static bool ConfirmFingerprint(IWin32Window owner, string address, ProbeResult probe, bool changed)
        {
            string text = changed
                ? "ACHTUNG: Der Server " + address + " zeigt ein anderes Zertifikat als beim letzten Mal.\r\n\r\n"
                    + "Das ist nach einer Erneuerung des Zertifikats normal, kann aber auch bedeuten, dass sich jemand zwischen diesen "
                    + "Computer und den Server geschaltet hat. Bitte erst beim Administrator nachfragen.\r\n\r\n"
                : "Erste Verbindung mit " + address + ".\r\n\r\n";
            text += "Fingerabdruck (SHA-256) des Serverzertifikats:\r\n\r\n" + Fingerprints.FormatTwoLines(probe.Fingerprint) + "\r\n\r\n"
                + "Zertifikat: " + probe.Subject + ", gültig bis " + probe.NotAfter.ToString("dd.MM.yyyy") + "\r\n"
                + (probe.TrustedByWindows ? "Windows stuft dieses Zertifikat als vertrauenswürdig ein.\r\n" : "")
                + "\r\nStimmt der Fingerabdruck mit der Anzeige auf dem Server (Verwaltung → Netzzugriff) überein?\r\n"
                + "Nur dann mit \"Ja\" bestätigen.";
            return MessageBox.Show(owner, text, changed ? "Zertifikat des Servers hat sich geändert" : "Fingerabdruck bestätigen",
                MessageBoxButtons.YesNo, changed ? MessageBoxIcon.Warning : MessageBoxIcon.Question,
                MessageBoxDefaultButton.Button2) == DialogResult.Yes;
        }
    }
}
