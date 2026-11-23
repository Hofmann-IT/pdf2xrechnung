<#
.SYNOPSIS
  Ende-zu-Ende-Test des Vorlagen-Assistenten (ADR 0016) mit dem auf diesem Computer installierten
  Word und Excel. Nicht Teil von "mvn verify" und nicht des normalen Builds, weil Office nötig ist.

.DESCRIPTION
  1. Erzeugt Rechnungen in fremden Layouts (erfundene Daten) als .xlsx, .xls, .docx und .doc.
  2. Lässt die Verwaltungsanwendung sie ohne Fenster auswerten (/vorlagentest).
  3. Prüft die Berichte.
  4. Prüft die umgebauten Excel-Dateien: Das Test-PDF muss das Profil "standard" bestehen (calibrate),
     ein- und mehrseitig.

  Vorher bauen: mvn -q package  und  powershell -ExecutionPolicy Bypass -File desktop\windows\build-desktop.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File desktop\windows\tests\vorlagen-e2e.ps1
#>
[CmdletBinding()]
param(
    [string] $Exe = "",
    [string] $Work = "",
    [string] $JavaHome = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot" }),
    [switch] $KeepFixtures
)
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
if (-not $Exe) { $Exe = Join-Path $root "target\desktop\PdfZuERechnung.Verwaltung\PdfZuERechnung.Verwaltung.exe" }
if (-not $Work) { $Work = Join-Path $root "target\desktop\vorlagen-e2e" }
if (-not (Test-Path $Exe)) { throw "Verwaltungsanwendung nicht gebaut: $Exe" }
if (-not $KeepFixtures) {
    if (Test-Path $Work) { Remove-Item $Work -Recurse -Force }
    New-Item -ItemType Directory -Force $Work | Out-Null

    # ---------- Excel: Briefkopf als Textfeld, "RECHNUNG <Nr>", "Datum:", Spalten Pos. | Stück | Bezeichnung | EP | GP
    $x = New-Object -ComObject Excel.Application
    $x.Visible = $false; $x.DisplayAlerts = $false
    try {
        $wb = $x.Workbooks.Add()
        $ws = $wb.Worksheets.Item(1); $ws.Name = "Rechnung"
        $ws.Columns.Item("C").ColumnWidth = 30
        $box = $ws.Shapes.AddTextbox(1, 330, 10, 180, 70)
        $box.Placement = 3
        $box.TextFrame2.TextRange.Text = "Muster Bau GmbH`nWerkstr. 9, 80999 München`nTelefon 089/123456"
        $cells = [ordered]@{
            B8 = "Muster Bau GmbH, Werkstr. 9, 80999 München"; B9 = "Herr"; B10 = "Max Beispiel"; B11 = "Hauptstr. 5"; B12 = "80331 München"
            A16 = "RECHNUNG 5001"; F16 = "Datum:"; G16 = "03.03.2026"; A17 = "Leistungszeitpunkt 02/26"; F17 = "Kd.-Nr.:"
            A18 = "Bauvorhaben: Wohnung 2. OG"
            A20 = "Pos."; B20 = "Stück"; C20 = "Bezeichnung"; F20 = "EP"; G20 = "GP"
            A21 = "Pos. 1"; C21 = "Reparatur einer Zimmertür"; C22 = "Größe ca. 500 x 1400 mm"; B23 = "2 Stck."; C23 = "An-/Abfahrtskosten"; F23 = "inkl."
            A24 = "Pos. 2"; C24 = "Türgriff"
            F26 = "Summe netto"; F27 = "+ 19 % MwSt"; F28 = "Gesamtbetrag EUR"
            A30 = "Zahlbar innerhalb von 14 Tagen ohne Abzug."
        }
        foreach ($k in $cells.Keys) { $ws.Range($k).NumberFormat = "@"; $ws.Range($k).Value2 = [string]$cells[$k] }
        $ws.Range("B21").Value2 = 1; $ws.Range("F21").Value2 = 512.31; $ws.Range("G21").Formula = "=B21*F21"
        $ws.Range("B24").Value2 = 3; $ws.Range("F24").Value2 = 10; $ws.Range("G24").Formula = "=B24*F24"
        $ws.Range("G26").Formula = "=SUM(G21:G24)"; $ws.Range("G27").Formula = "=ROUND(G26*0.19,2)"; $ws.Range("G28").Formula = "=G26+G27"
        foreach ($a in "F21", "G21", "F24", "G24", "G26", "G27", "G28") { $ws.Range($a).NumberFormatLocal = "#.##0,00 €" }
        [string] $p = Join-Path $Work "fremd-excel.xlsx"
        $wb.SaveAs($p, 51)
        [string] $p = Join-Path $Work "fremd-excel-alt.xls"
        $wb.SaveAs($p, 56)
        # lange Rechnung: 45 Positionen, im Ergebnis zwei Seiten (Tabellenkopf muss sich wiederholen)
        $ws.Range("A24:G30").ClearContents() | Out-Null
        for ($i = 2; $i -le 45; $i++) {
            $r = 22 + $i
            $ws.Range("A$r").NumberFormat = "@"; $ws.Range("A$r").Value2 = "Pos. $i"; $ws.Range("B$r").Value2 = 2
            $ws.Range("C$r").Value2 = "Montagearbeit Abschnitt $i"; $ws.Range("F$r").Value2 = 10.5; $ws.Range("G$r").Formula = "=B$r*F$r"
            $ws.Range("F$r").NumberFormatLocal = "#.##0,00 €"; $ws.Range("G$r").NumberFormatLocal = "#.##0,00 €"
        }
        $ws.Range("F69").Value2 = "Summe netto"; $ws.Range("G69").Formula = "=SUM(G21:G67)"
        $ws.Range("F70").Value2 = "+ 19 % MwSt"; $ws.Range("G70").Formula = "=ROUND(G69*0.19,2)"
        $ws.Range("F71").Value2 = "Gesamtbetrag EUR"; $ws.Range("G71").Formula = "=G69+G70"
        foreach ($a in "G69", "G70", "G71") { $ws.Range($a).NumberFormatLocal = "#.##0,00 €" }
        $ws.Range("A73").Value2 = "Zahlbar innerhalb von 14 Tagen ohne Abzug."
        [string] $p = Join-Path $Work "fremd-excel-lang.xlsx"
        $wb.SaveAs($p, 51)
        $wb.Close($false)
        # Angaben, die im Assistenten der Benutzer einträgt (stehen nicht auf der Rechnung)
        foreach ($n in "fremd-excel.xlsx", "fremd-excel-alt.xls", "fremd-excel-lang.xlsx") {
            [System.IO.File]::WriteAllText((Join-Path $Work "$n.werte.txt"), "CustomerNumber=10001`r`nBuyerEmail=kunde@beispiel.example`r`n", (New-Object System.Text.UTF8Encoding($false)))
        }
    } finally { $x.Quit(); [void][Runtime.InteropServices.Marshal]::ReleaseComObject($x) }

    # ---------- Word: Kopfzeile, Textfeld, "RECHNUNG<Tab>Nr", Tabelle Pos.-Nr. | Stück | Bezeichnung | EP | GP, Summentabelle
    $w = New-Object -ComObject Word.Application
    $w.Visible = $false; $w.DisplayAlerts = 0
    try {
        $d = $w.Documents.Add()
        $d.Sections.Item(1).Headers.Item(1).Range.Text = "Muster Fensterbau GmbH"
        $sel = $w.Selection
        foreach ($t in "Muster Fensterbau GmbH, Ringstr. 1, 85000 Musterdorf", "Beispiel Handel SE & Co. KG", "Industriestr. 2", "86150 Augsburg", "",
                       "RECHNUNG`t7002", "Datum: 26. Februar 2026", "Kunden-Nr.: 10002", "Projekt Nr.: 231123", "") {
            $sel.TypeText([string]$t); $sel.TypeParagraph()
        }
        $t1 = $d.Tables.Add($sel.Range, 2, 5)
        $head = "Pos.-Nr.", "Stück", "Bezeichnung", "EP", "GP"
        for ($c = 1; $c -le 5; $c++) { $t1.Cell(1, $c).Range.Text = [string]$head[$c - 1] }
        $t1.Cell(2, 1).Range.Text = "001"; $t1.Cell(2, 2).Range.Text = "1"
        $t1.Cell(2, 3).Range.Text = "2-teiliges Fensterelement aus Aluminium`rElementmaß 1740 mm x 1350 mm"
        $t1.Cell(2, 4).Range.Text = "5.784,13 €"; $t1.Cell(2, 5).Range.Text = "5.784,13 €"
        [void]$sel.EndKey(6); $sel.TypeParagraph()
        $t2 = $d.Tables.Add($sel.Range, 3, 2)
        $sum = @(@("Gesamtsumme", "5.784,13 €"), @("MwSt. 19 %", "1.098,98 €"), @("Endbetrag", "6.883,11 €"))
        for ($r = 1; $r -le 3; $r++) { $t2.Cell($r, 1).Range.Text = [string]$sum[$r - 1][0]; $t2.Cell($r, 2).Range.Text = [string]$sum[$r - 1][1] }
        [void]$sel.EndKey(6); $sel.TypeParagraph()
        $sel.TypeText("Zahlbar bis zum 12.03.2026 ohne Abzug."); $sel.TypeParagraph(); $sel.TypeText("Mit freundlichen Grüßen")
        $tb = $d.Shapes.AddTextbox(1, 400, 60, 160, 50)
        $tb.TextFrame.TextRange.Text = "Telefon 0841/1234`rrechnung@muster-fensterbau.example"
        [string] $p = Join-Path $Work "fremd-word.docx"
        $d.SaveAs2($p, 16)
        [string] $p = Join-Path $Work "fremd-word-alt.doc"
        $d.SaveAs2($p, 0)
        $d.Close($false)
        foreach ($n in "fremd-word.docx", "fremd-word-alt.doc") {
            [System.IO.File]::WriteAllText((Join-Path $Work "$n.werte.txt"), "BuyerEmail=kunde@beispiel.example`r`n", (New-Object System.Text.UTF8Encoding($false)))
        }
    } finally { $w.Quit(); [void][Runtime.InteropServices.Marshal]::ReleaseComObject($w) }
}

# ---------- Auswertung durch die Verwaltungsanwendung
$out = Join-Path $Work "ergebnis"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
$proc = Start-Process $Exe -ArgumentList @("/vorlagentest", "`"$Work`"") -PassThru
if (-not $proc.WaitForExit(600000)) { $proc.Kill(); throw "Zeitüberschreitung bei /vorlagentest" }
$failures = 0
function Expect([string] $file, [string[]] $texts) {
    $path = Join-Path $out "$file.analyse.txt"
    if (-not (Test-Path $path)) { Write-Host "FEHLGESCHLAGEN: $file – kein Bericht"; $script:failures++; return }
    $report = Get-Content $path -Raw -Encoding UTF8
    foreach ($t in $texts) {
        if (-not $report.Contains($t)) { Write-Host "FEHLGESCHLAGEN: $file – erwartet: $t"; $script:failures++ }
    }
}
$excel = "(Excel, Blatt ", "Rechnungsnummer:", "5001", "[bisher: RECHNUNG]", "03.03.2026", "Herr Max Beispiel", "80331 München",
         "Positionen: 2", "inkl. 2 Stck. An-/Abfahrtskosten", "542,31", "103,04", "645,35", "Form/Textfeld", "2 Pflichtangabe(n) fehlen"
Expect "fremd-excel.xlsx" $excel
Expect "fremd-excel-alt.xls" $excel
$word = "(Word)", "7002", "26.02.2026", "10002", "Beispiel Handel SE & Co. KG", "86150 Augsburg", "Positionen: 1", "2-teiliges Fensterelement aus Aluminium",
        "Elementmaß 1740 mm x 1350 mm", "[bisher: Gesamtsumme]", "1.098,98", "6.883,11", "12.03.2026", "Kopfzeile: Muster Fensterbau GmbH", "1 Pflichtangabe(n) fehlen"
Expect "fremd-word.docx" $word
Expect "fremd-word-alt.doc" $word
Expect "fremd-excel-lang.xlsx" @("Positionen: 45", "1.436,31", "272,90", "1.709,21")

# ---------- Umbau (Excel): Die erzeugten Test-PDFs müssen das Profil "standard" bestehen
$jar = Join-Path $root "target\pdf-zu-erechnung.jar"
if (-not (Test-Path $jar)) { throw "Dienst nicht gebaut: $jar fehlt (mvn -q package)" }
if (-not (Test-Path "$JavaHome\bin\java.exe")) { throw "JDK nicht gefunden unter $JavaHome" }
function ExpectConverted([string] $file, [int] $positions, [int] $pages, [string] $extension = ".xlsx",
                         [string[]] $tokens = @("10001", "kunde@beispiel.example", "Herr Max Beispiel", "Hauptstr. 5", "5001")) {
    $stem = [System.IO.Path]::GetFileNameWithoutExtension($file) + "_E-Rechnung"
    foreach ($n in "$stem$extension", "${stem}_markiert$extension", "${stem}_Test.pdf") {
        if (-not (Test-Path (Join-Path $out $n))) { Write-Host "FEHLGESCHLAGEN: $file – $n wurde nicht erzeugt"; $script:failures++; return }
    }
    $notes = Get-Content (Join-Path $out "$file.umbau.txt") -Raw -Encoding UTF8
    if ($notes.Contains("ACHTUNG")) { Write-Host "FEHLGESCHLAGEN: $file – neu berechnete Summen weichen ab:`n$notes"; $script:failures++ }
    $report = Join-Path $out "${stem}_Test.kalibrierung.txt"
    Push-Location $root
    try {
        & "$JavaHome\bin\java.exe" -jar $jar calibrate --samples (Join-Path $out "${stem}_Test.pdf") --profile .\profiles\standard.yaml --tenant hofmann-it --out $report > (Join-Path $out "calibrate.log") 2>&1
        $code = $LASTEXITCODE
    } finally { Pop-Location }
    $text = if (Test-Path $report) { Get-Content $report -Raw -Encoding UTF8 } else { "" }
    foreach ($t in @("Seiten: $pages", "Positionen: $positions", "bestanden") + $tokens) {
        if (-not $text.Contains($t)) { Write-Host "FEHLGESCHLAGEN: $file (umgebaut) – Profilprüfung, erwartet: $t"; $script:failures++ }
    }
    if ($code -ne 0 -or $text.Contains("NICHT bestanden")) { Write-Host "FEHLGESCHLAGEN: $file (umgebaut) – Profilprüfung nicht bestanden (Bericht: $report)"; $script:failures++ }
}
ExpectConverted "fremd-excel.xlsx" 2 1
ExpectConverted "fremd-excel-alt.xls" 2 1
ExpectConverted "fremd-excel-lang.xlsx" 45 2
# ---------- Umbau (Word): Briefkopf bleibt, Rechnungsteil im Standardaufbau, Test-PDF besteht das Profil
$wordTokens = "10002", "kunde@beispiel.example", "Beispiel Handel SE & Co. KG", "Industriestr. 2", "7002", "5784.13", "6883.11"
ExpectConverted "fremd-word.docx" 1 1 ".docx" $wordTokens
ExpectConverted "fremd-word-alt.doc" 1 1 ".docx" $wordTokens
Get-Content (Join-Path $out "uebersicht.txt") -Encoding UTF8
if ($proc.ExitCode -ne 0) { Write-Host "FEHLGESCHLAGEN: /vorlagentest endete mit $($proc.ExitCode)"; $failures++ }
if ($failures -gt 0) { throw "$failures Prüfung(en) des Vorlagen-Assistenten fehlgeschlagen (Berichte: $out)" }
Write-Host "Vorlagen-Assistent: alle Prüfungen bestanden (Berichte: $out)"
