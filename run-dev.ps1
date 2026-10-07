# ===================================================================
# 医院预约挂号系统 · 一键启动后端（dev）
#
# 用法（在项目根目录）：
#   powershell -ExecutionPolicy Bypass -File .\run-dev.ps1
#
# 它做的事：
#   1. 检查 JDK / Maven / MySQL / Redis / RabbitMQ 是否就绪，缺什么就说清楚
#   2. 补齐三个必需的环境变量（JWT_SECRET / DB_PASSWORD / REDIS_PASSWORD）
#   3. 启动 Spring Boot（端口 8081，profile=dev）
#
# 为什么要有这个脚本：
#   README 里那段启动命令有 5 行环境变量。**手输 5 行就会有人漏一行**，
#   而漏掉 JWT_SECRET 的后果是应用直接启动失败（刻意设计，见下）。
#   把它固化成一个脚本，就不会因为"少设了一个变量"而浪费时间。
#
# ⚠️ 本文件**必须是 UTF-8 带 BOM**。
#    这台机器上的 Windows PowerShell 5.1 对无 BOM 的 .ps1 会按 GBK 解码，
#    中文注释会被解成乱码并**破坏语法**（报一些看不懂的解析错误）。
#    push.ps1 里有一道守卫专门检查这一点。
# ===================================================================
param(
    [string]$JavaHome = "D:\java\jdk-21",
    [string]$DbPassword = "123456",
    [string]$RedisPassword = "123456",
    [string]$JwtSecret = "dev-only-secret-must-be-at-least-32-bytes-long",
    [string]$Profile = "dev",
    # 只检查环境、不启动（用来快速确认"这台机器能不能跑"）
    [switch]$CheckOnly
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

function Write-Step($text) {
    Write-Host ""
    Write-Host "=== $text ===" -ForegroundColor Cyan
}

function Test-Port($port) {
    return [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

$problems = @()

# -------------------------------------------------------------------
Write-Step "1. 检查工具链"

if (Test-Path (Join-Path $JavaHome "bin\java.exe")) {
    Write-Host "[OK] JDK: $JavaHome"
    $env:JAVA_HOME = $JavaHome
} else {
    Write-Host "[X] 找不到 $JavaHome\bin\java.exe" -ForegroundColor Red
    $problems += "JDK"
}

$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if ($mvn) {
    Write-Host "[OK] Maven: $($mvn.Source)"
} else {
    Write-Host "[X] PATH 里没有 mvn" -ForegroundColor Red
    $problems += "Maven"
}

# ⚠️ 这里刻意检查 JAVA_HOME，而不是只看 PATH 上的 java：
#   这台机器 PATH 上的 java 是 JDK 1.8，会让 Maven 报"不支持的类版本"。
if ($env:JAVA_HOME -ne $JavaHome) {
    Write-Host "[!] JAVA_HOME 未指向 $JavaHome（已由本脚本临时设置）" -ForegroundColor Yellow
}

# -------------------------------------------------------------------
Write-Step "2. 检查依赖服务"

# MySQL
if (Test-Port 3306) {
    Write-Host "[OK] MySQL 3306 在监听"
} else {
    Write-Host "[X] MySQL 3306 未监听 —— 启动 Windows 服务 MySQL80" -ForegroundColor Red
    $problems += "MySQL"
}

# Redis —— **可选，但已真实使用**（号源查询缓存，Cache-Aside）。
#    不起来也能跑：缓存读写失败会降级为直查数据库（有测试覆盖）。
#    代价只是失去缓存带来的提速，功能不受影响。
#    见 docs/02-architecture.md 的"关于 Redis"一节。
#    所以这里只提示，不阻止启动——把可选项写成必需项，
#    只会让人在一个其实无关的服务上白费时间。
if (Test-Port 6379) {
    Write-Host "[OK] Redis 6379 在监听（号源查询缓存生效）"
} else {
    Write-Host "[!] Redis 6379 未监听 —— 不影响运行（缓存会降级为直查库，只是没了提速）" -ForegroundColor Yellow
}

# RabbitMQ（**可选**：A-07 明确要求 MQ 不可用时挂号仍然成功）
if (Test-Port 5672) {
    Write-Host "[OK] RabbitMQ 5672 在监听（异步通知会真正投递）"
} else {
    Write-Host "[!] RabbitMQ 5672 未监听 —— 不影响主流程（A-07），只是不会有通知记录" -ForegroundColor Yellow
    Write-Host "    需要它时：D:\rabbitmq\sbin\rabbitmq-server.bat -detached" -ForegroundColor Gray
    $env:MQ_ENABLED = "false"
}

# 端口冲突：8081 是本项目的端口，被占就起不来
if (Test-Port 8081) {
    Write-Host "[X] 8081 已被占用 —— 可能已经有一个后端在跑" -ForegroundColor Red
    $problems += "端口 8081"
} else {
    Write-Host "[OK] 8081 空闲"
}

# -------------------------------------------------------------------
Write-Step "3. 设置环境变量"

# ⚠️ JWT_SECRET 不设会**启动失败**，这是刻意设计：
#    一个可预测的默认密钥意味着任何人都能自己签一个令牌冒充任意用户。
#    这里的值是公开的本地开发值，生产必须用环境变量覆盖。
$env:DB_PASSWORD = $DbPassword
$env:REDIS_PASSWORD = $RedisPassword
$env:JWT_SECRET = $JwtSecret
$env:SPRING_PROFILES_ACTIVE = $Profile

Write-Host "[OK] DB_PASSWORD / REDIS_PASSWORD 已设置（本地开发值）"
Write-Host "[OK] JWT_SECRET 已设置（长度 $($JwtSecret.Length) 字节，HS256 要求 >= 32）"
Write-Host "[OK] SPRING_PROFILES_ACTIVE=$Profile"

if ($JwtSecret.Length -lt 32) {
    Write-Host "[X] JWT_SECRET 必须至少 32 字节，否则应用启动即失败" -ForegroundColor Red
    $problems += "JWT_SECRET 过短"
}

# -------------------------------------------------------------------
if ($problems.Count -gt 0) {
    Write-Step "无法启动"
    Write-Host "以下前置条件不满足：" -ForegroundColor Red
    $problems | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    Write-Host ""
    Write-Host "排查提示见 README 第三节（环境要求）与 docs/PROGRESS.md（踩坑记录）。" -ForegroundColor Yellow
    exit 1
}

if ($CheckOnly) {
    Write-Step "检查完成（-CheckOnly，未启动）"
    Write-Host "[OK] 这台机器满足启动条件" -ForegroundColor Green
    exit 0
}

# -------------------------------------------------------------------
Write-Step "4. 启动后端（端口 8081）"
Write-Host "按 Ctrl+C 停止。" -ForegroundColor Gray
Write-Host ""

mvn spring-boot:run "-Dspring-boot.run.profiles=$Profile"
