# Changelog

## [1.3.0] — 2026-05-13

### New features

- **Glowing Trim** — surround a colored GlintTrim with 8 Glowstone Dust in a crafting grid to produce a Glowing Glint Trim. The glowing flag propagates through smithing so glinted items inherit the effect; the Black Tear strips it when resetting a trim.
- **Item and armor outline effect** — glowing items render a colored outline pass after the normal render. Armor pieces (including elytra and horse armor) call `doModelOutline()`; held/displayed items call `doItemOutline()`. The `IN_OUTLINE` re-entrance guard prevents the glint pass from firing during the stencil pass.
- **Elytra glint** — `ElytraLayerMixin` injects at `RETURN` of `ElytraLayer.render` and applies custom glint to elytra worn in the chest slot using `forArmorGlint()` (correct offset for `armorCutoutNoCull`).
- **Horse armor glint** — `HorseArmorLayerMixin` injects at `RETURN` of `HorseArmorLayer.render` and draws custom glint on horse armor via the new `forHorseArmorGlint()` factory. Horse armor uses `entityCutoutNoCull` (no polygon offset), so this factory uses `EQUAL_DEPTH_TEST + NO_LAYERING` rather than `EQUAL + VIEW_OFFSET_Z_LAYERING`.
- **Client design sync** — new `GlintDesignSyncPacket` (S→C, channel ID 1) sends the server's data-pack registered design names to clients on first join and after every `/reload`. Fixes editor picker and command tab-completion in multiplayer when data-pack designs are present.
- **Texture reload wired** — `clearTextures()` now fires automatically on client resource reload via `RegisterClientReloadListenersEvent`, fixing stale cached textures after a resource pack change. This resolves the previously documented known gap.
- **`CustomGlint.PATTERNS[]`** — new public `ResourceLocation[]` array enumerating all 55 built-in design constants in alphabetical order, for use by embedding mods.
- **`CustomGlint.VIBRANT_COLORS[]`** — new public `int[]` of the 11 vivid color presets (red through pink) for convenience.
- **GlintGlowTrimRecipe JEI display** — three example Glowing Trim recipes now appear in JEI (wave/red, sparkle/blue, aurora/gold).

### Changes

- **Trim item model simplified** — `glint_trim.json` now uses a single base texture (`customglint:item/glint_trim`) instead of per-pattern model variants. All `trim/*.json` per-pattern model files removed. CustomModelData values shift to 1000-range for the glowing variant (`glint_trim_glow.json` / `glow_glint_trim.png`).
- **3D model glint scale** — `forGlint()` scale for 3D entity models (trident, etc.) changed from `0.16` to `1.0`. The original 0.16 tiled too infrequently for custom designs; 1.0 gives readable pattern detail at entity scale.
- **`CURRENT_ITEM_STACK` ThreadLocal moved** — from a private field in `ItemRendererMixin` to `CustomGlint.CURRENT_ITEM_STACK` (public) so it can be read by outline rendering code without cross-mixin coupling.
- **`ArmorItem` instanceof guard** — `HumanoidArmorLayerMixin` now skips stacks whose item is not an `ArmorItem`, preventing double-rendering on chest slots that hold elytra (handled by `ElytraLayerMixin`).
- **Old `trim_pattern.*` lang keys removed** — these belonged to the vanilla armor trim system and were never used by Custom Glints.
- **JEI namespace:name fix** — `CustomGlintJeiPlugin` now correctly resolves `namespace:name` format patterns to their full texture RL (`namespace:textures/glint/name.png`) when building ingredient and output stacks.

### Bug fixes

- `GlintApplyPacket` decoder now clamps `speed <= 0` to `1.0`, matching the clamp already applied in `CustomGlint.read()`.
- `GlintBlackTearRecipe` now also strips the `glowing` tag when resetting a trim, so a reset trim cannot carry a stale glowing flag.
- `GlintTrimSmithingRecipe` propagates the `glowing` flag from the template trim to the output item stack.

---

## [1.2.0] — 2025-11-XX

35 new designs, JEI integration, blank trim duplicate recipe, speed/scale recipes, data-pack design support.

## [1.1.0] — 2025-XX-XX

Multi-layer glint, Layer Tear item, simultaneous flag, armor glint.

## [1.0.0] — 2025-XX-XX

Initial release: Glint Wand, single-layer animated glint, color/speed/interpolate control.
