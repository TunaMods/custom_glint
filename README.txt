Custom Glints — Minecraft 1.20.1 / Forge 47.x
MIT License — attribution required
================================================================================

Per-item animated enchantment glint with full color, timing, and scale control.
Works on any item or armor piece. Everything lives in NBT — no registry
changes, no loot table edits, no item subclasses. 55 built-in designs.

For players: drop the standalone jar into any Forge 1.20.1 modpack and grab
the Glint Wand from the Custom Glints creative tab. For server/datapack use,
see the NBT format and /glint command below.

For modders: bundle the embeddable api jar inside your own mod via Forge's
jarJar — your players see one jar, not two. See "BUNDLING" below.


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

  Player distribution: the full jar is published to CurseForge / Modrinth.
  Embedder distribution: the api jar is published to a GitHub raw maven
  (see "BUNDLING" below). Players never need to touch the maven; modders
  never need to touch the Modrinth/CurseForge page.


================================================================================
  BUNDLING THE API IN YOUR MOD (jarJar — recommended)
================================================================================

For modders who want to ship glints with their own mod without forcing players
to install a separate dependency, the supported path is Forge's jarJar against
the api artifact.

What this gives you:

  - Players download ONE jar (yours). They get the rendering pipeline and the
    Java API only — no Wand, no creative tab, no recipes, no commands. Your
    mod is in full control of how glints get applied.
  - Forge auto-extracts the nested api jar and loads it once at launch under
    the modid "customglint_api".
  - Multiple mods can each bundle the api — Forge resolves to a single
    compatible version across all of them. No class collisions, no duplicate
    mixin injections, no manual coordination.
  - If a player also installs the standalone "Custom Glints" (the full jar),
    Forge dedupes the api — both the standalone and your bundled copy share
    one customglint_api instance.

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


================================================================================
  ALTERNATIVE — Declare as a required/optional dependency (no bundling)
================================================================================

If you'd rather not bundle and just have the player install one of the mods
separately, declare it in your META-INF/mods.toml. You can target either the
api modid (lightweight, no player-facing content) or the full modid (so the
player gets the Wand + recipes too):

    [[dependencies.yourmodid]]
        modId="customglint_api"        # or "customglint" if you require the full mod
        mandatory=true                  # false = soft dep, your mod loads either way
        versionRange="[1.3,)"
        ordering="NONE"
        side="BOTH"

  build.gradle uses compileOnly/runtimeOnly the same way as STEP 2 above, but
  without the jarJar block. The player downloads the dependency jar from
  CurseForge / Modrinth themselves.


================================================================================
  ADVANCED — Source copy (NOT recommended for public distribution)
================================================================================

You CAN copy the contents of src/main/java/net/tunamods/customglint/common/
into your own mod under the MIT license — but read this section's warnings
carefully first.

WARNING — multi-mod collision risk

  If two mods ship a source-copied customglint without repackaging, every
  class (CustomGlint, all mixins, all accessors) ends up at the same
  fully-qualified name in two jars. Forge's classloader cannot resolve
  duplicates safely — one copy silently wins, or the mod loader hard-fails.
  The jarJar path above does not have this problem.

  This means source-copy is only safe when you can guarantee no other mod
  in the pack also embeds customglint. For public CurseForge / Modrinth
  releases where you don't control the pack composition, use jarJar.

  Do NOT copy module/compat/ — those are per-mod compat mixins (e.g. Ice
  and Fire) that belong to the standalone mod only. Bundling them inside
  an embedded copy makes you responsible for compat upkeep you didn't
  sign up for.

If you understand the risk and still want to source-copy:

  1. Copy src/main/java/net/tunamods/customglint/common/ into your project,
     updating package declarations and the mixins JSON "package" field.
  2. Wire MOD_ID — CustomGlint.java imports the constant from your main mod
     class. That one field drives ResourceLocation namespaces, render type
     names, and the NBT tag key.
  3. Copy assets/customglint/textures/glint/ → assets/<yourmodid>/textures/glint/
  4. Add the mixin classes to your mixins JSON's "client" array, register
     the config in mods.toml, and copy META-INF/accesstransformer.cfg into
     your project (publicizes TextureAtlas width/height and RenderBuffers
     fixedBuffers).
  5. Optional: omit ElytraLayerMixin / HorseArmorLayerMixin if you don't
     need glint/outline on those surfaces — the core item + humanoid armor
     pipeline works without them.


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

  // Glowing outline (colored border matching glint layer 0)
  CustomGlint.setGlowing(stack, true);   // enable
  CustomGlint.setGlowing(stack, false);  // disable
  boolean g = CustomGlint.isGlowing(stack);

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

  Add glowing:1b alongside layers for the colored outline effect:
  /give @p minecraft:diamond_sword{customglint:{glowing:1b,layers:[{design:"customglint:textures/glint/wave.png",colors:[I;-65536],speed:1.0f,interpolate:1b,scale:1.0f,simultaneous:0b}]}} 1

  Remove: /item replace entity @s weapon.mainhand nbt remove customglint


================================================================================
  /glint COMMAND
================================================================================

  /glint apply <design> <colors> [speed] [smooth] [scale] [simultaneous]
                                                    — applies to main-hand item
  /glint remove                                      — removes from main-hand item
  /glint glow <true|false>                           — enables/disables colored outline on main-hand item

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