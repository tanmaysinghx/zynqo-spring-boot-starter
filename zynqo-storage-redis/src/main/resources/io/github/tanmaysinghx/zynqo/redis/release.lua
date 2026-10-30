-- Deletes an owned IN_PROGRESS record so that the operation can be retried.
--
-- KEYS[1]  record key
-- ARGV[1]  owner id
-- ARGV[2]  fence
--
-- Returns 1 if deleted, 0 if the caller does not own the record.
local key = KEYS[1]
local r = redis.call('HMGET', key, 'state', 'owner', 'fence')
if r[1] ~= 'IN_PROGRESS' or r[2] ~= ARGV[1] or r[3] ~= ARGV[2] then
  return 0
end
redis.call('DEL', key)
return 1
