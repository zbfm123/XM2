# ===================================================================
# 前后端 API 契约检查
#
# 用法（在项目根目录；需要后端已在 8081 运行）：
#   .\run-dev.ps1                       # 另一个窗口先起后端
#   powershell -ExecutionPolicy Bypass -File .\scripts\check-api-contract.ps1
#
# 它检查三件事：
#   1. 前端组件里调用的每个 api.xxx()，在 src/api/index.js 里是否真的定义了
#      （防"视图里调了一个不存在的方法"——这类错误只在运行时才暴露，
#        而且表现为页面上一句模糊的 TypeError）
#   2. src/api/index.js 里定义的方法，是否都被用到了
#      （提示类：定义了却没人用，通常意味着某个页面还没接上）
#   3. 逐个真实调用后端，确认接口存在、鉴权生效、返回结构符合预期
#
# ⚠️ 为什么值得单独做：
#   前后端分离的项目里，最容易出的问题不是"某一边写错了"，
#   而是**两边的契约对不上**——前端拼的路径、字段名、HTTP 方法
#   与后端不一致。这类问题编译期发现不了（两边是不同语言），
#   静态检查也发现不了，只能靠"真的打一次"。
#
# ⚠️ 本文件必须带 UTF-8 BOM（见 push.ps1 的 BOM 守卫）。
# ===================================================================
param(
    [string]$BaseUrl = "http://127.0.0.1:8081",
    [string]$Phone = "13800000001",
    [string]$Password = "Demo@2026"
)

$ErrorActionPreference = "Continue"
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location $projectRoot

[Console]::OutputEncoding = [Text.Encoding]::UTF8

$fail = 0
$hint = 0

function Section($t) {
    Write-Host ""
    Write-Host ("-" * 62) -ForegroundColor Cyan
    Write-Host "  $t" -ForegroundColor Cyan
    Write-Host ("-" * 62) -ForegroundColor Cyan
}
function Ok($m)   { Write-Host "  [OK] $m" -ForegroundColor Green }
function Bad($m)  { Write-Host "  [X] $m" -ForegroundColor Red; $script:fail++ }
function Hint($m) { Write-Host "  [!] $m" -ForegroundColor Yellow; $script:hint++ }

$apiFile = "frontend\src\api\index.js"
if (-not (Test-Path $apiFile)) {
    Write-Host "[X] 找不到 $apiFile" -ForegroundColor Red
    exit 1
}

# -------------------------------------------------------------------
Section "1. 前端调用的方法是否都已定义"

$apiSource = Get-Content $apiFile -Raw -Encoding UTF8

# 只抓 `export const api = { ... }` 这个块里的方法名。
#
# ⚠️ 第一版直接对整个文件按 "^  名字(" 匹配，结果把 auth.token / config.baseURL /
#    config.timeout 这些**别的对象**的成员也算成了 api 方法，
#    于是"定义了但没人用"那一节报了 7 条假提示。
#    定位到具体的块再抓，是唯一可靠的做法。
$apiBlock = ''
$blockMatch = [regex]::Match($apiSource, 'export const api\s*=\s*\{([\s\S]*?)\n\}')
if ($blockMatch.Success) { $apiBlock = $blockMatch.Groups[1].Value }
else { Bad '没能定位 export const api = { ... } 块，方法列表可能不准' }

$defined = @()
# 方法形如：  name(args) {      或   name: (args) => 
foreach ($m in [regex]::Matches($apiBlock, '(?m)^\s{2}([a-zA-Z][a-zA-Z0-9]*)\s*[:(]')) {
    $name = $m.Groups[1].Value
    if ($defined -notcontains $name) { $defined += $name }
}
Write-Host "  src/api/index.js 中 api 对象的方法（$($defined.Count) 个）：$($defined -join ', ')"

# 扫描所有 .vue / .js 里的 api.xxx( 调用
$called = @{}
Get-ChildItem "frontend\src" -Recurse -Include *.vue, *.js -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch '\\node_modules\\' -and $_.Name -ne 'index.js' } |
    ForEach-Object {
        $src = Get-Content $_.FullName -Raw -Encoding UTF8
        foreach ($m in [regex]::Matches($src, '\bapi\.([a-zA-Z][a-zA-Z0-9]*)\s*\(')) {
            $name = $m.Groups[1].Value
            if (-not $called.ContainsKey($name)) { $called[$name] = @() }
            $called[$name] += $_.Name
        }
    }

$missing = @()
foreach ($name in $called.Keys) {
    if ($defined -notcontains $name) {
        $missing += "$name（被 $((($called[$name]) | Select-Object -Unique) -join ', ') 调用）"
    }
}

if ($missing.Count -eq 0) {
    Ok "所有被调用的方法都已定义（共 $($called.Count) 个被调用）"
} else {
    foreach ($m in $missing) { Bad "调用了未定义的方法：api.$m" }
}

# 反向：定义了但没人用
$unused = $defined | Where-Object { -not $called.ContainsKey($_) }
if ($unused.Count -eq 0) {
    Ok "没有定义了却没人用的方法"
} else {
    Hint "定义了但没被调用：$($unused -join ', ')（可能某个页面还没接上，或该删掉）"
}

# -------------------------------------------------------------------
Section "2. 后端接口真实可用性"

# 探活
try {
    $null = Invoke-WebRequest "$BaseUrl/api/health" -UseBasicParsing -TimeoutSec 5
} catch {
    Bad "后端 $BaseUrl 不可达 —— 先执行 .\run-dev.ps1"
    Write-Host ""
    Write-Host "  （只做静态检查的话，第 1 节的结果仍然有效）" -ForegroundColor Gray
    exit 1
}
Ok "后端可达"

# 登录拿令牌
$tmp = Join-Path $env:TEMP "hospital-contract"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$loginFile = Join-Path $tmp "login.json"
[IO.File]::WriteAllText($loginFile,
    "{`"phone`":`"$Phone`",`"password`":`"$Password`"}", [Text.UTF8Encoding]::new($false))

$login = & curl.exe -s "$BaseUrl/api/auth/login" -X POST -H "Content-Type: application/json" --data-binary "@$loginFile" | ConvertFrom-Json
$token = $login.token
if (-not $token) { Bad "登录失败，无法继续验证接口"; exit 1 }
Ok "登录成功（$($login.user.realName)）"
$auth = "Authorization: Bearer $token"

# 逐个接口：方法 + 路径 + 期望字段
$checks = @(
    @{ Name = '科室列表';    Method = 'GET';  Url = '/api/departments'; Expect = 'id,name';            Auth = $true },
    @{ Name = '当前用户';    Method = 'GET';  Url = '/api/auth/me';     Expect = 'phone';              Auth = $true },
    @{ Name = '我的挂号';    Method = 'GET';  Url = '/api/appointments?page=1&size=5'; Expect = 'items,total,page,size,totalPages'; Auth = $true },
@{ Name = '健康检查';    Method = 'GET';  Url = '/api/health';      Expect = 'status';             Auth = $false }

    # ⚠️ 模拟支付与就诊完成（决策 D-07）不在这个表里：
    #    它们需要一张真实订单才能调用，属于"有副作用的接口"，
    #    由 scripts/verify-e2e.ps1 的完整闭环覆盖（那里会真的挂一单再推进）。
    #    这里只做**静态**核对——确认前端调用的 api.pay / api.complete 确实定义了，
    #    那由第 1 节自动覆盖（它扫的是 index.js 里 api 对象的全部方法）。
)

foreach ($c in $checks) {
    $args = @('-s', ($BaseUrl + $c.Url))
    if ($c.Auth) { $args += @('-H', $auth) }
    try {
        $body = & curl.exe @args
        $obj = $body | ConvertFrom-Json
        $missingFields = @()
        foreach ($f in ($c.Expect -split ',')) {
            if ($null -eq $obj.$f) { $missingFields += $f }
        }
        if ($missingFields.Count -eq 0) {
            Ok "$($c.Name)：$($c.Url) 字段齐全（$($c.Expect)）"
        } else {
            Bad "$($c.Name)：$($c.Url) 缺少字段 $($missingFields -join ', ')"
        }
    } catch {
        Bad "$($c.Name)：$($c.Url) 调用失败 —— $($_.Exception.Message)"
    }
}

# 带参数的接口需要先取到真实 id
$depts = & curl.exe -s "$BaseUrl/api/departments" -H $auth | ConvertFrom-Json
if ($depts.Count -gt 0) {
    $depId = $depts[0].id
    $body = & curl.exe -s "$BaseUrl/api/doctors?deptId=$depId" -H $auth
    $docs = $body | ConvertFrom-Json
    $need = @('id', 'name', 'title', 'departmentId', 'departmentName')
    $miss = $need | Where-Object { $null -eq $docs[0].$_ }
    if ($miss.Count -eq 0) {
        Ok "医生列表：/api/doctors?deptId=$depId 字段齐全（含 JOIN 出的 departmentName）"
    } else {
        Bad "医生列表缺少字段：$($miss -join ', ')"
    }

    if ($docs.Count -gt 0) {
        $docId = $docs[0].id
        $sc = & curl.exe -s "$BaseUrl/api/schedules?doctorId=$docId&size=3" -H $auth | ConvertFrom-Json
        $need2 = @('items', 'total', 'page', 'size', 'totalPages')
        $miss2 = $need2 | Where-Object { $null -eq $sc.$_ }
        $need3 = @('id', 'doctorName', 'departmentName', 'workDate', 'period', 'totalSlots', 'remainingSlots', 'fee', 'soldOut')
        $miss3 = if ($sc.items.Count -gt 0) { $need3 | Where-Object { $null -eq $sc.items[0].$_ } } else { @() }
        if ($miss2.Count -eq 0 -and $miss3.Count -eq 0) {
            Ok "排班列表：字段齐全（含 remainingSlots 与 soldOut）"
        } else {
            Bad "排班列表缺少字段：$(($miss2 + $miss3) -join ', ')"
        }
    }
}

# 错误契约：未认证必须 401 且是 JSON（前端 axios 依赖 code 字段判断）
$unauthRaw = & curl.exe -s -i "$BaseUrl/api/departments"
$unauthCode = (& curl.exe -s -o NUL -w "%{http_code}" "$BaseUrl/api/departments")
if ($unauthCode -eq '401') {
    Ok "未认证返回 401"
} else {
    Bad "未认证应返回 401，实际 $unauthCode"
}
if (($unauthRaw -join "`n") -match '"code"') {
    Ok "401 响应体是 JSON 且含 code 字段（前端靠它判断登录态）"
} else {
    Bad "401 响应体不是预期的 JSON 结构"
}

Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue

# -------------------------------------------------------------------
Write-Host ""
Write-Host ("=" * 62) -ForegroundColor $(if ($fail -eq 0) { "Green" } else { "Red" })
if ($fail -eq 0) {
    Write-Host "  API 契约检查通过（$hint 条提示）" -ForegroundColor Green
} else {
    Write-Host "  发现 $fail 处契约不一致，$hint 条提示" -ForegroundColor Red
}
Write-Host ("=" * 62) -ForegroundColor $(if ($fail -eq 0) { "Green" } else { "Red" })
exit $(if ($fail -eq 0) { 0 } else { 1 })
