package net.tunamods.customglint.module.blueprint;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;
import net.tunamods.customglint.module.ModConfigPaths;
import net.tunamods.customglint.module.network.GlintServerBlueprintsSyncPacket;
import net.tunamods.customglint.module.network.ModNetworking;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Server-side store for the shared blueprint trims that live in the server machine's
 * {@code config/glint-and-glamour/trims/*.json}. On a dedicated server these are the admin-curated build targets
 * synced to clients; in single-player the integrated server reads the very same directory the client would.
 * The Glint Table and the wand editor both read/write through here, and {@link #syncTo} pushes the current
 * set back to a player over {@link GlintServerBlueprintsSyncPacket}. All I/O is best-effort: a locked or
 * unreadable file is skipped rather than failing the whole operation.
 */
public final class ServerBlueprints {
    private ServerBlueprints() {}

    /** Cap on stored blueprints so an un-gated save can't fill the disk with files. */
    public static final int MAX_BLUEPRINTS = 1024;

    public static Path dir() {
        return ModConfigPaths.TRIMS_DIR;
    }

    /** All blueprints as name → raw JSON, sorted by name. Never null. */
    public static Map<String, String> readAll() {
        Map<String, String> out = new LinkedHashMap<>();
        Path dir = dir();
        if (!Files.exists(dir)) return out;
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                  .sorted()
                  .forEach(p -> {
                      try {
                          out.put(p.getFileName().toString().replace(".json", ""), Files.readString(p));
                      } catch (Exception ignored) {
                          // Unreadable file: skip it rather than fail the whole read.
                      }
                  });
        } catch (Exception ignored) {
            // No dir / unreadable: return whatever we have.
        }
        return out;
    }

    public static int count() {
        return readAll().size();
    }

    /** Reject anything but a bare file name so a crafted packet can't escape the trims dir. */
    public static boolean safeName(String name) {
        return name != null && !name.isEmpty()
                && !name.contains("/") && !name.contains("\\") && !name.contains("..");
    }

    /** Write {@code json} to a unique {@code <base>.json} ({@code base}, {@code base_2}, …). Returns the final
     *  name, or null if writing failed. {@code baseName} is sanitized to a filesystem-safe slug first. */
    public static String saveUnique(String baseName, String json) {
        String base = sanitize(baseName);
        try {
            Path dir = dir();
            Files.createDirectories(dir);
            Path file = dir.resolve(base + ".json");
            for (int n = 2; Files.exists(file); n++) file = dir.resolve(base + "_" + n + ".json");
            Files.writeString(file, json);
            return file.getFileName().toString().replace(".json", "");
        } catch (Exception e) {
            return null;
        }
    }

    public static void delete(String name) {
        if (!safeName(name)) return;
        try {
            Path dir = dir();
            Path file = dir.resolve(name + ".json").normalize();
            if (file.startsWith(dir)) Files.deleteIfExists(file);
        } catch (Exception ignored) {
            // Locked/unremovable: a later re-sync simply keeps showing it.
        }
    }

    /** Push the current blueprint set to one player (works on both the integrated and dedicated server). */
    public static void syncTo(ServerPlayer sp) {
        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp),
                new GlintServerBlueprintsSyncPacket(readAll()));
    }

    private static String sanitize(String s) {
        if (s == null) return "trim";
        String cleaned = s.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        return cleaned.isEmpty() ? "trim" : cleaned;
    }
}
