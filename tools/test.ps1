param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [ValidateSet('standard', 'cs11')][string]$Flavor = 'standard',
    [string]$Adb = 'adb',
    [switch]$Live,
    [switch]$SavedLyrics,
    [switch]$Features,
    [switch]$Cache,
    [switch]$CachePolicy,
    [switch]$Ui,
    [switch]$WidgetsKaraoke,
    [switch]$Update,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$package = if ($Flavor -eq 'cs11') { 'com.netease.cloudmusic' } else { 'com.nomusic.player' }
$fixture = $null
if ($Live -and $SavedLyrics) { throw 'Choose either -Live or -SavedLyrics.' }

function Quote-AndroidShell([string]$Value) {
    return "'" + $Value.Replace("'", "'\''") + "'"
}

function Invoke-Native([string]$File, [string[]]$Arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $File @Arguments 2>&1 | ForEach-Object { "$_" }
        $nativeExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    if ($nativeExit -ne 0) { throw ('Command failed: ' + $File) }
    return $output
}

Push-Location $projectRoot
try {
    if (!$SkipBuild) {
        $env:DEBUG = ''
        Invoke-Native -File '.\gradlew.bat' -Arguments @((':app:assemble' + (Get-Culture).TextInfo.ToTitleCase($Flavor) + 'Debug'), (':app:assemble' + (Get-Culture).TextInfo.ToTitleCase($Flavor) + 'DebugAndroidTest'), '--console=plain')
    }
    if (!$Live -and !$SavedLyrics -and !$Update) {
        try { $status = Invoke-RestMethod 'http://127.0.0.1:18765/fixture/status' -TimeoutSec 2 }
        catch { $status = $null }
        if ($null -eq $status) {
            $python = (Get-Command python -ErrorAction Stop).Source
            $fixture = Start-Process -FilePath $python -ArgumentList @('-u', ('"' + (Join-Path $PSScriptRoot 'mock_emby.py') + '"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $projectRoot 'build\fixture.log') -RedirectStandardError (Join-Path $projectRoot 'build\fixture-error.log')
            $ready = $false
            for ($attempt = 0; $attempt -lt 20; $attempt++) {
                try { $null = Invoke-RestMethod 'http://127.0.0.1:18765/fixture/status' -TimeoutSec 1; $ready = $true; break }
                catch { Start-Sleep -Milliseconds 150 }
            }
            if (!$ready) { throw 'The local Emby fixture did not start.' }
        }
    }
    $apk = "app\build\outputs\apk\$Flavor\debug\app-$Flavor-debug.apk"
    $testApk = "app\build\outputs\apk\androidTest\$Flavor\debug\app-$Flavor-debug-androidTest.apk"
    Invoke-Native -File $Adb -Arguments @('-s', $Serial, 'install', '-r', $apk)
    Invoke-Native -File $Adb -Arguments @('-s', $Serial, 'install', '-r', $testApk)
    $testClass = if ($SavedLyrics) { 'com.nomusic.app.LiveLyricsTest' } elseif ($Live) { 'com.nomusic.app.LiveServerTest' } elseif ($CachePolicy) { 'com.nomusic.app.AudioCachePolicyTest' } elseif ($Cache) { 'com.nomusic.app.CacheIntegrationTest' } elseif ($Features) { 'com.nomusic.app.FeatureIntegrationTest' } elseif ($WidgetsKaraoke) { 'com.nomusic.app.WidgetKaraokeTest' } elseif ($Ui) { 'com.nomusic.app.UiScenarioTest' } elseif ($Update) { 'com.nomusic.app.UpdateIntegrationTest' } else { 'com.nomusic.app.NoMusicIntegrationTest' }
    $remote = 'am instrument -w -r -e class ' + $testClass
    if ($Live) {
        if (!$env:NOMUSIC_TEST_SERVER -or !$env:NOMUSIC_TEST_USER) { throw 'Set NOMUSIC_TEST_SERVER, NOMUSIC_TEST_USER, and NOMUSIC_TEST_PASSWORD for the live check.' }
        $remote += ' -e server ' + (Quote-AndroidShell $env:NOMUSIC_TEST_SERVER)
        $remote += ' -e username ' + (Quote-AndroidShell $env:NOMUSIC_TEST_USER)
        $remote += ' -e password ' + (Quote-AndroidShell ([string]$env:NOMUSIC_TEST_PASSWORD))
    }
    $remote += ' ' + $package + '.test/com.nomusic.app.NoMusicTestRunner'
    $result = Invoke-Native -File $Adb -Arguments @('-s', $Serial, 'shell', $remote)
    $result | Write-Output
    $log = Join-Path $projectRoot ("build\test-$Flavor-$Serial" + $(if ($SavedLyrics) { '-lyrics-live' } elseif ($Live) { '-live' } elseif ($CachePolicy) {'-cache-policy'} elseif ($Cache) {'-cache'} elseif ($Features) {'-features'} elseif ($WidgetsKaraoke) {'-widgets-karaoke'} elseif ($Ui) {'-ui'} elseif ($Update) {'-update'} else { '' }) + '.txt')
    $result | Set-Content -LiteralPath $log -Encoding utf8
    if (($result -join "`n") -notmatch 'OK \(\d+ tests?\)') { throw ('Instrumentation failed; see ' + $log) }
} finally {
    if ($fixture -and !$fixture.HasExited) { Stop-Process -Id $fixture.Id }
    Pop-Location
}
