package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService executorService = Executors.newFixedThreadPool(10);

    private boolean tryLock(String lockName) {
        return BooleanUtil.isTrue(
                stringRedisTemplate.opsForValue().setIfAbsent(lockName, lockName, 2, TimeUnit.SECONDS)
        );
    }

    private void unlock(String lockName) {
        stringRedisTemplate.delete(lockName);
    }

    public void setWithExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisDataWithExpire data = new RedisDataWithExpire();
        data.setData(value);
        data.setExpire(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(data));
    }

    public <R, ID> R queryWithMutex(
            String prefix, ID id, Long time, TimeUnit timeUnit, Class<R> type, Function<ID, R> dbFunction
    ) {
        // 从 Redis 查询缓存
        String key = prefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 存在则直接返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 不存在
        // 命中空值（shopJson == ""）
        if (json != null) {
            return null;
        }
        // 命中不是空值（shopJson == null）
        // 查询数据库
        R ret = dbFunction.apply(id);
        // 不存在将空值写入 Redis
        if (ret == null) {
            stringRedisTemplate
                    .opsForValue()
                    .set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 存在则先写入 Redis 缓存
        setWithExpire(key, ret, time, timeUnit);
        // 然后返回
        return ret;
    }

    public <R, ID> R queryWithLogicalExpire(
            String prefix, ID id, Long time, TimeUnit timeUnit, Class<R> type, Function<ID, R> dbFunction
    ) {
        // 从 Redis 查询缓存
        String key = prefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 不存在则返回空值
        if (StrUtil.isBlank(json)) {
            return null;
        }
        // 命中，Json 反序列化
        RedisDataWithExpire data = JSONUtil.toBean(json, RedisDataWithExpire.class);
        R ret = JSONUtil.toBean((JSONObject) data.getData(), type);
        LocalDateTime expire = data.getExpire();
        // 判断是否过期
        if(expire.isAfter(LocalDateTime.now())) {
            // 未过期，直接返回
            return ret;
        }
        // 过期，重建缓存
        // 获取互斥锁
        String lockName = prefix + "lock:" + id;
        boolean isLocked = tryLock(lockName);
        // 检查获取互斥锁是否成功
        if(isLocked) {
            // 从 Redis 查询缓存，Double check，检查是否已经重建缓存，如果已经重建缓存，就可以直接返回
            json = stringRedisTemplate.opsForValue().get(key);
            data = JSONUtil.toBean(json, RedisDataWithExpire.class);
            ret = JSONUtil.toBean((JSONObject) data.getData(), type);
            expire = data.getExpire();
            if(expire.isAfter(LocalDateTime.now())) {
                unlock(lockName);
                return ret;
            }
            // 获取成功，新建线程实现缓存重建
            executorService.submit(() -> {
                try {
                    R r = dbFunction.apply(id);
                    setWithLogicalExpire(key, r, time, timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockName);
                }
            });
        }
        // 返回
        return ret;
    }
}
