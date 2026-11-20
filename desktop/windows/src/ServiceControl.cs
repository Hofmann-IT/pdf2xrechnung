// Dienststatus und Dienststeuerung auf dem Server (ADR 0015, Phase C).
using System;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Net.Sockets;
using System.ServiceProcess;
using Microsoft.Win32;

namespace HofmannIT.ERechnung.Verwaltung
{
    public enum ServiceState
    {
        /// <summary>Auf diesem Computer ist kein Dienst installiert (Arbeitsplatz).</summary>
        NotInstalled,
        Running,
        Stopped,
        /// <summary>Startet oder stoppt gerade.</summary>
        Pending,
        /// <summary>Arbeitsplatz: Server antwortet.</summary>
        Reachable,
        /// <summary>Arbeitsplatz: Server antwortet nicht.</summary>
        Unreachable
    }

    public static class ServiceControl
    {
        public const string ServiceName = "pdf-zu-erechnung";

        /// <summary>Status des lokalen Dienstes; lesen darf jeder Benutzer.</summary>
        public static ServiceState LocalState()
        {
            try
            {
                using (ServiceController sc = new ServiceController(ServiceName))
                {
                    switch (sc.Status)
                    {
                        case ServiceControllerStatus.Running:
                            return ServiceState.Running;
                        case ServiceControllerStatus.Stopped:
                            return ServiceState.Stopped;
                        default:
                            return ServiceState.Pending;
                    }
                }
            }
            catch (InvalidOperationException)
            {
                return ServiceState.NotInstalled;
            }
            catch (Win32Exception)
            {
                return ServiceState.NotInstalled;
            }
        }

        /// <summary>Pfad der Dienst-Steuerdatei (WinSW) aus der Dienstregistrierung; leer, wenn nicht ermittelbar.</summary>
        public static string WrapperPath()
        {
            try
            {
                using (RegistryKey key = Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Services\" + ServiceName))
                {
                    string image = key == null ? null : key.GetValue("ImagePath") as string;
                    return ExecutableFromImagePath(image);
                }
            }
            catch (Exception)
            {
                return "";
            }
        }

        /// <summary>ImagePath kann in Anführungszeichen stehen und Argumente tragen.</summary>
        public static string ExecutableFromImagePath(string image)
        {
            if (string.IsNullOrEmpty(image))
            {
                return "";
            }
            string s = Environment.ExpandEnvironmentVariables(image.Trim());
            if (s.StartsWith("\"", StringComparison.Ordinal))
            {
                int end = s.IndexOf('"', 1);
                return end > 1 ? s.Substring(1, end - 1) : "";
            }
            int exe = s.IndexOf(".exe", StringComparison.OrdinalIgnoreCase);
            return exe > 0 ? s.Substring(0, exe + 4) : s;
        }

        /// <summary>
        /// Startet, stoppt oder startet den Dienst neu über die vorhandene Steuerdatei. Windows fragt
        /// dabei nach Administratorrechten. Rückgabe: leer bei Erfolg, sonst eine Meldung.
        /// </summary>
        public static string Run(string action)
        {
            string wrapper = WrapperPath();
            if (wrapper.Length == 0 || !File.Exists(wrapper))
            {
                return "Die Steuerdatei des Dienstes wurde nicht gefunden. Bitte den Dienst \"PDF-zu-E-Rechnung\" in der Windows-Diensteverwaltung " + Word(action) + ".";
            }
            try
            {
                ProcessStartInfo info = new ProcessStartInfo(wrapper, action);
                info.UseShellExecute = true;
                info.Verb = "runas";
                info.WindowStyle = ProcessWindowStyle.Hidden;
                info.WorkingDirectory = Path.GetDirectoryName(wrapper);
                using (Process p = Process.Start(info))
                {
                    if (p == null)
                    {
                        return "Die Dienststeuerung konnte nicht gestartet werden.";
                    }
                    if (!p.WaitForExit(90000))
                    {
                        return "Der Dienst reagiert nicht (Zeitüberschreitung nach 90 Sekunden).";
                    }
                    return p.ExitCode == 0 ? "" : "Die Dienststeuerung meldet einen Fehler (Code " + p.ExitCode + "). Details im Protokollordner unter \"service\".";
                }
            }
            catch (Win32Exception e)
            {
                // 1223 = Abfrage der Benutzerkontensteuerung abgelehnt
                return e.NativeErrorCode == 1223 ? "Abgebrochen: Für diese Aktion sind Administratorrechte nötig." : "Die Dienststeuerung konnte nicht gestartet werden: " + e.Message;
            }
        }

        private static string Word(string action)
        {
            return action == "start" ? "starten" : (action == "stop" ? "beenden" : "neu starten");
        }

        /// <summary>Arbeitsplatz: Nimmt der Server Verbindungen an? Es werden keine Daten gesendet.</summary>
        public static bool PortOpen(string host, int port, int timeoutMillis)
        {
            try
            {
                using (TcpClient client = new TcpClient())
                {
                    IAsyncResult pending = client.BeginConnect(host, port, null, null);
                    if (!pending.AsyncWaitHandle.WaitOne(timeoutMillis))
                    {
                        return false;
                    }
                    client.EndConnect(pending);
                    return true;
                }
            }
            catch (Exception)
            {
                return false;
            }
        }

        public static string Describe(ServiceState state)
        {
            switch (state)
            {
                case ServiceState.Running:
                    return "Dienst läuft";
                case ServiceState.Stopped:
                    return "Dienst gestoppt";
                case ServiceState.Pending:
                    return "Dienst startet oder stoppt";
                case ServiceState.Reachable:
                    return "Dienst erreichbar";
                case ServiceState.Unreachable:
                    return "Dienst nicht erreichbar";
                default:
                    return "Kein Dienst auf diesem Computer";
            }
        }
    }
}
