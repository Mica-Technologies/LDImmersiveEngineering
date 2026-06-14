![Logo](https://raw.githubusercontent.com/BluSunrize/ImmersiveEngineering/master/src/main/resources/assets/immersiveengineering/logo.png)

# LDImmersiveEngineering

**Limitless Development — Immersive Engineering**

A community fork of [Immersive Engineering](https://github.com/BluSunrize/ImmersiveEngineering)
focused on **extended support for Minecraft 1.12.2 (Forge)** — the version upstream has moved on from.

> ⚠️ **This is a concept / proof-of-concept of continued Forge 1.12.2 development.**
> It is an unofficial, experimental fork maintained for a private test modpack. It is **not** affiliated
> with or endorsed by BluSunrize or the original Immersive Engineering team, and it is **not** intended
> for distribution on CurseForge, Modrinth, or other mod platforms.

A retro-futuristic tech mod! Wires, transformers, capacitors!

## Credits & Attribution

Immersive Engineering was created by **BluSunrize** and **Damien A.W. Hazard**, with contributions
from many others. **All original credit goes to them and the upstream contributors.** This fork
exists only to keep the 1.12.2 line buildable and patched for our own use.

- Original project: <https://github.com/BluSunrize/ImmersiveEngineering>
- CurseForge: <https://www.curseforge.com/minecraft/mc-mods/immersive-engineering>
- Original authors: BluSunrize, Damien A.W. Hazard, and the IE contributors / community

## License

This project remains under the original **["Blu's License of Common Sense"](LICENSE) © 2017 BluSunrize**.
We have not relicensed anything. In short, and as it applies to this fork:

- Forking and modifying the code is **permitted** by the license.
- This repository is kept **publicly source-visible**, as the license requires of anything built on its code.
- We do **not** monetize this project in any way.
- The license **prohibits redistributing the project (source or compiled) without BluSunrize's explicit
  permission.** Accordingly, this fork does **not** publish compiled builds as public downloads — CI only
  builds and tests the mod. Builds are produced for our private modpack use only.

If you are looking for Immersive Engineering to play with, please get it from the
[official CurseForge page](https://www.curseforge.com/minecraft/mc-mods/immersive-engineering).

## Building

This is a legacy Forge 1.12.2 project and must be built with **JDK 8** (ForgeGradle 2.3 / Gradle 4.1).

```sh
# JDK 8 must be the JVM used by the Gradle wrapper.
# Either set JAVA_HOME for the session, or pin org.gradle.java.home in a local gradle.properties.
./gradlew setupCiWorkspace build      # CI / headless build
./gradlew setupDecompWorkspace        # full decompiled sources for IDE work
```

Build output lands in `build/libs/` (`ImmersiveEngineering-<version>.jar`).
