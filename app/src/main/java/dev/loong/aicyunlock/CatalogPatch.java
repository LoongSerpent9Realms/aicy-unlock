package dev.loong.aicyunlock;

/**
 * Rewrites Aicy's model catalog JSON on the fly.
 *
 * <p>The "选择模型" sheet is driven entirely by a catalog document of the shape
 * {@code {"data":{"content":{"scenarios":[{"scenario":"chat","default_tier_id":…,
 * "tiers":[{"tier_id":"chat-lite","name":"轻量",…}]}],"models":[…]}}}}}. That
 * document ships as {@code assets/model_catalog_builtin.json} and is cached
 * verbatim under the {@code model_catalog_remote_json} key, so both are patched
 * by string surgery here — no obfuscated class names involved.
 *
 * <p>Two edits:
 * <ul>
 *   <li>{@code default_tier_id}: {@code chat-auto} → {@code chat-lite}, so a
 *       fresh session starts on the free "轻量" tier.</li>
 *   <li>clone the free tier object into a {@code chat-custom} tier (name
 *       "自定义"). The clone keeps <em>every</em> field the shipped tier had, so
 *       the sheet renders it exactly like a built-in option even when the
 *       remote catalog carries fields the builtin one does not.</li>
 * </ul>
 */
final class CatalogPatch {

    static final String AUTO_TIER_ID = "chat-auto";
    static final String DEEP_TIER_ID = "chat-deep";
    static final String CUSTOM_TIER_ID = "chat-custom";
    static final String FREE_TIER_ID = "chat-lite";
    static final String CUSTOM_NAME = "自定义";

    private CatalogPatch() {
    }

    /** Cheap shape check so we never touch unrelated JSON. */
    static boolean looksLikeCatalog(String text) {
        return text != null
                && text.length() > 64
                && text.contains("\"default_tier_id\"")
                && text.contains("\"tiers\"");
    }

    /**
     * @param defaultTier user's preferred default tier id, or null to leave the
     *                   catalog's own default untouched.
     */
    static String patch(String json, String modelId, String host, String defaultTier) {
        if (!looksLikeCatalog(json)) return json;
        String out = json;
        // The custom tier only exists when BYOK is configured (modelId != null);
        // injecting it otherwise would point the sheet at a missing model.
        if (modelId != null && !out.contains("\"" + CUSTOM_TIER_ID + "\"")) {
            out = injectCustomTier(out, modelId, host);
        }
        out = forceDefaultTier(out, defaultTier);
        return out;
    }

    /** Number of chat tiers in a (possibly patched) catalog, for diagnostics. */
    static int tierCount(String json) {
        if (json == null) return -1;
        int anchor = tierAnchor(json, FREE_TIER_ID);
        if (anchor < 0) return -1;
        int open = json.lastIndexOf('{', anchor);
        int close = open < 0 ? -1 : match(json, open, '{', '}');
        if (close < 0) return -1;
        int tiers = json.lastIndexOf("\"tiers\"", open);
        if (tiers < 0) return -1;
        int bracket = json.indexOf('[', tiers);
        int end = bracket < 0 ? -1 : match(json, bracket, '[', ']');
        if (end < 0) return -1;
        int count = 0;
        int from = bracket;
        while (true) {
            int next = json.indexOf("\"tier_id\"", from + 1);
            if (next < 0 || next > end) break;
            count++;
            from = next;
        }
        return count;
    }

    /**
     * Rewrites a persisted tier id (session state, "last used" pref) so an old
     * vendor default does not win over the catalog default the user picked.
     *
     * @param defaultTier the user's preferred default, or null for "leave the
     *                   vendor default alone".
     */
    static String forceFreeTierId(String value, String defaultTier) {
        if (value == null || defaultTier == null) return value;
        // Only the vendor default (chat-auto / auto) is remapped; an explicit
        // user selection of any other tier is respected.
        if (!value.contains("chat-auto") && !"auto".equals(value)) return value;
        if (CUSTOM_TIER_ID.equals(defaultTier)) return value;
        return defaultTier;
    }

    // ------------------------------------------------------------------
    // default tier
    // ------------------------------------------------------------------

    /**
     * Forces {@code default_tier_id} to {@code defaultTier}. When {@code
     * defaultTier} is null the catalog keeps its own (vendor) default. The
     * rewrite is idempotent: if the value is already the preferred tier, nothing
     * changes, so a cached catalog already rewritten to the preferred tier is not
     * churned on every read.
     */
    private static String forceDefaultTier(String json, String defaultTier) {
        if (defaultTier == null) return json;
        // Only the chat scenario uses the selectable tiers (chat-auto /
        // chat-deep / chat-lite / chat-custom). The other scenarios
        // (image-generation, video-generation, image-edit, …) carry their own
        // default_tier_id and must be left alone — otherwise their sheet would
        // default to a tier id that does not exist in that scenario and could
        // fail to open. So rewrite only the default_tier_id inside the chat
        // scenario block.
        int chat = json.indexOf("\"scenario\":\"chat\"");
        if (chat < 0) {
            // No chat marker (unusual): only touch the very first occurrence,
            // which for a chat-first catalog is the one we want.
            return rewriteFirstDefaultTierIdInRange(json, defaultTier, 0, json.length());
        }
        int nextScenario = json.indexOf("\"scenario\"", chat + 10);
        int bound = nextScenario < 0 ? json.length() : nextScenario;
        return rewriteFirstDefaultTierIdInRange(json, defaultTier, chat, bound);
    }

    /** Rewrites the first {@code default_tier_id} value within [start, end). */
    private static String rewriteFirstDefaultTierIdInRange(
            String json, String defaultTier, int start, int end) {
        int from = start;
        while (true) {
            int key = json.indexOf("\"default_tier_id\"", from);
            if (key < 0 || key >= end) return json;
            int colon = json.indexOf(':', key);
            if (colon < 0 || colon >= end) return json;
            int quote = json.indexOf('"', colon);
            if (quote < 0 || quote >= end) return json;
            int close = json.indexOf('"', quote + 1);
            if (close < 0 || close >= end) return json;
            String current = json.substring(quote + 1, close);
            if (defaultTier.equals(current)) return json; // already set
            return json.substring(0, quote + 1) + defaultTier + json.substring(close);
        }
    }

    // ------------------------------------------------------------------
    // custom tier
    // ------------------------------------------------------------------

    private static String injectCustomTier(String json, String modelId, String host) {
        String desc = host == null ? "使用你自己配置的接口" : "使用你自己配置的接口 · " + host;

        // Field parity matters more than a pretty object: clone whatever the
        // shipped free tier looks like (remote 2.x may carry extra fields the
        // builtin 2.0 does not) and only swap the identity fields.
        String base = tierObject(json, FREE_TIER_ID);
        if (base == null) base = tierObjectLast(json);

        String tier;
        if (base != null) {
            String model = firstStringInField(base, "model_ids");
            if (model == null) model = modelId;
            tier = setFieldString(base, "tier_id", CUSTOM_TIER_ID);
            tier = setFieldString(tier, "name", CUSTOM_NAME);
            tier = setFieldString(tier, "desc", desc);
            if (model != null) {
                tier = setFieldRaw(tier, "model_ids", "[\"" + esc(model) + "\"]");
            }
            tier = setFieldRaw(tier, "sort", "9");
        } else {
            // No free tier found at all — fall back to a hand-built object.
            tier = "{"
                    + "\"tier_id\":\"" + CUSTOM_TIER_ID + "\","
                    + "\"name\":\"" + CUSTOM_NAME + "\","
                    + "\"desc\":\"" + esc(desc) + "\","
                    + "\"cost_type\":\"free\","
                    + "\"rate\":0,"
                    + "\"sort\":9,"
                    + "\"model_ids\":[\"" + esc(modelId == null ? FREE_TIER_ID : modelId) + "\"]"
                    + "}";
        }

        return appendTier(json, tier);
    }

    /** Extracts the object literal of the tier whose {@code tier_id} is {@code id}. */
    private static String tierObject(String json, String id) {
        int anchor = tierAnchor(json, id);
        return anchor < 0 ? null : objectAround(json, anchor);
    }

    /** Extracts the object literal of the last tier in the chat scenario. */
    private static String tierObjectLast(String json) {
        int anchor = tierAnchor(json, FREE_TIER_ID);
        int scanFrom = anchor < 0 ? 0 : anchor;
        int last = -1;
        int from = scanFrom;
        while (true) {
            int k = json.indexOf("\"tier_id\"", from);
            if (k < 0) break;
            last = k;
            from = k + 1;
        }
        return last < 0 ? null : objectAround(json, last);
    }

    private static String objectAround(String json, int index) {
        int open = json.lastIndexOf('{', index);
        if (open < 0) return null;
        int close = match(json, open, '{', '}');
        if (close < 0) return null;
        return json.substring(open, close + 1);
    }

    // ------------------------------------------------------------------
    // field surgery on a single JSON object literal
    // ------------------------------------------------------------------

    /** Index of {@code "key"} inside {@code obj} that is followed by a colon. */
    private static int fieldKey(String obj, String key) {
        String needle = "\"" + key + "\"";
        int from = 0;
        while (true) {
            int k = obj.indexOf(needle, from);
            if (k < 0) return -1;
            int i = k + needle.length();
            while (i < obj.length() && Character.isWhitespace(obj.charAt(i))) i++;
            if (i < obj.length() && obj.charAt(i) == ':') return k;
            from = k + 1;
        }
    }

    /** Index just past the value that starts after the key's colon. */
    private static int valueEnd(String obj, int keyIndex) {
        int colon = obj.indexOf(':', keyIndex);
        if (colon < 0) return -1;
        int i = colon + 1;
        while (i < obj.length() && Character.isWhitespace(obj.charAt(i))) i++;
        if (i >= obj.length()) return -1;
        char c = obj.charAt(i);
        if (c == '"') {
            int j = i + 1;
            while (j < obj.length()) {
                char d = obj.charAt(j);
                if (d == '\\') { j += 2; continue; }
                if (d == '"') return j + 1;
                j++;
            }
            return -1;
        }
        if (c == '[' || c == '{') {
            int end = match(obj, i, c, c == '[' ? ']' : '}');
            return end < 0 ? -1 : end + 1;
        }
        int j = i;
        while (j < obj.length() && ",}] \t\r\n".indexOf(obj.charAt(j)) < 0) j++;
        return j;
    }

    private static String setFieldString(String obj, String key, String value) {
        int k = fieldKey(obj, key);
        if (k < 0) {
            // Insert right after the opening brace so the key exists.
            return "{\"" + key + "\":\"" + esc(value) + "\","
                    + obj.substring(obj.indexOf('{') + 1);
        }
        int end = valueEnd(obj, k);
        if (end < 0) return obj;
        int start = obj.indexOf(':', k) + 1;
        return obj.substring(0, start) + "\"" + esc(value) + "\"" + obj.substring(end);
    }

    private static String setFieldRaw(String obj, String key, String raw) {
        int k = fieldKey(obj, key);
        if (k < 0) {
            return "{\"" + key + "\":" + raw + "," + obj.substring(obj.indexOf('{') + 1);
        }
        int end = valueEnd(obj, k);
        if (end < 0) return obj;
        int start = obj.indexOf(':', k) + 1;
        return obj.substring(0, start) + raw + obj.substring(end);
    }

    /** First string element of a string-array field inside {@code obj}. */
    private static String firstStringInField(String obj, String key) {
        int k = fieldKey(obj, key);
        if (k < 0) return null;
        int bracket = obj.indexOf('[', k);
        if (bracket < 0) return null;
        int quote = obj.indexOf('"', bracket);
        if (quote < 0) return null;
        int end = obj.indexOf('"', quote + 1);
        if (end < 0) return null;
        return obj.substring(quote + 1, end);
    }

    // ------------------------------------------------------------------
    // container helpers
    // ------------------------------------------------------------------

    /**
     * Inserts {@code tier} right after the free tier object of the chat
     * scenario. Anchors on the {@code "tier_id": "chat-lite"} pair, not on the
     * id alone: {@code fallback_free_tier_id} holds the same id earlier in the
     * document.
     */
    private static String appendTier(String json, String tier) {
        int anchor = tierAnchor(json, FREE_TIER_ID);
        if (anchor < 0) return json;
        int open = json.lastIndexOf('{', anchor);
        if (open < 0) return json;
        int close = match(json, open, '{', '}');
        if (close < 0) return json;
        return json.substring(0, close + 1) + ",\n" + tier + json.substring(close + 1);
    }

    /**
     * Index of the {@code "tier_id"} key whose value is {@code id}. Must be
     * matched by key/value pair: {@code fallback_free_tier_id} holds the same
     * id earlier in the document.
     */
    private static int tierAnchor(String json, String id) {
        int from = 0;
        while (true) {
            int k = json.indexOf("\"tier_id\"", from);
            if (k < 0) return -1;
            int colon = json.indexOf(':', k);
            if (colon < 0) return -1;
            int q1 = json.indexOf('"', colon);
            int q2 = q1 < 0 ? -1 : json.indexOf('"', q1 + 1);
            if (q2 < 0) return -1;
            if (id.equals(json.substring(q1 + 1, q2))) return k;
            from = q2 + 1;
        }
    }

    // ------------------------------------------------------------------
    // scanning helpers (string-literal aware)
    // ------------------------------------------------------------------

    private static int match(String text, int start, char open, char close) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static String esc(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
