Custom Glints — Developer Documentation
Minecraft 1.20.1 / Forge 47.x — MIT License (attribution required)
================================================================================

This repository ships the developer-facing build of Custom Glints. If you're
a player looking to install the mod, go to Modrinth or CurseForge — the
standalone jar there is the only thing you need.

If you're a mod developer, this README covers:

  - The two published artifacts (api vs full) and which one to depend on
  - The jarJar bundling path against the GitHub raw maven
  - The CustomGlint Java API: write/read/remove, auto-apply registries,
    glowing outlines, color and design constants
  - The NBT format the rendering pipeline reads (useful for /give in your
    own commands or datapack functions)

Per-item animated enchantment glint with full color, timing, and scale
control. Works on any item, armor piece, elytra, or horse armor. Glints
can also project a colored outline ("glowing" flag). Everything lives in
NBT — no registry changes, no loot table edits, no item subclasses. 55
built-in designs, extensible via data packs.


================================================================================
  TWO ARTIFACTS — api vs full
================================================================================

  custom-glint-api-<ver>.jar
      The rendering pipeline and Java API only. No Glint Wand, no creative
      tab, no recipes, no /glint command. This is what modders bundle via
      jarJar. Loaded mod ID: customglint_api.

  custom_glint-<ver>.jar
      The full standalone download. Includes everything in the api plus the
      Wand, creative tab, Glint Trim / Tear items, smithing + crafting
      recipes, /glint command, loot modifiers, and JEI integration. The
      api jar is nested inside this jar via META-INF/jarjar/, so a player
      who installs ONLY the full jar gets both mods loaded automatically.
      Loaded mod IDs: customglint + customglint_api.

  The api jar is what you depend on. The full jar is for end-users on
  Modrinth / CurseForge — you should not bundle it, and you don't need to
  visit its page to integrate.


================================================================================
  BUNDLING THE API IN YOUR MOD (jarJar — recommended)
================================================================================

The supported integration path is Forge's jarJar against the api artifact.

What this gives you:

  - One jar to ship — your mod jar, with the api nested at
    META-INF/jarjar/. Your end users install only your jar.
  - Forge auto-extracts the nested api at launch and loads it once under
    the modid "customglint_api". No Wand, no creative tab, no recipes, no
    commands surface to the player — your mod is in full control of how
    glints get applied.
  - Multiple mods can each bundle the api — Forge resolves to a single
    compatible version across all of them. No class collisions, no
    duplicate mixin injections, no manual coordination.
  - If a user also installs the standalone Custom Glints (the full jar),
    Forge dedupes the api — both the standalone and your bundled copy
    share one customglint_api instance.

STEP 1 — Add the GitHub raw maven repo

  In your build.gradle, alongside your other repositories:

    repositories {
        maven {
            name = "TunaMods Custom Glints"
            url = "https://raw.githubusercontent.com/TunaMods/custom_glint/1.20.1/mcmodsrepo"
        }
    }

  This serves the api jar directly from the repository — no auth, no
  external maven host. Versions track the 1.20.1 branch.

STEP 2 — Declare the jarJar dependency

    dependencies {
        // Compile-time API access (deobf-resolved for dev).
        compileOnly fg.deobf("net.tunamods.customglint:custom-glint-api:1.3.0")
        runtimeOnly fg.deobf("net.tunamods.customglint:custom-glint-api:1.3.0")

        // Nest the api jar inside your mod jar at build time. The version
        // range tells Forge which versions are acceptable when resolving
        // against other mods that also bundle the api.
        jarJar(group: 'net.tunamods.customglint', name: 'custom-glint-api',
               version: '[1.3.0,2.0)') {
            jarJar.ranged(it, '[1.3.0,2.0)')
        }
    }

  And ensure jarJar is enabled (add near the top of build.gradle if absent):

    jarJar.enable()

  Your build's reobf'd jar will now contain custom-glint-api-<ver>.jar
  nested under META-INF/jarjar/. Build it with `./gradlew jarJar` — the
  output is yourmod-<version>-all.jar in build/libs/.

STEP 3 — Use the API

  In your @Mod constructor or wherever you set up items:

    import net.tunamods.customglint.common.CustomGlint;

    CustomGlint.registerCraftGlint(MyItems.MAGIC_SWORD.get(),
        CustomGlint.WAVE, new int[]{CustomGlint.PURPLE, CustomGlint.CYAN});

  All public methods documented under JAVA API below work identically
  whether the api is bundled inside your jar or loaded from the standalone
  "Custom Glints" mod — same class, same package, same NBT tag key
  ("customglint").

  Server safety: everything under net.tunamods.customglint.common is safe
  to reference from server-reachable code (recipes, items, commands,
  networking). The rendering pipeline lives in
  net.tunamods.customglint.common.client.CustomGlintRenderer and must
  only be referenced from client-only code paths. Mixing the two will
  ClassNotFoundException on a dedicated server.


================================================================================
  ALTERNATIVE — Declare as a required/optional dependency (no bundling)
================================================================================

If you'd rather not bundle and just have the user install Custom Glints
separately, declare it in your META-INF/mods.toml. Target either the api
modid (lightweight, no in-game content) or the full modid (so the user
gets the Wand + recipes too):

    [[dependencies.yourmodid]]
        modId="customglint_api"        # or "customglint" if you require the full mod
        mandatory=true                  # false = soft dep, your mod loads either way
        versionRange="[1.3,)"
        ordering="NONE"
        side="BOTH"

  build.gradle uses compileOnly/runtimeOnly the same way as STEP 2 above,
  but without the jarJar block. The user installs the dependency jar from
  CurseForge / Modrinth themselves.


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

  // Glowing outline (colored border)
  CustomGlint.setGlowing(stack, true);   // enable
  CustomGlint.setGlowing(stack, false);  // disable
  boolean g = CustomGlint.isGlowing(stack);

  // Independent outline colors. When set, the outline animates through this color
  // list instead of tracking glint layer 0. Useful if you want a glow without any
  // glint pattern, or a glow whose color differs from the glint.
  CustomGlint.setGlowColors(stack, new int[]{CustomGlint.RED, CustomGlint.WHITE});
  int[]   gc      = CustomGlint.getGlowColors(stack);
  boolean hasGc   = CustomGlint.hasGlowColors(stack);
  CustomGlint.clearGlowColors(stack);    // outline falls back to glint layer 0

  // Pre-glinted ItemStack in one call (useful in creative tab displayItems)
  ItemStack stack = CustomGlint.glinted(Items.DIAMOND_SWORD, CustomGlint.WAVE,
      new int[]{CustomGlint.PURPLE}, 1.0f, true, 1.0f, true);

  // Short-form overloads — speed=1.0, interpolate=true, scale=1.0, simultaneous=false:
  CustomGlint.write(stack, CustomGlint.WAVE, new int[]{CustomGlint.RED});
  CustomGlint.write(stack, CustomGlint.WAVE, CustomGlint.RED);         // single color
  CustomGlint.glinted(Items.DIAMOND_SWORD, CustomGlint.WAVE, new int[]{CustomGlint.RED});
  CustomGlint.glinted(Items.DIAMOND_SWORD, CustomGlint.WAVE, CustomGlint.RED);
  CustomGlint.registerCraftGlint(Items.DIAMOND_SWORD, CustomGlint.WAVE, new int[]{CustomGlint.PURPLE});
  // (registerFishingGlint / registerMobDropGlint / registerLootGlint all have matching short forms)

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

  Iteration arrays (public final int[] / ResourceLocation[]):
    CustomGlint.ALL_COLORS       — all 16 dye-named colors above, in order
    CustomGlint.VIBRANT_COLORS   — the 11 vivid ones (drops neutrals/browns)
    CustomGlint.PATTERNS         — all 55 built-in designs as ResourceLocation[]

DESIGN CONSTANTS
  CustomGlint.VANILLA, ARCS, AURORA, BLOBS, CASCADE, CHECKER, CHEVRON, CORAL,
  CRACKS, CROSSHATCH, CRYSTAL, DEBRIS, DIAMONDS, DUNES, EMBER, FEATHER, FIRE,
  FROST, GLITCH, GLOW, GRID, HALO, HEXAGON, LIGHTNING, MARBLE, MATRIX, MESH,
  MOSAIC, NET, OIL, PETAL, PLASMA, PLATE, PRISM, PULSE, RIPPLE, SAND, SCALES,
  SHEEN, SHIMMER, SILK, SLASH, SMOKE, SOLID, SPARKLE, STARS, STATIC, STRIPES,
  SWIRL, TIDE, TILE, VEIN, WAVE, WEAVE, ZIGZAG

  VANILLA resolves to minecraft:textures/misc/enchanted_glint_item.png — the
  other 54 ship inside the api jar at customglint:textures/glint/<name>.png.


================================================================================
  WHAT THE API HANDLES AUTOMATICALLY
================================================================================

  - Held items (1st/3rd person, GUI, ground, item frame, glow frame).
  - 3D held models with custom BEWLR renderers (tridents, etc.) — outline
    and glint both work without per-item adapter code.
  - Humanoid armor — including modded armor that ships its own ArmorModel
    via ForgeHooksClient. No per-mod compat needed.
  - Elytra (when worn as chestplate).
  - Horse armor (iron / gold / diamond / modded).
  - Glowing outline pass — colored stencil border. Tracks layer 0's animated
    color by default, or an independent setGlowColors() list when set. Works
    on items AND on all four armor surfaces above.
  - Atlas-calibrated pattern scale — designs hold aspect ratio when other
    mods inflate the block atlas to non-square dimensions.
  - Texture and RenderType eviction on resource pack reload.

  One known render path the api does NOT wire automatically: BEWLR
  renderers that bypass ItemRenderer.getFoilBuffer entirely (call
  MultiBufferSource.getBuffer(RenderType) themselves). The standalone full
  jar ships compat mixins for several such cases (Ice and Fire troll
  weapons + death worm gauntlets, Sophisticated Backpacks, Epic Knights
  armor decorations, IaF mount armor); those mixins are NOT in the api
  jar. If your mod ships a BEWLR like this, apply glint manually via the
  advanced hooks below.


================================================================================
  ADVANCED — RENDERING HOOKS (for custom renderers)
================================================================================

If you render an item or model outside the normal ItemRenderer / armor
layer flow, you can drive the glint pipeline directly. All hooks live on
CustomGlintRenderer (client-only — gate any reference with FMLEnvironment
or DistExecutor; never touch this class from server-reachable code).

  import net.tunamods.customglint.common.client.CustomGlintRenderer;

  // Per-frame animated color for an item's outline (ARGB int). Prefers the
  // independent glowColors list, falls back to glint layer 0, else white.
  int argb = CustomGlintRenderer.outlineColor(stack);

  // RenderType factories — register lazily; cached by (glint, layer, color, isItem).
  // All four factories also self-register into RenderBuffers.fixedBuffers.
  RenderType rt  = CustomGlintRenderer.forGlint(glint, layerIdx, frameColor, isItem, colorIdx);
  RenderType rtA = CustomGlintRenderer.forArmorGlint(glint, layerIdx, frameColor, colorIdx);
  RenderType rtH = CustomGlintRenderer.forHorseArmorGlint(glint, layerIdx, frameColor, colorIdx);

  // Stencil-based colored outline for an entity/armor model. Two-pass
  // (stencil write + scaled re-render). Pivot/scale tuned for humanoid
  // and horse models.
  CustomGlintRenderer.doModelOutline(poseStack, bufferSource, packedLight,
      model, modelTexture, glint, equipmentSlot);

  // Stencil-based outline for an item. Handles BEWLR + flat-sprite paths.
  // Skips ItemDisplayContext.GUI internally.
  CustomGlintRenderer.doItemOutline(stack, displayContext, poseStack,
      bufferSource, packedLight, overlay);

  // Public ThreadLocals — useful if you proxy the item render call chain
  // yourself and need glint application to see the stack you're drawing.
  CustomGlintRenderer.CURRENT_ITEM_STACK   // set during ItemRenderer.render
  CustomGlintRenderer.IN_OUTLINE           // re-entrance guard during outline passes
  CustomGlintRenderer.COLOR_BUF            // shared float[4] for color packing

  // Optional gate: install a BooleanSupplier to suppress doModelOutline /
  // doItemOutline (returns true → skip). Used by the bundled First-Person
  // Model compat to silence outlines in the 3.5D body view.
  CustomGlintRenderer.outlineSuppressor = () -> shouldSuppress();


================================================================================
  DATA-PACK DESIGNS
================================================================================

Designs are extensible at runtime via data packs — useful if you want to
ship custom glint textures with your mod's resource pack without adding
constants to CustomGlint.

  1. Put the PNG at:
       assets/<your_modid>/textures/glint/mydesign.png

  2. In a data pack file under data/<any_namespace>/customglint/designs/
     <anything>.json, list the design names as a JSON array:

       ["<your_modid>:mydesign", "<your_modid>:another"]

  Names appear in the wand editor and /glint apply suggestions on every
  client. The server fires a GlintDesignSyncPacket on reload and on player
  join, so clients with a different design set than the server still see
  the server's list while connected.

  Designs referenced from NBT use the same path the constants use:
       customglint:textures/glint/<name>.png   (or your_modid namespace)


================================================================================
  NBT COMMAND FORMAT
================================================================================

  /give @p <item>{customglint:{layers:[{design:"customglint:textures/glint/wave.png",colors:[I;-65536,-16711936,-16776961],speed:0.5f,interpolate:1b,scale:1.0f,simultaneous:0b}]}} 1

  Tag key = "customglint" (always — same compiled api jar whether installed standalone or
  nested via jarJar inside your mod).
  speed: 1.0 = 20 ticks/color. interpolate: 1b = smooth. simultaneous: 1b = all colors at once.
  Alpha byte of each color int = brightness (0xFF full, 0x00 invisible).

  Add glowing:1b alongside layers for the colored outline effect:
  /give @p minecraft:diamond_sword{customglint:{glowing:1b,layers:[{design:"customglint:textures/glint/wave.png",colors:[I;-65536],speed:1.0f,interpolate:1b,scale:1.0f,simultaneous:0b}]}} 1

  Remove: /item replace entity @s weapon.mainhand nbt remove customglint