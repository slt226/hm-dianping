package com.hmdp.service;

import com.hmdp.dto.DeadLetterReplayVO;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.entity.SeckillDeadLetter;

import java.util.List;

/**
 * 秒杀死信记录服务（秒杀 P0 · 04）。
 * <p>
 * 把"落库"和"补偿"从消费者里独立出来，是因为它们要被<strong>两个入口</strong>复用：
 * <ul>
 *   <li>{@code SeckillDeadLetterConsumer}：消息刚进死信时调用一次；</li>
 *   <li>{@code SeckillReconcileTask}：每分钟把补偿失败的记录再试一次（自愈）。</li>
 * </ul>
 * 如果逻辑留在消费者里，对账任务就只能去调用一个 MQ 监听器——职责和依赖都会拧在一起。
 */
public interface ISeckillDeadLetterService {

    /**
     * 死信入口：先落库，再补偿，最后回写状态。
     * <p>
     * 顺序不能反：先补偿再落库，落库失败就丢了唯一凭据；
     * 落库和补偿也不能放进同一个事务——Redis 不参与 DB 事务，DB 回滚了 Redis 已经改了，反而制造新坏账。
     *
     * @param msg     消息体（已反序列化）
     * @param payload 原始报文（人工重放用）
     * @param reason  失败原因摘要
     */
    void handle(SeckillOrderMessage msg, String payload, String reason);

    /**
     * 补偿一条死信并回写状态。幂等：重复调用不会把库存加成两倍。
     *
     * @return true 表示本次真的归还了一次 Redis 预扣
     */
    boolean compensateAndMark(SeckillDeadLetter record);

    /**
     * 重试补偿"待补偿"的死信（对账任务的自愈阶段）。
     * <p>
     * 只处理进入死信超过 1 分钟的记录：正常路径上消费者早就补完了，
     * 这里兜的是"补偿时 Redis 抖动失败"的情况。
     *
     * @return 本次成功补偿的条数
     */
    int settlePending(int limit);

    /**
     * 某张券还有多少条死信没补偿 —— 对账的"在途门禁"输入之一。
     */
    long countPending(Long voucherId);

    /**
     * 死信列表（运维看板）：按进入死信时间倒序。
     *
     * @param status 可选，0待补偿 / 1已补偿 / 2已忽略；null 表示全部
     * @param limit  最多返回条数（上限 200，避免一次把整张表拖进内存）
     */
    List<SeckillDeadLetter> listByStatus(Integer status, int limit);

    /**
     * 人工重放一条死信（秒杀 P0 · 05）。
     * <p>
     * 只允许重放 <strong>待补偿(0)</strong> 和 <strong>已忽略(2)</strong>：
     * 已补偿(1) 的记录再补一次就是凭空空多一张券，直接拒绝。
     * <p>
     * 语义是"把状态打回待补偿，然后立刻补偿一次"：
     * <ul>
     *   <li>{@code retry_count} 原子 +1 —— 自动对账不会加它，只有人会加，所以它能区分
     *       "系统自愈了几次"和"人介入了几次"；</li>
     *   <li>真正的重复保护仍在 {@code compensateAndMark} 里（先查 DB 订单、再抢 SETNX），
     *       重放<strong>不会</strong>跳过这两道闸；</li>
     *   <li>为什么允许重放 2(已忽略)：人工可能已经把"误判"的订单删掉了，
     *       此时它就该被回补。若订单还在，重放会再次判定为忽略，没有任何副作用。</li>
     * </ul>
     *
     * @return 重放结果（含"本次是否真的动了库存"）
     * @throws com.hmdp.exception.BizException 记录不存在、已补偿、或正在重放中
     */
    DeadLetterReplayVO replay(Long id);
}
