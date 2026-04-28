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
  /give @p <item>{custom_glint:{design:"<rl>",colors:[I;<ints>],speed:<f>,interpolate:<b>}} 1

  design      — pattern ResourceLocation: customglint:textures/glint/<name>.png
  colors      — one or more signed 32-bit ARGB ints (alpha ignored)
                single entry = static color, multiple entries = animated cycle
  speed       — animation rate multiplier: 1.0 = 20 ticks/color
                0.25 = slow, 8.0 = fast; values outside this range work via command
  interpolate — 1b = smooth lerp between colors, 0b = hard cut

EXAMPLE
-------
  /give @p minecraft:diamond_sword{custom_glint:{design:"customglint:textures/glint/wave.png",colors:[I;-65536,-16711936,-16776961],speed:0.5f,interpolate:1b}} 1

REMOVE GLINT
------------
  /item replace entity @s weapon.mainhand nbt remove custom_glint

NOTES
-----
  - The alpha byte of each color is always ignored. Full opacity only.
  - Speed <= 0 is clamped to 1.0 at read time.
  - interpolate:0b with only 1 color is identical to interpolate:1b.


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

  - The one manual step: change the TAG constant in CustomGlint.java from
    "custom_glint" to something unique to your mod (e.g. "mymod_glint").
    If two mods share the same TAG they will read and overwrite each other's
    NBT data. MOD_ID alone does not protect this — TAG must be changed
    separately.

STEP 1 — Copy source files

    src/.../glint/CustomGlint.java
    src/.../mixin/ItemRendererMixin.java
    src/.../mixin/RenderBuffersMixin.java

  Update the package declaration at the top of each file to match your
  project's package root.

STEP 2 — Wire MOD_ID

  CustomGlint.java contains:
    import static <package>.YourMod.MOD_ID;

  Change that import to point to your own main mod class. The field must be
  named MOD_ID. That one field drives all ResourceLocation namespaces and
  render type names — nothing else needs changing.

  Optional: change the TAG constant in CustomGlint.java from "custom_glint"
  to something unique to your mod to avoid NBT key collisions if multiple
  mods embed this code.

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
frame. If the item has a custom_glint NBT tag, it reads the Data record,
computes the animated color for the current tick, and returns a
VertexMultiConsumer that draws the custom glint layer on top of the vanilla
glint.

RenderBuffersMixin captures the fixedBuffers map from RenderBuffers at
construction time. Each distinct glint config gets its own RenderType and
dedicated BufferBuilder inserted into that map — required so geometry is not
silently dropped when switching render types mid-batch.

CustomGlint.getTexture() converts the source PNG to grayscale on first use
and registers it as a DynamicTexture. The shader then tints it using
setShaderColor(), which is how per-frame color animation works.

The RenderType cache is keyed by design + full color array + speed + interpolate.
The current frame color is never part of the key — it flows into the RenderType
through a closed-over float[4] holder that is updated on every call.


================================================================================
  KNOWN LIMITATIONS
================================================================================

  - clearTextures() is not wired to a resource reload listener. Textures
    survive pack reloads until clearTextures() is called manually.

  - Speed values below ~0.1 cycle so slowly they look static for minutes.
    Values above ~10.0 cycle so fast individual colors become invisible.
