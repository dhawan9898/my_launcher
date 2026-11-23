# Minimal Liquid Launcher

A bloat-free, ultra-lightweight Android home screen featuring an **Apple iOS Liquid Glass** aesthetic, powered by rootless **Arch Linux ARM (`aarch64`)** under PRoot, dedicated exclusively to the **agy** (Antigravity CLI) coding agent, and fully optimized for the **Samsung Galaxy S24 Ultra** with **Google Gemini** and **Samsung Bixby** intelligence.

---

## Highlights

- **Apple iOS Liquid Glass UI**: Translucent, frosted glass surfaces with real-time backdrop blur, dual-stop specular edge reflections, and organic squircle contours.
- **Rootless Arch Linux ARM**: Full Linux CLI and GUI environments via PRoot without rooting your phone.
- **In-process Wayland Compositor**: Linux windows open as independent Android tasks with their own cards in Android Recents.
- **Dedicated agy Orchestration**: The prompt bar focuses strictly on the `agy` (Antigravity CLI) coding agent.
- **Zero Bloat & Zero Pre-Widgets**: Clean, distraction-free canvas without pre-installed promotional widgets, store banners, or unsolicited search suggestions.
- **Dual AI Assistant Integration**: Liquid action pills on the search bar summon **Google Gemini** (or on-device Gemini Nano) and **Samsung Bixby** instantly.
- **Samsung Galaxy S24 Ultra Optimizations**:
  - Native 120Hz dynamic refresh rate support on the Dynamic AMOLED 2X display.
  - Qualcomm Snapdragon 8 Gen 3 for Galaxy thread priority management.
  - Qualcomm Adreno 750 GPU acceleration via Turnip KGSL (`/dev/kgsl-3d0`).

---

## Architecture & Layout

- `app/`: Android application (Kotlin, Jetpack Compose, JNI C Wayland bridge in `src/main/cpp/wlbridge.c`).
- `owl/`: Standalone Kotlin module for the OWL declarative widget language and compiler.
- `scripts/`: PRoot toolchain and asset scripts.
- `patches/`: Custom PRoot syscall emulation patches.

---

## License

MIT License.
