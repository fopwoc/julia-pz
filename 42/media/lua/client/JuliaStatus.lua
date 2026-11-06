local WARNING, FAILURE, ACTIVE, IDLE = {0.95, 0.75, 0.3}, {0.95, 0.45, 0.4}, {0.65, 0.85, 0.7}, {0.7, 0.7, 0.7}

-- ZombieBuddy lists a Java mod as active once it is approved, and only a later launch can preload it.
local function approvedThisSession()
    for _, mod in pairs(ZombieBuddy.getActiveJavaMods() or {}) do
        if mod.id == "Julia" or mod.id == "JuliaDev" then return true end
    end
    return false
end

local function statusLine()
    if JuliaStatus then
        local failure = JuliaStatus.getFailure and JuliaStatus.getFailure()
        if failure then return "Julia failed to start: " .. failure, FAILURE end
        if JuliaStatus.isActive() then return JuliaStatus.label(), ACTIVE end
        return JuliaStatus.label(), IDLE
    end
    -- JuliaStatus comes from the running agent, so without it this game session is on its normal renderer.
    if not ZombieBuddy then return "Julia needs ZombieBuddy - see the Workshop page", FAILURE end
    if approvedThisSession() then return "Julia is approved - restart the game to start it", WARNING end
    return "Julia is waiting - approve it in ZombieBuddy, then restart the game", WARNING
end

local function drawJuliaStatus()
    local screen = MainScreen and MainScreen.instance
    if not screen or screen.inGame or not screen:getIsVisible() then return end
    if screen.versionBtn and not screen.versionBtn:getIsVisible() then return end

    local text, color = statusLine()
    local height = getTextManager():getFontHeight(UIFont.Small)
    getTextManager():DrawString(UIFont.Small, 12, getCore():getScreenHeight() - height - 8,
        text, color[1], color[2], color[3], 0.85)
end

Events.OnPostUIDraw.Add(drawJuliaStatus)
