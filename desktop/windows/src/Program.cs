// Programmstart der Verwaltungsanwendung: nur eine Instanz je Windows-Sitzung; ein zweiter Start
// holt das vorhandene Fenster nach vorn.
using System;
using System.Threading;
using System.Windows.Forms;

namespace HofmannIT.ERechnung.Verwaltung
{
    internal static class Program
    {
        private const string InstanceName = @"Local\HofmannIT.PdfZuERechnung.Verwaltung";

        [STAThread]
        private static int Main(string[] args)
        {
            CommandLine commandLine = CommandLine.Parse(args);
            bool selfTest = commandLine.SelfTestDirectory.Length > 0;
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            if (commandLine.TemplateTestDirectory.Length > 0)
            {
                // Vorlagen-Assistent ohne Fenster (Diagnose, Tests); unabhängig von einer laufenden Instanz
                return TemplateBatch.Run(commandLine.TemplateTestDirectory);
            }

            bool first;
            using (Mutex mutex = new Mutex(true, InstanceName + (selfTest ? ".Selbsttest" : ""), out first))
            using (EventWaitHandle show = new EventWaitHandle(false, EventResetMode.AutoReset, InstanceName + ".Zeigen" + (selfTest ? ".Selbsttest" : "")))
            {
                if (!first)
                {
                    show.Set();
                    return 0;
                }
                string settingsPath = commandLine.SettingsPath.Length > 0 ? commandLine.SettingsPath : AppSettings.DefaultPath();
                MainForm form = new MainForm(AppSettings.Load(settingsPath), settingsPath, commandLine);
                RegisteredWaitHandle registration = ThreadPool.RegisterWaitForSingleObject(show, delegate
                {
                    try
                    {
                        form.BeginInvoke(new Action(form.ShowFromTray));
                    }
                    catch (InvalidOperationException)
                    {
                        // Fenster wird gerade geschlossen
                    }
                }, null, Timeout.Infinite, false);
                try
                {
                    Application.Run(form);
                }
                finally
                {
                    registration.Unregister(null);
                    GC.KeepAlive(mutex);
                }
            }
            return 0;
        }
    }
}
