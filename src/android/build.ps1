# 构建 SparkKeep Android APK（不依赖 Gradle：aapt2 + javac + d8 + apksigner）
$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot"
$JDK = "$env:JAVA_HOME\bin"
$SDK = "C:\android-sdk"
$BT = "$SDK\build-tools\34.0.0"
$AJAR = "$SDK\platforms\android-34\android.jar"
$ROOT = $PSScriptRoot
$OUT = "$ROOT\out"
$PKG = "com.spark.keep"

Set-Location -LiteralPath $ROOT
foreach ($d in @("$OUT", "$OUT\gen", "$OUT\obj", "$OUT\dex")) {
    if (Test-Path -LiteralPath $d) { Remove-Item -LiteralPath $d -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

Write-Output "[1/7] aapt2 compile"
& "$BT\aapt2.exe" compile --dir "$ROOT\res" -o "$OUT\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile 失败" }

Write-Output "[2/7] aapt2 link"
# 版本号只有一个来源：make_assets.py 写出的 version.txt（前端关于卡也用同一个值）
$verName = if (Test-Path -LiteralPath "$ROOT\version.txt") { (Get-Content -LiteralPath "$ROOT\version.txt" -Raw).Trim() } else { "1.0.0" }
& "$BT\aapt2.exe" link -o "$OUT\base.apk" -I "$AJAR" --manifest "$ROOT\AndroidManifest.xml" `
    -A "$ROOT\assets" --java "$OUT\gen" --min-sdk-version 24 --target-sdk-version 34 `
    --version-code 28 --version-name "$verName" "$OUT\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败" }

Write-Output "[3/7] javac"
$srcs = @()
$srcs += (Get-ChildItem -LiteralPath "$ROOT\java\com\spark\keep" -Filter *.java | ForEach-Object { $_.FullName })
$srcs += (Get-ChildItem -LiteralPath "$OUT\gen\com\spark\keep" -Filter *.java -Recurse | ForEach-Object { $_.FullName })
& "$JDK\javac.exe" -encoding UTF-8 --release 8 -nowarn -classpath "$AJAR" `
    -d "$OUT\obj" $srcs 2> "$OUT\javac.log"
if ($LASTEXITCODE -ne 0) {
    Get-Content -LiteralPath "$OUT\javac.log" -Encoding utf8 | Select-Object -First 40
    throw "javac 失败"
}

Write-Output "[4/7] d8"
$classes = (Get-ChildItem -LiteralPath "$OUT\obj" -Filter *.class -Recurse | ForEach-Object { $_.FullName })
& "$BT\d8.bat" --lib "$AJAR" --min-api 24 --output "$OUT\dex" $classes 2> "$OUT\d8.log"
if ($LASTEXITCODE -ne 0) {
    Get-Content -LiteralPath "$OUT\d8.log" -Encoding utf8 | Select-Object -First 30
    throw "d8 失败"
}

Write-Output "[5/7] 打包 dex"
Copy-Item -LiteralPath "$OUT\base.apk" -Destination "$OUT\unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$OUT\unsigned.apk", [System.IO.Compression.ZipArchiveMode]::Update)
$entry = $zip.GetEntry("classes.dex")
if ($entry) { $entry.Delete() }
[void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$OUT\dex\classes.dex", "classes.dex", [System.IO.Compression.CompressionLevel]::Optimal)
$zip.Dispose()

Write-Output "[6/7] zipalign"
& "$BT\zipalign.exe" -f 4 "$OUT\unsigned.apk" "$OUT\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败" }

Write-Output "[7/7] 签名"
$KS = "$ROOT\debug.keystore"
if (-not (Test-Path -LiteralPath $KS)) {
    & "$JDK\keytool.exe" -genkeypair -v -keystore "$KS" -alias androiddebugkey `
        -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=SparkKeep, OU=Doubao, O=Local, L=Taiyuan, ST=Shanxi, C=CN" 2> "$OUT\keytool.log"
}
& "$BT\apksigner.bat" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey --min-sdk-version 24 --v1-signing-enabled true `
    --v2-signing-enabled true --out "$ROOT\SparkKeep.apk" "$OUT\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner 失败" }

& "$BT\apksigner.bat" verify "$ROOT\SparkKeep.apk"
$f = Get-Item -LiteralPath "$ROOT\SparkKeep.apk"
Write-Output ("APK OK: " + $f.FullName + "  " + [math]::Round($f.Length / 1024.0, 1) + " KB")
