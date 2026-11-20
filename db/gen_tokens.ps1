<#
.SYNOPSIS
    为压测批量生成登录态：往 Redis 写 N 个 login:token:tk{id}，同时导出
    test/jmeter/users.csv 给 JMeter 的 CSV Data Set Config 用。

.DESCRIPTION
    【为什么不能共用同一个 token】
      秒杀 Lua 脚本里有「一人一单」（SISMEMBER seckill:user:{voucherId}）。
      500 个线程共用一个 token，只有第 1 个能拿到库存，其余 499 个都返回
      「每人限抢一张」—— 压出来的是「被拒绝得有多快」，不是秒杀链路的吞吐。

    【为什么不走 /user/code + /user/login】
      那要发 N 次验证码 + N 次登录 = 2N 次 HTTP，慢且会往库里塞注册用户。
      本项目的登录态就是 Redis 里一个 JSON 字符串（LOGIN_USER_KEY + token），
      直接写 Redis 即可，语义与登录接口完全等价：
          key   login:token:{token}
          value {"id":1,"nickName":"..."}   ← LoginInterceptor 反序列化它
          TTL   36000 秒（10 小时，与 RedisConstants.LOGIN_USER_TTL 一致）

    【为什么一次性写文件再喂给 redis-cli】
      循环调用 500 次 redis-cli，每次启动进程约 30ms，光启动就 15 秒。
      把所有命令写进一个临时文件，用管道一次性送进去，只要 0.5 秒。

.PARAMETER Count
    生成多少个用户令牌，默认 1000（库里 1005 个用户，留出余量）。

.PARAMETER Prefix
    token 前缀，默认 tk。**刻意做成可预测格式**：压测后要追某个用户的行为时，
    从 userId 反推出 token（tk123），不用翻 CSV。
    也正因为可预测，这套脚本只能用于本地压测，不要写进 README 的「快速开始」。

.PARAMETER TtlSeconds
    令牌有效期，默认 36000（10 小时，与 RedisConstants.LOGIN_USER_TTL 一致）。
    压测跨天时记得加大，否则后半程全部 401。

.PARAMETER OutFile
    CSV 输出路径，默认 test/jmeter/users.csv。相对路径按项目根目录解析。

.EXAMPLE
    .\db\gen_tokens.ps1
    .\db\gen_tokens.ps1 -Count 100 -Prefix tk -TtlSeconds 7200

.NOTES
    ⛔ 仅限本地开发 / 压测环境。生产上这等于批量伪造登录态。
    连接信息可用环境变量覆盖：DB_HOST / DB_PORT / DB_USER / DB_PASSWORD /
    REDIS_HOST / REDIS_PORT / REDIS_PASSWORD。
#>

[CmdletBinding()]
param(
    [int]$Count = 1000,
    [string]$Prefix = 'tk',
    [int]$TtlSeconds = 36000,
    [string]$OutFile = 'test/jmeter/users.csv'
)

$ErrorActionPreference = 'Stop'

# ============================================================
# 0. 定位可执行文件与连接参数（与 reset_seckill.ps1 保持同一套约定）
# ============================================================

function Find-Exe {
    param([string]$Name, [string[]]$Candidates)
    foreach ($c in $Candidates) {
        if (Test-Path $c) { return $c }
    }
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

$mysql = Find-Exe -Name 'mysql' -Candidates @(
    'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
    'C:\Program Files\MySQL\MySQL Server 8.6\bin\mysql.exe',
    'C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe',
    'D:\phpEnv\phpEnv\MySQL\bin\mysql.exe'
)
$redisCli = Find-Exe -Name 'redis-cli' -Candidates @(
    'D:\redisconfig\redis-cli.exe',
    'C:\Program Files\Redis\redis-cli.exe',
    'C:\Redis\redis-cli.exe'
)

if (-not $mysql)    { throw '找不到 mysql.exe，请把 MySQL 的 bin 目录加进 PATH，或改脚本里的候选路径' }
if (-not $redisCli) { throw '找不到 redis-cli.exe，请改脚本里的候选路径' }

$dbHost = if ($env:DB_HOST) { $env:DB_HOST } else { '127.0.0.1' }
$dbPort = if ($env:DB_PORT) { $env:DB_PORT } else { '3306' }
$dbUser = if ($env:DB_USER) { $env:DB_USER } else { 'root' }
$dbPass = if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { 'root' }

$redisHost = if ($env:REDIS_HOST) { $env:REDIS_HOST } else { '127.0.0.1' }
$redisPort = if ($env:REDIS_PORT) { $env:REDIS_PORT } else { '6379' }
$redisPass = if ($env:REDIS_PASSWORD) { $env:REDIS_PASSWORD } else { '123321' }

# 密码走配置文件而不是命令行：避免 "Using a password on the command line" 警告，
# 而该警告走 stderr，在 $ErrorActionPreference='Stop' 下会被当成终止错误。
$script:cnfPath = Join-Path $env:TEMP 'hmdp-tokens-my.cnf'
Set-Content -Path $script:cnfPath -Encoding ASCII -Value @(
    '[client]',
    "host=$dbHost",
    "port=$dbPort",
    "user=$dbUser",
    "password=$dbPass",
    'default-character-set=utf8mb4'
)

# 相对路径统一按项目根目录解析：脚本放在 db\ 下，根目录是它的上一级
$projectRoot = Split-Path -Parent $PSScriptRoot
$outPath = if ([IO.Path]::IsPathRooted($OutFile)) { $OutFile } else { Join-Path $projectRoot $OutFile }
$outDir = Split-Path -Parent $outPath
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }

Write-Host ''
Write-Host "================ 生成压测登录态（$Count 个） ================" -ForegroundColor Cyan

# ============================================================
# 1. 从库里取真实用户 id
# ============================================================
$idsRaw = & $mysql "--defaults-extra-file=$script:cnfPath" -N -B -e "SELECT id FROM hmdp.tb_user ORDER BY id LIMIT $Count" 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "查询 tb_user 失败：`n$($idsRaw -join "`n")"
}
$ids = @($idsRaw | ForEach-Object { "$_".Trim() } | Where-Object { $_ -match '^\d+$' })
if ($ids.Count -eq 0) {
    throw 'tb_user 里没查到任何用户 id。先确认应用连的是 3306 那个库（有 1005 个用户），而不是 3307 的空库。'
}
if ($ids.Count -lt $Count) {
    Write-Host ("  ⚠ 库里只有 {0} 个用户，少于请求的 {1} 个；按实际数量生成" -f $ids.Count, $Count) -ForegroundColor Yellow
}

# ============================================================
# 2. 拼 Redis 命令
# ============================================================
# 注意值必须用**单引号**包起来：
#   redis-cli 从 stdin 读命令时按 sdssplitargs 解析，双引号会被当成「引用区」剥掉，
#   SET login:token:tk1 {"id":1} 的结果会变成 {id:1}（非法 JSON），
#   然后 LoginInterceptor 反序列化失败 -> 每个请求都 401，而压测报告只会显示「错误率 100%」。
$cmdFile = Join-Path $env:TEMP 'hmdp-token-commands.txt'
$cmdLines = New-Object System.Collections.Generic.List[string]
foreach ($id in $ids) {
    $cmdLines.Add("SET login:token:$Prefix$id '{`"id`":$id}' EX $TtlSeconds")
}
Set-Content -Path $cmdFile -Value $cmdLines -Encoding ASCII

# ============================================================
# 3. 一次性灌进 Redis
# ============================================================
# Windows 版 redis-cli：-h/-p 与值必须分开传；老版本不支持 --no-auth-warning，
# 用了 -a 必往 stderr 打警告，这里临时放宽错误策略再滤掉警告行。
$redisArgv = @('-h', $redisHost, '-p', $redisPort)
if ($redisPass) { $redisArgv += @('-a', $redisPass) }

$prev = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$out = Get-Content $cmdFile | & $redisCli @redisArgv 2>&1
$code = $LASTEXITCODE
$ErrorActionPreference = $prev

if ($code -ne 0) {
    throw "写入 Redis 失败：`n$($out -join "`n")"
}
$okCount = @($out | Where-Object { "$_" -eq 'OK' }).Count
Write-Host ("  Redis ✅ 写入 {0} 个令牌（响应 OK {1} 条），TTL {2}s" -f $ids.Count, $okCount, $TtlSeconds) -ForegroundColor Green
if ($okCount -ne $ids.Count) {
    Write-Host '  ⚠ 响应 OK 数与命令数不一致，请手工抽一个 key 验证：redis-cli -a *** GET login:token:tk1' -ForegroundColor Yellow
}

# ============================================================
# 4. 导出 CSV
# ============================================================
# ⚠ 必须是无 BOM 的 ASCII：
#   CSV Data Set Config 会把 BOM 当成第一个变量名的一部分，
#   表头读成 "\ufefftoken"，于是 ${token} 永远解析不到，全部请求 401/403。
$csvLines = New-Object System.Collections.Generic.List[string]
$csvLines.Add('token,userId')
foreach ($id in $ids) { $csvLines.Add("$Prefix$id,$id") }
Set-Content -Path $outPath -Value $csvLines -Encoding ASCII

# ============================================================
# 5. 抽检一个令牌，确认能被应用反序列化
# ============================================================
$probe = Get-Content $cmdFile -TotalCount 1
$probeToken = ($probe -split ' ')[1]
if ($redisPass) {
    $ErrorActionPreference = 'Continue'
    $value = & $redisCli -h $redisHost -p $redisPort -a $redisPass GET $probeToken 2>$null
    $ErrorActionPreference = $prev
} else {
    $value = & $redisCli -h $redisHost -p $redisPort GET $probeToken
}

Write-Host ("  抽样 {0} = {1}" -f $probeToken, $value) -ForegroundColor Gray

Write-Host ''
Write-Host ("CSV 已生成：{0}（{1} 行，无 BOM）" -f $outPath, ($ids.Count + 1)) -ForegroundColor Cyan
Write-Host '提示：CSV 的 token 列与 Redis 里真实存在的 key 一一对应；' -ForegroundColor Gray
Write-Host '      压测前先跑一次 .\db\reset_seckill.ps1 -Stock 100 -Reopen -Hours 2。' -ForegroundColor Gray
Write-Host ''
