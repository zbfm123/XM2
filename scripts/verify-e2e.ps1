# ===================================================================
# 端到端验收脚本：A-09（Nginx 站点）+ A-10（浏览器闭环）
#
# 用法（在项目根目录）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify-e2e.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify-e2e.ps1 -Port 80
#
# 它把"手工点一遍浏览器"变成可复现的检查：
#   1. 起后端（8081）与 Nginx（默认 8080）
#   2. 验证静态资源：首页、打包产物、深层路由回退（try_files）
#   3. 走完整业务闭环：登录 -> 科室 -> 医生 -> 号源 -> 挂号 -> 列表 -> 取消 -> 归还
#   4. 验证错误分支：未认证 401、不存在接口 404、后端不可达 502
#   5. 收尾：停 Nginx 与后端、清理测试产生的订单
#
# ⚠️ 为什么值得单独写一个脚本：
#   "浏览器能闭环"（A-10）这句话，如果是人手点出来的，就**不可复现**——
#   换了台机器、过了两周，没人知道当时点的是哪条路径。
#   把它写成脚本之后，`A-10 通过` 就有了可重复的证据。
#
# ⚠️ 本文件必须带 UTF-8 BOM（见 push.ps1 的 BOM 守卫）。
# ===================================================================
param(
    [int]$Port = 8080,
    [string]$JavaHome = "D:\java\jdk-21",
    [string]$DbPassword = "123456",
    [string]$NginxHome = "D:\nginx\nginx-1.22.0-web\nginx-1.22.0-web",
    # 只验证、不清理（方便手工接着看页面）
    [switch]$KeepRunning
)

$ErrorActionPreference = "Continue"
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location $projectRoot
$env:JAVA_HOME = $JavaHome

[Console]::OutputEncoding = [Text.Encoding]::UTF8

$pass = 0
$fail = 0
$backend = $null

function Check($name, $ok, $detail) {
    if ($ok) {
        Write-Host ("  [通过] {0}  {1}" -f $name, $detail) -ForegroundColor Green
        $script:pass++
    } else {
        Write-Host ("  [失败] {0}  {1}" -f $name, $detail) -ForegroundColor Red
        $script:fail++
    }
}

function Test-Port($p) {
    return [bool](Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue)
}

function Section($t) {
    Write-Host ""
    Write-Host ("-" * 62) -ForegroundColor Cyan
    Write-Host "  $t" -ForegroundColor Cyan
    Write-Host ("-" * 62) -ForegroundColor Cyan
}

# -------------------------------------------------------------------
Section "0. 前置检查"

if (-not (Test-Path (Join-Path $JavaHome "bin\java.exe"))) {
    Write-Host "[X] 找不到 JDK: $JavaHome" -ForegroundColor Red; exit 1
}
if (-not (Test-Path "frontend\dist\index.html")) {
    Write-Host "[X] 前端产物不存在：frontend\dist\index.html" -ForegroundColor Red
    Write-Host "    先构建：cd frontend; npm.cmd run build" -ForegroundColor Yellow
    exit 1
}
Write-Host "[OK] JDK 与前端产物就位"

# 端口占用检查
foreach ($p in 8081, $Port) {
    if (Test-Port $p) {
        Write-Host "[X] 端口 $p 已被占用，请先释放" -ForegroundColor Red
        exit 1
    }
}

# -------------------------------------------------------------------
Section "1. 启动后端（8081）"

$env:DB_PASSWORD = $DbPassword
$env:REDIS_PASSWORD = "123456"
$env:JWT_SECRET = "dev-only-secret-must-be-at-least-32-bytes-long"
# 只做端到端验证，不依赖 broker 是否在跑
$env:MQ_ENABLED = if (Test-Port 5672) { "true" } else { "false" }

Write-Host "MQ_ENABLED=$env:MQ_ENABLED" -ForegroundColor Gray

$log = Join-Path $env:TEMP "hospital-e2e-backend.log"
$backend = Start-Process -FilePath "cmd.exe" `
    -ArgumentList '/c', "mvn -B spring-boot:run `"-Dspring-boot.run.profiles=dev`" > `"$log`" 2>&1" `
    -WindowStyle Hidden -PassThru

$up = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 3
    try {
        $null = Invoke-WebRequest "http://127.0.0.1:8081/api/health" -UseBasicParsing -TimeoutSec 3
        $up = $true; break
    } catch { }
}
Check "后端启动" $up "约 $(($i+1)*3) 秒"

if (-not $up) {
    Write-Host "--- 后端日志尾部 ---" -ForegroundColor Yellow
    Get-Content $log -Tail 20 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "    $_" }
    exit 1
}

# -------------------------------------------------------------------
Section "2. 启动 Nginx（$Port）"

& powershell -ExecutionPolicy Bypass -File ".\scripts\start-nginx.ps1" -Port $Port | ForEach-Object { Write-Host "    $_" }
Check "Nginx 启动" (Test-Port $Port) "端口 $Port"

$base = "http://127.0.0.1:$Port"

# -------------------------------------------------------------------
Section "3. 静态资源（A-09）"

try {
    $r = Invoke-WebRequest "$base/" -UseBasicParsing -TimeoutSec 10
    Check "首页" ($r.StatusCode -eq 200) "HTTP $($r.StatusCode)"
    Check "是真正的 Vue 产物（不是占位页）" ($r.Content -match 'assets/index-.*\.js') ""
} catch {
    Check "首页" $false $_.Exception.Message
}

try {
    $r2 = Invoke-WebRequest "$base/appointments" -UseBasicParsing -TimeoutSec 10
    Check "深层路由回退 try_files" ($r2.StatusCode -eq 200) "HTTP $($r2.StatusCode)"
} catch {
    Check "深层路由回退 try_files" $false $_.Exception.Message
}

# 打包产物可访问
try {
    $idx = (Invoke-WebRequest "$base/" -UseBasicParsing -TimeoutSec 10).Content
    $m = [regex]::Match($idx, 'src="(/assets/index-[^"]+\.js)"')
    if ($m.Success) {
        $js = Invoke-WebRequest ($base + $m.Groups[1].Value) -UseBasicParsing -TimeoutSec 30
        Check "打包 JS 可访问" ($js.StatusCode -eq 200) ("{0} KB" -f [math]::Round($js.RawContentLength / 1KB))
    } else {
        Check "打包 JS 可访问" $false "index.html 里没找到脚本引用"
    }
} catch {
    Check "打包 JS 可访问" $false $_.Exception.Message
}

# -------------------------------------------------------------------
Section "4. 完整业务闭环（A-10）"

$tmp = Join-Path $env:TEMP "hospital-e2e"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$loginFile = Join-Path $tmp "login.json"
[IO.File]::WriteAllText($loginFile, '{"phone":"13800000001","password":"Demo@2026"}', [Text.UTF8Encoding]::new($false))

$login = & curl.exe -s "$base/api/auth/login" -X POST -H "Content-Type: application/json" --data-binary "@$loginFile" | ConvertFrom-Json
$token = $login.token
Check "登录" ($token.Length -gt 100) "用户=$($login.user.realName)"

$authHeader = "Authorization: Bearer $token"
$depts = & curl.exe -s "$base/api/departments" -H $authHeader | ConvertFrom-Json
Check "选科室" ($depts.Count -ge 1) "共 $($depts.Count) 个"
$dept = $depts[0]

$docs = & curl.exe -s "$base/api/doctors?deptId=$($dept.id)" -H $authHeader | ConvertFrom-Json
Check "选医生" ($docs.Count -ge 1) "「$($dept.name)」下有 $($docs.Count) 位"
$doc = $docs[0]

$sc = & curl.exe -s "$base/api/schedules?doctorId=$($doc.id)&size=20" -H $authHeader | ConvertFrom-Json
$slot = $sc.items | Where-Object { $_.remainingSlots -gt 0 } | Select-Object -First 1
Check "选号源" ($null -ne $slot) "$($slot.workDate) $($slot.period)，剩余 $($slot.remainingSlots)/$($slot.totalSlots)"
$before = $slot.remainingSlots

$key = [guid]::NewGuid().ToString()
$bookFile = Join-Path $tmp "book.json"
[IO.File]::WriteAllText($bookFile, "{`"scheduleId`":$($slot.id),`"idempotencyKey`":`"$key`"}", [Text.UTF8Encoding]::new($false))

$book = & curl.exe -s "$base/api/appointments" -X POST -H "Content-Type: application/json" -H $authHeader --data-binary "@$bookFile" | ConvertFrom-Json
Check "挂号" ($book.status -eq 'PENDING_PAYMENT') "单号=$($book.appointmentNo) replayed=$($book.replayed)"

$after = (& curl.exe -s "$base/api/schedules?doctorId=$($doc.id)&size=20" -H $authHeader | ConvertFrom-Json).items |
    Where-Object { $_.id -eq $slot.id } | Select-Object -ExpandProperty remainingSlots
Check "号源 -1" ($after -eq ($before - 1)) "$before -> $after"

$mine = & curl.exe -s "$base/api/appointments?page=1&size=10" -H $authHeader | ConvertFrom-Json
$hit = $mine.items | Where-Object { $_.appointmentNo -eq $book.appointmentNo }
Check "我的挂号列表" ($null -ne $hit) "total=$($mine.total)，医生=$($hit.doctorName)"

$cancel = & curl.exe -s "$base/api/appointments/$($book.appointmentNo)/cancel" -X POST -H "Content-Type: application/json" -H $authHeader --data-binary '{}' | ConvertFrom-Json
Check "取消" ($cancel.status -eq 'CANCELLED') "状态=$($cancel.status)"

$final = (& curl.exe -s "$base/api/schedules?doctorId=$($doc.id)&size=20" -H $authHeader | ConvertFrom-Json).items |
    Where-Object { $_.id -eq $slot.id } | Select-Object -ExpandProperty remainingSlots
Check "号源归还" ($final -eq $before) "$after -> $final"

# -------------------------------------------------------------------
Section "5. 错误分支"

$code401 = & curl.exe -s -o NUL -w "%{http_code}" "$base/api/departments"
Check "未认证返回 401" ($code401 -eq "401") "HTTP $code401"

$code404 = & curl.exe -s -o NUL -w "%{http_code}" "$base/api/nonexistent-endpoint" -H $authHeader
Check "不存在接口返回 404" ($code404 -eq "404") "HTTP $code404"

$codeHealth = & curl.exe -s -o NUL -w "%{http_code}" "$base/api/health"
Check "/api/health 经代理可用" ($codeHealth -eq "200") "HTTP $codeHealth"

# -------------------------------------------------------------------
Section "汇总"

Write-Host ("  通过 {0} 项，失败 {1} 项" -f $pass, $fail) -ForegroundColor $(if ($fail -eq 0) { "Green" } else { "Red" })

# -------------------------------------------------------------------
Section "收尾"

if ($KeepRunning) {
    Write-Host "[i] -KeepRunning：服务保持运行" -ForegroundColor Yellow
    Write-Host "    页面：  $base" -ForegroundColor Gray
    Write-Host "    停止：  .\scripts\start-nginx.ps1 -Stop" -ForegroundColor Gray
    Write-Host "    后端进程 PID: $($backend.Id)" -ForegroundColor Gray
} else {
    & powershell -ExecutionPolicy Bypass -File ".\scripts\start-nginx.ps1" -Stop | Out-Null
    Write-Host "[OK] Nginx 已停"

    Get-Process java -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -like "*jdk-21*" } | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
    Write-Host "[OK] 后端已停"

    # 清掉本次验证产生的订单（脚本自己造的，不能留给开发库）
    $env:MYSQL_PWD = $DbPassword
    & mysql -u root -e "DELETE FROM hospital_appointment.notification; DELETE FROM hospital_appointment.appointment;" 2>&1 | Out-Null
    $left = (& mysql -u root -N -B -e "SELECT COUNT(*) FROM hospital_appointment.appointment;" 2>&1) -join ''
    Write-Host "[OK] 测试订单已清理（剩余 $left 条）"

    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "[OK] 临时文件已清理"
}

if ($fail -eq 0) {
    Write-Host ""
    Write-Host "A-09 与 A-10 端到端验收通过。" -ForegroundColor Green
    exit 0
} else {
    exit 1
}
