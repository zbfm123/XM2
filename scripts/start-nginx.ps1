# ===================================================================
# 一键启动 Nginx（托管前端构建产物 + 反向代理 /api）
#
# 用法（在项目根目录，或任意位置）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\start-nginx.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\start-nginx.ps1 -Port 80
#   powershell -ExecutionPolicy Bypass -File .\scripts\start-nginx.ps1 -Stop
#
# 它做的事：
#   1. 检查前端产物是否存在（不存在就提示先 npm.cmd run build）
#   2. 检查后端 8081 是否在跑（不在就提示，但不阻止启动 Nginx —— 静态页仍可看）
#   3. 按端口选配置、检查语法、启动，并打印访问地址
#
# ⚠️ 为什么默认用 8080 而不是 80：
#   本机 80 端口被 `Steam++.Accelerator` 占用，`listen 80` 会直接启动失败
#   并报 bind() to 0.0.0.0:80 failed (10013)。
#   要恢复标准的 80，先关掉那个程序，再用 -Port 80。
#
# ⚠️ 本文件必须带 UTF-8 BOM（见 push.ps1 的 BOM 守卫）。
# ===================================================================
param(
    [int]$Port = 8080,
    [string]$NginxHome = "D:\nginx\nginx-1.22.0-web\nginx-1.22.0-web",
    [switch]$Stop
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path $PSScriptRoot -Parent
$conf = if ($Port -eq 80) {
    Join-Path $projectRoot "deploy\nginx\nginx.conf"
} else {
    Join-Path $projectRoot "deploy\nginx\nginx.8080.conf"
}

function Test-Port($p) {
    return [bool](Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue)
}

# -------------------------------------------------------------------
if ($Stop) {
    Write-Host "=== 停止 Nginx ===" -ForegroundColor Cyan
    Push-Location $NginxHome
    & .\nginx.exe -s stop 2>&1 | Out-Null
    Start-Sleep -Seconds 2
    Pop-Location
    # -s stop 有时会留下进程（Windows 上的已知现象），补一刀
    $left = Get-Process nginx -ErrorAction SilentlyContinue
    if ($left) {
        & cmd.exe /c "taskkill /IM nginx.exe /F" 2>&1 | Out-Null
        Start-Sleep -Seconds 1
    }
    Write-Host "[OK] 已停止（剩余 nginx 进程：$((Get-Process nginx -ErrorAction SilentlyContinue | Measure-Object).Count)）" -ForegroundColor Green
    exit 0
}

# -------------------------------------------------------------------
Write-Host "=== 启动 Nginx（端口 $Port）===" -ForegroundColor Cyan

if (-not (Test-Path (Join-Path $NginxHome "nginx.exe"))) {
    Write-Host "[X] 找不到 $NginxHome\nginx.exe" -ForegroundColor Red
    Write-Host "    本机可用的版本见 D:\nginx（1.18.0 / 1.20.2 / 1.22.0）" -ForegroundColor Yellow
    exit 1
}

if (-not (Test-Path $conf)) {
    Write-Host "[X] 找不到配置文件 $conf" -ForegroundColor Red
    exit 1
}

# 前端产物
$distIndex = Join-Path $projectRoot "frontend\dist\index.html"
if (-not (Test-Path $distIndex)) {
    Write-Host "[!] 前端产物不存在：frontend\dist\index.html" -ForegroundColor Yellow
    Write-Host "    先构建：cd frontend; npm.cmd run build" -ForegroundColor Yellow
    Write-Host "    （Nginx 仍会启动，但根路径会 404）" -ForegroundColor Gray
} else {
    Write-Host "[OK] 前端产物存在：frontend\dist\index.html"
}

# 后端
if (Test-Port 8081) {
    Write-Host "[OK] 后端 8081 在监听（/api 转发可用）"
} else {
    Write-Host "[!] 后端 8081 未监听 —— 页面能打开，但 /api 会返回 502" -ForegroundColor Yellow
    Write-Host "    启动后端：.\run-dev.ps1" -ForegroundColor Gray
}

# 端口冲突
if (Test-Port $Port) {
    Write-Host "[X] 端口 $Port 已被占用" -ForegroundColor Red
    if ($Port -eq 80) {
        Write-Host "    这很可能就是 Steam++.Accelerator。换用默认的 8080 即可：" -ForegroundColor Yellow
        Write-Host "    .\scripts\start-nginx.ps1" -ForegroundColor Gray
    }
    exit 1
}

# -------------------------------------------------------------------
Write-Host ""
Write-Host "--- 语法检查 ---"
Push-Location $NginxHome
# ⚠️ 传绝对路径：相对路径会相对 nginx prefix 解析，容易找不到文件
$confForNginx = $conf.Replace('\', '/')

# ⚠️ 不用 $LASTEXITCODE 判断成败。
#    nginx 把 "syntax is ok" 写到 stderr，PowerShell 会因此抛 NativeCommandError，
#    退出码会失真（本脚本第一版就因此误判成失败）。
#    改为把输出落到临时文件，再**看内容里有没有 successful** —— 这是唯一可靠的判据。
$tmpOut = Join-Path $env:TEMP ("nginx-t-" + [guid]::NewGuid().ToString("N") + ".txt")
& cmd.exe /c "`"$NginxHome\nginx.exe`" -t -c `"$confForNginx`" > `"$tmpOut`" 2>&1" | Out-Null
$out = if (Test-Path $tmpOut) { Get-Content $tmpOut } else { @() }
$out | ForEach-Object { Write-Host "    $_" }
$syntaxOk = ($out -join "`n") -match "test is successful"
Remove-Item $tmpOut -Force -ErrorAction SilentlyContinue

if (-not $syntaxOk) {
    Pop-Location
    Write-Host "[X] 配置语法检查未通过，未启动" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "--- 启动 ---"
Start-Process -FilePath ".\nginx.exe" -ArgumentList '-c', $confForNginx -WindowStyle Hidden
Pop-Location
Start-Sleep -Seconds 3

$count = (Get-Process nginx -ErrorAction SilentlyContinue | Measure-Object).Count
if (Test-Port $Port) {
    Write-Host "[OK] Nginx 已启动（$count 个进程），端口 $Port 在监听" -ForegroundColor Green
    Write-Host ""
    Write-Host "  访问：  http://localhost:$Port" -ForegroundColor Green
    Write-Host "  健康检查：http://localhost:$Port/api/health" -ForegroundColor Gray
    Write-Host "  停止：  .\scripts\start-nginx.ps1 -Stop" -ForegroundColor Gray
    Write-Host ""
    Write-Host "  日志：  $NginxHome\logs\hospital-access.log" -ForegroundColor Gray
    Write-Host "          $NginxHome\logs\hospital-error.log" -ForegroundColor Gray
} else {
    Write-Host "[X] Nginx 进程数 $count，但端口 $Port 未监听 —— 看错误日志：" -ForegroundColor Red
    Write-Host "    $NginxHome\logs\hospital-error.log" -ForegroundColor Yellow
    if (Test-Path "$NginxHome\logs\hospital-error.log") {
        Get-Content "$NginxHome\logs\hospital-error.log" -Tail 10 | ForEach-Object { Write-Host "    $_" }
    }
    exit 1
}
