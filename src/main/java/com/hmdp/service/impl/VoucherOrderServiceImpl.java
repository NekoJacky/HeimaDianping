package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIDGenerator;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIDGenerator redisIDGenerator;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Override
    public Result secKillVoucer(Long voucherId) {
        // 得到前端提交的优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 判断秒杀是否开始与是否结束
        if(voucher.getBeginTime().isAfter(LocalDateTime.now()) || !voucher.getEndTime().isAfter(LocalDateTime.now())) {
            return Result.fail("Voucher can't be used");
        }
        // 判断库存是否充足
        if(voucher.getStock() < 1) {
            return Result.fail("There is no more voucher");
        }
        Long userId = UserHolder.getUser().getId();
        // synchronized (userId.toString().intern()) {
        // 创建锁对象
        // SimpleRedisLock lock = new SimpleRedisLock("order:"+userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId); // 使用 Redisson
        // 获取锁
        // boolean isLocked = lock.tryLock(5L);
        boolean isLocked = lock.tryLock();
        if(!isLocked) {
            return Result.fail("Can't buy more voucher");
        }
        try {
            // 获取当前代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }
        // }
    }

    // 一人一单
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 一人一单判断
        Long userId = UserHolder.getUser().getId();
        Integer i = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (i > 0) {
            return Result.fail("Can't buy more voucher");
        }
        // 扣减库存，创建订单
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("There is no more voucher");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        Long OrderId = redisIDGenerator.nextID("order");
        voucherOrder.setId(OrderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        // 写回数据库
        save(voucherOrder);
        // 返回订单 id
        return Result.ok(OrderId);
    }
}
