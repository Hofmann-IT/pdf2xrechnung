<#
.SYNOPSIS
  Prüft den Profiltest des Vorlagen-Assistenten (ADR 0016, Phase 4) im echten Programm gegen eine
  laufende Test-Instanz des Dienstes. Nicht Teil des normalen Builds.

.DESCRIPTION
  Startet die Verwaltungsanwendung im Diagnoselauf (/selbsttest) mit einem Test-PDF (/testpdf). Das
  Fenster meldet sich mit einem Testzugang an, lädt das PDF über die Funktion "Profil testen" hoch und
  schreibt das Ergebnis nach <Ordner>\ergebnis.txt.

  Der Testzugang wird nur über Umgebungsvariablen übergeben; -Zugang nennt eine Datei mit den Zeilen
  Benutzer=... und Passwort=... (z. B. im Ordner target, nie im Repository).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File desktop\windows\tests\profiltest-diagnose.ps1 -Port 8093 `
      -Pdf target\desktop\vorlagen-e2e\ergebnis\fremd-word_E-Rechnung_Test.pdf -Zugang target\preview-c\testzugang.txt
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [int] $Port,
    [Parameter(Mandatory = $true)] [string] $Pdf,
    [string] $Zugang = "",
    [string] $Erwartet = "BESTANDEN",
    [string] $Exe = "",
    [string] $Ordner = ""
)
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
if (-not $Exe) { $Exe = Join-Path $root "target\desktop\PdfZuERechnung.Verwaltung\PdfZuERechnung.Verwaltung.exe" }
if (-not $Ordner) { $Ordner = Join-Path $root "target\desktop\profiltest-diagnose" }
if (-not (Test-Path $Exe)) { throw "Verwaltungsanwendung nicht gebaut: $Exe" }
$Pdf = (Resolve-Path $Pdf).Path
if (Test-Path $Ordner) { Remove-Item $Ordner -Recurse -Force }
New-Item -ItemType Directory -Force $Ordner | Out-Null
[System.IO.File]::WriteAllText((Join-Path $Ordner "verwaltung.ini"), "modus=lokal`r`nlokalerport=$Port`r`n")
$env:ERECHNUNG_SELBSTTEST_BENUTZER = ""
$env:ERECHNUNG_SELBSTTEST_PASSWORT = ""
if ($Zugang) {
    foreach ($line in Get-Content $Zugang -Encoding UTF8) {
        if ($line -match "^Benutzer=(.*)$") { $env:ERECHNUNG_SELBSTTEST_BENUTZER = $Matches[1] }
        if ($line -match "^Passwort=(.*)$") { $env:ERECHNUNG_SELBSTTEST_PASSWORT = $Matches[1] }
    }
}
try {
    $proc = Start-Process $Exe -ArgumentList @("/einstellungen", "`"$(Join-Path $Ordner 'verwaltung.ini')`"", "/selbsttest", "`"$Ordner`"", "/testpdf", "`"$Pdf`"") -PassThru
    if (-not $proc.WaitForExit(180000)) { $proc.Kill(); throw "Zeitüberschreitung im Diagnoselauf" }
} finally {
    $env:ERECHNUNG_SELBSTTEST_BENUTZER = ""
    $env:ERECHNUNG_SELBSTTEST_PASSWORT = ""
}
$result = Get-Content (Join-Path $Ordner "ergebnis.txt") -Raw -Encoding UTF8
$start = $result.IndexOf("Profiltest:")
if ($start -lt 0) { throw "Der Diagnoselauf hat keinen Profiltest ausgeführt:`n$result" }
$end = $result.IndexOf("Neues Fenster angefordert", $start)
Write-Host $result.Substring($start, $(if ($end -gt $start) { $end - $start } else { $result.Length - $start }))
if (-not $result.Substring($start).Contains($Erwartet)) { throw "Erwartet wurde '$Erwartet' (Bericht: $(Join-Path $Ordner 'ergebnis.txt'))" }
Write-Host "Profiltest im Fenster: Ergebnis wie erwartet ($Erwartet)"
