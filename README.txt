Custom Glints — Minecraft 1.20.1 / Forge 47.x
MIT License — attribution required
================================================================================

Per-item animated enchantment glint with full color and timing control.
Works on any item or armor piece. Everything lives in NBT — no registry
changes, no loot table edits, no item subclasses.

Ships with a Glint Wand for in-game editing and a full NBT command surface
for server admins and datapacks. Source can be embedded in your own mod
without taking this as a dependency — see the bottom of this file.


================================================================================
  USING THE GLINT WAND
================================================================================

Find the Glint Wand in the Custom Glints creative tab. Hold it and right-click
to open the editor.

  - Pick any item from the item list on the left.
  - Choose a pattern from the 4x4 design grid.
  - Add up to 8 color slots. Edit each via hex input or R/G/B sliders.
  - Adjust speed (0.25x – 8.0x) and toggle smooth color interpolation.
  - Click "Get Item" — the item lands in your inventory with the glint applied.
  - To remove a glint, hold the glinted item in your off-hand and click
    "Remove Glint" while holding the wand in your main hand.

The wand remembers your last config. Re-opening it pre-fills everything.


================================================================================
  NBT COMMAND FORMAT
================================================================================

  /give @p <item>{<modid>:{design:"<rl>",colors:[I;<ints>],speed:<f>,interpolate:<b>}}

  The tag key is the mod ID: "customglint" in the standalone version.
  Embedding devs: it changes automatically when you change MOD_ID.

FIELDS
------
  design        ResourceLocation of the pattern PNG.
                Format: customglint:textures/glint/<name>.png
                Custom PNGs are supported — drop them in the assets folder.

  colors        One or more signed 32-bit ARGB ints. Alpha byte is ignored.
                simultaneous:1b (default): all colors rendered as layers at once.
                simultaneous:0b: colors cycle one at a time.

  speed         Animation rate multiplier. 1.0 = 20 ticks per color.
                0.25 = very slow, 8.0 = very fast. Clamped to 1.0 if <= 0.

  interpolate   1b = smooth lerp between colors (cycle mode only).
                0b = hard cut between colors.

  scale         Texture tiling multiplier. 1.0 = default size. (optional, default 1.0)
                Clamped to 1.0 if <= 0.

  simultaneous  1b = all color slots rendered as overlapping layers simultaneously.
                0b = cycle through one color at a time.
                (optional, default 1b)

EXAMPLE
-------
  /give @p minecraft:diamond_sword{customglint:{design:"customglint:textures/glint/wave.png",colors:[I;-65536,-16711936,-16776961],speed:0.5f,interpolate:1b}} 1

REMOVE GLINT
------------
  /item replace entity @s weapon.mainhand nbt remove customglint

NOTES
-----
  - Alpha byte of each color int is always ignored. Full opacity only.
  - interpolate:0b with 1 color is identical to interpolate:1b.
  - simultaneous:1b with 1 color is identical to simultaneous:0b.
  - Speed values below ~0.1 look static; above ~10.0 individual colors
    become invisible due to cycle speed.


================================================================================
  DESIGNS (16 built-in)
================================================================================

  checker    crosshatch   diamonds   dots
  fire       grid         hexagon    pulse
  ripple     scales       sparkle    stars
  stripes    swirl        wave       zigzag

  Format: customglint:textures/glint/<name>.png

  Custom designs: drop any PNG into assets/customglint/textures/glint/.
  The system converts it to grayscale at runtime on first use and applies
  your color on top via shader tint.


================================================================================
  COLOR REFERENCE (hex → signed int)
================================================================================

  Red          0xFF0000  →  -65536
  Orange       0xFF8000  →  -32768
  Yellow       0xFFFF00  →    -256
  Lime         0x7FFF00  → -8388864
  Green        0x00FF00  → -16711936
  Forest Green 0x228B22  → -14513374
  Teal         0x008080  → -16744320
  Cyan         0x00FFFF  → -16711681
  Sky Blue     0x00BFFF  → -16728065
  Blue         0x0000FF  → -16776961
  Dark Blue    0x00008B  → -16777077
  Indigo       0x4B0082  → -11861886
  Violet       0xEE82EE  →  -1146130
  Purple       0x8000FF  →  -8388353
  Magenta      0xFF00FF  →    -65281
  Hot Pink     0xFF69B4  →    -38476
  Coral        0xFF6347  →    -40121
  Dark Red     0x8B0000  →  -7667712
  Gold         0xFFD700  →  -10496
  Aquamarine   0x7FFFD4  →  -8388652
  Lavender     0xE6E6FA  →  -1644806
  Silver       0xC0C0C0  →  -4144960
  White        0xFFFFFF  →       -1
  Black        0x000000  → -16777216

  To convert any hex color: parse as unsigned int, cast to signed int.
  Example: 0xFF8844EE = 4286930158 unsigned = -8037134 signed.


================================================================================
  COMMAND EXAMPLES
================================================================================

-- SINGLE COLOR --

Red wave on diamond sword:
/give @p minecraft:diamond_sword{customglint:{design:"customglint:textures/glint/wave.png",colors:[I;-65536],speed:1.0f,interpolate:1b}} 1

White wave on netherite chestplate:
/give @p minecraft:netherite_chestplate{customglint:{design:"customglint:textures/glint/wave.png",colors:[I;-1],speed:1.0f,interpolate:1b}} 1

Gold sparkle on netherite helmet:
/give @p minecraft:netherite_helmet{customglint:{design:"customglint:textures/glint/sparkle.png",colors:[I;-10496],speed:1.0f,interpolate:1b}} 1

Blue sparkle on diamond pickaxe:
/give @p minecraft:diamond_pickaxe{customglint:{design:"customglint:textures/glint/sparkle.png",colors:[I;-16776961],speed:1.0f,interpolate:1b}} 1


-- MULTI-COLOR ANIMATED (smooth lerp) --

Rainbow (wave, slow):
/give @p minecraft:diamond_sword{customglint:{design:"customglint:textures/glint/wave.png",colors:[I;-65536,-32768,-256,-16711936,-16776961,-1146130],speed:0.5f,interpolate:1b}} 1

Fire gradient (dark red → red → orange → yellow):
/give @p minecraft:netherite_axe{customglint:{design:"customglint:textures/glint/fire.png",colors:[I;-7667712,-65536,-32768,-256],speed:0.8f,interpolate:1b}} 1

Ice (white → sky blue → cyan → dark blue):
/give @p minecraft:trident{customglint:{design:"customglint:textures/glint/wave.png",colors:[I;-1,-16728065,-16711681,-16777077],speed:0.6f,interpolate:1b}} 1

Galaxy (sparkle, slow):
/give @p minecraft:netherite_chestplate{customglint:{design:"customglint:textures/glint/sparkle.png",colors:[I;-16777077,-11861886,-8388353,-1146130,-1,-1146130,-8388353],speed:0.4f,interpolate:1b}} 1

Ocean (dark blue → blue → cyan → sky blue → white):
/give @p minecraft:trident{customglint:{design:"customglint:textures/glint/wave.png",colors:[I;-16777077,-16776961,-16711681,-16728065,-1],speed:0.7f,interpolate:1b}} 1

Lava (black → dark red → red → orange → yellow):
/give @p minecraft:netherite_sword{customglint:{design:"customglint:textures/glint/fire.png",colors:[I;-16777216,-7667712,-65536,-32768,-256],speed:0.6f,interpolate:1b}} 1

Blood Moon (dark red → red → dark red, pulse):
/give @p minecraft:netherite_sword{customglint:{design:"customglint:textures/glint/pulse.png",colors:[I;-7667712,-65536,-7667712],speed:0.5f,interpolate:1b}} 1

Cyber (cyan → purple → magenta, fast):
/give @p minecraft:netherite_pickaxe{customglint:{design:"customglint:textures/glint/grid.png",colors:[I;-16711681,-8388353,-65281],speed:2.0f,interpolate:1b}} 1

Amethyst (diamonds):
/give @p minecraft:diamond_axe{customglint:{design:"customglint:textures/glint/diamonds.png",colors:[I;-11861886,-8388353,-1146130,-1644806,-1146130,-8388353],speed:0.6f,interpolate:1b}} 1

Cotton Candy (sparkle):
/give @p minecraft:crossbow{customglint:{design:"customglint:textures/glint/sparkle.png",colors:[I;-38476,-1644806,-16711681,-38476],speed:1.0f,interpolate:1b}} 1


-- MULTI-COLOR ANIMATED (hard cut) --

Police Lights (stripes, very fast):
/give @p minecraft:diamond_sword{customglint:{design:"customglint:textures/glint/stripes.png",colors:[I;-65536,-16776961],speed:6.0f,interpolate:0b}} 1

Strobe Rainbow (sparkle, fast):
/give @p minecraft:netherite_sword{customglint:{design:"customglint:textures/glint/sparkle.png",colors:[I;-65536,-256,-16711936,-16776961,-8388353],speed:4.0f,interpolate:0b}} 1

Alert Blink (red → black, very fast):
/give @p minecraft:netherite_sword{customglint:{design:"customglint:textures/glint/pulse.png",colors:[I;-65536,-16777216],speed:8.0f,interpolate:0b}} 1

Christmas (wave, hard red/green):
/give @p minecraft:diamond_axe{customglint:{design:"customglint:textures/glint/wave.png",colors:[I;-65536,-16711936],speed:1.0f,interpolate:0b}} 1

Halloween (fire, orange/black/purple):
/give @p minecraft:netherite_axe{customglint:{design:"customglint:textures/glint/fire.png",colors:[I;-32768,-16777216,-8388353],speed:1.0f,interpolate:0b}} 1


-- FULL ARMOR SETS --

Galaxy armor (sparkle, slow lerp):
/give @p minecraft:netherite_helmet{customglint:{design:"customglint:textures/glint/sparkle.png",colors:[I;-16777077,-11861886,-8388353,-1146130,-1],speed:0.5f,interpolate:1b}} 1
/give @p minecraft:netherite_chestplate{customglint:{design:"customglint:textures/glint/sparkle.png",colors:[I;-16777077,-11861886,-8388353,-1146130,-1],speed:0.5f,interpolate:1b}} 1
/give @p minecraft:netherite_leggings{customglint:{design:"customglint:textures/glint/sparkle.png",colors:[I;-16777077,-11861886,-8388353,-1146130,-1],speed:0.5f,interpolate:1b}} 1
/give @p minecraft:netherite_boots{customglint:{design:"customglint:textures/glint/sparkle.png",colors:[I;-16777077,-11861886,-8388353,-1146130,-1],speed:0.5f,interpolate:1b}} 1

Fire armor (fire pattern, lava colors):
/give @p minecraft:netherite_helmet{customglint:{design:"customglint:textures/glint/fire.png",colors:[I;-16777216,-7667712,-65536,-32768,-256],speed:0.8f,interpolate:1b}} 1
/give @p minecraft:netherite_chestplate{customglint:{design:"customglint:textures/glint/fire.png",colors:[I;-16777216,-7667712,-65536,-32768,-256],speed:0.8f,interpolate:1b}} 1
/give @p minecraft:netherite_leggings{customglint:{design:"customglint:textures/glint/fire.png",colors:[I;-16777216,-7667712,-65536,-32768,-256],speed:0.8f,interpolate:1b}} 1
/give @p minecraft:netherite_boots{customglint:{design:"customglint:textures/glint/fire.png",colors:[I;-16777216,-7667712,-65536,-32768,-256],speed:0.8f,interpolate:1b}} 1

Ice armor (scales, white → cyan gradient):
/give @p minecraft:diamond_helmet{customglint:{design:"customglint:textures/glint/scales.png",colors:[I;-1,-16728065,-16711681,-16777077],speed:0.5f,interpolate:1b}} 1
/give @p minecraft:diamond_chestplate{customglint:{design:"customglint:textures/glint/scales.png",colors:[I;-1,-16728065,-16711681,-16777077],speed:0.5f,interpolate:1b}} 1
/give @p minecraft:diamond_leggings{customglint:{design:"customglint:textures/glint/scales.png",colors:[I;-1,-16728065,-16711681,-16777077],speed:0.5f,interpolate:1b}} 1
/give @p minecraft:diamond_boots{customglint:{design:"customglint:textures/glint/scales.png",colors:[I;-1,-16728065,-16711681,-16777077],speed:0.5f,interpolate:1b}} 1


-- MISC ITEMS --

Glowing totem (rainbow wave):
/give @p minecraft:totem_of_undying{customglint:{design:"customglint:textures/glint/wave.png",colors:[I;-65536,-32768,-256,-16711936,-16776961,-1146130],speed:1.0f,interpolate:1b}} 1

Glowing elytra (ocean):
/give @p minecraft:elytra{customglint:{design:"customglint:textures/glint/wave.png",colors:[I;-16777077,-16776961,-16711681,-16728065,-1],speed:0.7f,interpolate:1b}} 1

Glowing shield (galaxy):
/give @p minecraft:shield{customglint:{design:"customglint:textures/glint/sparkle.png",colors:[I;-16777077,-11861886,-8388353,-1146130,-1],speed:0.5f,interpolate:1b}} 1

Glowing mace (blood moon pulse):
/give @p minecraft:mace{customglint:{design:"customglint:textures/glint/pulse.png",colors:[I;-7667712,-65536,-7667712],speed:0.5f,interpolate:1b}} 1


================================================================================
  KNOWN LIMITATIONS
================================================================================

  - clearTextures() is not wired to a resource reload listener. Textures
    survive pack reloads until clearTextures() is called manually.

  - Speed values below ~0.1 cycle so slowly they look static for minutes.
    Values above ~10.0 cycle so fast individual colors become invisible.

  - If a player's inventory is full when clicking "Get Item" in the editor,
    the item is silently dropped.


================================================================================
  EMBEDDING IN YOUR OWN MOD (no dependency required)
================================================================================

You can copy the glint system directly into your mod under the MIT license.
Attribution required — keep the MIT header in each file you copy.
The Glint Wand, GUI, and networking (module/) are optional. You can drive
everything through CustomGlint.write() alone and skip them entirely.

JAVA API
--------
All public methods live in CustomGlint. The design and color constants
are also there, so you never need raw integers or ResourceLocation strings.

  // Apply a glint
  CustomGlint.write(stack, CustomGlint.WAVE,
      new int[]{CustomGlint.RED, CustomGlint.BLUE},
      1.0f,   // speed
      true,   // interpolate
      1.0f,   // patternScale
      true);  // simultaneous

  // Check presence
  boolean hasGlint = CustomGlint.has(stack);

  // Read data (returns null if absent)
  CustomGlint.Data data = CustomGlint.read(stack);

  // Remove
  CustomGlint.remove(stack);

  // Color constants: CustomGlint.RED, BLUE, GREEN, GOLD, WHITE, etc.
  // Design constants: CustomGlint.WAVE, FIRE, SPARKLE, GALAXY, etc.
  // Compute animated color for the current tick (useful for custom rendering):
  int color = CustomGlint.computeAnimatedColor(data);

MULTI-MOD SAFETY
----------------
Multiple mods embedding this code simultaneously do not conflict:

  - Render types and texture namespaces are scoped to MOD_ID — each embedded
    copy registers its own isolated set with no overlap.

  - Mixin intercepts use @Inject, not @Redirect. @Inject stacks across mods;
    @Redirect does not. Every inject checks isCancelled() first and yields if
    another mod already handled the item.

  - The NBT tag key is derived from MOD_ID automatically. Changing MOD_ID
    (which you must do) also changes the key — no manual step needed.

STEP 1 — Copy source files

    src/.../glint/CustomGlint.java
    src/.../mixin/ItemRendererMixin.java
    src/.../mixin/HumanoidArmorLayerMixin.java
    src/.../mixin/RenderBuffersMixin.java

  Update the package declaration at the top of each file to match your
  project's package root.

STEP 2 — Wire MOD_ID

  CustomGlint.java contains:
    import static <package>.YourMod.MOD_ID;

  Change that import to point to your own main mod class. The field must be
  named MOD_ID. That one field drives all ResourceLocation namespaces,
  render type names, and the NBT tag key — nothing else needs changing.

STEP 3 — Copy textures

  Copy assets/customglint/textures/glint/ into your own assets folder:
    assets/<yourmodid>/textures/glint/

  Update design paths in your code or /give examples from
  "customglint:textures/glint/..." to "<yourmodid>:textures/glint/...".
  The design constants in CustomGlint update automatically via MOD_ID.

STEP 4 — Mixin config

  The mixin classes must live in a package that matches the "package" field
  in your mixins JSON. Getting this wrong causes the mixins to silently fail.

  If you already have a mixins JSON, add to its "client" array:
    "ItemRendererMixin",
    "HumanoidArmorLayerMixin",
    "RenderBuffersMixin"

  If you need a new file, create src/main/resources/<yourmodid>.mixins.json:
    {
      "required": true,
      "minVersion": "0.8",
      "package": "<your.package.mixin>",
      "compatibilityLevel": "JAVA_17",
      "refmap": "<yourmodid>.mixins.refmap.json",
      "client": [
        "ItemRendererMixin",
        "HumanoidArmorLayerMixin",
        "RenderBuffersMixin"
      ],
      "injectors": { "defaultRequire": 1 }
    }

  Register it in your mods.toml:
    [[mixins]]
    config="<yourmodid>.mixins.json"
