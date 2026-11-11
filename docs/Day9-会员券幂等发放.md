# Day 9 会员券幂等发放 · 动手前必读

> 目标：每月 1 号给有等级的会员发他应得的月度券，**重复跑任意多次，一张都不多发**。
>
> 脚手架已经就位（`MemberCouponGrantTask` / `MemberCouponGrantController` / `preview` / `pickTemplates`），
> 你要写的只有 `MemberCouponGrantServiceImpl.grantToUser` 一个方法。
>
> 但方法短不代表简单。Day 8 是「幂等键从请求参数来」，Day 9 是「幂等键必须服务端自己造」——
> 造错一个字符，整套幂等就静默失效。

---

## 一、你要写的方法

```java
// MemberCouponGrantServiceImpl.java
@Override
@Transactional(rollbackFor = Exception.class)
public int grantToUser(Long userId, int level, String period) {
    // TODO(day9-用户实现)
    throw new BizException("会员券发放未实现（Day 9 待完成）");
}
```

要做的事一句话：把 `pickTemplates(level)` 返回的每个模板，变成 `userId` 名下的一张券。

`@Transactional` 已经替你加好了，**但你要理解它为什么加在这里、不加在批量那边**（第七节）。

---

## 二、现有数据长什么样

### 2.1 唯一索引就是全部答案

```sql
UNIQUE KEY `uk_member_period` (`user_id`, `template_id`, `grant_period`)
```

`grant_period` 是 `char(7)`，形如 `2026-11`，只有会员月度券有值，普通券保持 `NULL`。

这三列一组合，表达的是一句业务断言：

> **同一个用户、同一种券，每个月最多一张。**

不需要额外的「发放记录表」，不需要 Redis 分布式锁，不需要先查后插。
重复发放这件事，在数据库层面上就写不进去。

### 2.2 模板与等级配置

| 模板 | 类型 | 最低等级 | 面额 | 有效期 |
|---|---|---|---|---|
| 5 会员月度 10 元红包 | 会员红包 | 银卡（1） | 满 0 减 1000 分 | 领取后 30 天 |
| 6 黑卡专享 8 折 | 会员红包 | 黑卡（3） | 8 折封顶 3000 分 | 领取后 30 天 |

| 等级 | 每月张数上限 `monthly_coupon_cnt` |
|---|---|
| 普通（0） | 0 |
| 银卡（1） | 2 |
| 金卡（2） | 3 |
| 黑卡（3） | 4 |

所以实际发放是：**银卡 1 张（模板 5）、金卡 1 张（模板 5）、黑卡 2 张（模板 5 + 6）**。
「上限」比模板数大，是个安全阀——将来运营加了模板，最多发到上限张就停。

这个结果我已经实测过，`GET /coupon/member/grant/preview` 返回：

```json
{"period":"2026-11","userTotal":3,"couponTotal":4,"couponsByLevel":{"1":1,"2":1,"3":2}}
```

你写完 `grantToUser` 后，`POST /coupon/member/grant` 的 `couponTotal` 必须也是 4。

---

## 三、核心：幂等键由谁构造

### 3.1 绝对不能接受客户端传 `period`

如果 `period` 从请求参数进来，攻击者只要换个值：

```
POST /coupon/member/grant?period=2026-11   → 发一批
POST /coupon/member/grant?period=2026-12   → 再发一批（同一个人，同一个月，第二次）
```

唯一索引一点都拦不住，因为它眼里这是两个不同的周期。

所以签名里那个 `period` 参数，**只能由服务端的 `buildPeriod(LocalDate.now())` 产生**。
`MemberCouponGrantTask.grantAll` 里那句：

```java
String p = (period == null || period.isEmpty())
        ? grantService.buildPeriod(LocalDate.now()) : period;
```

`period` 有值的那条分支是给**运维补发历史月份**用的（比如 10 月的任务挂了，手动补 `2026-10`）。
它在 Controller 上，属于管理端能力，不能暴露给普通用户。

### 3.2 格式是契约，不是习惯

`char(7)` + 唯一索引，意味着这个字段的写法必须严格统一：

| 写法 | 结果 |
|---|---|
| `yyyy-MM` → `2026-11` | ✅ 正确，固定 7 位 |
| `yyyy-M` → `2026-1` | ❌ 6 位，和「2026-01」是两个不同的键，**一月能发两次** |
| `YYYY-MM` → `2025-01` | ❌ `Y` 是「周所在年」，2026-01-01 属于 2025 年最后一周，跨年错位 |
| `yyyyMM` → `202611` | ❌ 长度对不上 `char(7)`，插入时被截断或报错 |

`YYYY` 那个坑每年 1 月准时出现一次，平时测不出来。
`buildPeriod` 已经写好了，但你最好自己验证一遍：

```java
System.out.println(buildPeriod(LocalDate.of(2026, 1, 1)));  // 必须是 2026-01
System.out.println(buildPeriod(LocalDate.of(2026, 12, 31))); // 必须是 2026-12
```

### 3.3 实测：`grant_period` 为 NULL 时，唯一索引直接失效

MySQL 认为 `NULL != NULL`，所以 `(1016, 5, NULL)` 可以插无数行。
我刚在库里试过，同一组参数连插两次：

```
INSERT IGNORE ... grant_period = NULL;   → ROW_COUNT() = 1
INSERT IGNORE ... grant_period = NULL;   → ROW_COUNT() = 1
```

结果库里多了两行完全一样的券，而且**没有任何报错**。

这就是问题 3 的答案：**`grantPeriod` 忘了赋值，不是「报错」，是「幂等悄悄没了」**。
你以为有唯一索引兜底，实际上每个月都在重复发，直到对账时发现券的数量对不上。

**结论：`uc.setGrantPeriod(period)` 这一行必须有，而且 `period` 不能是 null。**

---

## 四、七个字段，逐个定死

构造 `UserCoupon` 时要填的字段，每一个都有明确来源：

| 字段 | 值 | 为什么 |
|---|---|---|
| `userId` | 方法参数 | — |
| `templateId` | `t.getId()` | — |
| `couponType` | `t.getType()` | 从模板冗余过来，「我的券」列表页不用 join 模板表 |
| `shopId` | `t.getShopId()` | 同上。会员券通常是 0（全平台） |
| `source` | `CouponConst.SRC_MEMBER` = **4** | **不是 3**。见下 |
| `status` | `CouponConst.UC_UNUSED` = 0 | 刚发的券当然是未使用 |
| `expireTime` | `valid_type=2` → `now.plusDays(valid_days)` | 领取后 N 天，发券这一刻就算死 |
| `grantPeriod` | `period` | **必须非空**，否则唯一索引失效（3.3 已实测） |

### `source` 为什么是 4 不是 3

`CouponConst` 里两个常量都在：

- `SRC_SYSTEM = 3` 系统发放
- `SRC_MEMBER = 4` 会员月度

两个都「看起来对」，但 `source` 的用途是**事后统计「这张券是哪个渠道来的」**。
用 3 的话，「会员月度发放了多少张」这个问题就再也查不出来了——
它和新人券、活动券混在一个值里。

这类字段填错的代价不在当时，在三个月后运营问「上月会员券发了多少」的时候。

### `expireTime` 不要在 SQL 里算

`receive_time` 用 `NOW()`（数据库时钟），`expire_time` 用 Java 算好传进去。
这样会不会不一致？会差几毫秒，但**不影响**——有效期是 30 天，差几毫秒无所谓。

反过来，如果 `expire_time` 也用 SQL 的 `DATE_ADD(NOW(), INTERVAL 30 DAY)`，
那就得先 `SELECT valid_days` 再拼 SQL，一条语句变成两条，还得处理 `valid_type=1` 的分支。
**能用一次查询解决的，不要拆成两次。**

---

## 五、四条看起来能用、实际会失效的路

### 路线 A：先 `selectCount` 再插入

```java
long cnt = userCouponMapper.selectCount(/* user + template + period */);
if (cnt > 0) return 0;
userCouponMapper.insert(uc);
```

单线程测 100 次都对。**并发下两个线程同时查到 0，然后都插入**——
如果 `grant_period` 恰好是 NULL，两张都进库；即使非空，后一个会抛 `DuplicateKeyException`，
被批量循环的 catch 抓到，记为「失败」，统计表上出现莫名其妙的 failedUsers。

判据必须交给唯一索引。**能一条 INSERT 表达的正确性，不要用「查 + 判断 + 写」三步。**

### 路线 B：`period` 从请求参数拿

见 3.1。这个错误不会有任何报错，只会在某天发现券发多了。

### 路线 C：把 `@Transactional` 加在批量方法上

```java
@Transactional
public void grantAll() {
    for (Member m : members) grantService.grantToUser(...);
}
```

看起来更「原子」，实际三个问题：

1. 第 480 个人失败，前 479 个已经发出的券**全部回滚**，用户白高兴一场；
2. 500 条 INSERT 一个事务，行锁持有到提交，并发一高就锁等待；
3. 出错后只能整批重跑，没法只补失败的那几个。

发券不需要「整批原子」，需要的是**可重入**：重跑一遍，发过的返回 0，没发的补上。

### 路线 D：顺手扣一下 `remain_stock`

会员券的 `total_stock = -1`，表示**不限量**。
如果你照着 Day 3 领券的逻辑顺手扣了，它会变成 `-2`、`-3`……

Day 5 那条库存守恒对账 SQL 立刻报警：

```sql
-- 除不限量模板外，remain_stock 不能为负
SELECT * FROM tb_coupon_template WHERE total_stock <> -1 AND remain_stock < 0;
```

**会员券发放不碰库存**。库存是「有多少张可发」的概念，会员券是「人人有份」，没有这个约束。

---

## 六、实现路线（七步）

```
Step 1  取模板列表：List<CouponTemplate> tpls = pickTemplates(level);
        —— 空列表直接返回 0，别进循环
Step 2  定义一个计数器 int granted = 0;
Step 3  遍历 tpls，每个模板 new 一个 UserCoupon，按第四节填 7 个字段
Step 4  调 userCouponMapper.insertIgnoreGrant(uc)，返回值累加到 granted
Step 5  循环结束，返回 granted
Step 6  不要 catch 任何异常，让 @Transactional 该回滚就回滚
Step 7  不要动库存、不要先查后插、不要判空 period（上游已经保证）
```

核心只有一句：**累加的返回值就是实际发放张数**。
`insertIgnoreGrant` 返回 1 表示这次真发了，返回 0 表示本周期已经有了。

### 一个必须知道的歧义

`INSERT IGNORE` 返回 0 有**两种**可能：

1. 唯一索引冲突 → 本周期已发过（正常业务）
2. 插入被 MySQL 忽略 → 比如 `expire_time` 传了 NULL（NOT NULL 列），**数据根本没写进去**

两种情况返回值一模一样，**从返回值分不出来**。

所以 `grantToUser` 返回 0 时不要向调用方断言「已发过」，它只表示「这一张没有新增」。
真要区分，只能查库：

```sql
SELECT COUNT(*) FROM tb_user_coupon WHERE user_id=? AND template_id=? AND grant_period=?;
```

验收时必须跑这条，不能只看接口返回的数字。

---

## 七、事务边界与代理陷阱

`grantToUser` 上的 `@Transactional` 能生效，靠的是 **Spring 代理**：
外部 Bean 调它 → 走代理 → 开启事务 → 调真实对象。

如果批量循环写在本类里：

```java
public MemberGrantResultVO grantAll(...) {
    for (Member m : members) {
        this.grantToUser(...);   // ❌ 自调用，代理被绕过，事务不生效
    }
}
```

`this` 指向的是**原始对象**，不是代理。注解还在，日志也不报错，事务就是不生效。
这是 Spring 里最经典的「配置看起来没问题但就是不工作」。

本项目为了绕开它，故意把批量循环放到了 `MemberCouponGrantTask`（另一个 Bean），
通过 `IMemberCouponGrantService` 接口注入调用。**你可以去那个文件里确认一下这个设计。**

顺便回答一个常见疑问：为什么不干脆在 Task 上方法也加事务？
因为 Task 里那个 `try/catch` 是为了「单个人失败不影响整批」，
如果 Task 也在事务里，异常虽然被 catch 了，事务已经被标记 rollback-only，
外层提交时抛 `UnexpectedRollbackException`——和 Day 8 的坑 1 是同一个机制。

---

## 八、重复触发与多实例

三种情况都会让「同一批人」被发两次：

1. 运维手动点了 3 次 `POST /coupon/member/grant`；
2. 服务部署了 2 个实例，两个实例的定时任务同时到点；
3. 任务挂了以后补跑，而上次其实已经发了一半。

唯一索引对这三种情况**全部有效**，因为判据在数据库，不在进程里：

| 场景 | 结果 |
|---|---|
| 单实例重复点 | 第二次全部返回 0（skipped） |
| 双实例同时跑 | 同一行只有一个能插进去，另一个返回 0 |
| 补跑 | 已发的跳过，没发的补上 |

**这就是「判据在数据库」相对「Redis 分布式锁」的优势**：锁只能防并发，防不了重跑；
唯一索引两者都防，而且不依赖任何中间件可用性。

⚠️ 有一个边界：唯一索引让重复**插入**失败，但不会让重复**执行**变快。
两个实例同时跑，第二个实例仍然会走完 500 次 INSERT（每次返回 0）。
这是可以接受的浪费——发券是每月一次的低频任务。

---

## 九、验收

### 9.1 先看预演

```powershell
curl.exe -s "http://127.0.0.1:8081/coupon/member/grant/preview" -H "authorization: $t"
```

建 3 个测试会员后应返回 `{"userTotal":3,"couponTotal":4,"couponsByLevel":{"1":1,"2":1,"3":2}}`。

### 9.2 连打三次 grant

```powershell
1..3 | ForEach-Object {
  curl.exe -s -X POST "http://127.0.0.1:8081/coupon/member/grant" -H "authorization: $t"
  echo ""
}
```

期望：

| 次 | grantedUsers | skippedUsers | couponTotal |
|---|---|---|---|
| 1 | 3 | 0 | 4 |
| 2 | 0 | 3 | 0 |
| 3 | 0 | 3 | 0 |

### 9.3 四条必跑的 SQL

```sql
-- ① 一人一模板一周期，最多 1 行。必须为空集
SELECT user_id, template_id, COUNT(*) c
FROM tb_user_coupon WHERE grant_period = '2026-11'
GROUP BY user_id, template_id HAVING c > 1;

-- ② 该发的人都发了：黑卡 2 张、金卡 1 张、银卡 1 张
SELECT m.user_id, m.level, COUNT(uc.id) AS got
FROM tb_member m
LEFT JOIN tb_user_coupon uc
       ON uc.user_id = m.user_id AND uc.grant_period = '2026-11'
WHERE m.level > 0
GROUP BY m.user_id, m.level;

-- ③ source 只能是 4。出现 3 就说明常量填错了
SELECT source, COUNT(*) FROM tb_user_coupon
WHERE grant_period IS NOT NULL GROUP BY source;

-- ④ 会员券绝不能有空的 grant_period（有空就说明漏 set 了）
SELECT COUNT(*) FROM tb_user_coupon WHERE source = 4 AND grant_period IS NULL;
```

### 9.4 一个反向验证

故意把 `uc.setGrantPeriod(period)` 注释掉，再连打两次 grant，然后跑 ①——
你会看到重复行。**亲眼见过一次，以后就不会忘。**

---

## 十、面试口径

**Q：会员每月发券，怎么保证不重复发？**

> 用业务周期做唯一键。`tb_user_coupon` 上 `(user_id, template_id, grant_period)` 建唯一索引，
> `grant_period` 是 `2026-11` 这种月份键，由服务端生成，不接受客户端传参。
> 插入用 `INSERT IGNORE`，返回 0 就是本周期已发过。
>
> 关键点是这个键**必须在服务端构造**：一旦可以外部指定，换个 period 就能重复领，
> 唯一索引形同虚设。

**Q：为什么不用分布式锁？**

> 锁解决的是「同时」，解决不了「重跑」。定时任务补跑、双实例重复触发、
> 运营手动点两次，这些场景锁都不一定拦得住（锁过期、释放时机、误删别人的锁）。
> 唯一索引解决的是「结果」——不管谁在什么时候尝试，这一行只能存在一次。
> 而且它不依赖 Redis 可用性，Redis 挂了发券照样正确。

**Q：批量发放的事务怎么划？**

> 一个用户一个事务，不是整批一个事务。
> 整批事务的话，第 480 个人失败会把前 479 个已发的券一起回滚，
> 而且几百条 INSERT 持有行锁到提交，锁等待会拖垮其他接口。
> 单用户事务的好处是失败可局部重试——而这依赖于发放本身幂等。

**Q：`INSERT IGNORE` 有什么风险？**

> 它把所有警告级错误都降级成忽略，包括类型不匹配、字段超长、非空约束。
> 返回值 0 既可能是「唯一键冲突」也可能是「数据根本没插进去」，分不出来。
> 所以用它必须保证：① 所有字段在进入 SQL 前已经校验过；
> ② 验收要看库里行数，不能只看返回值。

**Q：`grant_period` 为什么是 NULL 允许的？**

> 因为普通券（主动领取、秒杀购买）没有周期概念，它们的 `grant_period` 就是 NULL。
> MySQL 唯一索引对 NULL 宽容——多个 NULL 互不冲突——正好让「普通券随便发、
> 会员券每月一张」两种规则共用同一个索引。
> 代价是会员券**必须显式写这个字段**，漏写就等于把幂等关掉了，而且是静默的。

---

## 十一、写完之后

```powershell
mvn -q -DskipTests compile
```

编译通过后按第九节验收，然后告诉我结果，我帮你跑一遍完整链路（含双实例并发触发）。
