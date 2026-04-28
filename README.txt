Custom Glints
Minecraft 1.20.1 / Forge 47.x
MIT License — attribution required
==============================

Per-item animated enchantment glint with full color and timing control.
Everything lives in NBT — works on any item without touching registries
or loot tables.

This project ships as both a ready-to-drop-in mod AND as source you can
embed in your own mod under the MIT license. See "INTEGRATING INTO YOUR
OWN MOD" below.


================================================================================
  COMMAND USAGE
================================================================================

FORMAT
------
  /give @p <item>{customglint:{design:"<rl>",colors:[I;<ints>],speed:<f>,interpolate:<b>}} 1

  scale and simultaneous are optional; defaults shown below.

  design       — pattern ResourceLocation: customglint:textures/glint/<name>.png
  colors       — one or more signed 32-bit ARGB ints (alpha ignored)
                 simultaneous:1b (default): all colors rendered at once as overlapping layers
                 simultaneous:0b: colors cycle one at a time
  speed        — animation rate multiplier: 1.0 = 20 ticks/color
                 0.25 = slow, 8.0 = fast; values outside this range work via command
  interpolate  — 1b = smooth lerp between colors, 0b = hard cut (cycle mode only)
  scale        — texture tiling multiplier; 1.0 = default pattern size (default: 1.0)
  simultaneous — 1b = render all colors as separate layers at once (default)
                 0b = cycle through colors one at a time

EXAMPLE
-------
  /give @p minecraft:diamond_sword{customglint:{design:"customglint:textures/glint/wave.png",colors:[I;-65536,-16711936,-16776961],speed:0.5f,interpolate:1b}} 1

REMOVE GLINT
------------
  /item replace entity @s weapon.mainhand nbt remove customglint

NOTES
-----
  - The alpha byte of each color is always ignored. Full opacity only.
  - Speed <= 0 is clamped to 1.0 at read time.
  - scale <= 0 is clamped to 1.0 at read time.
  - interpolate:0b with only 1 color is identical to interpolate:1b.
  - simultaneous:1b with only 1 color is identical to simultaneous:0b.


================================================================================
  DESIGNS (16 built-in)
================================================================================

  checker    crosshatch   diamonds   dots
  fire       grid         hexagon    pulse
  ripple     scales       sparkle    stars
  stripes    swirl        wave       zigzag

  Format: customglint:textures/glint/<name>.png
  Add your own by dropping any PNG into assets/customglint/textures/glint/.
  The system converts it to grayscale at runtime on first use.


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
  Gold         0xFFD700  →    -10496
  Aquamarine   0x7FFFD4  →  -8388652
  Lavender     0xE6E6FA  →  -1644806
  Silver       0xC0C0C0  →  -4144960
  White        0xFFFFFF  →        -1
  Black        0x000000  → -16777216

  To convert any hex color: parse as unsigned int, cast to signed int.
  Example: 0xFF8844EE = 4286930158 unsigned = -8037134 signed.


================================================================================
  INTEGRATING INTO YOUR OWN MOD (source embedding, MIT)
================================================================================

Attribution is required. Keep the MIT header in each file you copy.

MULTI-MOD SAFETY
----------------
Multiple mods can embed this code simultaneously without conflicting:

  - Render types and texture namespaces are all scoped to MOD_ID, so each
    embedded copy registers its own isolated set — no overlap.

  - The mixin intercepts use @Inject (not @Redirect). @Inject stacks across
    mods; @Redirect does not — only one mod's redirect fires per call site.
    Every inject checks isCancelled() first and yields if another mod already
    handled the item, so mods compose cleanly.

  - NBT key collisions are avoided automatically. TAG is derived from MOD_ID,
    so changing MOD_ID (which you must do anyway) also changes the NBT key.
    No separate manual step required.

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
  render type names, and the NBT key — nothing else needs changing.

STEP 3 — Copy textures

  Copy assets/customglint/textures/glint/ into your own assets folder:
    assets/<yourmodid>/textures/glint/

  Update design paths in your code or /give examples from
  "customglint:textures/glint/..." to "<yourmodid>:textures/glint/...".

STEP 4 — Mixin config

  The mixin classes must live in a package that matches the "package" field
  in your mixins JSON. Getting this wrong causes the mixins to silently fail
  to load at startup.

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


================================================================================
  HOW IT WORKS (brief)
================================================================================

ItemRendererMixin intercepts getFoilBuffer / getFoilBufferDirect every render
frame. If the item has a customglint NBT tag, it reads the Data record and
returns a VertexMultiConsumer that writes geometry to the custom glint layer(s)
and the item's base layer simultaneously. In simultaneous mode one consumer is
created per color slot; in cycle mode a single animated color is computed for
the current tick.

HumanoidArmorLayerMixin applies the same logic to worn armor, using a separate
render type path (EQUAL depth test + VIEW_OFFSET_Z_LAYERING) so the glint
aligns with the armor surface depth rather than clipping through it.

RenderBuffersMixin captures the fixedBuffers map from RenderBuffers at
construction time. Each distinct glint config gets its own RenderType and
dedicated BufferBuilder inserted into that map — required so geometry is not
silently dropped when switching render types mid-batch.

CustomGlint.getTexture() converts the source PNG to grayscale on first use
and registers it as a DynamicTexture. The shader then tints it using
setShaderColor(), which is how color animation works.

The RenderType cache key includes design, full color array, speed, interpolate,
isItem, scale, and color index. Color index lets simultaneous mode create one
RenderType per color slot. The current frame color is never part of the key —
it flows into the RenderType through a closed-over float[4] holder updated on
every call.


================================================================================
  KNOWN LIMITATIONS
================================================================================

  - clearTextures() is not wired to a resource reload listener. Textures
    survive pack reloads until clearTextures() is called manually.

  - Speed values below ~0.1 cycle so slowly they look static for minutes.
    Values above ~10.0 cycle so fast individual colors become invisible.
