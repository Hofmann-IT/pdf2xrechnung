// Hauptfenster der Verwaltungsanwendung (ADR 0015, Phase C): die Oberfläche des Dienstes in einem
// eigenen Fenster ohne Adresszeile, Symbol im Infobereich mit Dienststatus, Dienststeuerung,
// Downloads mit "Speichern unter", Bindung an den bestätigten Fingerabdruck des Servers.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace HofmannIT.ERechnung.Verwaltung
{
    public sealed class MainForm : Form
    {
        private const string AppTitle = "PDF-zu-E-Rechnung";

        private enum MessageKind { None, Unreachable, Other }

        private readonly AppSettings settings;
        private readonly string settingsPath;
        private readonly CommandLine commandLine;
        private readonly bool selfTest;
        private readonly StringBuilder selfTestLog = new StringBuilder();

        private readonly WebView2 web = new WebView2();
        private readonly ToolStrip bar = new ToolStrip();
        private readonly ToolStripButton backButton = new ToolStripButton("◀ Zurück");
        private readonly ToolStripButton homeButton = new ToolStripButton("Start");
        private readonly ToolStripButton reloadButton = new ToolStripButton("Neu laden");
        private readonly ToolStripButton connectButton = new ToolStripButton("Verbindung …");
        private readonly ToolStripLabel statusLabel = new ToolStripLabel("");

        private readonly Panel messagePanel = new Panel();
        private readonly Label messageTitle = new Label();
        private readonly Label messageText = new Label();
        private readonly Button retryButton = new Button();
        private readonly Button startServiceButton = new Button();
        private readonly Button connectionButton = new Button();
        private readonly FlowLayoutPanel messageButtons = new FlowLayoutPanel();
        private MessageKind messageKind = MessageKind.None;

        private readonly NotifyIcon tray = new NotifyIcon();
        private readonly ToolStripMenuItem trayStatus = new ToolStripMenuItem("…");
        private readonly ToolStripMenuItem trayStart = new ToolStripMenuItem("Dienst starten");
        private readonly ToolStripMenuItem trayStop = new ToolStripMenuItem("Dienst stoppen");
        private readonly ToolStripMenuItem trayRestart = new ToolStripMenuItem("Dienst neu starten");
        private readonly ToolStripMenuItem trayLogs = new ToolStripMenuItem("Protokollordner öffnen");
        private readonly ToolStripMenuItem trayManual = new ToolStripMenuItem("Handbuch");
        private readonly Dictionary<ServiceState, Icon> trayIcons = new Dictionary<ServiceState, Icon>();
        private readonly Timer statusTimer = new Timer();

        private Target target;
        private ServiceState state = ServiceState.NotInstalled;
        private bool localServiceInstalled;
        private bool started;
        private bool starting;
        private bool webReady;
        private bool exiting;
        private bool startHidden;
        private bool ticking;
        private bool serviceActionRunning;
        private bool certificateRejected;
        private DateTime lastDownload = DateTime.MinValue;
        private string lastSaveDirectory = "";

        public MainForm(AppSettings settings, string settingsPath, CommandLine commandLine)
        {
            this.settings = settings;
            this.settingsPath = settingsPath;
            this.commandLine = commandLine;
            selfTest = commandLine.SelfTestDirectory.Length > 0;
            startHidden = commandLine.StartInTray && !selfTest;

            Text = AppTitle;
            Icon = AppIcon();
            Font = SystemFonts.MessageBoxFont;
            AutoScaleMode = AutoScaleMode.Dpi;
            MinimumSize = new Size(720, 520);
            ApplySavedBounds();

            BuildToolbar();
            BuildMessagePanel();
            web.Dock = DockStyle.Fill;
            web.Visible = false;
            Controls.Add(web);
            Controls.Add(messagePanel);
            Controls.Add(bar);
            // Andockreihenfolge: die Leiste zuerst (oben), Anzeige und Meldung füllen den Rest
            bar.SendToBack();
            BuildTray();

            statusTimer.Interval = 5000;
            statusTimer.Tick += delegate { RefreshStatus(); };
            statusTimer.Start();
        }

        // ---- Aufbau ---------------------------------------------------------------------------

        private static Icon AppIcon()
        {
            try
            {
                return Icon.ExtractAssociatedIcon(Application.ExecutablePath);
            }
            catch (Exception)
            {
                return SystemIcons.Application;
            }
        }

        private void BuildToolbar()
        {
            bar.GripStyle = ToolStripGripStyle.Hidden;
            bar.RenderMode = ToolStripRenderMode.System;
            bar.Padding = new Padding(6, 2, 6, 2);
            backButton.Enabled = false;
            backButton.Click += delegate { if (webReady && web.CoreWebView2.CanGoBack) { web.CoreWebView2.GoBack(); } };
            homeButton.Click += delegate { Reconnect(); };
            reloadButton.Click += delegate { if (webReady && messageKind == MessageKind.None) { web.CoreWebView2.Reload(); } else { Reconnect(); } };
            connectButton.Alignment = ToolStripItemAlignment.Right;
            connectButton.Click += delegate { ChangeConnection(); };
            statusLabel.Alignment = ToolStripItemAlignment.Right;
            statusLabel.ForeColor = SystemColors.GrayText;
            bar.Items.AddRange(new ToolStripItem[] { backButton, homeButton, reloadButton, connectButton, new ToolStripSeparator { Alignment = ToolStripItemAlignment.Right }, statusLabel });
        }

        private void BuildMessagePanel()
        {
            messagePanel.Dock = DockStyle.Fill;
            messagePanel.BackColor = Color.FromArgb(245, 249, 252);
            messagePanel.Visible = true;
            messageTitle.Font = new Font(Font.FontFamily, 16f, FontStyle.Bold);
            messageTitle.ForeColor = Color.FromArgb(8, 43, 76);
            messageTitle.AutoSize = true;
            messageTitle.Location = new Point(44, 44);
            messageTitle.Text = AppTitle;
            messageText.AutoSize = true;
            messageText.MaximumSize = new Size(760, 0);
            messageText.Location = new Point(48, 104);
            messageText.Text = "Verbindung wird hergestellt …";
            retryButton.Text = "Erneut versuchen";
            retryButton.Click += delegate { Reconnect(); };
            startServiceButton.Text = "Dienst starten";
            startServiceButton.Click += delegate { RunService("start"); };
            connectionButton.Text = "Verbindung …";
            connectionButton.Click += delegate { ChangeConnection(); };
            foreach (Button b in new Button[] { retryButton, startServiceButton, connectionButton })
            {
                b.AutoSize = true;
                b.AutoSizeMode = AutoSizeMode.GrowAndShrink;
                b.Padding = new Padding(12, 4, 12, 4);
                b.Margin = new Padding(0, 0, 10, 0);
                b.Visible = false;
                messageButtons.Controls.Add(b);
            }
            messageButtons.AutoSize = true;
            messageButtons.WrapContents = false;
            messageButtons.Location = new Point(48, 300);
            messagePanel.Controls.AddRange(new Control[] { messageTitle, messageText, messageButtons });
        }

        private void BuildTray()
        {
            ContextMenuStrip menu = new ContextMenuStrip();
            ToolStripMenuItem open = new ToolStripMenuItem("Öffnen");
            open.Font = new Font(open.Font, FontStyle.Bold);
            open.Click += delegate { ShowFromTray(); };
            trayStatus.Enabled = false;
            trayStart.Click += delegate { RunService("start"); };
            trayStop.Click += delegate { RunService("stop"); };
            trayRestart.Click += delegate { RunService("restart"); };
            trayLogs.Click += delegate { OpenPath(LogDirectory()); };
            trayManual.Click += delegate { OpenPath(ManualPath()); };
            ToolStripMenuItem connection = new ToolStripMenuItem("Verbindung …");
            connection.Click += delegate { ShowFromTray(); ChangeConnection(); };
            ToolStripMenuItem exit = new ToolStripMenuItem("Beenden");
            exit.Click += delegate { ExitApplication(); };
            menu.Items.AddRange(new ToolStripItem[] { open, trayStatus, new ToolStripSeparator(), trayStart, trayStop, trayRestart,
                new ToolStripSeparator(), trayLogs, trayManual, connection, new ToolStripSeparator(), exit });
            menu.Opening += delegate
            {
                trayLogs.Visible = Directory.Exists(LogDirectory());
                trayManual.Visible = ManualPath().Length > 0;
            };
            tray.ContextMenuStrip = menu;
            tray.Text = AppTitle;
            tray.Icon = TrayIcon(ServiceState.NotInstalled);
            tray.DoubleClick += delegate { ShowFromTray(); };
            tray.Visible = !selfTest;
            UpdateStatusDisplay();
        }

        // ---- Start und Verbindung -------------------------------------------------------------

        protected override void SetVisibleCore(bool value)
        {
            if (startHidden)
            {
                // Start im Infobereich (Autostart): Fenster und Browser erst beim ersten Öffnen aufbauen
                startHidden = false;
                if (!IsHandleCreated)
                {
                    CreateHandle();
                }
                RefreshStatus();
                value = false;
            }
            base.SetVisibleCore(value);
        }

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);
            EnsureStarted();
        }

        private async void EnsureStarted()
        {
            if (started || starting)
            {
                return;
            }
            starting = true;
            try
            {
                await StartAsync();
            }
            catch (Exception ex)
            {
                ShowMessage(MessageKind.Other, "Die Anwendung konnte nicht gestartet werden", ex.Message, true, false, true);
            }
            finally
            {
                starting = false;
            }
        }

        private async Task StartAsync()
        {
            RefreshStatus();
            localServiceInstalled = ServiceControl.LocalState() != ServiceState.NotInstalled;
            if (settings.Mode.Length == 0)
            {
                if (localServiceInstalled)
                {
                    settings.Mode = AppSettings.ModeLocal;
                    SaveSettings();
                }
                else if (selfTest || !AskConnection())
                {
                    ShowMessage(MessageKind.Other, "Noch keine Verbindung eingerichtet",
                        "Auf diesem Computer ist kein PDF-zu-E-Rechnung-Dienst installiert. Bitte über \"Verbindung …\" den Server im Firmennetz angeben.",
                        false, false, true);
                    FinishSelfTestSoon();
                    return;
                }
            }
            if (!await InitBrowserAsync())
            {
                return;
            }
            started = true;
            await ConnectAsync();
        }

        private async Task<bool> InitBrowserAsync()
        {
            if (webReady)
            {
                return true;
            }
            try
            {
                CoreWebView2Environment.GetAvailableBrowserVersionString(null);
            }
            catch (WebView2RuntimeNotFoundException)
            {
                ShowMessage(MessageKind.Other, "Microsoft Edge WebView2 fehlt",
                    "Diese Anwendung zeigt die Oberfläche mit der Windows-Komponente \"Microsoft Edge WebView2-Laufzeit\" an. Sie ist in Windows 11 "
                    + "enthalten und fehlt auf diesem Computer.\r\n\r\nLösung: Die WebView2-Laufzeit (Evergreen) von Microsoft installieren oder den "
                    + "Administrator darum bitten, danach diese Anwendung neu starten.\r\n\r\nBis dahin ist die Oberfläche auch im Browser erreichbar: "
                    + Target.From(settings).BaseUri, false, false, false);
                FinishSelfTestSoon();
                return false;
            }
            string userData = selfTest
                ? Path.Combine(commandLine.SelfTestDirectory, "wv2")
                : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PDF-zu-ERechnung", "Verwaltung", "WebView2");
            Directory.CreateDirectory(userData);
            CoreWebView2Environment environment = await CoreWebView2Environment.CreateAsync(null, userData, null);
            await web.EnsureCoreWebView2Async(environment);
            CoreWebView2 core = web.CoreWebView2;
            core.Settings.AreDevToolsEnabled = false;
            core.Settings.IsStatusBarEnabled = false;
            core.Settings.IsBuiltInErrorPageEnabled = false;
            // Keine Zugangsdaten auf dem Arbeitsplatz speichern
            core.Settings.IsPasswordAutosaveEnabled = false;
            core.Settings.IsGeneralAutofillEnabled = false;
            core.NavigationStarting += OnNavigationStarting;
            core.NavigationCompleted += OnNavigationCompleted;
            core.NewWindowRequested += OnNewWindowRequested;
            core.DownloadStarting += OnDownloadStarting;
            core.ServerCertificateErrorDetected += OnServerCertificateError;
            core.DocumentTitleChanged += delegate { Text = string.IsNullOrEmpty(core.DocumentTitle) ? AppTitle : core.DocumentTitle; };
            core.HistoryChanged += delegate { backButton.Enabled = core.CanGoBack; };
            core.ProcessFailed += delegate
            {
                ShowMessage(MessageKind.Other, "Die Anzeige wurde unerwartet beendet", "Bitte \"Erneut versuchen\" wählen.", true, false, false);
            };
            webReady = true;
            return true;
        }

        private async Task ConnectAsync()
        {
            target = Target.From(settings);
            certificateRejected = false;
            UpdateStatusDisplay();
            if (target.Secure)
            {
                ShowMessage(MessageKind.Other, AppTitle, "Verbindung mit " + target.Display + " wird geprüft …", false, false, false);
                ProbeResult probe = null;
                string error = null;
                string h = target.Host;
                int p = target.Port;
                try
                {
                    probe = await Task.Run(delegate { return CertificateProbe.Fetch(h, p, 8000); });
                }
                catch (Exception ex)
                {
                    error = ex.Message;
                }
                if (probe == null)
                {
                    Log("Zertifikatsabfrage fehlgeschlagen: " + error);
                    ShowUnreachable(error);
                    return;
                }
                Log("Server zeigt Fingerabdruck " + Fingerprints.Format(probe.Fingerprint) + (probe.TrustedByWindows ? " (von Windows als vertrauenswürdig eingestuft)" : ""));
                if (!Fingerprints.Matches(settings.Fingerprint, probe.Fingerprint))
                {
                    bool changed = settings.Fingerprint.Length > 0;
                    if (selfTest || !ConnectionDialog.ConfirmFingerprint(this, target.Display, probe, changed))
                    {
                        Log("Fingerabdruck nicht bestätigt, keine Verbindung");
                        ShowMessage(MessageKind.Other, "Keine Verbindung: Zertifikat des Servers nicht bestätigt",
                            "Der Server " + target.Display + " zeigt ein Zertifikat, das auf diesem Computer nicht bestätigt ist. Es wurden keine Daten übertragen.\r\n\r\n"
                            + "Fingerabdruck des Servers:\r\n" + Fingerprints.FormatTwoLines(probe.Fingerprint)
                            + "\r\n\r\nBitte mit der Anzeige auf dem Server (Verwaltung → Netzzugriff) vergleichen und bei Abweichung den Administrator informieren.",
                            true, false, true);
                        FinishSelfTestSoon();
                        return;
                    }
                    settings.Fingerprint = probe.Fingerprint;
                    SaveSettings();
                }
                // Früher erteilte Ausnahmen verwerfen: es gilt nur der jetzt bestätigte Fingerabdruck
                await web.CoreWebView2.ClearServerCertificateErrorActionsAsync();
            }
            Log("Navigation zu " + target.BaseUri);
            web.CoreWebView2.Navigate(target.BaseUri.ToString());
        }

        private async void Reconnect()
        {
            if (!started)
            {
                EnsureStarted();
                return;
            }
            try
            {
                await ConnectAsync();
            }
            catch (Exception ex)
            {
                ShowUnreachable(ex.Message);
            }
        }

        private bool AskConnection()
        {
            using (ConnectionDialog dialog = new ConnectionDialog(settings, localServiceInstalled))
            {
                if (dialog.ShowDialog(this) != DialogResult.OK)
                {
                    return false;
                }
            }
            SaveSettings();
            return true;
        }

        private void ChangeConnection()
        {
            if (selfTest)
            {
                return;
            }
            localServiceInstalled = ServiceControl.LocalState() != ServiceState.NotInstalled;
            if (AskConnection())
            {
                RefreshStatus();
                Reconnect();
            }
        }

        // ---- Ereignisse des Browsers ----------------------------------------------------------

        private void OnNavigationStarting(object sender, CoreWebView2NavigationStartingEventArgs e)
        {
            if (target.IsInternal(e.Uri) || e.Uri.StartsWith("about:", StringComparison.OrdinalIgnoreCase))
            {
                return;
            }
            // Das Fenster zeigt ausschließlich den verbundenen Dienst; fremde Adressen öffnet der Standardbrowser
            e.Cancel = true;
            Log("Navigation außerhalb des Dienstes blockiert: " + e.Uri);
            if (e.IsUserInitiated && Target.IsExternalWeb(e.Uri))
            {
                OpenPath(e.Uri);
            }
        }

        private void OnNewWindowRequested(object sender, CoreWebView2NewWindowRequestedEventArgs e)
        {
            e.Handled = true;
            Log("Neues Fenster angefordert: " + e.Uri + (target.IsInternal(e.Uri) ? " (intern, im selben Fenster)" : " (fremd, nicht im Fenster)"));
            if (target.IsInternal(e.Uri))
            {
                web.CoreWebView2.Navigate(e.Uri);
            }
            else if (e.IsUserInitiated && Target.IsExternalWeb(e.Uri))
            {
                OpenPath(e.Uri);
            }
        }

        private void OnNavigationCompleted(object sender, CoreWebView2NavigationCompletedEventArgs e)
        {
            Log("Navigation abgeschlossen: Erfolg=" + e.IsSuccess + " Status=" + e.WebErrorStatus + " HTTP=" + e.HttpStatusCode + " Adresse=" + web.CoreWebView2.Source);
            if (e.IsSuccess)
            {
                HideMessage();
            }
            else if (certificateRejected)
            {
                ShowMessage(MessageKind.Other, "Verbindung abgebrochen: Zertifikat des Servers passt nicht",
                    "Der Server " + target.Display + " hat ein anderes Zertifikat vorgezeigt als das bestätigte. Die Verbindung wurde abgebrochen, bevor "
                    + "Daten übertragen wurden.\r\n\r\n\"Erneut versuchen\" prüft das Zertifikat neu und zeigt den Fingerabdruck zum Vergleich an.",
                    true, false, true);
            }
            else if (e.WebErrorStatus != CoreWebView2WebErrorStatus.OperationCanceled
                && (DateTime.UtcNow - lastDownload).TotalSeconds > 5
                && (int)e.HttpStatusCode == 0)
            {
                ShowUnreachable(e.WebErrorStatus.ToString());
            }
            FinishSelfTestSoon();
        }

        private void OnServerCertificateError(object sender, CoreWebView2ServerCertificateErrorDetectedEventArgs e)
        {
            // Ein selbstsigniertes Zertifikat ist für Windows "fehlerhaft". Zugelassen wird es nur beim
            // verbundenen Server und nur, wenn es genau das bestätigte Zertifikat ist.
            bool allow = false;
            string presented = "";
            try
            {
                presented = Fingerprints.Of(e.ServerCertificate.ToX509Certificate2());
                allow = target != null && target.Secure && target.IsInternal(e.RequestUri) && Fingerprints.Matches(settings.Fingerprint, presented);
            }
            catch (Exception)
            {
                allow = false;
            }
            e.Action = allow ? CoreWebView2ServerCertificateErrorAction.AlwaysAllow : CoreWebView2ServerCertificateErrorAction.Cancel;
            if (!allow)
            {
                certificateRejected = true;
            }
            Log("Zertifikatsprüfung (" + e.ErrorStatus + "): " + (allow ? "bestätigter Fingerabdruck, zugelassen" : "abgelehnt, vorgezeigt " + Fingerprints.Format(presented)));
        }

        private void OnDownloadStarting(object sender, CoreWebView2DownloadStartingEventArgs e)
        {
            lastDownload = DateTime.UtcNow;
            e.Handled = true;
            if (selfTest)
            {
                e.Cancel = true;
                return;
            }
            // Der Dialog darf nicht im Ereignis selbst laufen (verschachtelte Nachrichtenschleife)
            CoreWebView2Deferral deferral = e.GetDeferral();
            BeginInvoke(new Action(delegate
            {
                try
                {
                    using (SaveFileDialog dialog = new SaveFileDialog())
                    {
                        string suggested = e.ResultFilePath ?? "";
                        dialog.Title = "Speichern unter";
                        dialog.FileName = Path.GetFileName(suggested);
                        string dir = lastSaveDirectory.Length > 0 ? lastSaveDirectory : Path.GetDirectoryName(suggested);
                        if (!string.IsNullOrEmpty(dir) && Directory.Exists(dir))
                        {
                            dialog.InitialDirectory = dir;
                        }
                        string ext = Path.GetExtension(suggested);
                        dialog.Filter = (ext.Length > 1 ? ext.Substring(1).ToUpperInvariant() + "-Datei (*" + ext + ")|*" + ext + "|" : "") + "Alle Dateien (*.*)|*.*";
                        dialog.OverwritePrompt = true;
                        if (dialog.ShowDialog(this) != DialogResult.OK)
                        {
                            e.Cancel = true;
                            return;
                        }
                        e.ResultFilePath = dialog.FileName;
                        lastSaveDirectory = Path.GetDirectoryName(dialog.FileName) ?? "";
                        CoreWebView2DownloadOperation operation = e.DownloadOperation;
                        string saved = dialog.FileName;
                        operation.StateChanged += delegate
                        {
                            if (operation.State == CoreWebView2DownloadState.Completed)
                            {
                                tray.ShowBalloonTip(4000, "Gespeichert", saved, ToolTipIcon.Info);
                            }
                            else if (operation.State == CoreWebView2DownloadState.Interrupted)
                            {
                                MessageBox.Show(this, "Die Datei konnte nicht gespeichert werden:\r\n" + saved + "\r\n\r\nGrund: " + operation.InterruptReason,
                                    AppTitle, MessageBoxButtons.OK, MessageBoxIcon.Warning);
                            }
                        };
                    }
                }
                finally
                {
                    deferral.Complete();
                }
            }));
        }

        // ---- Meldungen statt Fehlerseiten -----------------------------------------------------

        private void ShowUnreachable(string detail)
        {
            bool localTarget = target != null && !target.Secure;
            ServiceState local = ServiceControl.LocalState();
            string text;
            if (localTarget && local == ServiceState.Stopped)
            {
                text = "Der Dienst \"PDF-zu-E-Rechnung\" auf diesem Computer ist gestoppt. Mit \"Dienst starten\" wird er gestartet (Windows fragt nach "
                    + "Administratorrechten); die Oberfläche erscheint, sobald er bereit ist.";
            }
            else if (localTarget && local == ServiceState.NotInstalled)
            {
                text = "Auf diesem Computer antwortet kein PDF-zu-E-Rechnung-Dienst unter " + target.BaseUri + ". Wenn der Dienst auf einem Server läuft, "
                    + "bitte über \"Verbindung …\" den Servernamen angeben.";
            }
            else if (localTarget)
            {
                text = "Der Dienst läuft, die Oberfläche antwortet aber noch nicht unter " + target.BaseUri + ". Nach einem Start dauert das bis zu einer "
                    + "Minute; diese Anzeige aktualisiert sich von selbst. Bleibt es dabei, bitte den Port unter \"Verbindung …\" und den Protokollordner prüfen.";
            }
            else
            {
                text = "Der Server " + target.Display + " ist nicht erreichbar. Bitte prüfen: Ist der Server eingeschaltet und im Netz, läuft dort der Dienst, "
                    + "ist der Zugriff aus dem Firmennetz eingeschaltet (Verwaltung → Netzzugriff)? Diese Anzeige aktualisiert sich von selbst.";
            }
            if (!string.IsNullOrEmpty(detail) && detail != CoreWebView2WebErrorStatus.Unknown.ToString())
            {
                text += "\r\n\r\nTechnischer Hinweis: " + detail;
            }
            ShowMessage(MessageKind.Unreachable, localTarget ? "Der Dienst ist nicht erreichbar" : "Der Server ist nicht erreichbar", text, true,
                localTarget && local == ServiceState.Stopped, true);
            FinishSelfTestSoon();
        }

        private void ShowMessage(MessageKind kind, string title, string text, bool retry, bool startService, bool connection)
        {
            messageKind = kind;
            messageTitle.Text = title;
            messageText.Text = text;
            retryButton.Visible = retry;
            startServiceButton.Visible = startService;
            connectionButton.Visible = connection;
            messageText.Top = messageTitle.Bottom + 14;
            messageButtons.Top = messageText.Bottom + 24;
            web.Visible = false;
            messagePanel.Visible = true;
            if (kind != MessageKind.None)
            {
                Text = AppTitle;
            }
        }

        private void HideMessage()
        {
            messageKind = MessageKind.None;
            messagePanel.Visible = false;
            web.Visible = true;
        }

        // ---- Dienststatus und Infobereich -----------------------------------------------------

        private async void RefreshStatus()
        {
            if (ticking || exiting)
            {
                return;
            }
            ticking = true;
            try
            {
                Target t = Target.From(settings);
                bool configured = settings.Mode.Length > 0;
                bool serverMode = settings.IsServer;
                bool open = false;
                ServiceState local = ServiceState.NotInstalled;
                await Task.Run(delegate
                {
                    local = ServiceControl.LocalState();
                    open = configured && ServiceControl.PortOpen(t.Host, t.Port, 2500);
                });
                localServiceInstalled = local != ServiceState.NotInstalled;
                // Auf dem Server zählt der Zustand des Dienstes, auf dem Arbeitsplatz die Erreichbarkeit
                if (!configured || (!serverMode && localServiceInstalled))
                {
                    state = local;
                }
                else
                {
                    state = open ? ServiceState.Reachable : ServiceState.Unreachable;
                }
                UpdateStatusDisplay();
                // Wartet das Fenster auf den Dienst, verbindet es sich von selbst, sobald er antwortet
                if (open && started && messageKind == MessageKind.Unreachable && Visible)
                {
                    Reconnect();
                }
            }
            catch (Exception)
            {
                // Statusanzeige ist nachrangig; nächster Versuch mit dem nächsten Intervall
            }
            finally
            {
                ticking = false;
            }
        }

        private void UpdateStatusDisplay()
        {
            string where = settings.Mode.Length == 0 ? "nicht verbunden" : Target.From(settings).Display;
            string text = ServiceControl.Describe(state);
            statusLabel.Text = where + " · " + text;
            trayStatus.Text = text + (settings.IsServer ? " (" + where + ")" : "");
            string tip = AppTitle + " – " + text;
            tray.Text = tip.Length > 63 ? tip.Substring(0, 63) : tip;
            tray.Icon = TrayIcon(state);
            bool local = localServiceInstalled && !serviceActionRunning;
            ServiceState localState = localServiceInstalled ? ServiceControl.LocalState() : ServiceState.NotInstalled;
            trayStart.Visible = trayStop.Visible = trayRestart.Visible = localServiceInstalled;
            trayStart.Enabled = local && localState == ServiceState.Stopped;
            trayStop.Enabled = local && localState == ServiceState.Running;
            trayRestart.Enabled = local && localState == ServiceState.Running;
        }

        private async void RunService(string action)
        {
            if (serviceActionRunning)
            {
                return;
            }
            serviceActionRunning = true;
            UpdateStatusDisplay();
            startServiceButton.Enabled = false;
            string error = await Task.Run(delegate { return ServiceControl.Run(action); });
            serviceActionRunning = false;
            startServiceButton.Enabled = true;
            if (error.Length > 0)
            {
                MessageBox.Show(Visible ? this : null, error, AppTitle, MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
            RefreshStatus();
        }

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool DestroyIcon(IntPtr handle);

        /// <summary>Programmsymbol mit farbigem Punkt: grün = läuft/erreichbar, rot = gestoppt/nicht erreichbar, gelb = im Übergang.</summary>
        private Icon TrayIcon(ServiceState s)
        {
            Icon cached;
            if (trayIcons.TryGetValue(s, out cached))
            {
                return cached;
            }
            Color dot;
            switch (s)
            {
                case ServiceState.Running:
                case ServiceState.Reachable:
                    dot = Color.FromArgb(19, 165, 107);
                    break;
                case ServiceState.Stopped:
                case ServiceState.Unreachable:
                    dot = Color.FromArgb(200, 60, 60);
                    break;
                case ServiceState.Pending:
                    dot = Color.FromArgb(224, 161, 0);
                    break;
                default:
                    dot = Color.FromArgb(140, 150, 160);
                    break;
            }
            Icon result;
            using (Bitmap bitmap = new Bitmap(32, 32))
            {
                using (Graphics g = Graphics.FromImage(bitmap))
                {
                    g.SmoothingMode = SmoothingMode.AntiAlias;
                    g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                    using (Icon large = new Icon(Icon, 32, 32))
                    {
                        g.DrawIcon(large, new Rectangle(0, 0, 32, 32));
                    }
                    using (SolidBrush white = new SolidBrush(Color.White))
                    using (SolidBrush fill = new SolidBrush(dot))
                    {
                        g.FillEllipse(white, 17, 17, 15, 15);
                        g.FillEllipse(fill, 19, 19, 11, 11);
                    }
                }
                IntPtr handle = bitmap.GetHicon();
                try
                {
                    using (Icon temp = Icon.FromHandle(handle))
                    {
                        result = (Icon)temp.Clone();
                    }
                }
                finally
                {
                    DestroyIcon(handle);
                }
            }
            trayIcons[s] = result;
            return result;
        }

        // ---- Fenster, Infobereich, Beenden ----------------------------------------------------

        /// <summary>Holt das Fenster nach vorn (Doppelklick im Infobereich oder zweiter Programmstart).</summary>
        public void ShowFromTray()
        {
            if (!Visible)
            {
                Show();
            }
            if (WindowState == FormWindowState.Minimized)
            {
                WindowState = settings.Maximized ? FormWindowState.Maximized : FormWindowState.Normal;
            }
            Activate();
            EnsureStarted();
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            if (!exiting && !selfTest && e.CloseReason == CloseReason.UserClosing)
            {
                // Schließen legt das Fenster in den Infobereich; beendet wird über das Menü dort
                e.Cancel = true;
                RememberBounds();
                Hide();
                if (!settings.TrayHintShown)
                {
                    settings.TrayHintShown = true;
                    tray.ShowBalloonTip(6000, AppTitle, "Die Anwendung läuft im Infobereich weiter. Doppelklick auf das Symbol öffnet sie wieder, "
                        + "Rechtsklick → Beenden schließt sie.", ToolTipIcon.Info);
                }
                SaveSettings();
                return;
            }
            RememberBounds();
            SaveSettings();
            statusTimer.Stop();
            tray.Visible = false;
            base.OnFormClosing(e);
        }

        private void ExitApplication()
        {
            exiting = true;
            Close();
        }

        private void ApplySavedBounds()
        {
            Size = new Size(settings.WindowWidth, settings.WindowHeight);
            Rectangle wanted = new Rectangle(settings.WindowX, settings.WindowY, settings.WindowWidth, settings.WindowHeight);
            bool visible = false;
            foreach (Screen screen in Screen.AllScreens)
            {
                Rectangle overlap = Rectangle.Intersect(screen.WorkingArea, wanted);
                if (overlap.Width >= 200 && overlap.Height >= 100)
                {
                    visible = true;
                }
            }
            if (visible && settings.WindowX != -1)
            {
                StartPosition = FormStartPosition.Manual;
                Location = wanted.Location;
            }
            else
            {
                StartPosition = FormStartPosition.CenterScreen;
            }
            if (settings.Maximized)
            {
                WindowState = FormWindowState.Maximized;
            }
        }

        private void RememberBounds()
        {
            if (WindowState == FormWindowState.Minimized)
            {
                return;
            }
            settings.Maximized = WindowState == FormWindowState.Maximized;
            Rectangle b = WindowState == FormWindowState.Normal ? Bounds : RestoreBoundsOrCurrent();
            settings.WindowX = b.X;
            settings.WindowY = b.Y;
            settings.WindowWidth = b.Width;
            settings.WindowHeight = b.Height;
        }

        private Rectangle RestoreBoundsOrCurrent()
        {
            Rectangle r = RestoreBounds;
            return r.Width > 0 && r.Height > 0 ? r : Bounds;
        }

        private void SaveSettings()
        {
            if (selfTest)
            {
                return;
            }
            try
            {
                settings.Save(settingsPath);
            }
            catch (Exception)
            {
                // Einstellungen sind Komfort; die Anwendung bleibt benutzbar
            }
        }

        // ---- Ordner, Handbuch, Diagnose -------------------------------------------------------

        private static string LogDirectory()
        {
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "PDF-zu-ERechnung", "logs");
        }

        private static string ManualPath()
        {
            string exeDir = Path.GetDirectoryName(Application.ExecutablePath) ?? "";
            foreach (string candidate in new string[] { Path.Combine(exeDir, "..", "docs", "Handbuch.html"), Path.Combine(exeDir, "docs", "Handbuch.html"),
                Path.Combine(exeDir, "Handbuch.html") })
            {
                if (File.Exists(candidate))
                {
                    return Path.GetFullPath(candidate);
                }
            }
            return "";
        }

        private void OpenPath(string pathOrUri)
        {
            if (string.IsNullOrEmpty(pathOrUri) || selfTest)
            {
                return;
            }
            try
            {
                ProcessStartInfo info = new ProcessStartInfo(pathOrUri);
                info.UseShellExecute = true;
                Process.Start(info);
            }
            catch (Exception ex)
            {
                MessageBox.Show(this, "Konnte nicht geöffnet werden: " + pathOrUri + "\r\n" + ex.Message, AppTitle, MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }

        private void Log(string line)
        {
            if (selfTest)
            {
                selfTestLog.Append(line).Append("\r\n");
            }
        }

        private bool selfTestFinishing;

        /// <summary>Diagnoselauf (/selbsttest): Ergebnis und Bildschirmfoto ablegen, dann beenden.</summary>
        private async void FinishSelfTestSoon()
        {
            if (!selfTest || selfTestFinishing)
            {
                return;
            }
            selfTestFinishing = true;
            await Task.Delay(2500);
            try
            {
                Directory.CreateDirectory(commandLine.SelfTestDirectory);
                Log("Fenstertitel: " + Text);
                Log("Meldung: " + (messageKind == MessageKind.None ? "(keine)" : messageTitle.Text));
                Log("Status: " + statusLabel.Text);
                if (webReady && messageKind == MessageKind.None)
                {
                    string field = await web.CoreWebView2.ExecuteScriptAsync(
                        "(function(){return [location.href, document.title, !!document.querySelector('input[name=benutzer]'), !!document.querySelector('.brand')].join(' | ');})()");
                    Log("Seite: " + field);
                    using (FileStream png = File.Create(Path.Combine(commandLine.SelfTestDirectory, "seite.png")))
                    {
                        await web.CoreWebView2.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, png);
                    }
                    // Fremde Adressen dürfen das Fenster nicht übernehmen (wird vor jeder Netzanfrage abgebrochen)
                    await web.CoreWebView2.ExecuteScriptAsync("window.open('https://fremd.invalid/fenster')");
                    await web.CoreWebView2.ExecuteScriptAsync("location.href='https://fremd.invalid/seite'");
                    await Task.Delay(1200);
                    Log("Adresse nach Navigationsversuch nach außen: " + web.CoreWebView2.Source);
                }
                using (Bitmap window = new Bitmap(Width, Height))
                {
                    DrawToBitmap(window, new Rectangle(0, 0, Width, Height));
                    window.Save(Path.Combine(commandLine.SelfTestDirectory, "fenster.png"), System.Drawing.Imaging.ImageFormat.Png);
                }
            }
            catch (Exception ex)
            {
                Log("Fehler im Diagnoselauf: " + ex);
            }
            File.WriteAllText(Path.Combine(commandLine.SelfTestDirectory, "ergebnis.txt"), selfTestLog.ToString(), new UTF8Encoding(false));
            exiting = true;
            Close();
        }
    }
}
