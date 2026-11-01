-- Development-only adapter. All files and mutations belong to the runner's copied profile.
local config = require "ProfilingConfig"
local started = false
local prepared = false
local ready = false
local failed = false
local lastPoll = 0

local function status(value)
    local writer = assert(getFileWriter("profile-scene.txt", true, false), "Cannot write scenario status")
    writer:write(value .. "\n")
    writer:close()
end

local function read(name)
    local reader = getFileReader(name, false)
    if not reader then return nil end
    local value = reader:readLine()
    reader:close()
    return value
end

local function guarded(action)
    if failed then return end
    local ok, message = pcall(action)
    if not ok then
        failed = true
        status("ERROR " .. tostring(message))
        error(message)
    end
end

Events.OnMainMenuEnter.Add(function()
    guarded(function()
        -- The game's normal mod-reset path reloads this file. Its continuation owns loading after a reset.
        if started or read("profile-scene.txt") then return end
        started = true
        status("LOADING")
        -- Use the game's normal checks and mod-reset path, rather than bypassing save validation.
        MainScreen.continueLatestSave(config.mode, config.save)
    end)
end)

Events.OnGameStart.Add(function()
    guarded(function()
        local player = assert(getPlayer(), "Scenario needs the existing single-player character")
        if config.x then player:teleportTo(config.x, config.y, config.z) end
        if config.hour then getGameTime():setTimeOfDay(config.hour) end
        if config.direction then player:setDir(IsoDirections[config.direction]) end
        setGameSpeed(1)
        prepared = true
        status("POSITIONING")
    end)
end)

Events.OnTick.Add(function()
    guarded(function()
        local now = getTimestampMs()
        if now - lastPoll < 500 then return end
        lastPoll = now
        if prepared and not ready then
            local player = assert(getPlayer(), "Scenario player disappeared")
            local square = player:getCurrentSquare()
            if square and (not config.x or (
                math.abs(player:getX() - config.x) < 1
                and math.abs(player:getY() - config.y) < 1
                and player:getZ() == config.z
                and square:getX() == math.floor(config.x)
                and square:getY() == math.floor(config.y)
                and square:getZ() == config.z)) then
                ready = true
                status("READY " .. player:getX() .. " " .. player:getY() .. " " .. player:getZ())
            end
        end
        if read("profile-command.txt") == "quit" then
            status("EXITING")
            getCore():quitToDesktop()
        end
    end)
end)
