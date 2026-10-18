param(
    [switch]$Package
)

$ErrorActionPreference = "Stop"
$desktop = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $desktop
$classes = Join-Path $desktop "build\classes"
$dist = Join-Path $root "dist"

Write-Host "==> Compiling desktop sources"
Remove-Item -Recurse -Force $classes -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$files = Get-ChildItem (Join-Path $desktop "src") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -d $classes $files
if ($LASTEXITCODE -ne 0) {
    throw "javac failed"
}

Write-Host "==> Packing SmsBridge.jar"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$jar = Join-Path $dist "SmsBridge.jar"
& jar --create --file $jar --main-class com.smsbridge.pc.Main -C $classes .
if ($LASTEXITCODE -ne 0) {
    throw "jar failed"
}

if ($Package) {
    Write-Host "==> Building self-contained Windows app (jpackage)"
    $staging = Join-Path $desktop "build\jpackage-input"
    Remove-Item -Recurse -Force $staging -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $staging | Out-Null
    Copy-Item $jar (Join-Path $staging "SmsBridge.jar")
    $appOut = Join-Path $dist "desktop"
    Remove-Item -Recurse -Force $appOut -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $appOut | Out-Null
    $icon = Join-Path $root "assets\SmsBridge.ico"
    & jpackage --type app-image --name SmsBridge --app-version 1.0.0 `
        --input $staging --main-jar SmsBridge.jar `
        --main-class com.smsbridge.pc.Main `
        --icon $icon --dest $appOut `
        --vendor "SmsBridge" --description "LAN SMS to PC clipboard bridge" `
        --java-options "-Dfile.encoding=UTF-8"
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage failed"
    }
    Write-Host "App: $(Join-Path $appOut 'SmsBridge\SmsBridge.exe')"
}

Write-Host "Jar: $jar"
