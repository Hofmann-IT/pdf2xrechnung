; =====================================================================================
; PDF-zu-E-Rechnung – Windows-Installationspaket (Inno Setup 6, ADR 0014)
;
; Erzeugt durch installer\windows\build-installer.ps1. Ziel: Installation ohne technische
; Kenntnisse. Enthalten sind Anwendung, eigene Java-Laufzeit (jlink), Windows-Dienst (WinSW),
; Validierungsressourcen und Handbuch. Nach der Installation startet der Dienst und der Browser
; öffnet den Einrichtungs-Assistenten.
;
; Programmdateien:  C:\Program Files\PDF-zu-ERechnung   (nur lesen)
; Daten:            C:\ProgramData\PDF-zu-ERechnung     (Konfiguration, Inbox, Archiv, Datenbank)
; Der Datenordner wird bei einer Deinstallation NIE gelöscht (Archiv, Ledger).
; =====================================================================================

#ifndef AppVersion
  #define AppVersion "1.0.0"
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
de.WelcomeLabel2=Dieses Programm installiert PDF-zu-E-Rechnung {#AppVersion} auf Ihrem Computer.%n%nDie Anwendung wandelt PDF-Rechnungen in E-Rechnungen (XRechnung, ZUGFeRD) um und prüft empfangene E-Rechnungen. Alle Daten bleiben auf diesem Computer.%n%nNach der Installation öffnet sich der Browser mit dem Einrichtungs-Assistenten.

[Types]
Name: "full"; Description: "Vollständige Installation"

[Components]
Name: "app"; Description: "Anwendung, Java-Laufzeit und Windows-Dienst"; Types: full; Flags: fixed
Name: "docs"; Description: "Handbuch und Lizenzhinweise"; Types: full

[Files]
; Anwendung und Laufzeit
Source: "{#StageDir}\app\pdf-zu-erechnung.jar"; DestDir: "{app}"; Flags: ignoreversion; Components: app
Source: "{#StageDir}\app\runtime\*"; DestDir: "{app}\runtime"; Flags: ignoreversion recursesubdirs createallsubdirs; Components: app
Source: "{#StageDir}\app\validator\*"; DestDir: "{app}\validator"; Flags: ignoreversion recursesubdirs createallsubdirs; Components: app
; Windows-Dienst (WinSW, MIT-Lizenz); die XML wird bei der Installation mit den Pfaden erzeugt
Source: "{#StageDir}\app\pdf-zu-erechnung.exe"; DestDir: "{app}"; Flags: ignoreversion; Components: app
Source: "{#StageDir}\app\pdf-zu-erechnung.xml.in"; DestDir: "{app}"; Flags: ignoreversion; Components: app
Source: "{#StageDir}\app\LICENSE-WinSW.txt"; DestDir: "{app}"; Flags: ignoreversion; Components: app
; Dokumentation
Source: "{#StageDir}\docs\*"; DestDir: "{app}\docs"; Flags: ignoreversion recursesubdirs createallsubdirs; Components: docs
; Daten: Konfiguration und Profil nur anlegen, wenn noch nicht vorhanden; nie deinstallieren
Source: "{#StageDir}\data\config\application.yaml"; DestDir: "{commonappdata}\PDF-zu-ERechnung\config"; Flags: onlyifdoesntexist uninsneveruninstall; Components: app
Source: "{#StageDir}\data\profiles\standard.yaml"; DestDir: "{commonappdata}\PDF-zu-ERechnung\profiles"; Flags: onlyifdoesntexist uninsneveruninstall; Components: app

[Dirs]
; Arbeitsverzeichnisse: Benutzer dürfen Rechnungen ablegen und Ergebnisse abholen
Name: "{commonappdata}\PDF-zu-ERechnung"; Permissions: users-modify; Flags: uninsneveruninstall
Name: "{commonappdata}\PDF-zu-ERechnung\inbox"; Permissions: users-modify; Flags: uninsneveruninstall
Name: "{commonappdata}\PDF-zu-ERechnung\processing"; Flags: uninsneveruninstall
Name: "{commonappdata}\PDF-zu-ERechnung\output"; Permissions: users-modify; Flags: uninsneveruninstall
Name: "{commonappdata}\PDF-zu-ERechnung\failed"; Permissions: users-modify; Flags: uninsneveruninstall
Name: "{commonappdata}\PDF-zu-ERechnung\manual-review"; Permissions: users-modify; Flags: uninsneveruninstall
Name: "{commonappdata}\PDF-zu-ERechnung\rejected"; Permissions: users-modify; Flags: uninsneveruninstall
Name: "{commonappdata}\PDF-zu-ERechnung\archive"; Flags: uninsneveruninstall
Name: "{commonappdata}\PDF-zu-ERechnung\data"; Flags: uninsneveruninstall
Name: "{commonappdata}\PDF-zu-ERechnung\inbound-validation"; Flags: uninsneveruninstall
Name: "{commonappdata}\PDF-zu-ERechnung\logs"; Flags: uninsneveruninstall
Name: "{commonappdata}\PDF-zu-ERechnung\logs\service"; Flags: uninsneveruninstall

[Icons]
Name: "{group}\PDF-zu-E-Rechnung öffnen"; Filename: "http://localhost:8080/"; Comment: "Oberfläche im Browser öffnen"
Name: "{group}\Rechnungen ablegen (Inbox)"; Filename: "{commonappdata}\PDF-zu-ERechnung\inbox"; Comment: "PDF-Rechnungen hier ablegen"
Name: "{group}\Ergebnisse (Output)"; Filename: "{commonappdata}\PDF-zu-ERechnung\output"
Name: "{group}\Handbuch"; Filename: "{app}\docs\Handbuch.html"; Components: docs
Name: "{group}\Datenverzeichnis"; Filename: "{commonappdata}\PDF-zu-ERechnung"
Name: "{group}\PDF-zu-E-Rechnung deinstallieren"; Filename: "{uninstallexe}"

[Run]
; Dienst registrieren und starten (WinSW liest die erzeugte XML neben der EXE)
Filename: "{app}\pdf-zu-erechnung.exe"; Parameters: "install"; Flags: runhidden waituntilterminated; StatusMsg: "Windows-Dienst wird eingerichtet ..."
Filename: "{app}\pdf-zu-erechnung.exe"; Parameters: "start"; Flags: runhidden waituntilterminated; StatusMsg: "Dienst wird gestartet ..."
Filename: "http://localhost:8080/"; Description: "Einrichtungs-Assistent im Browser öffnen"; Flags: postinstall shellexec nowait skipifsilent; Check: ServiceAnswered

[UninstallRun]
Filename: "{app}\pdf-zu-erechnung.exe"; Parameters: "stop"; Flags: runhidden waituntilterminated; RunOnceId: "svcstop"
Filename: "{app}\pdf-zu-erechnung.exe"; Parameters: "uninstall"; Flags: runhidden waituntilterminated; RunOnceId: "svcuninstall"

[Code]
var
  ServiceReady: Boolean;

function DataDir(): String;
begin
  Result := ExpandConstant('{commonappdata}\PDF-zu-ERechnung');
end;

{ Vorhandenen Dienst vor dem Kopieren anhalten und entfernen (Update-Installation). }
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Exe: String;
  ResultCode: Integer;
begin
  Result := '';
  Exe := ExpandConstant('{app}\pdf-zu-erechnung.exe');
  if FileExists(Exe) then
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

function ServiceAnswered(): Boolean;
begin
  Result := ServiceReady;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
    WriteServiceXml();
  if CurStep = ssDone then
  begin
    { [Run]-Einträge (install/start) sind hier bereits gelaufen; nun auf die Oberfläche warten }
    ServiceReady := WaitForService();
    if not ServiceReady then
      MsgBox('Der Dienst wurde installiert, antwortet aber noch nicht unter http://localhost:8080.' + #13#10 +
             'Bitte 1 bis 2 Minuten warten und dann die Verknüpfung "PDF-zu-E-Rechnung öffnen" im Startmenü verwenden.' + #13#10 +
             'Protokolle: ' + DataDir() + '\logs', mbInformation, MB_OK);
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usPostUninstall then
    MsgBox('PDF-zu-E-Rechnung wurde entfernt. Ihre Daten (Konfiguration, Archiv, Datenbank) bleiben unter' + #13#10 +
           DataDir() + #13#10 + 'erhalten und wurden nicht gelöscht.', mbInformation, MB_OK);
end;
