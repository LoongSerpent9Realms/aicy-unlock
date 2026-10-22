package dev.loong.aicyunlock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lists the class names inside a package of the host app.
 *
 * <p>Two routes, because the runtime one is not portable:
 *
 * <ol>
 *   <li><b>Runtime</b> — walk {@code BaseDexClassLoader.pathList.dexElements}
 *       and ask each {@code DexFile} for its entries. Fast, but the field names
 *       it needs are hidden API: 13.0.54 on Android 15 answered fine, while the
 *       same build on Android 16 returned nothing at all, which silently killed
 *       the whole TTS takeover ({@code cannot enumerate ... takeover skipped}).</li>
 *   <li><b>Archive</b> — open the APKs the class loader was built from and read
 *       the string table of every {@code classes*.dex} directly. Slower (a few
 *       tens of ms, tens of MB of reads) but it touches no hidden field, so it
 *       does not care what the platform renamed this cycle.</li>
 * </ol>
 *
 * <p>Results are cached per package: the dex contents cannot change while the
 * process lives, and both the TTS hooks and the catalogue injection ask for the
 * same answers repeatedly.
 */
final class DexIndex {

    /** pkg -> class names, filled on first successful scan. */
    private static final java.util.Map<String, List<String>> CACHE = new java.util.HashMap<>();

    private DexIndex() {
    }

    static List<String> classesIn(ClassLoader loader, String pkg,
                                  BiConsumer<String, Throwable> log) {
        synchronized (CACHE) {
            List<String> cached = CACHE.get(pkg);
            if (cached != null) return cached;
        }
        List<String> names = viaRuntime(loader, pkg);
        if (names.isEmpty()) {
            names = viaArchives(loader, pkg, log);
        }
        if (!names.isEmpty()) {
            synchronized (CACHE) {
                CACHE.put(pkg, names);
            }
        }
        return names;
    }

    /** Drops the cache: only used when a new process starts from scratch. */
    static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    // ------------------------------------------------------------ fast path

    private static List<String> viaRuntime(ClassLoader loader, String pkg) {
        List<String> out = new ArrayList<>();
        String prefix = pkg + ".";
        try {
            Field pathListField = Class.forName("dalvik.system.BaseDexClassLoader")
                    .getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(loader);
            if (pathList == null) return out;

            Field elementsField = findField(pathList.getClass(), "dexElements", "mutableDexElements");
            if (elementsField == null) return out;
            elementsField.setAccessible(true);
            Object[] elements = (Object[]) elementsField.get(pathList);
            if (elements == null) return out;

            Method entries = Class.forName("dalvik.system.DexFile").getMethod("entries");
            for (Object element : elements) {
                Object dexFile = fieldOrNull(element, "dexFile");
                if (dexFile == null) dexFile = fieldOrNull(element, "mInternalDexFile");
                if (dexFile == null) continue;
                Object raw = entries.invoke(dexFile);
                if (!(raw instanceof Enumeration)) continue;
                Enumeration<?> iterator = (Enumeration<?>) raw;
                while (iterator.hasMoreElements()) {
                    Object next = iterator.nextElement();
                    if (next instanceof String) {
                        String name = (String) next;
                        if (name.startsWith(prefix)) out.add(name);
                    }
                }
            }
        } catch (Throwable ignored) {
            // Android 16 and friends: fall through to the archive route
        }
        return out;
    }

    // --------------------------------------------------------- archive path

    private static List<String> viaArchives(ClassLoader loader, String pkg,
                                            BiConsumer<String, Throwable> log) {
        Set<String> out = new LinkedHashSet<>();
        List<File> archives = archivesOf(loader);
        if (archives.isEmpty()) {
            log.accept("AicyUnlock: no dex archive reachable for " + pkg, null);
            return new ArrayList<>(out);
        }
        String prefix = pkg.replace('.', '/') + "/";
        int dexFiles = 0;
        for (File archive : archives) {
            ZipFile zip = null;
            try {
                zip = new ZipFile(archive);
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.startsWith("classes") || !name.endsWith(".dex")) continue;
                    dexFiles++;
                    byte[] dex = readAll(zip.getInputStream(entry), entry.getSize());
                    collectClasses(dex, prefix, out);
                }
            } catch (Throwable error) {
                log.accept("AicyUnlock: archive scan failed for " + archive.getName(), error);
            } finally {
                if (zip != null) {
                    try {
                        zip.close();
                    } catch (Throwable ignored) {
                        // nothing to do
                    }
                }
            }
        }
        log.accept("AicyUnlock: archive scan " + pkg + " -> " + out.size()
                + " classes from " + dexFiles + " dex in " + archives.size() + " apk", null);
        return new ArrayList<>(out);
    }

    /** The APK files the loader was built from, or the app's own dirs as a fallback. */
    private static List<File> archivesOf(ClassLoader loader) {
        List<File> out = new ArrayList<>();
        try {
            Field pathListField = Class.forName("dalvik.system.BaseDexClassLoader")
                    .getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(loader);
            Field elementsField = pathList == null ? null
                    : findField(pathList.getClass(), "dexElements", "mutableDexElements");
            if (elementsField != null) {
                elementsField.setAccessible(true);
                Object[] elements = (Object[]) elementsField.get(pathList);
                if (elements != null) {
                    for (Object element : elements) {
                        // Element.path is the archive; it has kept that name since
                        // API 26, unlike the DexFile handle next to it.
                        Object path = fieldOrNull(element, "path");
                        if (path instanceof File) out.add((File) path);
                    }
                }
            }
        } catch (Throwable ignored) {
            // fall through to ApplicationInfo
        }
        if (!out.isEmpty()) return out;

        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method current = activityThread.getDeclaredMethod("currentApplication");
            current.setAccessible(true);
            Object app = current.invoke(null);
            if (app instanceof android.content.Context) {
                android.content.pm.ApplicationInfo info =
                        ((android.content.Context) app).getApplicationInfo();
                if (info != null) {
                    if (info.sourceDir != null) out.add(new File(info.sourceDir));
                    if (info.splitSourceDirs != null) {
                        for (String split : info.splitSourceDirs) {
                            if (split != null) out.add(new File(split));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // no context yet
        }
        return out;
    }

    // ------------------------------------------------------------ dex parse

    /**
     * Reads the string table of one DEX image and keeps the descriptors that
     * start with {@code prefix} (which is the package path, no leading L).
     *
     * <p>String data is MUTF-8, but class descriptors are pure ASCII, so a byte
     * compare is exact.
     */
    private static void collectClasses(byte[] dex, String prefix, Set<String> out) {
        if (dex.length < 0x70) return;
        if (dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x' || dex[3] != '\n') return;
        int stringIdsSize = le32(dex, 0x38);
        int stringIdsOff = le32(dex, 0x3C);
        if (stringIdsSize <= 0 || stringIdsOff <= 0) return;
        if ((long) stringIdsOff + (long) stringIdsSize * 4 > dex.length) return;

        int prefixLen = prefix.length();
        for (int i = 0; i < stringIdsSize; i++) {
            int at = le32(dex, stringIdsOff + i * 4);
            if (at <= 0 || at >= dex.length) continue;
            // uleb128 payload length, then the bytes themselves
            int pos = at;
            int len = 0;
            int shift = 0;
            while (pos < dex.length) {
                int b = dex[pos++] & 0xFF;
                len |= (b & 0x7F) << shift;
                shift += 7;
                if ((b & 0x80) == 0) break;
            }
            // Descriptors are "L" + package path + "/" + name + ";"
            if (len < prefixLen + 2 || pos + len > dex.length) continue;
            if (dex[pos] != 'L') continue;
            boolean match = true;
            for (int k = 0; k < prefixLen; k++) {
                if (dex[pos + 1 + k] != (byte) prefix.charAt(k)) {
                    match = false;
                    break;
                }
            }
            if (!match) continue;
            int start = pos + 1;
            int end = start;
            int limit = pos + len;
            while (end < limit && dex[end] != ';') end++;
            if (end <= start) continue;
            out.add(new String(dex, start, end - start, java.nio.charset.StandardCharsets.US_ASCII)
                    .replace('/', '.'));
        }
    }

    private static int le32(byte[] b, int at) {
        return (b[at] & 0xFF)
                | ((b[at + 1] & 0xFF) << 8)
                | ((b[at + 2] & 0xFF) << 16)
                | ((b[at + 3] & 0xFF) << 24);
    }

    private static byte[] readAll(InputStream in, long hint) throws Exception {
        int initial = (int) Math.max(64 * 1024, Math.min(hint > 0 ? hint : 1 << 20, 8 << 20));
        ByteArrayOutputStream out = new ByteArrayOutputStream(initial);
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        in.close();
        return out.toByteArray();
    }

    // -------------------------------------------------------------- helpers

    private static Field findField(Class<?> type, String... names) {
        for (String name : names) {
            try {
                return type.getDeclaredField(name);
            } catch (Throwable ignored) {
                // try the next spelling
            }
        }
        return null;
    }

    private static Object fieldOrNull(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
