package com.hmdp.mapper;

import com.hmdp.entity.CouponTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 验证「Java 实体 ↔ tb_coupon_template 表」这条链路是否打通。
 * <p>
 * 放在 com.hmdp.mapper 包下，@SpringBootTest 会自动向上找到 com.hmdp.HmDianPingApplication；
 * 如果放到 com.example 之类的包，会报 Unable to find a @SpringBootConfiguration。
 * <p>
 * 前提：Redis(6379)、MySQL(3306)、RabbitMQ(5672) 都在跑，
 * 因为 @SpringBootTest 启动的是完整上下文，VoucherOrderServiceImpl 的 @PostConstruct 会连 Redis。
 */
@SpringBootTest
class CouponMapperTest {

    @Resource
    private CouponTemplateMapper templateMapper;

    @Test
    @DisplayName("能读出初始化数据")
    void canReadInitData() {
        List<CouponTemplate> list = templateMapper.selectList(null);

        System.out.println("模板数=" + list.size());
        list.forEach(t -> System.out.println(t.getId() + " " + t.getName()
                + " 门槛=" + t.getThresholdAmount() + " 折扣率=" + t.getDiscountRate()));

        // 只 println 不 assert 的测试永远是绿的，等于没测
        assertFalse(list.isEmpty(), "一条都没读到：先确认数据源连的是哪个 MySQL 实例、库名是不是 hmdp");
        CouponTemplate first = list.get(0);
        assertNotNull(first.getId(), "id 为空：@TableId 没配对");
        assertNotNull(first.getAcquireMode(), "acquireMode 为空：map-underscore-to-camel-case 没生效");
        assertNotNull(first.getValidType(), "validType 为空：同上");
        assertNotNull(first.getDiscountRate(), "discountRate 为空：数据库 DEFAULT 100 没带回来");
    }
}
