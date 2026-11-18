<#
.SYNOPSIS
    秒杀链路数据重置：把一次压测/联调跑完后的「已售订单 + 库存 + 死信 + 队列」
    恢复到干净基线，让同一张券可以反复压、结果可对比。

.DESCRIPTION
    【为什么必须三处一起重置】
      · MySQL 的 tb_voucher_order              是「已卖出」的事实
      · Redis 的 seckill:stock:{id}           才是秒杀时的权威库存
                                              （DB 里的 stock 是消费端落库后的滞后副本）
      · Redis 的 seckill:user:{id}           是一人一单的已购集合
      · Redis 的 seckill:meta:{id}           是券时间窗缓存，不删会读到旧时间窗
      · tb_seckill_dead_letter + MQ 队列      可能还有未补偿的死信与积压消息
    只清 MySQL 不清 Redis，下一次秒杀仍读到旧的 stock 与旧的已购集合，
    表面「重置了」，实际压测数据全是错的 —— 这是本项目最容易踩的坑。

    【脚本不猜初始库存】
    DB 里现有的 stock 是历史残留，不等于业务初始值。本项目种子数据就有
    「5 条订单 + stock 仍是 98」这种不自洽状态，任何反推（stock + 订单数）
    都会算错。所以初始库存必须由人显式传进来。

.PARAMETER VoucherId
    要重置的秒杀券 id，默认 10。

.PARAMETER Stock
    恢复到的初始库存，默认 100。**必须自己核对**，脚本不做任何推断。
    补充事实：src/main/resources/db/hmdp.sql 里 tb_seckill_voucher 只有建表语句、
    没有任何 INSERT（「Records of tb_seckill_voucher」区块是空的），
    也就是说库里现有的券是手工建的，初始库存无处可查，只能由人给定。

.PARAMETER Reopen
    把时间窗改成「1 分钟前 ~ N 天后」。不加上去，重置后券可能仍是过期状态，
    压测会全部返回「秒杀已结束」，一条成功链路都压不出来。

.PARAMETER Days
    配合 -Reopen 使用，活动持续天数，默认 7。

.PARAMETER Hours
    配合 -Reopen 使用，活动持续小时数，默认 0。
    -Days 是整数天，冒烟想要「1 小时窗口」够不着，所以补了这个参数。
    两个都写就是相加（-Days 1 -Hours 12 = 36 小时）；
    只写 -Hours 时默认的 7 天会让位（-Hours 1 就是 1 小时，不是 169 小时）。

.PARAMETER CleanDlqMarkers
    连带清掉 Redis 里所有 seckill:dlq:done:* 与 lock:seckill:dlq:replay:*。
    这些标记按 orderId 维度存放，脚本无法判断属于哪张券，所以做成显式开关。
    单券开发环境建议加上；多券共用的环境慎用（会放开其它券的死信去重）。

.PARAMETER SkipMq
    跳过 RabbitMQ 队列清空。

.PARAMETER DryRun
    只做只读体检并打印将要执行的动作，不写入任何数据。**第一次务必先跑这个。**

.EXAMPLE
    # 先看现状与会执行什么，不动数据
    .\db\reset_seckill.ps1 -DryRun

    # 真正重置：库存回到 100，并把活动窗口开 7 天
    .\db\reset_seckill.ps1 -Stock 100 -Reopen

    # 小规模冒烟：只开 1 小时窗口
    .\db\reset_seckill.ps1 -Stock 100 -Reopen -Hours 1

.NOTES
    ⛔ 仅限本地开发 / 压测环境。
    生产执行会造成「已售订单被删、库存回滚」，属于数据事故。
    连接信息可用环境变量覆盖：DB_HOST / DB_PORT / DB_USER / DB_PASSWORD /
    REDIS_HOST / REDIS_PORT / REDIS_PASSWORD / MQ_HOST / MQ_PORT。
#>

[CmdletBinding()]
param(
    [long]$VoucherId = 10,
    [int]$Stock = 100,
    [switch]$Reopen,
    [int]$Days = 7,
    [int]$Hours = 0,
    [switch]$CleanDlqMarkers,
    [switch]$SkipMq,
    [switch]$DryRun
)

# 【为什么要有这段】-Days 是 [int]，写 -Days 0.04 会被 PowerShell 静默取整成 0，
# 得到的窗口是 [NOW()-1min, NOW()]，压测刚开跑就「秒杀已结束」，非常难查。
# 所以小时维度单独开一个参数，并且只在用户没显式给 -Days 时才让默认值让位。
if ($PSBoundParameters.ContainsKey('Hours') -and -not $PSBoundParameters.ContainsKey('Days')) {
    $Days = 0
}
$windowHours = $Days * 24 + $Hours
if ($Reopen -and $windowHours -le 0) {
    throw '-Reopen 需要一个大于 0 的活动时长，请给 -Days 或 -Hours'
}

$ErrorActionPreference = 'Stop'

# ============================================================
# 0. 连接与工具定位
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

# 为什么探测而不是写死路径：这段脚本要能在别的机器上直接跑，
# 而 Windows 上 mysql.exe / redis-cli.exe 的落点每家都不一样。
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
$dbName = 'hmdp'

# 【为什么用 defaults-extra-file 而不是 -uroot -proot】
#   1) 密码写在命令行会触发 "Using a password on the command line interface can be insecure"
#      警告。该警告走 stderr，而本脚本开头设了 $ErrorActionPreference='Stop'，
#      PowerShell 会把它当成 NativeCommandError 直接中断脚本 —— 这是 PowerShell 的经典坑。
#   2) 密码出现在命令行里，同机任何进程用 Win32_Process 都能读到。
#   项目里 db\seckill_dead_letter.sql 的头部注释用的也是这个做法，保持一致。
$script:cnfPath = Join-Path $env:TEMP 'hmdp-reset-my.cnf'
$cnfLines = @(
    '[client]',
    "host=$dbHost",
    "port=$dbPort",
    "user=$dbUser",
    "password=$dbPass",
    'default-character-set=utf8mb4'
)
Set-Content -Path $script:cnfPath -Value $cnfLines -Encoding ASCII

$redisHost = if ($env:REDIS_HOST) { $env:REDIS_HOST } else { '127.0.0.1' }
$redisPort = if ($env:REDIS_PORT) { $env:REDIS_PORT } else { '6379' }
$redisPass = if ($env:REDIS_PASSWORD) { $env:REDIS_PASSWORD } else { '123321' }

$mqHost = if ($env:MQ_HOST) { $env:MQ_HOST } else { '127.0.0.1' }
$mqApiPort = 15672          # management 插件端口，不是 AMQP 的 5672
$mqUser = 'guest'
$mqPass = 'guest'

# ============================================================
# 1. 只读辅助函数
# ============================================================

function Invoke-MySql {
    param([string]$Sql)
    # -N 去表头、-B 批处理模式（Tab 分隔、不做表格对齐，便于脚本切分）
    $argv = @("--defaults-extra-file=$script:cnfPath", '-N', '-B', '-e', $Sql, $dbName)
    $out = & $mysql @argv 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL 执行失败：$Sql`n$out"
    }
    return $out
}

function Invoke-Redis {
    param([string[]]$RedisArgs)
    # 注意两点：
    #   1) Windows 版 redis-cli 不接受 -h127.0.0.1 这种连写，-h/-p 与值必须分开传；
    #   2) 老版本 redis-cli 不支持 --no-auth-warning，用了 -a 就一定会往 stderr 打密码警告。
    #      而 $ErrorActionPreference='Stop' 会把 native 程序的 stderr 当成终止错误，
    #      所以这里临时放宽为 Continue，执行完再恢复，并把警告行从结果里滤掉。
    $argv = @('-h', $redisHost, '-p', $redisPort)
    if ($redisPass) { $argv += @('-a', $redisPass) }
    $argv += $RedisArgs

    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = & $redisCli @argv 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev

    if ($code -ne 0) {
        throw "Redis 执行失败：$($RedisArgs -join ' ')`n$out"
    }
    return @($out | Where-Object { "$_" -notmatch 'Warning' -and "$_" -ne '' })
}

function Get-MqHeaders {
    $b64 = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${mqUser}:${mqPass}"))
    return @{ Authorization = "Basic $b64" }
}

function Get-QueueMessages {
    param([string]$QueueName)
    try {
        $h = Get-MqHeaders
        $uri = "http://${mqHost}:${mqApiPort}/api/queues/%2F/$QueueName"
        $q = Invoke-RestMethod -Method Get -Uri $uri -Headers $h -TimeoutSec 5
        return [int]$q.messages
    } catch {
        return -1   # -1 = 查不到（队列不存在或 management 插件未开）
    }
}

function Clear-QueueMessages {
    param([string]$QueueName)
    $h = Get-MqHeaders
    $uri = "http://${mqHost}:${mqApiPort}/api/queues/%2F/$QueueName/contents"
    # 清空消息而不是删队列：删队列会让绑定一起消失，应用未重启前生产者会投递失败
    Invoke-RestMethod -Method Delete -Uri $uri -Headers $h -TimeoutSec 5 | Out-Null
}

# ============================================================
# 2. 重置前体检
# ============================================================

Write-Host ''
Write-Host '================ 秒杀数据重置 ================' -ForegroundColor Cyan

# 券是否存在、当前长什么样（一条记录，用 Tab 分隔，便于切分）
$row = Invoke-MySql -Sql ("SELECT voucher_id, stock, begin_time, end_time, " +
    "NOW(), IF(end_time < NOW(), 1, 0) " +
    "FROM tb_seckill_voucher WHERE voucher_id = $VoucherId")
if (-not $row) {
    throw "券 $VoucherId 不存在于 tb_seckill_voucher，无法重置"
}
$cols = ($row -join '') -split "`t"
$curStock   = $cols[1]
$curBegin   = $cols[2]
$curEnd     = $cols[3]
$isExpired  = $cols[5]

$orderCount = [int](Invoke-MySql -Sql "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = $VoucherId")
$dlqPending = [int](Invoke-MySql -Sql "SELECT COUNT(*) FROM tb_seckill_dead_letter WHERE voucher_id = $VoucherId AND status = 0")
$dlqAll     = [int](Invoke-MySql -Sql "SELECT COUNT(*) FROM tb_seckill_dead_letter WHERE voucher_id = $VoucherId")

$redisStock = Invoke-Redis -RedisArgs @('GET', "seckill:stock:$VoucherId")
if ($null -eq $redisStock) { $redisStock = '(不存在)' }
$redisUserSize = Invoke-Redis -RedisArgs @('SCARD', "seckill:user:$VoucherId")
$redisMetaExists = Invoke-Redis -RedisArgs @('EXISTS', "seckill:meta:$VoucherId")

$mqQueueMsgs = -1
$mqDlqMsgs = -1
if (-not $SkipMq) {
    $mqQueueMsgs = Get-QueueMessages -QueueName 'seckill.queue'
    $mqDlqMsgs   = Get-QueueMessages -QueueName 'seckill.dlq'
}

Write-Host ''
Write-Host "[当前状态] voucher_id = $VoucherId" -ForegroundColor Yellow
Write-Host ("  MySQL   stock={0}  订单={1} 条  死信={2} 条(待补偿 {3})" -f $curStock, $orderCount, $dlqAll, $dlqPending)
Write-Host ("  MySQL   时间窗 {0} ~ {1}" -f $curBegin, $curEnd)
if ($isExpired -eq '1') {
    Write-Host '  ⚠ 该券已过期，不加 -Reopen 重置后仍压不出成功链路' -ForegroundColor Red
}
Write-Host ("  Redis   seckill:stock:{0} = {1}" -f $VoucherId, $redisStock)
Write-Host ("  Redis   seckill:user:{0} 已购 {1} 人" -f $VoucherId, $redisUserSize)
Write-Host ("  Redis   seckill:meta:{0} 存在? {1}" -f $VoucherId, $redisMetaExists)
if (-not $SkipMq) {
    if ($mqQueueMsgs -ge 0) {
        Write-Host ("  MQ      seckill.queue = {0} 条, seckill.dlq = {1} 条" -f $mqQueueMsgs, $mqDlqMsgs)
    } else {
        Write-Host '  MQ      查询失败（management 插件未开或队列不存在），将跳过' -ForegroundColor Yellow
    }
}

# ============================================================
# 3. 组装待执行动作
# ============================================================

$windowSql = ''
if ($Reopen) {
    $windowSql = ", begin_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE), end_time = DATE_ADD(NOW(), INTERVAL $windowHours HOUR)"
}

$sqlReset = @"
START TRANSACTION;
DELETE FROM tb_voucher_order WHERE voucher_id = $VoucherId;
DELETE FROM tb_seckill_dead_letter WHERE voucher_id = $VoucherId;
UPDATE tb_seckill_voucher SET stock = $Stock$windowSql WHERE voucher_id = $VoucherId;
COMMIT;
"@

$redisPlan = @(
    @('SET', "seckill:stock:$VoucherId", "$Stock"),
    @('DEL', "seckill:user:$VoucherId"),
    @('DEL', "seckill:meta:$VoucherId"),
    @('DEL', "lock:seckill:reconcile:$VoucherId")
)

Write-Host ''
Write-Host '[将要执行]' -ForegroundColor Yellow
Write-Host '  MySQL:'
Write-Host ("    DELETE FROM tb_voucher_order        WHERE voucher_id={0}   -- 预计 {1} 行" -f $VoucherId, $orderCount)
Write-Host ("    DELETE FROM tb_seckill_dead_letter  WHERE voucher_id={0}   -- 预计 {1} 行" -f $VoucherId, $dlqAll)
if ($Reopen) {
    Write-Host ("    UPDATE tb_seckill_voucher SET stock={0}, 时间窗改为 [NOW()-1min, NOW()+{1}h] WHERE voucher_id={2}" -f $Stock, $windowHours, $VoucherId)
} else {
    Write-Host ("    UPDATE tb_seckill_voucher SET stock={0} WHERE voucher_id={1}" -f $Stock, $VoucherId)
}
Write-Host '  Redis:'
foreach ($c in $redisPlan) {
    Write-Host ("    {0}" -f ($c -join ' '))
}
if ($CleanDlqMarkers) {
    Write-Host '    SCAN 并清除 seckill:dlq:done:* / lock:seckill:dlq:replay:*'
}
if (-not $SkipMq -and $mqQueueMsgs -ge 0) {
    Write-Host '  MQ:'
    Write-Host '    清空 seckill.queue / seckill.dlq 的消息'
}

if ($DryRun) {
    Write-Host ''
    Write-Host '[DryRun] 未做任何写入。确认无误后去掉 -DryRun 再执行。' -ForegroundColor Green
    Write-Host ''
    Remove-Item $script:cnfPath -Force -ErrorAction SilentlyContinue
    return
}

# ============================================================
# 4. 执行重置
# ============================================================

Write-Host ''
Write-Host '[执行中...]' -ForegroundColor Yellow

# 4.1 MySQL —— 三条 DML 放在一个事务里，避免中途失败留下「订单删了库存没恢复」的半截状态
Invoke-MySql -Sql $sqlReset | Out-Null
Write-Host '  MySQL ✅ 订单/死信已删，库存已恢复' -ForegroundColor Green

# 4.2 Redis —— 必须覆盖三个 key，缺任何一个都会让下次秒杀读到脏数据
foreach ($c in $redisPlan) {
    Invoke-Redis -RedisArgs $c | Out-Null
}
Write-Host '  Redis ✅ stock 已设置，user/meta/lock 已清除' -ForegroundColor Green

# 4.3 可选的死信标记清理：按 orderId 维度存放，只能整体扫
if ($CleanDlqMarkers) {
    $cleared = 0
    foreach ($pattern in @('seckill:dlq:done:*', 'lock:seckill:dlq:replay:*')) {
        $cursor = '0'
        do {
            $scanOut = Invoke-Redis -RedisArgs @('SCAN', $cursor, 'MATCH', $pattern, 'COUNT', '500')
            $cursor = $scanOut[0]
            $keys = @($scanOut | Select-Object -Skip 1)
            if ($keys.Count -gt 0) {
                Invoke-Redis -RedisArgs (@('DEL') + $keys) | Out-Null
                $cleared += $keys.Count
            }
        } while ($cursor -ne '0')
    }
    Write-Host ("  Redis ✅ 已清除 {0} 个死信标记/锁" -f $cleared) -ForegroundColor Green
}

# 4.4 MQ —— 积压消息不清，应用一启动就会继续消费旧订单，压测基线直接被污染
if (-not $SkipMq -and $mqQueueMsgs -ge 0) {
    foreach ($q in @('seckill.queue', 'seckill.dlq')) {
        try {
            Clear-QueueMessages -QueueName $q
        } catch {
            Write-Host ("  MQ ⚠ {0} 清理失败：{1}" -f $q, $_.Exception.Message) -ForegroundColor Yellow
        }
    }
    Write-Host '  MQ ✅ 队列已清空' -ForegroundColor Green
}

# ============================================================
# 5. 重置后自检
# ============================================================

Write-Host ''
Write-Host '[重置后自检]' -ForegroundColor Cyan

$after = Invoke-MySql -Sql ("SELECT stock, begin_time, end_time, " +
    "IF(NOW() BETWEEN begin_time AND end_time, 1, 0), " +
    "(SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = $VoucherId), " +
    "(SELECT COUNT(*) FROM tb_seckill_dead_letter WHERE voucher_id = $VoucherId) " +
    "FROM tb_seckill_voucher WHERE voucher_id = $VoucherId")
$a = ($after -join '') -split "`t"

$redisStockAfter = Invoke-Redis -RedisArgs @('GET', "seckill:stock:$VoucherId")
$redisUserAfter  = Invoke-Redis -RedisArgs @('SCARD', "seckill:user:$VoucherId")
$redisMetaAfter  = Invoke-Redis -RedisArgs @('EXISTS', "seckill:meta:$VoucherId")

$ok = $true
$checks = @()

$checks += ,@('MySQL stock == 期望值', ($a[0] -eq "$Stock"), "实际 $($a[0]) / 期望 $Stock")
$checks += ,@('MySQL 订单已清空', ($a[4] -eq '0'), "实际 $($a[4]) 条")
$checks += ,@('MySQL 死信已清空', ($a[5] -eq '0'), "实际 $($a[5]) 条")
$checks += ,@('MySQL 活动窗口进行中', ($a[3] -eq '1'), "实际 $($a[1]) ~ $($a[2])")
$checks += ,@('Redis stock == 期望值', ($redisStockAfter -eq "$Stock"), "实际 $redisStockAfter")
$checks += ,@('Redis 已购集合为空', ($redisUserAfter -eq '0'), "实际 $redisUserAfter 人")
$checks += ,@('Redis meta 缓存已清除', ($redisMetaAfter -eq '0'), "实际 EXISTS=$redisMetaAfter")
if (-not $SkipMq -and $mqQueueMsgs -ge 0) {
    $q1 = Get-QueueMessages -QueueName 'seckill.queue'
    $q2 = Get-QueueMessages -QueueName 'seckill.dlq'
    $checks += ,@('MQ 队列已清空', (($q1 -eq 0) -and ($q2 -eq 0)), "queue=$q1, dlq=$q2")
}

foreach ($c in $checks) {
    if ($c[1]) {
        Write-Host ("  ✅ {0,-24} {1}" -f $c[0], $c[2]) -ForegroundColor Green
    } else {
        Write-Host ("  ❌ {0,-24} {1}" -f $c[0], $c[2]) -ForegroundColor Red
        $ok = $false
    }
}

Write-Host ''
# 临时的连接配置文件里是明文密码，用完必须删
Remove-Item $script:cnfPath -Force -ErrorAction SilentlyContinue

if ($ok) {
    Write-Host '重置完成，可以开始压测。' -ForegroundColor Green
    Write-Host '提示：应用若正在运行，Redis 的 stock/meta 已被脚本改写，无需重启；' -ForegroundColor DarkGray
    Write-Host '      但 @PostConstruct 预热只在启动时跑，重启一次可确保与脚本写入的值一致。' -ForegroundColor DarkGray
} else {
    Write-Host '存在未通过的检查项，先别开始压测。' -ForegroundColor Red
    exit 1
}
