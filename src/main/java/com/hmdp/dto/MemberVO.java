package com.hmdp.dto;

import lombok.Data;

/**
 * 会员中心展示对象（施工手册 Day 8）。
 * <p>
 * 和实体 Member 的差别，全在「客户端不该自己算的东西」上：
 * <ul>
 *   <li>等级名称、权益文案：客户端只有一个 level=2，它不认识「金卡」，
 *       也不该把等级文案写死在前端，否则运营改一次要发一次前端版本；</li>
 *   <li>距下一等级还差多少、当前进度百分比：这两个数都要拿三张表的信息才能算出来，
 *       让前端自己拼等于把业务规则复制到前端，迟早两边不一致；</li>
 *   <li>折扣率：存的是 95 这种整数，前端要展示「9.5 折」还得自己除，
 *       不如服务端给一个直接能用的值。</li>
 * </ul>
 * <p>
 * 这就是 DTO 存在的意义：<strong>实体描述数据库长什么样，
 * VO 描述界面需要什么</strong>。两者混用的话，某天数据库加个内部字段就自动漏给前端了。
 */
@Data
public class MemberVO {

    private Long userId;

    /** 当前等级序号，0=普通 */
    private Integer level;

    private String levelName;

    /** 累计成长值 */
    private Integer growth;

    /**
     * 当前等级的门槛。用来算进度条起点——
     * 没有它的话，「成长值 2100 / 下一级 8000」会被画成 26% 的进度，
     * 实际上在金卡内已经走了 (2100-2000)/(8000-2000)=1.7%。
     */
    private Integer currentThreshold;

    /** 下一等级序号，已满级时为 null */
    private Integer nextLevel;

    private String nextLevelName;

    /** 下一等级门槛，已满级时为 null */
    private Integer nextThreshold;

    /** 距下一等级还差多少成长值，已满级为 0 */
    private Integer growthToNext;

    /** 当前等级内的进度百分比 0-100，已满级为 100 */
    private Integer progress;

    /** 会员折扣，100=不打折，95=9.5 折 */
    private Integer discountRate;

    private String icon;

    private String benefitDesc;
}
