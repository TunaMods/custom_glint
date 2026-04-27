custom_glint — Animated Item Glint Mod
Minecraft 1.20.1 / Forge 47.x
======================================

Replaces the vanilla enchantment glint on any item with a custom animated glow.
Pick a pattern, pick your colors, done. Everything lives in NBT so it works on
any item without touching registries or loot tables.

QUICK START
-----------
Apply a glint by giving an item with the custom_glint NBT tag:

  /give @p minecraft:diamond_sword{custom_glint:{design:"examplemod:textures/glint/wave.png",colors:[I;-65536],speed:1.0f,interpolate:1b}} 1

See glint_commands.txt for 60+ ready-to-use commands.

TAG FIELDS
----------
  design      — which pattern to use (see DESIGNS below)
  colors      — one or more ARGB color ints (see COLORS below)
  speed       — 0.5 = slow, 1.0 = normal, 2.0 = fast  (default: 1.0)
  interpolate — 1b = smooth lerp between colors, 0b = hard switch  (default: 1b)

DESIGNS
-------
  examplemod:textures/glint/wave.png
  examplemod:textures/glint/sparkle.png
  examplemod:textures/glint/fire.png
  examplemod:textures/glint/pulse.png
  examplemod:textures/glint/stripes.png
  examplemod:textures/glint/grid.png
  examplemod:textures/glint/scales.png
  examplemod:textures/glint/diamonds.png

  To add a new design, drop a .png into:
    src/main/resources/assets/examplemod/textures/glint/

COLORS
------
Colors are signed 32-bit ints. Formula: 0xRRGGBB - 0x1000000

  Red     -65536      Green   -16711936    Blue    -16776961
  Yellow  -256        Cyan    -16711681    Magenta -65281
  White   -1          Gold    -10496       Orange  -32768
  Purple  -8388353    Pink    -38476       Black   -16777216

Multiple colors = animated cycle. Single color = static.

