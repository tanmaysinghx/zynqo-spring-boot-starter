-- Transitions an owned IN_PROGRESS record to FAILED.
--
-- KEYS[1]  record key
-- ARGV[1]  owner id
-- ARGV[2]  fence
-- ARGV[3]  retention in milliseconds
--
-- Returns 1 if applied, 0 if the caller does not own the record.
local key = KEYS[1]
local r = redis.call('HMGET', key, 'state', 'owner', 'fence')
if r[1] ~= 'IN_PROGRESS' or r[2] ~= ARGV[1] or r[3] ~= ARGV[2] then
  return 0
end
redis.call('HSET', key, 'state', 'FAILED')
redis.call('HDEL', key, 'owner', 'lease_until')
redis.call('PEXPIRE', key, ARGV[3])
return 1
