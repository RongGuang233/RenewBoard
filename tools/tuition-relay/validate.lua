-- Validate only the public envelope. The server never receives the AES key.
if ngx.req.get_method() ~= "PUT" then return end
local content_type = ngx.var.content_type or ""
local media_type = (content_type:match("^[^;]*") or ""):lower():gsub("^%s+", ""):gsub("%s+$", "")
if media_type ~= "application/json" then
    return ngx.exit(415)
end
-- DAV later commits the same buffered request with its native rename operation.
ngx.req.read_body()
local body = ngx.req.get_body_data()
if not body then
    local path = ngx.req.get_body_file()
    if path then
        local file = io.open(path, "rb")
        if not file then return ngx.exit(500) end
        body = file:read(2000001)
        file:close()
    end
end
if not body or #body > 2000000 then return ngx.exit(body and 413 or 400) end
local json = require "cjson.safe"
local envelope = json.decode(body)
if type(envelope) ~= "table" or envelope.version ~= 1 then return ngx.exit(400) end
local count = 0
for key in pairs(envelope) do
    if key ~= "version" and key ~= "nonce" and key ~= "ciphertext" then
        return ngx.exit(400)
    end
    count = count + 1
end
if count ~= 3 then return ngx.exit(400) end
local function decode(value)
    if type(value) ~= "string" then return nil end
    local decoded = ngx.decode_base64(value)
    if not decoded or ngx.encode_base64(decoded) ~= value then return nil end
    return decoded
end
local nonce = decode(envelope.nonce)
local ciphertext = decode(envelope.ciphertext)
if not nonce or #nonce ~= 12 or not ciphertext or #ciphertext < 16 then
    return ngx.exit(400)
end
