-- Synthetic game API fixture. No game libraries or window are loaded.
local callbacks = {}
Events = {}
for _, name in ipairs({"OnMainMenuEnter", "OnGameStart", "OnTickEvenPaused"}) do
    Events[name] = { Add = function(fn) callbacks[name] = fn end }
end
local files = {}
function getFileWriter(name)
    return {write = function(_, value) files[name] = value end, close = function() end}
end
function getFileReader(name)
    if not files[name] then return nil end
    return {readLine = function() return files[name] end, close = function() end}
end
local config = {mode="Sandbox", save="fixture", x=100, y=200, z=0, hour=12, direction="SE"}
package.preload.ProfilingConfig = function() return config end
local loaded, exited, speed, hour, polls = 0, false, 0, 0, 0
MainScreen = {continueLatestSave = function(mode, save)
    assert(mode == "Sandbox" and save == "fixture")
    loaded = loaded + 1
end}
IsoDirections = {SE=9}
local player = {x=0,y=0,z=0}
function player:teleportTo(x,y,z) self.x=x; self.y=y; self.z=z end
function player:setDir(dir) assert(dir == 9) end
function player:getCurrentSquare() return self.square end
function player:getX() return self.x end
function player:getY() return self.y end
function player:getZ() return self.z end
function getPlayer() return player end
function getGameTime() return {setTimeOfDay=function(_, value) hour=value end} end
function setGameSpeed(value) speed=value end
function getTimestampMs() polls=polls+500; return polls end
function getCore() return {quitToDesktop=function() exited=true end} end

dofile(arg[1])
callbacks.OnMainMenuEnter()
callbacks.OnMainMenuEnter()
assert(loaded == 1 and files["profile-scene.txt"] == "LOADING\n")
-- A Lua reset must not submit a second load while vanilla continues its existing one.
dofile(arg[1])
callbacks.OnMainMenuEnter()
assert(loaded == 1)
callbacks.OnGameStart()
assert(speed == 1 and hour == 12 and player.x == 100)
callbacks.OnTickEvenPaused()
assert(files["profile-scene.txt"] == "POSITIONING\n")
player.square = {getX=function() return 100 end, getY=function() return 200 end, getZ=function() return 0 end}
callbacks.OnTickEvenPaused()
assert(files["profile-scene.txt"]:match("^READY "))
files["profile-command.txt"] = "quit"
callbacks.OnTickEvenPaused()
assert(exited and files["profile-scene.txt"] == "EXITING\n")
-- Unexpected adapter failures are surfaced once, never declared ready.
dofile(arg[1])
getPlayer = function() error("missing player") end
assert(not pcall(callbacks.OnGameStart))
assert(files["profile-scene.txt"]:match("^ERROR .*missing player"))
assert(pcall(callbacks.OnTickEvenPaused))
