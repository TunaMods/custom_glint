package com.example.examplemod.glint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * Reads and writes the {@code custom_glint} compound NBT tag on an {@link ItemStack}.
 *
 * <p>Tag structure (stored as a compound under the key {@code "custom_glint"} on the item's root tag):
 * <pre>
 * custom_glint: {
 *   design:      "examplemod:textures/glint/wave.png"  // ResourceLocation string
 *   colors:      [I; -65536, -16711936]                 // packed ARGB int array; alpha ignored at render time
 *   speed:       0.5f                                   // animation speed (optional, default 1.0)
 *   interpolate: 1b                                     // lerp between colors (optional, default true)
 * }
 * </pre>
 *
 * <p>Example give command:
 * <pre>
 * /give @p minecraft:diamond_sword{custom_glint:{design:"examplemod:textures/glint/wave.png",colors:[I;-65536,-16711936],speed:0.5f,interpolate:1b}}
 * </pre>
 */
public final class CustomGlintNbt {

    /** Root key under the item's tag where the glint compound is stored. */
    private static final String TAG = "custom_glint";
    private static final String DESIGN_KEY = "design";
    private static final String COLORS_KEY = "colors";
    private static final String SPEED_KEY = "speed";
    private static final String INTERPOLATE_KEY = "interpolate";

    private CustomGlintNbt() {}

    /**
     * Reads glint config from the item's NBT. Returns {@code null} if the tag is absent,
     * the design is missing/empty, or the colors array is absent/empty.
     *
     * <p>Called every render frame by {@code ItemRendererMixin.applyGlint()}, so it must be fast.
     * All validation happens here so the rest of the render path can assume non-null == valid.
     */
    @Nullable
    public static CustomGlintData read(ItemStack stack) {
        if (!stack.hasTag()) return null;
        CompoundTag root = stack.getTag();
        if (!root.contains(TAG)) return null;
        CompoundTag tag = root.getCompound(TAG);

        String design = tag.getString(DESIGN_KEY);
        if (design.isEmpty()) return null;

        if (!tag.contains(COLORS_KEY)) return null;
        int[] colors = tag.getIntArray(COLORS_KEY);
        if (colors.length == 0) return null;

        // Default speed to 1.0; clamp ≤0 to prevent divide-by-zero in animation math.
        float speed = tag.contains(SPEED_KEY) ? tag.getFloat(SPEED_KEY) : 1.0f;
        if (speed <= 0) speed = 1.0f;

        // Interpolate defaults to true when the key is absent.
        boolean interpolate = !tag.contains(INTERPOLATE_KEY) || tag.getBoolean(INTERPOLATE_KEY);

        return new CustomGlintData(new ResourceLocation(design), colors, speed, interpolate);
    }

    /**
     * Returns {@code true} if this stack has the {@code custom_glint} compound tag.
     * Cheaper than {@link #read} — use this when you only need presence, not the data.
     */
    public static boolean has(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains(TAG);
    }

    /**
     * Writes glint config onto the stack, creating the root tag if needed.
     * Overwrites any previously stored {@code custom_glint} data.
     */
    public static void write(ItemStack stack, ResourceLocation design, int[] colors, float speed, boolean interpolate) {
        CompoundTag tag = new CompoundTag();
        tag.putString(DESIGN_KEY, design.toString());
        tag.putIntArray(COLORS_KEY, colors);
        tag.putFloat(SPEED_KEY, speed);
        tag.putBoolean(INTERPOLATE_KEY, interpolate);
        stack.getOrCreateTag().put(TAG, tag);
    }

    /** Removes the {@code custom_glint} tag from the stack (no-op if absent). */
    public static void remove(ItemStack stack) {
        if (stack.hasTag()) stack.getTag().remove(TAG);
    }
}
