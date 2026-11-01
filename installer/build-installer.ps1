<#
.SYNOPSIS
  Builds the Placement Tracker Windows installer (.exe) with jpackage + WiX 5.
.PARAMETER JdkHome
  A JDK 24+ (jpackage only; the app itself runs on the bundled runtime). Defaults to
  $env:JPACKAGE_JDK, else $env:JAVA_HOME.
.PARAMETER AppVersion
  x.y.z installer version. Default: the pom version without -SNAPSHOT.
.PARAMETER SkipTests
  Pass -DskipTests to Maven.
#>
param(
    [string]$JdkHome,
    [string]$AppVersion,
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

# --- JDK -------------------------------------------------------------------
if (-not $JdkHome) { $JdkHome = $env:JPACKAGE_JDK }
if (-not $JdkHome) { $JdkHome = $env:JAVA_HOME }
if (-not $JdkHome) {
    throw 'No JDK given. Pass -JdkHome <path> or set JPACKAGE_JDK (a JDK 24 or newer).'
}
$jpackage = Join-Path $JdkHome 'bin\jpackage.exe'
if (-not (Test-Path $jpackage)) {
    throw "jpackage.exe not found at $jpackage. -JdkHome must point to a JDK 24 or newer."
}
$verText = (& $jpackage --version | Select-Object -First 1)
if ($verText -notmatch '^(\d+)') {
    throw "Could not read the jpackage version from: $verText"
}
$major = [int]$Matches[1]
if ($major -lt 24) {
    throw "jpackage $verText is too old; WiX 5 needs jpackage from JDK 24 or newer. Pass -JdkHome <JDK 24+>."
}

# --- WiX -------------------------------------------------------------------
if (-not (Get-Command wix.exe -ErrorAction SilentlyContinue)) {
    $wixDir = 'C:\Program Files\WiX Toolset v5.0\bin'
    if (Test-Path (Join-Path $wixDir 'wix.exe')) {
        $env:PATH = $wixDir + ';' + $env:PATH
    } else {
        throw ("WiX 5 not found. Install WiX 5: winget install --id WiXToolset.WiXCLI --version 5.0.2.0`n" +
               "Then add the extensions:`n" +
               "  wix extension add -g WixToolset.Util.wixext/5.0.2`n" +
               "  wix extension add -g WixToolset.UI.wixext/5.0.2")
    }
}

# --- Build the jar ---------------------------------------------------------
Push-Location $root
try {
    $mvnArgs = @('clean', 'package')
    if ($SkipTests) { $mvnArgs += '-DskipTests' }
    & .\mvnw.cmd @mvnArgs
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed (exit $LASTEXITCODE)." }

    $jar = Get-ChildItem target -Filter 'placement-tracker-*.jar' |
        Where-Object { $_.Name -notmatch '(sources|javadoc|original)' } |
        Select-Object -First 1
    if (-not $jar) { throw 'No application jar found in target\.' }

    if (-not $AppVersion) {
        if ($jar.BaseName -match '^placement-tracker-(\d+\.\d+\.\d+)') {
            $AppVersion = $Matches[1]
        } else {
            throw "Cannot derive the version from $($jar.Name); pass -AppVersion x.y.z."
        }
    }

    # Stage only the app jar.
    $stage = 'target\installer-input'
    if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
    New-Item -ItemType Directory -Path $stage | Out-Null
    Copy-Item $jar.FullName $stage

    $dest = 'target\installer'
    if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }

    & $jpackage --type exe --name 'Placement Tracker' --app-version $AppVersion `
        --vendor 'Yusuf Aslam' --description 'Tracks placement applications from Gmail' `
        --input $stage --main-jar $jar.Name `
        --icon 'installer\placement-tracker.ico' `
        --win-per-user-install --win-menu --win-menu-group 'Placement Tracker' --win-shortcut `
        --win-upgrade-uuid '6f1c2b8e-4d3a-4b7e-9a51-2c0e8f3d7a14' `
        --dest $dest
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed (exit $LASTEXITCODE)." }

    $exe = Get-ChildItem $dest -Filter '*.exe' | Select-Object -First 1
    if (-not $exe) { throw 'jpackage reported success but no .exe was produced.' }
    Write-Host "Installer: $($exe.FullName)"
} finally {
    Pop-Location
}
