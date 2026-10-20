local function drawJuliaStatus()
    local screen = MainScreen and MainScreen.instance
    if not screen or screen.inGame or not screen:getIsVisible() then return end
    if screen.versionBtn and not screen.versionBtn:getIsVisible() then return end

    local active = JuliaStatus and JuliaStatus.isActive()
    local text = JuliaStatus and JuliaStatus.label() or "Julia inactive"
    local red, green, blue = 0.7, 0.7, 0.7
    if active then red, green, blue = 0.65, 0.85, 0.7 end

    local height = getTextManager():getFontHeight(UIFont.Small)
    getTextManager():DrawString(UIFont.Small, 12, getCore():getScreenHeight() - height - 8,
        text, red, green, blue, 0.85)
end

Events.OnPostUIDraw.Add(drawJuliaStatus)
