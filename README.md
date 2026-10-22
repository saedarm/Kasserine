# Kasserine

A greybox twin-stick action roguelike set in Tunisia, February 1943. You're one GI holding the south mouth of a canyon while the Afrika Korps pushes down it. A desktop game built with libGDX for Windows, Mac and Linux.

## How a run works

- A run is five days: Sidi Bou Zid, Sbeitla, Kasserine Pass twice, then Thala.
- Each day is a 60-second clock. Survive until it hits zero.
- Two ways to lose: you die, or **the line** breaks. Every German who slips past you out the bottom of the canyon knocks the line down (8 for infantry, 25 for a tank). A bugle call and a red flash on the bar tell you it happened.
- After each day you pick one of three upgrades: Thompson (extra bullet), M1 Garand (damage), Quick Hands (fire rate), Field Dressing (health), Jump Boots (speed), Bazooka Team (triple damage to tanks), AP Rounds (pierce), Air Cover (repairs the line).
- Each day adds a new threat (see below). The first time each one shows up in a run, a tip at the bottom of the screen tells you how to beat it.
- Hold all five days and Rommel calls off the attack.

## What you're up against

| Threat | From | What it does | How to beat it |
| --- | --- | --- | --- |
| **Rifleman** | Day 1 | Walks down the canyon, stops at mid range and shoots. | Shoot him. Roll through his bullets. |
| **Grenadier** | Day 1 | Slightly greener uniform. Hangs back 9-11 units, raises a stick grenade, and lobs it where you're standing. A red circle marks where it lands, then it goes off 0.75 seconds later. | Keep moving. Get out of the circle or roll. His grenades also kill his own men. |
| **MG42 team** | Day 2 | Walks in, digs in behind sandbags, then fires long bursts. Two orange lines on the ground mark its field of fire. The gun can't swing outside them, and it swings slowly. | Get outside the orange lines and come at it from the side, or put a rock between you and the gun. 50 health, so focus it down. |
| **Panzer** | Day 3 | Stops, its turret whines, and a red line shows where the shell will go. | Roll out of the line. |
| **Stuka** | Day 3 | A siren, a shadow sweeping the canyon, and a line of five red circles through where you're standing. The bombs land as the plane passes over. | Move off the line, or roll when a circle is about to go off. Bombs kill German infantry too. |

Anything with a red circle hurts German infantry as well as you. Rolling makes you immune to all of it.

## Controls

Keyboard, mouse and controller all work, and you can switch at any time. Whatever you used last decides what the on-screen prompts say ("Press Enter" or "Press A").

| | Move | Aim | Fire | Roll | Pause |
| --- | --- | --- | --- | --- | --- |
| **Keyboard + mouse** | WASD or arrow keys | Mouse | Left click or Space | Right-click or Shift | Esc or P |
| **Keyboard only** | Arrow keys or WASD | Automatic: the nearest German, or the way you're moving | Space, Z or J | Shift, X or K | Esc or P |
| **Controller** | Left stick or D-pad | Right stick | Push the right stick, or hold R1/R2 | A or L1 | Start |

Keyboard-only aiming kicks in when the mouse hasn't moved for 2.5 seconds. Arrows + Z to fire + X to roll is the classic arcade layout.

Menus work with any of them:
- **Upgrade cards:** click a card, or use left/right (arrows, A/D, stick or D-pad) and Enter, Space, Z or A to pick. Number keys 1, 2, 3 pick directly.
- **Paused or game over:** click, Enter, Space or A.
- Menu presses are ignored for 0.6 seconds after a screen appears, so holding fire when a day ends doesn't skip the upgrade screen.

The roll lasts 0.28 seconds. Bullets and shells can't hit you during it, and it recharges in 0.9 seconds. You roll the way you're moving, or the way you're aiming if you're standing still. You can't shoot mid-roll.

## Code

```
core/     all game code
lwjgl3/   desktop launcher and the runnable-jar build
assets/   sfx/*.wav
tools/    make_sfx.py, which generates the sound effects
Play Kasserine.bat   double-click to build and play on Windows
```

| File | What it does |
| --- | --- |
| `Battle.java` | All the rules as plain Java: the canyon, rocks, player, roll, every enemy type, bullets, grenades, bombing runs, explosions, the clock, the line, upgrades. Draws nothing and plays nothing, so you can simulate runs headless. |
| `Kasserine.java` | Builds the greybox 3D models, follows the player with the camera, draws units, MG nests, grenades, red warning circles, Stukas and the HUD, handles menus and clicks. Turns battle events into effects, sounds, tips, screen shake and freeze frames. |
| `Fx.java` | Visual-only effects: muzzle flashes, dust, blood, sparks, smoke, explosions, scorch marks, soldiers falling over, burning tank wrecks. Cleared at the start of each day. |
| `Sfx.java` | Loads and plays the sounds, with random pitch, distance falloff and left/right panning. |
| `Controls.java` | Merges keyboard, mouse and controller into one move/aim/fire/roll value per frame, plus menu presses. Tracks which you used last. |

### How effects get from the rules to the screen

`Battle` never calls graphics or sound code. When something happens it adds an `Event` to `battle.events` (`SHOT`, `KILL`, `TANK_AIM`, `BLAST`, `PLAYER_HIT` and so on), with a position and a direction. After each update, `Kasserine.handleEvents()` goes through the list and decides what that looks and sounds like. To add a new effect, emit an event in `Battle` and handle it in `handleEvents()`.

Game-feel settings live in `handleEvents()`:
- `freeze`: the world stops for a moment on a hit. 0.025s for a kill, 0.04s when you're hit, 0.12s for a tank kill.
- `shake`: 0 to 1 screen shake, scaled by how close a blast was.
- `kickX/kickZ`: a small camera recoil on each of your shots.

### Sound

The sounds are synthesized from noise and sine waves by `tools/make_sfx.py`, so there are no licensing questions. They're placeholders. To change one, edit the script and run `python3 tools/make_sfx.py` from the project root (needs numpy), or drop in your own `.wav` with the same name.

### Coordinates

`x` runs across the canyon, `z` runs up it. The Germans come from high `z`. With this camera, screen right is world `-x`, which is why `Controls` and the sound panning flip `x`.

Controllers come from the gdx-controllers library (2.2.4), which is the one extra dependency.

## Running on desktop

You need Java 17 or newer. Android Studio and IntelliJ both ship one, so if either is installed you already have it.

**Easiest: double-click `Play Kasserine.bat`.** The first time, it builds the game (a minute or two), then it starts. After that it starts straight away. It finds Java on its own: `JAVA_HOME` if you've set it, otherwise Android Studio's bundled copy, otherwise whatever `java` is on your PATH. If you change the code, delete `lwjgl3\build\libs\Kasserine-desktop.jar` and it rebuilds next time.

**From a terminal**, in the project folder:

```
gradlew lwjgl3:run
```

That runs straight from the source, so it always has your latest changes. Use `./gradlew` instead of `gradlew` in Git Bash, or on Mac and Linux.

**A single file to share:**

```
gradlew lwjgl3:dist
java -jar lwjgl3/build/libs/Kasserine-desktop.jar
```

The jar has everything inside it, sounds included, and runs on Windows, Mac or Linux, anywhere Java 17+ is installed.

**From IntelliJ or Android Studio:** open the folder, let Gradle sync, then open the Gradle panel on the right, then go to Kasserine → lwjgl3 → Tasks → application → double-click **run**. After that it shows up in the run menu at the top, so you can start it with the green play button.

### If it doesn't start

- **"java is not recognized"** or **"JAVA_HOME is not set"** (from the terminal commands; the .bat file handles this itself): Java isn't on your PATH. Point `JAVA_HOME` at Android Studio's copy. In a Command Prompt:
  ```
  setx JAVA_HOME "C:\Program Files\Android\Android Studio\jbr"
  ```
  Then close the terminal and open a new one. Running from inside Android Studio avoids this, because it uses its own Java.
- **"Unsupported class file major version"** or a Java version error: you have an old Java, 8 or 11. Use Android Studio's copy as above.
- **The window opens but there's no sound:** the game keeps going without audio if it can't find a sound device. Check your Windows output device.

## Tuning

Most of the feel lives in a few numbers in `Battle.java`:

- `DODGE_TIME`, `DODGE_SPEED`, `DODGE_COOLDOWN` at the top.
- `TANK_RANGE`, `TANK_WINDUP` (how long the warning shows), `SHELL_SPEED`, `BLAST_RADIUS` at the top. Tank reload is in `updateTankGun()`.
- `MG_ARC`, `MG_RANGE`, `MG_TURN`, `MG_SETUP`, `MG_BURST`, `MG_RELOAD`, `MG_RATE` for MG42 teams.
- `GRENADE_WINDUP`, `GRENADE_FUSE`, `GRENADE_RADIUS` for grenadiers, and `STUKA_PASS`, `BOMB_RADIUS` for Stukas. How often Stukas come is in `updateStukas()`.
- Which threats show up on which day, and how often, is all in `rollThreat()`.
- `DAY_LENGTH` and the `spawnTimer` formula in `spawnEnemies()` control how busy each day gets.
- Enemy health and speed are set in `spawnEnemies()`. Rifle range, fire rate and damage are in `updateRifle()`.
- Player starting stats are in `reset()`.

In a headless simulation of 400 runs, a bot that aims badly, stands still, and only rolls out of red circles and tank lines dies on day 2 about 40% of the time and on day 3 most of the rest. With the new threats switched off, the same bot dies on day 2 about 26% of the time. Rolling cuts explosion damage from about 45 to under 10 per run, so the warnings work. The bot never flanks MG42s or hides behind rocks, so a real player should do noticeably better. Tune from actual play.

## Roadmap

Build in this order. Each step should make the game more fun to play before the next one starts. Content added to a game that doesn't feel good to play doesn't help.

### 1. Combat feel (done in this version)
- [x] Dodge roll you can't be hit during
- [x] Muzzle flashes, hit sparks, dust, blood, knockback and stagger
- [x] Soldiers fall over, tanks become burning wrecks, shells leave scorch marks
- [x] Freeze frames, screen shake, camera recoil
- [x] Tanks show a warning line before firing
- [x] Placeholder sound for everything
- [x] Keyboard, mouse and controller support
- [ ] Play it and write down what feels wrong

### 2. Enemies that make you play differently
Each new enemy should make you do something different, not just take more hits.
- [x] **MG42 team:** digs in and fires into a fixed arc. Flank it or break line of sight behind rocks.
- [x] **Grenadiers:** throw at where you're standing, which punishes standing still.
- [x] **Stuka strike:** a shadow crosses the canyon, then bombs land along its path.
- [ ] **Officer:** buffs nearby troops, so you have to pick him out of the crowd.
- [ ] **Panzer III upgrade:** armored from the front, weak from the sides and back.
- [ ] **Boss per act**, such as a Tiger tank at the end. Tigers fought in Tunisia that winter.
- [ ] Move enemy types into their own classes or a JSON file so `Battle.java` stays readable. It's over 800 lines now, so this is due soon.

### 3. Build variety
- Weapons as loot (M1 Garand, Thompson, BAR), each with its own handling.
- One active ability slot: grenade, smoke, or calling in a mortar strike.
- Upgrades that combine with each other instead of flat +X% bonuses.

### 4. Run structure and meta progression
- A branching map between fights: battle, supply drop, aid station, event card.
- Switch to libGDX `Screen`s for the menu, map and battle.
- "Lessons learned" points from failed runs that unlock permanent upgrades, like better radios, air support or veteran squads. After Kasserine, Patton replaced Fredendall and the Army changed how it fought, and this mirrors that.

### 5. Art and polish
- Low-poly glTF models from Blender via gdx-gltf, plus shadows and animation. Replace the greybox one piece at a time.
- Real recorded sound.
- A save system, settings, a main menu, and a page on itch.io or Steam.
