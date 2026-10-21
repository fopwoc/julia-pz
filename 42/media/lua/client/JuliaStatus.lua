-- JuliaStatus is exposed by the agent itself, so its absence means the agent never loaded.
local function statusLine()
    if not JuliaStatus then
        return "Julia agent not loaded - see the Workshop page to install it", 0.95, 0.45, 0.4
    end
    if JuliaStatus.isActive() then return JuliaStatus.label(), 0.65, 0.85, 0.7 end
    return JuliaStatus.label(), 0.7, 0.7, 0.7
end

local function drawJuliaStatus()
    local screen = MainScreen and MainScreen.instance
    if not screen or screen.inGame or not screen:getIsVisible() then return end
    if screen.versionBtn and not screen.versionBtn:getIsVisible() then return end

    local text, red, green, blue = statusLine()
    local height = getTextManager():getFontHeight(UIFont.Small)
    getTextManager():DrawString(UIFont.Small, 12, getCore():getScreenHeight() - height - 8,
        text, red, green, blue, 0.85)
end

Events.OnPostUIDraw.Add(drawJuliaStatus)
