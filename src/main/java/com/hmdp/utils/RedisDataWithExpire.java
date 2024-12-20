package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisDataWithExpire {
    private LocalDateTime Expire;
    private Object data;
}
