$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$nodeRoot = Join-Path $repoRoot ".cache\node"

$node = Get-ChildItem -Path $nodeRoot -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName "node.exe") } |
    Sort-Object Name -Descending |
    Select-Object -First 1

if ($null -eq $node) {
    throw "No local Node.js found under $nodeRoot. Install or extract Node.js into .cache\node first."
}

$nodeBin = $node.FullName
$env:PATH = @($nodeBin) + ($env:PATH -split [IO.Path]::PathSeparator |
    Where-Object { $_ -and $_ -ne $nodeBin }) -join [IO.Path]::PathSeparator

Write-Host "NODE_HOME=$nodeBin"
& (Join-Path $nodeBin "node.exe") -v
