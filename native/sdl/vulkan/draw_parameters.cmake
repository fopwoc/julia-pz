# GPU-resident indirect commands need native base-instance shader inputs to preserve gl_InstanceID.
set(backend "${sdl_patched}/src/gpu/vulkan/SDL_gpu_vulkan.c")
file(READ "${backend}" source)
julia_replace("    Uint8 KHR_driver_properties;" "    Uint8 KHR_driver_properties;\n    Uint8 KHR_shader_draw_parameters;" 1)
julia_replace("else CHECK(KHR_driver_properties)" "else CHECK(KHR_driver_properties) else CHECK(KHR_shader_draw_parameters)" 1)
julia_replace("        supports->KHR_driver_properties +" "        supports->KHR_driver_properties +\n        supports->KHR_shader_draw_parameters +" 1)
julia_replace("    CHECK(KHR_driver_properties)" "    CHECK(KHR_driver_properties)\n    CHECK(KHR_shader_draw_parameters)" 1)
file(WRITE "${backend}" "${source}")
