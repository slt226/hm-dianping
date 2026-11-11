package com.hmdp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling 是 Day 9 加的：会员月度券发放靠 @Scheduled 触发。
// 不加这个注解，@Scheduled 方法会被静默忽略——不报错、不提示，
// 只在启动日志里少一行 ScheduledAnnotationBeanPostProcessor 的注册信息。
@EnableScheduling
@MapperScan("com.hmdp.mapper")
@SpringBootApplication
public class HmDianPingApplication {

    public static void main(String[] args) {
        SpringApplication.run(HmDianPingApplication.class, args);
    }

}
