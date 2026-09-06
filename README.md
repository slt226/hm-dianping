# 黑马点评（hm-dianping）

一个基于 Spring Boot + Redis 的本地生活服务平台（类大众点评），涵盖短信登录、商户缓存、优惠券秒杀、达人探店、好友关注、Feed 流、附近商户、用户签到、UV 统计九大模块。

项目源自黑马程序员《Redis 实战篇》课程，在此基础上做了若干工程化改造（见 [二次改造](#二次改造)）。

| 技术                | 版本                       |
| ----------------- | ------------------------ |
| JDK               | 1.8（已在 JDK 17 上实测可编译运行）  |
| Spring Boot       | 2.3.12.RELEASE           |
| MyBatis-Plus      | 3.4.3                    |
| Spring Data Redis | 2.3.9（Lettuce）           |
| RabbitMQ          | spring-boot-starter-amqp |
| MySQL             | 5.7（驱动 5.1.47）           |
| Hutool            | 5.7.17                   |

---

## 功能与 Redis 结构对照

| 模块    | 业务能力           | Redis 结构                 | 关键实现                         |
| ----- | -------------- | ------------------------ | ---------------------------- |
| 短信登录  | 验证码登录、Token 鉴权 | String                   | Redis 存登录态替代 Session，双拦截器续期  |
| 商户缓存  | 商铺详情缓存         | String(JSON) + String(锁) | 空值缓存防穿透、互斥锁防击穿、随机 TTL 防雪崩    |
| 优惠券秒杀 | 高并发抢券、一人一单     | String + Set + Lua       | Lua 原子预扣 → RabbitMQ 异步落库     |
| 达人探店  | 笔记点赞、点赞榜       | ZSet                     | `ZSCORE` 判重、`ZRANGE` 取 Top5  |
| 好友关注  | 关注/取关、共同关注     | Set                      | `SINTER` 求交集、`SCARD` 计数      |
| 关注推送  | 关注流时间线         | ZSet                     | 推模式收件箱 + 滚动分页                |
| 附近商户  | 按距离搜店          | GEO                      | `GEOADD` 预热、`GEORADIUS` 检索   |
| 用户签到  | 每日签到、连续签到      | BitMap                   | `SETBIT` 写、`BITFIELD` 位运算算连续 |
| UV 统计 | 独立访客           | HyperLogLog              | 拦截器 `PFADD`，`PFMERGE` 区间合并   |

---

## 核心设计

### 1. 登录：双拦截器 + Token 续期

传统单拦截器方案里，登录态 TTL 一到就强制下线，用户正逛着突然被踢。本项目拆成两个拦截器：

```
请求 → [0] RefreshTokenInterceptor  有 token 就从 Redis 取用户 → UserHolder，并续期 TTL（不拦截）
     → [1] LoginInterceptor         校验需登录接口，UserHolder 为空返回 401
     → [2] UserTrafficInterceptor   已登录则 PFADD uv:当天（旁路记 UV）
     → Controller
```

`UserHolder` 基于 `ThreadLocal`，在 `afterCompletion` 中清理，避免内存泄漏。返回前端的 token 存的是 `UserDTO` 而非 `User`，不泄露密码等字段。

### 2. 缓存：三大问题逐个击破

| 问题     | 方案                            | 实现位置                                     |
| ------ | ----------------------------- | ---------------------------------------- |
| 缓存穿透   | 空值缓存（TTL 2min）                | `ShopServiceImpl#queryWithPassThrough`   |
| 缓存击穿   | 互斥锁（`SET key v NX EX`）+ 双重检查  | `ShopServiceImpl#queryWithMutex`         |
| 缓存雪崩   | TTL + `ThreadLocalRandom` 随机值 | 同上                                       |
| 热点 key | 逻辑过期（异步重建，不阻塞请求）              | `ShopServiceImpl#queryWithLogicalExpire` |

一致性采用 Cache Aside：更新时**先改 DB 再删缓存**。反过来做会因为并发把旧值写回缓存。

### 3. 秒杀：Lua 原子预扣 + MQ 异步落库

```
请求 → Lua 原子预扣(Redis) → 生成订单 ID → 发 MQ → 立即返回 orderId
                                              ↓ 异步
                           消费者：DB 乐观锁扣库存 + 插订单 → 失败回滚 Redis
```

预扣脚本 `src/main/resources/seckill.lua`：

```lua
local stock = redis.call('GET', KEYS[1])
if (not stock) or (tonumber(stock) <= 0) then return 1 end          -- 库存不足
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return 2 end  -- 已下过单
redis.call('DECR', KEYS[1])                                          -- 预扣库存
redis.call('SADD', KEYS[2], ARGV[1])                                 -- 记录用户
return 0
```

Redis 单线程执行 Lua 期间不会被打断，把「查库存 → 判重复 → 扣减 → 记录」四步合为原子操作，杜绝超卖和重复下单。绝大多数请求在 Redis 层就被挡掉，DB 只承担真实成交的写入量。

**三道防线**：Redis Lua 预扣（挡并发）→ DB 乐观锁 `stock > 0`（防超卖）→ 唯一索引（防重复下单）。任一环节失败，消费者回滚 Redis 预扣。

订单 ID 由 `RedisIdWorker` 生成，64 bit = 符号位(1) + 秒级时间戳差(31) + 日维度自增序列(32)，全局唯一且趋势递增，对 InnoDB 聚簇索引友好。

### 4. Feed 流：推模式 + 滚动分页

发笔记时把笔记 id 推进每个粉丝的收件箱 `feed:{userId}`（ZSet，score = 笔记 id）。读关注流时直接查自己的收件箱，避免拉模式遍历所有关注对象的开销。

分页不用 `LIMIT offset, size`——关注流实时变化，页码分页会漏读或重复。改用游标 `(max = 上页最小 score, offset = 同分跳过数)`，每次只取比 max 小的下一批。

### 5. 降级策略

Redis 在项目里是**加速层而非依赖**：所有 Redis 操作都做了异常处理，登录失败退化为未登录、缓存失败退化为直查 DB、GEO 失败退化为按类型分页、UV 记录失败直接忽略。Redis 整体不可用不会导致服务雪崩。

---

## 二次改造

在原课程实现基础上做的改动：

1. **秒杀异步落库改用 RabbitMQ**。原方案用 JVM 内存阻塞队列（`BlockingQueue`），进程重启或宕机会丢失尚未落库的订单。改为 RabbitMQ 持久化队列（`seckill.exchange` / `seckill.queue` / `seckill.key`）+ 独立消费者类 `VoucherOrderConsumer`，消息不丢，且消费者可独立扩容。
2. **JDK 17 编译适配**。父工程默认的 Lombok 1.18.20 注解处理器依赖 JDK 内部 API `com.sun.tools.javac.code.TypeTag`，该常量在 JDK 16+ 已被移除，编译会抛 `ExceptionInInitializerError`。已将 Lombok 覆盖为 1.18.34，并在 `spring-boot-maven-plugin` 补充 `--add-opens` 参数。
3. **UV 统计下沉到拦截器**。独立 `UserTrafficInterceptor` 对已登录用户旁路 `PFADD`，业务代码零侵入。

---

## 快速开始

### 环境要求

- JDK 8 及以上（17 已验证）
- Maven 3.6+
- MySQL 5.7+
- Redis 5+（需支持 GEO、BitMap、HyperLogLog）
- RabbitMQ 3.8+（仅秒杀模块需要）
- Nginx（仅前端需要）

### 1. 初始化数据库

```bash
mysql -u root -p -e "CREATE DATABASE hmdp DEFAULT CHARACTER SET utf8mb4;"
mysql -u root -p hmdp < src/main/resources/db/hmdp.sql
```

共 11 张表：`tb_user`、`tb_user_info`、`tb_shop`、`tb_shop_type`、`tb_blog`、`tb_blog_comments`、`tb_follow`、`tb_voucher`、`tb_seckill_voucher`、`tb_voucher_order`、`tb_sign`。

### 2. 修改配置

`src/main/resources/application.yaml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
    username: root
    password: root
  redis:
    host: 127.0.0.1
    port: 6379
    password: 123321
  rabbitmq:
    host: 127.0.0.1
    port: 5672
    username: guest
    password: guest
```

后端端口 8081。

### 3. 启动后端

```bash
mvn spring-boot:run
```

或在 IDEA 中运行 `com.hmdp.HmDianPingApplication`。

### 4. 启动前端

前端是纯静态页面，由 Nginx 托管并反向代理后端接口：

```nginx
server {
    listen       8080;
    location / {
        root   html/hmdp;
        index  index.html;
    }
    location /api {
        rewrite /api(/.*) $1 break;   # 剥掉 /api 前缀
        proxy_pass http://127.0.0.1:8081;
        proxy_pass_request_headers on;
    }
}
```

访问 `http://localhost:8080` 即可。前端请求统一带 `/api` 前缀，由 Nginx 剥掉前缀后转发到 8081；直接访问后端时**不需要** `/api`。

### 5. 验证

```bash
# 发送验证码（验证码打印在后端日志里，未接入真实短信服务）
curl -X POST "http://localhost:8081/user/code?phone=13800138000"

# 登录，返回 token
curl -X POST http://localhost:8081/user/login \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800138000","code":"123456"}'

# 查询商铺（走缓存，命中返回 cache:shop:1）
curl http://localhost:8081/shop/1
```

---

## 项目结构

```
src/main/java/com/hmdp/
├── HmDianPingApplication.java
├── config/
│   ├── MvcConfig.java              拦截器注册（顺序 0/1/2）
│   ├── MybatisConfig.java          分页插件
│   ├── RabbitMqConfig.java         秒杀交换机、队列、JSON 序列化
│   └── WebExceptionAdvice.java     全局异常处理
├── controller/                     9 个控制器
├── service/impl/
│   ├── ShopServiceImpl.java        缓存三件套（穿透/击穿/逻辑过期）
│   ├── VoucherOrderServiceImpl.java   秒杀主流程
│   ├── VoucherOrderConsumer.java   MQ 消费者，异步落库
│   ├── BlogServiceImpl.java        探店笔记 + 点赞 + Feed 推送
│   ├── FollowServiceImpl.java      关注关系双写 Set
│   └── UserServiceImpl.java        登录/注册 + 签到
├── utils/
│   ├── RefreshTokenInterceptor.java   Token 续期
│   ├── LoginInterceptor.java          登录校验
│   ├── UserTrafficInterceptor.java    UV 采集
│   ├── RedisIdWorker.java             全局 ID 生成器
│   └── RedisConstants.java            全部 Key 前缀与 TTL
└── resources/
    ├── seckill.lua                 秒杀原子预扣脚本
    └── db/hmdp.sql                 建表与测试数据
```

---

## Redis Key 一览

| Key                                  | 类型          | 说明                   |
| ------------------------------------ | ----------- | -------------------- |
| `login:code:{phone}`                 | String      | 短信验证码，TTL 2min       |
| `login:token:{token}`                | String      | 登录态，TTL 10h          |
| `cache:shop:{id}`                    | String      | 商铺缓存，TTL 30min + 随机  |
| `cache:shopType:list`                | String      | 商铺类型列表               |
| `lock:shop:{id}`                     | String      | 缓存重建互斥锁，TTL 10s      |
| `seckill:stock:{voucherId}`          | String      | 秒杀库存                 |
| `seckill:user:{voucherId}`           | Set         | 已抢用户（一人一单）           |
| `icr:{prefix}:{yyyyMMdd}`            | String      | 全局 ID 序列，按天归零        |
| `follows:{userId}` / `fans:{userId}` | Set         | 关注 / 粉丝集合            |
| `feed:{userId}`                      | ZSet        | 关注流收件箱，score = 笔记 id |
| `blog:liked:{blogId}`                | ZSet        | 笔记点赞用户，score = 时间戳   |
| `shop:geo:{typeId}`                  | GEO         | 商铺坐标，按类型分组           |
| `sign:{userId}:{yyyyMM}`             | BitMap      | 月签到位图                |
| `uv:{yyyyMMdd}`                      | HyperLogLog | 当日独立访客               |

---

## 主要接口

| 分组               | 接口                                                  | 说明             |
| ---------------- | --------------------------------------------------- | -------------- |
| `/user`          | `POST /code`、`POST /login`、`GET /me`                | 验证码、登录、当前用户    |
| `/user`          | `POST /sign`、`GET /sign/count`                      | 签到、连续签到天数      |
| `/user`          | `GET /uv/today`、`GET /uv`                           | 今日 UV、区间 UV    |
| `/shop`          | `GET /{id}`、`GET /of/type`、`GET /of/name`           | 商铺查询（含 GEO 分页） |
| `/shop-type`     | `GET /list`                                         | 商铺类型           |
| `/voucher`       | `GET /list/{shopId}`、`POST /seckill`                | 优惠券列表、新增秒杀券    |
| `/voucher-order` | `POST /seckill/{id}`                                | **秒杀下单**       |
| `/blog`          | `POST`、`PUT /like/{id}`、`GET /of/follow`、`GET /hot` | 笔记发布、点赞、关注流    |
| `/blog-comments` | `POST`                                              | 笔记评论           |
| `/follow`        | `PUT /{id}/{isFollow}`、`GET /common/{id}`           | 关注取关、共同关注      |
| `/upload`        | `POST /blog`                                        | 图片上传           |

除上表中登录相关、商铺浏览、优惠券浏览、`/blog/hot` 外，其余接口均需登录，请求头携带 `authorization: {token}`。

---

## 已知问题

- 短信验证码只打印在日志中，未接入真实短信服务商。
- 分布式锁释放直接 `delete`，未校验持有者，生产环境建议用 Lua 比对 value 再删。
- UV 区间合并用的临时 key `uv:count` 没有设置 TTL，会残留在 Redis 中。
- 商铺缓存重建的互斥锁用递归重试，极端并发下可能栈深，生产建议改为自旋 + 超时。
- 秒杀库存需人工同步到 Redis（`seckill:stock:{id}`），新增秒杀券后要手动预热。

---

## 说明

本项目为个人学习用途，基于黑马程序员公开课程实现并做了工程化改造。业务逻辑与数据表结构参考原课程设计，异步落库、编译环境适配等部分为自行实现。
