package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIDGenerator {
    @Resource
    StringRedisTemplate stringRedisTemplate;

    private static final Long baseTime = 1577836800L;

    /// ID 结构为：1位符号位（0，表示正数），31位时间戳（以秒为单位），32位序列号共64位（8个字节）
    public Long nextID(String prefix) {
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long dTime = nowSecond - baseTime;
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long cnt = stringRedisTemplate.opsForValue().increment("icr:" + prefix + ":" + date);
        return (cnt % (1L<<32)) | ((dTime % (1L<<31))<<32);
    }

    // 获得标准时间的时间戳
    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2020,1,1,0,0,0);
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println(second);
    }
}
