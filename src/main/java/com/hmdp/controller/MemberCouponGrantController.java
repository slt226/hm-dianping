package com.hmdp.controller;

import com.hmdp.dto.MemberGrantResultVO;
import com.hmdp.dto.Result;
import com.hmdp.service.IMemberCouponGrantService;
import com.hmdp.task.MemberCouponGrantTask;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 会员月度券发放的管理端入口（施工手册 Day 9）。
 * <p>
 * 正常路径是定时任务自动跑，这两个接口存在的意义：
 * <ul>
 *   <li>{@code preview}：上线前预演，看这一轮会发多少张，不写任何数据；</li>
 *   <li>{@code grant}：手动补发。任务挂了、或者运营调整了等级配置需要立刻生效时用。</li>
 * </ul>
 * <p>
 * ⚠️ 权限：现在只校验「已登录」，Day 19 上线前必须换成管理员校验。
 * 好在{@code grant}是幂等的且只发用户本来应得的那几张，
 * 被人乱点最多是「提前触发本月发放」，不会多发。
 */
@RestController
@RequestMapping("/coupon/member")
public class MemberCouponGrantController {

    @Resource
    private MemberCouponGrantTask grantTask;

    @Resource
    private IMemberCouponGrantService grantService;

    /**
     * 预演：这个周期会给多少人、发多少张。
     * <p>
     * 典型用法是发版后先调一次，确认 {@code couponTotal} 与运营预期一致，再让任务跑。
     *
     * @param period 可选，格式 yyyy-MM；不传按当月算
     */
    @GetMapping("/grant/preview")
    public Result preview(@RequestParam(required = false) String period) {
        if (UserHolder.getUser() == null) {
            return Result.fail("请先登录");
        }
        return Result.ok(grantService.preview(period));
    }

    /**
     * 手动触发发放（幂等，可以反复点）。
     * <p>
     * 重复调用的结果：{@code grantedUsers} 变成 0、{@code skippedUsers} 等于总人数，
     * 库里一行都不多。这就是幂等最直观的验收方式。
     *
     * @param period 可选，格式 yyyy-MM；不传按当月算
     */
    @PostMapping("/grant")
    public Result grant(@RequestParam(required = false) String period) {
        if (UserHolder.getUser() == null) {
            return Result.fail("请先登录");
        }
        MemberGrantResultVO r = grantTask.grantAll(period);
        return Result.ok(r);
    }

    /**
     * 按周期区间拉发放明细（Day 10，{@code ZRANGEBYLEX}）。
     * <p>
     * 典型用法：{@code ?from=2026-01&to=2026-04&limit=100} 拉一季度全部发放记录。
     * 注意 {@code to} 是<strong>不含</strong>的——传 {@code 2026-04} 表示「到 3 月底为止」，
     * 和 SQL 里 {@code grant_period < '2026-04'} 的语义一致。
     *
     * @param from  起始周期 yyyy-MM，含；不传默认近 6 个月
     * @param to    结束周期 yyyy-MM，不含；不传默认下个月
     * @param limit 最多返回条数，上限 500
     */
    @GetMapping("/grant/records")
    public Result records(@RequestParam(required = false) String from,
                          @RequestParam(required = false) String to,
                          @RequestParam(defaultValue = "50") int limit) {
        if (UserHolder.getUser() == null) {
            return Result.fail("请先登录");
        }
        return Result.ok(grantService.listGrantRecords(from, to, limit));
    }
}
