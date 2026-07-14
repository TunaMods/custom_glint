package net.tunamods.customglint.module;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Single source of truth for the mod's on-disk config directory. Both the dedicated/integrated server
 * (shared blueprints in {@link ServerBlueprints}, {@code /glint export}) and the client GUI (wand editor +
 * Glint Table import lists, {@code GlintGuiConfig}) resolve every path through here so the two can never
 * drift apart - if they pointed at different folders the import lists would silently go empty.
 *
 * <p>The folder is {@code config/glint-and-glamour/} (the display-name brand). The mod id stays
 * {@code customglint} everywhere else; only this player-visible folder and the jar/maven names carry the
 * new name. Pre-1.7.0 installs kept everything under {@code config/customglint/} - {@link #migrateLegacy()}
 * renames that folder in place on first launch so nobody loses saved trims.
 *
 * <p>Server-safe: pure {@code java.nio}, no client imports, lives in the top-level {@code module} package.
 */
public final class ModConfigPaths {
    private ModConfigPaths() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The mod's config folder: {@code config/glint-and-glamour/} (absolute). */
    public static final Path CONFIG_DIR = Paths.get("config", "glint-and-glamour").toAbsolutePath();

    /** Saved/shared blueprint trims: {@code config/glint-and-glamour/trims/} (absolute). */
    public static final Path TRIMS_DIR = CONFIG_DIR.resolve("trims");

    /** Pre-1.7.0 folder, kept only so {@link #migrateLegacy()} can move it. */
    private static final Path LEGACY_DIR = Paths.get("config", "customglint").toAbsolutePath();

    /** A file inside the trims dir, e.g. {@code trimFile("Golden")} → {@code .../trims/Golden.json}. */
    public static Path trimFile(String name) {
        return TRIMS_DIR.resolve(name + ".json");
    }

    /** The client GUI preferences file: {@code config/glint-and-glamour/gui.properties}. */
    public static Path guiProperties() {
        return CONFIG_DIR.resolve("gui.properties");
    }

    /**
     * One-time rename of the old {@code config/customglint/} folder to {@code config/glint-and-glamour/}.
     * No-op once migrated (or on a fresh install). Runs on both physical sides during common setup, before
     * anything reads the folder. Best-effort: if the move fails (locked file, cross-device), the new folder
     * is created empty by whichever caller needs it and the old files are left untouched.
     */
    public static void migrateLegacy() {
        try {
            if (Files.isDirectory(LEGACY_DIR) && !Files.exists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR.getParent());
                Files.move(LEGACY_DIR, CONFIG_DIR);
                LOGGER.info("Migrated config folder {} -> {}", LEGACY_DIR, CONFIG_DIR);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not migrate {} to {}: {}", LEGACY_DIR, CONFIG_DIR, e.toString());
        }
    }
}
