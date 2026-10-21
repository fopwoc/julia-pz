# Unix shared builds hide everything not named in SDL's version script.
set(exports "${sdl_patched}/src/dynapi/SDL_dynapi.sym")
file(READ "${exports}" source)
string(REPLACE "  global:\n" "  global:\n    JULIA_SDL_GetExtension;\n" source "${source}")
file(WRITE "${exports}" "${source}")
