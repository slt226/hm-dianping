# Day 11 批量发放：从「发得对」到「发得动、查得到」

> Day 9 解决的是**正确性**：同一个人同一个月只能有一张券，靠唯一索引兜底。
> 今天解决的是**工程性**：一万个会员跑一遍要多久、跑挂了怎么续、事后怎么证明发过。
>
> 这两件事的技术含量完全不同。前者是设计，后者是账。

---

## 一、先算账：现在的代码要打多少次库

`MemberCouponGrantTask.grantAll` 是一个 `for (Member m : members)` 的串行循环，
每次循环调 `grantService.grantToUser(...)`，而 `grantToUser` 第一行就是 `pickTemplates(level)`。

按 **1000 个会员、每人 2 张券** 估算：

| 操作 | 次数 | 说明 |
|---|---|---|
| `listGrantableMembers` | 1 | 全量扫一次 |
| `templateMapper.selectList`（在 `pickTemplates` 里） | **1000** | 完全重复的同一条 SQL |
| `insertIgnoreGrant` | 2000 | 每人 2 张，必要开销 |
| `selectOne` 回查 id | 2000 | Day 10 为了挂索引补的，必要开销 |
| `ZADD`（临期索引 + 发放索引） | 4000 | 必要开销 |
| **合计** | **≈ 9000** | 其中 1000 次是纯浪费 |

关键在于：`pickTemplates(int level)` 的**入参只有 level**。
而全系统会员等级只有 3~5 个。也就是说，这 1000 次查询里，不同的 SQL 只有 5 条。

这就是 N+1 的经典形态：**外层循环 N 次，每次查一次本该在循环外查好的东西**。

### 1.1 为什么现在没炸

因为会员数还少。1000 次单表查询，每次 1ms，加起来 1 秒，你感觉不到。
但这件事的代价不是线性的——它是 `O(N)`，而会员数会涨。到 10 万会员时，
这 10 万次查询会占掉整个任务的绝大部分时间，而且期间数据库连接池一直被占着，
**其他接口开始排队**。这才是 N+1 真正危险的地方：它不在发券接口上出事，它在别人身上出事。

---

## 二、三个任务

### 任务 1 · 先测量，再优化

**不许一上来就改代码。** 没有基线数字的优化，改完你无法证明它变好了。

步骤：

1. **造数据**：往 `tb_member` 里插 1000 条 `level > 0` 的会员。
   注意 `user_id` 要真实存在对应的 `tb_user` 行吗？——先想清楚这个问题再动手，
   想不明白就去读 `grantToUser` 里有没有外键约束（MySQL 层面有没有 `FOREIGN KEY`）。

2. **跑一次预演**：`GET /coupon/member/grant/preview?period=2026-12`
   记录 `userTotal`、`couponTotal`。

3. **跑一次真实发放**：`POST /coupon/member/grant?period=2026-12`
   记录接口耗时（用 `Measure-Command` 或浏览器 Network 面板）。

4. **数 SQL 条数**：项目已经开了 `log-impl: StdOutImpl`，日志里每条 SQL 都长这样：

   ```
   ==>  Preparing: SELECT id,name,... FROM tb_coupon_template WHERE type=? AND status=? AND member_level<=?
   ==> Parameters: 3(Integer), 1(Integer), 1(Integer)
   <==      Total: 2
   ```

   统计 `==>  Preparing:` 出现了多少次。

   ```powershell
   Select-String -Path .\logs\hmdp.log -Pattern "==>  Preparing:" | Measure-Object
   ```

   记下这个数字。**它是你今天所有改动的对照组。**

5. **数一下模板 SQL 重复了多少次**：把日志里所有 `Preparing` 行去重，看看有多少条是
   `FROM tb_coupon_template` 开头的。这个比值就是你浪费的比例。

**验收**：一张表，三列——总 SQL 数、模板 SQL 数、接口耗时。贴给我。

---

### 任务 2 · 消除模板查询的 N+1

这一条有个真正的**设计决策**，不是套个缓存注解就完事。三个方案摆在这里，你要选一个并说明理由：

| 方案 | 做法 | 优点 | 风险 |
|---|---|---|---|
| A | `pickTemplates` 上加 `@Cacheable(value="memberTpl", key="#level")` | 改动最小，一行注解 | 运营改模板后要等缓存过期；缓存和 DB 不一致时**发错券**，而发券是不可逆的 |
| B | 改签名，`grantAll` 开头查一次全量模板，按 level 分组后传给 `grantToUser` | 一次查询，无跨批次状态 | 要改接口方法签名；批次内拿到的是**快照**，发放中途运营下架模板不会生效 |
| C | 在 `MemberCouponGrantServiceImpl` 里用 `ConcurrentHashMap<Integer, List<CouponTemplate>>` 做进程内缓存 | 不用改签名 | 缓存失效靠什么？多实例各有一份，改完不同步 |

**必须先回答三个问题，再选方案**：

1. 发券是**幂等且不可撤销**的操作吗？如果缓存里是旧模板（比如面额配错了），发出去的券能收回吗？
2. 一次批量发放的正常时长是多少？运营在发放过程中改模板的概率有多大？
3. 如果选 A 或 C，缓存什么时候失效？靠 TTL 还是靠手动清？

**我的倾向写在文档最后，但你先自己想。**

改造后的期望形态（只描述形状，代码自己写）：

```
grantAll(period)
  ├─ members = listGrantableMembers()
  ├─ levels  = members 里出现过的 level 去重集合
  ├─ allTpls = 一次 selectList，条件 member_level <= max(levels)
  ├─ tplMap  = Map<level, List<CouponTemplate>>   // 内存分组 + 按 monthly_coupon_cnt 截断
  └─ for (member : members) {
         grantToUser(userId, level, period, tplMap.get(level))   // 模板从参数传进去
     }
```

注意两点：

- **`grantToUser` 的签名要变**。原来的 `int level` 参数在改造后只用于查 `monthly_coupon_cnt`，
  而 `monthly_coupon_cnt` 在 `levels()` 里是内存缓存，不需要打库。所以 level 可以保留，
  但模板列表必须由调用方传进来——否则它还得自己查。
- **接口 `IMemberCouponGrantService` 要改**。改了签名所有调用点都会编译报错，
  这是好事：编译器会帮你找全所有需要同步修改的地方（定时任务、Controller、测试）。
  如果没人报错，说明你改错了地方。

**验收**：同样的 1000 人再跑一遍，`Preparing` 总数下降约 1000 次，
`FROM tb_coupon_template` 的语句从 1000 条降到 1 条。耗时对比记录在案。

---

### 任务 3 · 批次日志：让「发过没有」可查

现在跑完一次发放，唯一的痕迹是 `log.info("会员月度券定时发放结束：{}", r)`。
三天后运营问「11 月这次到底发给了多少人、失败几个」，你只能翻日志——而日志是会滚动的。

要一张表：

```sql
CREATE TABLE tb_coupon_grant_batch (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    batch_no      VARCHAR(40)  NOT NULL COMMENT '批次号，建议 周期+时间戳',
    grant_period  CHAR(7)      NOT NULL COMMENT '发放周期 yyyy-MM',
    trigger_type  TINYINT      NOT NULL DEFAULT 1 COMMENT '1定时 2手动',
    user_total    INT          NOT NULL DEFAULT 0,
    granted_users INT          NOT NULL DEFAULT 0,
    skipped_users INT          NOT NULL DEFAULT 0,
    failed_users  INT          NOT NULL DEFAULT 0,
    coupon_total  INT          NOT NULL DEFAULT 0,
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0运行中 1全部成功 2部分失败 3整体失败',
    start_time    DATETIME     NOT NULL,
    end_time      DATETIME     NULL,
    cost_ms       BIGINT       NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_batch_no (batch_no),
    KEY idx_period (grant_period, start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员券发放批次';
```

设计上要自己想清楚四件事：

1. **`batch_no` 为什么不能只用 period 做唯一键？**
   提示：任务失败后要重跑，重跑是同一个 period。
2. **`status = 0` 运行中的批次，如果进程被 kill 了，永远停在 0。**
   谁来发现它？怎么区分「正在跑」和「跑挂了」？提示：看 `start_time` 和当前时间的差。
3. **写批次记录应该放在 `grantAll` 的开头还是结尾？**
   放结尾的话，进程中途挂掉就没有记录——恰恰是你最需要记录的时候。
4. **这条 INSERT 要不要和发券放在同一个事务里？**
   想清楚：批次记录的作用是「事后追溯」，不是「保证发放正确」。
   如果把它们绑在一起，一次发放失败会连带把记录回滚掉。

**验收**：跑一次发放，`SELECT * FROM tb_coupon_grant_batch` 能看到一行，
`granted_users + skipped_users + failed_users = user_total` 自洽，
`status` 与实际情况相符；再跑一次，出现第二行（同一个 period，不同 batch_no）。

---

## 三、验收总表

| 项 | 改造前 | 改造后（目标） |
|---|---|---|
| 1000 人总 SQL 数 | ≈ 9000 | ≈ 8000 |
| `tb_coupon_template` 查询次数 | 1000 | 1 |
| 接口耗时 | ？ | 下降 ≥ 30% |
| 发券结果 | 正确 | **必须仍然正确**（发券数、幂等、索引一致） |
| 批次可查 | 无 | 有，且统计自洽 |

第四行是硬门槛：**优化不许改变行为**。
改造后必须重跑一遍 Day 9 的验收——同一个人连发三次仍然只有一行、5 并发仍然只成功一次。

---

## 四、三个坑

### 坑 1：把 N+1 优化成「少查了但发错了」

如果选的方案是缓存，务必确认一件事：**缓存里的模板和数据库里的模板不一致时，谁说了算**。
发券是写操作，写操作引用旧配置 = 发出错误的券。这类错误不可撤销（券已经进用户口袋）。
所以我的倾向是**方案 B（批次内快照）**：一次发放拿一份一致的模板集合，
发完就丢，没有任何跨批次的状态需要维护。运营改了模板，下一次发放自然生效。

代价是「发放中途下架，本批次仍会发」——但这恰恰是合理的：
一次批量发放是个原子业务动作，它对配置的认知应该冻结在开始那一刻。

### 坑 2：改了接口签名，但漏了调用点

`IMemberCouponGrantService` 是接口，`grantToUser` 改签名后：
- `MemberCouponGrantTask.grantAll` 会编译报错（正确处理）
- `IMemberCouponGrantService` 的 javadoc 里写的契约也要同步改
- 如果有 Controller 直接调 `grantToUser`（比如单用户补发接口），也会报错

**编译通过不代表改全了**。跑一次 `mvn compile`，看报错列表，逐个确认「这个调用点是不是应该跟着改」。

### 坑 3：批次日志写成了「发放事务的一部分」

如果批次记录的 INSERT 和 `grantToUser` 在同一个事务里：

```
批次记录写入 → 发券 → 发券失败 → 整体回滚 → 批次记录也没了
```

结果就是「失败了，但没有任何记录证明它失败过」。批次记录必须**独立提交**，
所以它要么在事务外写，要么用 `REQUIRES_NEW` 传播级别。想清楚这一点再写。

---

## 五、面试口径

**Q：什么是 N+1 查询，你怎么发现和解决的？**

A：外层循环 N 次，每次触发一次本该批量做的查询，总查询数变成 N+1。
发现靠两件事：看 MyBatis 日志里同一条 SQL 重复出现，或者用 APM 看单请求的 SQL 数。
解决分两种：能一次查完的就批量 `IN` + 内存分组；不能一次查完的（比如依赖上一次结果）才考虑缓存。
这里我选的是前者——把「按等级取模板」改成「一次取全量，按 level 分组」，
因为等级只有几个，模板集合很小，批次内快照还能保证一致性。

**Q：为什么不直接加 @Cacheable？**

A：因为发券是写操作且不可撤销。缓存有 TTL，TTL 内运营改了模板，用户会拿到错误的券。
缓存适合读多写少、且旧值可以容忍的场景；发券刚好相反，它需要的是「本次发放的配置一致性」，
不是「跨请求的查询加速」。批次内快照同时满足这两点，还不引入缓存失效问题。

**Q：批量任务怎么保证可重跑？**

A：两层。业务层靠唯一索引 `(user_id, template_id, grant_period)`，
重跑时发过的人返回 0，天然幂等；运维层靠批次表记录每次运行的统计和状态，
失败的人可以从 `failed_users > 0` 的批次里定位出来单独补。
关键是**不要给整批加事务**——那会让「第 480 个人失败」变成「前 479 个人白干」。

---

## 六、留给你的思考题（不用写代码）

1. 服务部署两个实例，`@Scheduled` 会在两个实例上各跑一次。会发生什么？
   （提示：先想唯一索引能挡什么、不能挡什么——它能挡重复发券，但挡不住重复扫全表）
2. 任务跑到第 600 个人时进程被 kill。重跑时前 600 个人的 `insertIgnoreGrant` 全部返回 0，
   这 600 次「无意义的 INSERT」要不要优化掉？如果优化，按什么条件跳过？
3. `preview` 里也有一模一样的 N+1（`pickTemplates(m.getLevel())`）。
   它只读不写，能不能用缓存？如果能，和任务里的方案有什么不同？

第 2 题没有标准答案，但它决定你下一步要不要做「断点续跑」。

---

## 七、实测基线（1000 会员 / 每人 1 张券 / 2026-12 周期）

### 7.1 环境修正

原文档写的是 `log-impl: StdOutImpl`，实际必须改成 `Slf4jImpl` 才能统计：

```yaml
mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.slf4j.Slf4jImpl
```

`StdOutImpl` 直接把 SQL 写到 `System.out`，**不进 logback**，所以 `logs/hmdp.log` 里
一条 `Preparing` 都没有——数出来是 0，会被误判成"没有 SQL"。

统计命令（注意 `==>` 与 `Preparing:` 之间是两个空格，用 `\s+` 更稳）：

```powershell
(Select-String -Path .\logs\hmdp.log -Pattern "==>\s+Preparing:").Count
```

启动本身产生 **5 条** SQL（券模板库存预热、会员等级、临期索引重建、商铺预热、秒杀券预热），
这 5 条是固定基线，测差值时要减掉。

### 7.2 preview 基线

| 项 | 数值 |
|---|---|
| 单次 SQL 数 | **1001**（1 次会员列表 + 1000 次模板查询） |
| 耗时 | **1743 ms** |
| `userTotal` / `couponTotal` | 1000 / 1000 |

模板查询去重结果——**出现 2001 次（两次调用），只有 2 种 SQL**：

```
SELECT ... FROM tb_coupon_template WHERE (type = ? AND status = ? AND member_level <= ?) ORDER BY id ASC
SELECT id,remain_stock FROM tb_coupon_template
```

### 7.3 真实发放基线

| 项 | 第一次 | 第二次（幂等重跑） |
|---|---|---|
| SQL 数 | **3001** | **2001** |
| 耗时 | **8886 ms** | **5418 ms** |
| `grantedUsers` / `skippedUsers` | 1000 / 0 | 0 / 1000 |
| 库中行数 | 1000 | 1000（不变） |
| 每人张数分布 | `cnt=1, users=1000` | 不变 |

3001 的构成：

| 类型 | 次数 | 是否必要 |
|---|---|---|
| 会员列表 `FROM tb_member` | 1 | 必要 |
| 模板查询 `FROM tb_coupon_template` | **1000** | **纯浪费** |
| `INSERT IGNORE INTO tb_user_coupon` | 1000 | 必要 |
| `selectOne` 回查券 id（Day 10 挂索引用） | 1000 | 必要 |

### 7.4 从这组数字能读出什么

1. **浪费占比 33%**（1000/3001），但耗时不按比例分布——模板查询是简单单表查询，
   真正的成本是 3001 次网络往返的累加。
2. **第二次调用最值得看**：5418 ms、2001 条 SQL，业务结果全是 `skipped`，
   **这 5.4 秒没有产生任何价值**。它证明幂等是靠数据库挡的，不是靠减少工作量挡的。
3. 单条 SQL 平均耗时 1.7~2.9 ms，远高于单表查询的正常值，说明瓶颈在**往返次数**而非 SQL 本身。
   这就是"批量 IN + 内存分组"能提速的根本原因——不是 SQL 变快了，是次数变少了。
4. 会员等级只有 1 级参与（1000 人全是 level=1），模板参数空间只有 1 种组合，
   却查了 1000 次。参数空间与调用次数的比值是 1:1000。
