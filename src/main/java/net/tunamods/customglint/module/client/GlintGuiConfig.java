package net.tunamods.customglint.module.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Tiny client-side persistence for the Glint GUI preferences (wand / table skin index and the button-click
 * sound toggle). Backed by {@code config/customglint/gui.properties} so the choice survives restarts; loaded
 * lazily on first read and written on menu close ({@link #flush()}). Standalone-only — not in the api jar.
 *
 * <p>Kept as a plain properties file rather than a {@code ModConfigSpec} so it needs no mod-container
 * registration wiring; these are cosmetic, client-local toggles that never sync.
 */
public final class GlintGuiConfig {
    private GlintGuiConfig() {}

    private static final Path FILE = Paths.get("config", "customglint", "gui.properties");
    private static Properties props;
    private static boolean dirty;

    private static Properties props() {
        if (props == null) {
            props = new Properties();
            try (InputStream in = Files.newInputStream(FILE)) {
                props.load(in);
            } catch (IOException ignored) {
                // First run / file absent — defaults apply.
            }
        }
        return props;
    }

    private static int getInt(String key, int def) {
        try {
            return Integer.parseInt(props().getProperty(key, Integer.toString(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void set(String key, String value) {
        props().setProperty(key, value);
        dirty = true;
    }

    public static int wandSkin()            { return getInt("wandSkin", 0); }
    public static void setWandSkin(int i)    { set("wandSkin", Integer.toString(i)); }

    public static int tableSkin()           { return getInt("tableSkin", 0); }
    public static void setTableSkin(int i)   { set("tableSkin", Integer.toString(i)); }

    public static boolean sound()           { return Boolean.parseBoolean(props().getProperty("sound", "true")); }
    public static void setSound(boolean on)  { set("sound", Boolean.toString(on)); }

    /** Persist pending changes to disk. Call when a Glint menu closes. No-op if nothing changed. */
    public static void flush() {
        if (!dirty) return;
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream out = Files.newOutputStream(FILE)) {
                props().store(out, "Custom Glints client GUI preferences");
            }
            dirty = false;
        } catch (IOException ignored) {
            // Non-fatal: a failed persist just means the choice doesn't survive this restart.
        }
    }
}
