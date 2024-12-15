package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        List<String> typeList = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY, 0, -1);
        List<ShopType> shopTypeList = new ArrayList<>();
        if(typeList != null && !typeList.isEmpty()){
            for(String type : typeList){
                shopTypeList.add(JSONUtil.toBean(type, ShopType.class));
            }
            return Result.ok(shopTypeList);
        }
        shopTypeList = query().orderByAsc("sort").list();
        if(shopTypeList == null || shopTypeList.isEmpty()){
            return Result.fail("Can't find shop type");
        }
        List<String> list = new ArrayList<>();
        for(ShopType shopType : shopTypeList){
            list.add(JSONUtil.toJsonStr(shopType));
        }
        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_TYPE_KEY, list);
        return Result.ok(shopTypeList);
    }
}
