package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    private static final String LOCK_PREFIX = "lock:";
    // UUID 用于解决误删问题
    private final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private final StringRedisTemplate stringRedisTemplate;
    private final String lockName;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("SimpleRedisLock_unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String lockName, StringRedisTemplate stringRedisTemplate) {
        this.lockName = lockName;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(Long timeoutSeconds) {
        String lockId = LOCK_PREFIX + lockName;
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(lockId, threadId, timeoutSeconds, TimeUnit.SECONDS);
        // 不直接返回 success，防止拆箱出现错误
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        /*
        String lockId = LOCK_PREFIX + lockName;
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        String id = stringRedisTemplate.opsForValue().get(lockId);
        if(threadId.equals(id)) {
            stringRedisTemplate.delete(lockId);
        }
         */
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(LOCK_PREFIX + lockName),
                ID_PREFIX + Thread.currentThread().getId()
        );
    }
}
