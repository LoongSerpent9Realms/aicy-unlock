package dev.loong.aicyunlock;

import android.content.Context;
import android.media.AudioManager;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Media-volume commands spoken or typed into Aicy.
 *
 * <p>Aicy's media_control tool has no volume action and a skill script runs in
 * a proot sandbox that cannot reach Android services, so the stream is changed
 * here, inside Aicy's process, which already holds MODIFY_AUDIO_SETTINGS. The
 * {@code volume-control} skill is the visible trigger: the request is staged
 * when the sentence is sent and applied once Aicy reports that skill as
 * invoked; a fallback fires shortly after the send in case it is not recalled.
 */
final class VolumeGate {

    interface Reporter {
        void report(String message, Throwable error);
    }

    static final String SKILL_ID = "volume-control";

    private static final long DUPLICATE_MS = 8_000L;
    private static final long FALLBACK_MS = 10_000L;
    private static final long STAGE_TTL_MS = 60_000L;

    private static final int SET = 1;
    private static final int UP = 2;
    private static final int DOWN = 3;
    private static final int MUTE = 4;
    private static final int UNMUTE = 5;
    private static final int MAX = 6;
    private static final int MIN = 7;

    private static final Pattern TRAILING = Pattern.compile("[。.！!？?，,、~～\\s]+$");
    private static final Pattern LEADING = Pattern.compile(
            "^(?:请问|请|帮我|帮忙|麻烦|给我|把|将|手机|一下)+\\s*");
    private static final Pattern VOLUME_WORD = Pattern.compile(
            "音量|声音|大声|小声|响声|响一点|静音");
    /** Call, ringtone and alarm volume use different streams; leave those alone. */
    private static final Pattern OTHER_STREAM = Pattern.compile(
            "通话|来电|铃声|闹钟|闹铃|听筒");
    /** Information questions ("为什么音量是50") must not turn into commands. */
    private static final Pattern QUESTION = Pattern.compile(
            "为什么|为啥|多少|是不是|有没有|什么|怎么|如何|能不能|可不可以|会不会");
    private static final Pattern IMPERATIVE = Pattern.compile(
            "调到|调成|调为|调至|设置为|设为|设置|设置成|开到|改成"
                    + "|调大|调小|调高|调低|提高|降低|增大|减小"
                    + "|加|减|大一点|小一点|大点|小点|大声点|小声点"
                    + "|静音|取消|关闭|最大|最小|归零|减半|一半");

    private static final Pattern UNMUTE_WORD = Pattern.compile(
            "(?:取消|关闭|关掉|解除|退出).{0,3}静音|静音.{0,3}(?:取消|关闭|解除|退出)");
    private static final Pattern NEGATED_MUTE = Pattern.compile(
            "(?:不要|别|不用|无需|不准).{0,3}静音");
    private static final Pattern CLOSE_SOUND = Pattern.compile(
            "关闭(?:声音|音量)|关掉(?:声音|音量)");
    private static final Pattern MAX_WORD = Pattern.compile("最大|最高|最响|开满|满音量");
    private static final Pattern MIN_WORD = Pattern.compile("最小|最低|最轻");

    private static final String NUMBER = "(?:[0-9]{1,3}|[零〇一二两三四五六七八九十百]+)";
    private static final String RELATIVE_VERB =
            "增加|加大|调高|提高|调大|增大|加上|加|减少|减小|调低|降低|调小|关小|减去|减";
    private static final Pattern RELATIVE = Pattern.compile(
            "(?:" + RELATIVE_VERB + ")\\s*(?:百分之\\s*)?(" + NUMBER + ")\\s*[%％]?");
    private static final Pattern RELATIVE_VERBS = Pattern.compile(RELATIVE_VERB);
    private static final Pattern DOWN_RELATIVE = Pattern.compile(
            "减少|减小|调低|降低|调小|关小|减去|减");
    private static final Pattern PERCENT = Pattern.compile("百分之\\s*(" + NUMBER + ")");
    private static final Pattern SET_NUMBER = Pattern.compile(
            "(?:调到|调为|调成|调至|设置为|设为|设置|设成|设至|开到|开为|开至|加到"
                    + "|改成|改到|改至|变成|变到|降到|减到|到|至|为)"
                    + "\\s*(?:为|成|到|至)?\\s*(?:百分之\\s*)?(" + NUMBER + ")");
    private static final Pattern BARE_DIGITS = Pattern.compile("([0-9]{1,3})\\s*[%％]?");
    private static final Pattern BARE_CHINESE =
            Pattern.compile("([零〇一二两三四五六七八九十百]+)");
    private static final Pattern HALF = Pattern.compile("一半");
    private static final Pattern DOWN_WORD = Pattern.compile(
            "调小|减小|关小|降低|调低|小一点|小点|小一些|小声|低一点|低些|开小");
    private static final Pattern UP_WORD = Pattern.compile(
            "调大|增大|加大|提高|调高|大一点|大点|大一些|大声|响一点|响点|高一点|高些|开大");

    private static volatile Reporter reporter = (m, e) -> { };
    /** Level to restore on 取消静音; -1 until a nonzero level is muted. */
    private static volatile int sUnmuteLevel = -1;

    private static String sStagedText = "";
    private static long sStagedAt;
    private static Pending sPending;

    private VolumeGate() {
    }

    static void attach(Reporter sink) {
        if (sink != null) reporter = sink;
    }

    static synchronized void stage(String text) {
        if (text == null) return;
        String raw = text.trim();
        if (raw.isEmpty() || !RemoteConfig.master()) return;
        Action action = parse(raw);
        if (action == null) return;

        long now = System.currentTimeMillis();
        if (raw.equals(sStagedText) && now - sStagedAt < DUPLICATE_MS) return;

        Pending pending = new Pending(action, now);
        sPending = pending;
        sStagedText = raw;
        sStagedAt = now;
        reporter.report("staged " + action + " text=" + clip(raw), null);
        scheduleFallback(pending);
    }

    static boolean dispatchPending() {
        Pending pending;
        synchronized (VolumeGate.class) {
            pending = sPending;
            sPending = null;
        }
        return apply(pending);
    }

    private static synchronized Pending takeIfCurrent(Pending expected) {
        if (sPending != expected) return null;
        sPending = null;
        return expected;
    }

    private static boolean apply(Pending pending) {
        if (pending == null) return false;
        if (System.currentTimeMillis() - pending.at > STAGE_TTL_MS) return false;
        Context context = RemoteConfig.app();
        if (context == null) {
            reporter.report("no context, volume request dropped", null);
            return false;
        }
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) {
            reporter.report("no AudioManager", null);
            return false;
        }
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (max <= 0) max = 15;
        int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int target = target(pending.action, current, max);
        if (target < 0) target = 0;
        if (target > max) target = max;
        if (target == 0 && current > 0) sUnmuteLevel = current;
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target,
                    AudioManager.FLAG_SHOW_UI);
        } catch (Throwable error) {
            reporter.report("setStreamVolume failed", error);
            return false;
        }
        reporter.report("apply " + pending.action + " " + current + " -> " + target
                + " (max " + max + ")", null);
        return true;
    }

    private static int target(Action action, int current, int max) {
        int step = Math.max(1, Math.round(max / 10f));
        switch (action.kind) {
            case SET:
                return Math.round(action.value / 100f * max);
            case MAX:
                return max;
            case MIN:
            case MUTE:
                return 0;
            case UNMUTE:
                return sUnmuteLevel > 0 ? sUnmuteLevel
                        : Math.max(1, Math.round(max * 0.5f));
            case UP:
                return current + (action.value > 0 ? delta(action.value, max) : step);
            case DOWN:
                return current - (action.value > 0 ? delta(action.value, max) : step);
            default:
                return current;
        }
    }

    private static int delta(int percent, int max) {
        return Math.max(1, Math.round(percent / 100f * max));
    }

    private static void scheduleFallback(Pending pending) {
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(FALLBACK_MS);
            } catch (InterruptedException ignored) {
                return;
            }
            apply(takeIfCurrent(pending));
        }, "aicy-volume-fallback");
        worker.setDaemon(true);
        worker.start();
    }

    private static Action parse(String raw) {
        String text = normalize(raw);
        text = TRAILING.matcher(text).replaceAll("");
        if (!VOLUME_WORD.matcher(text).find()) return null;
        if (OTHER_STREAM.matcher(text).find()) return null;
        text = LEADING.matcher(text).replaceFirst("");
        if (QUESTION.matcher(text).find() && !IMPERATIVE.matcher(text).find()) return null;

        if (text.contains("静音") || CLOSE_SOUND.matcher(text).find()) {
            if (UNMUTE_WORD.matcher(text).find()) return new Action(UNMUTE, 0);
            if (NEGATED_MUTE.matcher(text).find()) return null;
            return new Action(MUTE, 0);
        }
        if (MAX_WORD.matcher(text).find()) return new Action(MAX, 0);
        if (MIN_WORD.matcher(text).find()) return new Action(MIN, 0);
        if (text.contains("归零") || text.contains("清零")) return set(0);
        if (text.contains("减半")) return new Action(DOWN, 50);

        Matcher half = HALF.matcher(text);
        if (half.find()) {
            String before = text.substring(0, half.start());
            Matcher verb = RELATIVE_VERBS.matcher(before);
            if (verb.find()) {
                boolean down = DOWN_RELATIVE.matcher(verb.group()).find();
                return new Action(down ? DOWN : UP, 50);
            }
            if (before.contains("小")) return new Action(DOWN, 50);
            if (before.contains("大")) return new Action(UP, 50);
            return set(50);
        }
        Matcher relative = RELATIVE.matcher(text);
        if (relative.find()) {
            Integer value = number(relative.group(1));
            if (value != null && value > 0) {
                boolean down = DOWN_RELATIVE.matcher(relative.group()).find();
                return new Action(down ? DOWN : UP, Math.min(100, value));
            }
        }
        Matcher percent = PERCENT.matcher(text);
        if (percent.find()) {
            Integer value = number(percent.group(1));
            if (value != null) return set(value);
        }
        Matcher explicit = SET_NUMBER.matcher(text);
        if (explicit.find()) {
            Integer value = number(explicit.group(1));
            if (value != null) return set(value);
        }
        Matcher digits = BARE_DIGITS.matcher(text);
        if (digits.find()) {
            Integer value = number(digits.group(1));
            if (value != null) return set(value);
        }
        Matcher chinese = BARE_CHINESE.matcher(text);
        if (chinese.find()) {
            Integer value = number(chinese.group(1));
            // "一点"/"一些" are degree words, not a target level.
            if (value != null && value >= 10) return set(value);
        }
        if (DOWN_WORD.matcher(text).find()) return new Action(DOWN, 0);
        if (UP_WORD.matcher(text).find()) return new Action(UP, 0);
        return null;
    }

    private static Action set(int value) {
        return new Action(SET, Math.max(0, Math.min(100, value)));
    }

    /** Full-width digits and percent signs come out of Chinese IMEs. */
    private static String normalize(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '０' && c <= '９') {
                c = (char) ('0' + (c - '０'));
            } else if (c == '％') {
                c = '%';
            }
            out.append(c);
        }
        return out.toString().trim();
    }

    private static Integer number(String token) {
        if (token == null || token.isEmpty()) return null;
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException ignored) {
            // Chinese numerals below
        }
        int result = 0;
        int current = 0;
        boolean seen = false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            int digit = digitOf(c);
            if (digit >= 0) {
                current = digit;
                seen = true;
            } else if (c == '十' || c == '百') {
                int unit = c == '十' ? 10 : 100;
                if (current == 0) current = 1;
                result += current * unit;
                current = 0;
                seen = true;
            } else {
                return null;
            }
        }
        return seen ? result + current : null;
    }

    private static int digitOf(char c) {
        switch (c) {
            case '零':
            case '〇':
                return 0;
            case '一':
                return 1;
            case '二':
            case '两':
                return 2;
            case '三':
                return 3;
            case '四':
                return 4;
            case '五':
                return 5;
            case '六':
                return 6;
            case '七':
                return 7;
            case '八':
                return 8;
            case '九':
                return 9;
            default:
                return -1;
        }
    }

    private static String clip(String text) {
        if (text == null) return "";
        return text.length() <= 120 ? text : text.substring(0, 120) + "…";
    }

    private static final class Action {
        final int kind;
        final int value;

        Action(int kind, int value) {
            this.kind = kind;
            this.value = value;
        }

        @Override
        public String toString() {
            switch (kind) {
                case SET: return "set " + value + "%";
                case UP: return value > 0 ? "up " + value + "%" : "up";
                case DOWN: return value > 0 ? "down " + value + "%" : "down";
                case MUTE: return "mute";
                case UNMUTE: return "unmute";
                case MAX: return "max";
                case MIN: return "min";
                default: return "?";
            }
        }
    }

    private static final class Pending {
        final Action action;
        final long at;

        Pending(Action action, long at) {
            this.action = action;
            this.at = at;
        }
    }
}
