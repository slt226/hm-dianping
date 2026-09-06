package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Data
public class SeckillOrderMessage {
    Long orderId;
    Long userId;
    Long voucherId;

}
