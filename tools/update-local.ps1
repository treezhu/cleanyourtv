# Update the local copy of Gathin23/cleanyourtv from upstream, rebuild the launcher
# with this TV's local customizations, and reinstall it keeping the app data
# (Dock, theme, config).
#
# Usage: tools\update-local.ps1 -TvHost 192.168.1.50 [-StockLauncher <package>|none]
#
# The repo has one local commit on top of upstream main ("Local customizations for
# Hisense 65E3G"); git pull --rebase replays it on the new upstream tip. If git
# stops on a conflict, resolve the file, `git add` it and run `git rebase --continue`,
# then re-run this script — it builds whatever is in the tree.

param(
    [string]$TvHost = $env:TV_HOST,
    [string]$StockLauncher = ''
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }

# GitHub is only reachable here through the local proxy; the repo config carries
# http.proxy = http://127.0.0.1:7890 (git config http.proxy). Re-add it if a fresh
# clone of this tree ever loses it.

Push-Location $repo
try {
    git pull --rebase origin main
    if ($LASTEXITCODE -ne 0) {
        throw 'git pull --rebase stopped (conflict or network). Fix it, then re-run. Nothing was built or sent to the TV.'
    }

    # Build settings: this machine reaches Google Maven only through the mirror init script.
    # Override with $env:CLEANYOURTV_TOOLCHAIN when the toolchain lives elsewhere.
    $tc = if ($env:CLEANYOURTV_TOOLCHAIN) { $env:CLEANYOURTV_TOOLCHAIN } else { Join-Path $env:USERPROFILE 'Doubao\chats\2026-11-07\new-chat\_toolchain' }
    if (-not (Test-Path -LiteralPath "$tc\gradle-8.9\bin\gradle.bat")) { throw "no Gradle toolchain at $tc; set CLEANYOURTV_TOOLCHAIN to the folder holding gradle-8.9 and mirrors.init.gradle" }
    $jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot' }
    if (-not (Test-Path -LiteralPath "$jdk\bin\java.exe")) { throw "no JDK at $jdk; set JAVA_HOME to a JDK 17" }
    $env:JAVA_HOME = $jdk
    $env:ANDROID_HOME = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
    & "$tc\gradle-8.9\bin\gradle.bat" --stop 2>&1 | Out-Null
    & "$tc\gradle-8.9\bin\gradle.bat" --init-script "$tc\mirrors.init.gradle" -p "$repo\launcher" assembleRelease --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed. Nothing was sent to the TV.' }

    $env:SKIP_BUILD = '1'
    & "$repo\launcher\build-and-install.ps1" -TvHost $TvHost -StockLauncher $StockLauncher
} finally {
    Remove-Item env:SKIP_BUILD -ErrorAction SilentlyContinue
    Pop-Location
}
