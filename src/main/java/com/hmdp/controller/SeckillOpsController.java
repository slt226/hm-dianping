package com.hmdp.controller;

import com.hmdp.dto.QueueHealthVO;
import com.hmdp.dto.Result;
import com.hmdp.monitor.QueueAlertJudge;
import com.hmdp.monitor.QueueProbe;
import com.hmdp.monitor.SeckillQueues;
import com.hmdp.service.ISeckillDeadLetterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 秒杀运维入口（秒杀 P0 · 05）：死信列表 + 人工重放 + 队列健康。
 * <p>
 * 为什么单独开一个 controller 而不是塞进 {@code VoucherOrderController}：
 * 前者是"用户下单"，后者是"值班的人止血"，两类调用者的权限、频率、出错后果都不同。
 * 混在一起，将来给用户接口放行时很容易把重放接口一起放出去。
 * <p>
 * <strong>权限走 {@code AdminInterceptor}（userId 必须等于 {@link com.hmdp.utils.AdminConst#ADMIN_ID}），
 * 在 {@code MvcConfig} 里注册到 {@code /seckill/ops/**}</strong>；未登录/非管理员直接 403，
 * 所以方法体里不再重复判空 —— 重放会动库存，这道闸必须只有一个明确的实现。
 */
@RestController
@RequestMapping("/seckill/ops")
public class SeckillOpsController {

    @Resource
    private ISeckillDeadLetterService deadLetterService;

    /** 队列名不再写死在控制器里：与告警任务共用 {@code seckill.alert.queues} 这份名单 */
    @Resource
    private SeckillQueues seckillQueues;

    @Resource
    private QueueProbe queueProbe;

    @Resource
    private QueueAlertJudge queueAlertJudge;

    /**
     * 死信列表，按进入死信时间倒序。
     * <p>
     * 典型用法：{@code ?status=0} 看"还没补的"（这些会被算进对账的在途量），
     * 不传则看全部，用于复盘"这个券到底坏过几次"。
     *
     * @param status 可选，0待补偿 / 1已补偿 / 2已忽略
     * @param limit  最多条数，默认 50，上限 200
     */
    @GetMapping("/dead-letters")
    public Result deadLetters(@RequestParam(required = false) Integer status,
                              @RequestParam(defaultValue = "50") int limit) {
        return Result.ok(deadLetterService.listByStatus(status, limit));
    }

    /**
     * 人工重放一条死信。
     * <p>
     * 三步语义：<strong>打回待补偿(0) → retry_count+1 → 立刻补偿一次</strong>。
     * 已补偿(1) 的记录会被拒绝（再补 = 库存多一张）；已忽略(2) 允许重放，
     * 因为人工可能已经把误判的订单删掉了。返回体里的 {@code compensated}
     * 明确告诉调用方"这次到底有没有动库存"。
     */
    @PostMapping("/dead-letters/{id}/replay")
    public Result replay(@PathVariable Long id) {
        return Result.ok(deadLetterService.replay(id));
    }

    /**
     * 队列健康：名单里每条队列的 ready / consumers / 告警状态。
     * <p>
     * 这是"日志之外的第二个入口"：告警靠日志关键字，但值班的人需要一个能立刻刷新的页面。
     * 注意这里每次都真实探一次，不返回缓存 —— 缓存过的队列状态比没有更危险。
     */
    @GetMapping("/queues")
    public Result queues() {
        long now = System.currentTimeMillis();
        List<String> names = seckillQueues.names();
        List<QueueHealthVO> list = new ArrayList<>(names.size());
        for (String queue : names) {
            list.add(QueueHealthVO.of(queue, queueProbe.stat(queue), queueAlertJudge, now));
        }
        return Result.ok(list);
    }
}
