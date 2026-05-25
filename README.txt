Custom Glints - developer documentation
Minecraft 1.20.1 / Forge 47.x - MIT license (attribution required)
================================================================================

Per-item animated enchantment glint with color, timing, and scale control. Works
on held items, armor, elytra, horse armor, and any LivingEntity. Glints can also
project a colored stencil outline through the "glowing" flag. NBT-driven; 55
built-in designs, extensible via data packs.


================================================================================
  Two artifacts
================================================================================

  custom-glint-api-<ver>.jar   (modid: customglint_api)
      Rendering pipeline + Java API. No wand, recipes, or /glint command.
      Bundle this via jarJar.

  custom_glint-<ver>.jar       (modid: customglint + customglint_api)
      Full standalone download. Adds wand, Glint Trim / Glow Trim / Tear
      items, recipes, /glint command, loot modifiers, JEI integration.
      The api jar is nested inside via META-INF/jarjar/.

Mod developers depend on the api coord. The full jar is the user-facing
Modrinth / CurseForge download.


================================================================================
  Bundling the api (jarJar)
================================================================================

In build.gradle:

    repositories {
        maven {
            name = "TunaMods Custom Glints"
            url = "https://raw.githubusercontent.com/TunaMods/custom_glint/1.20.1/mcmodsrepo"
        }
    }

    dependencies {
        compileOnly fg.deobf("net.tunamods.customglint:custom-glint-api:1.4.0")
        runtimeOnly fg.deobf("net.tunamods.customglint:custom-glint-api:1.4.0")

        jarJar(group: 'net.tunamods.customglint', name: 'custom-glint-api',
               version: '[1.4.0,2.0)') {
            jarJar.ranged(it, '[1.4.0,2.0)')
        }
    }

    jarJar.enable()

Pin both versions to the latest released api when you update. Build with
`./gradlew jarJar`; output at build/libs/yourmod-<version>-all.jar.

If multiple mods bundle the api, Forge dedupes to a single shared copy.

Alternative: declare as a hard / soft dep instead of bundling.

    [[dependencies.yourmodid]]
        modId="customglint_api"
        mandatory=true
        versionRange="[1.4,)"
        ordering="NONE"
        side="BOTH"


================================================================================
  Server safety
================================================================================

  net.tunamods.customglint.common.*         - server-safe (NBT API, registries,
                                              entity events, networking).
  net.tunamods.customglint.common.client.*  - client-only (rendering pipeline).
                                              Never reference from
                                              server-reachable code.

Mixing the two will ClassNotFoundException on a dedicated server.

NBT tag key is always "customglint", whether the api is bundled in your jar
or loaded from the standalone.


================================================================================
  Item API
================================================================================

All on net.tunamods.customglint.common.CustomGlint.

  CustomGlint.write(stack, design, colors, speed, interpolate, scale, simultaneous);
  CustomGlint.write(stack, new CustomGlint.Layer[]{ ... });   // multi-layer
  CustomGlint.write(stack, design, colors);                   // shorthand
  CustomGlint.write(stack, design, color);                    // single-color shorthand

  boolean has = CustomGlint.has(stack);
  CustomGlint.Data data = CustomGlint.read(stack);
  CustomGlint.remove(stack);

  // Pre-glinted ItemStack
  ItemStack s = CustomGlint.glinted(Items.DIAMOND_SWORD, CustomGlint.WAVE,
                                    new int[]{CustomGlint.PURPLE});

  // Glowing outline
  CustomGlint.setGlowing(stack, true);
  CustomGlint.isGlowing(stack);

  // Independent outline colors. When set, the outline cycles through this
  // list instead of tracking glint layer 0.
  CustomGlint.setGlowColors(stack, new int[]{CustomGlint.RED, CustomGlint.WHITE});
  CustomGlint.getGlowColors(stack);
  CustomGlint.hasGlowColors(stack);
  CustomGlint.clearGlowColors(stack);


Auto-apply registries

Register from your @Mod constructor. Do NOT use FMLCommonSetupEvent (parallel
thread pool; the registries are unsynchronized HashMaps).

  CustomGlint.registerCraftGlint(item, design, colors);
  CustomGlint.registerFishingGlint(item, design, colors);
  CustomGlint.registerMobDropGlint(item, design, colors);
  CustomGlint.registerLootGlint(lootTableRL, item, design, colors);

registerLootGlint is wired by the full standalone's loot modifier and is not
present in the bundled api jar.


================================================================================
  Entity API
================================================================================

Glints and glow outlines also apply to LivingEntity instances. The renderer
unions the body model and all overlay layers (armor, capes, saddles) into one
outline ring.

  import net.tunamods.customglint.common.CustomGlint;
  import net.tunamods.customglint.common.entity.EntityGlintEvents;

  // Per-instance writes run server-side. Broadcast after each mutation.
  CustomGlint.writeEntity(living, layers);
  CustomGlint.setEntityGlowColors(living, colors);
  CustomGlint.setEntityGlowing(living, true);
  EntityGlintEvents.broadcast(living);

  CustomGlint.readEntity(living);
  CustomGlint.hasEntity(living);
  CustomGlint.removeEntity(living);
  CustomGlint.clearEntityGlowColors(living);

  // Type-wide auto-apply (every spawn of this EntityType carries the glint)
  CustomGlint.registerEntityGlint(EntityType.ALLAY, CustomGlint.SHIMMER,
      new int[]{CustomGlint.CYAN, CustomGlint.WHITE});

Sync:
  - All mutating calls run server-side. EntityGlintEvents.broadcast(living)
    pushes the current tag to every tracking client.
  - The api jar registers a PlayerEvent.StartTracking listener that re-syncs
    late joiners automatically.
  - State lives on the entity's PersistentData under the "customglint" key.
  - The networking channel (customglint_api:main) ships in the api jar, so
    embedders get entity sync with no extra wiring.

Tag-level helpers for packets, NBT files, and snapshot / restore:

  CompoundTag tag = CustomGlint.entityGlintTag(living);   // or itemGlintTag(stack)
  CustomGlint.writeEntityTag(living, tag);
  CustomGlint.Data layers   = CustomGlint.fromTag(tag);
  boolean          glowing  = CustomGlint.tagGlowing(tag);
  int[]            glowCols = CustomGlint.tagGlowColors(tag);
  CompoundTag      fresh    = CustomGlint.toTag(layers);


================================================================================
  Constants
================================================================================

Colors: CustomGlint.RED, ORANGE, YELLOW, LIME, GREEN, CYAN, LIGHT_BLUE, BLUE,
PURPLE, MAGENTA, PINK, BROWN, WHITE, LIGHT_GRAY, GRAY, BLACK. Custom hex via
CustomGlint.color("FFD700"). The alpha byte is a brightness multiplier
(0xFF full, 0x00 invisible); blend mode is additive.

Designs: 55 ResourceLocation constants on CustomGlint (e.g. WAVE, SPARKLE,
AURORA). Iterate with CustomGlint.PATTERNS.

Iteration arrays: CustomGlint.ALL_COLORS (16), VIBRANT_COLORS (11), PATTERNS (55).


================================================================================
  What the api handles automatically
================================================================================

  - Held items in every context (1st / 3rd person, GUI, ground, item frame).
  - 3D held models with custom BEWLR renderers (tridents and similar).
  - Humanoid armor including modded armor that ships its own ArmorModel
    via ForgeHooksClient.
  - Elytra worn as chestplate.
  - Horse armor (vanilla + modded).
  - Living entities (body + overlay layers as one unioned outline ring).
  - Glowing outline pass on items and all four armor surfaces above.
  - Iris / Oculus shader-pack outline path.
  - Atlas-calibrated pattern scale on non-square block atlases.
  - Texture and RenderType eviction on resource pack reload.

NOT wired automatically: BEWLR renderers that bypass ItemRenderer.getFoilBuffer
and call MultiBufferSource.getBuffer(RenderType) themselves. Drive the pipeline
directly via the hooks below if your mod ships one.


================================================================================
  Advanced rendering hooks
================================================================================

Client-only. Gate any reference with FMLEnvironment / DistExecutor.

  import net.tunamods.customglint.common.client.CustomGlintRenderer;

  int argb = CustomGlintRenderer.outlineColor(stack);

  // RenderType factories (cached, self-register into fixedBuffers)
  RenderType rt  = CustomGlintRenderer.forGlint(glint, layerIdx, frameColor, isItem, colorIdx);
  RenderType rtA = CustomGlintRenderer.forArmorGlint(glint, layerIdx, frameColor, colorIdx);
  RenderType rtH = CustomGlintRenderer.forHorseArmorGlint(glint, layerIdx, frameColor, colorIdx);

  // Two-pass stencil outline for entity / armor models
  CustomGlintRenderer.doModelOutline(poseStack, bufferSource, packedLight,
      model, modelTexture, glint, equipmentSlot);

  // Stencil outline for an item (BEWLR + flat-sprite paths)
  CustomGlintRenderer.doItemOutline(stack, displayContext, poseStack,
      bufferSource, packedLight, overlay);

  // Public ThreadLocals
  CustomGlintRenderer.CURRENT_ITEM_STACK
  CustomGlintRenderer.IN_OUTLINE
  CustomGlintRenderer.COLOR_BUF

  // Optional gate to suppress outlines (true = skip)
  CustomGlintRenderer.outlineSuppressor = () -> shouldSuppress();


================================================================================
  Data-pack designs
================================================================================

Register custom design names at runtime via data packs. Names appear in the
wand editor and /glint apply suggestions on every client; the server syncs the
list on join and /reload.

  data/<namespace>/customglint/designs/<anything>.json:
      ["<your_modid>:mydesign", "<your_modid>:another"]

  Texture: assets/<your_modid>/textures/glint/mydesign.png

  Reference from NBT: <your_modid>:textures/glint/<name>.png


================================================================================
  NBT format
================================================================================

  /give @p <item>{customglint:{layers:[{design:"customglint:textures/glint/wave.png",colors:[I;-65536,-16711936,-16776961],speed:0.5f,interpolate:1b,scale:1.0f,simultaneous:0b}]}} 1

Tag key: "customglint".
speed: 1.0 = 20 ticks / color. interpolate: 1b = smooth. simultaneous: 1b = all
colors at once. Alpha byte of each color int = brightness.

Colors in [I;...] are signed 32-bit ints. Any color with alpha >= 0x80 (i.e.
every full-brightness color) is negative in this form; the leading 0xFF makes
it exceed Integer.MAX_VALUE unsigned. Use the named constants from Java code,
the color names from /glint apply, or a hex-to-signed-int converter.

Add glowing:1b alongside layers for the colored outline:

  /give @p minecraft:diamond_sword{customglint:{glowing:1b,layers:[{design:"customglint:textures/glint/wave.png",colors:[I;-65536],speed:1.0f,interpolate:1b,scale:1.0f,simultaneous:0b}]}} 1

Remove: /item replace entity @s weapon.mainhand nbt remove customglint
