# SoE Launcher: Sanctuary of Exile and Single Player+ on Android

An unofficial edition of [PD2 Launcher for Android](https://github.com/Pocketward/pd2-launcher-android) (0.3.2) that also installs [Project Diablo 2: Sanctuary of Exile](https://github.com/Lukaszpg/PD2-Sanctuary-of-Exile) or [Single Player+](https://github.com/Lukaszpg/PD2-Single-Player-Plus-mod) on top of Project Diablo 2, the way each mod's Windows instructions do. It installs as its own app, **SoE Launcher** (`com.pd2launcher.app.soe`), beside the official app, with its own game folder and characters.

## Using it

1. Install Project Diablo 2 in it as in the official app (Check, Download, Install) until Play is ready.
2. **Settings → Game → Mods:** pick Sanctuary of Exile (Standard or Damnation) or Single Player+, leave the recommended loot filter on (HiimFilter's for Sanctuary of Exile, Roofoo's for Single Player+), and tap **Install**. It downloads the mod's newest release from GitHub, checks each file against GitHub's SHA-256, and puts it into `ProjectD2`. Single Player+ is three files (`ProjectDiablo.dll`, `pd2data.mpq`, `BH.dll`); Switching mods puts Project Diablo 2's own files back first.
3. Tap **Play**. For Sanctuary of Exile, Akara sells a green potion that mentions it and the main menu shows `SoE <version>`; for Single Player+, Akara sells an item with Alkor's quest potion graphics.

**Pause in the background (Settings → Game):** when you leave the app or the screen turns off for 15 seconds, the game process is frozen (SIGSTOP) so it uses no battery, and it resumes where it was when you come back. Save before a long break: Android can still end a frozen app.

- Start with new characters: neither mod takes Project Diablo 2's characters or shared stash, and Sanctuary of Exile's are not compatible with Single Player+'s.
- While the mod is in place, Play offers no Project Diablo 2 update (it would put PD2's files back). When the mod has a new release, the same section offers **Update to …**.
- **Restore Project Diablo 2** puts back every file the mod replaced.
- Keep updates off in the **official PD2 launcher**. If it updates Project Diablo 2 anyway, Play shows **Check files**; after that, install Sanctuary of Exile again.

## How it works

The engine checks `ProjectD2` against its record of Project Diablo 2's release (`ProjectD2/.pd2launcher/accepted-manifest.json`, an MD5 per file) and plays only a matching installation. The install writes the mod's MD5s into that record for the files it replaces (`ProjectDiablo.dll`, `pd2data.mpq`, `BH.dll`, `pd2maps.mpq`, `pd2assets.mpq`, `D2Game.dll`) and keeps the originals and the old record in the app's storage for Restore. The code is in `app/src/main/java/com/pd2launcher/app/soe/` and `ui/SanctuaryRows.kt`.

## Building

`.github/workflows/soe-apk.yml` (Actions → SoE Launcher APK → Run workflow) compiles only the app and takes the engine, App userland and runtime from the official 0.3.2 release APK, checked against its SHA-256. Locally: put those into `app/src/main/assets/{userland,runtime}`, then `PD2_SOE=1 PD2_ENGINE_BINARY=<libpd2launcher.so> ./build.sh`.

Not affiliated with the Project Diablo 2 team, the Sanctuary of Exile author, Pocketward or Blizzard.
