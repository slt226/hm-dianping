# Day 8 会员与成长值 · 复盘

> 第二周开工第一天。用途：Day 19 写 README / 简历时的原料，以及面试前 5 分钟速查。
> 所有数字都是本地实测（临时实例 8081，MySQL 3306，Redis 6379）。
>
> 配套教学文档：`docs/Day8-成长值幂等.md`（动手前读的推演版）。
> 本文是**做完之后**的复盘版：记录最终形态、真实试错过程、验收数据。

---

## 一、一句话概括

Day 8 真正有技术含量的只有一个方法——`MemberServiceImpl.addGrowth`。
其余全是脚手架（实体、Mapper、查接口、领券门槛接入），
而那个方法的核心也不是"写 SQL"，是**想清楚谁来做幂等的判据**。

**答案：数据库的唯一索引，不是应用层的 if。**

---

## 二、交付清单

| 层 | 文件 | 谁写的 | 说明 |
|---|---|---|---|
| 表 | `tb_member` / `tb_member_level` / `tb_growth_log` | 施工手册已备 | 唯一索引 `uk_user_reason_biz (user_id, reason, biz_id)` 是幂等的地基 |
| 实体 | `entity/Member` `MemberLevel` `GrowthLog` | 脚手架 | 原来三个都是空壳 `public class X {}` |
| Mapper | `mapper/MemberMapper` | 脚手架 | 三个方法都下沉到 SQL：`initIfAbsent` / `addGrowth` / `upgradeLevel` |
| Mapper | `mapper/GrowthLogMapper` | **你** | 加 `insertIgnore`（`INSERT IGNORE`） |
| Mapper | `MemberLevelMapper` `GrowthLogMapper` | 脚手架 | 故意留空，逼你想清楚用 `insert` 还是 `insertIgnore` |
| DTO | `MemberVO` `GrowthLogVO` `GrowthEarnRequest` | 脚手架 | VO 里带 `growthToNext` / `progress`，前端不做算术 |
| Service | `IMemberService` / `MemberServiceImpl` | 读方法脚手架，`addGrowth` **你** | — |
| Controller | `MemberController` | 脚手架 | 含一个**生产不该存在**的自测接口 |
| 常量 | `utils/MemberConst` | 脚手架 | 等级、6 种 reason、1 元 = 1 成长值、单笔上限 500 |
| 接入 | `UserCouponServiceImpl.claim()` | 脚手架 | 原 `TODO(day8)` 换成真实等级校验 |

---

## 三、数据模型

### 3.1 三张表的分工

| 表 | 角色 | 关键设计 |
|---|---|---|
| `tb_member_level` | **规则**：等级怎么定 | 4 行配置，`growth_threshold` 是进入该等级的门槛 |
| `tb_member` | **余额**：用户现在多少分、几级 | `growth` 与 `level` 分开存：前者是累计值，后者是"只升不降"的结果 |
| `tb_growth_log` | **账本**：每一笔怎么来的 | 只增不改；`(user_id, reason, biz_id)` 唯一 |

`tb_member` 和 `tb_growth_log` 的关系就是**余额与流水**：
余额必须等于流水求和，这条恒等式是所有对账 SQL 的起点。

### 3.2 等级配置（实测数据）

| level | 名称 | 门槛 | 折扣 | 权益 |
|---|---|---|---|---|
| 0 | 普通会员 | 0 | 无 | — |
| 1 | 银卡会员 | 500 | 9.8 折 | 每月 2 张红包 |
| 2 | 金卡会员 | 2000 | 9.5 折 | 每月 4 张红包 |
| 3 | 黑卡会员 | 8000 | 9.0 折 | 每月 8 张红包 + 专属客服 |

`levelCache` 在 `@PostConstruct` 时全量加载并按 `level` 升序排好。
**这个顺序是"遍历取最后一个满足门槛的等级"这条逻辑的前提**，
所以 `loadLevels()` 里的 `orderByAsc("level")` 不能删——删了等级会乱升。

---

## 四、接口（4 个）

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/member/info` | 是 | 当前等级、成长值、距下一级还差多少、`progress` 百分比、折扣 |
| GET | `/member/levels` | 是 | 全部等级配置（前端渲染权益对比表） |
| GET | `/member/growth/logs` | 是 | 成长值流水分页，`reason` 翻译成中文 |
| POST | `/member/growth/earn` | 是 | **自测/压测入口，生产必须删** |

`earn` 这个接口让登录用户能给自己加成长值，等于把会员体系白送。
留它唯一的原因：验证幂等必须能并发打同一个 `bizId`。
参数做了两层收敛（`amount` 限 ±500、`reason` 限 1~6），且流水可回溯。

---

## 五、核心：`addGrowth` 的最终形态

### 5.1 执行顺序（十步，每一步的位置都有理由）

| # | 动作 | 为什么必须在这个位置 |
|---|---|---|
| 1 | 校验 `userId` / `bizId` 非空 | 唯一索引对 NULL **不生效**（多个 NULL 互不冲突），`bizId` 为 null 时幂等静默失效 |
| 2 | 校验 `amount != 0` | 零值变动写流水没意义，只会污染账本 |
| 3 | `initIfAbsent(userId)` | 必须在 `addGrowth` 之前。会员行不存在时 `UPDATE` 影响 0 行且**不报错**，会变成"流水记了、分数没加" |
| 4 | 构造 `GrowthLog` | — |
| 5 | `insertIgnore` | **先记账后改余额**。账没记上说明这单处理过，余额一分都不能动 |
| 6 | `inserted == 0` → `return false` | 立刻返回，不做任何其他写入 |
| 7 | `addGrowth(userId, amount)` | 一条 `UPDATE ... SET growth = growth + ?`，原子自增 |
| 8 | `selectOne` 读回最新值 | 读的必须是**更新后**的数据库值，不是本地 `oldGrowth + amount` |
| 9 | 遍历 `levelCache` 算 `target` | 扫完整个列表才确定，不能在循环里写库 |
| 10 | `target > 当前等级` → `upgradeLevel` | `>` 而非 `>=`，少一次必然影响 0 行的 UPDATE |

顺序的关键只有一句：**流水是"账"，成长值是"余额"，先记账再改余额。**
反过来先改余额，冲突发生时余额已经脏了。

### 5.2 代码

```java
@Override
@Transactional(rollbackFor = Exception.class)
public boolean addGrowth(Long userId, int amount, int reason, Long bizId) {
    if (userId == null || bizId == null) throw new BizException("参数不能为空");
    if (amount == 0) throw new BizException("成长值变动不能为 0");
    memberMapper.initIfAbsent(userId);

    GrowthLog log = new GrowthLog();
    log.setUserId(userId);
    log.setChangeAmount(amount);
    log.setReason(reason);
    log.setBizId(bizId);

    int inserted = growthLogMapper.insertIgnore(log);
    if (inserted == 0) {
        return false;                       // 这单处理过了
    }

    memberMapper.addGrowth(userId, amount);

    Member fresh = memberMapper.selectOne(
            new QueryWrapper<Member>().eq("user_id", userId));
    if (fresh == null) {
        throw new BizException("会员信息异常");
    }

    int target = MemberConst.LEVEL_NORMAL;
    for (MemberLevel l : levelCache) {
        if (fresh.getGrowth() >= l.getGrowthThreshold()) {
            target = l.getLevel();
        }
    }
    if (target > fresh.getLevel()) {
        memberMapper.upgradeLevel(userId, target);
    }
    return true;
}
```

### 5.3 为什么 `insertIgnore` 而不是 `insert + catch`

| | 重复 bizId 时 | `inserted == 0` | 事务状态 |
|---|---|---|---|
| `insert()` | 抛 `DuplicateKeyException` | **永远不成立** | 已标记 **rollback-only** |
| `insertIgnore()` | 静默跳过，返回 0 | 成立 | 干净 |

`insert + catch` 的坑在于：**catch 住异常后方法还能 return，但事务已经废了**。
外层提交时抛 `UnexpectedRollbackException`，接口 500，
而且堆栈指向提交点，看不出真正原因——这是最容易卡一整天的地方。

---

## 六、真实的试错过程（4 轮）

这段比结论更有价值：**面试如果问"你怎么保证幂等"，能讲出收敛过程比背结论可信。**

| 轮次 | 状态 | 存在的问题 |
|---|---|---|
| v1 | 能编译，跑不通 | 占位 `throw` 没删；`initIfAbsent` 放在最后；循环里 `updateById`；`target` 与 `growthThreshold` 比大小（类型同为 int，语义不同）；`bizId` 校验放在写库之后 |
| v2 | 顺序对了 | 仍旧用 `insert()`（幂等分支是死代码）；仍用 `updateById`（升级永不发生 + 整行覆盖丢更新）；残留两段死代码 |
| v3 | 逻辑正确 | 缺 `@Transactional`，4 条 SQL 是 4 个独立事务 |
| v4 | 完整 | 补上事务注解 |

### 每一轮的具体错法（留作反面教材）

**① `if (target >= l.getGrowthThreshold())`**
`target` 是等级（0~3），`threshold` 是成长值（0/500/2000/8000）。
两者都是 `int`，**编译器不会报错**，但判断永远成立——类型相同、语义不同，
是这类 bug 最容易漏网的地方。

**② 在 `for` 循环里调 `updateById(fresh)`**
三个错叠在一起：循环 4 次更新 4 次；`fresh` 的 `level` 还是旧值，
写回去等于没升级；`updateById` 是整行更新，把 `growth` 按字面量写回，
并发下会覆盖掉别人刚加的分数——**这才是真正的丢更新**。

**③ 用 `updateById` 而不是 `upgradeLevel`**
`upgradeLevel` 的 SQL 自带 `level < #{level}`，只升不降且只碰 `level` 一列。
`updateById` 把"哪些列要写"的决定权交给了对象状态，而对象状态是过期的。

---

## 七、验收数据（实测）

测试用户 1016，起始 `tb_member` 无行。每组测完都核对
`growth` 与 `SUM(change_amount)` 是否相等。

### 7.1 幂等：同一 bizId 连打 3 次（+10）

| 次 | 返回 | `growth` | 流水行数 |
|---|---|---|---|
| 1 | 加分成功 | 10 | 1 |
| 2 | 该业务已加过分，本次忽略 | 10 | 1 |
| 3 | 同上 | 10 | 1 |

### 7.2 丢更新：10 个不同 bizId 并发（各 +10）

| 指标 | 结果 |
|---|---|
| HTTP | 10 × 200 |
| `growth` | **110**（= 10 + 100） |
| 流水行数 / 合计 | 11 行 / 110 |

**结论：并发下没有丢更新**——因为 `growth = growth + ?` 是一条语句，
读写在同一把行锁内完成。

### 7.3 唯一索引兜底：10 个并发请求打同一 bizId（+50）

| 指标 | 结果 |
|---|---|
| HTTP | 10 × 200（全部走完，无一 500） |
| `growth` | 510 → **560**（只加了 50） |
| `biz_id=5001` 流水行数 | **1** |

**结论：10 个线程同时"通过"前置检查，最终只有一个 INSERT 成功。判据在数据库，不在应用层。**

### 7.4 跨档升级（+400 → 510）

```json
{"level":1,"levelName":"银卡会员","growth":510,
 "nextLevelName":"金卡会员","nextThreshold":2000,
 "growthToNext":1490,"discountRate":98}
```

升级逻辑正确：510 跨过 500 门槛，`level` 0 → 1。

### 7.5 恒等式

所有测试结束后：`growth` = `SUM(change_amount)`，一分不差。
这条式子就是成长值对账 SQL，可以直接进 Day 19 的 README。

---

## 八、五个坑的原理（面试可讲）

### 坑 1：唯一索引对 NULL 不生效
MySQL 认为 `NULL != NULL`，所以 `(user_id, reason, NULL)` 可以重复插入无限行。
幂等键里有可空字段时，**必须在入口拒绝 null**，不能指望索引兜底。

### 坑 2：`catch` 住 `DuplicateKeyException` 救不了事务
异常被 Spring 标记 `rollback-only` 是在抛出点，不在 catch 点。
catch 只能让代码继续执行，改不了事务的最终结局。

### 坑 3：`growth = growth + ?` 不能拆成两步
拆成 `select` → 计算 → `update` 就有时间窗：两个线程都读到 100、都写 120，实际应为 140。
Day 3 扣库存踩过同一个坑，这里是同一个原理的第二次应用。

### 坑 4：升级判断必须读更新后的值
`oldGrowth + amount` 是本地推测值，并发下会算低。
必须 `UPDATE` 之后 `SELECT` 读回——此时该行已被自己的 UPDATE 加了写锁，
读回的是确定值，也是这条"先写后读"顺序的唯一理由。

### 坑 5：`initIfAbsent` 必须在 `addGrowth` 之前
`UPDATE ... WHERE user_id = ?` 在行不存在时**影响 0 行且不抛异常**。
这个静默失败不会留下任何日志，只会表现为"流水有了，分数没涨"。

---

## 九、遗留项（不阻塞 Day 9）

| 项 | 现状 | 建议 |
|---|---|---|
| `@Transactional` 缺 `rollbackFor` | 当前 `@Transactional` 无参数 | `BizException extends RuntimeException`，默认规则能回滚，**功能上没有 bug**；但项目规范建议写全 `rollbackFor = Exception.class`，将来改成受检异常也不会漏 |
| `reason` 未在 Service 层校验 | 只在 `MemberController` 拦 1~6 | 订单流程将来直接调 Service 会绕过；建议在 Service 补一句兜底 |
| 负数金额的业务边界 | 任何 reason 都接受负数 | 「只有 `REASON_REFUND` / `REASON_EXPIRE` 允许负数」是业务规则，待定 |
| `/member/growth/earn` | 自测接口 | Day 19 上线前删除，或加 `@Profile("dev")` |
| 代码风格 | `int inserted=` 缺空格、残留 TODO 注释 | 提交前统一格式化 |

---

## 十、面试口径

**Q：怎么保证成长值不被重复加？**

> 用业务唯一键做幂等。流水表上 `(user_id, reason, biz_id)` 建唯一索引，
> 插入用 `INSERT IGNORE`：插进去说明是第一次，返回 0 说明这单处理过了。
> 判据在数据库，不在应用层，所以多实例部署也成立。
>
> 为什么不用"先 `selectCount` 再插入"：两个线程同时查到 0 行，然后都去加分，
> 成长值翻倍。这是典型的 check-then-act 竞态。

**Q：为什么先写流水再改余额？**

> 流水是账、余额是缓存性质的聚合值。账没记上说明这单被处理过，
> 余额一分都不能动；反过来先改余额，冲突发生时余额已经脏了，
> 而且没有账本可以回溯修正。
>
> 整个方法在一个事务里，任何一步失败连同流水一起回滚，重试还能正常进行。

**Q：`INSERT IGNORE` 和 `insert + catch DuplicateKeyException` 有什么区别？**

> 后者 catch 住异常代码能继续走，但异常在抛出点已经把事务标记成 rollback-only，
> 外层提交时抛 `UnexpectedRollbackException`。
> 而且 `INSERT IGNORE` 返回 0 是正常返回值，事务状态干净，判断也更直观。

**Q：并发加分会丢更新吗？**

> 不会。`UPDATE tb_member SET growth = growth + ?` 是一条语句，
> 读和写在同一把行锁内完成。拆成 `select` + `update` 才会丢——
> 两个线程都读到 100、都写 120，实际应该是 140。

**Q：升级为什么要先 UPDATE 再 SELECT？**

> 因为要用数据库里的真实值判级。`oldGrowth + amount` 是本地推测，
> 并发下会算低导致漏升级。UPDATE 之后该行已有写锁，
> 此时读回的是确定值。

---

## 十一、Day 9 预告

**唯一索引幂等发放会员券**。

Day 8 用唯一索引解决"同一笔业务不重复加分"，
Day 9 用同一把刀解决"同一周期不重复发券"——
`tb_user_coupon` 上已有的 `uk_member_period`，
配合 `INSERT IGNORE`，做每月 1 号自动发放。

区别在于：Day 8 的幂等键来自请求参数（`bizId`），
Day 9 的幂等键要在服务端**自己构造**（`user_id + template_id + 年月`），
构造错了会漏发或重发，这是新的难点。
