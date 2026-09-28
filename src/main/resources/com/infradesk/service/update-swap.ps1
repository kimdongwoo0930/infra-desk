# InfraDesk self-update (Windows). Started by the app right before it quits:
#   update-swap.ps1 <app pid> <installed app folder> <new app folder>
# Waits for the app to exit, swaps the folders (restoring the old one if anything fails),
# then starts whichever is in place. INFRADESK_SWAP_NO_LAUNCH=1 skips the start (tests).
param([int]$ParentId, [string]$App, [string]$New)

# Never stand inside the folder being renamed (Windows won't rename a process's current directory).
Set-Location -LiteralPath ([System.IO.Path]::GetTempPath())

function Log($message) { "{0:yyyy-MM-dd HH:mm:ss} {1}" -f (Get-Date), $message }

try { Wait-Process -Id $ParentId -Timeout 60 -ErrorAction Stop } catch { }
$backup = "$App.previous"

Log "installing $New -> $App"
if (Test-Path -LiteralPath $backup) { Remove-Item -LiteralPath $backup -Recurse -Force -ErrorAction SilentlyContinue }

# Files can stay locked for a moment after exit (antivirus, Explorer), so retry the rename.
$moved = $false
$lastError = ""
for ($i = 0; $i -lt 40 -and -not $moved; $i++) {
    try {
        Move-Item -LiteralPath $App -Destination $backup -ErrorAction Stop
        $moved = $true
    } catch {
        $lastError = $_.Exception.Message
        Start-Sleep -Milliseconds 500
    }
}

if ($moved) {
    try {
        Move-Item -LiteralPath $New -Destination $App -ErrorAction Stop
        Remove-Item -LiteralPath $backup -Recurse -Force -ErrorAction SilentlyContinue
        Log "updated"
    } catch {
        Log "could not move the new app in ($_); restoring the old one"
        Move-Item -LiteralPath $backup -Destination $App -ErrorAction SilentlyContinue
    }
} else {
    Log "could not move the old app aside ($lastError); update skipped"
}
Remove-Item -LiteralPath (Split-Path -Parent $New) -Recurse -Force -ErrorAction SilentlyContinue

if ($env:INFRADESK_SWAP_NO_LAUNCH -ne "1") {
    Start-Process -FilePath (Join-Path $App "InfraDesk.exe") -WorkingDirectory $App
}
