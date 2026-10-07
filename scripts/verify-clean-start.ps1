# ===================================================================
# 干净机器复现验证（N-04 / A-09）
#
# 用法（在项目根目录，普通权限即可）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify-clean-start.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify-clean-start.ps1 -KeepData
#
# 它回答的问题是：
#   **一个刚 clone 下来的人，照着 README 做，能不能真的跑起来？**
#
# ⚠️ 为什么必须有一个脚本，而不是"我手工试过了"：
#   "干净机器能复现"这句话如果由人来断言，它**不可复现**——
#   换个时间、换个人，没人知道当时到底删了什么、跳过了哪一步。
#   而且人做这件事时会**无意识地绕过障碍**（"这步我记得要改一下配置"），
#   恰好把最该发现的问题遮住了。脚本不会绕过任何东西。
#
# 它按 README 的顺序真的走一遍：
#   0. 前置检查（JDK / Maven / Node / MySQL，缺了就明确说清楚）
#   1. 破坏状态：删库 + 删前端产物  ← 这是"干净"的关键
#   2. 起后端，确认它**自己建库建表并预置演示数据**
#   3. 跑全量 mvn test（验证"没有 MySQL/Redis/RabbitMQ 也能全绿"这一半）
#   4. 构建前端
#   5. 经 Nginx 端到端验证（A-09 + A-10）
#   6. 收尾：停服务、清测试数据
#
# ⚠️ 本文件必须带 UTF-8 BOM（见 push.ps1 的 BOM 守卫）。
# ===================================================================
param(
    [string]$JavaHome = "D:\java\jdk-21",
    [string]$DbUser = "root",
    [string]$DbPassword = "123456",
    [int]$NginxPort = 8080,
    # 跳过测试订单清理（脚本自己造的那些），用于事后手工查看数据
    [switch]$KeepData,
    # 跳过耗时步骤（调试用）
    [switch]$SkipTests,
    [switch]$SkipFrontend
)

$ErrorActionPreference = "Continue"
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location $projectRoot

[Console]::OutputEncoding = [Text.Encoding]::UTF8
$env:JAVA_HOME = $JavaHome
$env:DB_PASSWORD = $DbPassword

$steps = [ordered]@{}
$failed = 0

function Section($t) {
    Write-Host ""
    Write-Host ("=" * 64) -ForegroundColor Cyan
    Write-Host "  $t" -ForegroundColor Cyan
    Write-Host ("=" * 64) -ForegroundColor Cyan
}
function Ok($m)   { Write-Host "  [OK] $m" -ForegroundColor Green }
function Bad($m)  { Write-Host "  [X] $m" -ForegroundColor Red; $script:failed++ }
function Info($m) { Write-Host "  [i] $m" -ForegroundColor Gray }

function Test-Port($p) {
    return [bool](Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue)
}
function Stop-Backend {
    Get-Process java -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -like "*jdk-21*" } | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
}

Write-Host ""
Write-Host "干净机器复现验证 —— 目标是回答'刚 clone 下来的人能不能真的跑起来'" -ForegroundColor White
Write-Host "时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Gray

# -------------------------------------------------------------------
Section "0. 前置检查（照 README 第三节）"

$missing = @()
if (Test-Path (Join-Path $JavaHome "bin\java.exe")) {
    $v = & (Join-Path $JavaHome "bin\java.exe") -version 2>&1 | Select-Object -First 1
    Ok "JDK: $JavaHome（$v）"
} else {
    Bad "找不到 JDK: $JavaHome（README 第三节要求 JDK 21）"
    $missing += "JDK"
}

$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if ($mvn) { Ok "Maven: $($mvn.Source)" } else { Bad "PATH 上没有 mvn"; $missing += "Maven" }

$node = Get-Command node -ErrorAction SilentlyContinue
if ($node) { Ok "Node: $(node -v 2>&1)" } else { Bad "PATH 上没有 node"; $missing += "Node" }

$mysql = Get-Command mysql -ErrorAction SilentlyContinue
if ($mysql) { Ok "mysql 客户端可用" } else { Info "找不到 mysql 客户端（第 2 步的验证会跳过，不影响启动）" }

if (-not (Test-Port 3306)) {
    Bad "MySQL 3306 未监听 —— README 第一步就要求它在跑"
    $missing += "MySQL"
} else {
    Ok "MySQL 3306 在监听"
}

# Redis / RabbitMQ 都是**可选**，这里只提示
if (Test-Port 6379) { Info "Redis 6379 在监听（号源查询缓存生效）" } else { Info "Redis 未监听 —— 不影响本项目" }
if (Test-Port 5672) { Info "RabbitMQ 5672 在监听（异步通知会真正投递）" } else { Info "RabbitMQ 未监听 —— 应用会用 NoopNotifier，挂号仍正常（A-07）" }

if ($missing.Count -gt 0) {
    Write-Host ""
    Bad "缺少必需项：$($missing -join ', ') —— 按要求装好再跑本脚本"
    exit 1
}
$steps["0 前置检查"] = "通过"

# -------------------------------------------------------------------
Section "1. 破坏状态（这一步是'干净'的关键）"

Write-Host "  删除数据库 hospital_appointment（模拟新机器上它还不存在）..."
$env:MYSQL_PWD = $DbPassword
& mysql -u $DbUser -e "DROP DATABASE IF EXISTS hospital_appointment;" 2>&1 | Out-Null
$dbGone = ((& mysql -u $DbUser -N -B -e "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='hospital_appointment';" 2>&1) -join '').Trim()
if ($dbGone -eq '0') { Ok "数据库已删除" } else { Bad "数据库删除失败（返回 $dbGone）" }

Write-Host "  删除前端构建产物 frontend\dist（模拟还没构建过）..."
if (Test-Path "frontend\dist") {
    Remove-Item "frontend\dist" -Recurse -Force -ErrorAction SilentlyContinue
}
if (-not (Test-Path "frontend\dist")) { Ok "前端产物已删除" } else { Bad "前端产物删除失败" }

$steps["1 破坏状态"] = "完成"

# -------------------------------------------------------------------
Section "2. 起后端 —— 它必须自己把库建出来（A-09 的关键）"

Stop-Backend
if (Test-Port 8081) { Bad "8081 仍被占用，无法继续"; exit 1 }

$env:REDIS_PASSWORD = "123456"
$env:JWT_SECRET = "dev-only-secret-must-be-at-least-32-bytes-long"
# 刻意按 README 的做法：broker 在不在跑都不影响启动（A-07）
$env:MQ_ENABLED = if (Test-Port 5672) { "true" } else { "false" }
Info "MQ_ENABLED=$env:MQ_ENABLED"

$log = Join-Path $env:TEMP "hospital-clean-start.log"
Start-Process -FilePath "cmd.exe" `
    -ArgumentList '/c', "mvn -B spring-boot:run `"-Dspring-boot.run.profiles=dev`" > `"$log`" 2>&1" `
    -WindowStyle Hidden -PassThru | Out-Null

$up = $false
$sw = [Diagnostics.Stopwatch]::StartNew()
for ($i = 0; $i -lt 80; $i++) {
    Start-Sleep -Seconds 3
    try {
        $null = Invoke-WebRequest "http://127.0.0.1:8081/api/health" -UseBasicParsing -TimeoutSec 3
        $up = $true; break
    } catch { }
}
$sw.Stop()

if (-not $up) {
    Bad "后端启动失败（等了 $([math]::Round($sw.Elapsed.TotalSeconds)) 秒）"
    Write-Host "--- 日志尾部 ---" -ForegroundColor Yellow
    Get-Content $log -Tail 25 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "    $_" }
    exit 1
}
Ok "后端启动成功（$([math]::Round($sw.Elapsed.TotalSeconds)) 秒，第一次启动还要建库建表）"

# 库是否被自动创建 + 表与预置数据是否齐全
$dbExists = ((& mysql -u $DbUser -N -B -e "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='hospital_appointment';" 2>&1) -join '').Trim()
if ($dbExists -eq '1') { Ok "数据库被自动创建（createDatabaseIfNotExist）" } else { Bad "数据库没有被自动创建" }

$counts = & mysql -u $DbUser -N -B -e @"
SELECT 'department', COUNT(*) FROM hospital_appointment.department
UNION ALL SELECT 'doctor', COUNT(*) FROM hospital_appointment.doctor
UNION ALL SELECT 'schedule', COUNT(*) FROM hospital_appointment.schedule
UNION ALL SELECT 'sys_user', COUNT(*) FROM hospital_appointment.sys_user;
"@ 2>&1
$tbl = @{}
foreach ($line in $counts) {
    $parts = ($line -split "`t")
    if ($parts.Count -eq 2) { $tbl[$parts[0].Trim()] = [int]$parts[1].Trim() }
}
$expect = @{ department = 5; doctor = 10; schedule = 140; sys_user = 1 }
foreach ($k in $expect.Keys) {
    if ($tbl[$k] -eq $expect[$k]) {
        Ok "预置数据 $k = $($tbl[$k])"
    } else {
        $actual = if ($tbl.ContainsKey($k)) { $tbl[$k] } else { 0 }
        Bad "预置数据 $k 期望 $($expect[$k])，实际 $actual"
    }
}

# 演示账号能不能登录（这是"能跑"的最小证据）
$tmp = Join-Path $env:TEMP "hospital-clean"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$loginFile = Join-Path $tmp "login.json"
[IO.File]::WriteAllText($loginFile, '{"phone":"13800000001","password":"Demo@2026"}', [Text.UTF8Encoding]::new($false))
$login = & curl.exe -s "http://127.0.0.1:8081/api/auth/login" -X POST -H "Content-Type: application/json" --data-binary "@$loginFile" | ConvertFrom-Json
if ($login.token) { Ok "演示账号可登录（13800000001 / Demo@2026）" } else { Bad "演示账号登录失败" }

# 启动日志里不该有 ERROR（WARN 允许）
$errs = Get-Content $log -ErrorAction SilentlyContinue | Select-String -Pattern 'ERROR' | Select-Object -First 3
if ($errs) {
    Bad "启动日志里有 ERROR（见下）"
    $errs | ForEach-Object { Write-Host "      $($_.Line.Trim())" -ForegroundColor DarkYellow }
} else {
    Ok "启动日志无 ERROR"
}

$steps["2 自动建库与启动"] = "通过"

# -------------------------------------------------------------------
Section "3. 全量 mvn test —— 验证'不依赖本机服务也能全绿'"

if ($SkipTests) {
    Info "已跳过（-SkipTests）"
    $steps["3 全量测试"] = "跳过"
} else {
    # ⚠️ 关键：把后端停掉再跑测试。
    #    否则测试与那个 dev 应用会争用同一个 broker，
    #    结果可能通过但不是干净复现的结果。
    Stop-Backend
    Ok "已停掉 dev 后端（避免与测试争用 broker）"

    $testLog = Join-Path $env:TEMP "hospital-clean-test.log"
    & mvn -B test *> $testLog
    $summary = Get-Content $testLog -ErrorAction SilentlyContinue |
        Select-String -Pattern 'Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+$' |
        Select-Object -Last 1

    if ($summary -and $summary.Line -notmatch 'Failures: [1-9]' -and $summary.Line -notmatch 'Errors: [1-9]') {
        $n = [regex]::Match($summary.Line, 'Tests run: (\d+)').Groups[1].Value
        Ok "全量测试通过：$($summary.Line.Trim())"
    } else {
        Bad "测试未全绿（见 $testLog）"
        Get-Content $testLog | Select-String -Pattern 'ERROR\].*Tests run|FAILURE' | Select-Object -First 6 |
            ForEach-Object { Write-Host "      $($_.Line.Trim())" -ForegroundColor DarkYellow }
    }
    $steps["3 全量测试"] = if ($summary) { "通过" } else { "失败" }
}

# -------------------------------------------------------------------
Section "4. 构建前端"

if ($SkipFrontend) {
    Info "已跳过（-SkipFrontend）"
    $steps["4 前端构建"] = "跳过"
} else {
    if (-not (Test-Path "frontend\node_modules")) {
        Info "frontend\node_modules 不存在，先 npm.cmd install（第一次会慢）"
        Push-Location frontend
        # ⚠️ 必须用 npm.cmd：npm.ps1 会被执行策略拦住
        & npm.cmd install *> (Join-Path $env:TEMP "hospital-clean-npm.log")
        $installed = Test-Path "node_modules"
        Pop-Location
        if ($installed) { Ok "依赖安装完成" } else { Bad "npm.cmd install 失败（见 %TEMP%\hospital-clean-npm.log）" }
    } else {
        Info "frontend\node_modules 已存在，跳过 install"
    }

    Push-Location frontend
    $buildLog = Join-Path $env:TEMP "hospital-clean-build.log"
    & npm.cmd run build *> $buildLog
    $built = Test-Path "dist\index.html"
    Pop-Location

    if ($built) {
        $size = [math]::Round(((Get-ChildItem "frontend\dist" -Recurse -File | Measure-Object -Property Length -Sum).Sum / 1MB), 2)
        Ok "前端构建成功（产物 $size MB）"
    } else {
        Bad "前端构建失败（见 %TEMP%\hospital-clean-build.log）"
        Get-Content $buildLog -Tail 15 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "      $_" -ForegroundColor DarkYellow }
    }
    $steps["4 前端构建"] = if ($built) { "通过" } else { "失败" }
}

# -------------------------------------------------------------------
Section "5. 经 Nginx 端到端验证（A-09 + A-10）"

if (-not (Test-Path "frontend\dist\index.html")) {
    Bad "没有前端产物，无法做端到端验证"
    $steps["5 端到端"] = "失败"
} else {
    Stop-Backend
    $e2eLog = Join-Path $env:TEMP "hospital-clean-e2e.log"
    & powershell -ExecutionPolicy Bypass -File ".\scripts\verify-e2e.ps1" -Port $NginxPort *> $e2eLog
    $tail = Get-Content $e2eLog -ErrorAction SilentlyContinue | Select-Object -Last 40
    $passed = ($tail | Select-String -Pattern 'A-09 与 A-10 端到端验收通过').Count -gt 0
    $sumLine = ($tail | Select-String -Pattern '通过 \d+ 项，失败 \d+ 项' | Select-Object -Last 1)

    if ($passed) {
        Ok "端到端验收通过（$($sumLine.Line.Trim())）"
    } else {
        Bad "端到端验收未通过"
        $tail | Select-Object -Last 20 | ForEach-Object { Write-Host "      $_" -ForegroundColor DarkYellow }
    }
    $steps["5 端到端（Nginx + 浏览器闭环）"] = if ($passed) { "通过" } else { "失败" }
}

# -------------------------------------------------------------------
Section "收尾"

Stop-Backend
& powershell -ExecutionPolicy Bypass -File ".\scripts\start-nginx.ps1" -Stop 2>&1 | Out-Null
Ok "已停后端与 Nginx"

if (-not $KeepData) {
    $env:MYSQL_PWD = $DbPassword
    & mysql -u $DbUser -e "DELETE FROM hospital_appointment.notification; DELETE FROM hospital_appointment.appointment;" 2>&1 | Out-Null
    $left = ((& mysql -u $DbUser -N -B -e "SELECT COUNT(*) FROM hospital_appointment.appointment;" 2>&1) -join '').Trim()
    Ok "测试订单已清理（剩余 $left 条）"
}

Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue

# -------------------------------------------------------------------
Write-Host ""
Write-Host ("=" * 64) -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })
Write-Host "  复现汇总"
Write-Host ("=" * 64) -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })
foreach ($k in $steps.Keys) {
    Write-Host ("  {0,-32} {1}" -f $k, $steps[$k])
}

Write-Host ""
if ($failed -eq 0) {
    Write-Host "干净机器复现验证通过：删库删产物之后，照 README 能一路跑到端到端验收。" -ForegroundColor Green
    exit 0
} else {
    Write-Host "有 $failed 项未通过 —— 说明 README 的复现步骤有缺口，需要修文档或修代码。" -ForegroundColor Red
    exit 1
}
