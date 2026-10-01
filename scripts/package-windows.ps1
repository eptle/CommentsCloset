# Собирает dist\CommentsCloset-1.0.0.exe (нужны JDK 21+ с jpackage и WiX Toolset 3.x).
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

.\mvnw.cmd -q -B package
Remove-Item dist -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory dist\input | Out-Null
Copy-Item target\comments-closet.jar dist\input\

jpackage --type exe --name CommentsCloset --app-version 1.0.0 `
  --input dist\input --main-jar comments-closet.jar --main-class com.commentscloset.Launcher `
  --icon packaging\icon.ico --dest dist --win-shortcut --win-menu --win-dir-chooser
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }
Write-Host "Готово: dist\CommentsCloset-1.0.0.exe"
