package com.hmdp.controller;

import com.hmdp.dto.GrowthEarnRequest;
import com.hmdp.dto.Result;
import com.hmdp.service.IMemberService;
import com.hmdp.utils.MemberConst;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 会员中心接口（施工手册 Day 8）。
 * <p>
 * 所有接口都不接收 userId 入参，身份一律从 UserHolder 取——
 * 由 RefreshTokenInterceptor 在请求进来时塞好。
 * 一旦把 userId 做成参数，就等于允许任何人查看和操作别人的会员信息。
 */
@RestController
@RequestMapping("/member")
public class MemberController {

    @Resource
    private IMemberService memberService;

    /**
     * 我的会员信息：等级、成长值、距下一级还差多少、当前折扣。
     * <p>
     * 一次返回完，不让前端再单独请求等级配置来拼装——
     * 前端每多一个请求就多一次失败可能，而且两次请求之间数据可能已经变了。
     */
    @GetMapping("/info")
    public Result info() {
        return Result.ok(memberService.myInfo());
    }

    /**
     * 会员等级权益表，给「会员权益说明」页面用。
     */
    @GetMapping("/levels")
    public Result levels() {
        return Result.ok(memberService.levels());
    }

    /**
     * 我的成长值流水，按时间倒序。
     */
    @GetMapping("/growth/logs")
    public Result growthLogs(@RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "10") int size) {
        return memberService.growthLogs(page, size);
    }

    /**
     * 加分（自测 / 压测入口）。
     * <p>
     * ⚠️ 这个接口在生产环境<strong>不应该存在</strong>：
     * 它让一个已登录用户可以给自己加任意成长值，等于把会员体系白送。
     * 放在这里只是为了让 Day 8 的幂等能被验证——你需要能并发打同一个 bizId，
     * 看它到底加了几次分。
     * <p>
     * 参数做了两层收敛，即使被滥用也不至于造成灾难：
     * amount 限制在 ±{@link MemberConst#MAX_GROWTH_PER_ORDER} 以内，
     * reason 只接受 1~6。真发生了，流水表上每笔都有 bizId 可回溯。
     */
    @PostMapping("/growth/earn")
    public Result earn(@RequestBody GrowthEarnRequest req) {
        if (UserHolder.getUser() == null) {
            return Result.fail("请先登录");
        }
        if (req.getBizId() == null) {
            return Result.fail("bizId 不能为空：它是防重复加分的唯一依据");
        }
        if (req.getAmount() == null || req.getAmount() == 0) {
            return Result.fail("变动值不能为空或 0");
        }
        if (Math.abs(req.getAmount()) > MemberConst.MAX_GROWTH_PER_ORDER) {
            return Result.fail("单次变动不能超过 " + MemberConst.MAX_GROWTH_PER_ORDER);
        }
        int reason = req.getReason() == null ? MemberConst.REASON_ORDER : req.getReason();
        if (reason < MemberConst.REASON_ORDER || reason > MemberConst.REASON_REFUND) {
            return Result.fail("非法的变动原因");
        }

        Long userId = UserHolder.getUser().getId();
        boolean added = memberService.addGrowth(userId, req.getAmount(), reason, req.getBizId());
        return Result.ok(added ? "加分成功" : "该业务已加过分，本次忽略");
    }
}
