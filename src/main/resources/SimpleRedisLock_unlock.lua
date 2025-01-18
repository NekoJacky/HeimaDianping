-- 比较线程表示与锁中标识是否一致
if(redis.call('get', KEYS[1]) == ARGV[1]) then
    -- 一致，删除标识
    return redis.call('del', KEYS[1])
end
-- 不一致，返回 false
return 0
