param(
    [string]$ProjectDirectory = (Split-Path $PSScriptRoot -Parent),
    [string]$ExpectedVersion
)
$ErrorActionPreference = 'Stop'
$repository = 'ZohanDam/BibleAudioVIE1934-Android-Apk'
$root = [IO.Path]::GetFullPath($ProjectDirectory)
$install = Join-Path $root 'install'
$archive = Join-Path $root 'archive/installers'
$target = Join-Path $install 'NgheKinhThanh1925.apk'
$temporary = Join-Path $install ('.download-' + [guid]::NewGuid().ToString('N') + '.part')
$headers = @{ 'User-Agent'='BibleAppInstaller'; Accept='application/vnd.github+json' }

function Assert-ReleaseUrl([string]$Url, [string]$Tag) {
    $uri = [uri]$Url
    if (!$uri.IsAbsoluteUri -or $uri.Scheme -ne 'https' -or $uri.Host -ne 'github.com' -or
        $uri.Port -ne 443 -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or
        !$uri.AbsolutePath.StartsWith("/$repository/releases/download/$Tag/") -or
        $uri.AbsolutePath.Contains('..') -or $uri.AbsolutePath.Contains('%')) {
        throw 'Untrusted release URL'
    }
}

$release = Invoke-RestMethod -Uri "https://api.github.com/repos/$repository/releases/latest" -Headers $headers -TimeoutSec 30
if ($release.draft -or $release.prerelease -or $release.tag_name -notmatch '^v\d+\.\d+(\.\d+)?$') {
    throw 'Latest release is not a stable app release'
}
$manifestAsset = @($release.assets | Where-Object name -eq 'update.json')
if ($manifestAsset.Count -ne 1) { throw 'Release must contain one update manifest' }
Assert-ReleaseUrl $manifestAsset[0].browser_download_url $release.tag_name
$manifest = Invoke-RestMethod -Uri $manifestAsset[0].browser_download_url -Headers $headers -TimeoutSec 30
if ($manifest.packageName -ne 'com.zohan.nghekinhthanh1925' -or
    $manifest.versionCode -le 0 -or $manifest.minimumSdk -lt 26 -or
    "v$($manifest.versionName)" -ne $release.tag_name -or
    $manifest.sha256 -notmatch '^[a-fA-F0-9]{64}$' -or
    $manifest.sizeBytes -lt 1 -or $manifest.sizeBytes -gt 209715200 -or
    ($ExpectedVersion -and $manifest.versionName -ne $ExpectedVersion)) {
    throw 'Invalid or unexpected app update manifest'
}
Assert-ReleaseUrl $manifest.apkUrl $release.tag_name
$apk = @($release.assets | Where-Object {
    $_.browser_download_url -eq $manifest.apkUrl -and $_.name.EndsWith('.apk') -and $_.size -eq $manifest.sizeBytes
})
if ($apk.Count -ne 1) { throw 'APK does not match the published release' }

function Test-CurrentApk([string]$Path) {
    return (Test-Path -LiteralPath $Path -PathType Leaf) -and
        (Get-Item -LiteralPath $Path).Length -eq $manifest.sizeBytes -and
        (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -eq $manifest.sha256
}

if (Test-CurrentApk $target) {
    Write-Output "Local installer already matches $($release.tag_name): $target"
    return
}
New-Item -ItemType Directory -Path $install -Force | Out-Null
$backup = $null
try {
    Invoke-WebRequest -Uri $manifest.apkUrl -Headers @{ 'User-Agent'='BibleAppInstaller' } -OutFile $temporary -TimeoutSec 300
    if (!(Test-CurrentApk $temporary)) { throw 'Downloaded APK size or SHA-256 mismatch; existing installer preserved' }
    # Only fixed, project-contained files are moved; nothing is recursively removed.
    if (Test-Path -LiteralPath $target) {
        New-Item -ItemType Directory -Path $archive -Force | Out-Null
        $oldHash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
        $backup = Join-Path $archive ("{0}-{1}.apk" -f (Get-Date -Format 'yyyyMMdd-HHmmssfff'), $oldHash.Substring(0, 12))
        Move-Item -LiteralPath $target -Destination $backup
    }
    Move-Item -LiteralPath $temporary -Destination $target
    Write-Output "Local installer updated to $($release.tag_name): $target"
} catch {
    if ($backup -and (Test-Path -LiteralPath $backup) -and !(Test-Path -LiteralPath $target)) {
        Move-Item -LiteralPath $backup -Destination $target
    }
    throw
} finally {
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
}
