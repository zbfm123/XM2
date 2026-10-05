# ===================================================================
# 文档一致性检查（docs freshness check）
#
# 用法（在项目根目录）：
#   powershell -ExecutionPolicy Bypass -File .\scripts\check-docs.ps1
#
# 它检查四类"文档与代码不一致"的问题——这几类在开发过程中**反复出现过**，
# 每次都靠人工核对发现，所以固化成脚本：
#
#   1. 文档里写的测试数量 与 surefire 报告里的实际数量是否一致
#   2. README 里引用的脚本文件是否真的存在（避免"文档说有个脚本，但没写"）
#   3. 所有 .ps1 是否带 UTF-8 BOM（无 BOM 会让 5.1 按 GBK 解码并破坏语法）
#   4. 文档里是否残留"待做/待安装/尚未"这类可能已过时的措辞
#
# 检查 4 只是**提示**（不是失败）：那类词在"历史记录"里出现是正常的，
# 但需要人看一眼是不是该更新了。
#
# ⚠️ 本文件必须带 UTF-8 BOM（见 push.ps1 的 BOM 守卫）。
# ===================================================================
param(
    # 提示类检查（第 4 类）也当成失败
    [switch]$Strict
)

$ErrorActionPreference = "Continue"
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location $projectRoot

[Console]::OutputEncoding = [Text.Encoding]::UTF8

$problems = 0
$hints = 0

function Section($t) {
    Write-Host ""
    Write-Host ("-" * 62) -ForegroundColor Cyan
    Write-Host "  $t" -ForegroundColor Cyan
    Write-Host ("-" * 62) -ForegroundColor Cyan
}

function Fail($msg) {
    Write-Host "  [X] $msg" -ForegroundColor Red
    $script:problems++
}

function Ok($msg) {
    Write-Host "  [OK] $msg" -ForegroundColor Green
}

function Hint($msg) {
    Write-Host "  [!] $msg" -ForegroundColor Yellow
    $script:hints++
}

# -------------------------------------------------------------------
Section "1. 测试数量一致性"

# 实际数量：从 surefire 报告统计
$reportDir = "target\surefire-reports"
$actualTests = $null
if (Test-Path $reportDir) {
    $sum = 0
    Get-ChildItem $reportDir -Filter "*.xml" -ErrorAction SilentlyContinue | ForEach-Object {
        try {
            [xml]$doc = Get-Content $_.FullName
            $sum += [int]$doc.testsuite.tests
        } catch { }
    }
    if ($sum -gt 0) { $actualTests = $sum }
}

if ($null -eq $actualTests) {
    Hint "找不到 surefire 报告（先跑一次 mvn test），跳过数量核对"
} else {
    Write-Host "  实际测试数（surefire 报告）：$actualTests"

    # ⚠️ 只匹配"总数"级别的声明，避免误报分类计数。
    #    第一版用的是 (\d+)\s*个[，,]?\s*(测试|全绿|用例)，
    #    结果把"验收 A-08 已通过：23 个测试"（T-005 的分类计数）也当成总数据警了。
    #    扫描器的价值取决于它有多可信——误报多了人就会忽略它。
    $claimPattern = '(\d+)\s*个[，,]?\s*(?:测试)?\s*(?:全绿|合计|\*\*合计\*\*)'
    $mismatch = @()

    # ⚠️ 只扫项��根目录一次（docs 是它的子目录），否则 docs 会被扫两遍、提示重复
    Get-ChildItem "." -Recurse -Filter "*.md" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '\\target\\|\\node_modules\\|\\.git\\' } |
        ForEach-Object {
            $file = $_
            $lineNo = 0
            Get-Content $file.FullName -Encoding UTF8 | ForEach-Object {
                $lineNo++
                foreach ($m in [regex]::Matches($_, $claimPattern)) {
                    $n = [int]$m.Groups[1].Value
                    # 只关心"看起来像总数"的量级（避免把"5 个科室"也算进来）
                    if ($n -ge 20 -and $n -ne $actualTests) {
                        $mismatch += [pscustomobject]@{
                            File = $file.Name
                            Line = $lineNo
                            Claimed = $n
                            Text = $_.Trim()
                        }
                    }
                }
            }
        }

    if ($mismatch.Count -eq 0) {
        Ok "文档里的测试数量与实测一致（$actualTests）"
    } else {
        foreach ($mm in $mismatch) {
            # 历史记录里出现旧数字是正常的（例如"69 → 98 个"），所以只提示
            Hint ("{0}:{1} 提到 {2} 个（实测 {3}）：{4}" -f $mm.File, $mm.Line, $mm.Claimed, $actualTests, $mm.Text)
        }
        Write-Host "      说明：历史记录里出现旧数字是正常的，请确认那处是否**当前状态**。" -ForegroundColor Gray
    }
}

# -------------------------------------------------------------------
Section "2. README 引用的脚本是否存在"

# 从 README 里抓 `.\xxx.ps1` 或 `.\scripts\xxx.ps1` 形式的引用
$refs = @()
$readme = "README.md"
if (Test-Path $readme) {
    $content = Get-Content $readme -Raw -Encoding UTF8
    foreach ($m in [regex]::Matches($content, '`?\.\\(scripts\\)?([A-Za-z0-9\-]+\.ps1)`?')) {
        $rel = if ($m.Groups[1].Success) { "scripts\$($m.Groups[2].Value)" } else { $m.Groups[2].Value }
        if ($refs -notcontains $rel) { $refs += $rel }
    }
}

if ($refs.Count -eq 0) {
    Hint "README 里没抓到脚本引用（可能写法变了）"
} else {
    foreach ($r in $refs) {
        if (Test-Path $r) { Ok "$r 存在" } else { Fail "README 引用了 $r，但文件不存在" }
    }
}

# -------------------------------------------------------------------
Section "3. 所有 .ps1 的 UTF-8 BOM"

$ps1 = Get-ChildItem -Recurse -Filter "*.ps1" -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch '\\target\\|\\node_modules\\|\\.git\\' }

if ($ps1.Count -eq 0) {
    Hint "没找到任何 .ps1"
} else {
    $noBom = @()
    foreach ($f in $ps1) {
        $b = [IO.File]::ReadAllBytes($f.FullName)
        $hasBom = ($b.Length -ge 3 -and $b[0] -eq 0xEF -and $b[1] -eq 0xBB -and $b[2] -eq 0xBF)
        if (-not $hasBom) { $noBom += $f.FullName.Replace("$projectRoot\", "") }
    }
    if ($noBom.Count -eq 0) {
        Ok "$($ps1.Count) 个 .ps1 全部带 BOM"
    } else {
        foreach ($n in $noBom) { Fail "$n 缺少 UTF-8 BOM（5.1 会按 GBK 解码中文并破坏语法）" }
        Write-Host "      修复：Get-ChildItem -Recurse -Filter *.ps1 | ForEach-Object {" -ForegroundColor Gray
        Write-Host "              `$t = [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes(`$_))" -ForegroundColor Gray
        Write-Host "              [IO.File]::WriteAllText(`$_, `$t, [Text.UTF8Encoding]::new(`$true)) }" -ForegroundColor Gray
    }
}

# -------------------------------------------------------------------
Section "4. 可能过时的措辞（提示）"

$staleWords = @("待安装", "尚未开始", "待做", "T-0\d\d 待", "还没有")
$hits = @()

Get-ChildItem "docs" -Recurse -Filter "*.md" -ErrorAction SilentlyContinue | ForEach-Object {
    $file = $_
    $lineNo = 0
    Get-Content $file.FullName -Encoding UTF8 | ForEach-Object {
        $lineNo++
        foreach ($w in $staleWords) {
            if ($_ -match $w) {
                $hits += [pscustomobject]@{
                    File = $file.Name; Line = $lineNo; Word = $w; Text = $_.Trim()
                }
                break
            }
        }
    }
}

if ($hits.Count -eq 0) {
    Ok "没发现可能过时的措辞"
} else {
    foreach ($h in $hits) {
        Hint ("{0}:{1} 含「{2}」：{3}" -f $h.File, $h.Line, $h.Word, $h.Text)
    }
    Write-Host "      说明：这些词出现在**历史记录**里是正常的；请确认那处描述的是当前状态。" -ForegroundColor Gray
}

# -------------------------------------------------------------------
Write-Host ""
Write-Host ("=" * 62) -ForegroundColor $(if ($problems -eq 0) { "Green" } else { "Red" })
if ($problems -eq 0) {
    Write-Host "  文档一致性检查通过（$hints 条提示供人工确认）" -ForegroundColor Green
} else {
    Write-Host "  发现 $problems 处**不一致**，$hints 条提示" -ForegroundColor Red
}
Write-Host ("=" * 62) -ForegroundColor $(if ($problems -eq 0) { "Green" } else { "Red" })

if ($Strict -and $hints -gt 0) {
    Write-Host "-Strict：提示也视为失败" -ForegroundColor Yellow
    exit 1
}
exit $(if ($problems -eq 0) { 0 } else { 1 })
