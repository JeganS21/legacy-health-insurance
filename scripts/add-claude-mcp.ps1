# Adds the "legacy-health-insurance" MCP server to Claude Desktop's config.
# Safe to run more than once. Keeps any servers you already have and saves a .bak copy first.
# Run from the project folder:
#   powershell -ExecutionPolicy Bypass -File .\scripts\add-claude-mcp.ps1

$ErrorActionPreference = "Stop"
$serverName = "legacy-health-insurance"
$entry = [pscustomobject]@{
    command = "npx"
    args    = @("mcp-remote", "http://localhost:8081/mcp")
}

if (-not (Get-Command npx -ErrorAction SilentlyContinue)) {
    Write-Warning "npx was not found. Install Node.js from https://nodejs.org first - Claude Desktop needs it to start mcp-remote."
}

# Normal install location, plus the Microsoft Store (MSIX) location if it exists
$files = @("$env:APPDATA\Claude\claude_desktop_config.json")
Get-ChildItem "$env:LOCALAPPDATA\Packages" -Directory -Filter "Claude_*" -ErrorAction SilentlyContinue | ForEach-Object {
    $dir = Join-Path $_.FullName "LocalCache\Roaming\Claude"
    if (Test-Path $dir) { $files += (Join-Path $dir "claude_desktop_config.json") }
}

# Newer Claude Desktop builds keep settings in %LOCALAPPDATA%\Claude-Data\claude_<id>
Get-ChildItem "$env:LOCALAPPDATA\Claude-Data" -Directory -Filter "claude_*" -ErrorAction SilentlyContinue | ForEach-Object {
    $files += (Join-Path $_.FullName "claude_desktop_config.json")
}

$updated = 0
foreach ($file in $files) {
    $dir = Split-Path $file
    if (-not (Test-Path $dir)) { continue }

    if (Test-Path $file) {
        Copy-Item $file "$file.bak" -Force
        $raw = Get-Content $file -Raw
        if ([string]::IsNullOrWhiteSpace($raw)) { $config = [pscustomobject]@{} } else { $config = $raw | ConvertFrom-Json }
    } else {
        $config = [pscustomobject]@{}
    }

    if (-not $config.PSObject.Properties["mcpServers"]) {
        $config | Add-Member -NotePropertyName "mcpServers" -NotePropertyValue ([pscustomobject]@{})
    }
    if ($config.mcpServers.PSObject.Properties[$serverName]) {
        $config.mcpServers.$serverName = $entry
    } else {
        $config.mcpServers | Add-Member -NotePropertyName $serverName -NotePropertyValue $entry
    }

    $json = $config | ConvertTo-Json -Depth 20
    # UTF-8 without BOM - Claude Desktop can't read a BOM
    [System.IO.File]::WriteAllText($file, $json, (New-Object System.Text.UTF8Encoding $false))
    Write-Host "Updated: $file" -ForegroundColor Green
    $updated++
}

if ($updated -eq 0) {
    Write-Warning "No Claude Desktop settings folder found. Open Claude Desktop once, then run this script again."
} else {
    Write-Host ""
    Write-Host "Done. Now fully quit Claude Desktop (system tray icon -> Quit) and open it again." -ForegroundColor Cyan
    Write-Host "Keep the Spring Boot app running on port 8081 so Claude can connect."
}
