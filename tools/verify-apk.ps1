# tools/verify-apk.ps1
# 构建产物静态验收测试。
#
# 用法:
#   .\tools\verify-apk.ps1 -Stage M2
#   .\tools\verify-apk.ps1 -Stage M2 -Variant release
#
# 设计要点：权限断言采用**按里程碑精确匹配**（不多不少）。
# 这样任何意外引入的权限都会被立刻发现 —— 到 M4/M5 加入无障碍、
# 悬浮窗、定位时，这条断言是防止"悄悄多要权限"的主要防线。
#
# 退出码: 0 = 全部通过；1 = 有断言失败。

param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('M1', 'M2', 'M3', 'M4', 'M5', 'M6', 'M7')]
    [string]$Stage,

    [ValidateSet('debug', 'release')]
    [string]$Variant = 'debug'
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# 各里程碑**预期**的系统权限（精确集合）。
# 新增权限必须显式更新这里，并说明来源，不允许默默通过。
# ---------------------------------------------------------------------------
$ExpectedSystemPermissions = @{
    # M1：纯骨架，无任何依赖，除 AndroidX 自动注入的自身命名空间权限外应无权限
    'M1' = @()

    # M2：引入 androidx.work（为 M3 的保活自检任务准备），WorkManager 会合并以下权限
    'M2' = @(
        'android.permission.WAKE_LOCK',
        'android.permission.ACCESS_NETWORK_STATE',
        'android.permission.RECEIVE_BOOT_COMPLETED',
        'android.permission.FOREGROUND_SERVICE'
    )

    # M3：前台服务 + 精确闹钟 + 权限向导。
    #
    # 说明：M3 的权限向导必须能引导用户走完全部 9 步，因此这里一次性声明了
    # 完整 App 所需的权限（含 M4 的悬浮窗、M5 的前台定位）。
    # 声明权限 ≠ 启用功能；真正使用它们的功能仍按里程碑落地。
    # 该集合依然是**精确匹配**，因此任何库偷偷引入的额外权限都会被抓出来。
    #
    # 特别检查项：**不得出现 ACCESS_BACKGROUND_LOCATION**
    # （PRD 定位方案 A：只在点击「立即打卡」时取一次前台位置）
    'M3' = @(
        'android.permission.WAKE_LOCK',                          # WorkManager
        'android.permission.ACCESS_NETWORK_STATE',               # WorkManager
        'android.permission.RECEIVE_BOOT_COMPLETED',             # WorkManager + 开机恢复
        'android.permission.FOREGROUND_SERVICE',                 # 前台服务
        'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',     # Android 14 前台服务类型
        'android.permission.POST_NOTIFICATIONS',                 # 常驻通知与预警
        'android.permission.SCHEDULE_EXACT_ALARM',               # 精确闹钟
        'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS', # 电池优化白名单
        'android.permission.SYSTEM_ALERT_WINDOW',                 # M4 遮挡层
        'android.permission.ACCESS_FINE_LOCATION',                # M5 打卡
        'android.permission.ACCESS_COARSE_LOCATION'               # M5 打卡
    )

    # M4：无障碍拦截 + 遮挡层。
    #
    # 关于 QUERY_ALL_PACKAGES：白名单选择器需要枚举已安装应用才能工作
    # （白名单制的本质就是"从全部应用里挑"）。Android 11+ 的包可见性限制下
    # 不声明它就只能看到自己与少数几个包。这是**有意识的产品决策**，
    # 因此从"禁用清单"移入"预期清单"，而不是绕过检查。
    'M4' = @(
        'android.permission.WAKE_LOCK',
        'android.permission.ACCESS_NETWORK_STATE',
        'android.permission.RECEIVE_BOOT_COMPLETED',
        'android.permission.FOREGROUND_SERVICE',
        'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
        'android.permission.POST_NOTIFICATIONS',
        'android.permission.SCHEDULE_EXACT_ALARM',
        'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
        'android.permission.SYSTEM_ALERT_WINDOW',
        'android.permission.ACCESS_FINE_LOCATION',
        'android.permission.ACCESS_COARSE_LOCATION',
        'android.permission.QUERY_ALL_PACKAGES'
    )

    # M5：打卡点 + GPS + 打卡解锁。
    # **刻意不新增任何权限** —— 定位权限在 M3（权限向导需要）时已声明，
    # 这里保持不变本身就是一条断言：证明 M5 没有偷偷扩大权限面。
    'M5' = @(
        'android.permission.WAKE_LOCK',
        'android.permission.ACCESS_NETWORK_STATE',
        'android.permission.RECEIVE_BOOT_COMPLETED',
        'android.permission.FOREGROUND_SERVICE',
        'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
        'android.permission.POST_NOTIFICATIONS',
        'android.permission.SCHEDULE_EXACT_ALARM',
        'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
        'android.permission.SYSTEM_ALERT_WINDOW',
        'android.permission.ACCESS_FINE_LOCATION',
        'android.permission.ACCESS_COARSE_LOCATION',
                'android.permission.QUERY_ALL_PACKAGES'
    )

    # M6：使用时长 + 锁机报告 + 通知脱敏。
    # 同样**不新增任何权限** ——「通知使用权」是 service 级声明
    # （BIND_NOTIFICATION_LISTENER_SERVICE），不出现在 uses-permission 里。
    'M6' = @(
        'android.permission.WAKE_LOCK',
        'android.permission.ACCESS_NETWORK_STATE',
        'android.permission.RECEIVE_BOOT_COMPLETED',
        'android.permission.FOREGROUND_SERVICE',
        'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
        'android.permission.POST_NOTIFICATIONS',
        'android.permission.SCHEDULE_EXACT_ALARM',
        'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
        'android.permission.SYSTEM_ALERT_WINDOW',
        'android.permission.ACCESS_FINE_LOCATION',
        'android.permission.ACCESS_COARSE_LOCATION',
        'android.permission.QUERY_ALL_PACKAGES'
    )

    # M7：打磨 + 备份 + 正式签名打包。
    # 权限集依然与 M4/M5/M6 完全一致 —— M4 到 M7 四个里程碑零新增权限。
    'M7' = @(
        'android.permission.WAKE_LOCK',
        'android.permission.ACCESS_NETWORK_STATE',
        'android.permission.RECEIVE_BOOT_COMPLETED',
        'android.permission.FOREGROUND_SERVICE',
        'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
        'android.permission.POST_NOTIFICATIONS',
        'android.permission.SCHEDULE_EXACT_ALARM',
        'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
        'android.permission.SYSTEM_ALERT_WINDOW',
        'android.permission.ACCESS_FINE_LOCATION',
        'android.permission.ACCESS_COARSE_LOCATION',
        'android.permission.QUERY_ALL_PACKAGES'
    )
}

# 尚未到达的里程碑
$UndefinedStages = @()

# 全项目**任何里程碑都不得出现**的权限（逐条说明原因）
$ForbiddenPermissions = @{
    'android.permission.ACCESS_BACKGROUND_LOCATION' =
        'PRD 定位方案 A 明确不需要后台定位；出现即说明依赖或配置写错了'
    'android.permission.PACKAGE_USAGE_STATS' =
        'PRD 已决定不做系统屏幕时间统计，使用时长由无障碍自采集'
    'android.permission.READ_CONTACTS' = '与产品功能无关'
    'android.permission.READ_SMS' = '与产品功能无关'
    'android.permission.CALL_PHONE' = '与产品功能无关'
    'android.permission.RECORD_AUDIO' = '与产品功能无关'
    'android.permission.CAMERA' = '拍照存证走系统相机 Intent，不需要自己声明 CAMERA'
    'android.permission.READ_EXTERNAL_STORAGE' = '与产品功能无关'
}

$root = Split-Path -Parent $PSScriptRoot
$apk = Join-Path $root "android\app\build\outputs\apk\$Variant\app-$Variant.apk"

$script:pass = 0
$script:fail = 0
$script:pending = @()

function Check($name, $condition, $detail) {
    if ($condition) {
        Write-Host ("  [PASS] {0}" -f $name) -ForegroundColor Green
        $script:pass++
    } else {
        Write-Host ("  [FAIL] {0}  ->  {1}" -f $name, $detail) -ForegroundColor Red
        $script:fail++
    }
}

function Pending($name, $why) {
    Write-Host ("  [PENDING] {0}  ({1})" -f $name, $why) -ForegroundColor Yellow
    $script:pending += $name
}

Write-Host "=== APK 静态验收测试 ($Variant / $Stage) ===" -ForegroundColor Cyan
Write-Host "目标: $apk`n"

if ($UndefinedStages -contains $Stage) {
    Write-Host "阶段 $Stage 的预期权限集合尚未定义。" -ForegroundColor Red
    Write-Host "请先在本脚本的 ExpectedSystemPermissions 中补充后再运行。" -ForegroundColor Red
    Write-Host "（这是刻意设计的：不给未定义的阶段放行。）" -ForegroundColor DarkGray
    exit 1
}

Check 'APK 文件存在' (Test-Path $apk) $apk
if (-not (Test-Path $apk)) {
    Write-Host "`nAPK 不存在，无法继续断言。" -ForegroundColor Red
    exit 1
}

$sizeMb = [math]::Round((Get-Item $apk).Length / 1MB, 2)
Check 'APK 大小合理 (1MB - 80MB)' ((Get-Item $apk).Length -gt 1MB -and (Get-Item $apk).Length -lt 80MB) "实际 $sizeMb MB"
Write-Host "        APK 大小: $sizeMb MB`n"

$aapt2 = Join-Path $env:ANDROID_HOME 'build-tools\35.0.0\aapt2.exe'
Check 'aapt2 可用' (Test-Path $aapt2) $aapt2
if (-not (Test-Path $aapt2)) { exit 1 }

$badging = & $aapt2 dump badging $apk 2>&1 | Out-String

Check '包含 package 声明' ($badging -match "package: name='([^']+)'") '未能解析 package 行'
if ($badging -match "package: name='([^']+)'") {
    $pkg = $Matches[1]
    $expectedPkg = if ($Variant -eq 'debug') { 'com.zilv.app.debug' } else { 'com.zilv.app' }
    Check "包名 = $expectedPkg" ($pkg -eq $expectedPkg) "实际 $pkg"
    Write-Host "        包名: $pkg"
}

if ($badging -match "versionCode='(\d+)'") {
    Check 'versionCode = 22' ($Matches[1] -eq '22') "实际 $($Matches[1])"
}
if ($badging -match "versionName='([^']+)'") {
    Check 'versionName = 2.15' ($Matches[1] -eq '2.15') "实际 $($Matches[1])"
}
if ($badging -match "sdkVersion:'(\d+)'") {
    Check 'minSdkVersion = 26' ($Matches[1] -eq '26') "实际 $($Matches[1])"
}
if ($badging -match "targetSdkVersion:'(\d+)'") {
    Check 'targetSdkVersion = 35' ($Matches[1] -eq '35') "实际 $($Matches[1])"
}

Check '存在可启动 Activity' ($badging -match 'launchable-activity:') '未找到 launchable-activity'
if ($badging -match "launchable-activity: name='([^']+)'") {
    Check '启动 Activity = com.zilv.app.MainActivity' ($Matches[1] -eq 'com.zilv.app.MainActivity') "实际 $($Matches[1])"
}

Check '应用标签为「自律」' ($badging -match "application-label:'") '未找到 application-label'

$abis = [regex]::Matches($badging, "native-code: '([^']+)'")
if ($abis.Count -gt 0) {
    $abiList = $abis[0].Groups[1].Value
    $badAbis = @($abiList -split "' '" | Where-Object { $_ -notin @('arm64-v8a', 'armeabi-v7a') })
    Check '原生库 ABI 仅含 arm64-v8a / armeabi-v7a' ($badAbis.Count -eq 0) "发现: $($badAbis -join ', ')"
} else {
    Write-Host "  [INFO] APK 内无原生库" -ForegroundColor DarkGray
}

# ---- M4 起：无障碍服务声明检查 ----
# 无障碍服务必须满足三个条件才能被系统绑定：service 上声明
# BIND_ACCESSIBILITY_SERVICE 权限、有 AccessibilityService 的 intent-filter、
# 挂上 meta-data 指向配置文件。任何一项缺失都会导致"开关打不开"或"服务不起作用"。
if ($Stage -in @('M4', 'M5', 'M6', 'M7')) {
    $manifestXml = & $aapt2 dump xmltree $apk --file AndroidManifest.xml 2>&1 | Out-String

    Check '声明了 LockAccessibilityService' `
        ($manifestXml -match 'LockAccessibilityService') `
        'AndroidManifest 中找不到无障碍服务'

    Check '无障碍服务声明了 BIND_ACCESSIBILITY_SERVICE' `
        ($manifestXml -match 'BIND_ACCESSIBILITY_SERVICE') `
        '缺少该权限声明，系统不会绑定服务'

    Check '无障碍服务挂载了配置文件 meta-data' `
        ($manifestXml -match 'android\.accessibilityservice') `
        '缺少 meta-data，服务配置不生效'

    # 前台服务类型：aapt2 把 foregroundServiceType 输出为位掩码整数，
    # **不是**字面量 "specialUse"。SPECIAL_USE = 0x40000000。
    # 解析实际位值比字符串匹配更严格 —— 能发现"类型写错/写多了"的情况。
    $fgsMatch = [regex]::Match($manifestXml, 'foregroundServiceType\(0x01010599\)=0x([0-9a-fA-F]+)')
    Check '前台服务声明了 foregroundServiceType' `
        $fgsMatch.Success `
        '前台服务缺少 foregroundServiceType，Android 14 上启动会崩溃'

    if ($fgsMatch.Success) {
        $mask = [Convert]::ToInt64($fgsMatch.Groups[1].Value, 16)
        $specialUse = 0x40000000L
        Check '前台服务类型含 SPECIAL_USE (0x40000000)' `
            (($mask -band $specialUse) -ne 0) `
            ("实际掩码 0x{0:X}" -f $mask)

        # 除 specialUse 外不应声明其它类型：少要权限、少受系统限制
        $extra = $mask -band (-bnot $specialUse)
        Check '前台服务类型仅含 SPECIAL_USE' `
            ($extra -eq 0) `
            ("多出了其它类型，实际掩码 0x{0:X}" -f $mask)
    }

    Check '声明了 specialUse 子类型属性' `
        ($manifestXml -match 'PROPERTY_SPECIAL_USE_FGS_SUBTYPE') `
        '缺少 PROPERTY_SPECIAL_USE_FGS_SUBTYPE，Play/系统无法识别用途'
# ---- M6 起：通知监听服务声明检查 ----
# 「通知使用权」是 service 级声明，不会出现在 uses-permission 里，
# 因此必须单独检查 —— 否则"忘了声明"会表现为"脱敏永远不生效"。
if ($Stage -in @('M6', 'M7')) {
    if (-not $manifestXml) {
        $manifestXml = & $aapt2 dump xmltree $apk --file AndroidManifest.xml 2>&1 | Out-String
    }

    Check '声明了 LockNotificationListenerService' `
        ($manifestXml -match 'LockNotificationListenerService') `
        'AndroidManifest 中找不到通知监听服务'

    Check '通知监听服务声明了 BIND_NOTIFICATION_LISTENER_SERVICE' `
        ($manifestXml -match 'BIND_NOTIFICATION_LISTENER_SERVICE') `
        '缺少该权限声明，系统不会绑定监听器，脱敏永远不生效'
}
}

# ---- 权限：精确集合匹配 ----
# 应用自身命名空间下的权限（AndroidX 自动注入的签名级权限）单独区分
$allPerms = @([regex]::Matches($badging, "uses-permission: name='([^']+)'") | ForEach-Object { $_.Groups[1].Value })
$ownNamespace = @($allPerms | Where-Object { $_.StartsWith('com.zilv.app') })
$actualSystem = @($allPerms | Where-Object { -not $_.StartsWith('com.zilv.app') } | Sort-Object)
$expected = @($ExpectedSystemPermissions[$Stage] | Sort-Object)

Write-Host ""
Write-Host "  权限核对（$Stage）" -ForegroundColor DarkGray
Write-Host "    预期: $(if ($expected.Count -eq 0) { '(无)' } else { $expected -join ', ' })" -ForegroundColor DarkGray
Write-Host "    实际: $(if ($actualSystem.Count -eq 0) { '(无)' } else { $actualSystem -join ', ' })" -ForegroundColor DarkGray

$unexpected = @($actualSystem | Where-Object { $_ -notin $expected })
$missing = @($expected | Where-Object { $_ -notin $actualSystem })

Check '未出现预期之外的系统权限' ($unexpected.Count -eq 0) "多出: $($unexpected -join ', ')"
Check '预期系统权限全部存在' ($missing.Count -eq 0) "缺少: $($missing -join ', ')"

# 全项目禁用的权限（每个里程碑都检查）
foreach ($forbidden in $ForbiddenPermissions.Keys | Sort-Object) {
    Check "不得出现 $($forbidden.Split('.')[-1])" ($forbidden -notin $actualSystem) $ForbiddenPermissions[$forbidden]
}

$nonAndroid = @($actualSystem | Where-Object { $_ -notmatch '^android\.permission\.' })
Check '未声明任何非 android.permission 前缀的系统权限' ($nonAndroid.Count -eq 0) "发现: $($nonAndroid -join ', ')"

if ($ownNamespace.Count -gt 0) {
    Write-Host "    (应用自身命名空间权限，预期内: $($ownNamespace -join ', '))" -ForegroundColor DarkGray
}

Write-Host ""
Pending 'APK 安装到真机并启动' '当前无设备连接（adb devices 为空）'
Pending '真机渲染截图确认' '当前无设备连接'

# ---- M7：正式签名产物验收 ----
# 这是 M7 的核心交付物，必须真的检查签名与包名，
# 而不是"构建成功就算过"。
if ($Stage -eq 'M7') {
    Write-Host ""
    Write-Host "--- 正式签名产物（release）---" -ForegroundColor Cyan

    $releaseApk = Join-Path (Split-Path $apk -Parent | Split-Path -Parent) 'release\app-release.apk'

    Check 'release APK 存在' (Test-Path $releaseApk) "未找到: $releaseApk"

    if (Test-Path $releaseApk) {
        $btRoot = Join-Path $env:ANDROID_HOME 'build-tools'
        $btDir = (Get-ChildItem $btRoot -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
        $apksigner = Join-Path $btDir 'apksigner.bat'

        Check 'apksigner 可用' (Test-Path $apksigner) '找不到 apksigner'

        if (Test-Path $apksigner) {
            $signOut = & $apksigner verify --print-certs --verbose $releaseApk 2>&1 | Out-String

            Check 'release APK 签名有效' ($signOut -match 'Verification succeeded|Verifies') '签名校验失败'
            Check '签名方案至少含 v2（Android 7+ 要求）' ($signOut -match 'v2 scheme \(APK Signature Scheme v2\): true') 'v2 签名缺失'
            Check '签名者数量为 1' ($signOut -match 'Number of signers: 1') '签名者数量异常'
            Check '签名证书是本项目的（CN=Zilv）' ($signOut -match 'CN=Zilv') '签名证书不是本项目生成的'
        }

        $relBadging = & $aapt2 dump badging $releaseApk 2>&1 | Out-String

        # release 绝不能带 .debug 后缀，否则会被当成另一个应用、无法覆盖升级
        Check 'release 包名 = com.zilv.app（不带 .debug）' `
            ($relBadging -match "package: name='com\.zilv\.app' ") `
            'release 包名带上了 debug 后缀，会导致无法覆盖升级'

        $relPerms = [regex]::Matches($relBadging, "uses-permission: name='([^']+)'") |
            ForEach-Object { $_.Groups[1].Value } |
            Where-Object { $_ -like 'android.permission.*' } |
            Sort-Object

        $permsMatch = (($relPerms -join ',') -eq (($expected | Sort-Object) -join ','))
        Check 'release 权限集与 debug 完全一致' $permsMatch `
            "release 实际: $($relPerms -join ', ')"
    }

    Pending 'APK 安装到已连接设备并完成首启' '当前无设备连接'
}

Write-Host ""
Write-Host ("=== 结果: {0} 通过, {1} 失败, {2} 待真机验证 ===" -f $script:pass, $script:fail, $script:pending.Count) -ForegroundColor Cyan

if ($script:fail -gt 0) { exit 1 }
exit 0
