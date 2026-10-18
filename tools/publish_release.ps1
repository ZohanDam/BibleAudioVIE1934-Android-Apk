param(
    [Parameter(Mandatory=$true)][string]$ApkPath,
    [Parameter(Mandatory=$true)][string]$SourceCommit,
    [int]$MinimumSdk = 26,
    [string]$AndroidSdk = "$env:LOCALAPPDATA/Android/Sdk",
    [string]$Notes = 'Cap nhat ung dung tu GitHub; giu audio offline va cai dat.'
)
$ErrorActionPreference = 'Stop'
$repository = 'ZohanDam/BibleAudioVIE1934-Android-Apk'
$apk = Get-Item -LiteralPath $ApkPath
$metadataPath = Join-Path $apk.DirectoryName 'output-metadata.json'
$metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
if ($metadata.applicationId -ne 'com.zohan.nghekinhthanh1925' -or $metadata.elements.Count -ne 1 -or $metadata.variantName -ne 'release') {
    throw 'Unexpected APK build metadata'
}
if ($metadata.minSdkVersionForDexing -ne $MinimumSdk) { throw 'MinimumSdk does not match the APK build metadata' }
$element = $metadata.elements[0]
if ($element.outputFile -ne $apk.Name) { throw 'APK and build metadata do not match' }
$version = $element.versionName
$versionCode = [long]$element.versionCode
if ($version -notmatch '^\d+\.\d+(\.\d+)?$' -or $SourceCommit -notmatch '^[a-f0-9]{40}$') {
    throw 'Invalid version or source commit'
}
$signer = Get-ChildItem -LiteralPath "$AndroidSdk/build-tools" -Directory |
    Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
    Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (!$signer) { throw 'Android apksigner not found' }
$signing = & $signer verify --print-certs $apk.FullName
if ($LASTEXITCODE -ne 0 -or -not ($signing -match '364b713d0baf12a9c45ed74b5c224b8bc8a10d24582eb61939c380456914c613')) {
    throw 'APK signature does not match installed versions 2.15 and later'
}

# Git Credential Manager keeps the GitHub token out of files, arguments and logs.
$env:GIT_TERMINAL_PROMPT = '0'
$env:GCM_INTERACTIVE = 'never'
$credentialLines = "protocol=https`nhost=github.com`n`n" | git credential fill
if ($LASTEXITCODE -ne 0) { throw 'Log into GitHub with Git Credential Manager first' }
$credential = @{}
foreach ($line in $credentialLines) {
    $pair = $line -split '=', 2
    if ($pair.Length -eq 2) { $credential[$pair[0]] = $pair[1] }
}
if (!$credential.password) { throw 'No GitHub credential available' }
$headers = @{ Authorization="Bearer $($credential.password)"; Accept='application/vnd.github+json'; 'User-Agent'='BibleAppPublisher' }
$api = "https://api.github.com/repos/$repository"
$repo = Invoke-RestMethod -Uri $api -Headers $headers
if (!$repo.permissions.push) { throw 'GitHub login cannot publish to this repository' }
# The supplied source commit must exist remotely before a release is created.
$remoteCommit = Invoke-RestMethod -Uri "$api/commits/$SourceCommit" -Headers $headers
if ($remoteCommit.sha -ne $SourceCommit) { throw 'Source commit is not published' }
$releases = Invoke-RestMethod -Uri "$api/releases?per_page=100" -Headers $headers
if ($releases | Where-Object tag_name -eq "v$version") { throw 'Release already exists; never replace a published APK' }
if (@($releases | Where-Object { !$_.draft -and !$_.prerelease }).Count -gt 0) {
    $latest = Invoke-RestMethod -Uri "$api/releases/latest" -Headers $headers
    $previousManifest = $latest.assets | Where-Object name -eq 'update.json' | Select-Object -First 1
    if ($previousManifest) {
        $manifestUrl = $previousManifest.browser_download_url
        if (!$manifestUrl.StartsWith("https://github.com/$repository/releases/download/")) { throw 'Untrusted previous update manifest' }
        $previous = Invoke-RestMethod -Uri $manifestUrl -Headers @{ 'User-Agent'='BibleAppPublisher' }
        if ($versionCode -le [long]$previous.versionCode) { throw 'versionCode must increase for every release' }
    }
}

$assetName = "NgheKinhThanh1925-$version.apk"
$manifest = [ordered]@{
    packageName=$metadata.applicationId
    versionCode=$versionCode
    versionName=$version
    minimumSdk=$MinimumSdk
    apkUrl="https://github.com/$repository/releases/download/v$version/$assetName"
    sha256=(Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    sizeBytes=$apk.Length
    notes=$Notes
}
$releaseBody = @{
    tag_name="v$version"; target_commitish=$SourceCommit; name="Kinh Thanh $version"
    body=$Notes + "`n`nInstall this APK over version 2.15 or later. Do not uninstall: offline audio and settings are retained.`n`nAfter this installation: Settings (gear) > Check for updates / Kiem tra cap nhat. Android installation confirmation is required."
    draft=$true; prerelease=$false
} | ConvertTo-Json
$release = Invoke-RestMethod -Uri "$api/releases" -Headers $headers -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($releaseBody))
$releaseId = $release.id

Add-Type -AssemblyName System.Net.Http
$client = [Net.Http.HttpClient]::new()
$client.DefaultRequestHeaders.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $credential.password)
$client.DefaultRequestHeaders.UserAgent.ParseAdd('BibleAppPublisher')
$client.Timeout = [TimeSpan]::FromMinutes(5)
try {
    $upload = "https://uploads.github.com/repos/$repository/releases/$releaseId/assets?name=$assetName"
    $stream = $apk.OpenRead()
    $content = [Net.Http.StreamContent]::new($stream)
    $content.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::new('application/vnd.android.package-archive')
    try {
        $response = $client.PostAsync($upload, $content).GetAwaiter().GetResult()
        if (!$response.IsSuccessStatusCode) { throw "APK upload failed: HTTP $([int]$response.StatusCode). Release remains a draft." }
        $uploaded = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
        if ($uploaded.size -ne $apk.Length -or ($uploaded.digest -and $uploaded.digest -ne "sha256:$($manifest.sha256)")) {
            throw 'Uploaded APK size or checksum mismatch; release remains a draft'
        }
    } finally { $content.Dispose(); $stream.Dispose() }
    $json = $manifest | ConvertTo-Json
    $content = [Net.Http.ByteArrayContent]::new([Text.Encoding]::UTF8.GetBytes($json))
    $content.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::new('application/json')
    try {
        $response = $client.PostAsync("https://uploads.github.com/repos/$repository/releases/$releaseId/assets?name=update.json", $content).GetAwaiter().GetResult()
        if (!$response.IsSuccessStatusCode) { throw "Manifest upload failed: HTTP $([int]$response.StatusCode). Release remains a draft." }
    } finally { $content.Dispose() }
    $published = Invoke-RestMethod -Uri "$api/releases/$releaseId" -Headers $headers -Method Patch -ContentType 'application/json' -Body '{"draft":false,"make_latest":"true"}'
    Write-Output "Published $($published.html_url)"
    Write-Output "APK: $($manifest.apkUrl)"
    Write-Output "SHA256: $($manifest.sha256)"
} finally {
    $client.Dispose()
    $credential.Clear()
    $headers.Clear()
}
