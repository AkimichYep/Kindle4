package com.yep.kindle.dron.display;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Thread-safe singleton loader for the bundled Weather Icons TrueType font
 * (Erik Flowers, SIL OFL 1.1).
 * <p>
 * Previously each of the three Kindle app classes had its own identical
 * {@code getWeatherIconBaseFont()} method and a separate static cache field.
 * This class consolidates that into one load point.
 * </p>
 * <p>
 * Usage:
 * <pre>{@code
 * Font icon = WeatherIconFont.get();
 * if (icon != null) {
 *     g2d.setFont(icon.deriveFont(Font.PLAIN, 48f));
 *     g2d.drawString(WeatherIconFont.glyph(0xF00D), x, y); // sunny
 * }
 * }</pre>
 * </p>
 */
public final class WeatherIconFont {

    private static final String FONT_PATH = "font/weathericons-regular-webfont.ttf";

    /** Lazily initialised; {@code null} if the font resource is unavailable. */
    private static volatile Font instance;

    private WeatherIconFont() {}

    /**
     * Returns the base {@link Font} object at size 1.0 (derive before use),
     * or {@code null} when the font resource cannot be found or loaded.
     * <p>
     * The font is loaded from the classpath of the supplied {@code loader}
     * class — typically pass {@code MyClass.class} from the caller module.
     * After the first successful load the result is cached for the JVM lifetime.
     * </p>
     *
     * @param loader any class whose classloader can see the font resource
     * @return the base weather-icons {@link Font}, or {@code null}
     */
    public static synchronized Font get(Class<?> loader) {
        if (instance != null) {
            return instance;
        }
        try (InputStream in = loader.getClassLoader().getResourceAsStream(FONT_PATH)) {
            if (in == null) {
                return null;
            }
            instance = Font.createFont(Font.TRUETYPE_FONT, in);
            return instance;
        } catch (IOException | FontFormatException e) {
            return null;
        }
    }

    /**
     * Returns the base font using this class's own classloader as the lookup
     * anchor.  Use this form when calling from within the same module as the
     * font resource; otherwise prefer {@link #get(Class)}.
     *
     * @return the base weather-icons {@link Font}, or {@code null}
     */
    public static synchronized Font get() {
        return get(WeatherIconFont.class);
    }

    /**
     * Convenience helper — converts a Unicode code point to the single-character
     * {@link String} that {@link java.awt.Graphics2D#drawString} expects.
     *
     * @param codePoint a Weather Icons Unicode code point, e.g. {@code 0xF00D}
     * @return a {@code String} containing exactly one code-point character
     */
    public static String glyph(int codePoint) {
        return new String(Character.toChars(codePoint));
    }
}
