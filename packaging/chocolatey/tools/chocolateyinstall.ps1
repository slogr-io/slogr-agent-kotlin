$ErrorActionPreference = 'Stop'

$version    = '1.0.0'
$installDir = "$env:ProgramFiles\Slogr"
$dataDir    = "$env:ProgramData\Slogr"

$packageArgs = @{
  packageName   = 'slogr-agent'
  url64bit      = "https://github.com/slogr/agent-kotlin/releases/download/v$version/slogr-agent-$version-windows-x64.zip"
  checksum64    = 'PLACEHOLDER_SHA256_WINDOWS_X64'
  checksumType64 = 'sha256'
  unzipLocation = $installDir
}

Install-ChocolateyZipPackage @packageArgs

# Create data directory
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
New-Item -ItemType Directory -Force -Path "$dataDir\logs" | Out-Null

# Write default config if absent
$configFile = "$dataDir\agent.yaml"
if (-not (Test-Path $configFile)) {
  Set-Content -Path $configFile -Value ""
}

# Add install dir to PATH for the current process and system
$currentPath = [Environment]::GetEnvironmentVariable('PATH', 'Machine')
if ($currentPath -notlike "*$installDir*") {
  [Environment]::SetEnvironmentVariable('PATH', "$currentPath;$installDir", 'Machine')
}

# Connect if API key was provided via package parameters
$pp = Get-PackageParameters
if ($pp['ApiKey']) {
  Write-Host "Connecting to Slogr with provided API key..."
  & "$installDir\slogr-agent.exe" connect --api-key $pp['ApiKey']
} else {
  Write-Host "Run ``slogr-agent connect --api-key <key>`` to register this agent."
}

# Register and start Windows service
Write-Host "Registering slogr-agent Windows service..."
& "$installDir\slogr-agent.exe" service install
Start-Service slogr-agent
