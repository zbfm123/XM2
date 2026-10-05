# ===================================================================
# 医院预约挂号系统 · 一键跑完全部验证
#
# 用法（在项目根目录）：
#   powershell -ExecutionPolicy Bypass -File .\run-all-tests.ps1
#   powershell -ExecutionPolicy Bypass -File .\run-all-tests.ps1 -SkipMysql
#
# 它依次做三件事：
#   1. mvn test                    —— 98 个测试（H2 + 内存 Redis，不依赖任何本机服务）
#   2. 真实 MySQL 并发验证          —— A-03 的第二处证据（H2 的锁实现与 InnoDB 不同）
#   3. 前端构建                     —— 确认 Vue 能打出产物
#
# 为什么要有它：
#   "干净机器按 README 能起完整站点"（验收 A-09）这句话，
#   只有在**每条命令都能一条跑完**的时候才可信。
#   把验证步骤散在文档各处，等于每次都靠人记得跑哪几条——一定会漏。
#
# ⚠️ 本文件必须带 UTF-8 BOM（见 push.ps1 的 BOM 守卫）。
# ===================================================================
param(
    [string]$JavaHome = "D:\java\jdk-21",
    [string]$DbPassword = "123456",
    # 跳过需要真实 MySQL 的那一步（没有 MySQL 的机器上用）
    [switch]$SkipMysql,
    # 跳过前端构建（没装 node_modules 时用）
    [switch]$SkipFrontend
)

$ErrorActionPreference = "Continue"
Set-Location $PSScriptRoot

$env:JAVA_HOME = $JavaHome

$results = [ordered]@{}
$failed = 0

function Write-Step($text) {
    Write-Host ""
    Write-Host ("=" * 62) -ForegroundColor Cyan
    Write-Host "  $text" -ForegroundColor Cyan
    Write-Host ("=" * 62) -ForegroundColor Cyan
}

# -------------------------------------------------------------------
Write-Step "1/4  后端测试（98 个，H2 内存库）"

& mvn -B test 2>&1 | Select-String -Pattern "Tests run:.*Skipped: \d+$|BUILD" | Select-Object -Last 4

# ⚠️ 不能用 $LASTEXITCODE 判断：mvn 输出到 stderr 时 PowerShell 会设置
#    NativeCommandError，但 Maven 自身可能是成功的。改为看 surefire 报告。
$reportDir = "target\surefire-reports"
if (Test-Path $reportDir) {
    $xmls = Get-ChildItem $reportDir -Filter "*.xml" -ErrorAction SilentlyContinue
    $totalTests = 0; $totalFail = 0; $totalErr = 0
    foreach ($x in $xmls) {
        try {
            [xml]$doc = Get-Content $x.FullName
            $totalTests += [int]$doc.testsuite.tests
            $totalFail  += [int]$doc.testsuite.failures
            $totalErr   += [int]$doc.testsuite.errors
        } catch { }
    }
    $results["后端测试"] = "$totalTests 个，失败 $totalFail，错误 $totalErr"
    if ($totalFail -gt 0 -or $totalErr -gt 0) { $failed++ }
} else {
    $results["后端测试"] = "未找到报告"
    $failed++
}

# -------------------------------------------------------------------
if ($SkipMysql) {
    Write-Step "2/4  真实 MySQL 并发验证（已跳过 -SkipMysql）"
    $results["MySQL 并发验证"] = "跳过"
} else {
    Write-Step "2/4  真实 MySQL 并发验证（A-03 第二处证据）"
    $env:DB_PASSWORD = $DbPassword
    & powershell -ExecutionPolicy Bypass -File ".\scripts\verify-concurrency-on-mysql.ps1" 2>&1 |
        Select-Object -Last 30

    # 该脚本失败时退出码非 0
    if ($LASTEXITCODE -eq 0) {
        $results["MySQL 并发验证"] = "通过"
    } else {
        $results["MySQL 并发验证"] = "失败（退出码 $LASTEXITCODE）"
        $failed++
    }
}

# -------------------------------------------------------------------
if ($SkipFrontend) {
    Write-Step "3/4  前端构建（已跳过 -SkipFrontend）"
    $results["前端构建"] = "跳过"
} elseif (Test-Path "frontend\node_modules") {
    Write-Step "3/4  前端构建（Vite）"
    Push-Location frontend
    & npm.cmd run build 2>&1 | Select-String -Pattern "built in|error|Error|dist/" | Select-Object -Last 8
    Pop-Location

    if (Test-Path "frontend\dist\index.html") {
        $results["前端构建"] = "通过（产物 frontend\dist）"
    } else {
        $results["前端构建"] = "失败（没有产物）"
        $failed++
    }
} else {
    Write-Step "3/4  前端构建（前端依赖未安装）"
    Write-Host "先在 frontend 目录执行：npm.cmd install" -ForegroundColor Yellow
    $results["前端构建"] = "跳过（需先 npm.cmd install）"
}

# -------------------------------------------------------------------
Write-Step "4/4  文档一致性（测试数量 / 脚本引用 / BOM / 过时措辞）"

& powershell -ExecutionPolicy Bypass -File ".\scripts\check-docs.ps1" 2>&1 | Select-Object -Last 12

if ($LASTEXITCODE -eq 0) {
    $results["文档一致性"] = "通过"
} else {
    $results["文档一致性"] = "有**不一致**（见上方）"
    $failed++
}

# -------------------------------------------------------------------
Write-Host ""
Write-Host ("=" * 62) -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })
Write-Host "  验证汇总" -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })
Write-Host ("=" * 62) -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })
foreach ($k in $results.Keys) {
    Write-Host ("  {0,-18} {1}" -f $k, $results[$k])
}
Write-Host ""

if ($failed -eq 0) {
    Write-Host "全部通过。" -ForegroundColor Green
    Write-Host ""
    Write-Host "接下来要验证浏览器闭环（A-10），按这个顺序：" -ForegroundColor Gray
    Write-Host "  1) 起后端： .\run-dev.ps1" -ForegroundColor Gray
    Write-Host "  2) 起前端： cd frontend; npm.cmd run dev   （访问 http://localhost:5173）" -ForegroundColor Gray
    Write-Host "  3) 或走 Nginx： .\scripts\start-nginx.ps1 （访问 http://localhost:8080）" -ForegroundColor Gray
    exit 0
} else {
    Write-Host "有 $failed 项未通过，请看上面的输出。" -ForegroundColor Red
    exit 1
}
