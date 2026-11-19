param([string]$SdkRoot, [string]$JavaHome)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
if (!$env:JAVA_HOME -or !(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $candidates = @(
        (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr')
        (Get-ChildItem -Path (Join-Path $env:ProgramFiles 'Java\jdk-*') -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object FullName)
    )
    $env:JAVA_HOME = $candidates | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe')) } | Select-Object -First 1
}
if (!$env:JAVA_HOME) { throw 'Please install JDK 17+ and pass -JavaHome or set JAVA_HOME.' }

if ($SdkRoot) {
    $resolved = (Resolve-Path -LiteralPath $SdkRoot).Path.Replace('\', '/').Replace(':', '\:')
    Set-Content -LiteralPath (Join-Path $projectRoot 'local.properties') -Value ('sdk.dir=' + $resolved) -Encoding ascii
}
$env:DEBUG = ''
Push-Location $projectRoot
try {
    # Windows PowerShell can turn native stderr warnings into terminating errors.
    $ErrorActionPreference = 'Continue'
    & .\gradlew.bat :app:assembleStandardDebug :app:assembleCs11Debug :app:lintStandardDebug :app:lintCs11Debug --console=plain 2>&1 | ForEach-Object { "$_" }
    $nativeExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($nativeExit -ne 0) { throw 'Android build or lint failed.' }
    $output = Join-Path $projectRoot 'artifacts'
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    foreach ($flavor in @('standard', 'cs11')) {
        $apkDir = Join-Path $projectRoot "app\build\outputs\apk\$flavor\debug"
        $meta = Get-Content -Raw -LiteralPath (Join-Path $apkDir 'output-metadata.json') | ConvertFrom-Json
        $entry = $meta.elements[0]
        $version = $entry.versionName -replace '-cs11$', ''
        Copy-Item -LiteralPath (Join-Path $apkDir $entry.outputFile) -Destination (Join-Path $output "NoMusic-$version-$flavor-debug.apk") -Force
    }
    Get-ChildItem -LiteralPath $output -Filter '*.apk' | Get-FileHash -Algorithm SHA256 | ForEach-Object { $_.Hash.ToLowerInvariant() + '  ' + (Split-Path -Leaf $_.Path) } | Set-Content -LiteralPath (Join-Path $output 'SHA256SUMS.txt') -Encoding ascii
    Write-Output ('Test APKs: ' + $output)
} finally { Pop-Location }
