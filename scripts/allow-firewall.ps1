#Requires -RunAsAdministrator
# Allow SmsBridge through Windows Firewall on private networks.
# TCP 47823 = WebSocket, UDP 47824 = auto discovery broadcast.

$ErrorActionPreference = "Stop"

$tcpRule = "SmsBridge TCP 47823"
$udpRule = "SmsBridge UDP 47824"

Get-NetFirewallRule -DisplayName $tcpRule -ErrorAction SilentlyContinue | Remove-NetFirewallRule
Get-NetFirewallRule -DisplayName $udpRule -ErrorAction SilentlyContinue | Remove-NetFirewallRule

New-NetFirewallRule -DisplayName $tcpRule -Direction Inbound -Action Allow `
    -Protocol TCP -LocalPort 47823 -Profile Private,Domain
New-NetFirewallRule -DisplayName $udpRule -Direction Inbound -Action Allow `
    -Protocol UDP -LocalPort 47824 -Profile Private,Domain

Write-Host "Firewall rules added for SmsBridge."
Write-Host "If your Wi-Fi is marked as Public, switch it to Private in Windows settings."
