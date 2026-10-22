$ErrorActionPreference = "Stop"
# Offline APK build for aicy-unlock, bypassing Gradle entirely.
# The cached Gradle 9.3.1 distribution is incomplete (no bin/, no module
# registry) and the wrapper cannot download a replacement, so the toolchain is
# driven directly: javac -> d8 -> aapt2 -> zipalign -> apksigner.

$root    = "H:\Flymetool\aicy-unlock"
$app     = Join-Path $root "app"
$sdk     = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$jdkBin  = "C:\Program Files\Android\Android Studio\jbr\bin"
$bt      = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
$androidJar = "$sdk\platforms\android-35\android.jar"
if (-not (Test-Path $androidJar)) {
    $p = Get-ChildItem "$sdk\platforms" -Directory | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    $androidJar = Join-Path $p.FullName "android.jar"
}
if (-not (Test-Path $androidJar)) { throw "android.jar not found" }

$work = Join-Path $env:TEMP "aicy-build"
if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$work\classes", "$work\gen", "$work\apk" | Out-Null

Write-Host "[1/6] unpack aar" -ForegroundColor Cyan
$aar = Join-Path $app "libs\api.aar"
if (Test-Path $aar) {
    New-Item -ItemType Directory -Force -Path "$work\aar" | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($aar, "$work\aar")
    if (Test-Path "$work\aar\classes.jar") { Copy-Item "$work\aar\classes.jar" "$work\api-classes.jar" -Force }
}

$cp = @($androidJar)
foreach ($j in @("api-classes.jar", "libs\service.jar", "libs\interface.jar")) {
    $p = if ($j -eq "api-classes.jar") { Join-Path $work $j } else { Join-Path $app $j }
    if (Test-Path $p) { $cp += $p }
}

Write-Host "[2/6] javac" -ForegroundColor Cyan
$sources = Get-ChildItem "$app\src\main\java" -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
Write-Host "      $($sources.Count) source files"
$argFile = Join-Path $work "javac.txt"
$utf8 = New-Object System.Text.UTF8Encoding($false)
# Argfile entries are whitespace-separated, so paths with spaces must not be
# quoted with characters javac would carry into the token itself.
$lines = @("-d", "$work\classes", "-encoding", "UTF-8", "-source", "17", "-target", "17",
           "-classpath", ($cp -join ';'), "-nowarn")
$lines += ($sources)
[System.IO.File]::WriteAllLines($argFile, $lines, $utf8)
# javac writes its notes to stderr, which PowerShell turns into an error record
# under Stop; keep the pipeline tolerant and rely on $LASTEXITCODE instead.
$ErrorActionPreference = "Continue"
& "$jdkBin\javac.exe" "@$argFile" 2>&1 | ForEach-Object { Write-Host $_ }
$ErrorActionPreference = "Stop"
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

Write-Host "[3/6] aapt2 compile+link" -ForegroundColor Cyan
& "$($bt.FullName)\aapt2.exe" compile --dir "$app\src\main\res" -o "$work\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

# aapt2 does not expand manifest placeholders -- AGP's manifest merger normally
# does. Left literal, both providers register under the authority
# "${applicationId}.cfg", so every hook-side content://dev.loong.aicyunlock.cfg
# query fails with "Could not find provider" and the timbre catalogue plus the
# custom-model settings silently disappear.
$rawManifest = "$app\src\main\AndroidManifest.xml"
$ph = @([regex]::Matches((Get-Content $rawManifest -Raw), '\$\{[^}]+\}') | ForEach-Object { $_.Value } | Sort-Object -Unique)
if ($ph.Count -ne 1 -or $ph[0] -ne '${applicationId}') { throw "unsupported manifest placeholders: $($ph -join ', ')" }
$appId = (Select-String -Path "$app\build.gradle" -Pattern "applicationId\s+'([^']+)'" | Select-Object -First 1).Matches.Groups[1].Value
if (-not $appId) { throw "applicationId not found in app/build.gradle" }
$manifest = "$work\AndroidManifest.xml"
[System.IO.File]::WriteAllText($manifest,
    (Get-Content $rawManifest -Raw).Replace('${applicationId}', $appId), $utf8)
Write-Host "      manifest placeholders: $appId"

# No -A here: aapt2 on Windows writes nested asset entries with backslash
# separators ("assets\wake_cues\round1.pcm") that AssetManager cannot open;
# assets/ and the java resources are appended with aapt add below.
& "$($bt.FullName)\aapt2.exe" link -o "$work\base.apk" -I $androidJar --manifest $manifest `
    "$work\res.zip" --java "$work\gen" --min-sdk-version 26 --target-sdk-version 35 `
    --version-code 76 --version-name 2.8.12 --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

# Generated R.java has to be compiled too, then everything goes to d8.
$genSources = Get-ChildItem "$work\gen" -Recurse -Filter *.java -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
if ($genSources) {
    $argFile2 = Join-Path $work "javac2.txt"
    $l2 = @("-d", "$work\classes", "-encoding", "UTF-8", "-source", "17", "-target", "17",
            "-classpath", ($cp -join ';'), "-nowarn")
    $l2 += ($genSources)
    [System.IO.File]::WriteAllLines($argFile2, $l2, $utf8)
    $ErrorActionPreference = "Continue"
    & "$jdkBin\javac.exe" "@$argFile2" 2>&1 | ForEach-Object { Write-Host $_ }
    $ErrorActionPreference = "Stop"
    if ($LASTEXITCODE -ne 0) { throw "javac (R.java) failed" }
}

Write-Host "[4/6] d8" -ForegroundColor Cyan
# app/build.gradle declares libs/service.jar and libs/interface.jar as
# `implementation`, so their classes belong in the APK dex; api.aar is
# `compileOnly` (supplied by the framework) and stays out.
$classDirs = @("$work\classes")
$classList = Join-Path $work "classes.txt"
$all = @(Get-ChildItem "$work\classes" -Recurse -Filter *.class | Select-Object -ExpandProperty FullName)
foreach ($j in @("service.jar", "interface.jar")) {
    $p = Join-Path $app "libs\$j"
    if (Test-Path $p) { $all += $p } else { throw "missing implementation jar: $p" }
}
[System.IO.File]::WriteAllLines($classList, $all, $utf8)
$ErrorActionPreference = "Continue"
& "$($bt.FullName)\d8.bat" "--lib" $androidJar "--min-api" "26" "--output" "$work\apk" "@$classList" 2>&1 | ForEach-Object { Write-Host $_ }
$ErrorActionPreference = "Stop"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[5/6] package" -ForegroundColor Cyan
$dex = Join-Path "$work\apk" "classes.dex"
if (-not (Test-Path $dex)) { throw "classes.dex not produced" }

# Target 30+ refuses to install when resources.arsc is compressed, so the aapt2
# output is kept as-is (it already stores resources.arsc) and everything else is
# appended. Rebuilding the archive in .NET would deflate it again: the framework's
# ZipArchive ignores CompressionLevel.NoCompression.
$outApk = "$work\unsigned.apk"
if (Test-Path $outApk) { Remove-Item $outApk -Force }
Copy-Item "$work\base.apk" $outApk -Force
$added = 0
$ErrorActionPreference = "Continue"
Push-Location "$work\apk"
& "$($bt.FullName)\aapt.exe" add $outApk "classes.dex"
if ($LASTEXITCODE -ne 0) { $added = $LASTEXITCODE }
Pop-Location

# assets/ holds the wake-cue pcm files; resources/ carries META-INF/xposed
# (module.prop, java_init.list, scope.list) -- without the latter LSPosed does
# not see the module at all. Gradle maps both directories to the APK root, so
# aapt add has to run from each one's own parent, not from src/main (that would
# ship them as "resources/META-INF/...").
$payloads = @(
    @{ stage = "$app\src\main";           filter = "assets/*" },
    @{ stage = "$app\src\main\resources"; filter = "*" }
)
foreach ($p in $payloads) {
    if (-not (Test-Path $p.stage)) { continue }
    $rel = (Get-ChildItem $p.stage -Recurse -File | ForEach-Object {
        $_.FullName.Substring($p.stage.Length + 1).Replace('\', '/')
    }) | Where-Object { $_ -like $p.filter }
    if (-not $rel) { continue }
    Push-Location $p.stage
    & "$($bt.FullName)\aapt.exe" add $outApk $rel
    if ($LASTEXITCODE -ne 0) { $added = $LASTEXITCODE }
    Pop-Location
}
$ErrorActionPreference = "Stop"
if ($added -ne 0) { throw "aapt add failed ($added)" }

Write-Host "[6/6] sign" -ForegroundColor Cyan
$aligned = "$work\aligned.apk"
& "$($bt.FullName)\zipalign.exe" -f -p 4 $outApk $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$ks = "$env:USERPROFILE\.android\debug.keystore"
$final = "$root\dist\aicy-unlock-2.8.12-debug.apk"
New-Item -ItemType Directory -Force -Path "$root\dist" | Out-Null
& "$($bt.FullName)\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey --out $final $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

$ErrorActionPreference = "Continue"
& "$($bt.FullName)\apksigner.bat" verify --print-certs $final | Select-Object -First 3
$ErrorActionPreference = "Stop"

# Every previous offline build shipped an APK that a single stage silently
# truncated, so verify the archive content itself rather than trusting the
# pipeline exit codes.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($final)
$names = @($zip.Entries | ForEach-Object { $_.FullName })
$dexEntry = $zip.GetEntry("classes.dex")
$dexMs = New-Object System.IO.MemoryStream
$ds = $dexEntry.Open(); $ds.CopyTo($dexMs); $ds.Close()
$zip.Dispose()
$dexText = [System.Text.Encoding]::ASCII.GetString($dexMs.ToArray())
foreach ($need in @("resources.arsc", "assets/wake_cues/round1.pcm",
                    "META-INF/xposed/module.prop", "META-INF/xposed/java_init.list",
                    "META-INF/xposed/scope.list")) {
    if ($names -notcontains $need) { throw "APK is missing $need" }
}
# The manifest registers this provider, so the process dies at bind time when
# the libxposed jars are not dexed in (the implementation dependencies).
if ($dexText -notmatch "Lio/github/libxposed/service/XposedProvider;") {
    throw "classes.dex does not contain io.github.libxposed.service.XposedProvider"
}
if ($names | Where-Object { $_ -like "*\*" }) { throw "APK entry names contain backslashes" }
$ErrorActionPreference = "Continue"
$manifestDump = (& "$($bt.FullName)\aapt.exe" dump xmltree $final AndroidManifest.xml 2>&1) -join "`n"
$ErrorActionPreference = "Stop"
if ($manifestDump -match '\$\{') { throw "APK manifest still contains an unexpanded placeholder" }
if ($manifestDump -notmatch "A: android:authorities\(0x01010018\)=""$([regex]::Escape($appId)).cfg""") {
    throw "APK manifest has no $appId.cfg provider authority"
}
Write-Host "checked: $($names.Count) entries, authorities use $appId" -ForegroundColor DarkGray
Write-Host ""
Write-Host "OK -> $final" -ForegroundColor Green
Get-Item $final | Select-Object Length, LastWriteTime