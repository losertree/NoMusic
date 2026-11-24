param(
    [string]$SshHost = 'HostHatch',
    [string]$Site = 'build/update-site',
    [string]$SshConfig
)
$ErrorActionPreference = 'Stop'
$nomusicRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $nomusicRoot
try {
    $nomusicSshOptions = @('-o', 'BatchMode=yes')
    if ($SshConfig) {
        $nomusicSshOptions += @('-F', (Resolve-Path -LiteralPath $SshConfig).Path)
    }
    $nomusicSite = (Resolve-Path -LiteralPath $Site).Path
    $nomusicBundle = Join-Path $nomusicRoot 'build/nomusic-updates.tar.gz'
    & tar.exe -czf $nomusicBundle -C $PSScriptRoot server.py nomusic-updates.service publish.sh
    if ($LASTEXITCODE -ne 0) { throw 'Could not archive the update service.' }
    $nomusicStage = (ssh @nomusicSshOptions $SshHost 'mktemp -d /tmp/nomusic-stage.XXXXXX').Trim()
    if ($LASTEXITCODE -ne 0 -or $nomusicStage -notmatch '^/tmp/nomusic-stage\.[A-Za-z0-9]+$') { throw 'Could not create update staging directory.' }
    & scp @nomusicSshOptions $nomusicBundle "${SshHost}:${nomusicStage}/service.tar.gz"
    if ($LASTEXITCODE -ne 0) { throw 'Could not upload the service archive.' }
    & scp @nomusicSshOptions -r $nomusicSite "${SshHost}:${nomusicStage}/site"
    if ($LASTEXITCODE -ne 0) { throw 'Could not upload the update artifacts.' }
    & ssh @nomusicSshOptions $SshHost "tar -xzf '$nomusicStage/service.tar.gz' -C '$nomusicStage' && sh '$nomusicStage/publish.sh' '$nomusicStage'"
    if ($LASTEXITCODE -ne 0) { throw "Publish failed; staged files remain at $nomusicStage." }
    & ssh @nomusicSshOptions $SshHost "rm -r -- '$nomusicStage'"
    if ($LASTEXITCODE -ne 0) { Write-Warning "Could not remove staging directory $nomusicStage." }
    Write-Output 'Published to https://nomusic.losertree.de/'
} finally {
    Pop-Location
}
