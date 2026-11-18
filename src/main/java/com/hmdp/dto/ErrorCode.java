package com.hmdp.dto;

public enum ErrorCode {
    SUCCESS(0, "ok"),
    BIZ_GENERAL(4000, "业务处理失败"),
    PARAM_INVALID(4100,"参数不合法"),
    UNAUTHORIZED(4101,"请先登录"),
    FORBIDDEN(4103,"无权限操作"),
    SECKILL_ENDED(4200,"秒杀已结束"),
    SECKILL_VOUCHER_NOT_FOUND(4201,"秒杀券不存在"),
    SECKILL_NOT_BEGIN(4202,"秒杀尚未开始"),
    SECKILL_STOCK_EMPTY(4203,"库存不足"),
    SECKILL_DUPLICATE(4204,"每人限抢一张"),
    SECKILL_BUSY(4205,"系统繁忙，请稍后重试"),
    INTERNAL_ERROR(5000,"系统异常")
    ;
    private final int code;
    private final String msg;
    ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
    public int getCode() {
        return code;
    }
    public String getMsg() {
        return msg;
    }
}
