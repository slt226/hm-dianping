package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.UseCouponRequest;
import com.hmdp.service.IUserCouponService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 用户券接口（施工手册 Day 3）。
 * <p>
 * 类级前缀同样是 /coupon，和 CouponController 共用：
 * 一个是「券的模板」，一个是「用户手里的券」，对前端来说都属于券中心，
 * 但 Spring 允许不同 Controller 挂同一前缀，只要最终方法级路径不重复即可。
 * <p>
 * ⚠️ 这里的两个接口都<strong>没有</strong>放进 MvcConfig 的登录白名单，
 * 未登录会被 LoginInterceptor 挡回 401——这是有意的，领券和看自己的券都必须登录。
 */
@RestController
@RequestMapping("/coupon")
public class UserCouponController {

    @Resource
    private IUserCouponService userCouponService;

    /**
     * 领取一张券。
     * <p>
     * 注意参数里只有 templateId，<strong>没有 userId</strong>：
     * 用户身份从 UserHolder（ThreadLocal）里取，由 RefreshTokenInterceptor 提前塞好。
     * 把 userId 暴露成入参，等于把权限交给前端填。
     */
    @PostMapping("/claim/{templateId}")
    public Result claim(@PathVariable Long templateId) {
        return userCouponService.claim(templateId);
    }

    /**
     * 我的券列表。
     * status 不传表示全部；传 0/1/2/3 对应未使用/已使用/已过期/锁定中（CouponConst.UC_*）。
     */
    @GetMapping("/my")
    public Result my(@RequestParam(required = false) Integer status,
                     @RequestParam(defaultValue = "1") int page,
                     @RequestParam(defaultValue = "10") int size) {
        return userCouponService.myList(status, page, size);
    }
    @GetMapping("/available")
    public Result available(@RequestParam Long shopId,
                            @RequestParam Integer orderAmount) {
        return userCouponService.available(shopId, orderAmount);
    }

    /**
     * 临期券扫描（Day 10）：未来 {@code days} 天内即将过期的券。
     * <p>
     * 这是<strong>运营视角</strong>的接口，不是「我的券」——注意它不查当前登录用户，
     * 所以上线前必须加管理员校验（和 {@code /coupon/member/grant} 同一个待办）。
     * 现在放在这里只是因为它归券服务管。
     *
     * @param days  向前看几天，默认 1
     * @param limit 最多几条，默认 20
     */
    @GetMapping("/expiring/soon")
    public Result expiringSoon(@RequestParam(defaultValue = "1") int days,
                               @RequestParam(defaultValue = "20") int limit) {
        return userCouponService.listExpiringSoon(days, limit);
    }

    /**
     * 下单核销（Day 5）。
     * <p>
     * 用 POST 而不是 GET：这个接口会改数据，GET 可能被浏览器预取、被爬虫撞到，
     * 一旦命中就是真实的券被核销掉。
     * <p>
     * 请求体里同样没有 userId，身份从拦截器塞进 UserHolder 的登录态取。
     */
    @PostMapping("/use")
    public Result use(@RequestBody UseCouponRequest req) {
        return userCouponService.useCoupons(req);
    }

    /**
     * 取消订单退券（Day 5.3）。
     * <p>
     * 路径里只有 orderId，没有 userId —— 退谁的券由登录态决定，
     * 否则换个 orderId 就能退别人的券。
     * <p>
     * 用 orderId 而不是券 id 列表来退：调用方只知道「订单取消了」，
     * 不该关心这单到底用了几张券。让服务端自己按订单反查，
     * 也避免了前端少传一张导致退不干净。
     */
    @PostMapping("/revert/{orderId}")
    public Result revert(@PathVariable Long orderId) {
        return userCouponService.revertCoupons(orderId);
    }
}
