package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIDGenerator;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService secKillVoucherService;

    @Resource
    private RedisIDGenerator redisIDGenerator;

    // 静态代码块加载 lua 脚本
    private static final DefaultRedisScript<Long> SEC_KILL_SCRIPT;
    static {
        SEC_KILL_SCRIPT = new DefaultRedisScript<>();
        SEC_KILL_SCRIPT.setLocation(new ClassPathResource("secKill.lua"));
        SEC_KILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024);
    private final ExecutorService SEC_KILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    IVoucherOrderService proxy;

    @PostConstruct
    private void init() {
        SEC_KILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while(true) {
                try {
                    VoucherOrder order = orderTasks.take();
                    handleVoucherOrder(order);
                } catch (InterruptedException e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 获取用户 id。注意此时是一个新的线程，因此不能从 UserHolder 中获得 UserId。
        Long userId = voucherOrder.getUserId();
        // 创建锁对象
        // SimpleRedisLock lock = new SimpleRedisLock("order:"+userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId); // 使用 Redisson
        // 获取锁
        // boolean isLocked = lock.tryLock(5L);
        boolean isLocked = lock.tryLock();
        if(!isLocked) {
            log.error("禁止重复下单");
            return;
        }
        try {
            // 注意，此时是子线程，因此无法获取代理对象，所以我们在主线程中提前获取代理对象，并存放在类中。
            // 获取当前代理对象
            // IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            // return proxy.createVoucherOrder(voucherId);
            // 注意我们将 createVoucherOrder进行了重写，因为创建订单的过程在主线程（secKillVoucher方法）中已经完成
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }

    /*
    @Override
    public Result secKillVoucher(Long voucherId) {
        // 得到前端提交的优惠券信息
        SecKillVoucher voucher = secKillVoucherService.getById(voucherId);
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
     */
    @Override
    public Result secKillVoucher(Long voucherId) {
        // 获取用户 id
        Long userId = UserHolder.getUser().getId();
        // 执行 Lua 脚本
        Long result = stringRedisTemplate.execute(
                SEC_KILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        // 判断结果是否为0
        if (result == null) {
            return Result.fail("错误，请重试");
        }
        if(result != 0) {
            // 非0，没有购买资格
            return Result.fail(result == 1 ? "库存不足" : "禁止重复下单");
        }
        // 为0，可以购买
        VoucherOrder voucherOrder = new VoucherOrder();
        Long orderId = redisIDGenerator.nextID("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        // 将订单信息保存到阻塞队列
        orderTasks.add(voucherOrder);
        // 获取当前代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 返回订单 id
        return Result.ok(orderId);
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
        boolean success = secKillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("There is no more voucher");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        Long orderId = redisIDGenerator.nextID("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        // 写回数据库
        save(voucherOrder);
        // 返回订单 id
        return Result.ok(orderId);
    }

    // 一人一单
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 一人一单判断
        Long userId = UserHolder.getUser().getId();
        Integer i = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count();
        if (i > 0) {
            log.error("禁止重复下单");
            return ;
        }
        // 扣减库存，创建订单
        boolean success = secKillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足");
            return ;
        }
        // 写回数据库
        save(voucherOrder);
    }
}
