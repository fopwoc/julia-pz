local function drawJuliaStatus()
    local screen = MainScreen and MainScreen.instance
    if not screen or screen.inGame or not screen:getIsVisible() then return end
    if screen.versionBtn and not screen.versionBtn:getIsVisible() then return end

    local active = JuliaStatus and JuliaStatus.isActive()
    local text = "Julia inactive"
    local red, green, blue = 0.7, 0.7, 0.7
    if active then
        local backend = JuliaStatus.getBackend()
        if backend == "metal" then backend = "Metal" end
        text = "Julia active | " .. backend
        red, green, blue = 0.65, 0.85, 0.7
        local blocked = JuliaStatus.getBlockedMods()
        if blocked ~= "" then
            local name = blocked:match("^[^:]+") or "Java mod"
            text = text .. " | " .. name .. " blocked"
            red, green, blue = 1, 0.75, 0.35
        end
    end

    local height = getTextManager():getFontHeight(UIFont.Small)
    getTextManager():DrawString(UIFont.Small, 12, getCore():getScreenHeight() - height - 8,
        text, red, green, blue, 0.85)
end

Events.OnPostUIDraw.Add(drawJuliaStatus)
