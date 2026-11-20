package com.hmdp.service;

import com.hmdp.dto.DeadLetterReplayVO;
import com.hmdp.entity.SeckillDeadLetter;
import com.hmdp.exception.BizException;
import com.hmdp.mapper.SeckillDeadLetterMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.impl.SeckillDeadLetterServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 死信人工重放（{@code replay}）分支覆盖。
 * <p>
 * 为什么这个类最该测：它是全项目<strong>唯一</strong>由人手动触发、且会改 Redis 库存的入口。
 * 写错一行就是「凭空空多几张券」，而这种错误在页面上完全看不出来。
 * <p>
 * 测试策略：不启动 Spring 容器（无 {@code @SpringBootTest}），三个依赖全部 mock，
 * 只验证 {@code replay} 自己的分支走向和副作用。一个用例的耗时应该在毫秒级。
 * <p>
 * 断言重点不是返回值，而是<strong>副作用</strong>：锁有没有释放、库存有没有被 +1、
 * 该不走的补偿链路有没有被误闯。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("死信重放：五条拒绝分支 + 两条放行分支")
class SeckillDeadLetterServiceTest {

    private static final Long ID = 1L;
    private static final Long ORDER_ID = 100L;
    private static final Long VOUCHER_ID = 10L;
    private static final Long USER_ID = 7L;

    @Mock
    private SeckillDeadLetterMapper deadLetterMapper;

    @Mock
    private VoucherOrderMapper voucherOrderMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    /** 第二层 mock：opsForValue() 的返回值，不打这一层，when(...) 在准备阶段就 NPE */
    @Mock
    private ValueOperations<String, String> valueOps;

    /** 第二层 mock：opsForSet() 的返回值 */
    @Mock
    private SetOperations<String, String> setOps;

    @InjectMocks
    private SeckillDeadLetterServiceImpl service;

    // ------------------------------------------------------------------ 工具

    private static SeckillDeadLetter record(int status, int retryCount) {
        return new SeckillDeadLetter()
                .setId(ID)
                .setOrderId(ORDER_ID)
                .setVoucherId(VOUCHER_ID)
                .setUserId(USER_ID)
                .setReason("投递失败")
                .setStatus(status)
                .setRetryCount(retryCount);
    }

    /**
     * 抢锁成功。锁和 doneKey 用的是同一个 setIfAbsent，TTL 单位不同（秒 / 小时），
     * 所以用 anyLong() + any() 一次覆盖两种调用，不必写两条 stub。
     */
    private void stubLockAcquired() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);
    }

    /** 回补链路需要 opsForSet().remove(...)，不 stub 会 NPE 并被 catch 吞成"补偿失败" */
    private void stubCompensateWritable() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
    }

    /**
     * 锁释放断言。注意 unlockReplayLock 走的是 Lua 脚本 execute，不是 delete，
     * 而脚本常量是 private，只能按类型匹配。
     */
    private void verifyReplayLockReleased() {
        verify(stringRedisTemplate).execute(any(RedisScript.class), anyList(), anyString());
    }

    // ------------------------------------------------------------------ 拒绝分支

    @Test
    void id为空_直接抛异常且不碰任何依赖() {
        BizException ex = assertThrows(BizException.class, () -> service.replay(null));

        assertTrue(ex.getMessage().contains("不能为空"), "异常文案应说明是 id 为空");
        // 连锁都不该去抢：参数校验必须在任何 IO 之前
        verifyNoInteractions(stringRedisTemplate, deadLetterMapper, voucherOrderMapper);
    }

    @Test
    void 抢锁失败_提示正在重放且不释放别人的锁() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(false);

        BizException ex = assertThrows(BizException.class, () -> service.replay(ID));

        assertTrue(ex.getMessage().contains("正在重放中"));
        verifyNoInteractions(deadLetterMapper, voucherOrderMapper);
        // 抢锁失败时 throw 发生在 try 之前，绝不能执行 finally 里的解锁脚本去删别人的锁
        verify(stringRedisTemplate, never()).execute(any(RedisScript.class), anyList(), anyString());
    }

    @Test
    void 记录不存在_抛异常但锁必须被释放() {
        stubLockAcquired();

        BizException ex = assertThrows(BizException.class, () -> service.replay(ID));

        assertTrue(ex.getMessage().contains("不存在"));
        verifyReplayLockReleased();
        verifyNoInteractions(voucherOrderMapper);
    }

    @Test
    void 已补偿记录_拒绝重放且不进补偿链路() {
        stubLockAcquired();
        when(deadLetterMapper.selectById(ID)).thenReturn(record(SeckillDeadLetter.STATUS_COMPENSATED, 1));

        BizException ex = assertThrows(BizException.class, () -> service.replay(ID));

        assertTrue(ex.getMessage().contains("已补偿"));
        // compensateAndMark 的第一行就是 voucherOrderMapper.selectById：它没被调用，即证明根本没进补偿
        verifyNoInteractions(voucherOrderMapper);
        verify(deadLetterMapper, never()).update(isNull(), any());
        verify(deadLetterMapper, never()).updateById(any());
        verifyReplayLockReleased();
    }

    // ------------------------------------------------------------------ 放行分支

    @Test
    void 正常重放_归还库存且重放次数加一() {
        stubLockAcquired();
        stubCompensateWritable();
        // 同一个 id 被查两次：第 1 次取记录、第 2 次取重放后的状态。
        // retry_count 是 SQL 里 +1 的，内存对象不会变，所以第二次必须返回另一个实例才能验出 +1。
        when(deadLetterMapper.selectById(ID))
                .thenReturn(record(SeckillDeadLetter.STATUS_PENDING, 2),
                        record(SeckillDeadLetter.STATUS_COMPENSATED, 3));

        DeadLetterReplayVO vo = service.replay(ID);

        assertTrue(vo.getCompensated(), "本次应真的归还了 Redis 预扣");
        assertEquals(SeckillDeadLetter.STATUS_PENDING, vo.getStatusBefore());
        assertEquals(SeckillDeadLetter.STATUS_COMPENSATED, vo.getStatusAfter());
        assertEquals(3, vo.getRetryCount().intValue(), "重放次数应已被 SQL 加一");

        verify(valueOps).increment("seckill:stock:" + VOUCHER_ID);
        verify(setOps).remove("seckill:user:" + VOUCHER_ID, String.valueOf(USER_ID));
        verify(deadLetterMapper).update(isNull(), any());
        verify(deadLetterMapper).updateById(any());
        verifyReplayLockReleased();
    }

    @Test
    void 已忽略记录_允许重放而不是被拦住() {
        stubLockAcquired();
        stubCompensateWritable();
        // 第 234 行只拦 STATUS_COMPENSATED，IGNORED(2) 是允许重放的：
        // 误判死信后订单其实没落库，人复核完需要能把库存还回去
        when(deadLetterMapper.selectById(ID))
                .thenReturn(record(SeckillDeadLetter.STATUS_IGNORED, 1),
                        record(SeckillDeadLetter.STATUS_COMPENSATED, 2));

        DeadLetterReplayVO vo = service.replay(ID);

        assertEquals(SeckillDeadLetter.STATUS_IGNORED, vo.getStatusBefore(), "应记录重放前的状态是已忽略");
        assertTrue(vo.getCompensated());
        verify(valueOps).increment("seckill:stock:" + VOUCHER_ID);
        verifyReplayLockReleased();
    }
}
