$ErrorActionPreference = 'Stop'

$installDir = "$env:ProgramFiles\Slogr"

# Stop and remove the Windows service
if (Get-Service slogr-agent -ErrorAction SilentlyContinue) {
  Stop-Service slogr-agent -Force
  & "$installDir\slogr-agent.exe" service remove
}

# Remove from PATH
$currentPath = [Environment]::GetEnvironmentVariable('PATH', 'Machine')
$newPath = ($currentPath.Split(';') | Where-Object { $_ -ne $installDir }) -join ';'
[Environment]::SetEnvironmentVariable('PATH', $newPath, 'Machine')

# Remove install directory (data directory is preserved for clean uninstall)
Remove-Item -Recurse -Force $installDir -ErrorAction SilentlyContinue
