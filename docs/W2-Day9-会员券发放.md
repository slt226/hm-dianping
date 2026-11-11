# Day 9 会员月度券发放 · 复盘

> 施工手册 Day 9。核心是一句话：**幂等判据交给唯一索引，应用层只负责把键填对。**
> 与 Day 8 结构相同、结论相同，但难点从「填对参数」变成了「自己造键」。

---

## 一、一句话概括

Day 8 的 `bizId` 是别人送来的，Day 9 的 `period` 得自己造。
造错不报错——`grant_period` 一旦是 `NULL`，`uk_member_period` 整行失效，同一个用户每个月能领无限张。

---

## 二、交付清单

| 层 | 文件 | 内容 |
|---|---|---|
| Mapper | `UserCouponMapper.insertIgnoreGrant` | 9 列 `INSERT IGNORE`，`receive_time` 用数据库 `NOW()`，`status` 写死 `UC_UNUSED` |
| DTO | `dto/MemberGrantResultVO` | 四计数 + `consistent()` 自检（三分支之和 == 总人数） |
| Service | `IMemberCouponGrantService` / `MemberCouponGrantServiceImpl` | `buildPeriod` / `pickTemplates` / `preview` / `listGrantableMembers` 已实现；`grantToUser` 为用户实现 |
| Task | `task/MemberCouponGrantTask` | `@Scheduled(cron = "0 0 1 1 * ?")` + `grantAll` 批量遍历 |
| Controller | `MemberCouponGrantController` | `GET /coupon/member/grant/preview`、`POST /coupon/member/grant` |
| 配置 | `HmDianPingApplication` | `@EnableScheduling`（原来没有，定时任务不会跑） |
| 教学 | `docs/Day9-会员券幂等发放.md` | 动手前必读 |

---

## 三、数据模型

### 3.1 唯一索引就是全部答案

```sql
UNIQUE KEY uk_member_period (user_id, template_id, grant_period)
```

三个列一起构成「这个人 + 这个模板 + 这个月」的唯一身份。`grant_period` 是 `char(7)`，格式 `yyyy-MM`。

**这条索引有一个致命特性：任意一列为 `NULL`，整行不参与唯一性比较。**
所以 `grant_period` 留空不是「少填一个字段」，是「把整条防线拆了」。

### 3.2 模板（实测数据）

| id | name | type | member_level | valid_type | valid_days |
|---|---|---|---|---|---|
| 5 | 会员月度 10 元红包 | 3 会员红包 | 1（银卡起） | 2 领取后N天 | 30 |
| 6 | 黑卡专享 8 折（封顶30） | 3 会员红包 | 3（黑卡） | 2 | 30 |

### 3.3 等级权益

| level | 名称 | 成长值门槛 | monthly_coupon_cnt | discount_rate |
|---|---|---|---|---|
| 0 | 普通 | 0 | 0 | 100 |
| 1 | 银卡 | 500 | 2 | 98 |
| 2 | 金卡 | 2000 | 3 | 95 |
| 3 | 黑卡 | 8000 | 4 | 90 |

`monthly_coupon_cnt` 是**上限阀门**，不是实际发放数——实际发放数 = `min(上限, 该等级可见的模板数)`。
所以银卡实发 1 张、金卡 1 张、黑卡 2 张。这个分离很重要：运营加一张模板不用改等级配置。

---

## 四、核心一：幂等键由谁构造

| | Day 8 成长值 | Day 9 会员券 |
|---|---|---|
| 幂等键 | `biz_id`（业务方传入） | `(user_id, template_id, grant_period)` |
| 谁保证唯一 | 唯一索引 `uk_biz` | 唯一索引 `uk_member_period` |
| 键从哪来 | **调用方给**，改了就是新业务 | **服务端算**，算错就是静默重复 |
| 冲突结果 | `INSERT IGNORE` 返回 0 | 同左 |
| 失败模式 | 传 null → 索引失效 | 算错/漏填 → 索引失效 |

Day 8 的坑是「用户把有序号当 bizId」；Day 9 的坑是「用户忘了填 grantPeriod 或格式写成 `2026-1`」。

### 格式是契约

`DateTimeFormatter.ofPattern("yyyy-MM")`，两个细节：

- **必须 `MM` 不是 `M`**：`M` 会让 1 月变成 `2026-1`，长度 6，和 `char(7)` 不匹配；更要命的是 `2026-1` 和 `2026-01` 会被当成两个周期，同一个月能发两次。
- **必须 `yyyy` 不是 `YYYY`**：`Y` 是「周所在年」。2026-01-01 属于 2025 年的最后一周，会被格式化成 `2025-01`——每年 1 月准时错一次，而且只在跨年那一周发作，测试根本发现不了。

---

## 五、核心二：`grantToUser` 的最终形态

```java
@Override
@Transactional(rollbackFor = Exception.class)
public int grantToUser(Long userId, int level, String period) {
    List<CouponTemplate> tpls = pickTemplates(level);
    int granted = 0;
    LocalDateTime now = LocalDateTime.now();
    for (CouponTemplate tpl : tpls) {
        UserCoupon uc = new UserCoupon();
        uc.setUserId(userId);
        uc.setTemplateId(tpl.getId());
        uc.setCouponType(tpl.getType());
        uc.setShopId(tpl.getShopId());
        uc.setSource(CouponConst.SRC_MEMBER);
        uc.setExpireTime(tpl.getValidType() != null
                && tpl.getValidType() == CouponConst.VALID_FIXED
                ? tpl.getValidEnd()
                : now.plusDays(tpl.getValidDays() == null ? 0 : tpl.getValidDays()));
        uc.setGrantPeriod(period);
        granted += userCouponMapper.insertIgnoreGrant(uc);
    }
    return granted;
}
```

### 5.1 七个字段的来源

| 字段 | 值 | 为什么 |
|---|---|---|
| `userId` | 方法参数 | 最容易写错的一个——详见第七节 v1~v5 |
| `templateId` | `tpl.getId()` | 唯一索引的第二个分量 |
| `couponType` | `tpl.getType()` | 从模板**冗余**，"我的券"列表页免 join |
| `shopId` | `tpl.getShopId()` | 同上，会员券通常是 0（全平台） |
| `source` | `CouponConst.SRC_MEMBER` = 4 | 不是 `SRC_SYSTEM`=3，见 5.2 |
| `expireTime` | `validType=1` → `getValidEnd()`；否则 `now + validDays` | 见 5.3 |
| `grantPeriod` | 方法参数 `period` | 唯一索引的第三个分量，**必须非空** |

`status` 和 `receiveTime` 不填：前者 SQL 里写死 `UC_UNUSED`，后者用数据库 `NOW()`。

### 5.2 `source` 为什么必须是 4

`CouponConst` 里两个都「看起来对」：

- `SRC_SYSTEM = 3`：系统发放
- `SRC_MEMBER = 4`：会员月度

`source` 的用途是**事后统计「这张券是哪个渠道来的」**。用 3 的话，「会员月度发了多少张」这个问题永远查不出来——它和新人礼包、活动赠券混在一个值里了。这类错误的代价不在当时，在三个月后运营拉报表的时候。

⚠️ 一个更隐蔽的错法：填 `tpl.getStatus()`。`status` 是模板上下架（0/1），库里在售模板都是 1，而 `source=1` 正好是「付费购买」——不报错、能用券，只是所有会员券都被记成了用户自己买的。

### 5.3 `expireTime` 不要在 SQL 里算

`receive_time` 用 `NOW()`（数据库时钟），`expire_time` 用 Java 算好传进去。会不会差几毫秒？会，但 30 天有效期差几毫秒无所谓。

反过来如果 `expire_time` 也用 `DATE_ADD(NOW(), INTERVAL valid_days DAY)`，就得先查 `valid_days` 再拼 SQL，一条语句变两条，还得处理 `valid_type=1` 的分支。**能用一次查询解决的不要拆成两次。**

⚠️ 另一个错法：填 `tpl.getCreateTime()`。模板是 2026-10-22 建的，10 月建、发一年，每个领到的人都该从**领取那一刻**起算 30 天。用创建时间的话，现在发出去的券 `expire_time` 是 10 月 22 日——**到手即过期**，且列表页显示正常，只能在用券时才报「券已过期」。

---

## 六、核心三：事务边界在单用户

```java
// MemberCouponGrantTask.grantAll —— 故意没有 @Transactional
for (Member m : members) {
    try {
        int n = grantService.grantToUser(m.getUserId(), m.getLevel(), p);
        ...
    } catch (Exception e) {
        result.setFailedUsers(result.getFailedUsers() + 1);
        log.error(...);
    }
}
```

**为什么批量不加事务**：500 人一个事务，第 480 人失败会把前 479 人已发的券全部回滚，而且 500 条 INSERT 会把行锁持有到全部结束。

**为什么这个拆分是必须的，不只是「分层好看」**：批量循环在 `Task`（另一个 Bean），单用户发放在 `Service`。如果两者写在同一个类里、用 `this.grantToUser(...)` 调用，**Spring 代理被绕过，`@Transactional` 直接失效且不报错**。注解还在、日志干净、就是没有事务，只能从上线的脏数据里发现。

**为什么切小了还安全**：发券不需要整批原子性，它需要**可重入**。失败的人下次重跑，发过的人返回 0，没发的人补上——这正是唯一索引给的底气。

`MemberGrantResultVO.consistent()` 是配套的自检：`grantedUsers + skippedUsers + failedUsers == userTotal`。等不上就说明循环里有出口漏了统计，这个问题不会让任务失败，只会让报表说谎，所以单独 `log.warn`。

---

## 七、真实的试错过程（5 轮）

| 版本 | 卡在哪 |
|---|---|
| v1 | `tpl = new UserCoupon()`——`CouponTemplate` 变量装 `UserCoupon`，**类型不兼容编译不过**；下一行写 `uc.set...` 但 `uc` 未定义。而且把循环变量覆盖掉，模板 id 就再也拿不到了 |
| v2 | 只写了 `setUserId(tpl.getId())`，其余 6 个字段全空；`if (tpls == null) return -1`（`pickTemplates` 返回空列表不是 null，死分支）；`granted` 从未累加 |
| v3 | 删掉了重复调用的 `insertIgnoreGrant`（对），但 `userId` 仍写 `tpl.getId()`，6 字段仍空 |
| v4 | 把「该填什么」写成了**空括号**：`setCouponType()` / `setShopId()` / `setSource()` / `setExpireTime()` → 4 个编译错误「无法将方法应用到给定类型」 |
| v5 | 字段都填了，但 `source = tpl.getStatus()`（→1 付费购买）、`expireTime = tpl.getCreateTime()`（→券到手即过期 10 天）、`userId` 仍是 `tpl.getId()` |
| v6 | 全对，实测通过 |

### 值得记的两个细节

**① 前 3 轮的代码在接口层全是「成功」。** 字段全空时 `INSERT IGNORE` 依然返回 1，`grantedUsers` 依然 +1，日志里看不出任何异常。唯一能发现的方式是查库。

**② `setUserId(tpl.getId())` 错了 4 轮。** 原因是 `Long` 赋给 `Long`，编译器一句话不说，而模板 id（5、6）恰好是合法的用户 id。这意味着券会静静地发到「可爱多」「user_xn5wr3hpsv」名下——两个从未开通过会员的用户，会在「我的券」里凭空看到会员专属券，而真正的会员一张都收不到。

---

## 八、一个实证：`INSERT IGNORE` 到底会吞掉什么

按 v2 的代码拼出的 SQL（只给 `user_id`，其余全 `NULL`）直接打进库：

```sql
INSERT IGNORE INTO tb_user_coupon
  (user_id,template_id,coupon_type,shop_id,source,status,receive_time,expire_time,grant_period)
VALUES (1016,NULL,NULL,NULL,NULL,0,NOW(),NULL,NULL);
```

结果：

```
影响行数 = 1        ← 没有报错
```

入库的这一行是：

```
template_id=0  coupon_type=0  source=0  expire_time=0000-00-00 00:00:00  grant_period=NULL
```

`sql_mode` 里明明有 `STRICT_TRANS_TABLES`，本该报 `Field doesn't have a default value`；但 `INSERT IGNORE` 把它降级成 warning，塞进隐式默认值后返回「成功」。

更糟的是第二次。同一条语句再打一次，**仍然返回 1**：

```
第二次影响行数 = 1
该用户 template_id=0 的行数 = 2
```

因为 `grant_period=NULL`，`uk_member_period` 对这两行完全不生效。

**结论：`INSERT IGNORE` 换来的是「重复时能继续循环」，代价是「所有警告级错误都被吞掉」。所以验收必须查库，不能只看返回值。**

---

## 九、验收实测

环境：临时实例 8081，银卡测试会员 `1016`（`level=1, growth=600`），模板 5。

### 9.1 预演

```json
{"period":"2026-11","userTotal":1,"couponTotal":1,"couponsByLevel":{"1":1},"note":"只读预演，未写入任何数据"}
```

### 9.2 连打三次 grant

| 次数 | grantedUsers | skippedUsers | failedUsers | couponTotal |
|---|---|---|---|---|
| 1 | 1 | 0 | 0 | 1 |
| 2 | 0 | 1 | 0 | 0 |
| 3 | 0 | 1 | 0 | 0 |

库里始终 **1 行**。

### 9.3 入库数据

| 字段 | 值 | 判定 |
|---|---|---|
| `user_id` | 1016 | ✅ |
| `template_id` | 5 | ✅ 真实模板，不是 0 |
| `coupon_type` | 3 | ✅ 会员红包 |
| `shop_id` | 0 | ✅ 全平台 |
| `source` | 4 | ✅ 会员月度 |
| `status` | 0 | ✅ 未使用 |
| `receive_time` | 2026-11-02 12:31:54 | ✅ 数据库时钟 |
| `expire_time` | 2026-12-02 12:31:55 | ✅ 发券 + 30 天 |
| `grant_period` | 2026-11 | ✅ 非空 |

### 9.4 并发

5 个 `POST /coupon/member/grant` 同时打（先手工删掉本月那一行，让它们真的竞争）：

```
req_1:200 req_2:200 req_3:200 req_4:200 req_5:200
并发后本月券行数 = 1
```

只有一个请求拿到 `grantedUsers=1`，其余是 `skippedUsers=1`。没有报错、没有重复。

### 9.5 反向校验

| 检查 | 结果 |
|---|---|
| `user_id` 5 和 6（非会员）是否被误发 | **0 行** |
| `template_id = 0` 的废数据 | **0 行** |
| `grant_period` 非空且 `expire_time < NOW()` 的过期券 | **0 行** |
| `/coupon/my` 是否可见 | 可见，`expired=false`、`stackable=true`、`total=4` |

---

## 十、五个坑（面试可讲）

### 坑 1：`grant_period` 为 NULL，唯一索引整行失效

MySQL 的唯一索引允许多个 NULL。`(1016, 5, NULL)` 可以出现无数次。这不是「少填一个字段」，是把幂等防线整个拆掉。

Day 8 的 `biz_id` 也是同一个道理——所以 `addGrowth` 第一行就校验 `bizId == null`。

### 坑 2：`catch DuplicateKeyException` 救不了事务

如果不用 `INSERT IGNORE` 而用 `insert()` + catch：异常被 catch 住，但事务已经被标记 `rollback-only`，方法返回 `false` 看着正常，外层提交时抛 `UnexpectedRollbackException`，接口 500。

`INSERT IGNORE` 从根上避免了这个局面——它不抛异常，事务状态干净。

### 坑 3：先 `selectCount` 再插，判据就没了

```java
if (count == 0) { insert(); }   // 两个线程同时查到 0，然后都去插
```

判据必须交给数据库的唯一索引，应用层的 `if` 只是自己骗自己。

### 坑 4：`@Transactional` 加在批量方法上

500 人一个事务：1 个失败回滚前 479 个成功的券；500 条 INSERT 持锁到结束。而且这种设计**没法重跑**——整批原子意味着要么全成功要么全回滚，可发券本来就不需要这个性质。

### 坑 5：同类自调用让事务静默失效

`this.grantToUser(...)` 绕过 Spring 代理。注解在、日志干净、就是不生效。所以批量循环在 `Task`、单用户发放在 `Service`，跨 Bean 调用代理才起作用。

---

## 十一、遗留项（上线前处理，不阻塞 Day 10）

| # | 问题 | 处理时机 |
|---|---|---|
| 1 | `/coupon/member/grant` 只校验「已登录」，任何用户都能触发全站发放 | Day 19 加管理员校验。好在幂等且只发应得的券，被乱点最多提前触发本月 |
| 2 | 批量发放**同步**跑在 HTTP 请求里，500 人会明显卡住 | 改异步任务 + 进度查询 |
| 3 | `pickTemplates` 每个用户查一次模板表 → 典型 N+1 | Day 11 用批量 `IN` + 内存分组 |
| 4 | `@Scheduled` 在多实例部署时会重复触发 | 靠唯一索引兜住（已有），也可以加分布式锁 |
| 5 | `/member/growth/earn` 是自测接口，允许登录用户给自己加任意成长值 | 上线前删除或加 `@Profile("dev")` |

---

## 十二、面试口径

**Q：月度发券怎么保证不重复？**

A：唯一索引 `(user_id, template_id, grant_period)` + `INSERT IGNORE`。判断是否发过完全交给数据库，应用层不做 `selectCount` 再插入——那种写法两个线程会同时查到 0。发过的返回 0，调用方记成「跳过」继续处理下一个人。

**Q：为什么不整批一个事务？**

A：发券需要的是可重入，不是整批原子。整批事务会让 1 个失败回滚前面几百个已经发成功的券，而且 500 条 INSERT 持锁到结束。切成一人一事务后，失败的单独重跑，重跑安全正是因为唯一索引让发放幂等。

**Q：`@Transactional` 为什么没生效过？**

A：同类里 `this.method()` 自调用绕过 Spring 代理。所以批量循环放在 `Task`、单用户放在 `Service`，跨 Bean 才走代理。这个问题不报错，只能从上线的脏数据里发现。

**Q：`INSERT IGNORE` 有什么风险？**

A：它会吞掉所有警告级错误。实测过：字段全 NULL 时 `INSERT IGNORE` 依然返回 1，入库的是 `template_id=0 / expire_time='0000-00-00'`，一句报错都没有。所以验收必须查库，不能只看返回值。这也是为什么 `expire_time`、`source` 这些字段要有测试覆盖。

**Q：幂等键为什么不能由客户端传？**

A：客户端传 `period=2026-09` 就能补发历史月份的券，传个不存在的月份就能刷券。周期只能服务端从当前日期算，请求参数最多给运维补发用，且必须校验格式。

**Q：`yyyy` 和 `YYYY` 有什么区别？**

A：`y` 是日历年，`Y` 是周所在年。2026-01-01 属于 2025 年第 53 周，`YYYY-MM` 会格式化成 `2025-01`，直接跨年错位。这个 bug 每年 1 月发作一次，平时测不出来。同理 `M` 和 `MM`——少一个 `M`，1 月变成 `2026-1`，和 `char(7)` 对不上，还会和 `2026-01` 被当成两个周期。

---

## 十三、Day 10 预告

券的**排序与区间查询**：用 ZSet 存「即将过期的券」，`ZRANGEBYSCORE` 按时间取；`ZRANGEBYLEX` 按字典序做周期区间扫描。

难点会转移：Day 8、9 的幂等键是精确匹配（等值），Day 10 是**范围查询**——score 相同的成员按 member 字典序排，而字典序依赖字符串补零，`2026-1` 和 `2026-01` 在这里又会咬人一次。
