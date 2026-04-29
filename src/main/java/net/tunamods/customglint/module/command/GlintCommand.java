package net.tunamods.customglint.module.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.tunamods.customglint.common.CustomGlint;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

public class GlintCommand {

    private static final Map<String, ResourceLocation> DESIGNS = new LinkedHashMap<>();
    private static final Map<String, Integer> COLORS = new LinkedHashMap<>();

    static {
        DESIGNS.put("vanilla",    CustomGlint.VANILLA);
        DESIGNS.put("checker",    CustomGlint.CHECKER);
        DESIGNS.put("crosshatch", CustomGlint.CROSSHATCH);
        DESIGNS.put("diamonds",   CustomGlint.DIAMONDS);
        DESIGNS.put("dots",       CustomGlint.DOTS);
        DESIGNS.put("fire",       CustomGlint.FIRE);
        DESIGNS.put("grid",       CustomGlint.GRID);
        DESIGNS.put("hexagon",    CustomGlint.HEXAGON);
        DESIGNS.put("pulse",      CustomGlint.PULSE);
        DESIGNS.put("ripple",     CustomGlint.RIPPLE);
        DESIGNS.put("scales",     CustomGlint.SCALES);
        DESIGNS.put("sparkle",    CustomGlint.SPARKLE);
        DESIGNS.put("stars",      CustomGlint.STARS);
        DESIGNS.put("stripes",    CustomGlint.STRIPES);
        DESIGNS.put("swirl",      CustomGlint.SWIRL);
        DESIGNS.put("wave",       CustomGlint.WAVE);
        DESIGNS.put("zigzag",     CustomGlint.ZIGZAG);

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
            for (String name : DESIGNS.keySet()) {
                if (name.startsWith(remaining)) builder.suggest(name);
            }
            return builder.buildFuture();
        };

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_COLORS =
        (ctx, builder) -> {
            String remaining = builder.getRemaining();
            int lastComma = remaining.lastIndexOf(',');
            String prefix  = lastComma >= 0 ? remaining.substring(0, lastComma + 1) : "";
            String partial = lastComma >= 0 ? remaining.substring(lastComma + 1)    : remaining;
            for (String name : COLORS.keySet()) {
                if (name.startsWith(partial.toLowerCase())) {
                    builder.suggest(prefix + name);
                }
            }
            return builder.buildFuture();
        };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("glint")
            .then(Commands.literal("apply")
                .then(Commands.argument("design", StringArgumentType.word())
                    .suggests(SUGGEST_DESIGNS)
                    .then(Commands.argument("colors", StringArgumentType.word())
                        .suggests(SUGGEST_COLORS)
                        .executes(ctx -> apply(ctx.getSource(),
                            StringArgumentType.getString(ctx, "design"),
                            StringArgumentType.getString(ctx, "colors"),
                            1.0f, true))
                        .then(Commands.argument("speed", FloatArgumentType.floatArg(0.25f, 8.0f))
                            .executes(ctx -> apply(ctx.getSource(),
                                StringArgumentType.getString(ctx, "design"),
                                StringArgumentType.getString(ctx, "colors"),
                                FloatArgumentType.getFloat(ctx, "speed"), true))
                            .then(Commands.argument("smooth", BoolArgumentType.bool())
                                .executes(ctx -> apply(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "design"),
                                    StringArgumentType.getString(ctx, "colors"),
                                    FloatArgumentType.getFloat(ctx, "speed"),
                                    BoolArgumentType.getBool(ctx, "smooth"))))))))
            .then(Commands.literal("remove")
                .executes(ctx -> remove(ctx.getSource()))));
    }

    private static int apply(CommandSourceStack source, String designName, String colorsArg,
                              float speed, boolean smooth) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Must be a player"));
            return 0;
        }

        ResourceLocation design = DESIGNS.get(designName.toLowerCase());
        if (design == null) {
            source.sendFailure(Component.literal(
                "Unknown design '" + designName + "'. Valid: " + String.join(", ", DESIGNS.keySet())));
            return 0;
        }

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

        CustomGlint.write(stack, design, colors, speed, smooth, 1.0f, false);
        source.sendSuccess(() -> Component.literal("Glint applied"), false);
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
}