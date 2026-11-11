package com.hmdp.utils;

import com.hmdp.dto.CouponCheck;
import com.hmdp.entity.CouponTemplate;
import com.hmdp.entity.UserCoupon;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


class CouponCalcTest {
    private static CouponCalc.Item item(Long id,int type,int threshold,
                                        int amount,int rate,int max,int stackable){
        CouponCalc.Item it = new CouponCalc.Item();
        it.setUserCouponId(id);
        it.setDiscountRate(rate);
        it.setDiscountAmount(amount);
        it.setStackable(stackable);
        it.setDiscountType(type);
        it.setThresholdAmount(threshold);
        it.setMaxDiscount(max);
        return it;
    }
    @Test
    void fullCutplusRate(){
        List<CouponCalc.Item> items = new ArrayList<>();
        items.add(item(1L,CouponConst.DISCOUNT_FULL_CUT,10000,3000,0,0,CouponConst.STACK_YES));
        items.add(item(2L,CouponConst.DISCOUNT_RATE,0,0,88,2000,CouponConst.STACK_YES));
        CouponCalc.Result r=CouponCalc.calc(15000,items);

    }
    // ===== 算钱：再补 4 个 =====

    @Test
    void rateWithCap() {
        List<CouponCalc.Item> items = new ArrayList<>();
        items.add(item(1L, CouponConst.DISCOUNT_RATE, 0, 0, 80, 3000, CouponConst.STACK_YES));
        CouponCalc.Result r = CouponCalc.calc(50000, items);
        assertEquals(47000, r.getPayAmount());
    }

    @Test
    void thresholdNotReached() {
        List<CouponCalc.Item> items = new ArrayList<>();
        items.add(item(1L, CouponConst.DISCOUNT_FULL_CUT, 10000, 3000, 0, 0, CouponConst.STACK_YES));
        CouponCalc.Result r = CouponCalc.calc(8800, items);
        assertEquals(8800, r.getPayAmount());
        assertTrue(r.getPickedIds().isEmpty());
    }

    @Test
    void exclusivePicksOnlyOne() {
        List<CouponCalc.Item> items = new ArrayList<>();
        items.add(item(1L, CouponConst.DISCOUNT_FULL_CUT, 0, 1000, 0, 0, CouponConst.STACK_NO));
        items.add(item(2L, CouponConst.DISCOUNT_FULL_CUT, 0, 800, 0, 0, CouponConst.STACK_NO));
        CouponCalc.Result r = CouponCalc.calc(10000, items);
        assertEquals(9000, r.getPayAmount());
        assertEquals(1, r.getPickedIds().size());
        assertEquals(1L, r.getPickedIds().get(0));
    }

    @Test
    void discountExceedsOrder() {
        List<CouponCalc.Item> items = new ArrayList<>();
        items.add(item(1L, CouponConst.DISCOUNT_DIRECT, 0, 50000, 0, 0, CouponConst.STACK_YES));
        CouponCalc.Result r = CouponCalc.calc(10000, items);
        assertEquals(1, r.getPayAmount());
        assertEquals(9999, r.getSavedAmount());
    }

    // ===== 判断：5 个分支 =====

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 12, 0);

    private static UserCoupon userCoupon(int status, Long shopId, LocalDateTime expireTime) {
        UserCoupon uc = new UserCoupon();
        uc.setId(1L);
        uc.setStatus(status);
        uc.setShopId(shopId);
        uc.setExpireTime(expireTime);
        return uc;
    }

    private static CouponTemplate template(int threshold, Long shopId) {
        CouponTemplate t = new CouponTemplate();
        t.setThresholdAmount(threshold);
        t.setShopId(shopId);
        return t;
    }

    @Test
    void usedCoupon() {
        CouponCheck c = CouponCalc.checkOne(
                userCoupon(CouponConst.UC_USED, 1L, NOW.plusDays(1)),
                template(0, 1L), 1L, 10000, NOW);
        assertFalse(c.isUsable());
        assertEquals(CouponCheck.USED, c.getReasonCode());
    }

    @Test
    void lockedCoupon() {
        CouponCheck c = CouponCalc.checkOne(
                userCoupon(CouponConst.UC_LOCKED, 1L, NOW.plusDays(1)),
                template(0, 1L), 1L, 10000, NOW);
        assertEquals(CouponCheck.LOCKED, c.getReasonCode());
    }

    @Test
    void expiredCoupon() {
        CouponCheck c = CouponCalc.checkOne(
                userCoupon(CouponConst.UC_UNUSED, 1L, NOW.minusDays(1)),
                template(0, 1L), 1L, 10000, NOW);
        assertEquals(CouponCheck.EXPIRED, c.getReasonCode());
    }

    @Test
    void shopMismatch() {
        CouponCheck c = CouponCalc.checkOne(
                userCoupon(CouponConst.UC_UNUSED, 2L, NOW.plusDays(1)),
                template(0, 2L), 1L, 10000, NOW);
        assertEquals(CouponCheck.SHOP_MISMATCH, c.getReasonCode());
    }

    @Test
    void thresholdGap() {
        CouponCheck c = CouponCalc.checkOne(
                userCoupon(CouponConst.UC_UNUSED, 1L, NOW.plusDays(1)),
                template(10000, 1L), 1L, 8800, NOW);
        assertEquals(CouponCheck.THRESHOLD, c.getReasonCode());
        assertEquals(1200, c.getGapAmount());
    }

}
