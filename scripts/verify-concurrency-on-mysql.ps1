# ===================================================================
# 在真实 MySQL 上验证号源防超卖（验收 A-03 的第二处证据）
#
# 用法（在项目根目录）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify-concurrency-on-mysql.ps1
#
# 需要：
#   - JDK 21（默认 D:\java\jdk-21）
#   - 本机 MySQL 在跑，账号有建库权限
#   - 环境变量 DB_PASSWORD（或下面 -DbPassword 参数）
#
# ⚠️ 它用**独立的库** hospital_appointment_conc，不碰开发库 hospital_appointment。
#    跑完会把自己的测试行删干净，库本身保留以便重复运行。
#
# 为什么要有这个脚本：
#   测试套件里的 ScheduleConcurrencyTest 跑在 H2 上（好处是任何人 mvn test 都能跑），
#   但本项目的防超卖依赖 **InnoDB 的行锁**，而 H2 的锁实现与它不同。
#   H2 的绿灯只能证明"这条 SQL 语法对、逻辑对"，不能证明"在 InnoDB 上原子"。
#   所以并发正确性必须在真实 MySQL 上再验一次 —— 两处都过，才敢说防超卖成立。
# ===================================================================
param(
    [string]$JavaHome = "D:\java\jdk-21",
    [string]$DbHost = "127.0.0.1",
    [string]$DbPort = "3306",
    [string]$DbUser = "root",
    [string]$DbPassword = $env:DB_PASSWORD,
    [switch]$SkipCompile
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot\..

# 控制台按 UTF-8 读子进程输出，否则 Java 打的中文会显示成乱码。
# （乱码只是显示问题、不影响判定，但"看不懂的输出"等于没有输出。）
try {
    [Console]::OutputEncoding = [Text.Encoding]::UTF8
    $OutputEncoding = [Text.Encoding]::UTF8
} catch {
    Write-Host "[i] 无法设置控制台编码，中文可能显示为乱码（不影响结果判定）" -ForegroundColor Yellow
}

function Write-Step($text) {
    Write-Host ""
    Write-Host "=== $text ===" -ForegroundColor Cyan
}

# -------------------------------------------------------------------
Write-Step "0. 环境检查"

$javac = Join-Path $JavaHome "bin\javac.exe"
$java  = Join-Path $JavaHome "bin\java.exe"
foreach ($exe in @($javac, $java)) {
    if (-not (Test-Path $exe)) {
        Write-Host "[X] 找不到 $exe" -ForegroundColor Red
        exit 1
    }
}
Write-Host "[OK] JDK: $JavaHome"

if (-not $DbPassword) {
    Write-Host "[X] 未提供数据库口令。请设环境变量 DB_PASSWORD 或加 -DbPassword 参数。" -ForegroundColor Red
    exit 1
}

# 找 mysql-connector jar
$jar = Get-ChildItem "$env:USERPROFILE\.m2\repository\com\mysql\mysql-connector-j" -Recurse -Filter "*.jar" `
        -ErrorAction SilentlyContinue |
       Where-Object { $_.Name -notmatch 'sources|javadoc' } |
       Sort-Object Name -Descending | Select-Object -First 1

if (-not $jar) {
    Write-Host "[X] 未在本地 Maven 仓库找到 mysql-connector-j。" -ForegroundColor Red
    Write-Host "    先执行一次 mvn test（它会下载依赖），然后重跑本脚本。" -ForegroundColor Yellow
    exit 1
}
Write-Host "[OK] JDBC 驱动: $($jar.Name)"

# -------------------------------------------------------------------
$outDir = "target\concurrency-verifier"
$srcFile = "src\test\java\com\demo\hospital\schedule\MySqlConcurrencyVerifier.java"

if (-not $SkipCompile) {
    Write-Step "1. 编译验证程序"
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    & $javac -encoding UTF-8 -d $outDir -cp $jar.FullName $srcFile
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[X] 编译失败" -ForegroundColor Red
        exit 1
    }
    Write-Host "[OK] 编译完成 -> $outDir"
}

# -------------------------------------------------------------------
Write-Step "2. 在真实 MySQL 上跑并发验证"

$env:DB_HOST = $DbHost
$env:DB_PORT = $DbPort
$env:DB_USERNAME = $DbUser
$env:DB_PASSWORD = $DbPassword

# ⚠️ 编码这件事在这台机器上踩了两次，所以这里写清楚：
#   1) 不能用 `& $java -Dfile.encoding=UTF-8 ...`：该参数含 "="，
#      PowerShell 的参数解析会把它拆坏（曾报 ClassNotFoundException: /encoding=UTF-8）。
#   2) 只设 [Console]::OutputEncoding 也不够：Windows 控制台的活动代码页仍是 936(GBK)，
#      于是 Java 按 UTF-8 输出、控制台按 GBK 解码 —— 中文全成乱码。
#   解法：用 JAVA_TOOL_OPTIONS 显式告诉 JVM 用 UTF-8 写标准输出（避免 "=" 被拆），
#         同时把控制台代码页切到 65001。
$env:JAVA_TOOL_OPTIONS = "-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
try { & chcp.com 65001 | Out-Null } catch { }

$javaArgs = @(
    "-cp", "$outDir;$($jar.FullName)",
    "com.demo.hospital.schedule.MySqlConcurrencyVerifier"
)
& $java $javaArgs
$env:JAVA_TOOL_OPTIONS = $null
$code = $LASTEXITCODE

# -------------------------------------------------------------------
Write-Step "结果"
if ($code -eq 0) {
    Write-Host "[OK] A-03 在真实 MySQL 上通过" -ForegroundColor Green
    Write-Host ""
    Write-Host "这条证据与 H2 上的 ScheduleConcurrencyTest 一起，构成'防超卖成立'的完整依据：" -ForegroundColor Gray
    Write-Host "  - H2：任何人 mvn test 都能复现（回归防线）" -ForegroundColor Gray
    Write-Host "  - MySQL：证明这条 SQL 在 InnoDB 上真的原子（真实性依据）" -ForegroundColor Gray
} else {
    Write-Host "[X] A-03 在真实 MySQL 上失败（退出码 $code）" -ForegroundColor Red
    exit $code
}
