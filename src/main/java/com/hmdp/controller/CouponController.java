package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.CouponTemplate;
import com.hmdp.exception.BizException;
import com.hmdp.service.ICouponService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.validation.Valid;

/**
 * 券模板接口（施工手册 Day 2）。
 * <p>
 * 这里是管理端建券 + 用户端看券的入口，暂时不加权限，
 * Day 19 再补 @PreAuthorize 或简单 token 校验。
 */
@RestController
@RequestMapping("/coupon")
public class CouponController {

    @Resource
    private ICouponService couponService;

    /**
     * 创建券模板（管理端）。
     * <p>
     * @Valid 负责「格式」：字段非空、数值范围之类，写在实体注解上；
     * 跨字段的业务规则（满减券必须有门槛等）在 Service 的 checkTemplate 里。
     * 两者分工不同，别混在一起。
     */
    @PostMapping("/template")
    public Result createTemplate(@Valid @RequestBody CouponTemplate t) {
        return couponService.createTemplate(t);
    }

    /** 某店铺可领的券（含平台通用券），店铺详情页用 */
    @GetMapping("/shop/{shopId}")
    public Result listByShop(@PathVariable Long shopId) {
        return couponService.listAvailable(shopId);
    }

    /** 平台券中心，分页 */
    @GetMapping("/center")
    public Result center(@RequestParam(defaultValue = "1") int page,
                         @RequestParam(defaultValue = "10") int size) {
        return couponService.pageCenter(page, size);
    }
    /**
     * 结算页可用券与最优组合。
     * orderAmount 单位是分，传 15000 表示 150 元。
     */


}
