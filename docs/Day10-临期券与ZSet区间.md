# Day 10 临期券扫描 · 动手前必读

> 施工手册 Day 10。前八天写的都是**等值判断**（这一单是不是重复的、这本书是不是发过了）。
> 今天第一次做**范围查询**，而范围查询的坑集中在「边界」和「顺序」两个字上。

---

## 一、你要写的方法

`UserCouponServiceImpl.listExpiringSoon(int days, int limit)`，实现 `IUserCouponService` 里的同名方法。

> 已于 2026-11-03 实现并实测通过，实现细节与验收结果见文末「十二、实现记录」。

接口已经通：`GET /coupon/expiring/soon?days=2&limit=10`。

---

## 二、为什么这件事要用 Redis

先说清楚**不该用**的地方，免得你把它当成万能钥匙：

| 场景 | 数据量级 | 该用什么 |
|---|---|---|
| 「我的券」列表 | 单用户十几到几十行 | **SQL**。`idx_user_status_expire` 索引足矣，套 ZSet 只是多一份可能不一致的副本 |
| 「我未来 3 天要过期的券」 | 同上 | 同上 |
| 「全站未来 24 小时要过期的券」 | 全表 | **ZSet** ← 今天要写的这个 |

区别在最后一行的**跨用户**：`WHERE expire_time BETWEEN ? AND ? AND status = 0` 会按时间范围扫出一大批行，再逐行回表；数据量上去以后，这条 SQL 的代价和「总共发了多少券」正相关。

ZSet 的 score 本身就是有序结构，`ZRANGEBYSCORE` 是**从有序索引里定位起点、往后取 N 个**，复杂度只和「取多少条」有关，和总量无关。这就是它能扛住的原因。

一句话：**范围 + 跨用户 + 数据量大，三个条件同时成立才值得上 ZSet。**

---

## 三、索引怎么存

```
key     coupon:expire:soon          （全局一个，不分用户）
score   过期时间的 epoch 秒          1793790136
member  用户券 id                    "3"
```

### 3.1 score 用秒，不用毫秒

两个理由：

1. **精度够用**。券的有效期是「天」级别，秒级绰绰有余。
2. **少一个 1000 倍的错误源**。写入用秒、查询用毫秒，区间会整体偏移 1000 倍，表现只有两种：什么都查不出来，或者什么都查出来。哪种都不报错。

关键是**全项目统一**。所以 `CouponIndexService.toScore()` 是静态方法，写入和查询都必须调它，不许自己 `System.currentTimeMillis()`。

### 3.2 为什么不用 millis 也不存 `expire_time` 字符串

存字符串 `"2026-11-04 19:02:16"` 的话，score 得是数值，还得解析，纯属多余——ZSet 的 score 本来就是 double，时间戳才是它的天然用法。

---

## 四、查询怎么写（你要实现的部分）

```
1  参数校验        days ∈ [1, 7]，limit ∈ [1, 100]，越界直接报错
2  算区间          min = now秒，max = now秒 + days * 86400
3  ZRANGEBYSCORE  opsForZSet().rangeByScore(key, min, max, offset, count)
4  空就直接返回    空列表不是错误
5  回表            userCouponMapper.selectBatchIds(ids)
6  还原顺序        ★ 这步最容易漏
7  过滤脏数据       status != 0 的丢掉
8  拼 VO           join 模板取名/面额，算 remainHours
9  兜底            Redis 异常 → 退化成 SQL 查询
```

### 4.1 区间边界：默认就是闭区间

Redis 的 `ZRANGEBYSCORE key min max` 是 **`[min, max]` 闭区间**。想排除端点得写成 `(min` / `(max`。

这里**恰好应该用闭区间**：正好卡在「第 3 天整」那一秒过期的券，也该被提醒。

`rangeByScore(key, min, max, offset, count)` 这四个参数没有开闭区间的表达能力——需要开区间时得用 `rangeByScore(key, Range, Limit)` 那个重载。

### 4.2 `LIMIT offset count` 不是分页

`ZRANGEBYSCORE ... LIMIT 0 20` 里的 `0` 是**偏移量**，不是页码。

它也不能当分页用，原因在下一节。

### 4.3 回表之后顺序就丢了（核心）

`ZRANGEBYSCORE` 返回的是**有序的 id**，但 `selectBatchIds` 是 `WHERE id IN (...)`，返回的是**主键序**。

```java
Set<String> ids = redis.rangeByScore(...);        // ["3", "2", "24"] ← 快过期的在前
List<UserCoupon> rows = mapper.selectBatchIds(ids); // [2, 3, 24]     ← 变主键序了
```

不还原的话，「最快到期的在最前面」这个契约就废了——运营看到的列表顺序是随机的。

标准做法：**先把查询结果按 id 建 Map，再按 ZSet 的顺序遍历取值**。

```java
Map<Long, UserCoupon> byId = rows.stream()
        .collect(Collectors.toMap(UserCoupon::getId, x -> x));
for (String id : ids) {
    UserCoupon uc = byId.get(Long.valueOf(id));
    ...
}
```

这个坑在 `BlogServiceImpl` 的点赞排行榜里已经踩过一次（那边是 `ZSet` 顺序 + `selectBatchIds` 回表），可以直接对照 `queryBlogLikes` 的写法。

### 4.4 为什么要过滤脏数据

`CouponIndexService.addExpire` 是 fire-and-forget：写失败只记日志，不影响业务。再加上 Redis 可能重启、可能被 flush，ZSet 里一定会出现「库里已经不是未使用状态」的 id。

不过滤的后果很具体：**提醒用户「你的券快过期了」，用户打开发现早就用掉了。**

所以回表后要再筛一遍 `status == UC_UNUSED && expireTime.isAfter(now)`。

### 4.5 Redis 挂了要降级，不能返回空

返回空列表会被理解成「今天没有临期券」，而真相是 Redis 挂了。运营据此判断「不用发提醒了」，这事就过去了。

降级就是同一件事用 SQL 再做一遍：

```sql
SELECT id, user_id, template_id, expire_time
FROM tb_user_coupon
WHERE status = 0 AND expire_time > NOW() AND expire_time <= ?
ORDER BY expire_time ASC LIMIT ?
```

注意降级查询的条件必须和 ZSet 查询**严格等价**，否则「有没有 Redis」会得出两个不同的数字，比直接报错还难查。

---

## 五、另半个 ZSet：`ZRANGEBYLEX`

`listGrantRecords` 已经由脚手架实现好了，你可以直接调 `GET /coupon/member/grant/records?from=2026-01&to=2026-04`。这里讲它为什么这么写。

### 5.1 它和 `ZRANGEBYSCORE` 的分工

| 命令 | 按什么排序 | 什么时候用 |
|---|---|---|
| `ZRANGEBYSCORE` | score（数值） | 时间、分数、金额这类**可比较的数值** |
| `ZRANGEBYLEX` | member（字典序） | 字符串前缀、区间枚举，且 **score 全部相同** |

### 5.2 score 必须全相同

这是 `ZRANGEBYLEX` 的**硬前提**，不是建议。

它的排序规则是：**先按 score 升序，score 相同的再按 member 字典序**。所以只要混进一个 score 不同的成员，跨组取出来的顺序就完全不是你想要的字典序了。

这就是为什么 `coupon:grant:index` 里所有成员的 score 都写 `0`——不是忘了填，是刻意抹平。

### 5.3 member 怎么拼：`{period}#{userId}#{templateId}`

```
2026-11#1016#5
2026-12#1016#5
```

三个决定：

1. **period 必须在最前面**，而且定长 7 位。这样字符串比较 = 日期比较。反过来把 userId 放前面，索引就只能按用户扫，跨用户的区间统计彻底失效。
2. **分隔符用 `#`**。`#` 的 ASCII 是 35，比所有数字（48+）和字母都小，所以 `2026-01#` 一定排在 `2026-01` 的所有后代之前，切片不会和内容混淆。
3. **`yyyy-MM` 的补零在这里又咬人一次**。`2026-1` 和 `2026-01` 在字典序里不是一个位置，前者会排到 `2026-10` 后面去。Day 9 讲过的 M/MM 问题，在范围查询里从「幂等失效」升级成「数据错位」。

### 5.4 `[` 和 `(`

```
[2026-01#     含 2026-01
(2026-04#     不含 2026-04
```

接口语义：`to` 是**不含**的，和 SQL 的 `<` 一致。传 `to=2026-04` 表示「到 3 月底为止」。

实测（`GET /coupon/member/grant/records`）：

| 参数 | 返回 | 说明 |
|---|---|---|
| `from=2026-01&to=2027-01` | `["2026-11#1016#5"]` | 全年包含 |
| `from=2026-11&to=2026-12` | `["2026-11#1016#5"]` | 单月包含 |
| `from=2026-12&to=2027-01` | `[]` | 12 月没有记录 |
| `from=2026-01&to=2026-11` | `[]` | **上界排他生效**，11 月被排除 |
| `from=2026-11&to=2026-11` | 报错 | 起始必须早于结束 |

第四行是这条命令唯一需要记住的地方：**闭区间的 `to=2026-11` 会返回 11 月的记录，开区间不会**。

---

## 六、写侧：索引什么时候挂、什么时候摘

| 时机 | 动作 | 为什么 |
|---|---|---|
| 领券成功 | `addExpire(uc.getId(), uc.getExpireTime())` | 新券要进索引 |
| 会员券发放成功 | 同上 + `addGrantPeriod(...)` | 发放的券也会过期，也要能按周期查 |
| 核销成功 | `removeExpireAfterCommit(id)` | 用掉的券不该再提醒 |
| 退券成功 | `addExpireAfterCommit(id, expireTime)` | **券回来了，它还会过期，得重新进索引** |

第三条和第四条必须成对。只摘不挂的话，退回来的券会从提醒列表里**永久消失**——不报错、不影响用券，只是用户再也收不到这张券的到期提醒。这种缺陷通常要等到有人投诉「我明明还有券，怎么没提醒我」才会被发现。

### 6.1 为什么必须是 afterCommit

核销是一个事务。如果在事务里直接删索引，然后同一单的第二张券校验失败导致整体回滚：

```
数据库：券回到「未使用」   ← 事务回滚，正确
Redis ：索引已经被删了      ← Redis 不参与事务，不会回滚
结果  ：这张券再也不会被提醒
```

Redis 不是事务资源，Spring 不会帮你回滚它。唯一可靠的做法是**等提交成功再动缓存**：

- 提交前动 → 回滚就丢数据；
- 提交后动 → 最坏是「提交成功但删索引失败」，那只是多一条僵尸成员，查询侧会过滤掉，比丢数据安全。

`CouponIndexService.removeExpireAfterCommit` / `addExpireAfterCommit` 用 `TransactionSynchronizationManager` 实现，没有事务时同步执行。

---

## 七、Redis 是副本，不是真相

启动时 `CouponIndexService.rebuildExpireIndex()` 会从数据库重建一遍：

```sql
SELECT id, expire_time FROM tb_user_coupon
WHERE status = 0 AND expire_time > NOW() AND expire_time <= NOW() + 30 DAY
ORDER BY expire_time LIMIT 5000
```

启动日志会打印 `临期索引重建完成：N 条`。实测这次是 **3 条**（当时库里有 3 张 30 天内到期的未使用券，第 4 张 2026-12-31 到期的正确排除）。

为什么这步不能省：索引缺失的表现是「运营扫不到该提醒的券」——不报错、不影响下单，只影响那一批提醒。等业务方发现「最近怎么没人收到提醒」，已经漏了一个月。

这和 Day 5 的 `coupon:stock:` 校准、Day 7 的启动重算是同一套思路：**缓存可以坏，但不能坏了没人知道。**

`purgeExpired()` 负责清掉已过期还赖在 ZSet 里的成员。Redis 的 TTL 只能整 key 设置，没法让「某几个成员过期」，所以这是 ZSet 版 TTL 必须自己付的账。

---

## 八、验收

### 8.1 写对了的判据

```powershell
# 1) 索引内容与库一致（score 应等于 UNIX_TIMESTAMP(expire_time)）
redis-cli -a 123321 zrange coupon:expire:soon 0 -1 withscores

# 2) 接口返回按「快过期在前」升序
curl.exe "http://127.0.0.1:8081/coupon/expiring/soon?days=3&limit=10" -H "authorization: $token"

# 3) 边界：days=1 不该出现 3 天后到期的券
# 4) 核销一张再查，它必须消失；退券后再查，它必须回来
```

### 8.2 一个反向验证（建议做）

手动往 ZSet 里塞一个不存在的 id：

```powershell
redis-cli -a 123321 zadd coupon:expire:soon <未来时间戳> 999999
```

然后调接口。**如果结果里出现了 id=999999 的脏数据，说明你没做 4.4 那步过滤。**
正确表现是：它被静默过滤掉，接口返回的条数不变。

---

## 九、四个坑（面试可讲）

### 坑 1：`ZRANGEBYSCORE` 返回有序，`selectBatchIds` 返回主键序

回表之后顺序一定丢。要么按 ZSet 顺序重排，要么别在前端承诺「按到期时间排序」。

### 坑 2：score 单位不统一

写入秒、查询毫秒 → 区间偏移 1000 倍。两种表现都不报错：查空，或查出全部。

### 坑 3：`ZRANGEBYLEX` 混入不同 score

它只保证「score 相同时按字典序」。score 一乱，跨组结果是乱的。所以发放索引的 score 必须统一写 0。

### 坑 4：事务里动缓存

Redis 不参与数据库事务，回滚不会撤销你删掉的索引。核销用 afterCommit，退券用 afterCommit，别图省事。

---

## 十、面试口径

**Q：为什么临期券要用 ZSet，直接查库不行吗？**

A：要看是不是跨用户。单用户的券十几张，走 `idx_user_status_expire` 索引的 SQL 就够，套 ZSet 只是多一份可能不一致的副本。但运营侧要扫「全站未来 24 小时到期的券」，`WHERE expire_time BETWEEN` 的代价和总券数正相关，而 `ZRANGEBYSCORE` 只和取多少条有关。范围 + 跨用户 + 量大，三个条件同时成立才值得用。

**Q：score 为什么用秒不用毫秒？**

A：券有效期是天级别，秒足够。用毫秒不会错，但会多一个 1000 倍的错误源——写入和查询只要有一边用了毫秒，区间就整体偏移，而且两种错法都不报错。全项目统一一个 `toScore()` 比省一次类型转换重要。

**Q：ZSet 里的数据和数据库不一致怎么办？**

A：三层防护。① 写入 fire-and-forget，失败只记日志，不阻断业务；② 查询侧回表后按状态过滤，脏成员不会出现在结果里；③ 启动时从数据库重建索引。核心认识是「Redis 是副本不是真相」，副本可以坏，但必须有自愈路径。

**Q：`ZRANGEBYLEX` 和 `ZRANGEBYSCORE` 有什么区别？**

A：前者按 member 的**字典序**排序，前提是所有 score 相同；后者按 score 排序。前者适合字符串前缀、定长编号的区间枚举；后者适合时间、分数这类数值区间。用 `ZRANGEBYLEX` 而 score 又不统一，是最常见的误用——它不会报错，只是结果乱序。

**Q：为什么 member 里 period 要放最前面？**

A：为了让字典序等于时间序，从而支持「按周期区间扫」。如果 userId 在前，索引就只能按用户扫，跨用户的区间统计失效。另外 period 必须定长补零（`yyyy-MM`），否则 `2026-1` 会排到 `2026-10` 后面。

**Q：核销后为什么用 afterCommit 删索引？**

A：核销是事务，Redis 不参与事务。事务里删了索引，后面一张券失败导致整体回滚，数据库里券回到未使用，但索引已经没了，这张券再也不会被提醒。提交后动最坏是多一条僵尸成员，查询会过滤掉，比丢数据安全。

---

## 十一、写完之后

- 连打 `days=1/3/7` 三个值，确认结果集合是包含关系（1 ⊂ 3 ⊂ 7）
- 核销一张券 → 它从结果里消失；退券 → 它回来
- 塞一个假 id 进 ZSet → 被过滤掉
- 把 Redis 停掉 → 接口应降级返回 SQL 结果，不是空列表

通过后进 Day 11：`pickTemplates` 的 N+1 问题（每人查一次模板表）+ 批量 `IN` 改造成发放日志走 MQ。

---

## 十二、实现记录（已完成）

### 12.1 代码位置

`UserCouponServiceImpl`：

| 成员 | 作用 |
|---|---|
| `listExpiringSoon` | 主流程：参数闸门 → 取 id → 回表组装 |
| `parseCouponIds` | ZSet member 转 id，保留 `LinkedHashSet` 的 score 顺序 |
| `queryExpiringIdsFromDb` | 降级 SQL，条件与 ZSet 查询严格等价 |
| `buildExpiringVOs` | 还原顺序 + 三重过滤 + 批量查模板 + 拼 VO |
| `remainHours` | `Math.ceil(seconds / 3600.0)`，不用 `toHours()` |

常量 `EXPIRE_SCAN_MAX_DAYS = 7`、`EXPIRE_SCAN_MAX_LIMIT = 100`。

### 12.2 两个实现上的决定

**降级复用组装逻辑。** `queryExpiringIdsFromDb` 只 `select("id")`，拿到有序 id 后仍交给 `buildExpiringVOs`。
多花一次回表，换来「有没有 Redis」两条路径的顺序、过滤规则、字段填充永远一致。
降级的价值是让数字对得上，不是省一次查询。

**过滤用 `continue` 不用 `break`。** 一条脏数据不该让后面的券全部消失。
代价是返回条数可能少于 `limit`，这是对的——`limit` 的语义是「最多取几条候选」。

### 12.3 实测

启动日志 `临期索引重建完成：2 条`，`coupon:expire:soon` 里 id=3（2026-11-04 19:02 到期）、id=2（26 天后）。

| 验证 | 结果 |
|---|---|
| `days=1/3/7` | 均返回 id=3，`remainHours=24`；id=2 正确排除 |
| `days=0` / `days=8` / `limit=0` | 返回 fail，不退化成全量 |
| 塞 `999999`（score 最小、排第一） | 被静默过滤，条数不变 |
| 字段填充 | `discountYuan=5`（5 元券），`thresholdYuan=0` |

### 12.4 一个实测才发现的坑：Redis 全挂时降级跑不到

直接 `Stop-Process` 掉 Redis 再调接口，返回的是 **401**，不是降级结果。

原因：`LoginInterceptor` / `RefreshTokenInterceptor` 同样依赖 Redis 取 token，
鉴权层先失败，请求根本进不到 Service。所以「Redis 挂了降级查库」的真实生效场景是
**命令级故障**（超时、内存满拒绝写、WRONGTYPE），不是 Redis 整体不可用。

要验证降级，正确姿势是制造命令级错误：

```powershell
redis-cli -a 123321 del coupon:expire:soon
redis-cli -a 123321 set coupon:expire:soon "wrongtype"   # 类型错误 → ZRANGEBYSCORE 抛 WRONGTYPE
```

实测：接口 153ms 返回 SQL 结果，日志打印 `临期索引查询失败，降级查库`。

这条对面试有用：**JWT 自校验 token 的意义就在这里**——鉴权不依赖 Redis，
Redis 故障时业务降级链路才真的能生效。Session 方案下 Redis 一挂，整站登录态全失。
