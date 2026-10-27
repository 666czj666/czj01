# tools/run-tests.ps1
# 每个阶段统一使用的测试运行器。
#
# 用法:
#   .\tools\run-tests.ps1                  # 运行 JVM 单元测试（无需设备）
#   .\tools\run-tests.ps1 -Rerun           # 强制重跑全部任务
#   .\tools\run-tests.ps1 -Clean           # 先 gradle clean
#
# 退出码: 0 = 全部通过；1 = 有失败/错误，或构建失败。
#
# 说明：这里运行的是 Gradle 的 testDebugUnitTest（标准 JUnit 任务），
# 没有替换测试框架、没有跳过任何用例、没有放宽任何断言。

param(
    [switch]$Rerun,
    [switch]$Clean
)

# 本文件含中文，必须以 UTF-8 with BOM 保存，否则 PowerShell 5.1 会按 GBK 解析。
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$androidDir = Join-Path $root 'android'

# 载入环境
. (Join-Path $PSScriptRoot 'env.ps1') | Out-Null

Push-Location $androidDir
try {
    if ($Clean) {
        Write-Host '=== gradle clean ===' -ForegroundColor Cyan
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        cmd /c 'gradle clean > nul 2>&1'
        $ErrorActionPreference = $prevEap
        Write-Host "clean 退出码: $LASTEXITCODE"
    }

    $gradleArgs = @('testDebugUnitTest')
    if ($Rerun) { $gradleArgs += '--rerun-tasks' }
    $gradleCmd = 'gradle ' + ($gradleArgs -join ' ')

    $logFile = Join-Path $root '.cache\unit-tests.log'
    New-Item -ItemType Directory -Force (Split-Path $logFile) | Out-Null

    # ⚠️ 必须先删除上一轮的测试报告。
    # 否则一旦编译失败、测试任务根本没执行，脚本会读到**陈旧的 XML**，
    # 把上一轮的结果当成这一轮的结果汇报（可能掩盖真实失败）。
    $staleResults = Join-Path $androidDir 'app\build\test-results\testDebugUnitTest'
    $staleReport = Join-Path $androidDir 'app\build\reports\tests\testDebugUnitTest'
    Remove-Item $staleResults -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item $staleReport -Recurse -Force -ErrorAction SilentlyContinue

    Write-Host "=== $gradleCmd ===" -ForegroundColor Cyan
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    # 通过 cmd /c 执行并把所有输出重定向到文件。
    # 原因：Gradle 会往 stderr 写无害警告（如 "Couldn't open current thread, error = 5"），
    # 在 $ErrorActionPreference='Stop' 下 PowerShell 会把它当成终止错误而中断脚本。
    # 另外不要用 $args —— 它是 PowerShell 的自动变量。
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    cmd /c "$gradleCmd > `"$logFile`" 2>&1"
    $gradleExit = $LASTEXITCODE
    $ErrorActionPreference = $prevEap
    $sw.Stop()
    Write-Host ("gradle 退出码: {0}   用时: {1}s" -f $gradleExit, [math]::Round($sw.Elapsed.TotalSeconds))

    # ---- 解析测试报告 ----
    # 报告是 UTF-8；必须显式按 UTF-8 读取，否则中文用例名会被按 GBK 解析成非法 XML。
    $resultsDir = Join-Path $androidDir 'app\build\test-results\testDebugUnitTest'
    $xmlFiles = @()
    if (Test-Path $resultsDir) {
        $xmlFiles = @(Get-ChildItem -Path $resultsDir -Filter '*.xml' -Recurse -ErrorAction SilentlyContinue)
    }

    if ($xmlFiles.Count -eq 0) {
        Write-Host ''
        Write-Host '没有产出测试报告 —— 任务未执行到测试阶段。' -ForegroundColor Red
        Write-Host '--- 日志关键行 ---' -ForegroundColor DarkGray
        Get-Content $logFile -ErrorAction SilentlyContinue |
            Select-String -Pattern '^e: |FAILED|error:|ClassNotFound|Exception|Caused by|Unresolved reference' |
            Select-Object -First 20 |
            ForEach-Object { Write-Host "  $($_.Line.Trim())" -ForegroundColor DarkGray }
        exit 1
    }

    $total = 0; $failures = 0; $errors = 0; $skipped = 0
    $failureDetail = New-Object System.Collections.ArrayList
    $suiteLines = New-Object System.Collections.ArrayList

    foreach ($f in $xmlFiles) {
        $raw = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
        $doc = New-Object System.Xml.XmlDocument
        $doc.LoadXml($raw)
        $suite = $doc.DocumentElement

        $t = [int]$suite.GetAttribute('tests')
        $fl = [int]$suite.GetAttribute('failures')
        $er = [int]$suite.GetAttribute('errors')
        $sk = [int]$suite.GetAttribute('skipped')
        $total += $t; $failures += $fl; $errors += $er; $skipped += $sk

        $null = $suiteLines.Add(("  {0,-58} 用例 {1,3}  失败 {2}  错误 {3}  跳过 {4}" -f `
            $suite.GetAttribute('name'), $t, $fl, $er, $sk))

        foreach ($tc in $suite.SelectNodes('testcase')) {
            $bad = $tc.SelectSingleNode('failure')
            if (-not $bad) { $bad = $tc.SelectSingleNode('error') }
            if ($bad) {
                $name = $tc.GetAttribute('name')
                $cls = $tc.GetAttribute('classname')
                $msg = $bad.GetAttribute('message')
                $null = $failureDetail.Add("  $cls`n    :: $name`n    -> $msg")
            }
        }
    }

    Write-Host ''
    Write-Host '=== 测试套件明细 ===' -ForegroundColor Cyan
    $suiteLines | ForEach-Object { Write-Host $_ }

    Write-Host ''
    Write-Host '=== 汇总 ===' -ForegroundColor Cyan
    Write-Host ("  用例总数 {0}   失败 {1}   错误 {2}   跳过 {3}" -f $total, $failures, $errors, $skipped)

    if ($failureDetail.Count -gt 0) {
        Write-Host ''
        Write-Host '=== 失败明细 ===' -ForegroundColor Red
        $failureDetail | ForEach-Object { Write-Host $_ -ForegroundColor Red }
    }

    if ($skipped -gt 0) {
        Write-Host ''
        Write-Host "警告: 有 $skipped 个用例被跳过。按项目约定，除有明确书面说明外不允许跳过用例。" -ForegroundColor Yellow
    }

    if ($total -eq 0) {
        Write-Host ''
        Write-Host '测试用例数为 0 —— 视为失败（阶段必须带有有效测试）。' -ForegroundColor Red
        exit 1
    }

    if ($failures -gt 0 -or $errors -gt 0 -or $gradleExit -ne 0) {
        Write-Host ''
        Write-Host '结果: 失败' -ForegroundColor Red
        exit 1
    }

    Write-Host ''
    Write-Host '结果: 全部通过' -ForegroundColor Green
    exit 0
}
finally {
    Pop-Location
}
