# Project Phoenix - Gradle Bootstrap Script
# Installs Gradle 8.8 to user folder and sets up the wrapper. No admin required.

$ErrorActionPreference = "Stop"
$GradleVersion = "8.8"
$GradleDir = "$env:USERPROFILE\.gradle-installs\gradle-$GradleVersion"
$GradleBin = "$GradleDir\bin\gradle.bat"
$ZipUrl = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"
$ZipPath = "$env:TEMP\gradle-$GradleVersion-bin.zip"

Write-Host "=== Project Phoenix Gradle Bootstrap ===" -ForegroundColor Cyan

# Step 1: Check if already installed
if (Test-Path $GradleBin) {
    Write-Host "Gradle $GradleVersion already installed at $GradleDir" -ForegroundColor Green
} else {
    # Step 2: Download
    Write-Host "Downloading Gradle $GradleVersion..." -ForegroundColor Yellow
    Invoke-WebRequest -Uri $ZipUrl -OutFile $ZipPath -UseBasicParsing
    Write-Host "Download complete." -ForegroundColor Green

    # Step 3: Extract
    Write-Host "Extracting..." -ForegroundColor Yellow
    $InstallParent = "$env:USERPROFILE\.gradle-installs"
    New-Item -ItemType Directory -Force -Path $InstallParent | Out-Null
    Expand-Archive -Path $ZipPath -DestinationPath $InstallParent -Force
    Remove-Item $ZipPath -Force
    Write-Host "Extracted to $GradleDir" -ForegroundColor Green
}

# Step 4: Generate gradlew wrapper
Write-Host "Generating Gradle wrapper..." -ForegroundColor Yellow
$ProjectDir = $PSScriptRoot
Push-Location $ProjectDir
& "$GradleBin" wrapper --gradle-version $GradleVersion --distribution-type bin
Pop-Location

if (Test-Path "$ProjectDir\gradlew.bat") {
    Write-Host "Wrapper generated." -ForegroundColor Green
} else {
    Write-Host "Wrapper generation failed - falling back to direct gradle run." -ForegroundColor Yellow
}

# Step 5: Add to PATH for this session
$env:PATH = "$GradleDir\bin;$env:PATH"

Write-Host ""
Write-Host "=== Setup complete! ===" -ForegroundColor Cyan
Write-Host "Now running the app..." -ForegroundColor Yellow
Write-Host ""

# Step 6: Run (use gradlew.bat if available, else gradle directly)
if (Test-Path "$ProjectDir\gradlew.bat") {
    Push-Location $ProjectDir
    & "$ProjectDir\gradlew.bat" run
    Pop-Location
} else {
    Push-Location $ProjectDir
    & "$GradleBin" run
    Pop-Location
}
