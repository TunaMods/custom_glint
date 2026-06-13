# Custom Glints

Per-item animated enchantment glints for Minecraft, with full control over color, design, timing, and scale. NBT-driven, so no registry changes or item subclasses are needed. 55 built-in design textures.

Download from [Modrinth](https://modrinth.com/mod/custom-glints).

## Pick your version

The code lives on a branch per game version. Pick the one that matches your pack:

| Minecraft | Loader | Branch |
|---|---|---|
| 1.20.1 | Forge 47.x | [`1.20.1`](https://github.com/TunaMods/custom_glint/tree/1.20.1) |
| 1.21.1 | NeoForge | [`1.21.1`](https://github.com/TunaMods/custom_glint/tree/1.21.1) |

Each version branch has its own `README.txt` with the developer docs (jarJar bundling, the API surface, render hooks) and its own `changelog.txt`.

## Quick look — latest changes

Highlights from the current release on each branch. Full history is in the linked changelog.

### 1.20.1 (Forge) — 1.5.0

Full changelog: [changelog-1.20.1.txt](changelog-1.20.1.txt)

**Added**
- The wand now carries the glint you are editing. It updates live when you import a saved trim or change the preview item.
- Saved trim files remember a custom display name and its color. `/glint export` writes them; the wand's Import picker restores them.

**Compat**
- Epic Knights: armor decoration glints no longer disappear after toggling a shader pack off.
- Ice and Fire: glowing dragon, hippogryph, and hippocampus armor no longer paints the mount's body glint across the whole creature, and the glow outline stops bleeding through transparent wings, feathers, and fins.
- Ice and Fire: the glow outline on troll weapons and death worm gauntlets traces the weapon instead of filling the whole item.

**Fixed**
- Glowing modded shields and other 3D-model items trace the item's real shape instead of covering the whole item, both in hand and in the inventory slot.
- Held item glow outlines no longer shift or change shape as you turn the camera.

### 1.21.1 (NeoForge) — 1.5.0

Full changelog: [changelog-1.21.1.txt](changelog-1.21.1.txt)

Ported to NeoForge 1.21.1.

**Added**
- The wand carries the glint you are editing, updating live on import or preview-item change.
- Saved trim files remember a custom display name and its color.

**Compat**
- Ice and Fire (Community Edition): player armor (copper, deathworm, dragonscale, sea serpent, troll, and the rest) now shows glints and glow outlines. CE routes this armor through its own renderer that bypassed the usual armor path.
- Ice and Fire (Community Edition): dragon, hippocampus, and hippogryph armor glints stay on the armor instead of bleeding across the creature's body, and glowing mount armor no longer leaks the back-side glow ring through transparent wings, feathers, and fins.
- Ice and Fire (Community Edition): troll weapons and the tide trident each get an outline matching their own shape, in hand and in the inventory, instead of a shared block-shaped glow.
- Epic Knights: armor decoration glints no longer disappear after toggling a shader pack off.

**Fixed**
- Glowing modded shields and other 3D-model items trace the item's real shape instead of covering the whole item.
- Held item glow outlines no longer shift or change shape as you turn the camera.

## License

MIT, attribution required. See the `LICENSE.txt` on either version branch.
