package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.validation.constraints.Min;

/**
 * 秒杀下单入口。
 * <p>
 * 类上的 {@code @Validated} 不能省：方法参数上的 {@code @Min} 属于 javax.validation 的
 * 方法级校验，只有类被 {@code MethodValidationPostProcessor} 代理后才会执行，
 * 少了这个注解，{@code @Min(1)} 是纯装饰、永远不会触发。
 * <p>
 * 为什么给 {@code @PathVariable} 只加 {@code @Min(1)} 不加 {@code @NotNull}：
 * 路径变量缺失时请求根本不会进入方法（Spring 直接 404），
 * 也就是说 {@code null} 这个分支在 {@code @PathVariable} 上不可达，
 * 加了只是一段永不执行、又让人误以为有校验的代码。
 */
@Validated
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") @Min(value = 1, message = "必须大于 0") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }
}

