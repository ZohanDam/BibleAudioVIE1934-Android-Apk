$ErrorActionPreference = 'Stop'
$helper = Join-Path $PSScriptRoot 'sync_latest_release.ps1'
$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$suiteRoot = Join-Path $tempBase ('bible-release-tests-' + [guid]::NewGuid().ToString('N'))
$payload = [byte[]](1..32)
$oldPayload = [byte[]](9, 8, 7)
$hasher = [Security.Cryptography.SHA256]::Create()
try { $hash = ([BitConverter]::ToString($hasher.ComputeHash($payload))).Replace('-', '').ToLowerInvariant() }
finally { $hasher.Dispose() }
$baseUrl = 'https://github.com/ZohanDam/BibleAudioVIE1934-Android-Apk/releases/download/v2.24'
$count = 0

# Network and the final file move are isolated; real filesystem operations use a temporary fixture only.
function Invoke-RestMethod {
    param($Uri, $Headers, $TimeoutSec)
    if ($Uri -like 'https://api.github.com/*/releases/latest') { return $mock.Release }
    return $mock.Manifest
}
function Invoke-WebRequest {
    param($Uri, $Headers, $OutFile, $TimeoutSec)
    $mock.Downloads++
    [IO.File]::WriteAllBytes($OutFile, $mock.Payload)
    if ($mock.NetworkFailure) { throw 'Simulated transport failure' }
}
function Move-Item {
    param($LiteralPath, $Destination)
    if ($mock.MoveFailure -and $LiteralPath.EndsWith('.part')) { throw 'Simulated replacement failure' }
    Microsoft.PowerShell.Management\Move-Item -LiteralPath $LiteralPath -Destination $Destination
}
function Assert([bool]$Condition, [string]$Message) {
    if (!$Condition) { throw $Message }
}
function Run-Case([string]$Name, [scriptblock]$Change, [bool]$Failure = $false,
    [string]$Existing = 'old', [string]$ExpectedVersion = '2.24') {
    $script:count++
    $project = Join-Path $suiteRoot $Name
    $install = Join-Path $project 'install'
    $target = Join-Path $install 'NgheKinhThanh1925.apk'
    New-Item -ItemType Directory -Path $install -Force | Out-Null
    $mock = @{
        Payload=$payload; Downloads=0; NetworkFailure=$false; MoveFailure=$false
        Release=[pscustomobject]@{
            tag_name='v2.24'; draft=$false; prerelease=$false
            assets=@(
                [pscustomobject]@{name='update.json'; browser_download_url="$baseUrl/update.json"; size=600},
                [pscustomobject]@{name='NgheKinhThanh1925-2.24.apk'; browser_download_url="$baseUrl/NgheKinhThanh1925-2.24.apk"; size=$payload.Length}
            )
        }
        Manifest=[pscustomobject]@{
            packageName='com.zohan.nghekinhthanh1925'; versionCode=26; versionName='2.24'; minimumSdk=26
            apkUrl="$baseUrl/NgheKinhThanh1925-2.24.apk"; sha256=$hash; sizeBytes=$payload.Length
        }
    }
    if ($Existing -ne 'none') {
        [IO.File]::WriteAllBytes($target, $(if ($Existing -eq 'current') { $payload } else { $oldPayload }))
    }
    & $Change
    $caught = $false
    $caughtError = ''
    try { & $helper -ProjectDirectory $project -ExpectedVersion $ExpectedVersion | Out-Null }
    catch { $caught = $true; $caughtError = $_.ToString() + "`n" + $_.ScriptStackTrace }
    Assert ($caught -eq $Failure) "$Name : unexpected success/failure: $caughtError"
    $expected = if ($Failure) { $oldPayload } else { $payload }
    Assert (([BitConverter]::ToString([IO.File]::ReadAllBytes($target))) -eq [BitConverter]::ToString($expected)) "$Name : installer not preserved/updated"
    Assert (@(Get-ChildItem -LiteralPath $install -Filter '*.part' -Force).Count -eq 0) "$Name : partial download left behind"
    $backups = @(Get-ChildItem -LiteralPath $project -Recurse -Filter '*.apk' | Where-Object FullName -ne $target)
    $expectedBackups = if (!$Failure -and $Existing -eq 'old') { 1 } else { 0 }
    Assert ($backups.Count -eq $expectedBackups) "$Name : unexpected archive count"
    if ($backups.Count) {
        Assert (([BitConverter]::ToString([IO.File]::ReadAllBytes($backups[0].FullName))) -eq [BitConverter]::ToString($oldPayload)) "$Name : archive contents changed"
    }
    if ($Existing -eq 'current') { Assert ($mock.Downloads -eq 0) "$Name : matching installer downloaded again" }
    Write-Output "PASS: $Name"
}

try {
    Run-Case 'first-download' {} -Existing 'none'
    Run-Case 'already-current' {} -Existing 'current'
    Run-Case 'archive-and-update' {}
    Run-Case 'wrong-checksum' { $mock.Payload = [byte[]](32..1) } -Failure $true
    Run-Case 'wrong-size' { $mock.Payload = [byte[]](1..31) } -Failure $true
    Run-Case 'transport-failure' { $mock.NetworkFailure = $true } -Failure $true
    Run-Case 'replacement-rollback' { $mock.MoveFailure = $true } -Failure $true
    Run-Case 'untrusted-manifest' { $mock.Release.assets[0].browser_download_url = 'https://example.com/update.json' } -Failure $true
    Run-Case 'untrusted-apk' { $mock.Manifest.apkUrl = 'https://example.com/app.apk' } -Failure $true
    Run-Case 'wrong-package' { $mock.Manifest.packageName = 'other.package' } -Failure $true
    Run-Case 'wrong-version' { $mock.Manifest.versionName = '2.23' } -Failure $true
    Run-Case 'prerelease' { $mock.Release.prerelease = $true } -Failure $true
    Run-Case 'missing-manifest' { $mock.Release.assets = @($mock.Release.assets[1]) } -Failure $true
    Run-Case 'asset-size-mismatch' { $mock.Release.assets[1].size = 1 } -Failure $true
    Run-Case 'unexpected-version' {} -Failure $true -ExpectedVersion '2.25'
    Write-Output "$count release-tool tests passed; no network used."
} finally {
    $resolved = [IO.Path]::GetFullPath($suiteRoot)
    if (!$resolved.StartsWith($tempBase.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path $resolved -Leaf) -notlike 'bible-release-tests-*') { throw 'Unsafe test cleanup path' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
