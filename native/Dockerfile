# syntax=docker/dockerfile:1
# glibc 2.31 baseline for Steam Runtime compatibility.
FROM debian:bullseye-slim@sha256:e5b6442dd2e9684cf5e87d8338b5968f3b348636fc0be6d7850a381e3731a2bd AS toolchain
RUN printf '%s\n' \
      'deb [check-valid-until=no] http://archive.debian.org/debian bullseye main' \
      'deb [check-valid-until=no] http://archive.debian.org/debian-security bullseye-security main' \
      > /etc/apt/sources.list \
    && apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates build-essential ninja-build pkg-config python3-pip \
    gcc-mingw-w64-x86-64 g++-mingw-w64-x86-64 binutils-mingw-w64-x86-64 \
    libx11-dev libxext-dev libxrandr-dev libxcursor-dev libxi-dev libxfixes-dev \
    libxkbcommon-dev libwayland-dev wayland-protocols libegl1-mesa-dev libgl1-mesa-dev \
    libudev-dev libdbus-1-dev libasound2-dev libpulse-dev \
    && rm -rf /var/lib/apt/lists/* \
    && pip3 install --no-cache-dir cmake==4.4.4
COPY sdl /src/sdl

FROM toolchain AS linux
RUN apt-get update && apt-get install -y --no-install-recommends libxss-dev libxtst-dev \
    && rm -rf /var/lib/apt/lists/*
RUN --mount=type=cache,id=sdl-linux-x64,target=/build/linux,sharing=locked \
    cmake -S /src/sdl -B /build/linux -G Ninja -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_LIBRARY_OUTPUT_DIRECTORY=/build/linux/lib -DSDL_HIDAPI_LIBUSB=OFF \
      -DSDL_X11=ON -DSDL_WAYLAND=ON \
    && grep -q "^#define SDL_VIDEO_DRIVER_X11 1" /build/linux/sdl-build/include-config-release/build_config/SDL_build_config.h \
    && grep -q "^#define SDL_VIDEO_DRIVER_WAYLAND 1" /build/linux/sdl-build/include-config-release/build_config/SDL_build_config.h \
    && grep -q "^#define SDL_XKBCOMMON_VERSION_MAJOR 0" /build/linux/sdl-build/include-config-release/build_config/SDL_build_config.h \
    && cmake --build /build/linux --target SDL3-shared --parallel 8 \
    && mkdir -p /out/linux/x64 \
    && cp -L /build/linux/lib/libSDL3Julia.so /out/linux/x64/libSDL3Julia.so

FROM toolchain AS windows
RUN --mount=type=cache,id=sdl-windows-x64,target=/build/windows,sharing=locked \
    cmake -S /src/sdl -B /build/windows -G Ninja -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_SYSTEM_NAME=Windows -DCMAKE_C_COMPILER=x86_64-w64-mingw32-gcc \
      -DCMAKE_CXX_COMPILER=x86_64-w64-mingw32-g++ \
      -DCMAKE_RC_COMPILER=x86_64-w64-mingw32-windres \
      "-DCMAKE_SHARED_LINKER_FLAGS=-static-libgcc -static-libstdc++" \
      -DCMAKE_RUNTIME_OUTPUT_DIRECTORY=/build/windows/lib \
      -DSDL_DIRECTX=OFF -DSDL_HIDAPI_LIBUSB=OFF \
    && cmake --build /build/windows --target SDL3-shared --parallel 8 \
    && mkdir -p /out/windows/x64 \
    && cp /build/windows/lib/SDL3Julia.dll /out/windows/x64/SDL3Julia.dll

FROM scratch AS natives
COPY --from=linux /out/ /
COPY --from=windows /out/ /
