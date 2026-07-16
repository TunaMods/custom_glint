package net.tunamods.customglint.module.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.tunamods.customglint.common.client.CustomGlintRenderer;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * Tells a Photon user why their multi-layer glints render as one layer.
 *
 * <p>Photon's shaders.properties carries {@code blend.gbuffers_armor_glint = off}, and its
 * {@code USE_SEPARATE_ENTITY_DRAWS} option ships commented out, so the {@code #else} branch re-asserts it. With
 * blending off, each layer draw REPLACES the pixel instead of adding to it and only the last one survives.
 * Turning the option on gives the glint {@code blend.gbuffers_armor_glint.colortex13 = ONE ONE ZERO ONE}, which
 * is additive, and every layer comes back.
 *
 * <p>Nothing here changes the setting. Iris can be driven to (its option queue is public), but it persists the
 * result into the user's own {@code shaderpacks/<pack>.txt} where it outlives this mod, and the option governs
 * how Photon draws every entity rather than anything to do with glints. That is the pack author's default to
 * make, so this only reports it.
 *
 * <p>Standalone only, deliberately: a mod that bundles the api jar should not get to write in its users' chat.
 */
public final class PhotonGlintNotice {
    private PhotonGlintNotice() {}

    private static final String OPTION = "USE_SEPARATE_ENTITY_DRAWS";

    /** The ShaderPack instance behind the last check. Iris builds a NEW one on every pipeline reload, so identity
     *  is what tells us a pack was loaded, swapped or reloaded. Watching it covers an in-game shader reload, which
     *  a resource-reload listener does not see: Iris reloads its own pipeline without touching MC's resources. */
    private static Object lastPack = null;
    private static boolean checked = false;

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(PhotonGlintNotice::onClientTick);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) { // between worlds: re-arm so the next session gets told again
            checked = false;
            lastPack = null;
            return;
        }
        Object pack = currentPack();
        if (checked && pack == lastPack) return;
        lastPack = pack;
        checked = true;
        notifyIfLimited(mc);
    }

    private static void notifyIfLimited(Minecraft mc) {
        String pack = CustomGlintRenderer.currentPackName();
        if (pack == null || !pack.toLowerCase(Locale.ROOT).contains("photon")) return;
        if (separateEntityDrawsOn(pack)) return;

        mc.player.displayClientMessage(Component.literal("[Glint & Glamour] ")
                .withStyle(ChatFormatting.AQUA)
                .append(Component.literal("Photon draws glints with blending off, so only the last layer of a "
                                + "multi-layer glint shows. Turn on \"Use Separate Entity Draws\" in Photon's "
                                + "shader options (Misc) to get them all back.")
                        .withStyle(ChatFormatting.GRAY)), false);
    }

    /** Iris writes each pack's options to shaderpacks/&lt;packname&gt;.txt as plain Properties, and writes nothing at
     *  all while a pack sits on its defaults. So an absent or unreadable file both mean defaults, and Photon's
     *  default for this option is off.
     *
     *  <p>Read the file rather than Iris's parsed option state: ShaderPack keeps its ShaderPackOptions private,
     *  so reaching the live value takes three levels of reflection into internals that carry no compatibility
     *  promise, where a missing file means "defaults" for as long as the format holds. */
    private static boolean separateEntityDrawsOn(String pack) {
        Path cfg = Minecraft.getInstance().gameDirectory.toPath().resolve("shaderpacks").resolve(pack + ".txt");
        if (!Files.isRegularFile(cfg)) return false;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(cfg)) {
            p.load(in);
        } catch (Throwable t) {
            return false;
        }
        return Boolean.parseBoolean(p.getProperty(OPTION, "false"));
    }

    private static volatile boolean PACK_LOOKUP_DONE = false;
    private static volatile Method IRIS_GET_CURRENT_PACK = null;

    /** The live ShaderPack, or null with no shader mod / no pack. Identity only; nothing here reads it. */
    private static Object currentPack() {
        if (!PACK_LOOKUP_DONE) {
            try {
                IRIS_GET_CURRENT_PACK = Class.forName("net.irisshaders.iris.Iris").getMethod("getCurrentPack");
            } catch (Throwable ignored) {
                IRIS_GET_CURRENT_PACK = null;
            }
            PACK_LOOKUP_DONE = true;
        }
        if (IRIS_GET_CURRENT_PACK == null) return null;
        try {
            Object o = IRIS_GET_CURRENT_PACK.invoke(null);
            return o instanceof Optional<?> opt ? opt.orElse(null) : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
