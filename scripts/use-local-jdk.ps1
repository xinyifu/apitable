$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$jdkRoot = Join-Path $repoRoot ".cache\jdk"

$jdk = Get-ChildItem -Path $jdkRoot -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName "bin\java.exe") } |
    Sort-Object Name -Descending |
    Select-Object -First 1

if ($null -eq $jdk) {
    throw "No local JDK found under $jdkRoot. Install or extract JDK 17 into .cache\jdk first."
}

$javaBin = Join-Path $jdk.FullName "bin"
$env:JAVA_HOME = $jdk.FullName

$pathParts = $env:PATH -split [IO.Path]::PathSeparator |
    Where-Object { $_ -and $_ -ne $javaBin }
$env:PATH = @($javaBin) + $pathParts -join [IO.Path]::PathSeparator

Write-Host "JAVA_HOME=$env:JAVA_HOME"
& (Join-Path $javaBin "java.exe") -version
