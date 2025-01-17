package com.hmdp.service.impl;

import com.hmdp.entity.SecKillVoucher;
import com.hmdp.mapper.SecKillVoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 秒杀优惠券表，与优惠券是一对一关系 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2022-01-04
 */
@Service
public class SecKillVoucherServiceImpl extends ServiceImpl<SecKillVoucherMapper, SecKillVoucher> implements ISeckillVoucherService {

}
