-- Atomically decides whether one request may pass: monthly quota check + token bucket + quota increment.
-- One round trip, no race between "check" and "consume" even with many gateway instances.
--
-- KEYS[1]  token bucket hash   {tokens, ts}
-- KEYS[2]  monthly quota counter
-- ARGV[1]  bucket capacity (burst)
-- ARGV[2]  refill rate, tokens per second
-- ARGV[3]  monthly quota limit
-- ARGV[4]  quota counter TTL in seconds
-- ARGV[5]  tokens this request costs
--
-- Returns {outcome, tokens_left, retry_after_ms, quota_used}
--   outcome: 1 = allowed, 0 = rate limited, 2 = quota exceeded

local capacity    = tonumber(ARGV[1])
local rate        = tonumber(ARGV[2])
local quota_limit = tonumber(ARGV[3])
local quota_ttl   = tonumber(ARGV[4])
local requested   = tonumber(ARGV[5])

-- Use Redis' clock, not the caller's: gateway instances with skewed clocks still agree.
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local used = tonumber(redis.call('GET', KEYS[2]) or '0')
if used + requested > quota_limit then
  return {2, 0, 0, used}
end

local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local ts = tonumber(bucket[2])
if tokens == nil or ts == nil then
  tokens = capacity
  ts = now
end

-- Refill for the time that passed since the last request, capped at capacity.
local elapsed = math.max(0, now - ts)
tokens = math.min(capacity, tokens + (elapsed * rate / 1000))

-- An idle bucket refills completely in capacity/rate seconds; after that it can simply disappear.
local ttl_ms = math.ceil(capacity / rate * 1000) * 2

if tokens < requested then
  redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
  redis.call('PEXPIRE', KEYS[1], ttl_ms)
  local retry_after = math.ceil((requested - tokens) * 1000 / rate)
  return {0, math.floor(tokens), retry_after, used}
end

tokens = tokens - requested
redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
redis.call('PEXPIRE', KEYS[1], ttl_ms)

used = redis.call('INCRBY', KEYS[2], requested)
if used == requested then
  redis.call('EXPIRE', KEYS[2], quota_ttl)
end

return {1, math.floor(tokens), 0, used}
