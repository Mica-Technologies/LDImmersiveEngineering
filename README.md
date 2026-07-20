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

This is a legacy Forge 1.12.2 project: **ForgeGradle 2.3 on Gradle 4.10.3, running on JDK 8.**
That combination is not negotiable — ForgeGradle 2.3 cannot load on Gradle 5+ or on a JVM
newer than 8. `build.gradle` checks both up front and fails with an explanatory message
rather than a cryptic plugin error.

Use the `build.sh` / `build.bat` wrappers. They find a JDK 8 (`~/.jdks/*1.8*`, or
`JAVA8_HOME` if set) and hand off to `./gradlew`, which matters because this machine setup
keeps no `java` on `PATH` and no global `JAVA_HOME`:

```sh
./build.sh setupCiWorkspace build      # headless build (what CI runs)
./build.sh setupDecompWorkspace        # full decompiled sources for IDE work
./build.sh setupDevWorkspace           # assets + natives, needed for runClient/runServer
```

```powershell
build.bat setupCiWorkspace build       # same, on Windows without a bash shell
```

`./gradlew` directly also works if `JAVA_HOME` already points at a JDK 8.

Build output lands in `build/libs/` (`ImmersiveEngineering-<version>.jar`).

### Versioning

Builds are versioned as the upstream IE version plus this fork's build metadata:

```
0.12-98+LD.2026.07.19.586a9f5
└─────┘ └──────────┘ └─────┘
  │           │         └── commit short sha
  │           └── commit date (YYYY.MM.DD)
  └── upstream Immersive Engineering version this fork is built from
```

The date comes from the **commit**, not the build, so rebuilding a commit reproduces its
version exactly. A `.dirty` suffix is appended when the working tree has uncommitted
tracked changes — CI rejects any build carrying it.

Nothing is set by hand; `./build.sh -q printModVersion` reports the current value. Every
successful build of `main` is tagged `ld-YYYY.MM.DD` (with `+N` for repeat builds on the
same day) — prefixed to keep fork tags apart from the ~150 inherited upstream IE tags.

Note that the in-game update checker is deliberately disabled: it used to point at
upstream's `changelog.json`, and the `+LD...` suffix makes Maven version ordering rank
these builds below the bare upstream version, so every build reported itself as outdated.

### Before pushing

```sh
bash .github/scripts/server-smoke-test.sh   # boots a dedicated server, asserts "Done ("
```

A successful compile does not prove the mod can *load*: this fork ships a coremod
(`IELoadingPlugin`) and an access transformer, and neither is exercised by `build`.
CI runs this same script on every PR.

### Dependency changes

Every dependency version is pinned exactly, and the resolved graph is locked in
`gradle/dependency-locks/`. After changing a version in `build.gradle`:

```sh
./build.sh resolveAndLockAll --write-locks   # then commit gradle/dependency-locks/
```

CI fails the build if the lockfile is out of date.

### IDE setup

`.idea/gradle.xml` is committed and pins the Gradle JVM to JDK 8 and the distribution to
the project wrapper. **If IntelliJ offers to upgrade the Gradle wrapper, decline.** It has
silently rewritten `gradle/wrapper/gradle-wrapper.properties` to a modern Gradle more than
once, which breaks the build entirely. To recover:

```sh
git checkout -- gradle/wrapper/gradle-wrapper.properties
```

If `clean` fails with "Unable to delete file" under `build/`, a Gradle daemon from a
different Gradle version is still holding the file — `./gradlew --stop` only stops daemons
matching the current wrapper, so check for leftover `java` processes.
