package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private boolean tryLock(String lockName) {
        return BooleanUtil.isTrue(
                stringRedisTemplate.opsForValue().setIfAbsent(lockName, lockName, 2, TimeUnit.SECONDS)
        );
    }

    private void unlock(String lockName) {
        stringRedisTemplate.delete(lockName);
    }

    private Shop queryShopById(Long id) {
        while(true) {
            // 从 Redis 查询缓存
            String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            // 存在则直接返回
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            // 不存在
            // 命中空值（shopJson == ""）
            if (shopJson != null) {
                return null;
            }
            // 命中不是空值（shopJson == null）
            // 获取互斥锁
            String lockName = "lock:shop:" + id;
            boolean isLocked = tryLock(lockName);
            // 未能获取互斥锁，则休眠一段时间重新查询缓存
            try {
                if (!isLocked) {
                    Thread.sleep(100);
                    continue;
                }
            } catch (InterruptedException e) {
                unlock(lockName);
                throw new RuntimeException(e);
            }
            // 成功获取互斥锁，则查询数据库，写入 Redis，最后释放互斥锁
            // 从 Redis 查询缓存，Double check，检查是否已经重建缓存，如果已经重建缓存，就可以直接返回
            shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            // 查询数据库
            Shop shop = getById(id);
            // 不存在则返回
            if (shop == null) {
                // 将空值写入 Redis
                stringRedisTemplate
                        .opsForValue()
                        .set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 存在则先写入 Redis 缓存
            stringRedisTemplate
                    .opsForValue()
                    .set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
            // 释放互斥锁
            unlock(lockName);
            // 然后返回
            return shop;
        }
    }

    // 查询
    @Override
    public Result queryById(Long id) {
        /*
        // 从 Redis 查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 存在则直接返回
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        // 不存在
        // 命中空值（shopJson == ""）
        if(shopJson != null){
            return Result.fail("Can't find shop");
        }
        // 命中不是空值（shopJson == null），查询数据库
        Shop shop = getById(id);
        // 不存在则返回
        if (shop == null) {
            // 将空值写入 Redis
            stringRedisTemplate
                    .opsForValue()
                    .set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("Can't find shop");
        }
        // 存在则先写入 Redis 缓存
        stringRedisTemplate
                .opsForValue()
                .set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 然后返回
        return Result.ok(shop);
         */
        Shop shop = queryShopById(id);
        if (shop == null) {
            return Result.fail("Can't find shop");
        }
        return Result.ok(shop);
    }

    // 更新
    @Override
    @Transactional(rollbackFor = Exception.class) // 确保更新数据库和 Redis 是一个事务
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("Blank shop");
        }
        // 更新数据库
        updateById(shop);
        // 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
