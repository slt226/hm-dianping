package com.hmdp.dto;

import lombok.Data;

/**
 * 会员月度券发放的统计结果（施工手册 Day 9）。
 * <p>
 * 定时任务跑完只看日志是不够的——「发了 37 张」这句话没有信息量，
 * 真正要回答的是：这一轮<strong>该发的人是不是都发了</strong>。
 * 所以把四个计数分开：
 * <ul>
 *   <li>{@code grantedUsers}：确有其事地拿到了券的用户数</li>
 *   <li>{@code skippedUsers}：本周期早就发过，被唯一索引挡下的用户数</li>
 *   <li>{@code failedUsers}：查询或写库出错，需要人工介入的用户数</li>
 * </ul>
 * 三者相加应当等于 {@code userTotal}。等不上就说明循环里有分支漏了统计，
 * 这比「总数对不对」更容易发现问题。
 * <p>
 * {@code couponTotal} 与 {@code grantedUsers} 不同：一个黑卡用户一次会拿 2 张。
 */
@Data
public class MemberGrantResultVO {

    /** 发放周期，格式 yyyy-MM，例如 2026-11 */
    private String period;

    /** 扫描到的会员总数（level > 0 的用户） */
    private int userTotal;

    /** 实际发放成功的用户数 */
    private int grantedUsers;

    /** 本周期已发过、被跳过的用户数 */
    private int skippedUsers;

    /** 出错失败的用户数 */
    private int failedUsers;

    /** 实际发出的券张数（一个用户可能多张） */
    private int couponTotal;

    public static MemberGrantResultVO of(String period) {
        MemberGrantResultVO vo = new MemberGrantResultVO();
        vo.setPeriod(period);
        return vo;
    }

    /** 发完一轮后自检：三个分支之和必须等于扫描总数 */
    public boolean consistent() {
        return grantedUsers + skippedUsers + failedUsers == userTotal;
    }
}
