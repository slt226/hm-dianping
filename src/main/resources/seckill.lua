-- 秒杀预扣 Lua 脚本（原子操作）
-- KEYS[1] = 库存key   seckill:stock:{voucherId}
-- KEYS[2] = 用户Set key  seckill:user:{voucherId}
-- ARGV[1] = userId
-- 返回值: 0=预扣成功  1=库存不足(或库存未预热)  2=该用户已下过单

local stock = redis.call('GET', KEYS[1])
if (not stock) or (tonumber(stock) <= 0) then
    return 1
end

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return 2
end

-- 预扣库存 + 记录用户，二者原子完成，杜绝并发下的超卖与重复下单
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 0
