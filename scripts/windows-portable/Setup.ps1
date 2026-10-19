# Windows PowerShell 5.1. Downloads only pinned official archives if needed.
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$root = $PSScriptRoot
try {
    if (-not [Environment]::Is64BitOperatingSystem) { throw 'This bundle requires 64-bit Windows 10/11.' }
    $manifest = Get-Content -LiteralPath (Join-Path $root 'tools-manifest.json') -Raw | ConvertFrom-Json
    $tools = Join-Path $root 'tools'
    New-Item -ItemType Directory -Path $tools -Force | Out-Null
    foreach ($package in $manifest.packages) {
        $target = Join-Path $tools $package.directory
        $valid = $true
        foreach ($file in $package.files.PSObject.Properties) {
            $path = Join-Path $target $file.Name
            if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { $valid = $false; break }
            if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $file.Value) { $valid = $false; break }
        }
        if ($valid) { Write-Host ($package.name + ': ready'); continue }
        Write-Host ('Downloading ' + $package.name + ' from official source...')
        $stage = Join-Path $tools ('.setup-' + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $stage | Out-Null
        try {
            $archive = Join-Path $stage 'download.zip'
            Invoke-WebRequest -UseBasicParsing -Uri $package.url -OutFile $archive
            if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $package.sha256) {
                throw ('Download checksum mismatch: ' + $package.name)
            }
            $expanded = Join-Path $stage 'expanded'
            Expand-Archive -LiteralPath $archive -DestinationPath $expanded
            $source = $expanded
            if ($package.archiveDirectory) { $source = Join-Path $expanded $package.archiveDirectory }
            foreach ($file in $package.files.PSObject.Properties) {
                $path = Join-Path $source $file.Name
                if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $file.Value) {
                    throw ('Extracted file checksum mismatch: ' + $package.name)
                }
            }
            if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
            Move-Item -LiteralPath $source -Destination $target
        } finally {
            if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
        }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $root '.env'))) {
        Copy-Item -LiteralPath (Join-Path $root '.env.example') -Destination (Join-Path $root '.env')
    }
    Write-Host 'Portable tools ready.'
    exit 0
} catch {
    Write-Host ('Setup failed: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
