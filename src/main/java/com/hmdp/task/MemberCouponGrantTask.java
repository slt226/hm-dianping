package com.hmdp.task;

import com.hmdp.dto.MemberGrantResultVO;
import com.hmdp.entity.Member;
import com.hmdp.service.IMemberCouponGrantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.util.List;

/**
 * 会员月度券发放任务（施工手册 Day 9）。
 * <p>
 * 这个类只负责三件事：<strong>什么时候跑、扫哪些人、出错了怎么办</strong>。
 * 「怎么给一个人发」在 {@link IMemberCouponGrantService#grantToUser} 里，
 * 这样切分是为了让事务边界落在单用户那一层——
 * 如果批量循环和方法本身写在同一个类里，{@code this.grantToUser(...)}
 * 会绕过 Spring 代理，{@code @Transactional} 直接失效且不报错。
 */
@Slf4j
@Component
public class MemberCouponGrantTask {

    @Resource
    private IMemberCouponGrantService grantService;

    /**
     * 每月 1 号 01:00 发放。
     * <p>
     * 为什么不放在 00:00：整点是全系统定时任务最拥挤的时刻（对账、报表、备份都在抢），
     * 而且 00:00 前后正好是「本月/上月」的边界，任何一点时钟漂移都会让周期算错。
     * 错开一小时，代价只是用户晚一小时看到券。
     * <p>
     * cron 六位是 Spring 的格式（秒 分 时 日 月 周），和 Linux crontab 的五位不同，
     * 少写一位是最常见的配错方式。
     */
    @Scheduled(cron = "0 0 1 1 * ?")
    public void monthlyGrant() {
        log.info("会员月度券定时发放开始");
        try {
            MemberGrantResultVO r = grantAll(null);
            log.info("会员月度券定时发放结束：{}", r);
        } catch (Exception e) {
            // 定时任务里必须兜住所有异常，否则线程池会静默吞掉，下个月才知道没发
            log.error("会员月度券定时发放整体失败", e);
        }
    }

    /**
     * 给所有有等级的会员发放指定周期的券。
     * <p>
     * <strong>这个方法故意不加 {@code @Transactional}</strong>。
     * 加了就变成「整批一个事务」：第 480 个人失败，前 479 个已发出的券一起回滚，
     * 而且 500 条 INSERT 会把行锁持有到全部结束。
     * <p>
     * 正确做法是让每个用户各自一个事务，失败的记下来单独重跑——
     * 这个「重跑」之所以安全，正是因为唯一索引让发放本身是幂等的：
     * 发过的人返回 0，没发的人补上。
     *
     * @param period 发放周期；传 null 表示按当前日期自动计算
     */
    public MemberGrantResultVO grantAll(String period) {
        String p = (period == null || period.isEmpty())
                ? grantService.buildPeriod(LocalDate.now())
                : period;

        MemberGrantResultVO result = MemberGrantResultVO.of(p);
        List<Member> members = grantService.listGrantableMembers();
        result.setUserTotal(members.size());
        log.info("周期 {} 待发放会员 {} 人", p, members.size());

        for (Member m : members) {
            try {
                int n = grantService.grantToUser(m.getUserId(), m.getLevel(), p);
                if (n > 0) {
                    result.setGrantedUsers(result.getGrantedUsers() + 1);
                    result.setCouponTotal(result.getCouponTotal() + n);
                } else {
                    result.setSkippedUsers(result.getSkippedUsers() + 1);
                }
            } catch (Exception e) {
                // 单个人失败不能中断整批。这里一定要 catch Exception 而不是 RuntimeException：
                // 将来换成 HTTP 调用或文件 IO，受检异常会直接漏出去。
                result.setFailedUsers(result.getFailedUsers() + 1);
                log.error("会员券发放失败 userId={} level={} period={}",
                        m.getUserId(), m.getLevel(), p, e);
            }
        }

        if (!result.consistent()) {
            // 三个分支之和 != 总数，说明循环里漏了一个出口。
            // 这种问题不会让任务失败，只会让统计说谎，所以单独喊一声。
            log.warn("发放统计不自洽：{}", result);
        }
        return result;
    }
}
