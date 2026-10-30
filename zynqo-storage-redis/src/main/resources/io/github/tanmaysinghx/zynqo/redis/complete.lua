-- Transitions an owned IN_PROGRESS record to COMPLETED.
--
-- KEYS[1]  record key
-- ARGV[1]  owner id
-- ARGV[2]  fence
-- ARGV[3]  retention in milliseconds
-- ARGV[4]  status code
-- ARGV[5]  encoded headers
-- ARGV[6]  '1' if a body is present, '0' otherwise
-- ARGV[7]  body bytes (ignored when ARGV[6] is '0')
--
-- Returns 1 if applied, 0 if the caller does not own the record.
local key = KEYS[1]
local r = redis.call('HMGET', key, 'state', 'owner', 'fence')
if r[1] ~= 'IN_PROGRESS' or r[2] ~= ARGV[1] or r[3] ~= ARGV[2] then
  return 0
end
redis.call('HSET', key, 'state', 'COMPLETED', 'status', ARGV[4], 'headers', ARGV[5])
if ARGV[6] == '1' then
  redis.call('HSET', key, 'body', ARGV[7])
end
redis.call('HDEL', key, 'owner', 'lease_until')
redis.call('PEXPIRE', key, ARGV[3])
return 1
