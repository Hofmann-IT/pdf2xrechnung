<#
.SYNOPSIS
  Baut die Windows-Verwaltungsanwendung PdfZuERechnung.Verwaltung.exe (ADR 0015, Phase C).

.DESCRIPTION
  Schritte:
    1. WebView2-SDK (Microsoft, BSD-3-Clause) als NuGet-Paket herunterladen, SHA-256 und die
       Microsoft-Signatur der DLLs prüfen – einmalig, wird unter target\desktop\cache zwischengespeichert
    2. Programmsymbol aus den Farben der Oberfläche erzeugen
    3. Tests der Kernlogik übersetzen und ausführen (Fehler bricht den Build ab)
    4. Anwendung übersetzen

  Voraussetzung auf dem Build-Rechner: nur Windows 10/11. Übersetzt wird mit dem C#-Compiler des
  .NET Framework 4.8, der zu Windows gehört (Sprachstand C# 5); ein .NET SDK ist nicht nötig.
  Auf dem Zielrechner: .NET Framework 4.8 und die WebView2-Laufzeit (beides Bestandteil von Windows 11).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File desktop\windows\build-desktop.ps1
#>
[CmdletBinding()]
param(
    [switch] $SkipTests
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$target = Join-Path $root "target\desktop"
$cache = Join-Path $target "cache"
$obj = Join-Path $target "obj"
$out = Join-Path $target "PdfZuERechnung.Verwaltung"

# WebView2-SDK: feste Version, feste Prüfsumme – nichts wird ungeprüft eingebunden
$wv2Version = "1.0.3856.49"
$wv2Url = "https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/$wv2Version/microsoft.web.webview2.$wv2Version.nupkg"
$wv2Sha256 = "bc0f76eb911b569838dc4aa8f8d325269b966bedb592863d26211aef3a099f1a"

$csc = Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path $csc)) { throw "C#-Compiler des .NET Framework nicht gefunden: $csc" }

function Step($text) { Write-Host "==> $text" -ForegroundColor Cyan }

$version = ([xml](Get-Content (Join-Path $root "pom.xml"))).project.version
if (-not $version) { throw "Version nicht aus pom.xml lesbar" }
$numeric = ($version -replace "[^0-9.].*$", "")
Step "Version $version"

New-Item -ItemType Directory -Force -Path $cache, $obj | Out-Null
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null

Step "WebView2-SDK $wv2Version"
$nupkg = Join-Path $cache "microsoft.web.webview2.$wv2Version.nupkg"
if (-not (Test-Path $nupkg)) { Invoke-WebRequest -Uri $wv2Url -OutFile $nupkg }
$actual = (Get-FileHash $nupkg -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $wv2Sha256) { Remove-Item $nupkg; throw "Prüfsumme des WebView2-SDK stimmt nicht: $actual" }
$sdk = Join-Path $cache "webview2-$wv2Version"
if (-not (Test-Path (Join-Path $sdk "LICENSE.txt"))) {
    if (Test-Path $sdk) { Remove-Item $sdk -Recurse -Force }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($nupkg, $sdk)
}
$core = Join-Path $sdk "lib\net462\Microsoft.Web.WebView2.Core.dll"
$winforms = Join-Path $sdk "lib\net462\Microsoft.Web.WebView2.WinForms.dll"
$loader = Join-Path $sdk "runtimes\win-x64\native\WebView2Loader.dll"
foreach ($dll in $core, $winforms, $loader) {
    $signature = Get-AuthenticodeSignature $dll
    if ($signature.Status -ne "Valid" -or $signature.SignerCertificate.Subject -notmatch "O=Microsoft Corporation") {
        throw "Signatur von $dll ist nicht gültig oder nicht von Microsoft: $($signature.Status)"
    }
}

Step "Programmsymbol"
Add-Type -AssemblyName System.Drawing
$ico = Join-Path $obj "app.ico"
$sizes = 16, 20, 24, 32, 48, 64, 256
$images = @()
foreach ($size in $sizes) {
    $bitmap = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bitmap)
    $g.SmoothingMode = "AntiAlias"
    $g.TextRenderingHint = "AntiAliasGridFit"
    $scale = $size / 64.0
    function RoundRect($x, $y, $w, $h, $r) {
        $p = New-Object System.Drawing.Drawing2D.GraphicsPath
        $d = 2 * $r
        $p.AddArc($x, $y, $d, $d, 180, 90); $p.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
        $p.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90); $p.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
        $p.CloseFigure(); return $p
    }
    # Farben und Aufbau wie src/main/resources/static/favicon.svg
    $navy = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(0x0B, 0x3A, 0x63))
    $cyan = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(0x06, 0xC7, 0xD9))
    $dark = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(0x08, 0x2B, 0x4C))
    $g.FillPath($navy, (RoundRect 0 0 (64 * $scale) (64 * $scale) (12 * $scale)))
    $g.FillPath($cyan, (RoundRect (14 * $scale) (12 * $scale) (36 * $scale) (40 * $scale) (4 * $scale)))
    $font = New-Object System.Drawing.Font "Segoe UI", ([float](30 * $scale)), ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
    $format = New-Object System.Drawing.StringFormat
    $format.Alignment = "Center"; $format.LineAlignment = "Center"
    $g.DrawString("E", $font, $dark, (New-Object System.Drawing.RectangleF (0, (1 * $scale), (64 * $scale), (64 * $scale))), $format)
    $g.Dispose()
    $stream = New-Object System.IO.MemoryStream
    $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
    $images += , $stream.ToArray()
}
# ICO-Datei mit PNG-Einträgen (ICONDIR, je Bild ICONDIRENTRY, dann die Bilddaten)
$file = New-Object System.IO.MemoryStream
$writer = New-Object System.IO.BinaryWriter $file
$writer.Write([uint16]0); $writer.Write([uint16]1); $writer.Write([uint16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $dimension = if ($sizes[$i] -ge 256) { 0 } else { $sizes[$i] }
    $writer.Write([byte]$dimension); $writer.Write([byte]$dimension); $writer.Write([byte]0); $writer.Write([byte]0)
    $writer.Write([uint16]1); $writer.Write([uint16]32)
    $writer.Write([uint32]$images[$i].Length); $writer.Write([uint32]$offset)
    $offset += $images[$i].Length
}
foreach ($image in $images) { $writer.Write($image) }
$writer.Flush()
[System.IO.File]::WriteAllBytes($ico, $file.ToArray())

$assemblyInfo = Join-Path $obj "AssemblyInfo.cs"
@"
using System.Reflection;
[assembly: AssemblyTitle("PDF-zu-E-Rechnung Verwaltung")]
[assembly: AssemblyDescription("Verwaltungsanwendung für den Dienst PDF-zu-E-Rechnung")]
[assembly: AssemblyCompany("Hofmann IT")]
[assembly: AssemblyProduct("PDF-zu-E-Rechnung")]
[assembly: AssemblyCopyright("Hofmann IT")]
[assembly: AssemblyVersion("$numeric.0")]
[assembly: AssemblyFileVersion("$numeric.0")]
[assembly: AssemblyInformationalVersion("$version")]
"@ | Set-Content -Encoding UTF8 $assemblyInfo

$src = Join-Path $PSScriptRoot "src"
if (-not $SkipTests) {
    Step "Tests der Kernlogik"
    $testExe = Join-Path $obj "Tests.exe"
    & $csc /nologo /utf8output /codepage:65001 /warnaserror /target:exe /platform:x64 "/out:$testExe" `
        /r:System.dll /r:System.Core.dll /r:System.ServiceProcess.dll `
        (Join-Path $src "Core.cs") (Join-Path $src "ServiceControl.cs") (Join-Path $src "TemplateModel.cs") (Join-Path $PSScriptRoot "tests\Tests.cs")
    if ($LASTEXITCODE -ne 0) { throw "Tests konnten nicht übersetzt werden" }
    & $testExe
    if ($LASTEXITCODE -ne 0) { throw "Tests der Verwaltungsanwendung fehlgeschlagen" }
}

Step "Anwendung übersetzen"
$exe = Join-Path $out "PdfZuERechnung.Verwaltung.exe"
& $csc /nologo /utf8output /codepage:65001 /warnaserror /optimize+ /debug- /target:winexe /platform:x64 "/out:$exe" `
    "/win32icon:$ico" "/win32manifest:$(Join-Path $PSScriptRoot 'app.manifest')" `
    /r:System.dll /r:System.Core.dll /r:System.Drawing.dll /r:System.Windows.Forms.dll /r:System.ServiceProcess.dll /r:Microsoft.CSharp.dll `
    "/r:$core" "/r:$winforms" `
    (Join-Path $src "*.cs") $assemblyInfo
if ($LASTEXITCODE -ne 0) { throw "Übersetzung fehlgeschlagen" }

Copy-Item $core, $winforms, $loader $out
Copy-Item (Join-Path $sdk "LICENSE.txt") (Join-Path $out "LICENSE-WebView2.txt")
Copy-Item (Join-Path $sdk "NOTICE.txt") (Join-Path $out "NOTICE-WebView2.txt")

$size = [math]::Round(((Get-ChildItem $out | Measure-Object Length -Sum).Sum) / 1KB)
Step "Fertig: $exe ($size KB im Ordner $out)"
