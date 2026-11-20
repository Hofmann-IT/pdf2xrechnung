<#
.SYNOPSIS
  Bereitet den Zugriff aus dem Firmennetz vor (ADR 0015, Phase B): Serverzertifikat im
  Windows-Zertifikatsspeicher und Firewall-Regel. Als Administrator ausführen.

.DESCRIPTION
  1. Zertifikat: Liegt unter "Lokaler Computer -> Eigene Zertifikate" noch kein gültiges
     Zertifikat mit dem Anzeigenamen "PDF-zu-ERechnung" und privatem Schlüssel, wird ein
     selbstsigniertes erzeugt (RSA 3072, 5 Jahre, privater Schlüssel nicht exportierbar).
     Der Schlüssel verlässt den Zertifikatsspeicher nicht; es gibt keine Schlüsseldatei und
     kein Passwort. Ein vorhandenes Zertifikat (z. B. der eigenen Zertifizierungsstelle) wird
     nicht verändert.
  2. Firewall: eingehende Regel für den HTTPS-Port, nur für die Profile Domäne und Privat
     (nicht für öffentliche Netze). Eine vorhandene Regel gleichen Namens wird angepasst.

  Danach in der Oberfläche unter Verwaltung -> Netzzugriff "Dieser Computer und Firmennetz
  (HTTPS)" speichern und den Dienst neu starten. Das Skript ändert weder die Konfiguration der
  Anwendung noch startet es den Dienst neu.

.PARAMETER Port
  HTTPS-Port (Standard 8443); muss dem Port in Verwaltung -> Netzzugriff entsprechen.

.PARAMETER Entfernen
  Entfernt die Firewall-Regel wieder. Das Zertifikat bleibt erhalten.

.PARAMETER OhneFirewall
  Nur das Zertifikat einrichten (z. B. wenn die Firewall zentral verwaltet wird).

.PARAMETER StoreLocation
  Zertifikatsspeicher: LocalMachine (Standard, vom Dienst verwendet) oder CurrentUser (nur für Tests).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File netzbetrieb-einrichten.ps1
#>
[CmdletBinding()]
param(
    [ValidateRange(1024, 65535)] [int] $Port = 8443,
    [switch] $Entfernen,
    [switch] $OhneFirewall,
    [ValidateSet("LocalMachine", "CurrentUser")] [string] $StoreLocation = "LocalMachine",
    [string] $Anzeigename = "PDF-zu-ERechnung"
)

$ErrorActionPreference = "Stop"
$ruleName = "PDF-zu-E-Rechnung (HTTPS)"

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
$needsAdmin = ($StoreLocation -eq "LocalMachine") -or (-not $OhneFirewall) -or $Entfernen
if ($needsAdmin -and -not $isAdmin) {
    throw "Bitte als Administrator ausführen (Rechtsklick auf PowerShell -> Als Administrator ausführen)."
}

if ($Entfernen) {
    $rule = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
    if ($rule) {
        $rule | Remove-NetFirewallRule
        Write-Host "Firewall-Regel '$ruleName' entfernt."
    } else {
        Write-Host "Keine Firewall-Regel '$ruleName' vorhanden."
    }
    Write-Host "Das Zertifikat bleibt im Zertifikatsspeicher. In der Verwaltung auf 'Nur dieser Computer' stellen und den Dienst neu starten."
    return
}

# --- Zertifikat -----------------------------------------------------------------------------
$store = "Cert:\$StoreLocation\My"
$now = Get-Date
$cert = Get-ChildItem $store |
    Where-Object { $_.FriendlyName -eq $Anzeigename -and $_.HasPrivateKey -and $_.NotAfter -gt $now.AddDays(30) } |
    Sort-Object NotAfter -Descending | Select-Object -First 1

if ($cert) {
    Write-Host "Vorhandenes Zertifikat '$Anzeigename' wird verwendet (gültig bis $($cert.NotAfter.ToString('dd.MM.yyyy')))."
} else {
    $names = @($env:COMPUTERNAME, "localhost")
    try {
        $fqdn = [System.Net.Dns]::GetHostEntry($env:COMPUTERNAME).HostName
        if ($fqdn -and ($names -notcontains $fqdn)) { $names += $fqdn }
    } catch { }
    $cert = New-SelfSignedCertificate -CertStoreLocation $store -FriendlyName $Anzeigename `
        -Subject "CN=$env:COMPUTERNAME" -DnsName $names `
        -KeyAlgorithm RSA -KeyLength 3072 -HashAlgorithm SHA256 -KeyExportPolicy NonExportable `
        -KeyUsage DigitalSignature, KeyEncipherment `
        -TextExtension @("2.5.29.37={text}1.3.6.1.5.5.7.3.1") `
        -NotAfter $now.AddYears(5)
    Write-Host "Zertifikat '$Anzeigename' erzeugt (gültig bis $($cert.NotAfter.ToString('dd.MM.yyyy')), Namen: $($names -join ', '))."
}

$sha256 = [System.BitConverter]::ToString(
    [System.Security.Cryptography.SHA256]::Create().ComputeHash($cert.RawData)).Replace("-", ":")

# --- Firewall -------------------------------------------------------------------------------
if (-not $OhneFirewall) {
    $rule = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
    if ($rule) {
        $rule | Set-NetFirewallRule -Profile Domain, Private -Enabled True -Action Allow
        $rule | Get-NetFirewallPortFilter | Set-NetFirewallPortFilter -Protocol TCP -LocalPort $Port
        Write-Host "Firewall-Regel '$ruleName' angepasst (TCP $Port, Profile Domäne und Privat)."
    } else {
        New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow -Protocol TCP -LocalPort $Port `
            -Profile Domain, Private `
            -Description "Zugriff auf PDF-zu-E-Rechnung aus dem Firmennetz (HTTPS). Entfernen: netzbetrieb-einrichten.ps1 -Entfernen" | Out-Null
        Write-Host "Firewall-Regel '$ruleName' angelegt (TCP $Port, Profile Domäne und Privat)."
    }
}

Write-Host ""
Write-Host "Fingerabdruck (SHA-256) – beim ersten Verbinden auf den Arbeitsplätzen vergleichen:"
Write-Host "  $sha256"
Write-Host ""
Write-Host "Nächste Schritte:"
Write-Host "  1. Oberfläche -> Verwaltung -> Netzzugriff: 'Dieser Computer und Firmennetz (HTTPS)', Port $Port, speichern."
Write-Host "  2. Dienst 'PDF-zu-E-Rechnung' neu starten."
Write-Host "  3. Arbeitsplätze verbinden sich mit https://$($env:COMPUTERNAME.ToLower()):$Port"
