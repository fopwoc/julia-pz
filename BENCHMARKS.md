# Benchmarks

Julia against the game's own OpenGL renderer on the same save, settings and camera spots. These are a few runs on two
machines, not a broad survey: they show where Julia helps and where it still falls behind.

## Setup

| | |
| --- | --- |
| Julia | 0.0.3 |
| Project Zomboid | 42.21 |
| Save | Apocalypse, Kentucky |
| Mods | none, or [ZombieBuddy](https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853) 2.3.4 with [Viewpoint](https://steamcommunity.com/sharedfiles/filedetails/?id=3809306528) alpha-0.1.6.1 |
| Runs | 3 per renderer |
| Capture | 5 s warmup, then 10 s per spot, at noon |
| Limits | VSync and the game's FPS limit off, so numbers show throughput |

FPS is the median across runs, with the range in brackets. Trust a difference only when the ranges don't overlap. p99
is the median of each run's 99th-percentile frame time. Timings are CPU wall time between completed frames, not GPU
timestamps.

The resource tables show medians across runs during the capture. RAM is the game process's resident memory. CPU % covers
the whole process, where 100% is one core. Render thread % is how busy the thread that drives drawing was; near 100%
means it is the bottleneck.

Spot positions are in [kentucky.json](java/tooling/profiling/scenes/kentucky.json); the Viewpoint scene visits the same
spots in first person. The [profiling tools](java/tooling/profiling/README.md) run the same comparison on your machine.

## Machines

| | macOS | Linux |
| --- | --- | --- |
| CPU | Apple M4 Max | AMD Ryzen 7 5700X |
| GPU | Apple M4 Max | NVIDIA GeForce RTX 4060 Ti 16 GB, driver 620.70.06 |
| RAM | 36 GB | 64 GB |
| OS | macOS 27.0.1 | CachyOS, Linux 7.3.1 |
| Julia's API | Metal | Vulkan |
| Resolution | 1800×1102 | 3840×2560 |

## macOS, no mods

| Spot | OpenGL FPS | Julia FPS | Difference | OpenGL p99 ms | Julia p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| plain | 933.7 (926.3–957.2) | 433.0 (427.3–442.9) | −53.6% | 3.81 | 9.74 |
| water | 279.7 (278.9–279.8) | 326.7 (325.9–331.7) | +16.8% | 7.86 | 9.51 |
| forest | 93.7 (92.9–94.0) | 108.3 (106.8–109.5) | +15.6% | 14.07 | 10.16 |
| town | 186.5 (184.7–195.7) | 267.6 (222.0–279.1) | +43.5% | 9.35 | 8.20 |
| skyscrapers | 138.3 (137.8–140.4) | 192.5 (190.2–196.7) | +39.2% | 10.65 | 7.38 |
| vehicles | 275.2 (263.0–289.9) | 291.0 (290.8–322.2) | +5.7% | 8.12 | 8.88 |
| horde | 107.1 (90.5–116.8) | 116.7 (115.3–126.5) | +9.0% | 16.41 | 12.36 |

Julia is faster everywhere the scene has real content. The plain loss is a fixed per-frame cost of about 1.2 ms that
only shows at several hundred FPS.

| Spot | OpenGL RAM GB | Julia RAM GB | OpenGL CPU % | Julia CPU % | OpenGL render thread % | Julia render thread % |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| plain | 15.4 | 11.1 | 197 | 116 | 73 | 21 |
| water | 15.4 | 11.0 | 157 | 164 | 34 | 44 |
| forest | 15.4 | 11.0 | 185 | 203 | 92 | 100 |
| town | 15.4 | 11.0 | 260 | 271 | 86 | 78 |
| skyscrapers | 14.9 | 11.0 | 260 | 301 | 85 | 95 |
| vehicles | 14.9 | 11.0 | 218 | 208 | 58 | 40 |
| horde | 14.8 | 11.0 | 214 | 213 | 42 | 30 |

## macOS, Viewpoint

| Spot | Julia FPS | Julia p99 ms |
| --- | ---: | ---: |
| plain | 330.0 (327.9–335.8) | 8.88 |
| water | 352.5 (336.3–358.4) | 8.75 |
| forest | 252.6 (252.6–261.5) | 7.06 |
| town | 178.7 (174.9–182.0) | 7.65 |
| skyscrapers | 162.7 (161.4–167.0) | 8.98 |
| vehicles | 266.6 (254.9–276.9) | 8.73 |
| horde | 155.4 (146.5–157.6) | 10.72 |

There is no OpenGL column: Viewpoint needs OpenGL 4.3, and macOS stops at 4.1, so it only runs on a Mac through Julia.

| Spot | RAM GB | CPU % | Render thread % |
| --- | ---: | ---: | ---: |
| plain | 13.0 | 232 | 44 |
| water | 13.0 | 210 | 43 |
| forest | 13.0 | 208 | 55 |
| town | 13.8 | 289 | 69 |
| skyscrapers | 14.1 | 333 | 65 |
| vehicles | 14.0 | 270 | 49 |
| horde | 13.8 | 294 | 51 |

## Linux, no mods

| Spot | OpenGL FPS | Julia FPS | Difference | OpenGL p99 ms | Julia p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| plain | 213.4 (213.3–273.8) | 213.6 (212.6–216.2) | +0.1% | 7.05 | 5.69 |
| water | 63.3 (63.2–69.4) | 65.4 (65.3–65.6) | +3.4% | 18.22 | 16.39 |
| forest | 101.5 (101.2–174.4) | 24.7 (24.2–24.9) | −75.6% | 12.08 | 41.81 |
| town | 83.3 (66.9–210.9) | 55.4 (55.0–56.6) | −33.5% | 19.98 | 20.12 |
| skyscrapers | 73.9 (71.4–166.8) | 48.7 (47.6–49.7) | −34.1% | 22.60 | 24.04 |
| vehicles | 207.3 (200.8–231.2) | 208.7 (207.8–209.0) | +0.6% | 7.47 | 7.59 |
| horde | 64.7 (59.1–101.4) | 63.4 (60.5–63.5) | −2.0% | 25.93 | 24.05 |

On NVIDIA, Julia matches OpenGL in light scenes but falls well behind in forest, town and skyscrapers, where its render
thread is saturated. One OpenGL run was much faster than the other two, which widens the OpenGL ranges; the forest loss
holds either way.

| Spot | OpenGL RAM GB | Julia RAM GB | OpenGL CPU % | Julia CPU % | OpenGL render thread % | Julia render thread % |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| plain | 5.9 | 8.2 | 189 | 190 | 50 | 40 |
| water | 5.9 | 8.5 | 122 | 170 | 13 | 22 |
| forest | 5.9 | 8.9 | 295 | 168 | 85 | 99 |
| town | 5.9 | 9.5 | 303 | 285 | 53 | 98 |
| skyscrapers | 6.2 | 10.3 | 312 | 282 | 50 | 95 |
| vehicles | 6.7 | 12.7 | 227 | 240 | 27 | 91 |
| horde | 8.1 | 12.7 | 260 | 287 | 27 | 80 |

## Linux, Viewpoint

| Spot | OpenGL FPS | Julia FPS | Difference | OpenGL p99 ms | Julia p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| plain | 75.9 (75.8–76.2) | 61.8 (60.6–61.9) | −18.6% | 14.83 | 17.73 |
| water | 66.6 (66.5–67.1) | 55.7 (55.5–55.8) | −16.3% | 16.65 | 19.52 |
| forest | 49.4 (49.2–49.6) | 42.8 (42.8–42.9) | −13.4% | 26.27 | 27.96 |
| town | 50.4 (49.6–50.7) | 44.2 (42.9–44.9) | −12.2% | 29.70 | 30.10 |
| skyscrapers | 41.2 (41.2–44.7) | 37.7 (37.6–37.7) | −8.5% | 36.09 | 39.91 |
| vehicles | 60.8 (60.4–61.3) | 59.8 (59.6–59.9) | −1.6% | 18.91 | 19.42 |
| horde | 56.8 (56.7–56.9) | 42.8 (42.6–43.0) | −24.8% | 21.22 | 45.88 |

| Spot | OpenGL RAM GB | Julia RAM GB | OpenGL CPU % | Julia CPU % | OpenGL render thread % | Julia render thread % |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| plain | 13.9 | 16.4 | 298 | 182 | 67 | 47 |
| water | 13.9 | 16.4 | 176 | 117 | 16 | 43 |
| forest | 13.9 | 16.4 | 275 | 194 | 22 | 36 |
| town | 14.0 | 16.8 | 354 | 305 | 23 | 50 |
| skyscrapers | 14.6 | 17.4 | 366 | 327 | 20 | 46 |
| vehicles | 14.5 | 17.4 | 302 | 248 | 23 | 54 |
| horde | 14.4 | 17.6 | 370 | 304 | 24 | 51 |

## Windows

There are no Windows results yet. If you have a Windows machine, I'd be happy if you ran the
[profiling tools](java/tooling/profiling/README.md) and shared the `summary.md` in an
[issue](https://github.com/fopwoc/julia-pz/issues).
