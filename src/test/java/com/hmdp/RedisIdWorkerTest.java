package com.hmdp;

import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RedisIdWorkerTest {

    @Autowired
    private RedisIdWorker idWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 测试1：基础生成 — 2次调用ID不同、均为正、近似单调递增
     */
    @Test
    void basicTwoIdsAreUniqueAndPositive() {
        long a = idWorker.nextId("test-basic");
        long b = idWorker.nextId("test-basic");
        assertTrue(a > 0, "ID应为正数");
        assertTrue(b > a, "第二次ID应严格大于第一次（同秒序列+1，跨秒时间戳高位+1）");
        assertNotEquals(a, b);
    }

    /**
     * 测试2：位布局验证 — 高31位 = 时间戳差值；低32位 = 自增序列
     */
    @Test
    void bitLayoutMatchesTimestampAndCount() {
        String prefix = "test-bit";
        // 先把当日key重置到100，方便精确比对
        String dateSuffix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String icrKey = RedisConstants.ICR_KEY_PREFIX + prefix + ":" + dateSuffix;
        stringRedisTemplate.delete(icrKey);

        long id1 = idWorker.nextId(prefix);
        long id2 = idWorker.nextId(prefix);

        long count1 = id1 & 0xFFFFFFFFL;
        long count2 = id2 & 0xFFFFFFFFL;
        assertEquals(1L, count1, "第一个ID的序列号应为1（INCR首次返回1）");
        assertEquals(2L, count2, "第二个ID的序列号应为2");

        long nowSecond = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        long tsDeltaExpectedMin = nowSecond - RedisConstants.ID_WORKER_BEGIN_TIMESTAMP - 2; // 容差2秒
        long tsDelta = id1 >>> RedisConstants.COUNT_BITS; // 无符号右移拿高位
        assertTrue(tsDelta >= tsDeltaExpectedMin,
                "时间戳差值异常，tsDelta=" + tsDelta + "，期望至少=" + tsDeltaExpectedMin);
    }

    /**
     * 测试3：10线程并发 100次，共1000个ID — 唯一性(Set size == 1000)、严格有序
     * （高并发时整体仍单调：同一秒内的ID严格因为INCR原子有序）
     */
    @Test
    void concurrentThousandIdsUnique() throws InterruptedException {
        int threadCnt = 10;
        int perThread = 100;
        int total = threadCnt * perThread;

        ExecutorService pool = Executors.newFixedThreadPool(threadCnt);
        CountDownLatch done = new CountDownLatch(threadCnt);
        List<Long> shared = Collections.synchronizedList(new ArrayList<>(total));
        AtomicInteger errCounter = new AtomicInteger();

        for (int i = 0; i < threadCnt; i++) {
            pool.submit(() -> {
                try {
                    for (int j = 0; j < perThread; j++) {
                        shared.add(idWorker.nextId("test-concurrent"));
                    }
                } catch (Exception e) {
                    errCounter.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(30, TimeUnit.SECONDS), "30s内并发任务未完成");
        pool.shutdown();
        assertEquals(0, errCounter.get(), "有线程抛异常");
        assertEquals(total, shared.size(), "生成数量不对");

        Set<Long> dedup = new HashSet<>(shared);
        assertEquals(total, dedup.size(), "存在重复ID！总数=" + total + " 去重后=" + dedup.size());

        // 整体单调：排序后前 <= 后
        List<Long> sorted = new ArrayList<>(shared);
        Collections.sort(sorted);
        for (int i = 1; i < sorted.size(); i++) {
            assertTrue(sorted.get(i) > sorted.get(i - 1),
                    "排序后第" + i + "个ID不严格递增：" + sorted.get(i - 1) + " vs " + sorted.get(i));
        }
    }

    /**
     * 测试4：不同业务 prefix 生成的序列号独立 — 两个业务同时生成，不会互相占用序列
     */
    @Test
    void differentPrefixesUseSeparateCounters() {
        String dateSuffix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        stringRedisTemplate.delete(RedisConstants.ICR_KEY_PREFIX + "bizA:" + dateSuffix);
        stringRedisTemplate.delete(RedisConstants.ICR_KEY_PREFIX + "bizB:" + dateSuffix);

        long a1 = idWorker.nextId("bizA");
        long a2 = idWorker.nextId("bizA");
        long b1 = idWorker.nextId("bizB");

        assertEquals(1L, a1 & 0xFFFFFFFFL);
        assertEquals(2L, a2 & 0xFFFFFFFFL);
        assertEquals(1L, b1 & 0xFFFFFFFFL, "bizB 的序列号应从1重新开始");
    }
}
