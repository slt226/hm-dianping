package com.hmdp.controller;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * 压测对照组：<strong>不用 Redis，直接扣 DB</strong>（任务 5 · A 组）。
 * <p>
 * 存在的唯一目的，是给"Redis 到底挡掉了多少流量"提供一个可测量的分母。
 * 同一份 JMeter 脚本分别打 {@code /voucher-order/seckill/{id}} 和本接口，
 * 两轮的 QPS 与 DB 写请求数之差，就是秒杀链路里 Redis 预扣的价值。
 * <p>
 * 三条纪律：
 * <ol>
 *   <li><strong>独立路径</strong>：{@code /naive/seckill/**}，绝不往主链路里塞 {@code if (naive)} 开关 ——
 *       压测代码混进生产分支，面试官一问就答不上来；</li>
 *   <li><strong>账要平</strong>：一人一单靠 {@code uk_uid_vid} 唯一索引兜，撞索引时必须把刚才扣掉的
 *       DB 库存还回去，否则这组数据会表现为"少卖"，和主链路的对比就失真了；</li>
 *   <li><strong>可随时删除</strong>：本类不参与任何业务，删掉它不影响其它代码。</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/naive/seckill")
public class NaiveSeckillController {

    /** 与主链路同一个券；写死是为了让两组压测的条件完全一致 */
    private static final int ORDER_STATUS_UNPAID = 1;
    private static final int PAY_TYPE_BALANCE = 1;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private RedisIdWorker redisIdWorker;

    @PostMapping("/{voucherId}")
    public Result seckill(@PathVariable Long voucherId) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();

        // ① DB 乐观扣减：where stock > 0 是唯一的防超卖手段，并发下靠 InnoDB 行锁串行化
        boolean deducted = seckillVoucherService.update(
                new UpdateWrapper<SeckillVoucher>()
                        .setSql("stock = stock - 1")
                        .eq("voucher_id", voucherId)
                        .gt("stock", 0));
        if (!deducted) {
            return Result.fail("库存不足");
        }

        // ② 落订单，一人一单由 uk_uid_vid 唯一索引兜
        Long orderId = redisIdWorker.nextId("voucher-order");
        try {
            voucherOrderMapper.insert(new VoucherOrder()
                    .setId(orderId)
                    .setUserId(userId)
                    .setVoucherId(voucherId)
                    .setStatus(ORDER_STATUS_UNPAID)
                    .setPayType(PAY_TYPE_BALANCE)
                    .setCreateTime(LocalDateTime.now()));
        } catch (DuplicateKeyException dup) {
            // ③ 撞唯一索引 = 这个用户已经下过单，本次扣的库存要还回去。
            //    漏了这一步，A 组的"订单数"会小于"库存减少量"，变成假少卖，
            //    而报告的结论恰恰是拿这两个数做比的。
            seckillVoucherService.update(new UpdateWrapper<SeckillVoucher>()
                    .setSql("stock = stock + 1")
                    .eq("voucher_id", voucherId));
            return Result.fail("每人限抢一张");
        }
        log.info("【压测对照-DB直扣】下单成功 | orderId={}, userId={}, voucherId={}", orderId, userId, voucherId);
        return Result.ok(orderId);
    }
}
