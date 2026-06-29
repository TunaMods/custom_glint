package net.tunamods.customglint.module.command;

import net.tunamods.customglint.module.item.ModItems;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.tunamods.customglint.CustomGlintMod;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.entity.EntityGlintEvents;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class GlintCommand {

    private static final Map<String, Integer> COLORS = new LinkedHashMap<>();

    static {
        COLORS.put("red",        CustomGlint.RED);
        COLORS.put("orange",     CustomGlint.ORANGE);
        COLORS.put("yellow",     CustomGlint.YELLOW);
        COLORS.put("lime",       CustomGlint.LIME);
        COLORS.put("green",      CustomGlint.GREEN);
        COLORS.put("cyan",       CustomGlint.CYAN);
        COLORS.put("light_blue", CustomGlint.LIGHT_BLUE);
        COLORS.put("blue",       CustomGlint.BLUE);
        COLORS.put("purple",     CustomGlint.PURPLE);
        COLORS.put("magenta",    CustomGlint.MAGENTA);
        COLORS.put("pink",       CustomGlint.PINK);
        COLORS.put("brown",      CustomGlint.BROWN);
        COLORS.put("white",      CustomGlint.WHITE);
        COLORS.put("light_gray", CustomGlint.LIGHT_GRAY);
        COLORS.put("gray",       CustomGlint.GRAY);
        COLORS.put("black",      CustomGlint.BLACK);
    }

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_DESIGNS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining().toLowerCase();
            for (String name : GlintTrimItem.PATTERNS) {
                if (name.startsWith(remaining)) builder.suggest(name);
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_COLORS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining();
            boolean quoted = remaining.startsWith("\"");
            String inner   = quoted ? remaining.substring(1) : remaining;
            int lastComma  = inner.lastIndexOf(',');
            String prefix  = lastComma >= 0 ? inner.substring(0, lastComma + 1) : "";
            String partial = lastComma >= 0 ? inner.substring(lastComma + 1)    : inner;
            for (String name : COLORS.keySet()) {
                if (name.startsWith(partial.toLowerCase())) {
                    String value = prefix + name;
                    builder.suggest(quoted ? "\"" + value + "\"" : value);
                }
            }
            return builder.buildFuture();
        };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("glint")
            .requires(s -> s.hasPermission(2))
            .then(Commands.literal("apply")
                .then(Commands.argument("design", StringArgumentType.word())
                    .suggests(SUGGEST_DESIGNS)
                    .then(Commands.argument("colors", StringArgumentType.string())
                        .suggests(SUGGEST_COLORS)
                        .executes(ctx -> apply(ctx.getSource(),
                            StringArgumentType.getString(ctx, "design"),
                            StringArgumentType.getString(ctx, "colors"),
                            1.0f, true, 1.0f, false))
                        .then(Commands.argument("speed", FloatArgumentType.floatArg(0.25f, 8.0f))
                            .executes(ctx -> apply(ctx.getSource(),
                                StringArgumentType.getString(ctx, "design"),
                                StringArgumentType.getString(ctx, "colors"),
                                FloatArgumentType.getFloat(ctx, "speed"), true, 1.0f, false))
                            .then(Commands.argument("smooth", BoolArgumentType.bool())
                                .executes(ctx -> apply(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "design"),
                                    StringArgumentType.getString(ctx, "colors"),
                                    FloatArgumentType.getFloat(ctx, "speed"),
                                    BoolArgumentType.getBool(ctx, "smooth"), 1.0f, false))
                                .then(Commands.argument("scale", FloatArgumentType.floatArg(0.25f, 4.0f))
                                    .executes(ctx -> apply(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "design"),
                                        StringArgumentType.getString(ctx, "colors"),
                                        FloatArgumentType.getFloat(ctx, "speed"),
                                        BoolArgumentType.getBool(ctx, "smooth"),
                                        FloatArgumentType.getFloat(ctx, "scale"), false))
                                    .then(Commands.argument("simultaneous", BoolArgumentType.bool())
                                        .executes(ctx -> apply(ctx.getSource(),
                                            StringArgumentType.getString(ctx, "design"),
                                            StringArgumentType.getString(ctx, "colors"),
                                            FloatArgumentType.getFloat(ctx, "speed"),
                                            BoolArgumentType.getBool(ctx, "smooth"),
                                            FloatArgumentType.getFloat(ctx, "scale"),
                                            BoolArgumentType.getBool(ctx, "simultaneous"))))))))))
            .then(Commands.literal("remove")
                .executes(ctx -> remove(ctx.getSource())))
            .then(Commands.literal("glow")
                .then(Commands.argument("enabled", BoolArgumentType.bool())
                    .executes(ctx -> glow(ctx.getSource(),
                        BoolArgumentType.getBool(ctx, "enabled")))))
            .then(Commands.literal("extract")
                .executes(ctx -> extract(ctx.getSource())))
            .then(Commands.literal("export")
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> export(ctx.getSource(),
                        StringArgumentType.getString(ctx, "name")))))
            .then(Commands.literal("entity")
                .then(Commands.argument("targets", EntityArgument.entities())
                    .then(Commands.literal("apply")
                        .then(Commands.argument("design", StringArgumentType.word())
                            .suggests(SUGGEST_DESIGNS)
                            .then(Commands.argument("colors", StringArgumentType.string())
                                .suggests(SUGGEST_COLORS)
                                .executes(ctx -> applyEntity(ctx.getSource(),
                                    EntityArgument.getEntities(ctx, "targets"),
                                    StringArgumentType.getString(ctx, "design"),
                                    StringArgumentType.getString(ctx, "colors"),
                                    1.0f, true, 1.0f, false, false))
                                .then(Commands.argument("glowing", BoolArgumentType.bool())
                                    .executes(ctx -> applyEntity(ctx.getSource(),
                                        EntityArgument.getEntities(ctx, "targets"),
                                        StringArgumentType.getString(ctx, "design"),
                                        StringArgumentType.getString(ctx, "colors"),
                                        1.0f, true, 1.0f, false,
                                        BoolArgumentType.getBool(ctx, "glowing")))
                                    .then(Commands.argument("speed", FloatArgumentType.floatArg(0.25f, 8.0f))
                                        .executes(ctx -> applyEntity(ctx.getSource(),
                                            EntityArgument.getEntities(ctx, "targets"),
                                            StringArgumentType.getString(ctx, "design"),
                                            StringArgumentType.getString(ctx, "colors"),
                                            FloatArgumentType.getFloat(ctx, "speed"), true, 1.0f, false,
                                            BoolArgumentType.getBool(ctx, "glowing")))
                                        .then(Commands.argument("smooth", BoolArgumentType.bool())
                                            .executes(ctx -> applyEntity(ctx.getSource(),
                                                EntityArgument.getEntities(ctx, "targets"),
                                                StringArgumentType.getString(ctx, "design"),
                                                StringArgumentType.getString(ctx, "colors"),
                                                FloatArgumentType.getFloat(ctx, "speed"),
                                                BoolArgumentType.getBool(ctx, "smooth"), 1.0f, false,
                                                BoolArgumentType.getBool(ctx, "glowing")))
                                            .then(Commands.argument("scale", FloatArgumentType.floatArg(0.25f, 4.0f))
                                                .executes(ctx -> applyEntity(ctx.getSource(),
                                                    EntityArgument.getEntities(ctx, "targets"),
                                                    StringArgumentType.getString(ctx, "design"),
                                                    StringArgumentType.getString(ctx, "colors"),
                                                    FloatArgumentType.getFloat(ctx, "speed"),
                                                    BoolArgumentType.getBool(ctx, "smooth"),
                                                    FloatArgumentType.getFloat(ctx, "scale"), false,
                                                    BoolArgumentType.getBool(ctx, "glowing")))
                                                .then(Commands.argument("simultaneous", BoolArgumentType.bool())
                                                    .executes(ctx -> applyEntity(ctx.getSource(),
                                                        EntityArgument.getEntities(ctx, "targets"),
                                                        StringArgumentType.getString(ctx, "design"),
                                                        StringArgumentType.getString(ctx, "colors"),
                                                        FloatArgumentType.getFloat(ctx, "speed"),
                                                        BoolArgumentType.getBool(ctx, "smooth"),
                                                        FloatArgumentType.getFloat(ctx, "scale"),
                                                        BoolArgumentType.getBool(ctx, "simultaneous"),
                                                        BoolArgumentType.getBool(ctx, "glowing")))))))))))
                    .then(Commands.literal("remove")
                        .executes(ctx -> removeEntity(ctx.getSource(),
                            EntityArgument.getEntities(ctx, "targets"))))
                    .then(Commands.literal("glow")
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                            .executes(ctx -> glowEntity(ctx.getSource(),
                                EntityArgument.getEntities(ctx, "targets"),
                                BoolArgumentType.getBool(ctx, "enabled"))))))));
    }

    private static int applyEntity(CommandSourceStack source, Collection<? extends Entity> targets,
                                   String designName, String colorsArg,
                                   float speed, boolean smooth, float scale, boolean simultaneous,
                                   boolean glowing) {
        String key = designName.toLowerCase();
        if (!GlintTrimItem.PATTERNS.contains(key)) {
            source.sendFailure(Component.literal(
                "Unknown design '" + designName + "'. Valid: " + String.join(", ", GlintTrimItem.PATTERNS)));
            return 0;
        }
        ResourceLocation design = CustomGlint.designFromName(key);

        String[] parts = colorsArg.split(",");
        int[] colors = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String name = parts[i].trim().toLowerCase();
            Integer c = COLORS.get(name);
            if (c == null) {
                source.sendFailure(Component.literal(
                    "Unknown color '" + name + "'. Valid: " + String.join(", ", COLORS.keySet())));
                return 0;
            }
            colors[i] = c;
        }

        CustomGlint.Layer layer = new CustomGlint.Layer(design, colors, speed, smooth, scale, simultaneous);
        CustomGlint.Layer[] layers = CustomGlint.ensureChromaticSeeds(new CustomGlint.Layer[]{ layer });

        int count = 0;
        for (Entity e : targets) {
            if (!(e instanceof LivingEntity le)) continue;
            CustomGlint.writeEntity(le, layers);
            CustomGlint.setEntityGlowing(le, glowing);
            EntityGlintEvents.broadcast(le);
            count++;
        }
        if (count == 0) {
            source.sendFailure(Component.literal("No matching living entities"));
            return 0;
        }
        final int n = count;
        source.sendSuccess(() -> Component.literal("Glint applied to " + n + " entit" + (n == 1 ? "y" : "ies")), false);
        return count;
    }

    private static int removeEntity(CommandSourceStack source, Collection<? extends Entity> targets) {
        int count = 0;
        for (Entity e : targets) {
            if (!(e instanceof LivingEntity le)) continue;
            if (!CustomGlint.hasEntity(le)) continue;
            CustomGlint.removeEntity(le);
            EntityGlintEvents.broadcast(le);
            count++;
        }
        if (count == 0) {
            source.sendFailure(Component.literal("No matching living entities had a glint"));
            return 0;
        }
        final int n = count;
        source.sendSuccess(() -> Component.literal("Glint removed from " + n + " entit" + (n == 1 ? "y" : "ies")), false);
        return count;
    }

    private static int glowEntity(CommandSourceStack source, Collection<? extends Entity> targets, boolean enabled) {
        int count = 0;
        for (Entity e : targets) {
            if (!(e instanceof LivingEntity le)) continue;
            if (enabled && !CustomGlint.hasEntity(le)) continue;
            CustomGlint.setEntityGlowing(le, enabled);
            EntityGlintEvents.broadcast(le);
            count++;
        }
        if (count == 0) {
            source.sendFailure(Component.literal(enabled
                ? "No matching living entities have a glint to glow"
                : "No matching living entities"));
            return 0;
        }
        final int n = count;
        source.sendSuccess(() -> Component.literal((enabled ? "Glowing enabled on " : "Glowing disabled on ") + n + " entit" + (n == 1 ? "y" : "ies")), false);
        return count;
    }

    private static int apply(CommandSourceStack source, String designName, String colorsArg,
                              float speed, boolean smooth, float scale, boolean simultaneous) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be a player"));
            return 0;
        }

        String key = designName.toLowerCase();
        if (!GlintTrimItem.PATTERNS.contains(key)) {
            source.sendFailure(Component.literal(
                "Unknown design '" + designName + "'. Valid: " + String.join(", ", GlintTrimItem.PATTERNS)));
            return 0;
        }
        ResourceLocation design = CustomGlint.designFromName(key);

        String[] parts = colorsArg.split(",");
        int[] colors = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String name = parts[i].trim().toLowerCase();
            Integer c = COLORS.get(name);
            if (c == null) {
                source.sendFailure(Component.literal(
                    "Unknown color '" + name + "'. Valid: " + String.join(", ", COLORS.keySet())));
                return 0;
            }
            colors[i] = c;
        }

        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (stack.isEmpty()) {
            source.sendFailure(Component.literal("Hold an item in your main hand"));
            return 0;
        }

        CustomGlint.Layer layer = new CustomGlint.Layer(design, colors, speed, smooth, scale, simultaneous);
        CustomGlint.write(stack, CustomGlint.ensureChromaticSeeds(new CustomGlint.Layer[]{ layer }));
        source.sendSuccess(() -> Component.literal("Glint applied"), false);
        return 1;
    }

    private static int glow(CommandSourceStack source, boolean enabled) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be a player"));
            return 0;
        }

        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (stack.isEmpty()) {
            source.sendFailure(Component.literal("Hold an item in your main hand"));
            return 0;
        }

        if (enabled && !CustomGlint.has(stack)) {
            source.sendFailure(Component.literal("Item has no custom glint — apply a glint first"));
            return 0;
        }

        CustomGlint.setGlowing(stack, enabled);
        source.sendSuccess(() -> Component.literal(enabled ? "Glowing outline enabled" : "Glowing outline disabled"), false);
        return 1;
    }

    private static int remove(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be a player"));
            return 0;
        }

        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (stack.isEmpty()) {
            source.sendFailure(Component.literal("Hold an item in your main hand"));
            return 0;
        }

        if (!CustomGlint.has(stack)) {
            source.sendFailure(Component.literal("Item has no custom glint"));
            return 0;
        }

        CustomGlint.remove(stack);
        source.sendSuccess(() -> Component.literal("Glint removed"), false);
        return 1;
    }

    private static int extract(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be a player"));
            return 0;
        }

        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (held.isEmpty()) {
            source.sendFailure(Component.literal("Hold an item in your main hand"));
            return 0;
        }

        CustomGlint.Data data = CustomGlint.read(held);
        if (data == null) {
            source.sendFailure(Component.literal("Item has no custom glint"));
            return 0;
        }

        CustomGlint.Layer[] layers = data.layers();
        ItemStack trim = new ItemStack(ModItems.GLINT_TRIM.get());

        if (layers.length == 1) {
            CustomGlint.Layer layer = layers[0];
            for (int color : layer.colors()) GlintTrimItem.addColor(trim, color);
            trim.getOrCreateTag().putFloat(GlintTrimItem.SPEED_TAG, layer.speed());
            trim.getOrCreateTag().putFloat(GlintTrimItem.SCALE_TAG, layer.patternScale());
            GlintTrimItem.setPattern(trim, layer.design());
            GlintTrimItem.setGlowing(trim, CustomGlint.isGlowing(held));
        } else {
            // Multi-layer: set flat tags from layer 0 for display, then copy the full glint tag verbatim
            CustomGlint.Layer layer0 = layers[0];
            for (int color : layer0.colors()) GlintTrimItem.addColor(trim, color);
            trim.getOrCreateTag().putFloat(GlintTrimItem.SPEED_TAG, layer0.speed());
            trim.getOrCreateTag().putFloat(GlintTrimItem.SCALE_TAG, layer0.patternScale());
            trim.getOrCreateTag().putString(GlintTrimItem.PATTERN_TAG, layer0.design().toString());
            if (held.hasTag() && held.getTag().contains(CustomGlintMod.MOD_ID)) {
                trim.getOrCreateTag().put(CustomGlintMod.MOD_ID, held.getTag().get(CustomGlintMod.MOD_ID).copy());
            }
            // Set glowing and CustomModelData without calling setGlowing (which would clobber the multi-layer tag)
            boolean glowing = CustomGlint.isGlowing(held);
            trim.getOrCreateTag().putBoolean(GlintTrimItem.GLOWING_TAG, glowing);
            String name = layer0.design().equals(CustomGlint.VANILLA) ? "vanilla" : GlintTrimItem.extractPatternName(layer0.design());
            int idx = GlintTrimItem.PATTERNS.indexOf(name);
            if (idx >= 0) trim.getOrCreateTag().putInt("CustomModelData", (glowing ? 1000 : 0) + idx + 1);
        }

        if (!player.getInventory().add(trim)) player.drop(trim, false);
        source.sendSuccess(() -> Component.literal("Glint Trim extracted"), false);
        return 1;
    }

    private static int export(CommandSourceStack source, String name) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be a player"));
            return 0;
        }

        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (held.isEmpty()) {
            source.sendFailure(Component.literal("Hold an item in your main hand"));
            return 0;
        }

        CustomGlint.Data data = CustomGlint.read(held);
        if (data == null) {
            source.sendFailure(Component.literal("Item has no custom glint"));
            return 0;
        }

        try {
            Path configDir = Paths.get("config/customglint/trims").toAbsolutePath();
            Files.createDirectories(configDir);

            JsonObject root = new JsonObject();
            root.addProperty("name", name);
            root.addProperty("glowing", CustomGlint.isGlowing(held));

            if (held.hasCustomHoverName()) {
                Component hover = held.getHoverName();
                root.addProperty("displayName", hover.getString());
                TextColor color = hover.getStyle().getColor();
                if (color != null) {
                    root.addProperty("nameColor", String.format("0x%06X", color.getValue() & 0xFFFFFF));
                }
            }

            JsonArray layersArray = new JsonArray();
            for (CustomGlint.Layer layer : data.layers()) {
                JsonObject layerObj = new JsonObject();
                layerObj.addProperty("design", layer.design().toString());

                JsonArray colorsArray = new JsonArray();
                for (int color : layer.colors()) {
                    colorsArray.add(String.format("0x%08X", color));
                }
                layerObj.add("colors", colorsArray);

                layerObj.addProperty("speed", layer.speed());
                layerObj.addProperty("interpolate", layer.interpolate());
                layerObj.addProperty("patternScale", layer.patternScale());
                layerObj.addProperty("simultaneous", layer.simultaneous());

                layersArray.add(layerObj);
            }
            root.add("layers", layersArray);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String json = gson.toJson(root);

            Path file = configDir.resolve(name + ".json");
            try (BufferedWriter writer = Files.newBufferedWriter(file)) {
                writer.write(json);
            }

            source.sendSuccess(() -> Component.literal("Glint trim exported to: " + file.toString()), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Failed to export: " + e.getMessage()));
            return 0;
        }
    }
}