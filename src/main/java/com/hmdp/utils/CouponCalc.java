package com.hmdp.utils;

import com.hmdp.dto.CouponCheck;
import com.hmdp.entity.CouponTemplate;
import com.hmdp.entity.UserCoupon;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.*;

public class CouponCalc {
    @Data
    public static class Item {
        private Long userCouponId;
        private Integer discountType;
        private Integer discountAmount;
        private Integer discountRate;
        private Integer thresholdAmount;
        private Integer maxDiscount;
        private Integer stackable;
    }
    @Data
    public static class Result {
        private List<Long> pickedIds;
        private int originAmount;
        private int payAmount;
        private int savedAmount;
        private Map<Long,Integer> eachCut;
    }
    public static int apply(int origin,List<Item> combo){
        return applyWithDetail(origin,combo).getPayAmount();

    }

    public static Result applyWithDetail(int origin,List<Item> combo) {
        int pay=origin;//初始的金额
        Map<Long,Integer> eachCut=new LinkedHashMap<>();
        //第一轮满减操作
        for(Item it:combo) {
            int type = nvl(it.getDiscountType());
            int threshold = nvl(it.getThresholdAmount());
            boolean firstStage = type == CouponConst.DISCOUNT_FULL_CUT
                    || (type == CouponConst.DISCOUNT_DIRECT && threshold > 0);
            if (!firstStage || origin < threshold) {
                continue;
            }
            int before=pay;
            pay-=nvl(it.getDiscountAmount());
            pay=Math.max(pay,1);
            eachCut.put(it.getUserCouponId(), before-pay);
        }
        for(Item it:combo) {
            if(nvl(it.getDiscountType())!=CouponConst.DISCOUNT_RATE) {
                continue;
            }
            int rate = nvl(it.getDiscountRate());
            if(rate<=0 || rate>100) {
                continue;
            }
            int cut=pay*(100-rate)/100;
            int max=nvl(it.getMaxDiscount());
            if(max>0 &&cut>max) {
                cut=max;
            }
            int before=pay;
            pay-=cut;
            pay=Math.max(pay,1);
            eachCut.put(it.getUserCouponId(), before-pay);
        }
        for(Item it:combo) {
            int type=nvl(it.getDiscountType());
            int threshold = nvl(it.getThresholdAmount());
            if(type!=CouponConst.DISCOUNT_DIRECT ||  threshold>0) {
                continue;
            }
            int before=pay;
            pay-=nvl(it.getDiscountAmount());
            pay=Math.max(pay,1);
            eachCut.put(it.getUserCouponId(), before-pay);
        }
       Result r=new Result();
        r.setOriginAmount(origin);
        r.setPayAmount(pay);
        r.setSavedAmount(origin-pay);
        r.setEachCut(eachCut);
        List<Long> ids=new ArrayList<>();
        for(Item it:combo) {
            ids.add(it.getUserCouponId());
        }
        r.setPickedIds(new ArrayList<>(eachCut.keySet()));
        return r;
    }

    private static int nvl(Integer v) {
        return v == null ? 0 : v;
    }
    public static Result calc(int origin, List<Item> items) {
        // 0. 最优解先设成"一张券都不用"
        int bestPay = origin;
        List<Long> bestIds = new ArrayList<>();

        // 1. 拆两堆
        List<Item> stackables = new ArrayList<>();
        List<Item> exclusives = new ArrayList<>();
        for (Item it : items) {
            if (nvl(it.getStackable()) == CouponConst.STACK_YES) {
                stackables.add(it);
            } else {
                exclusives.add(it);
            }
        }
        if (stackables.size() > 10) {
            stackables = new ArrayList<>(stackables.subList(0, 10));
        }

        // 2. 基准集：不用互斥券 + 每张互斥券单独一组
        List<List<Item>> bases = new ArrayList<>();
        bases.add(new ArrayList<>());
        for (Item e : exclusives) {
            List<Item> one = new ArrayList<>();
            one.add(e);
            bases.add(one);
        }

        // 3. 每个基准 × 所有叠加子集
        int n = stackables.size();
        for (List<Item> base : bases) {
            for (int mask = 0; mask < (1 << n); mask++) {
                List<Item> combo = new ArrayList<>(base);
                for (int i = 0; i < n; i++) {
                    if ((mask & (1 << i)) != 0) {
                        combo.add(stackables.get(i));
                    }
                }
                int pay = apply(origin, combo);
                if (pay < bestPay) {
                    bestPay = pay;
                    bestIds = new ArrayList<>();
                    for (Item c : combo) {
                        bestIds.add(c.getUserCouponId());
                    }
                }
            }
        }

        Result r = new Result();
        r.setPickedIds(bestIds);
        r.setOriginAmount(origin);
        r.setPayAmount(bestPay);
        r.setSavedAmount(origin - bestPay);
        return r;
    }

    public static CouponCheck checkOne(UserCoupon uc, CouponTemplate t,
                                       Long shopId, int orderAmount, LocalDateTime now) {
        Long id = uc.getId();

        // 1. 已使用
        if (uc.getStatus() != null && uc.getStatus() == CouponConst.UC_USED) {
            return CouponCheck.fail(id, CouponCheck.USED, "已使用");
        }

        // 2. 锁定中（下单预占）
        if (uc.getStatus() != null && uc.getStatus() == CouponConst.UC_LOCKED) {
            return CouponCheck.fail(id, CouponCheck.LOCKED, "订单未完成");
        }

        // 3. 过期：状态标记过期，或已过失效时间
        if ((uc.getStatus() != null && uc.getStatus() == CouponConst.UC_EXPIRED)
                || (uc.getExpireTime() != null && uc.getExpireTime().isBefore(now))) {
            return CouponCheck.fail(id, CouponCheck.EXPIRED, "已过期");
        }

        // 4. 店铺范围：用户券上的店铺优先，为 null 时回退到模板
        Long ucShop = uc.getShopId() != null
                ? uc.getShopId()
                : (t == null ? null : t.getShopId());
        if (ucShop != null && ucShop != CouponConst.SHOP_ID_ALL
                && (shopId == null || !ucShop.equals(shopId))) {
            return CouponCheck.fail(id, CouponCheck.SHOP_MISMATCH, "不适用于本店");
        }

        // 5. 门槛：只有这一关是"努力一下就能过"的
        int threshold = t == null ? 0 : nvl(t.getThresholdAmount());
        if (threshold > 0 && orderAmount < threshold) {
            int gap = threshold - orderAmount;
            return CouponCheck.fail(id, CouponCheck.THRESHOLD,
                    "再买 " + yuan(gap) + " 元可用", gap);
        }

        return CouponCheck.ok(id);
    }

    private static String yuan(int fen) {
        return String.format("%.2f", fen / 100.0);
    }



}
