-- Atomically claims an idempotency record or reports its current state.
--
-- KEYS[1]  record key
-- ARGV[1]  request fingerprint
-- ARGV[2]  owner id of this attempt
-- ARGV[3]  lease duration in milliseconds
-- ARGV[4]  time-to-live of the in-progress record in milliseconds
--
-- Lease expiry is computed from the Redis server clock (TIME), never from caller clocks.
-- Every reply element is a bulk string so clients can decode replies uniformly.
local key = KEYS[1]
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local leaseUntil = string.format('%.0f', now + tonumber(ARGV[3]))

local state = redis.call('HGET', key, 'state')
if not state then
  redis.call('HSET', key, 'state', 'IN_PROGRESS', 'fp', ARGV[1], 'owner', ARGV[2], 'fence', '1',
    'lease_until', leaseUntil)
  redis.call('PEXPIRE', key, ARGV[4])
  return { 'ACQUIRED', '1' }
end

if redis.call('HGET', key, 'fp') ~= ARGV[1] then
  return { 'MISMATCH' }
end

if state == 'COMPLETED' then
  local r = redis.call('HMGET', key, 'status', 'headers', 'body')
  if not r[1] or not r[2] then
    return { 'CORRUPT' }
  end
  if r[3] then
    return { 'COMPLETED', r[1], r[2], '1', r[3] }
  end
  return { 'COMPLETED', r[1], r[2], '0', '' }
end

if state == 'FAILED' then
  return { 'FAILED' }
end

if state ~= 'IN_PROGRESS' then
  return { 'CORRUPT' }
end

local current = tonumber(redis.call('HGET', key, 'lease_until'))
if current == nil then
  return { 'CORRUPT' }
end
if current > now then
  return { 'IN_PROGRESS', string.format('%.0f', current - now) }
end

-- The lease expired: take over ownership with a strictly greater fence.
local fence = redis.call('HINCRBY', key, 'fence', 1)
redis.call('HSET', key, 'owner', ARGV[2], 'lease_until', leaseUntil)
redis.call('PEXPIRE', key, ARGV[4])
return { 'RECOVERED', tostring(fence) }
