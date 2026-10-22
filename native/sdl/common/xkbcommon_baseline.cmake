# SDL requires every xkbcommon symbol newer than the build host's headers, but Steam's soldier runtime ships
# xkbcommon 0.8.2. Building against SDL's own 0.5.0 baseline keeps its fallbacks, so Wayland loads in the runtime.
set(checks "${sdl_patched}/cmake/sdlchecks.cmake")
file(READ "${checks}" source)
julia_replace([=[if (PC_WAYLAND_xkbcommon_VERSION MATCHES "^([0-9]+)\\.([0-9]+)\\.([0-9]+)")]=]
    [=[if (FALSE)]=] 1)
julia_replace([=[message(WARNING "Failed to parse xkbcommon version; defaulting to lowest supported (0.5.0)")]=] "" 1)
file(WRITE "${checks}" "${source}")
