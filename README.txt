Custom Glints — Minecraft 1.20.1 / Forge 47.x
MIT License — attribution required
================================================================================

Per-item animated enchantment glint with full color, timing, and scale control.
Works on any item or armor piece. Everything lives in NBT — no registry
changes, no loot table edits, no item subclasses. 55 built-in designs.

Drop the compiled jar into any Forge 1.20.1 modpack and grab the Glint Wand
from the Custom Glints creative tab to get started. For server/datapack use,
see the NBT format and /glint command below.

================================================================================
  EMBEDDING IN YOUR OWN MOD (no dependency required)
================================================================================

Copy the common/ folder directly into your mod under the MIT license.
Attribution required — keep the MIT header in each file you copy.

STEP 1 — Copy source files

    src/.../common/CustomGlint.java
    src/.../common/mixin/ItemRendererMixin.java
    src/.../common/mixin/HumanoidArmorLayerMixin.java
    src/.../common/mixin/RenderBuffersMixin.java

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


================================================================================
  JAVA API
================================================================================

All public methods live in CustomGlint.

  // Apply a glint (full args)
  CustomGlint.write(stack, CustomGlint.WAVE,
      new int[]{CustomGlint.RED, CustomGlint.BLUE},
      1.0f,   // speed (1.0 = 20 ticks/color)
      true,   // interpolate (smooth lerp between colors)
      1.0f,   // patternScale
      true);  // simultaneous (all colors as stacked layers vs. cycling)

  // Multi-layer glint
  CustomGlint.write(stack, new CustomGlint.Layer[]{
      new CustomGlint.Layer(CustomGlint.WAVE,    new int[]{CustomGlint.RED},  1.0f, true, 1.0f, true),
      new CustomGlint.Layer(CustomGlint.SPARKLE, new int[]{CustomGlint.BLUE}, 2.0f, true, 1.0f, false),
  });

  // Presence check (cheaper than read)
  boolean has = CustomGlint.has(stack);

  // Read back
  CustomGlint.Data data = CustomGlint.read(stack);

  // Remove
  CustomGlint.remove(stack);

  // Pre-glinted ItemStack in one call (useful in creative tab displayItems)
  ItemStack stack = CustomGlint.glinted(Items.DIAMOND_SWORD, CustomGlint.WAVE,
      new int[]{CustomGlint.PURPLE}, 1.0f, true, 1.0f, true);

  // Auto-apply on craft / fishing / mob drop / loot table (call once during setup)
  // Register in your @Mod constructor — NOT in FMLCommonSetupEvent.
  // FMLCommonSetupEvent fires on a parallel thread pool; these registries are
  // unsynchronized HashMaps and will race. The constructor runs on the main thread.
  CustomGlint.registerCraftGlint(Items.DIAMOND_SWORD, CustomGlint.WAVE, new int[]{CustomGlint.PURPLE});
  CustomGlint.registerFishingGlint(Items.NAME_TAG, CustomGlint.SPARKLE, new int[]{CustomGlint.CYAN});
  CustomGlint.registerMobDropGlint(Items.NETHER_STAR, CustomGlint.PULSE, new int[]{CustomGlint.WHITE});
  CustomGlint.registerLootGlint(
      new ResourceLocation("minecraft", "chests/end_city_treasure"),
      Items.DIAMOND_HORSE_ARMOR, CustomGlint.CRYSTAL, new int[]{CustomGlint.CYAN, CustomGlint.PURPLE});

COLOR CONSTANTS
  CustomGlint.RED, ORANGE, YELLOW, LIME, GREEN, CYAN, LIGHT_BLUE,
  BLUE, PURPLE, MAGENTA, PINK, BROWN, WHITE, LIGHT_GRAY, GRAY, BLACK
  Any other color: CustomGlint.color("FFD700")

DESIGN CONSTANTS
  CustomGlint.VANILLA, ARCS, AURORA, BLOBS, CASCADE, CHECKER, CHEVRON, CORAL,
  CRACKS, CROSSHATCH, CRYSTAL, DEBRIS, DIAMONDS, DUNES, EMBER, FEATHER, FIRE,
  FROST, GLITCH, GLOW, GRID, HALO, HEXAGON, LIGHTNING, MARBLE, MATRIX, MESH,
  MOSAIC, NET, OIL, PETAL, PLASMA, PLATE, PRISM, PULSE, RIPPLE, SAND, SCALES,
  SHEEN, SHIMMER, SILK, SLASH, SMOKE, SOLID, SPARKLE, STARS, STATIC, STRIPES,
  SWIRL, TIDE, TILE, VEIN, WAVE, WEAVE, ZIGZAG


================================================================================
  NBT COMMAND FORMAT
================================================================================

  /give @p <item>{customglint:{layers:[{design:"customglint:textures/glint/wave.png",colors:[I;-65536,-16711936,-16776961],speed:0.5f,interpolate:1b,scale:1.0f,simultaneous:0b}]}} 1

  Tag key = mod ID ("customglint" standalone; your MOD_ID when embedded).
  speed: 1.0 = 20 ticks/color. interpolate: 1b = smooth. simultaneous: 1b = all colors at once.
  Alpha byte of each color int = brightness (0xFF full, 0x00 invisible).

  Remove: /item replace entity @s weapon.mainhand nbt remove customglint


================================================================================
  /glint COMMAND
================================================================================

  /glint apply <design> <colors> [speed] [smooth] [scale] [simultaneous]
                                                    — applies to main-hand item
  /glint remove                                      — removes from main-hand item

  design: any name from the 55 built-in designs (see DESIGN CONSTANTS above),
          or a data-pack design registered as "namespace:name"

  colors: comma-separated color names, quoted when using multiple colors:
          red, orange, yellow, lime, green, cyan, light_blue, blue, purple,
          magenta, pink, brown, white, light_gray, gray, black

  speed:  0.25–8.0 (default 1.0)
  smooth: true/false interpolation (default true)
  scale:  0.25–4.0 pattern zoom (default 1.0)
  simultaneous: true/false — all colors at once vs. cycling (default false)

  Examples:
    /glint apply wave "red,blue,purple"
    /glint apply fire "red,orange,yellow" 1.2 true
    /glint apply crystal "cyan,white" 1.0 true 2.0 true