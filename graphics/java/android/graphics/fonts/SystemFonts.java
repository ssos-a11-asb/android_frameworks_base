/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.graphics.fonts;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.FontListParser;
import android.text.FontConfig;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provides the system font configurations.
 */
public final class SystemFonts {
    private static final String TAG = "SystemFonts";
    private static final String DEFAULT_FAMILY = "sans-serif";

    private SystemFonts() {}  // Do not instansiate.

    private static final Object LOCK = new Object();
    private static @GuardedBy("sLock") Set<Font> sAvailableFonts;
    private static @GuardedBy("sLock") Map<String, FontFamily[]> sFamilyMap;

    /**
     * Returns all available font files in the system.
     *
     * @return a set of system fonts
     */
    public static @NonNull Set<Font> getAvailableFonts() {
        synchronized (LOCK) {
            if (sAvailableFonts != null) {
                return sAvailableFonts;
            }

            Set<Font> set = new HashSet<>();

            for (FontFamily[] items : sFamilyMap.values()) {
                for (FontFamily family : items) {
                    for (int i = 0; i < family.getSize(); ++i) {
                        set.add(family.getFont(i));
                    }
                }
            }

            sAvailableFonts = Collections.unmodifiableSet(set);
            return sAvailableFonts;
        }
    }

    private static @Nullable ByteBuffer mmap(@NonNull String fullPath) {
        try (FileInputStream file = new FileInputStream(fullPath)) {
            final FileChannel fileChannel = file.getChannel();
            final long fontSize = fileChannel.size();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fontSize);
        } catch (IOException e) {
            return null;
        }
    }

    private static void pushFamilyToFallback(@NonNull FontConfig.Family xmlFamily,
            @NonNull ArrayMap<String, ArrayList<FontFamily>> fallbackMap,
            @NonNull Map<String, ByteBuffer> cache) {

        final String languageTags = xmlFamily.getLanguages();
        final int variant = xmlFamily.getVariant();

        final ArrayList<FontConfig.Font> defaultFonts = new ArrayList<>();
        final ArrayMap<String, ArrayList<FontConfig.Font>> specificFallbackFonts = new ArrayMap<>();

        // Collect default fallback and specific fallback fonts.
        for (final FontConfig.Font font : xmlFamily.getFonts()) {
            final String fallbackName = font.getFallbackFor();
            if (fallbackName == null) {
                defaultFonts.add(font);
            } else {
                ArrayList<FontConfig.Font> fallback = specificFallbackFonts.get(fallbackName);
                if (fallback == null) {
                    fallback = new ArrayList<>();
                    specificFallbackFonts.put(fallbackName, fallback);
                }
                fallback.add(font);
            }
        }

        final FontFamily defaultFamily = defaultFonts.isEmpty() ? null : createFontFamily(
                xmlFamily.getName(), defaultFonts, languageTags, variant, cache);

        // Insert family into fallback map.
        for (int i = 0; i < fallbackMap.size(); i++) {
            final ArrayList<FontConfig.Font> fallback =
                    specificFallbackFonts.get(fallbackMap.keyAt(i));
            if (fallback == null) {
                if (defaultFamily != null) {
                    fallbackMap.valueAt(i).add(defaultFamily);
                }
            } else {
                final FontFamily family = createFontFamily(
                        xmlFamily.getName(), fallback, languageTags, variant, cache);
                if (family != null) {
                    fallbackMap.valueAt(i).add(family);
                } else if (defaultFamily != null) {
                    fallbackMap.valueAt(i).add(defaultFamily);
                } else {
                    // There is no valid for for default fallback. Ignore.
                }
            }
        }
    }

    private static @Nullable FontFamily createFontFamily(@NonNull String familyName,
            @NonNull List<FontConfig.Font> fonts,
            @NonNull String languageTags,
            @FontConfig.Family.Variant int variant,
            @NonNull Map<String, ByteBuffer> cache) {
        if (fonts.size() == 0) {
            return null;
        }

        FontFamily.Builder b = null;
        for (int i = 0; i < fonts.size(); i++) {
            final FontConfig.Font fontConfig = fonts.get(i);
            final String fullPath = fontConfig.getFontName();
            ByteBuffer buffer = cache.get(fullPath);
            if (buffer == null) {
                if (cache.containsKey(fullPath)) {
                    continue;  // Already failed to mmap. Skip it.
                }
                buffer = mmap(fullPath);
                cache.put(fullPath, buffer);
                if (buffer == null) {
                    continue;
                }
            }

            final Font font;
            try {
                font = new Font.Builder(buffer, new File(fullPath), languageTags)
                        .setWeight(fontConfig.getWeight())
                        .setSlant(fontConfig.isItalic() ? FontStyle.FONT_SLANT_ITALIC
                                : FontStyle.FONT_SLANT_UPRIGHT)
                        .setTtcIndex(fontConfig.getTtcIndex())
                        .setFontVariationSettings(fontConfig.getAxes())
                        .build();
            } catch (IOException e) {
                throw new RuntimeException(e);  // Never reaches here
            }

            if (b == null) {
                b = new FontFamily.Builder(font);
            } else {
                b.addFont(font);
            }
        }
        return b == null ? null : b.build(languageTags, variant, false /* isCustomFallback */);
    }

    private static void appendNamedFamily(@NonNull FontConfig.Family xmlFamily,
            @NonNull HashMap<String, ByteBuffer> bufferCache,
            @NonNull ArrayMap<String, ArrayList<FontFamily>> fallbackListMap) {
        final String familyName = xmlFamily.getName();
        final FontFamily family = createFontFamily(
                familyName, Arrays.asList(xmlFamily.getFonts()),
                xmlFamily.getLanguages(), xmlFamily.getVariant(), bufferCache);
        if (family == null) {
            return;
        }
        final ArrayList<FontFamily> fallback = new ArrayList<>();
        fallback.add(family);
        fallbackListMap.put(familyName, fallback);
    }

    /**
     * Build the system fallback from xml file.
     *
     * @param xmlPath A full path string to the fonts.xml file.
     * @param fontDir A full path string to the system font directory. This must end with
     *                slash('/').
     * @param fallbackMap An output system fallback map. Caller must pass empty map.
     * @return a list of aliases
     * @hide
     */
    @VisibleForTesting
    public static FontConfig.Alias[] buildSystemFallback(@NonNull String xmlPath,
            @NonNull String fontDir,
            @NonNull FontCustomizationParser.Result oemCustomization,
            @NonNull Map<String, FontFamily[]> fallbackMap) {
        try {
            final FileInputStream fontsIn = new FileInputStream(xmlPath);
            final FontConfig fontConfig = FontListParser.parse(fontsIn, fontDir);

            final HashMap<String, ByteBuffer> bufferCache = new HashMap<String, ByteBuffer>();
            final FontConfig.Family[] xmlFamilies = fontConfig.getFamilies();

            final ArrayMap<String, ArrayList<FontFamily>> fallbackListMap = new ArrayMap<>();
            // First traverse families which have a 'name' attribute to create fallback map.
            for (final FontConfig.Family xmlFamily : xmlFamilies) {
                final String familyName = xmlFamily.getName();
                if (familyName == null) {
                    continue;
                }
                appendNamedFamily(xmlFamily, bufferCache, fallbackListMap);
            }

            for (int i = 0; i < oemCustomization.mAdditionalNamedFamilies.size(); ++i) {
                appendNamedFamily(oemCustomization.mAdditionalNamedFamilies.get(i),
                        bufferCache, fallbackListMap);
            }

            // Then, add fallback fonts to the each fallback map.
            for (int i = 0; i < xmlFamilies.length; i++) {
                final FontConfig.Family xmlFamily = xmlFamilies[i];
                // The first family (usually the sans-serif family) is always placed immediately
                // after the primary family in the fallback.
                if (i == 0 || xmlFamily.getName() == null) {
                    pushFamilyToFallback(xmlFamily, fallbackListMap, bufferCache);
                }
            }

            // Build the font map and fallback map.
            for (int i = 0; i < fallbackListMap.size(); i++) {
                final String fallbackName = fallbackListMap.keyAt(i);
                final List<FontFamily> familyList = fallbackListMap.valueAt(i);
                final FontFamily[] families = familyList.toArray(new FontFamily[familyList.size()]);

                fallbackMap.put(fallbackName, families);
            }

            final ArrayList<FontConfig.Alias> list = new ArrayList<>();
            list.addAll(Arrays.asList(fontConfig.getAliases()));
            list.addAll(oemCustomization.mAdditionalAliases);
            return list.toArray(new FontConfig.Alias[list.size()]);
        } catch (IOException | XmlPullParserException e) {
            Log.e(TAG, "Failed initialize system fallbacks.", e);
            return ArrayUtils.emptyArray(FontConfig.Alias.class);
        }
    }

    private static FontCustomizationParser.Result readFontCustomization(
            @NonNull String customizeXml, @NonNull String customFontsDir) {
        try (FileInputStream f = new FileInputStream(customizeXml)) {
            return FontCustomizationParser.parse(f, customFontsDir);
        } catch (IOException e) {
            return new FontCustomizationParser.Result();
        } catch (XmlPullParserException e) {
            Log.e(TAG, "Failed to parse font customization XML", e);
            return new FontCustomizationParser.Result();
        }
    }

    /** @hide */
    public static @NonNull Pair<FontConfig.Alias[], Map<String, FontFamily[]>>
            initializePreinstalledFonts() {
        final FontCustomizationParser.Result oemCustomization =
                readFontCustomization("/product/etc/fonts_customization.xml", "/product/fonts/");
        Map<String, FontFamily[]> map = new ArrayMap<>();
        FontConfig.Alias[] aliases = buildSystemFallback("/system/etc/fonts.xml", "/system/fonts/",
                oemCustomization, map);
        synchronized (LOCK) {
            sFamilyMap = map;
        }
        return new Pair(aliases, map);
    }
}
