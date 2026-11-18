package com.hmdp.config;

import com.hmdp.dto.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 业务错误码 → HTTP 状态码的<strong>唯一映射点</strong>。
 * <p>
 * 这一步是「永远返回 200、靠 body 里的 code 判断」和「语义化状态码」两派之间的取舍，
 * 本项目选的是<strong>折中版</strong>，理由如下：
 * <ul>
 *   <li><strong>4xxx 一律 200</strong>：业务失败（库存不足、券已过期、参数不合法）在 HTTP 语义上
 *       其实是「请求成功处理了，只是业务结果是否」。更重要的是课程前端是按
 *       {@code res.data.success} 判断的，服务端改成 400 会让 axios 直接走 catch，
 *       页面既不弹提示也不刷新，属于把一个可观测的失败改成静默失败。</li>
 *   <li><strong>5xxx 映射 500</strong>：服务端故障的唯一消费者不是前端，而是监控、网关、告警。
 *       {@code http_server_requests_seconds_count{status="5xx"}} 这类指标只认状态码，
 *       永远 200 的话，接 Prometheus 也筛不出真故障。</li>
 * </ul>
 * 换句话说：<strong>只有「不是调用方的错」才升级成 HTTP 层的失败</strong>。
 * <p>
 * 想改成完整语义化（4xxx → 400 / 4201 → 404 / 4101 → 401）只需要改下面这一个方法，
 * 调用方一处都不用动 —— 这就是把决策收敛到一个点的价值。
 */
public final class ApiStatus {

    private ApiStatus() {
    }

    public static HttpStatus of(ErrorCode errorCode) {
        if (errorCode == null) {
            return HttpStatus.OK;
        }
        return errorCode.getCode() >= 5000
                ? HttpStatus.INTERNAL_SERVER_ERROR
                : HttpStatus.OK;
    }
}
