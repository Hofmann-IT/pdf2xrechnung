; =====================================================================================
; PDF-zu-E-Rechnung – Windows-Installationspaket (Inno Setup 6, ADR 0014)
;
; Erzeugt durch installer\windows\build-installer.ps1. Ziel: Installation ohne technische
; Kenntnisse. Zwei Installationsarten (ADR 0015):
;   Server        – Dienst (Anwendung, eigene Java-Laufzeit, WinSW, Validierungsressourcen),
;                   Verwaltungsanwendung und Handbuch; optional Zugriff aus dem Firmennetz
;   Arbeitsplatz  – nur die Verwaltungsanwendung, die sich mit dem Server verbindet
; Nach der Installation startet die Verwaltungsanwendung (auf dem Server mit dem
; Einrichtungs-Assistenten).
;
; Programmdateien:  C:\Program Files\PDF-zu-ERechnung   (nur lesen)
; Daten:            C:\ProgramData\PDF-zu-ERechnung     (Konfiguration, Inbox, Archiv, Datenbank)
; Der Datenordner wird bei einer Deinstallation NIE gelöscht (Archiv, Ledger).
; =====================================================================================

#ifndef AppVersion
  #define AppVersion "1.1.0"
#endif
#ifndef StageDir
  #define StageDir "..\..\target\installer\stage"
#endif

[Setup]
AppId={{7D1C6B7E-5C1A-4C8F-9E5B-2E1F0A6B3C42}
AppName=PDF-zu-E-Rechnung
AppVersion={#AppVersion}
AppVerName=PDF-zu-E-Rechnung {#AppVersion}
AppPublisher=Hofmann IT
AppPublisherURL=https://www.hofmann-it.de
AppSupportURL=https://www.hofmann-it.de
DefaultDirName={autopf}\PDF-zu-ERechnung
DefaultGroupName=PDF-zu-E-Rechnung
DisableProgramGroupPage=yes
DisableDirPage=no
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\..\target\installer
OutputBaseFilename=PDF-zu-ERechnung-{#AppVersion}-Setup
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
SetupLogging=yes
UninstallDisplayName=PDF-zu-E-Rechnung
CloseApplications=no
MinVersion=10.0

[Languages]
Name: "de"; MessagesFile: "compiler:Languages\German.isl"

[Messages]
de.WelcomeLabel2=Dieses Programm installiert PDF-zu-E-Rechnung {#AppVersion} auf Ihrem Computer.%n%nDie Anwendung wandelt PDF-Rechnungen in E-Rechnungen (XRechnung, ZUGFeRD) um und prüft empfangene E-Rechnungen. Alle Daten bleiben auf dem Computer, auf dem der Dienst läuft.%n%nIm nächsten Schritt wählen Sie: „Server" für den Computer, der die Rechnungen verarbeitet, oder „Arbeitsplatz" für weitere Computer im Firmennetz.

[Types]
; Der erste Eintrag ist die Vorgabe und gilt auch beim Update einer Installation 1.0.0
Name: "server"; Description: "Server: Dienst und Verwaltung (verarbeitet die Rechnungen)"
Name: "client"; Description: "Arbeitsplatz: nur Verwaltung (verbindet sich mit dem Server)"

[Components]
Name: "app"; Description: "Dienst mit Java-Laufzeit und Validierung"; Types: server; Flags: fixed
Name: "verwaltung"; Description: "Verwaltungsanwendung (eigenes Fenster, Symbol im Infobereich)"; Types: server client; Flags: fixed
Name: "docs"; Description: "Handbuch und Lizenzhinweise"; Types: server client; Flags: fixed

[Tasks]
Name: "desktopicon"; Description: "Verknüpfung auf dem Desktop anlegen"
Name: "autostart"; Description: "Symbol im Infobereich bei der Windows-Anmeldung starten"; Flags: unchecked
Name: "netz"; Description: "Zugriff aus dem Firmennetz erlauben (HTTPS, Port 8443): erzeugt ein Zertifikat und eine Firewall-Regel"; Components: app; Flags: unchecked

[Files]
; Anwendung und Laufzeit
Source: "{#StageDir}\app\pdf-zu-erechnung.jar"; DestDir: "{app}"; Flags: ignoreversion; Components: app
Source: "{#StageDir}\app\runtime\*"; DestDir: "{app}\runtime"; Flags: ignoreversion recursesubdirs createallsubdirs; Components: app
Source: "{#StageDir}\app\validator\*"; DestDir: "{app}\validator"; Flags: ignoreversion recursesubdirs createallsubdirs; Components: app
; Windows-Dienst (WinSW, MIT-Lizenz); die XML wird bei der Installation mit den Pfaden erzeugt
Source: "{#StageDir}\app\pdf-zu-erechnung.exe"; DestDir: "{app}"; Flags: ignoreversion; Components: app
Source: "{#StageDir}\app\pdf-zu-erechnung.xml.in"; DestDir: "{app}"; Flags: ignoreversion; Components: app
Source: "{#StageDir}\app\LICENSE-WinSW.txt"; DestDir: "{app}"; Flags: ignoreversion; Components: app
; Netzzugriff: Zertifikat und Firewall-Regel (wird bei gewählter Aufgabe ausgeführt, sonst später von Hand)
Source: "{#StageDir}\app\netzbetrieb-einrichten.ps1"; DestDir: "{app}"; Flags: ignoreversion; Components: app
; Verwaltungsanwendung (WinForms + WebView2-SDK)
Source: "{#StageDir}\verwaltung\*"; DestDir: "{app}\verwaltung"; Flags: ignoreversion; Components: verwaltung
; Dokumentation
Source: "{#StageDir}\docs\*"; DestDir: "{app}\docs"; Flags: ignoreversion recursesubdirs createallsubdirs; Components: docs
; Daten: Konfiguration und Profil nur anlegen, wenn noch nicht vorhanden; nie deinstallieren
Source: "{#StageDir}\data\config\application.yaml"; DestDir: "{commonappdata}\PDF-zu-ERechnung\config"; Flags: onlyifdoesntexist uninsneveruninstall; Components: app
Source: "{#StageDir}\data\profiles\*.yaml"; DestDir: "{commonappdata}\PDF-zu-ERechnung\profiles"; Flags: onlyifdoesntexist uninsneveruninstall; Components: app

[Dirs]
; Arbeitsverzeichnisse: Benutzer dürfen Rechnungen ablegen und Ergebnisse abholen
Name: "{commonappdata}\PDF-zu-ERechnung"; Permissions: users-modify; Flags: uninsneveruninstall; Components: app
Name: "{commonappdata}\PDF-zu-ERechnung\inbox"; Permissions: users-modify; Flags: uninsneveruninstall; Components: app
Name: "{commonappdata}\PDF-zu-ERechnung\processing"; Flags: uninsneveruninstall; Components: app
Name: "{commonappdata}\PDF-zu-ERechnung\output"; Permissions: users-modify; Flags: uninsneveruninstall; Components: app
Name: "{commonappdata}\PDF-zu-ERechnung\failed"; Permissions: users-modify; Flags: uninsneveruninstall; Components: app
Name: "{commonappdata}\PDF-zu-ERechnung\manual-review"; Permissions: users-modify; Flags: uninsneveruninstall; Components: app
Name: "{commonappdata}\PDF-zu-ERechnung\rejected"; Permissions: users-modify; Flags: uninsneveruninstall; Components: app
Name: "{commonappdata}\PDF-zu-ERechnung\archive"; Flags: uninsneveruninstall; Components: app
Name: "{commonappdata}\PDF-zu-ERechnung\data"; Flags: uninsneveruninstall; Components: app
Name: "{commonappdata}\PDF-zu-ERechnung\inbound-validation"; Flags: uninsneveruninstall; Components: app
Name: "{commonappdata}\PDF-zu-ERechnung\logs"; Flags: uninsneveruninstall; Components: app
Name: "{commonappdata}\PDF-zu-ERechnung\logs\service"; Flags: uninsneveruninstall; Components: app

[Icons]
Name: "{group}\PDF-zu-E-Rechnung"; Filename: "{app}\verwaltung\PdfZuERechnung.Verwaltung.exe"; Comment: "PDF-zu-E-Rechnung öffnen"; Components: verwaltung
Name: "{autodesktop}\PDF-zu-E-Rechnung"; Filename: "{app}\verwaltung\PdfZuERechnung.Verwaltung.exe"; Comment: "PDF-zu-E-Rechnung öffnen"; Components: verwaltung; Tasks: desktopicon
Name: "{commonstartup}\PDF-zu-E-Rechnung"; Filename: "{app}\verwaltung\PdfZuERechnung.Verwaltung.exe"; Parameters: "/tray"; Comment: "Symbol im Infobereich"; Components: verwaltung; Tasks: autostart
Name: "{group}\PDF-zu-E-Rechnung im Browser öffnen"; Filename: "http://localhost:8080/"; Comment: "Oberfläche im Browser öffnen"; Components: app
Name: "{group}\Rechnungen ablegen (Inbox)"; Filename: "{commonappdata}\PDF-zu-ERechnung\inbox"; Comment: "PDF-Rechnungen hier ablegen"; Components: app
Name: "{group}\Ergebnisse (Output)"; Filename: "{commonappdata}\PDF-zu-ERechnung\output"; Components: app
Name: "{group}\Handbuch"; Filename: "{app}\docs\Handbuch.html"; Components: docs
Name: "{group}\Datenverzeichnis"; Filename: "{commonappdata}\PDF-zu-ERechnung"; Components: app
Name: "{group}\PDF-zu-E-Rechnung deinstallieren"; Filename: "{uninstallexe}"

[Run]
; Dienst registrieren und starten geschieht im [Code]-Teil (ssPostInstall), nachdem die
; Dienstkonfiguration aus der Vorlage geschrieben wurde; [Run]-Einträge liefen davor.
; Die Verwaltungsanwendung startet mit den Rechten des angemeldeten Benutzers, nicht mit denen des Setups.
Filename: "{app}\verwaltung\PdfZuERechnung.Verwaltung.exe"; Description: "PDF-zu-E-Rechnung jetzt öffnen"; Flags: postinstall nowait skipifsilent runasoriginaluser; Components: verwaltung

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/IM PdfZuERechnung.Verwaltung.exe /F"; Flags: runhidden waituntilterminated; RunOnceId: "appclose"
Filename: "{app}\pdf-zu-erechnung.exe"; Parameters: "stop"; Flags: runhidden waituntilterminated; RunOnceId: "svcstop"; Components: app
Filename: "{app}\pdf-zu-erechnung.exe"; Parameters: "uninstall"; Flags: runhidden waituntilterminated; RunOnceId: "svcuninstall"; Components: app
; Firewall-Regel des Netzzugriffs entfernen (falls vorhanden); das Zertifikat bleibt im Windows-Zertifikatsspeicher
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\netzbetrieb-einrichten.ps1"" -Entfernen"; Flags: runhidden waituntilterminated; RunOnceId: "fwremove"; Components: app

[Code]
var
  ServiceReady: Boolean;

function DataDir(): String;
begin
  Result := ExpandConstant('{commonappdata}\PDF-zu-ERechnung');
end;

{ Arbeitsplatz-Installation über einen vorhandenen Dienst würde ihn in der alten Version zurücklassen. }
function NextButtonClick(CurPageID: Integer): Boolean;
begin
  Result := True;
  if (CurPageID = wpSelectComponents) and (not WizardIsComponentSelected('app'))
     and FileExists(ExpandConstant('{app}\pdf-zu-erechnung.exe')) then
  begin
    MsgBox('Auf diesem Computer ist der Dienst PDF-zu-E-Rechnung installiert.' + #13#10 + #13#10 +
           'Bitte "Server" wählen, damit der Dienst aktualisiert wird. Soll dieser Computer künftig nur Arbeitsplatz sein, ' +
           'deinstallieren Sie zuerst die vorhandene Installation (die Daten bleiben erhalten).', mbInformation, MB_OK);
    Result := False;
  end;
end;

{ Laufende Verwaltungsanwendung schließen; vorhandenen Dienst vor dem Kopieren anhalten und entfernen (Update-Installation). }
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Exe: String;
  ResultCode: Integer;
begin
  Result := '';
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/IM PdfZuERechnung.Verwaltung.exe /F', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exe := ExpandConstant('{app}\pdf-zu-erechnung.exe');
  if WizardIsComponentSelected('app') and FileExists(Exe) then
  begin
    Exec(Exe, 'stop', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    Exec(Exe, 'uninstall', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    Sleep(2000);
  end;
end;

{ Dienstkonfiguration aus der Vorlage mit den tatsächlichen Pfaden erzeugen. }
procedure WriteServiceXml();
var
  Template: AnsiString;
  Xml: String;
  Src, Dst: String;
begin
  Src := ExpandConstant('{app}\pdf-zu-erechnung.xml.in');
  Dst := ExpandConstant('{app}\pdf-zu-erechnung.xml');
  if LoadStringFromFile(Src, Template) then
  begin
    Xml := String(Template);
    StringChangeEx(Xml, '@APPDIR@', ExpandConstant('{app}'), True);
    StringChangeEx(Xml, '@DATADIR@', DataDir(), True);
    SaveStringToFile(Dst, AnsiString(Xml), False);
  end;
end;

{ Wartet bis zu 90 Sekunden, bis die Oberfläche antwortet; danach öffnet Setup den Browser. }
function WaitForService(): Boolean;
var
  WinHttp: Variant;
  I: Integer;
begin
  Result := False;
  for I := 1 to 45 do
  begin
    try
      WinHttp := CreateOleObject('WinHttp.WinHttpRequest.5.1');
      WinHttp.SetTimeouts(1000, 1000, 2000, 2000);
      WinHttp.Open('GET', 'http://localhost:8080/einrichtung', False);
      WinHttp.Send();
      if WinHttp.Status = 200 then
      begin
        Result := True;
        Exit;
      end;
    except
    end;
    Sleep(2000);
  end;
end;

{ WinSW-Befehl ausführen; Ausgabe landet zur Diagnose in logs\service\setup-<Befehl>.log }
function RunWinSW(Command: String): Integer;
var
  Exe, LogFile: String;
  ResultCode: Integer;
begin
  Exe := ExpandConstant('{app}\pdf-zu-erechnung.exe');
  LogFile := DataDir() + '\logs\service\setup-' + Command + '.log';
  if Exec(ExpandConstant('{cmd}'), '/C ""' + Exe + '" ' + Command + ' > "' + LogFile + '" 2>&1"', ExpandConstant('{app}'),
          SW_HIDE, ewWaitUntilTerminated, ResultCode) then
    Result := ResultCode
  else
    Result := -1;
  Log('WinSW ' + Command + ': Exit-Code ' + IntToStr(Result));
end;

{ Die Verwaltungsanwendung braucht die WebView2-Laufzeit von Microsoft (in Windows 11 enthalten). }
function WebView2Installed(): Boolean;
var
  Version: String;
begin
  Result := (RegQueryStringValue(HKLM, 'SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}', 'pv', Version)
             or RegQueryStringValue(HKLM, 'SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}', 'pv', Version)
             or RegQueryStringValue(HKCU, 'Software\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}', 'pv', Version))
            and (Version <> '') and (Version <> '0.0.0.0');
end;

{ Zugriff aus dem Firmennetz (ADR 0015): Zertifikat und Firewall-Regel anlegen, dann den Modus
  in config\network.yaml eintragen. Schlägt das Skript fehl, bleibt der Dienst nur lokal erreichbar. }
procedure SetupNetwork();
var
  Script, LogFile, Yaml: String;
  ResultCode: Integer;
begin
  WizardForm.StatusLabel.Caption := 'Zugriff aus dem Firmennetz wird eingerichtet ...';
  Script := ExpandConstant('{app}\netzbetrieb-einrichten.ps1');
  LogFile := DataDir() + '\logs\service\setup-netzbetrieb.log';
  if (not Exec(ExpandConstant('{cmd}'), '/C ""' + ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe') +
          '" -NoProfile -ExecutionPolicy Bypass -File "' + Script + '" -Port 8443 > "' + LogFile + '" 2>&1"',
          ExpandConstant('{app}'), SW_HIDE, ewWaitUntilTerminated, ResultCode)) or (ResultCode <> 0) then
  begin
    MsgBox('Der Zugriff aus dem Firmennetz konnte nicht eingerichtet werden. Die Anwendung ist auf diesem Computer trotzdem nutzbar.' + #13#10 +
           'Protokoll: ' + LogFile + #13#10 + 'Später nachholen: Handbuch, Abschnitt "Weitere Arbeitsplätze".', mbInformation, MB_OK);
    Exit;
  end;
  Yaml := DataDir() + '\config\network.yaml';
  if FileExists(Yaml) then
    CopyFile(Yaml, Yaml + '.bak-setup', False);
  SaveStringToFile(Yaml,
    '# Netzzugriff (config/network.yaml) - geschrieben vom Setup; aenderbar unter Verwaltung -> Netzzugriff.' + #13#10 +
    '# local = nur dieser Computer, network = zusaetzlich HTTPS fuer das Firmennetz. KEINE SECRETS in dieser Datei.' + #13#10 +
    'app:' + #13#10 + '  network:' + #13#10 + '    mode: network' + #13#10 + '    port: 8443' + #13#10, False);
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  Code: Integer;
begin
  if (CurStep = ssPostInstall) and (not WebView2Installed()) then
    MsgBox('Auf diesem Computer fehlt die Microsoft Edge WebView2-Laufzeit, mit der die Verwaltungsanwendung die Oberfläche anzeigt.' + #13#10 + #13#10 +
           'Lösung: Die "WebView2-Laufzeit (Evergreen)" von Microsoft installieren oder Windows aktualisieren. ' +
           'Bis dahin ist die Oberfläche im Browser erreichbar.', mbInformation, MB_OK);
  if (CurStep = ssPostInstall) and WizardIsComponentSelected('app') then
  begin
    if WizardIsTaskSelected('netz') then
      SetupNetwork();
    WizardForm.StatusLabel.Caption := 'Windows-Dienst wird eingerichtet ...';
    WriteServiceXml();
    Code := RunWinSW('install');
    if Code <> 0 then
    begin
      MsgBox('Der Windows-Dienst konnte nicht eingerichtet werden (Code ' + IntToStr(Code) + ').' + #13#10 +
             'Protokoll: ' + DataDir() + '\logs\service\setup-install.log', mbError, MB_OK);
      Exit;
    end;
    WizardForm.StatusLabel.Caption := 'Dienst wird gestartet, bitte warten ...';
    Code := RunWinSW('start');
    ServiceReady := WaitForService();
    if not ServiceReady then
      MsgBox('Der Dienst wurde eingerichtet, antwortet aber noch nicht unter http://localhost:8080.' + #13#10 +
             'Die Anwendung "PDF-zu-E-Rechnung" zeigt die Oberfläche von selbst an, sobald der Dienst bereit ist (1 bis 2 Minuten).' + #13#10 +
             'Protokolle: ' + DataDir() + '\logs', mbInformation, MB_OK);
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if (CurUninstallStep = usPostUninstall) and DirExists(DataDir()) then
    MsgBox('PDF-zu-E-Rechnung wurde entfernt. Ihre Daten (Konfiguration, Archiv, Datenbank) bleiben unter' + #13#10 +
           DataDir() + #13#10 + 'erhalten und wurden nicht gelöscht.', mbInformation, MB_OK);
end;
