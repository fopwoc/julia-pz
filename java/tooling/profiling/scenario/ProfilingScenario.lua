-- Development-only adapter. All files and mutations belong to the runner's copied profile.
local config = require "ProfilingConfig"
local started = false
local current = nil
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

local function arrive(index)
    local spot = assert(config.spots[index], "Unknown scenario spot")
    local player = assert(getPlayer(), "Scenario needs the existing single-player character")
    -- A death would end the run, and the game calls this outside debug mode itself (Tutorial1.lua).
    player:setInvincible(true)
    player:setGhostMode(spot.ghost == true)
    if spot.x then player:teleportTo(spot.x, spot.y, spot.z) end
    if spot.hour then getGameTime():setTimeOfDay(spot.hour) end
    if spot.direction then player:setDir(IsoDirections[spot.direction]) end
    current = index
    ready = false
    status("POSITIONING " .. index)
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
        setGameSpeed(1)
        arrive(1)
    end)
end)

Events.OnTickEvenPaused.Add(function()
    guarded(function()
        local now = getTimestampMs()
        if now - lastPoll < 500 then return end
        lastPoll = now
        if current and not ready then
            local spot = config.spots[current]
            local player = assert(getPlayer(), "Scenario player disappeared")
            local square = player:getCurrentSquare()
            if square and (not spot.x or (
                math.abs(player:getX() - spot.x) < 1
                and math.abs(player:getY() - spot.y) < 1
                and player:getZ() == spot.z
                and square:getX() == math.floor(spot.x)
                and square:getY() == math.floor(spot.y)
                and square:getZ() == spot.z)) then
                ready = true
                status("READY " .. current .. " " .. player:getX() .. " " .. player:getY() .. " " .. player:getZ())
            end
        end
        local command = read("profile-command.txt")
        if command == "quit" then
            status("EXITING")
            getCore():quitToDesktop()
        elseif command and command:sub(1, 5) == "spot " then
            local index = tonumber(command:sub(6))
            if current and index ~= current then arrive(index) end
        end
    end)
end)
