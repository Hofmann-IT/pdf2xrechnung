<#
.SYNOPSIS
  Baut das Windows-Installationspaket PDF-zu-ERechnung-<Version>-Setup.exe (ADR 0014).

.DESCRIPTION
  Schritte:
    1. mvn -q verify (Tests, Jar)                     – überspringbar mit -SkipBuild
    2. jlink: schlanke Java-Laufzeit aus dem JDK 21   – Modulliste unten, gegen die Testsuite geprüft
    3. WinSW 2.12.0 herunterladen und SHA-256 prüfen  – einmalig, wird unter target\installer zwischengespeichert
    4. Staging-Ordner zusammenstellen
    5. Inno Setup (ISCC.exe) kompilieren

  Voraussetzungen auf dem Build-Rechner: JDK 21 (JAVA_HOME oder Standardpfad), Maven, Inno Setup 6
  (winget install JRSoftware.InnoSetup). Der Kunde braucht nichts davon.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File installer\windows\build-installer.ps1
#>
[CmdletBinding()]
param(
    [switch] $SkipBuild,
    [string] $JavaHome = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot" }),
    [string] $Iscc = $(if (Test-Path "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe") { "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe" } else { "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" })
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$target = Join-Path $root "target\installer"
$stage = Join-Path $target "stage"

# WinSW (MIT): feste Version, feste Prüfsumme – nichts wird ungeprüft eingebunden
$winswUrl = "https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe"
$winswSha256 = "05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da"
$winswLicenseUrl = "https://raw.githubusercontent.com/winsw/winsw/v2.12.0/LICENSE.txt"

# JDK-Module der Laufzeit (mit der vollständigen Testsuite auf dieser Laufzeit geprüft)
$modules = @(
    "java.base", "java.desktop", "java.datatransfer", "java.sql", "java.transaction.xa", "java.naming", "java.management",
    "java.instrument", "java.xml", "java.xml.crypto", "java.logging", "java.scripting", "java.prefs", "java.security.jgss",
    "java.security.sasl", "java.net.http", "java.compiler", "jdk.unsupported", "jdk.crypto.ec", "jdk.crypto.cryptoki",
    "jdk.localedata", "jdk.charsets", "jdk.zipfs", "jdk.management", "jdk.xml.dom", "jdk.naming.dns", "jdk.jfr", "jdk.attach"
) -join ","

function Step($text) { Write-Host "==> $text" -ForegroundColor Cyan }

if (-not (Test-Path "$JavaHome\bin\jlink.exe")) { throw "JDK 21 mit jlink nicht gefunden unter $JavaHome" }
if (-not (Test-Path $Iscc)) { throw "Inno Setup (ISCC.exe) nicht gefunden: $Iscc  – Installation: winget install JRSoftware.InnoSetup" }

$version = ([xml](Get-Content (Join-Path $root "pom.xml"))).project.version
if (-not $version) { throw "Version nicht aus pom.xml lesbar" }
Step "Version $version"

if (-not $SkipBuild) {
    Step "mvn -q verify"
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome\bin;$env:Path"
    Push-Location $root
    try { & mvn -q verify; if ($LASTEXITCODE -ne 0) { throw "mvn verify fehlgeschlagen" } } finally { Pop-Location }
}
$jar = Join-Path $root "target\pdf-zu-erechnung.jar"
if (-not (Test-Path $jar)) { throw "Jar fehlt: $jar (mvn -q verify ausführen)" }

Step "Staging-Ordner"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$stage\app", "$stage\docs", "$stage\data\config", "$stage\data\profiles" | Out-Null
Copy-Item $jar "$stage\app\pdf-zu-erechnung.jar"
Copy-Item (Join-Path $root "validator") "$stage\app\validator" -Recurse
Copy-Item (Join-Path $PSScriptRoot "pdf-zu-erechnung.xml.in") "$stage\app\pdf-zu-erechnung.xml.in"
Copy-Item (Join-Path $root "config\application.yaml") "$stage\data\config\application.yaml"
Copy-Item (Join-Path $root "profiles\standard.yaml") "$stage\data\profiles\standard.yaml"
Copy-Item (Join-Path $root "docs\Handbuch.html") "$stage\docs\Handbuch.html"
foreach ($f in "README.md", "CHANGELOG.md", "THIRD-PARTY.md") { Copy-Item (Join-Path $root $f) "$stage\docs\$f" }
Copy-Item (Join-Path $root "docs\datev-format-referenz.md") "$stage\docs\datev-format-referenz.md"

Step "jlink-Laufzeit"
$runtime = "$stage\app\runtime"
& "$JavaHome\bin\jlink.exe" --add-modules $modules --strip-debug --no-man-pages --no-header-files --compress zip-6 --output $runtime
if ($LASTEXITCODE -ne 0) { throw "jlink fehlgeschlagen" }
& "$runtime\bin\java.exe" -version

Step "WinSW"
$cache = Join-Path $target "WinSW-x64-2.12.0.exe"
if (-not (Test-Path $cache)) { Invoke-WebRequest -Uri $winswUrl -OutFile $cache }
$actual = (Get-FileHash $cache -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $winswSha256) { Remove-Item $cache; throw "WinSW-Prüfsumme stimmt nicht: $actual" }
Copy-Item $cache "$stage\app\pdf-zu-erechnung.exe"
$lic = Join-Path $target "LICENSE-WinSW.txt"
if (-not (Test-Path $lic)) { Invoke-WebRequest -Uri $winswLicenseUrl -OutFile $lic }
Copy-Item $lic "$stage\app\LICENSE-WinSW.txt"

Step "Inno Setup"
& $Iscc "/DAppVersion=$version" "/DStageDir=$stage" (Join-Path $PSScriptRoot "pdf-zu-erechnung.iss")
if ($LASTEXITCODE -ne 0) { throw "ISCC fehlgeschlagen" }

$setup = Get-ChildItem $target -Filter "PDF-zu-ERechnung-$version-Setup.exe" | Select-Object -First 1
$hash = (Get-FileHash $setup.FullName -Algorithm SHA256).Hash
"$hash *$($setup.Name)" | Set-Content -Encoding ASCII "$($setup.FullName).sha256"
Step "Fertig: $($setup.FullName) ($([math]::Round($setup.Length / 1MB)) MB), SHA-256 $hash"
