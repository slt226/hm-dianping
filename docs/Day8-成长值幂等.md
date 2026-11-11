# Day 8 成长值幂等 · 动手前必读

> 这是 Day 8 唯一需要你自己写的方法：`MemberServiceImpl.addGrowth(userId, amount, reason, bizId)`
>
> 脚手架已经搭好——实体、Mapper、会员查询接口、领券的会员门槛都写完了。
> 剩下这一个方法，是会员模块里唯一有并发风险的地方，也是面试唯一会被追问的地方。
>
> 读完再动手，别边写边试。

---

## 一、你要写的这个方法

```java
boolean addGrowth(Long userId, int amount, int reason, Long bizId)
```

一句话说明业务：用户因为消费/签到/评价获得成长值，累加到 `tb_member.growth`，写一条 `tb_growth_log` 明细，跨过门槛就升级。

返回值的语义很关键：**`true` = 本次真的加了，`false` = 这笔业务已经处理过**。
不抛异常，因为"重复请求"是正常业务分支，不是错误——用户手抖点了两次下单，第二次不该是 500。

五条验收契约（接口注释里也写了）：

| # | 契约 | 怎么验 |
|---|---|---|
| 1 | 幂等：同一 `(userId, reason, bizId)` 加多次只生效一次 | 同一个 bizId 连打 10 次 |
| 2 | 并发安全：10 线程同 bizId 并发，结果等于调 1 次 | 并发同一个 bizId |
| 3 | 不丢更新：10 线程不同 bizId 各加 10，最终 +100 | 并发不同 bizId |
| 4 | 原子性：流水与成长值同生共死 | 制造异常看是否整体回滚 |
| 5 | 自动升级：跨门槛后 level 跟着变，只升不降 | 加分跨过 500 |

---

## 二、问题从哪来

不是假想的。订单服务在三种情况下会重复调用加分：

```
① 用户下单成功 → 加分 → 返回响应超时 → 用户重试下单（同一订单号）
② 订单服务调用加分接口 → 网络抖动 → 客户端超时重发
③ MQ 消费订单完成消息 → 消费者 ACK 前进程被杀 → 消息重投
```

第三种最常见，也最容易被忽略：**消息队列的投递语义是"至少一次"（at-least-once），
不是"恰好一次"**。只要业务靠 MQ 驱动，加分方法就必须自己能扛重复。

所以问题不是"怎么防止重复请求"，而是"重复请求进来了，怎么保证只加一次"。

---

## 三、三条看起来能用、实际会失效的路

### 路线 A：先查后改

```java
// ❌ 会翻倍
int cnt = growthLogMapper.selectCount(new QueryWrapper<GrowthLog>()
        .eq("user_id", userId).eq("reason", reason).eq("biz_id", bizId));
if (cnt > 0) return false;
// ... 加分
```

两个线程同时执行到 `selectCount`，**都读到 0**，然后都往下走，各加一次。
这就是经典的 TOCTOU（check-then-act）：检查和动作之间有窗口，窗口里别人能插进来。

加 `synchronized` 能救单机，但项目部署两个实例就废了——两把不同的锁保护同一份数据，
等于没锁。用 Redis 分布式锁能跨实例，但为了一个加分动作引入锁，成本和故障面都不划算：
锁超时、锁续期、Redis 挂了的降级，每一条都是新的坑。

### 路线 B：先加成长值，再判断要不要写流水

```java
// ❌ 顺序反了
memberMapper.addGrowth(userId, amount);        // 先加
try {
    growthLogMapper.insert(log);               // 再记
} catch (DuplicateKeyException e) {
    return false;                              // 重复了，但成长值已经加过了
}
```

`return false` 的时候，成长值已经加了。你以为挡住了重复请求，其实只挡住了流水。

### 路线 C：靠 `update ... where not exists`

```sql
-- ❌ MySQL 不支持在 UPDATE 的子查询里引用同一张目标表
UPDATE tb_member SET growth = growth + 10
WHERE user_id = 1
  AND NOT EXISTS (SELECT 1 FROM tb_growth_log WHERE ...);
```

即便写成 join 版本绕过去，它也只是把判断挪进了 SQL，仍然依赖"检查那一刻没有并发插入"，
在 RR 隔离级别下两个事务照样都看不到对方。**判据必须是一条带唯一约束的写操作，不能是任何读。**

---

## 四、正确方案：把判据交给数据库

表上已经有一条索引，就是为这件事准备的：

```sql
UNIQUE KEY uk_user_reason_biz (user_id, reason, biz_id)
```

它的意思是：`(用户, 原因, 业务单号)` 这个组合，全表只可能存在一行。

于是"这笔业务加过分没有"这个问题，不再需要查询——**你去插那行流水，
插得进去就是没加过，插不进去就是加过了**。判断和写入是同一条语句，中间没有窗口。

这是幂等的第一性原理：**别问"做过没有"，直接做，让数据库告诉你做没做过。**

---

## 五、三个必须自己踩过去的坑

### 坑 1：`DuplicateKeyException` 会污染事务

这是最容易让人卡一整天的地方。

如果你在 `@Transactional` 方法里这么写：

```java
try {
    growthLogMapper.insert(log);     // 撞唯一索引，抛 DuplicateKeyException
} catch (DuplicateKeyException e) {
    return false;                     // 想优雅地返回"已处理"
}
```

方法会正常返回 `false`，但事务在抛异常那一刻已经被 Spring 标记为 **rollback-only**。
方法返回后 Spring 尝试提交，发现只能回滚，于是抛出：

```
org.springframework.transaction.UnexpectedRollbackException:
Transaction rolled back because it has been marked as rollback-only
```

调用方看到的是 500，而不是你以为的 `false`。

**根因**：Spring 的声明式事务通过「异常是否穿透代理」来判断要不要回滚。
你在方法内部 catch 住了异常，异常没传出去，但事务状态已经改不了了——
它是单向的，设成 rollback-only 之后没有任何 API 能改回来。

**三条出路**，推荐第一条：

| 方案 | 做法 | 评价 |
|---|---|---|
| **① 用 `INSERT IGNORE`** | 自己写一个 `@Insert("INSERT IGNORE INTO tb_growth_log ...")`，返回 `int` | **推荐**。重复时返回 0，不产生异常，事务干净 |
| ② 拆独立事务 | 把 insert 放到 `@Transactional(propagation = REQUIRES_NEW)` 的方法里 | 能行，但嵌套事务会多占一个连接，且外层回滚时内层已提交的流水撤不掉，破坏原子性 |
| ③ 换 `ON DUPLICATE KEY UPDATE` | `INSERT ... ON DUPLICATE KEY UPDATE id = id`，看 affected rows | 可行，但 affected rows 有 1/0/2 三种含义，容易判错 |

方案 ① 的语义要清楚：`INSERT IGNORE` 返回 1 表示插入成功，返回 0 表示被忽略。
被忽略的原因可能是重复键，也可能是字段超长、类型不匹配——它把**所有**错误都降级了。
这里三个字段全由服务端填、且有非空校验，所以风险可控；换个带用户输入的场景就不能这么用。

> 你需要自己在 `GrowthLogMapper` 里加这个方法。`BaseMapper.insert()` 走的是标准 INSERT，
> 用它就必然要面对坑 1。

### 坑 2：`growth = growth + ?` 不能拆成两步

```java
// ❌ 会丢更新
Member m = memberMapper.selectOne(...);
memberMapper.update(... set growth = m.getGrowth() + amount ...);
```

两个线程都读到 100，都写 120，实际应该是 140。丢了一次。

`MemberMapper.addGrowth` 已经写成一条语句：

```sql
UPDATE tb_member SET growth = growth + #{delta} WHERE user_id = #{userId}
```

读和写在同一个行锁内完成，并发请求被 MySQL 串行化，不会丢。

### 坑 3：升级判断读的必须是更新后的值

```java
// ❌ 用的是加分之前的 growth
memberMapper.addGrowth(userId, amount);
int newGrowth = member.getGrowth();   // 这是旧值
```

正确顺序是：先 `addGrowth`，**再查一次**拿新值，然后拿新值去比对各档门槛。
同一事务内能看到自己的修改，且此时行锁还在手上，读到的一定是最终值。

比对的规则是：在 `levelCache` 里找**满足 `growth_threshold <= 新成长值` 的最大 level**。
不能因为"正好等于某个门槛"才升级——用户一次加 600 分，应该直接跳到银卡，
而不是因为没精确命中 500 就留在 0 级。

---

## 六、实现路线

按这个顺序写，每一步都有对应的现成零件：

**Step 1 · 参数校验**
`bizId` 为空直接抛 `BizException`。没有 bizId 就没有幂等依据，宁可失败也不能瞎加。
`amount` 为 0 同理。

**Step 2 · 懒创建会员行**
调 `memberMapper.initIfAbsent(userId)`。新用户第一次加分时表里还没有这行，
后面的 UPDATE 会静默影响 0 行——不报错，但也没加上，是最难查的那种 bug。

**Step 3 · 先写流水**（幂等闸门）
构造 `GrowthLog`，`insert` 进去。
- 插成功 → 这是第一次，继续；
- 插不进去（返回 0 或抛异常）→ `return false`，**立刻返回，不做任何其他写入**。

顺序的意义：流水是"账"，成长值是"余额"。先记账再改余额，账没记上就说明这单处理过，
余额一分都不能动。反过来先改余额，冲突发生时余额已经脏了。

**Step 4 · 原子自增**
`memberMapper.addGrowth(userId, amount)`。

**Step 5 · 读回新值并判级**
再 `selectOne` 拿最新 `growth`，遍历 `levelCache` 算出应该升到几级。
注意 `levelCache` 在 `MemberServiceImpl` 里是私有字段，你可以直接用。

**Step 6 · 升级**
`memberMapper.upgradeLevel(userId, targetLevel)`。
SQL 里已经带了 `level < #{level}`，天然只升不降，你不用在外面再判断一次。

**Step 7 · 返回 true**

---

## 七、事务该加在哪

`addGrowth` 上直接加 `@Transactional(rollbackFor = Exception.class)` 就行，
但要想清楚两件事：

1. **为什么必须在一个事务里**：流水插成功、成长值没加上，就会变成"有账无余额"，
   对账时永远对不平。反过来成长值加了、流水没记，用户查不到加分记录，客诉。
2. **为什么 `rollbackFor = Exception.class`**：Spring 默认只对 `RuntimeException` 回滚，
   受检异常不回滚。这个项目全程抛 `BizException`（RuntimeException），
   加不加都一样——但养成写上的习惯，换项目时不会踩。

还有一个**顺序陷阱**：`@Transactional` 只有通过 Spring 代理调用才生效。
如果你在同一个类里 `this.addGrowth(...)` 自己调自己，事务是不生效的——
这个项目里 `MemberController` 从外部调 `memberService.addGrowth`，走的是代理，没问题。

---

## 八、验收

后端起来后（注意端口，别和 IDEA 里的实例抢），先登录拿 token，然后：

**① 幂等：同一 bizId 打 10 次**

```powershell
1..10 | ForEach-Object {
    Invoke-RestMethod -Uri "http://127.0.0.1:8081/member/growth/earn" -Method Post `
      -Headers @{ authorization = $token } -ContentType "application/json" `
      -Body '{"bizId":1001,"amount":10,"reason":1}'
}
```

期望：1 次「加分成功」，9 次「该业务已加过分」；`growth` 只 +10，流水只有 1 行。

**② 不丢更新：10 个不同 bizId 并发**

```sql
SELECT growth FROM tb_member WHERE user_id = 1016;              -- 应 +100
SELECT COUNT(*) FROM tb_growth_log WHERE user_id = 1016;        -- 应 10 行
```

**③ 自动升级**

把 `amount` 设成 500 打一次（单次上限就是 500），`level` 应从 0 变 1，
`/member/info` 返回的 `levelName` 是「银卡会员」，`progress` 回到 0 附近。

**④ 对账 SQL**

```sql
-- 成长值与流水求和是否一致（负数扣回也适用）
SELECT m.user_id, m.growth, IFNULL(SUM(g.change_amount), 0) AS log_sum
FROM tb_member m LEFT JOIN tb_growth_log g ON g.user_id = m.user_id
GROUP BY m.user_id, m.growth
HAVING m.growth <> IFNULL(SUM(g.change_amount), 0);
```

结果为空 = 账实相符。这条 SQL 建议你故意把某个 `growth` 改错再跑一次，
确认它真能抓出来——**没验证过的对账 SQL 等于没写**。

---

## 九、面试口径

被问到"你怎么保证同一笔订单不重复加分"时，按这四层答，从下往上：

| 层 | 说法 |
|---|---|
| **判据** | 表上 `(user_id, reason, biz_id)` 唯一索引。判断和写入是同一条语句，不存在检查与执行之间的窗口，多实例部署同样成立 |
| **顺序** | 先插流水再改成长值。流水插不进去说明这单处理过，直接返回，余额一分不动——账没记上就不动余额 |
| **不丢更新** | `growth = growth + ?` 一条语句完成，不先查再改。并发累加由 MySQL 行锁保证 |
| **工程取舍** | 不用分布式锁：为一个加分动作引入锁，等于引入锁超时、续期、Redis 故障降级三个新问题，而唯一索引零成本 |

追问「`catch DuplicateKeyException` 行不行」——这是送分题，答：

> 不行。异常一旦抛出来，Spring 已经把事务标成 rollback-only 了，
> 你在方法里 catch 住返回 false，外层提交时会抛 `UnexpectedRollbackException`。
> 所以我用 `INSERT IGNORE` 看 affected rows，让重复键不产生异常，事务才是干净的。

追问「MQ 重投怎么办」——同一套机制，`bizId` 用订单号，重投进来就是插流水失败，直接 `return false`。

---

## 十、写完之后

- 把 `MemberController.earn` 里的注释再读一遍，确认你知道它为什么不该上线
- 把这次的实测数字（并发几个线程、最终 growth 多少、耗时）记到 `W2` 日志里
- 如果写完发现 `growth` 和流水总和对不上，先别改代码，跑一遍第 8 节那条对账 SQL，
  看清楚是"多加了"还是"漏记了"，两种情况的修法完全不同
