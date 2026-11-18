package com.hmdp.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一响应体。
 * <p>
 * 「为什么不用 @AllArgsConstructor」：字段里 code / errorMsg / data / total 大都可为 null，
 * 靠全参构造器按位置传参，一旦把 data 和 total 写反，编译器不会报错、测试也未必发现，
 * 只在某个前端分页页面上表现为"总数变成数组"。所以这里只用无参构造 + setter，
 * 位置错位这类问题从根上不存在。
 * <p>
 * 字段顺序写死为 code → success → errorMsg → data → total，
 * 是为了 JSON 序列化后 code 排在第一位，肉眼和抓包都先看到错误码。
 */
@Data
@NoArgsConstructor
public class Result {
    private Integer code;
    private Boolean success;
    private String errorMsg;
    private Object data;
    private Long total;

    public static Result ok() {
        return ok(null);
    }

    public static Result ok(Object data) {
        Result r = new Result();
        r.setCode(ErrorCode.SUCCESS.getCode());
        r.setSuccess(true);
        r.setData(data);
        return r;
    }

    public static Result ok(List<?> data, Long total) {
        Result r = ok(data);
        r.setTotal(total);
        return r;
    }

    /**
     * 归口重载：老代码有 45 处 {@code Result.fail("中文文案")}，
     * 一次性全改成枚举的收益远小于风险，所以保留这个签名，
     * 统一挂 BIZ_GENERAL(4000)。新代码请用枚举版本。
     */
    public static Result fail(String errorMsg) {
        return fail(ErrorCode.BIZ_GENERAL, errorMsg);
    }

    public static Result fail(ErrorCode errorCode) {
        return fail(errorCode, null);
    }

    /**
     * @param msg 覆盖文案，为 null 时回退到枚举自带的提示
     */
    public static Result fail(ErrorCode errorCode, String msg) {
        Result r = new Result();
        r.setCode(errorCode.getCode());
        r.setSuccess(false);
        r.setErrorMsg(msg != null ? msg : errorCode.getMsg());
        return r;
    }
}
