# ===================================================================
# 多实例部署下的防超卖验证（补 docs/07 里明确标注的"没实测过"）
#
# 用法（在项目根目录）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify-multi-instance.ps1
#
# 需要：JDK 21、MySQL 在跑、数据库能连上（本脚本用**开发库**并自己清理数据）
#
# ⚠️ 为什么要有这个脚本：
#   docs/07-concurrency-results.md 的"没证明"一节里写着：
#
#       | 多实例部署下的行为 | 本机是单实例。不过因为依赖的是数据库行锁
#       |                   而非 JVM 锁，**理论上多实例也成立**——但没实测过，不能声称 |
#
#   这句"理论上成立"正是最需要证据的地方：**防超卖的价值就在多实例**。
#   如果只在单实例上成立，用 synchronized 就够了，根本不需要那条原子 SQL。
#   所以"没实测过"是一个必须补上的缺口。
#
#   本脚本起**两个后端实例**（8081 / 8082）指向**同一个库**，
#   然后让 40 个用户**分散打到两个实例上**抢 20 个号：
#     · 若防超卖依赖 JVM 锁 -> 两个实例各扣各的，必然超卖
#     · 若依赖数据库行锁（本项目的做法）-> 全库只有一个赢家，恰好 20 单
#
# ⚠️ 本文件必须带 UTF-8 BOM（见 push.ps1 的 BOM 守卫）。
# ===================================================================
param(
    [string]$JavaHome = "D:\java\jdk-21",
    [string]$DbUser = "root",
    [string]$DbPassword = "123456",
    [string]$DbName = "hospital_appointment",
    [int]$PortA = 8081,
    [int]$PortB = 8082,
    # 号源总数（用户数是它的两倍，且平均分到两个实例上）
    [int]$Slots = 20,
    [switch]$KeepRunning
)

$ErrorActionPreference = "Continue"
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location $projectRoot

[Console]::OutputEncoding = [Text.Encoding]::UTF8
$env:JAVA_HOME = $JavaHome
$env:MYSQL_PWD = $DbPassword

$Users = $Slots * 2

function Section($t) {
    Write-Host ""
    Write-Host ("=" * 64) -ForegroundColor Cyan
    Write-Host "  $t" -ForegroundColor Cyan
    Write-Host ("=" * 64) -ForegroundColor Cyan
}
function Ok($m)   { Write-Host "  [OK] $m" -ForegroundColor Green }
function Bad($m)  { Write-Host "  [X] $m" -ForegroundColor Red }
function Info($m) { Write-Host "  [i] $m" -ForegroundColor Gray }

function Test-Port($p) {
    return [bool](Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue)
}
function Stop-AllBackends {
    Get-Process java -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -like "*jdk-21*" } | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
}

Write-Host ""
Write-Host "多实例防超卖验证 —— 两个实例共用一个库，看全库是否只有一个赢家" -ForegroundColor White
Write-Host "时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Gray

# -------------------------------------------------------------------
Section "0. 前置检查"

if (-not (Test-Path (Join-Path $JavaHome "bin\java.exe"))) { Bad "找不到 JDK: $JavaHome"; exit 1 }
Ok "JDK: $JavaHome"

if (-not (Test-Port 3306)) { Bad "MySQL 3306 未监听"; exit 1 }
Ok "MySQL 3306 在监听"

$dbOk = ((& mysql -u $DbUser -N -B -e "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$DbName';" 2>&1) -join '').Trim()
if ($dbOk -ne '1') {
    Bad "数据库 $DbName 不存在 —— 先起一次应用让它自己建（或跑 run-dev.ps1）"
    exit 1
}
Ok "数据库 $DbName 存在"

Stop-AllBackends
foreach ($p in $PortA, $PortB) {
    if (Test-Port $p) { Bad "端口 $p 被占用，请先释放"; exit 1 }
}
Ok "端口 $PortA / $PortB 空闲"

# -------------------------------------------------------------------
Section "1. 造测试数据（一条排班 + N 个用户）"

# ⚠️ 复用演示数据里已有的科室与医生，只造一条新排班。
#
# 为什么不自己造科室/医生：第一版就是那么写的，结果因为
# **猜错列名**（department 表没有 updated_at）而整段 SQL 失败——
# 而失败被 `Out-Null` 吞掉了，表现为"排班 id=0、造了 0 个用户"，
# 看起来像后面的并发逻辑有问题，实际是数据根本没插进去。
#
# 教训：**造测试数据时优先复用已知存在的行**，而不是按记忆拼 INSERT。
# 对确实需要新建的表，先查 information_schema 确认列名，不要猜。
$demoRaw = (& mysql -u $DbUser -N -B -e "SELECT d.id, d.department_id FROM $DbName.doctor d JOIN $DbName.department dp ON d.department_id=dp.id WHERE dp.name='内科' LIMIT 1;" 2>&1) -join ''
$demo = if ($demoRaw) { $demoRaw.Trim() } else { '' }
if (-not $demo) { Bad "拿不到演示医生，请先确认演示数据已初始化"; Stop-AllBackends; exit 1 }
$demoParts = ($demo -split "`t")
$doctorId = [int]$demoParts[0].Trim()
$deptId = [int]$demoParts[1].Trim()
Ok "复用演示医生 id=$doctorId（科室 id=$deptId）"

# 排班：列名已按 information_schema 核对过（schedule 有 updated_at）
& mysql -u $DbUser -e "INSERT INTO $DbName.schedule (doctor_id, department_id, work_date, period, total_slots, remaining_slots, fee, created_at, updated_at) VALUES ($doctorId, $deptId, DATE_ADD(CURDATE(), INTERVAL 30 DAY), 'AM', $Slots, $Slots, 50.00, NOW(), NOW());" 2>&1 | Out-Null

# ⚠️ 先取值再转换，不要写成一行的 [int](( ... ) -join '').Trim()。
#    那种嵌法里 `&` 调用运算符和括号的关系很容易被解析错，
#    报出来是"缺少右括号"这种与真实原因无关的信息。
$scheduleIdRaw = (& mysql -u $DbUser -N -B -e "SELECT id FROM $DbName.schedule WHERE doctor_id=$doctorId AND period='AM' AND total_slots=$Slots AND work_date=DATE_ADD(CURDATE(), INTERVAL 30 DAY);" 2>&1) -join ''
$scheduleId = 0
if ($scheduleIdRaw -and $scheduleIdRaw.Trim() -match '^\d+$') { $scheduleId = [int]$scheduleIdRaw.Trim() }
if ($scheduleId -le 0) { Bad "排班插入失败（id=$scheduleId）"; Stop-AllBackends; exit 1 }
Ok "造了排班 id=$scheduleId（号源 $Slots）"

# 用户：列名已核对（sys_user 的 created_at/updated_at 都有默认值，可以不写）
$phones = @()
for ($i = 1; $i -le $Users; $i++) { $phones += ("137" + $suffix + $i.ToString("D2")) }
$values = ($phones | ForEach-Object { "('$_', 'PLACEHOLDER', '多实例用户', 1, 0)" }) -join ','
& mysql -u $DbUser -e "INSERT INTO $DbName.sys_user (phone, password_hash, real_name, enabled, failed_count) VALUES $values;" 2>&1 | Out-Null

$userCountRaw = (& mysql -u $DbUser -N -B -e "SELECT COUNT(*) FROM $DbName.sys_user WHERE phone LIKE '137$suffix%';" 2>&1) -join ''
$userCount = 0
if ($userCountRaw -and $userCountRaw.Trim() -match '^\d+$') { $userCount = [int]$userCountRaw.Trim() }
if ($userCount -ne $Users) { Bad "只造出 $userCount/$Users 个用户"; Stop-AllBackends; exit 1 }
Ok "造了 $userCount 个用户（手机号前缀 137$suffix）"

$env:DB_PASSWORD = $DbPassword
$env:DB_PASSWORD = $DbPassword

# -------------------------------------------------------------------
Section "2. 起两个实例（同一个库）"

$env:REDIS_PASSWORD = "123456"
$env:JWT_SECRET = "dev-only-secret-must-be-at-least-32-bytes-long"
# ⚠️ 刻意关掉 MQ：本验证只关心"号源扣减"，
#    两个实例同时消费队列会让通知重复投递，那是另一个话题，会干扰观察。
$env:MQ_ENABLED = "false"

# ⚠️ 端口用 SERVER_PORT 环境变量覆盖，而不是传给 mvn 的 jvmArguments。
#    Spring Boot 的 relaxed binding 认 SERVER_PORT，而 mvn spring-boot:run
#    的 jvmArguments 在"cmd /c 里再套引号"这种调用下很容易不生效——
#    那种失败的典型表现是**两个实例都起在 8081**，第二个报端口占用，
#    看起来像"多实例起不来"，实际只是参数没传进去。环境变量没有这个问题。
$procs = @()
$env:SERVER_PORT = "$PortA"
foreach ($p in $PortA, $PortB) {
    $log = Join-Path $env:TEMP "hospital-mi-$p.log"
    # 每个实例在子 shell 里设自己的 SERVER_PORT，互不影响
    Start-Process -FilePath "cmd.exe" `
        -ArgumentList '/c', "set SERVER_PORT=$p&& mvn -B spring-boot:run `"-Dspring-boot.run.profiles=dev`" > `"$log`" 2>&1" `
        -WindowStyle Hidden | Out-Null
    $procs += $log
    Info "启动实例（SERVER_PORT=$p），日志 $log"
}
Remove-Item Env:SERVER_PORT -ErrorAction SilentlyContinue

$urls = @("http://127.0.0.1:$PortA", "http://127.0.0.1:$PortB")
$ready = @{}
foreach ($u in $urls) { $ready[$u] = $false }

$sw = [Diagnostics.Stopwatch]::StartNew()
for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep -Seconds 3
    foreach ($u in $urls) {
        if (-not $ready[$u]) {
            try {
                $null = Invoke-WebRequest "$u/api/health" -UseBasicParsing -TimeoutSec 3
                $ready[$u] = $true
            } catch { }
        }
    }
    if (-not ($ready.Values -contains $false)) { break }
}
$sw.Stop()

foreach ($u in $urls) {
    if ($ready[$u]) { Ok "实例就绪：$u" } else { Bad "实例未就绪：$u" }
}
if ($ready.Values -contains $false) {
    Write-Host "--- 日志尾部 ---" -ForegroundColor Yellow
    foreach ($lg in $procs) { Get-Content $lg -Tail 12 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "    $_" } }
    Stop-AllBackends
    exit 1
}
Ok "两个实例都起来了（共 $([math]::Round($sw.Elapsed.TotalSeconds)) 秒）"

# -------------------------------------------------------------------
Section "3. 并发抢号（用户分散打到两个实例上）"

# 先给刚造的用户设置加密口令：直接用演示账号的哈希太脆，
# 改为通过注册接口走一遍——但手机号已存在。
# 所以这里换成更稳的做法：把演示账号的哈希复制给所有测试用户。
$demoHash = ((& mysql -u $DbUser -N -B -e "SELECT password_hash FROM $DbName.sys_user WHERE phone='13800000001';" 2>&1) -join '').Trim()
if (-not $demoHash) { Bad "拿不到演示账号的密码哈希，无法登录测试用户"; Stop-AllBackends; exit 1 }
& mysql -u $DbUser -e "UPDATE $DbName.sys_user SET password_hash='$demoHash' WHERE phone LIKE '137$suffix%';" 2>&1 | Out-Null
Ok "测试用户口令已与演示账号一致"

$tmpDir = Join-Path $env:TEMP "hospital-mi"
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

# 登录拿 N 个令牌（登录本身不并发，避免把噪声混进来）
# 平均分到两个实例上登录，顺便验证两个实例的鉴权都正常。
$tokens = @()
$loginFile = Join-Path $tmpDir "login.json"
$ok = 0
for ($i = 0; $i -lt $Users; $i++) {
    $phone = $phones[$i]
    $target = $urls[$i % 2]
    [IO.File]::WriteAllText($loginFile, "{`"phone`":`"$phone`",`"password`":`"Demo@2026`"}", [Text.UTF8Encoding]::new($false))
    $r = & curl.exe -s "$target/api/auth/login" -X POST -H "Content-Type: application/json" --data-binary "@$loginFile" | ConvertFrom-Json
    if ($r.token) { $tokens += $r.token; $ok++ }
}
if ($ok -ne $Users) { Bad "只拿到 $ok/$Users 个令牌，无法继续"; Stop-AllBackends; exit 1 }
Ok "登录成功 $ok 个用户（分打到两个实例）"

# 闸门：让请求尽量同时发出
$gate = [Threading.ManualResetEventSlim]::new($false)
$results = [System.Collections.Concurrent.ConcurrentBag[object]]::new()

# ⚠️ 用 runspace 池而不是 Start-Job。
#    Start-Job 每个任务起一个独立 PowerShell 进程，40 个就是 40 个进程，
#    启动开销（每个几百毫秒）会**把并发窗口拉得很宽**——
#    那样测出来的"恰好 20"可能是因为请求根本没撞上，而不是因为防线有效。
#    runspace 是同进程内的线程，闸门放行时它们真的会同时发出请求。
$pool = [runspacefactory]::CreateRunspacePool(1, $Users)
$pool.Open()
$handles = @()

for ($i = 0; $i -lt $Users; $i++) {
    $target = $urls[$i % 2]   # 轮换目标实例
    # 把每个请求的输入预先落到文件，runspace 里只做一次 curl 调用
    $bodyFile = Join-Path $tmpDir "book-$i.json"
    [IO.File]::WriteAllText($bodyFile,
        "{`"scheduleId`":$scheduleId,`"idempotencyKey`":`"$([guid]::NewGuid())`"}",
        [Text.UTF8Encoding]::new($false))

    $ps = [powershell]::Create()
    $ps.RunspacePool = $pool
    $null = $ps.AddScript({
        param($Gate, $Url, $Token, $BodyFile, $Results, $Index)
        $Gate.Wait()
        $code = & curl.exe -s -o NUL -w "%{http_code}" "$Url/api/appointments" `
            -X POST -H "Content-Type: application/json" `
            -H "Authorization: Bearer $Token" --data-binary "@$BodyFile"
        $Results.Add([pscustomobject]@{ Index = $Index; Url = $Url; Code = $code })
    }).AddArgument($gate).AddArgument($target).AddArgument($tokens[$i]).AddArgument($bodyFile).AddArgument($results).AddArgument($i)
    $handles += [pscustomobject]@{ PS = $ps; Handle = $ps.BeginInvoke() }
}

Info "已提交 $Users 个请求，放行..."
$gate.Set()
foreach ($h in $handles) {
    $null = $h.Handle.AsyncWaitHandle.WaitOne(120000)
}
foreach ($h in $handles) { $h.PS.EndInvoke($h.Handle); $h.PS.Dispose() }
$pool.Close()
$pool.Dispose()
$gate.Dispose()

$arr = @($results)
$okCount = @($arr | Where-Object { $_.Code -eq '200' }).Count
$conflict = @($arr | Where-Object { $_.Code -eq '409' }).Count
$serverErr = @($arr | Where-Object { [int]$_.Code -ge 500 }).Count
$other = @($arr | Where-Object { $_.Code -ne '200' -and $_.Code -ne '409' -and [int]$_.Code -lt 500 }).Count

$perInstance = $arr | Group-Object Url | ForEach-Object {
    $u = $_.Name
    $o = @($_.Group | Where-Object { $_.Code -eq '200' }).Count
    [pscustomobject]@{ Url = $u; 成功 = $o; 被拒 = @($_.Group | Where-Object { $_.Code -eq '409' }).Count }
}

Write-Host ""
Write-Host "  各实例收到的请求结果：" -ForegroundColor White
$perInstance | ForEach-Object { Write-Host ("    {0}  成功 {1,-4} 被拒 {2}" -f $_.Url, $_.成功, $_.被拒) }
Write-Host ""

# -------------------------------------------------------------------
Section "4. 核对结果"

$remaining = ((& mysql -u $DbUser -N -B -e "SELECT remaining_slots FROM $DbName.schedule WHERE id=$scheduleId;" 2>&1) -join '').Trim()
$orderCount = ((& mysql -u $DbUser -N -B -e "SELECT COUNT(*) FROM $DbName.appointment WHERE schedule_id=$scheduleId;" 2>&1) -join '').Trim()
$distinctUsers = ((& mysql -u $DbUser -N -B -e "SELECT COUNT(DISTINCT user_id) FROM $DbName.appointment WHERE schedule_id=$scheduleId;" 2>&1) -join '').Trim()

$pass = $true
function Check($name, $actual, $expect) {
    if ("$actual" -eq "$expect") {
        Ok "$name = $actual"
    } else {
        Bad "$name 期望 $expect，实际 $actual"
        $script:pass = $false
    }
}

Check "HTTP 200 的数量"      $okCount       $Slots
Check "HTTP 409 的数量"      $conflict      ($Users - $Slots)
Check "5xx 的数量"          $serverErr     0
Check "预期外状态码数量"       $other         0
Check "剩余号源"             $remaining     0
Check "订单总数"             $orderCount    $Slots
Check "下单的不同用户数"       $distinctUsers $Slots

Write-Host ""
if ($pass) {
    Write-Host "  ✅ 多实例防超卖成立：两个实例共库，全库只有 $Slots 个赢家。" -ForegroundColor Green
    Write-Host "     这说明防线在**数据库**上（行锁 + 原子 UPDATE），不是 JVM 锁。" -ForegroundColor Green
} else {
    Write-Host "  ❌ 多实例下防超卖**不成立** —— 这是一个真实缺陷，必须修。" -ForegroundColor Red
}

# -------------------------------------------------------------------
Section "收尾"

if ($KeepRunning) {
    Info "-KeepRunning：两个实例保持运行（$PortA / $PortB）"
} else {
    Stop-AllBackends
    Ok "两个实例已停"
}

# 清掉本次造的数据（按 id 与手机号前缀，绝不误删演示数据）
& mysql -u $DbUser -e @"
DELETE FROM $DbName.appointment WHERE schedule_id=$scheduleId;
DELETE FROM $DbName.notification WHERE appointment_no LIKE 'AP%' AND user_id IN (SELECT id FROM $DbName.sys_user WHERE phone LIKE '137$suffix%');
DELETE FROM $DbName.sys_user WHERE phone LIKE '137$suffix%';
DELETE FROM $DbName.schedule WHERE id=$scheduleId;
"@ 2>&1 | Out-Null
$left = ((& mysql -u $DbUser -N -B -e "SELECT COUNT(*) FROM $DbName.appointment WHERE schedule_id=$scheduleId;" 2>&1) -join '').Trim()
Ok "测试数据已清理（该排班剩余订单 $left 条）"

Remove-Item $tmpDir -Recurse -Force -ErrorAction SilentlyContinue

Write-Host ""
if ($pass) { exit 0 } else { exit 1 }
