-- Copyright (c) 2015-2023 Lymia Kanokawa <lymia@lymia.moe>
--
-- Permission is hereby granted, free of charge, to any person obtaining a copy
-- of this software and associated documentation files (the "Software"), to deal
-- in the Software without restriction, including without limitation the rights
-- to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
-- copies of the Software, and to permit persons to whom the Software is
-- furnished to do so, subject to the following conditions:
--
-- The above copyright notice and this permission notice shall be included in
-- all copies or substantial portions of the Software.
--
-- THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
-- IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
-- FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
-- AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
-- LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
-- OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
-- THE SOFTWARE.

-- globals from patch
local rawset = _mpPatch.patch.globals.rawset

-- Metatable __index hooks
local indexers = {}
function _mpPatch._mt.__index(_, k)
    for _, fn in ipairs(indexers) do
        local hasValue, v = fn(k)
        if hasValue then return v end
    end
    error("Access to unknown field "..k.." in MpPatch runtime.")
end
function _mpPatch._mt.registerIndexer(fn)
    table.insert(indexers, fn)
end

-- Metatable __newindex hooks
local newIndexers = {}
function _mpPatch._mt.__newindex(_, k, v)
    for _, fn in ipairs(newIndexers) do
        if fn(k, v) then return end
    end
    rawset(_mpPatch, k, v)
end
function _mpPatch._mt.registerNewIndexer(fn)
    table.insert(newIndexers, fn)
end

-- Lazy variables
local lazyVals = {}
_mpPatch._mt.registerIndexer(function(k)
    local fn = lazyVals[k]
    if fn then
        local v = fn()
        _mpPatch[k] = v
        return true, v
    end
end)
function _mpPatch._mt.registerLazyVal(k, fn)
    lazyVals[k] = fn
end

-- Properties
local properties = {}
_mpPatch._mt.registerIndexer(function(k)
    local p = properties[k]
    if p then
        return true, p.read()
    end
end)
_mpPatch._mt.registerNewIndexer(function(k, v)
    local p = properties[k]
    if p then
        if not p.write then error("write to immutable property "..k) end
        p.write(v)
        return true
    end
end)
function _mpPatch._mt.registerProperty(k, read, write)
    properties[k] = {read = read, write = write}
end

-- Event system (registered as property so it works regardless of mppatch_utils.lua execution)
local eventTable = {}
local function newEvent()
    local events = {}
    local eventLevels = {}
    return setmetatable({
        registerHandler = function(fn, level)
            level = level or 0
            if not events[level] then
                events[level] = {}
                table.insert(eventLevels, level)
                table.sort(eventLevels)
            end
            table.insert(events[level], fn)
        end
    }, {
        __call = function(t, ...)
            for _, level in ipairs(eventLevels) do
                for _, fn in ipairs(events[level]) do
                    if fn(...) then
                        return true
                    end
                end
            end
        end
    })
end
local eventProxy
_mpPatch._mt.registerProperty("event", function()
    if not eventProxy then
        eventProxy = setmetatable({}, {
            __index = function(_, k)
                if not eventTable[k] then
                    eventTable[k] = newEvent()
                end
                return eventTable[k]
            end
        })
    end
    return eventProxy
end, function(v)
    -- no-op: event system initializes lazily via property read
    -- mppatch_utils.lua tries to assign _mpPatch.event = setmetatable(...)
    -- but the property handles this internally already
end)

-- Chat protocol base (defined here to survive include() caching of mppatch_chatprotocol.lua)
local marker = "mppatch_command:8f671fc2-cd03-11e6-9c65-00e09c101bf5:"
local chatProtocolCmds = {}

local function sendChatCommand(id, data)
    _mpPatch.debugPrint("Sending MPPatch chat command: "..id..", data = "..(data or "<no data>"))
    Network.SendChat(marker..id..":"..(data or ""))
end

local function newChatCommand(id)
    local event = _mpPatch.event["command_"..id]
    chatProtocolCmds[id] = event
    return setmetatable({
        send = function(data)
            sendChatCommand(id, data)
        end,
        registerHandler = event.registerHandler
    }, {
        __call = function(t, ...) return t.send(...) end
    })
end

-- net property (survives caching; chatprotocol.lua's write is intercepted, its value discarded)
local netCommands = {}
local netProxy
_mpPatch._mt.registerProperty("net", function()
    if not netProxy then
        netProxy = setmetatable({}, {
            __index = function(_, k)
                if not netCommands[k] then
                    netCommands[k] = newChatCommand(k)
                end
                return netCommands[k]
            end
        })
    end
    return netProxy
end, function(v)
    -- no-op: chatprotocol.lua's assignment is handled by the property read
end)

-- interceptChatFunction (property so it survives caching; shares chatProtocolCmds with newChatCommand)
_mpPatch._mt.registerProperty("interceptChatFunction", function()
    return function(fn, condition, chatCondition, noCheckHide)
        condition = condition or function() return true end
        chatCondition = chatCondition or function() return true end
        local function chatFn(...)
            local fromPlayer, _, text = ...
            if (noCheckHide or not ContextPtr:IsHidden()) and condition(...) then
                local textHead, textTail = text:sub(1, marker:len()), text:sub(marker:len() + 1)
                if textHead == marker then
                    local split = textTail:find(":")
                    local command, data = textTail:sub(1, split - 1), textTail:sub(split + 1)
                    if data == "" then data = nil end
                    _mpPatch.debugPrint("Got MPPatch chat command: "..command..", "..
                                        "player = "..fromPlayer.." data = "..(data or "<no data>"))
                    local cmdFn = chatProtocolCmds[command]
                    if not cmdFn then
                        return
                    else
                        return cmdFn(data, ...)
                    end
                end
            end
            if fn and chatCondition(...) then return fn(...) end
        end
        return chatFn
    end
end, function(v)
    -- no-op: chatprotocol.lua's assignment is handled by the property read
end)

-- protocolVersion (survives caching; chatprotocol.lua assigns the same value, redundant but harmless)
_mpPatch._mt.registerLazyVal("protocolVersion", function()
    return "0"
end)

-- version property (ensures .get and .getBoolean survive caching even if mppatch_utils.lua doesn't run)
local versionTable = nil
_mpPatch._mt.registerProperty("version", function()
    if versionTable then
        return versionTable
    end
    local t = { info = {}, buildId = {}, loaded = false }
    t.get = function(self, k) return self.info[k] end
    t.getBoolean = function(self, k) return self.info[k] == "true" end
    return t
end, function(v)
    v.get = v.get or function(self, k) return self.info[k] end
    v.getBoolean = v.getBoolean or function(self, k) return self.info[k] == "true" end
    versionTable = v
end)
