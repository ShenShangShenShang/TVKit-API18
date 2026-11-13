<#
TVKit —— 在老电视上编一个 APK（不需要 Android Studio / Gradle）

流程：javac -> d8 -> aapt2 compile -> aapt2 link -> 塞 classes.dex -> zipalign + apksigner

用法：
  .\build.ps1 -SdkRoot D:\sdk -JdkHome D:\jdk17
  .\build.ps1                      # 走 ANDROID_HOME / JAVA_HOME 环境变量

需要：
  - Android SDK，含 build-tools 与 platforms\android-36\android.jar
  - JDK 17
  - 可选 -Libs 传额外的 jar（比如 ExoPlayer 各模块的 classes.jar）

关于中文路径：
  aapt2 / apksigner 在中文路径上会报 "failed to open directory"，所以脚本会把源码复制到
  一个纯 ASCII 的临时目录里编译，编完再把 APK 复制回脚本所在目录。
  临时目录里的 keystore 会被保留复用 —— 删掉它就会重生成一把新密钥，
  覆盖安装时立刻报 INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES。

改代码后记得提 AndroidManifest.xml 里的 versionCode。
#>
param(
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$JdkHome = $env:JAVA_HOME,
    [string[]]$Libs = @(),
    [string]$App = 'app',
    [int]$MinApi = 14,
    [string]$Platform = 'android-36'
)

$ErrorActionPreference = 'Stop'
if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_SDK_ROOT }
if (-not $SdkRoot) { throw '找不到 Android SDK：请设置 ANDROID_HOME，或用 -SdkRoot 传入。' }
if (-not $JdkHome) { throw '找不到 JDK：请设置 JAVA_HOME，或用 -JdkHome 传入。' }

$srcRoot = Join-Path $PSScriptRoot $App
if (-not (Test-Path -LiteralPath (Join-Path $srcRoot 'AndroidManifest.xml'))) {
    throw "找不到源码目录：$srcRoot"
}

function Test-AsciiPath([string]$p) { return [regex]::IsMatch($p, '^[\x20-\x7E]+$') }

$workRoot = $null
foreach ($cand in @((Join-Path $env:TEMP 'tvkit-build'), 'C:\tvkit-build')) {
    if (-not (Test-AsciiPath $cand)) { continue }
    try { New-Item -ItemType Directory -Force -Path $cand | Out-Null; $workRoot = $cand; break } catch { }
}
if (-not $workRoot) { throw '找不到可用的 ASCII 路径作为编译目录（请检查用户名是否含中文）。' }

$work = Join-Path $workRoot 'demo'
Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $work | Out-Null

Copy-Item -LiteralPath (Join-Path $srcRoot 'src') -Destination $work -Recurse -Force
Copy-Item -LiteralPath (Join-Path $srcRoot 'AndroidManifest.xml') -Destination $work -Force
foreach ($d in @('res', 'assets')) {
    $s = Join-Path $srcRoot $d
    if (Test-Path -LiteralPath $s) { Copy-Item -LiteralPath $s -Destination $work -Recurse -Force }
}

$g = $work
$out = Join-Path $g 'out'

# 图标在仓库里是 base64 文本（*.png.b64），这里还原成 png —— 免去二进制进版本库的麻烦
$iconsDir = Join-Path $g 'assets\icons'
if (Test-Path -LiteralPath $iconsDir) {
    foreach ($bf in (Get-ChildItem -LiteralPath $iconsDir -Filter *.png.b64)) {
        $bytes = [Convert]::FromBase64String(([IO.File]::ReadAllText($bf.FullName)).Trim())
        [IO.File]::WriteAllBytes($bf.FullName.Substring(0, $bf.FullName.Length - 4), $bytes)
        Write-Host ("      icon {0} {1} bytes" -f $bf.Name, $bytes.Length)
        Remove-Item -LiteralPath $bf.FullName -Force
    }
}

$btRoot = Join-Path $SdkRoot 'build-tools'
if (-not (Test-Path -LiteralPath $btRoot)) { throw "SDK 里没有 build-tools：$btRoot" }
$bt = Get-ChildItem -LiteralPath $btRoot -Directory |
      Sort-Object { [version](($_.Name -replace '[^0-9\.]', '').Trim('.')) } |
      Select-Object -Last 1 -ExpandProperty FullName

$aj = Join-Path $SdkRoot ("platforms\{0}\android.jar" -f $Platform)
if (-not (Test-Path -LiteralPath $aj)) { throw "找不到 android.jar：$aj" }

$ks = Join-Path $workRoot 'release.keystore'
$ksFixed = Join-Path $PSScriptRoot 'signing.keystore'
if (Test-Path -LiteralPath $ksFixed) { Copy-Item -LiteralPath $ksFixed -Destination $ks -Force }

$env:JAVA_HOME = $JdkHome
$env:PATH = "$JdkHome\bin;$bt;$env:PATH"

if (-not (Test-Path -LiteralPath $ks)) {
    Write-Host '[0/6] 生成自签名 keystore（口令 123456）'
    & "$JdkHome\bin\keytool.exe" -genkeypair -keystore $ks -alias tvkit `
        -storepass 123456 -keypass 123456 -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=TVKit,O=Community,C=CN' 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'keytool 生成 keystore 失败' }
}

New-Item -ItemType Directory -Force -Path (Join-Path $out 'classes'), (Join-Path $out 'dex') | Out-Null

Write-Host '[1/6] javac'
$srcs = Get-ChildItem -LiteralPath (Join-Path $g 'src') -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
if (-not $srcs) { throw "源码目录里没有 .java" }
$cp = $aj
foreach ($l in $Libs) { $cp = $cp + ';' + $l }
& "$JdkHome\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -classpath $cp -d (Join-Path $out 'classes') $srcs 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw 'javac 失败' }

Write-Host '[2/6] d8'
$clsJar = Join-Path $out 'classes.jar'
if (Test-Path -LiteralPath $clsJar) { Remove-Item -LiteralPath $clsJar -Force }
& "$JdkHome\bin\jar.exe" cf $clsJar -C (Join-Path $out 'classes') . 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'jar 打包 classes 失败' }
$d8in = @($clsJar) + @($Libs)
& "$bt\d8.bat" --min-api $MinApi --lib $aj --output (Join-Path $out 'dex') $d8in 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }

Write-Host '[3/6] aapt2 compile'
$res = Join-Path $g 'res'
if (Test-Path -LiteralPath $res) {
    & "$bt\aapt2.exe" compile --dir $res -o (Join-Path $out 'res.zip') 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile 失败' }
}

Write-Host '[4/6] aapt2 link'
$linkArgs = @('-o', (Join-Path $out 'base.apk'), '-I', $aj, '--manifest', (Join-Path $g 'AndroidManifest.xml'))
if (Test-Path -LiteralPath (Join-Path $out 'res.zip')) {
    $linkArgs += @('-R', (Join-Path $out 'res.zip'), '--auto-add-overlay')
}
& "$bt\aapt2.exe" link @linkArgs 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }

Write-Host '[5/6] add dex'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Copy-Item -LiteralPath (Join-Path $out 'base.apk') -Destination (Join-Path $out 'g.apk') -Force
$zip = [System.IO.Compression.ZipFile]::Open((Join-Path $out 'g.apk'), 'Update')
$e = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::NoCompression)
$s = $e.Open()
$b = [IO.File]::ReadAllBytes((Join-Path $out 'dex\classes.dex'))
$s.Write($b, 0, $b.Length)
$s.Close()

$assetsDir = Join-Path $g 'assets'
if (Test-Path -LiteralPath $assetsDir) {
    foreach ($af in (Get-ChildItem -LiteralPath $assetsDir -Recurse -File)) {
        $rel = $af.FullName.Substring($assetsDir.Length).TrimStart('\') -replace '\\', '/'
        $ae = $zip.CreateEntry("assets/$rel", [System.IO.Compression.CompressionLevel]::Optimal)
        $astream = $ae.Open()
        $ab = [IO.File]::ReadAllBytes($af.FullName)
        $astream.Write($ab, 0, $ab.Length)
        $astream.Close()
        Write-Host ("      asset assets/{0} {1} bytes" -f $rel, $ab.Length)
    }
}
$zip.Dispose()

Write-Host '[6/6] zipalign + sign'
$built = Join-Path $work 'tvkit.apk'
& "$bt\zipalign.exe" -f 4 (Join-Path $out 'g.apk') (Join-Path $out 'g_aligned.apk') 2>&1 | Out-Null
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:123456 --key-pass pass:123456 `
    --ks-key-alias tvkit --min-sdk-version $MinApi --out $built (Join-Path $out 'g_aligned.apk') 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'apksigner 签名失败' }

$final = Join-Path $PSScriptRoot 'TVKit-demo.apk'
Copy-Item -LiteralPath $built -Destination $final -Force
$f = Get-Item -LiteralPath $final
Write-Host ("OK -> {0}  {1} bytes" -f $f.Name, $f.Length)
