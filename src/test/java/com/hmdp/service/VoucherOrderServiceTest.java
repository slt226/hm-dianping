package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.TraceIds;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 秒杀下单入口（{@code seckillVoucher}）分支覆盖。
 * <p>
 * 这条链路的价值在<strong>失败时的收尾</strong>：Lua 预扣成功、MQ 投递失败，
 * 必须把 Redis 的库存和已购标记还回去。还错了就是库存错乱，而且线上看不出异常。
 * <p>
 * 与 {@link SeckillDeadLetterServiceTest} 的两个差异点：
 * <ol>
 *   <li>被测类继承 MyBatis-Plus 的 {@code ServiceImpl}，{@code baseMapper} 一定是 null，
 *       但本方法一行都没用它，所以<strong>不要去 mock</strong>；</li>
 *   <li>依赖了静态 ThreadLocal 的 {@code UserHolder}，必须每个用例前后清干净。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("秒杀下单：登录/售罄/重复/脚本异常/投递失败回滚/成功")
class VoucherOrderServiceTest {

    private static final Long VOUCHER_ID = 10L;
    private static final Long USER_ID = 7L;
    private static final Long ORDER_ID = 123L;

    private static final String STOCK_KEY = "seckill:stock:" + VOUCHER_ID;
    private static final String USER_KEY = "seckill:user:" + VOUCHER_ID;
    private static final String EXCHANGE = "seckill.exchange";
    private static final String ROUTING_KEY = "seckill.key";

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private ISeckillVoucherService seckillVoucherService;

    @Mock
    private RedisIdWorker redisIdWorker;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    /** 第二层 mock：opsForValue()，锁、库存自增都走它 */
    @Mock
    private ValueOperations<String, String> valueOps;

    /** 第二层 mock：opsForSet()，回滚时移除已购标记 */
    @Mock
    private SetOperations<String, String> setOps;

    @InjectMocks
    private VoucherOrderServiceImpl service;

    // ------------------------------------------------------------------ 环境

    @BeforeEach
    void setUp() {
        // 上一个用例可能残留了用户，不清会串数据：单独跑绿、一起跑红就是这么来的
        UserHolder.removeUser();
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
        // traceId 也存在 ThreadLocal（MDC）里，同样要清，否则会串到下一个用例的日志断言
        TraceIds.clear();
    }

    // ------------------------------------------------------------------ 工具

    private void login() {
        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        user.setNickName("测试用户");
        UserHolder.saveUser(user);
    }

    /** 时间窗必须显式给：beginTime / endTime 为 null 时那两个 if 分支不可达，用例会"恰好"通过 */
    private static SeckillVoucher validMeta() {
        return new SeckillVoucher()
                .setVoucherId(VOUCHER_ID)
                .setStock(100)
                .setBeginTime(LocalDateTime.now().minusHours(1))
                .setEndTime(LocalDateTime.now().plusHours(1));
    }

    /** Lua 脚本的返回码：0 预扣成功、1 库存不足、2 已下过单 */
    private void stubLuaReturns(long code) {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn(code);
    }

    // ------------------------------------------------------------------ 用例

    @Test
    void 未登录_直接拒绝且不碰任何依赖() {
        Result res = service.seckillVoucher(VOUCHER_ID);

        assertFalse(res.getSuccess());
        assertEquals("请先登录", res.getErrorMsg());
        verifyNoInteractions(stringRedisTemplate, seckillVoucherService, redisIdWorker, rabbitTemplate);
    }

    @Test
    void 库存不足_不生成订单号也不投递() {
        login();
        when(seckillVoucherService.queryMetaById(VOUCHER_ID)).thenReturn(validMeta());
        stubLuaReturns(1L);

        Result res = service.seckillVoucher(VOUCHER_ID);

        assertFalse(res.getSuccess());
        assertEquals("库存不足", res.getErrorMsg());
        verifyNoInteractions(redisIdWorker, rabbitTemplate);
    }

    @Test
    void 重复下单_不生成订单号也不投递() {
        login();
        when(seckillVoucherService.queryMetaById(VOUCHER_ID)).thenReturn(validMeta());
        stubLuaReturns(2L);

        Result res = service.seckillVoucher(VOUCHER_ID);

        assertFalse(res.getSuccess());
        assertEquals("每人限抢一张", res.getErrorMsg());
        verifyNoInteractions(redisIdWorker, rabbitTemplate);
    }

    @Test
    void 脚本返回空_归为系统异常而不是当成成功() {
        login();
        when(seckillVoucherService.queryMetaById(VOUCHER_ID)).thenReturn(validMeta());
        // 故意不 stub execute：Redis 超时 / 脚本没加载时返回的就是 null

        Result res = service.seckillVoucher(VOUCHER_ID);

        assertFalse(res.getSuccess());
        assertEquals("系统异常", res.getErrorMsg());
        verifyNoInteractions(redisIdWorker, rabbitTemplate);
    }

    @Test
    void 投递失败_回滚Redis预扣() {
        login();
        when(seckillVoucherService.queryMetaById(VOUCHER_ID)).thenReturn(validMeta());
        stubLuaReturns(0L);
        when(redisIdWorker.nextId("voucher-order")).thenReturn(ORDER_ID);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        doThrow(new AmqpException("broker down"))
                .when(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq(ROUTING_KEY),
                        any(Object.class), any(MessagePostProcessor.class), any(CorrelationData.class));

        Result res = service.seckillVoucher(VOUCHER_ID);

        assertFalse(res.getSuccess());
        assertEquals("系统繁忙，请稍后重试", res.getErrorMsg());
        // 回滚的两个副作用，缺一个都会留下"库存少一张 + 用户再也抢不了"的脏数据
        verify(valueOps).increment(STOCK_KEY);
        verify(setOps).remove(USER_KEY, String.valueOf(USER_ID));
    }

    @Test
    void 投递成功_返回订单号() {
        login();
        when(seckillVoucherService.queryMetaById(VOUCHER_ID)).thenReturn(validMeta());
        stubLuaReturns(0L);
        when(redisIdWorker.nextId("voucher-order")).thenReturn(ORDER_ID);
        // 关键：CorrelationData 是在 seckillVoucher 内部 new 出来的，我们拿不到引用，
        // 只能在 mock 的 convertAndSend 里把它取出来，手动完成 future。
        // 不这么做，第 102 行 get(1, SECONDS) 会真的阻塞 1 秒然后抛 TimeoutException，
        // 用例会"莫名其妙"走进 catch 分支返回"系统繁忙"，还慢 1 秒。
        // ⚠️ 任务 6 之后这个方法是 5 参重载（中间多了一个 MessagePostProcessor，用来塞 traceId），
        //    所以 CorrelationData 的位置从 3 变成了 4。生产代码改签名，测试必须同步改。
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.getFuture().set(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq(ROUTING_KEY),
                any(Object.class), any(MessagePostProcessor.class), any(CorrelationData.class));

        Result res = service.seckillVoucher(VOUCHER_ID);

        assertTrue(res.getSuccess());
        assertEquals(ORDER_ID, res.getData());
        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq(ROUTING_KEY),
                any(Object.class), any(MessagePostProcessor.class), any(CorrelationData.class));
        // 成功路径不该动回滚逻辑
        verify(stringRedisTemplate, never()).opsForSet();
    }

    /**
     * 任务 6 的 traceId 跨线程传递：MDC 是 ThreadLocal，消费者在另一个线程，
     * 唯一能带过去的就是消息头。这条用例把 MessagePostProcessor 抓出来手动执行一遍，
     * 断言它确实把当前线程的 traceId 写进了 header —— 这是"日志能不能串起来"的根。
     */
    @Test
    void 投递消息头带上traceId() throws Exception {
        login();
        when(seckillVoucherService.queryMetaById(VOUCHER_ID)).thenReturn(validMeta());
        stubLuaReturns(0L);
        when(redisIdWorker.nextId("voucher-order")).thenReturn(ORDER_ID);
        TraceIds.put("trace-abc");

        ArgumentCaptor<MessagePostProcessor> mppCaptor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.getFuture().set(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq(ROUTING_KEY),
                any(Object.class), mppCaptor.capture(), any(CorrelationData.class));

        service.seckillVoucher(VOUCHER_ID);

        Message outbound = mppCaptor.getValue().postProcessMessage(new Message(new byte[0]));
        assertEquals("trace-abc", outbound.getMessageProperties().getHeaders().get(TraceIds.MQ_HEADER));
    }
}
