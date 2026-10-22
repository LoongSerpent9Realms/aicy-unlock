package dev.loong.aicyunlock;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The headset allow-list behind "只播信任的耳机".
 *
 * <p>Stored as a small JSON document in the module preferences and served to
 * Aicy's process through {@link ConfigProvider}. An empty list trusts nothing:
 * with the headset switch on, no output passes until the user approves one.
 * Once at least one device is trusted, only a matching connected output is
 * allowed to trigger speech.
 *
 * <p>Bluetooth identity is the adapter address when the platform exposes it;
 * the product name is stored as well, because {@link AudioDeviceInfo#getAddress()}
 * can come back empty in another process. Wired and USB devices usually have no
 * address, so their product name (or, failing that, the whole device kind) is
 * the best available identity.
 */
final class TrustedHeadsets {

    /** Enough for a drawer of earbuds without turning the dialog into a list. */
    static final int MAX = 12;

    private static final String KIND_BLUETOOTH = "bluetooth";
    private static final String KIND_WIRED = "wired";
    private static final String KIND_USB = "usb";
    private static final String KIND_OTHER = "other";

    private TrustedHeadsets() {
    }

    /** One remembered device. Names may be empty when the device has no label. */
    static final class Entry {
        final String id;
        final String name;
        final String address;
        final String kind;

        Entry(String id, String name, String address, String kind) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name.trim();
            this.address = address == null ? "" : address.trim();
            this.kind = kind == null || kind.isEmpty() ? KIND_OTHER : kind;
        }

        String displayName() {
            return name.isEmpty() ? kindLabel() : name;
        }

        String kindLabel() {
            switch (kind) {
                case KIND_BLUETOOTH: return "蓝牙耳机";
                case KIND_WIRED: return "有线耳机";
                case KIND_USB: return "USB 音频";
                default: return "耳机";
            }
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("name", name);
                o.put("address", address);
                o.put("kind", kind);
            } catch (Throwable ignored) {
                // org.json only throws on NaN/Infinity, which never occur here
            }
            return o;
        }
    }

    // ------------------------------------------------------------- storage

    static List<Entry> load(Context context) {
        if (context == null) return new ArrayList<>();
        try {
            return parse(context.getSharedPreferences(ConfigProvider.PREFS,
                    Context.MODE_PRIVATE).getString(ConfigProvider.KEY_NOTIFY_TRUSTED, ""));
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    static void save(Context context, List<Entry> entries) {
        if (context == null) return;
        // This list is the security boundary for headset-only playback, so the
        // write must reach disk before the app process can be frozen or killed.
        context.getSharedPreferences(ConfigProvider.PREFS, Context.MODE_PRIVATE)
                .edit().putString(ConfigProvider.KEY_NOTIFY_TRUSTED, serialize(entries))
                .commit();
    }

    static String serialize(List<Entry> entries) {
        JSONArray devices = new JSONArray();
        if (entries != null) {
            for (Entry entry : entries) {
                if (entry != null) devices.put(entry.toJson());
            }
        }
        JSONObject root = new JSONObject();
        try {
            root.put("v", 1);
            root.put("devices", devices);
        } catch (Throwable ignored) {
            // unreachable
        }
        return root.toString();
    }

    /** @return never null; skips malformed rows instead of dropping the list */
    static List<Entry> parse(String json) {
        List<Entry> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            String raw = json.trim();
            JSONArray devices = raw.startsWith("[")
                    ? new JSONArray(raw)
                    : new JSONObject(raw).optJSONArray("devices");
            if (devices == null) return out;
            for (int i = 0; i < devices.length() && out.size() < MAX; i++) {
                JSONObject o = devices.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "").trim();
                String name = o.optString("name", "").trim();
                String address = o.optString("address", "").trim();
                String kind = o.optString("kind", KIND_OTHER).trim();
                if (id.isEmpty()) id = identity(kind, name, address);
                Entry entry = new Entry(id, name, address, kind);
                if (!contains(out, entry)) out.add(entry);
            }
        } catch (Throwable ignored) {
            // corrupt document: trust nothing rather than speaking anywhere
        }
        return out;
    }

    // ------------------------------------------------------------- devices

    static List<AudioDeviceInfo> connectedHeadsets(AudioManager audio) {
        List<AudioDeviceInfo> out = new ArrayList<>();
        if (audio == null) return out;
        try {
            for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (device != null && isHeadsetType(device.getType())) out.add(device);
            }
        } catch (Throwable ignored) {
            // treat as nothing connected
        }
        return out;
    }

    /**
     * True when speech may go ahead under the headset-only switch.
     *
     * <p>An empty allow-list deliberately allows nothing. The earlier "any
     * headset" fallback meant a notification could be read into a stranger's
     * earbuds; the switch is a privacy boundary, so it must fail closed.
     */
    static boolean allowed(AudioManager audio, List<Entry> trusted) {
        if (trusted == null || trusted.isEmpty()) return false;
        List<AudioDeviceInfo> connected = connectedHeadsets(audio);
        if (connected.isEmpty()) return false;
        for (AudioDeviceInfo device : connected) {
            if (find(trusted, from(device)) != null) return true;
        }
        return false;
    }

    static Entry from(AudioDeviceInfo device) {
        if (device == null) return new Entry("", "", "", KIND_OTHER);
        String kind = kindOf(device.getType());
        String name = nameOf(device);
        String address = addressOf(device);
        return new Entry(identity(kind, name, address), name, address, kind);
    }

    /** Finds the remembered row that covers {@code probe}, or null. */
    static Entry find(List<Entry> trusted, Entry probe) {
        if (trusted == null || probe == null) return null;
        for (Entry entry : trusted) {
            if (matches(entry, probe)) return entry;
        }
        return null;
    }

    /**
     * Both sides with an address must agree on it; a same-named pair of earbuds
     * must not inherit trust. Otherwise the product name decides. A remembered
     * device that never had any identity of its own stands for its whole kind
     * (typically generic wired headsets), while a specific entry is rejected
     * when the current device cannot be identified at all.
     */
    private static boolean matches(Entry stored, Entry current) {
        if (stored == null || current == null) return false;
        if (!stored.kind.equals(current.kind)) return false;
        if (!stored.address.isEmpty() && !current.address.isEmpty()) {
            return stored.address.equalsIgnoreCase(current.address);
        }
        if (!stored.name.isEmpty() && !current.name.isEmpty()) {
            return stored.name.equalsIgnoreCase(current.name);
        }
        // No comparable identity: only an entry that was generic to begin with
        // may cover every device of this kind.
        return stored.address.isEmpty() && stored.name.isEmpty();
    }

    private static boolean contains(List<Entry> list, Entry entry) {
        for (Entry existing : list) {
            if (matches(existing, entry)) return true;
        }
        return false;
    }

    static boolean isHeadsetType(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_HEARING_AID:
                return true;
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
            default:
                return false;
        }
    }

    private static String kindOf(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_HEARING_AID:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                return KIND_BLUETOOTH;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return KIND_WIRED;
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return KIND_USB;
            default:
                return KIND_OTHER;
        }
    }

    private static String identity(String kind, String name, String address) {
        if (address != null && !address.isEmpty()) {
            return kind + "@" + address.toUpperCase(Locale.US);
        }
        if (name != null && !name.isEmpty()) {
            return kind + "#" + name.toLowerCase(Locale.US);
        }
        return kind + "#*";
    }

    private static String nameOf(AudioDeviceInfo device) {
        try {
            CharSequence product = device.getProductName();
            if (product != null) {
                String name = product.toString().trim();
                if (!name.isEmpty()) return name;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return "";
    }

    private static String addressOf(AudioDeviceInfo device) {
        try {
            String address = device.getAddress();
            if (address != null) {
                String value = address.trim();
                if (!value.isEmpty()
                        && !"02:00:00:00:00:00".equals(value)
                        && !"00:00:00:00:00:00".equals(value)) {
                    return value.toUpperCase(Locale.US);
                }
            }
        } catch (Throwable ignored) {
            // no address: name matching takes over
        }
        return "";
    }
}
