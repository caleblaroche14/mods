# DSLR Camera

A real camera for Minecraft. Take photos with exposure, aperture, ISO, shutter speed and focus that behave like a
DSLR, print them onto maps, hang big prints across walls of item frames, and touch them up in a photo editor at
any cartography table.

It comes in two parts:

| Part | Who needs it | File |
|---|---|---|
| **Client mod** (Fabric) | Every player who wants to take or edit photos | `dslrcamera-1.0.0.jar` |
| **Server plugin** (Paper) | The server, once | `dslr-camera-plugin-1.0.0.jar` |

Players without the mod can still join, see cameras (as spyglasses), and see printed photos on maps and walls.
They just can't take or edit pictures.

---

## For players

### Installing

1. Install **Fabric Loader** for **Minecraft 26.2** (Prism Launcher, the Fabric installer, or similar).
2. Put these in your `mods` folder:
   - **Fabric API** for 26.2
   - **`dslrcamera-1.0.0.jar`**
3. Join a server running the DSLR Camera plugin.

It works alongside Sodium, Iris and shader packs (tested with Complementary Unbound).

### Getting a camera

Craft one at a crafting table:

```
     [Iron Ingot]
[Iron Ingot] [Spyglass] [Iron Ingot]
     [Redstone]
```

…or ask an admin to run `/dslrcamera give <your name>`.

The camera is a real item. You can trade it, store it in chests, or lend it to friends. Normal spyglasses stay
normal spyglasses.

### Controls

Hold the camera, then:

| Input | What it does |
|---|---|
| **Right-click** | Raise or lower the camera (it's a toggle, no need to hold) |
| **Left-click** | Take the photo |
| **X** | Select the next setting on the dial (**Shift+X** for the previous one) |
| **Mouse wheel** | Turn the dial: change the selected setting |
| **V** | Focus lock (AF-L). Press again to go back to autofocus |

All keys can be changed in **Options → Controls → DSLR Camera**.

Opening your inventory, chat or any other screen lowers the camera.

### The viewfinder

- **Bright area:** exactly what will be printed. The dark bars are cropped out. Faint grid lines show where each map
  tile will split.
- **Top left:** focus mode and distance, e.g. `AF 5.5m`. Green means your subject is in focus.
- **Top right:** the exposure mode dial: **M A S P**.
- **Center brackets:** the autofocus point. They turn green when what you're aiming at is sharp.
- **Bottom strip:** shutter, aperture, the **exposure meter**, ISO, exposure compensation, focal length and print
  size. The selected setting is highlighted.
- **Dial ribbon** (above the strip): the selected setting's neighbouring values, like looking at a camera's command
  dial.

**The exposure meter** runs from −3 to +3 stops. The bar shows how far off your exposure is: right means too bright,
left means too dark. A blinking red mark means you're more than 3 stops off.

What you see in the viewfinder is exactly what the photo will look like: brightness, blur, grain and all.

### Camera settings

Select a setting with **X** and change it with the **mouse wheel**.

| Setting | Range | What it does |
|---|---|---|
| **Mode** | M, A, S, P | Who picks the exposure (see below) |
| **Shutter** | 30" to 1/32000 | How long the sensor collects light. Slower = brighter, and anything moving blurs |
| **Aperture** | f/1.4 to f/22 | How wide the lens opens. Low numbers = brighter and a blurrier background |
| **ISO** | 100 to 25600 | Sensor sensitivity. Higher = brighter but grainier |
| **Exp. comp** | −3 to +3 | Make auto exposure brighter or darker (not in M mode) |
| **Focus** | AF, 0.3m to ∞ | Autofocus, or turn the focus ring by hand |
| **Zoom** | 16mm to 300mm | Wide angle to telephoto. Mouse sensitivity slows down as you zoom in |
| **Print size** | 1×1 up to 4×3 / 3×4 | How many maps the photo is printed across |

#### Exposure modes

| Mode | You choose | The camera chooses |
|---|---|---|
| **P** (Program) | ISO, compensation | Shutter and aperture |
| **A** (Aperture priority, default) | Aperture, ISO, compensation | Shutter |
| **S** (Shutter priority) | Shutter, ISO, compensation | Aperture |
| **M** (Manual) | Everything | Nothing. Watch the meter |

The dial skips settings the current mode controls for you.

#### Focus

- **AF:** continuously focuses on whatever is under the center brackets.
- **AF-L (press V):** freezes focus where it is, so you can aim somewhere else and keep your subject sharp. Great
  for off-centre subjects.
- **Manual:** select **Focus** and scroll to set a distance. Scroll all the way back to return to AF. The brackets
  still turn green when what you're aiming at is in focus.

#### Long exposures

Any shutter speed **slower than 1/30** is a real long exposure. The viewfinder goes dark with a countdown and the
camera collects light for the whole time:

- **Hold still.** If you move, the photo blurs.
- Moving mobs, water and particles streak.
- At night, long exposures (1–30 seconds) can make a dark scene bright.

Lowering the camera (right-click) cancels the exposure.

### Tips for great photos

- **Blurry background ("bokeh"):** use **A** mode, set the aperture to **f/1.4–f/2.8**, zoom in to **85mm or
  more**, get close to your subject and keep the background far away.
- **Everything sharp (landscapes):** use **f/8–f/16**.
- **Bright sunlight at f/1.4:** this works thanks to the electronic shutter (1/8000 and faster). If the meter still
  blinks, stop down a little.
- **Night:** raise the ISO for handheld shots, or use a long exposure and stand still for a clean, bright result.
- **Too bright or too dark?** In P, A or S mode use **Exp. comp**. In M mode, turn the dials until the meter bar
  is centred.

### Printing

Every photo you take is printed straight into your inventory. Printing costs **1 paper per map tile** by default,
so a 3×2 photo costs 6 paper. Your server may set a different cost. Creative-mode players print for free.

- A **1×1** photo is a normal map you can hold or put in a frame.
- A bigger photo arrives as a single **"Photo (W×H)"** item showing a small preview.

You also keep a full-resolution copy on your computer, in `.minecraft/dslr_photos/`. Click the file name in chat to
open it. A `_map.png` next to it shows the printed version.

### Hanging big photos

1. Build a wall of **empty item frames** the same size as your photo (e.g. 3 wide × 2 tall for a 3×2).
2. Hold the "Photo (3×2)" item and **right-click the top-left frame**.
3. The whole photo fills the wall.

Big photos can only be hung on walls (not floors or ceilings). If you break a frame, its piece drops as a map.

### Editing photos (cartography table)

1. Hold a printed photo and **right-click a cartography table**.
2. The photo editor opens:
   - **Light:** Exposure, Contrast, Highlights, Shadows, and Levels (Black, Midtones, White)
   - **Color:** Temperature, Tint, Saturation, Vibrance, Hue
   - **Effects:** Sharpen, Vignette, Grain, Fade, Sepia
   - **Presets:** B&W, Sepia, Vintage, Vivid, Noir, Warm, Cool, Dreamy
3. Useful buttons:
   - **Map view:** shows exactly how the result will look when printed in map colours.
   - **Before/After:** compare with the original.
   - **Reset:** clear all edits. **Double-click** a slider to reset just that one.
4. Click **Print** to get a new, edited copy. It costs the same as a normal print, and **you keep the original**.

You can edit any photo you hold, even ones other players took, and you can edit an edited copy again. If the
server can't print (no paper, or the printer is cooling down), the editor stays open with your edits and tells you
why. Stay near the table while you edit.

### Troubleshooting

| Problem | Fix |
|---|---|
| Right-clicking the camera just looks through a normal spyglass | You don't have the mod installed, or the server isn't running the plugin |
| "This server can't print photos" | The server doesn't have the plugin. Your photo is still saved in `dslr_photos/` |
| "You need N paper to print" | Carry more paper (or whatever item your server uses) |
| "The printer is still cooling down" | Wait a few seconds between prints |
| "You need to be holding a camera to print" | Keep the camera in your hand until the print arrives |
| Photo is white or black | Check the exposure meter. Adjust shutter, aperture or ISO until the bar is centred |
| No background blur | Use a wide aperture (f/1.4–f/2.8), zoom in, and get closer to your subject |
| The cartography table opens normally | Hold the photo in your **main** hand when you right-click it |
| Big photo won't hang | You need exactly W×H **empty** frames on a wall, and must click the **top-left** one |

---

## For server owners

### Requirements

- **Paper 26.2** (Spigot isn't supported)
- Players need the client mod to take or edit photos (see above)

### Installing

1. Put **`dslr-camera-plugin-1.0.0.jar`** in the server's `plugins/` folder.
2. **Restart** the server (don't use `/reload`).
3. Edit `plugins/DslrCamera/config.yml` if you like, then restart again.

The plugin and the client mod must be the **same version**. If you update one, update the other.

### Configuration (`plugins/DslrCamera/config.yml`)

| Option | Default | Meaning |
|---|---|---|
| `consume-item` | `PAPER` | Item taken for each print. `""` makes printing free |
| `consume-per-tile` | `1` | How many of that item per map tile (a 3×2 photo uses 6×) |
| `cooldown-seconds` | `3` | Minimum time between prints per player |
| `max-cols` / `max-rows` | `4` / `4` | Largest photo size players may print |
| `camera-recipe` | `true` | Whether the camera crafting recipe is enabled |

Costs apply to both camera prints and edited copies. Creative-mode players never pay.

### Commands and permissions

| Command / permission | Default | Meaning |
|---|---|---|
| `/dslrcamera give [player]` | ops | Give a camera |
| `dslrcamera.give` | ops | Use `/dslrcamera give` |
| `dslrcamera.use` | everyone | Print and edit photos |
| `dslrcamera.bypass` | ops | Print for free with no cooldown |

To make ops pay like everyone else, deny `dslrcamera.bypass` with a permissions plugin (e.g. LuckPerms).

### Data and backups

Everything lives in `plugins/DslrCamera/`. **Back up both folders:**

| Folder | Contents | If lost |
|---|---|---|
| `photos/` | The image on every printed map (one small file per map) | Printed photos turn into blank maps |
| `sources/` | Full-colour copies used by the photo editor | Photos can still be edited, but from their map colours (lower quality) |

Each map tile is 16 KB. Sources are typically 20–300 KB per photo, depending on size.

### How it works (short version)

- The camera is a spyglass tagged by the plugin. The client mod renders it as a camera and turns it into a
  viewfinder.
- All the optics (exposure, depth of field, grain, long exposures) run on the photographer's computer. The server
  only receives the finished picture, already converted to map colours, plus a full-colour copy for editing.
- The server checks the player is really holding a camera (or is at a cartography table for edits), charges the
  print cost, and creates locked maps that survive restarts.

---

## For developers

The project has two Gradle builds, both requiring **JDK 25**:

```bash
# Client mod
cd client-mod && ./gradlew build          # -> build/libs/dslrcamera-1.0.0.jar

# Server plugin
cd paper-plugin && ./gradlew build        # -> build/libs/dslr-camera-plugin-1.0.0.jar
```

### End-to-end test

`test-server/` is a local Paper 26.2 server with the plugin installed. With it running, op the test player (in the
server console: `op DslrTester`), then:

```bash
cd client-mod && ./gradlew runAutotestClient
```

A client joins `localhost`, and with real mouse clicks:

1. Prints a 3×2 photo and hangs it on a wall of item frames.
2. Checks exposure, depth of field, high ISO and a 1-second long exposure.
3. Edits a photo at a cartography table.

It saves screenshots to `client-mod/run/screenshots/autotest_*.png` and logs `PASS` or `FAIL`. The test server's
`spigot.yml` excludes the test's setup commands from the chat spam limit.
