# ===================================================================
# 医院预约挂号系统 · RabbitMQ 服务化脚本（需要管理员权限）
#
# 为什么需要这个脚本：
#   RabbitMQ 目前是"解压即用 + 手动启动"的状态，重启机器后不会自动运行。
#   而 MySQL80 与 Redis 都是 Windows 自启动服务。为了让三者一致
#   （也让 A-09"干净机器可复现"有据可依），需要把 broker 注册成服务。
#
#   注册 Windows 服务需要管理员权限，所以这一步必须由你手动执行。
#
# 用法（二选一）：
#   1) 右键"以管理员身份运行 PowerShell"，然后执行：
#        powershell -ExecutionPolicy Bypass -File D:\xmdeepseek\hospital-appointment\scripts\install-rabbitmq-service.ps1
#   2) 或者在已打开的窗口里执行：
#        Start-Process powershell -Verb RunAs -ArgumentList '-ExecutionPolicy','Bypass','-File','D:\xmdeepseek\hospital-appointment\scripts\install-rabbitmq-service.ps1'
#
# ⚠️ 本脚本可重复执行（幂等）：已安装则先移除再重装。
# ===================================================================

$ErrorActionPreference = "Stop"

# -------------------------------------------------------------------
# 0. 必须提权
# -------------------------------------------------------------------
$identity  = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "❌ 需要管理员权限。请右键 PowerShell -> 以管理员身份运行，再执行本脚本。" -ForegroundColor Red
    exit 1
}

# -------------------------------------------------------------------
# 1. 路径与参数（都在 D 盘，不往 C 盘写任何东西）
# -------------------------------------------------------------------
$ErlangHome   = "D:\erl-26.2.5.21"
$RabbitMqHome = "D:\rabbitmq"
$RabbitMqData = "D:\rabbitmq\data"
$RabbitMqLogs = "D:\rabbitmq\data\log\rabbit.log"
$ServiceName  = "RabbitMQ"

# ⚠️ 固定 Erlang cookie 的值，是这个脚本里最关键的一处设计。
#
# 问题：Windows 服务以 LocalSystem 身份运行，它的用户配置文件目录是
#       C:\Windows\system32\config\systemprofile，因此 broker 会去那里读/建
#       .erlang.cookie；而你在自己的终端里跑 rabbitmqctl 时，用的是
#       C:\Users\tianliang\.erlang.cookie。两个文件不同 -> CLI 报
#       "unable to connect to node"，而服务本身其实跑得好好的。
#
# 解法：用 RABBITMQ_ERLANG_COOKIE 环境变量显式指定同一个值，
#       让服务和 CLI 都不再依赖各自的用户配置文件路径。
#
# ⚠️ 这是一个**本地开发环境**的固定值。生产中绝不该这样：
#    正式环境应当让 RabbitMQ 随机生成 cookie，并保证只有运维账号能读该文件。
$Cookie = "HOSPITALDEVCOOKIE2026"

Write-Host "=== 安装 RabbitMQ 为 Windows 服务 ===" -ForegroundColor Cyan
Write-Host "Erlang  : $ErlangHome"
Write-Host "RabbitMQ: $RabbitMqHome"
Write-Host "数据目录: $RabbitMqData"
Write-Host "服务名  : $ServiceName"
Write-Host ""

# -------------------------------------------------------------------
# 2. 前置检查
# -------------------------------------------------------------------
foreach ($p in @("$ErlangHome\bin\erl.exe", "$RabbitMqHome\sbin\rabbitmq-service.bat")) {
    if (-not (Test-Path $p)) {
        Write-Host "❌ 找不到 $p" -ForegroundColor Red
        Write-Host "   请确认 Erlang 与 RabbitMQ 已解压到 D 盘。" -ForegroundColor Red
        exit 1
    }
}
New-Item -ItemType Directory -Force -Path $RabbitMqData | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path $RabbitMqLogs) | Out-Null

# -------------------------------------------------------------------
# 3. 写系统级环境变量
#
# 必须是"系统级"而不是"用户级"：服务以 LocalSystem 运行，读的是系统级变量。
# -------------------------------------------------------------------
Write-Host "[1/6] 写入系统环境变量 ..." -ForegroundColor Yellow
[Environment]::SetEnvironmentVariable("ERLANG_HOME",          $ErlangHome,   "Machine")
[Environment]::SetEnvironmentVariable("RABBITMQ_HOME",        $RabbitMqHome, "Machine")
[Environment]::SetEnvironmentVariable("RABBITMQ_BASE",        $RabbitMqData, "Machine")
[Environment]::SetEnvironmentVariable("RABBITMQ_LOGS",        $RabbitMqLogs, "Machine")
[Environment]::SetEnvironmentVariable("RABBITMQ_SERVICENAME", $ServiceName,  "Machine")
[Environment]::SetEnvironmentVariable("RABBITMQ_ERLANG_COOKIE", $Cookie,     "Machine")
[Environment]::SetEnvironmentVariable("ERL_CRASH_DUMP", "$RabbitMqData\erl_crash.dump", "Machine")

# PATH：让 rabbitmqctl 等命令在任何终端都能直接调用
$machinePath = [Environment]::GetEnvironmentVariable("Path", "Machine")
$need = @("$RabbitMqHome\sbin", "$ErlangHome\bin") | Where-Object { $machinePath -notlike "*$_*" }
if ($need) {
    [Environment]::SetEnvironmentVariable("Path", ($machinePath.TrimEnd(';') + ";" + ($need -join ';')), "Machine")
    Write-Host "      已追加到系统 PATH: $($need -join ', ')"
} else {
    Write-Host "      系统 PATH 已包含，跳过"
}

# 当前进程也生效，后面几步要用
$env:ERLANG_HOME            = $ErlangHome
$env:RABBITMQ_HOME          = $RabbitMqHome
$env:RABBITMQ_BASE          = $RabbitMqData
$env:RABBITMQ_LOGS          = $RabbitMqLogs
$env:RABBITMQ_SERVICENAME   = $ServiceName
$env:RABBITMQ_ERLANG_COOKIE = $Cookie

# -------------------------------------------------------------------
# 4. 停掉可能存在的旧实例与服务，避免端口冲突
# -------------------------------------------------------------------
Write-Host "[2/6] 清理旧实例 ..." -ForegroundColor Yellow

$existing = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "      发现已存在的服务，先移除以便重建"
    if ($existing.Status -ne 'Stopped') {
        Stop-Service -Name $ServiceName -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 5
    }
    & "$RabbitMqHome\sbin\rabbitmq-service.bat" remove 2>&1 | Out-Null
    Start-Sleep -Seconds 3
}

# 手动启动的 broker 会占着 5672，必须先停
$onPort = Get-NetTCPConnection -LocalPort 5672 -State Listen -ErrorAction SilentlyContinue
if ($onPort) {
    Write-Host "      检测到 5672 被占用（可能是手动启动的 broker），正在停止 ..."
    & "$RabbitMqHome\sbin\rabbitmqctl.bat" stop 2>&1 | Out-Null
    Start-Sleep -Seconds 10
}

# -------------------------------------------------------------------
# 5. 安装并启动服务
# -------------------------------------------------------------------
Write-Host "[3/6] 注册服务 ..." -ForegroundColor Yellow
& "$RabbitMqHome\sbin\rabbitmq-service.bat" install 2>&1 | ForEach-Object { "      $_" }

Write-Host "[4/6] 设置为自动启动 ..." -ForegroundColor Yellow
# rabbitmq-service.bat 默认装成手动启动，这里显式改成自动（与 MySQL80 / Redis 一致）
& sc.exe config $ServiceName start= auto 2>&1 | ForEach-Object { "      $_" }

Write-Host "[5/6] 启动服务 ..." -ForegroundColor Yellow
& "$RabbitMqHome\sbin\rabbitmq-service.bat" start 2>&1 | ForEach-Object { "      $_" }

# -------------------------------------------------------------------
# 6. 等待就绪 + 启用管理插件 + 验证
# -------------------------------------------------------------------
Write-Host "[6/6] 等待 broker 就绪 ..." -ForegroundColor Yellow
$ready = $false
for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Seconds 3
    if (Get-NetTCPConnection -LocalPort 5672 -State Listen -ErrorAction SilentlyContinue) { $ready = $true; break }
}
if ($ready) { Write-Host "      ✅ 5672 已监听（约 $(($i+1)*3) 秒）" -ForegroundColor Green }
else        { Write-Host "      ⚠️ 5672 未在 120 秒内监听，请检查 $RabbitMqLogs" -ForegroundColor Red }

Write-Host "      启用 rabbitmq_management ..."
& "$RabbitMqHome\sbin\rabbitmq-plugins.bat" enable rabbitmq_management 2>&1 |
    Select-String -Pattern "enabled|started|Error" | ForEach-Object { "      $_" }

for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Seconds 3
    if (Get-NetTCPConnection -LocalPort 15672 -State Listen -ErrorAction SilentlyContinue) { break }
}

# -------------------------------------------------------------------
# 7. 验证
# -------------------------------------------------------------------
Write-Host ""
Write-Host "=== 验证结果 ===" -ForegroundColor Cyan

$svc = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($svc) {
    $sm = (Get-CimInstance Win32_Service -Filter "Name='$ServiceName'").StartMode
    Write-Host ("服务状态: {0} / 启动类型: {1}" -f $svc.Status, $sm) -ForegroundColor $(if ($svc.Status -eq 'Running' -and $sm -eq 'Auto') { 'Green' } else { 'Yellow' })
}

foreach ($p in 4369, 5672, 15672, 25672) {
    $st = if (Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue) { "LISTEN" } else { "未监听" }
    Write-Host ("端口 {0,-6} {1}" -f $p, $st)
}

Write-Host ""
Write-Host "版本:" -NoNewline
& "$RabbitMqHome\sbin\rabbitmqctl.bat" version 2>&1 | Select-Object -First 1

try {
    $cred = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("guest:guest"))
    $api  = Invoke-RestMethod "http://localhost:15672/api/overview" -Headers @{ Authorization = "Basic $cred" } -TimeoutSec 15
    Write-Host "管理台: HTTP 200, RabbitMQ $($api.rabbitmq_version), Erlang $($api.erlang_version)" -ForegroundColor Green
    Write-Host "管理台地址: http://localhost:15672  (guest / guest，仅限本机)"
} catch {
    Write-Host "⚠️ 管理台还没起来，稍等片刻再访问 http://localhost:15672" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "=== 完成 ===" -ForegroundColor Green
Write-Host "以后重启机器，RabbitMQ 会自动启动（与 MySQL80 / Redis 一样）。"
Write-Host "若需手动控制："
Write-Host "  net stop  RabbitMQ"
Write-Host "  net start RabbitMQ"
Write-Host ""
Write-Host "⚠️ 新开一个终端，让新的系统 PATH 生效，之后可以直接用 rabbitmqctl"
