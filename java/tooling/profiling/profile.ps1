# Finds the game and its bundled Java, then hands over to the jar, which holds all the logic.
# Usage: profile.ps1 run <scene.json> [options] | bake <Mode/SaveName> | compare <a> <b> | audit <jar>
# PZ_GAME overrides where the game is installed.
$ErrorActionPreference = 'Stop'

$jar = Join-Path $PSScriptRoot 'agent/build/libs/profiling-agent.jar'
$bundled = Join-Path $PSScriptRoot 'lib/profiling-agent.jar'
if (Test-Path $bundled) { $jar = $bundled }
if (-not (Test-Path $jar)) {
    Write-Error 'Build it first: ./java/gradlew -p java :profiling-agent:jar'
}

$game = $env:PZ_GAME
if (-not $game) { $game = Join-Path ${env:ProgramFiles(x86)} 'Steam/steamapps/common/ProjectZomboid' }
$java = Join-Path $game 'jre64/bin/java.exe'
if (-not (Test-Path $java)) {
    Write-Error "No game found at $game; set PZ_GAME"
}

if ($args.Count -gt 0 -and $args[0] -eq 'run') {
    & $java -jar $jar @args --game $game
} else {
    & $java -jar $jar @args
}
exit $LASTEXITCODE
