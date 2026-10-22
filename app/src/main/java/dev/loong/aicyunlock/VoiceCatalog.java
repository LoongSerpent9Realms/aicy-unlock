package dev.loong.aicyunlock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Injects the user's own timbres into Aicy's stock list, so 设置 → 声音 shows one
 * row per configured profile next to 微微 / 小何 / 云舟 / 小天.
 *
 * <p>Nothing stock is replaced or reordered: the original entries keep their ids,
 * names and order, and ours are appended in profile order.
 *
 * <p><b>The class names move between releases.</b> 13.0.54 kept the catalogue in
 * {@code voice.x} and the row type in {@code voice.z}; 13.0.55 moved them to
 * {@code voice.y} and {@code voice.A}, and turned {@code voice.x} into a holder
 * of five {@link java.util.regex.Pattern}s. Hard-coding either pair breaks on the
 * next update — which is exactly how the 13.0.55 regression shipped: the code
 * read {@code x.b}, got a {@code kotlin.text.Regex}, and bailed out with
 * "catalogue field b is not a List".
 *
 * <p>So the layout is now discovered instead of assumed. We walk the voice
 * package, look for a {@code static List} whose elements match the row shape
 * below, and take that class as the catalogue plus its element type as the row.
 * The shape check is what keeps unrelated lists (say the ASR language tables)
 * from being mistaken for the catalogue.
 *
 * <pre>
 *   catalogue:  static final List b = [row, row, ...]
 *               static List a(String speakerId)      lookup by id
 *   row (13.0.55 = voice.A):
 *               a: speakerId   b: displayName   c: genderLabel
 *               d: avatarRes (org.jetbrains.compose.resources.e)
 *               e: supportedLanguages (Set)
 * </pre>
 *
 * <p>Avatars cannot be user-supplied images: {@code d} holds a Compose resource
 * handle, not a Bitmap or a Uri, so it can only point at something already
 * compiled into Aicy's APK. Each profile therefore picks one of the stock
 * entries as its avatar template — enough to tell the rows apart visually.
 *
 * <p>The list field is {@code static final}, so it is swapped by reflection
 * after forcing the class initialiser to run. When the runtime refuses the write
 * we report it and leave the list untouched rather than half-applying it.
 */
final class VoiceCatalog {

    /** Package that holds both the catalogue and the row type. */
    static final String VOICE_PACKAGE = "com.flyme.agentos.app.common.voice";

    /**
     * Last-resort guesses when dex enumeration is unavailable. Ordered newest
     * first; every candidate still has to pass the shape check below.
     */
    private static final String[] FALLBACK_CLASSES = {
            "y", "x", "z", "A", "B", "w", "v",
    };

    /** Prefix that tells the TTS takeover "this speaker is not Doubao's". */
    static final String CUSTOM_PREFIX = "aicyunlock_";
    /** Kept as the single-profile default so older configs still resolve. */
    static final String CUSTOM_SPEAKER = CUSTOM_PREFIX + "my_voice";
    static final String CUSTOM_GENDER = "自定义";

    private final ClassLoader loader;
    private final BiConsumer<String, Throwable> log;

    /** Resolved once: the class holding the catalogue, its list field, the row type. */
    private Class<?> catalogClass;
    private Field catalogField;
    private Class<?> timbreClass;

    VoiceCatalog(ClassLoader loader, BiConsumer<String, Throwable> log) {
        this.loader = loader;
        this.log = log;
    }

    /**
     * Rebuilds the catalogue as <em>stock entries</em> + <em>one row per
     * profile</em>.
     *
     * <p>Stale rows from a previous run (a profile the user deleted since) are
     * dropped, so the list always mirrors the current configuration.
     *
     * @param profiles the user's timbres; an empty list leaves stock untouched
     * @return the number of rows now present, or -1 when injection failed
     */
    int inject(List<VoiceProfile> profiles) {
        try {
            if (!resolve()) {
                log.accept("AicyVoice: no catalogue found in " + VOICE_PACKAGE
                        + " (no static List of timbre rows) — stock list unchanged", null);
                return -1;
            }
            Object raw = catalogField.get(null);
            if (!(raw instanceof List)) {
                log.accept("AicyVoice: " + catalogClass.getSimpleName() + "."
                        + catalogField.getName() + " is not a List ("
                        + (raw == null ? "null" : raw.getClass().getName()) + ")", null);
                return -1;
            }
            @SuppressWarnings("unchecked")
            List<Object> stock = (List<Object>) raw;

            List<Object> templates = new ArrayList<>();
            List<Object> stockOnly = new ArrayList<>();
            for (Object entry : stock) {
                if (!timbreClass.isInstance(entry)) continue;
                templates.add(entry);
                // Anything carrying our prefix is from an earlier injection.
                if (!isOurs(entry)) stockOnly.add(entry);
            }
            if (templates.isEmpty()) {
                log.accept("AicyVoice: no stock timbre to clone, abort", null);
                return -1;
            }
            if (profiles == null || profiles.isEmpty()) {
                // Nothing configured: only strip our leftovers.
                if (stockOnly.size() != stock.size()) {
                    if (writeList(catalogField, stockOnly)) {
                        log.accept("AicyVoice: cleared " + (stock.size() - stockOnly.size())
                                + " stale custom rows", null);
                        return stockOnly.size();
                    }
                }
                return stockOnly.size();
            }

            List<Object> merged = new ArrayList<>(stockOnly);
            int added = 0;
            for (int i = 0; i < profiles.size(); i++) {
                VoiceProfile profile = profiles.get(i);
                Object template = templates.get(
                        Math.floorMod(profile.avatar, templates.size()));
                Object row = buildTimbre(template, profile);
                if (row == null) {
                    log.accept("AicyVoice: skipped " + profile.id
                            + " (no usable constructor)", null);
                    continue;
                }
                merged.add(row);
                added++;
            }
            if (writeList(catalogField, merged)) {
                log.accept("AicyVoice: catalogue " + stockOnly.size() + " -> "
                        + merged.size() + " entries (" + added + " custom)", null);
                AicyHooks.report("音色目录已注入 · " + added + " 条自定义音色");
                return merged.size();
            }
            log.accept("AicyVoice: static field write refused, catalogue left untouched", null);
            return -1;
        } catch (Throwable error) {
            log.accept("AicyVoice: catalogue injection failed", error);
            return -1;
        }
    }

    // ---------------------------------------------------------------- discovery

    /** Finds the catalogue once; later calls are free. */
    private boolean resolve() {
        if (catalogField != null && timbreClass != null) return true;

        for (String name : classesIn(VOICE_PACKAGE)) {
            if (name.indexOf('$') >= 0) continue;
            if (tryClass(name)) return true;
        }
        // Enumeration can come up empty when the dex layout is unusual; the
        // obfuscated names are single letters, so a short guess list is cheap.
        log.accept("AicyVoice: dex enumeration found no catalogue, trying "
                + FALLBACK_CLASSES.length + " known names", null);
        for (String simple : FALLBACK_CLASSES) {
            if (tryClass(VOICE_PACKAGE + "." + simple)) return true;
        }
        return false;
    }

    /** True when {@code name} holds a static List of timbre-shaped rows. */
    private boolean tryClass(String name) {
        Class<?> type;
        try {
            // Initialise: the stock list only exists after <clinit>.
            type = Class.forName(name, true, loader);
        } catch (Throwable ignored) {
            return false;
        }
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            if (field.getType() != List.class) continue;
            try {
                field.setAccessible(true);
            } catch (Throwable ignored) {
                continue;
            }
            Object value;
            try {
                value = field.get(null);
            } catch (Throwable ignored) {
                continue;
            }
            if (!(value instanceof List)) continue;
            List<?> list = (List<?>) value;
            Class<?> element = elementType(list);
            if (element == null || !looksLikeTimbre(element)) continue;

            catalogClass = type;
            catalogField = field;
            timbreClass = element;
            log.accept("AicyVoice: catalogue = " + name + "." + field.getName()
                    + " (" + list.size() + " stock, row=" + element.getName() + ")", null);
            return true;
        }
        return false;
    }

    private static Class<?> elementType(List<?> list) {
        for (Object entry : list) {
            if (entry != null) return entry.getClass();
        }
        return null;
    }

    /**
     * The row shape: three Strings (id, name, gender) and a language Set. The
     * avatar type is left open — it is a Compose resource handle whose obfuscated
     * name changes too, and we never construct it, only copy it.
     */
    private static boolean looksLikeTimbre(Class<?> type) {
        try {
            if (type.getDeclaredField("a").getType() != String.class) return false;
            if (type.getDeclaredField("b").getType() != String.class) return false;
            if (type.getDeclaredField("c").getType() != String.class) return false;
            if (type.getDeclaredField("e").getType() != Set.class) return false;
        } catch (Throwable missing) {
            return false;
        }
        // A five-arg constructor is how we build our own rows later on.
        for (Constructor<?> ctor : type.getDeclaredConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 5
                    && params[0] == String.class && params[1] == String.class
                    && params[2] == String.class && params[4] == Set.class) {
                return true;
            }
        }
        return false;
    }

    /** Same two-route scan the TTS hooks use; see {@link DexIndex}. */
    private List<String> classesIn(String pkg) {
        List<String> all = DexIndex.classesIn(loader, pkg, log);
        List<String> top = new ArrayList<>();
        for (String name : all) {
            // Nested names are lambdas and companions, never the catalogue holder.
            if (name.indexOf('$') < 0) top.add(name);
        }
        return top;
    }

    // ------------------------------------------------------------------ building

    private boolean isOurs(Object entry) {
        try {
            Object id = timbreClass.getDeclaredField("a").get(entry);
            return id instanceof String && ((String) id).startsWith(CUSTOM_PREFIX);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Clones a stock entry, swapping only the identity fields. The avatar handle
     * and the language set are reused as-is, so nothing has to be constructed
     * from Compose types we cannot reference at compile time.
     */
    private Object buildTimbre(Object template, VoiceProfile profile) {
        try {
            Field avatar = timbreClass.getDeclaredField("d");
            Field languages = timbreClass.getDeclaredField("e");
            avatar.setAccessible(true);
            languages.setAccessible(true);
            Object avatarRes = avatar.get(template);
            Object langs = languages.get(template);

            for (Constructor<?> ctor : timbreClass.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length != 5) continue;
                if (params[0] != String.class || params[1] != String.class
                        || params[2] != String.class) continue;
                if (params[4] != Set.class) continue;
                if (avatarRes != null && !params[3].isInstance(avatarRes)) continue;
                ctor.setAccessible(true);
                return ctor.newInstance(profile.id, profile.name, CUSTOM_GENDER,
                        avatarRes, langs);
            }
            return null;
        } catch (Throwable error) {
            log.accept("AicyVoice: cannot build " + profile.id, error);
            return null;
        }
    }

    /** Tries the static-final swap, then the modifiers hack, then Unsafe. */
    private boolean writeList(Field field, List<Object> merged) {
        try {
            field.set(null, merged);
            return true;
        } catch (Throwable refused) {
            // static final — expected on some ART builds; try harder below
        }
        try {
            Field modifiers = Field.class.getDeclaredField("accessFlags");
            modifiers.setAccessible(true);
            modifiers.setInt(field, field.getModifiers() & ~Modifier.FINAL);
            field.set(null, merged);
            return true;
        } catch (Throwable ignored) {
            // no accessFlags on this runtime
        }
        try {
            Object unsafe = unsafe();
            Class<?> type = unsafe.getClass();
            Method base = type.getMethod("staticFieldBase", Field.class);
            Method offset = type.getMethod("staticFieldOffset", Field.class);
            Method put = type.getMethod("putObject", Object.class, long.class, Object.class);
            Object target = base.invoke(unsafe, field);
            long at = (Long) offset.invoke(unsafe, field);
            put.invoke(unsafe, target, at, merged);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object unsafe() throws Exception {
        Class<?> type = Class.forName("sun.misc.Unsafe");
        Field field = type.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return field.get(null);
    }
}
