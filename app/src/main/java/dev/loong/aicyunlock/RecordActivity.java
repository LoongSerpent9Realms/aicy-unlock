package dev.loong.aicyunlock;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * 录音界面：给某条音色录一段参考音。
 *
 * <p>为什么自己录 WAV 而不是用 MediaRecorder：MiMo 的复刻接口只接受 mp3 / wav，
 * 而 MediaRecorder 常见输出是 m4a/amr。AudioRecord 直接拿 PCM16，自己写 44 字节
 * 的 WAV 头即可，同时 16 kHz 单声道 15 秒 ≈ 480 KB，转 Base64 后仍能塞进
 * ContentProvider 的一行（binder 有上限）。
 */
public final class RecordActivity extends Activity {

    private static final int SAMPLE_RATE = 16000;
    private static final int MAX_SECONDS = 15;
    private static final int REQUEST_MIC = 2001;
    /** Which timbre the recording belongs to; see {@link VoiceProfile}. */
    static final String EXTRA_PROFILE_ID = "profile_id";

    private static final int ACCENT = 0xFF818CF8;
    private static final int TEXT = 0xFFE6E6E6;
    private static final int MUTED = 0xFF9BA0A6;
    private static final int WARN = 0xFFFBBF24;

    private TextView status;
    private TextView timer;
    private TextView action;
    private LinearLayout wave;

    private AudioRecord recorder;
    private Thread worker;
    private volatile boolean recording;
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private long startedAt;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!recording) return;
            int seconds = (int) ((System.currentTimeMillis() - startedAt) / 1000);
            if (timer != null) timer.setText(seconds + " s / " + MAX_SECONDS + " s");
            if (seconds >= MAX_SECONDS) {
                stop();
                return;
            }
            ui.postDelayed(this, 200);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF111113);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(34), dp(20), dp(28));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("录音 · 复刻音色");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("念 5–15 秒，正常语速、安静环境。录完会自动存成 16 kHz 单声道 WAV，"
                + "交给 MiMo 的 mimo-v2.5-tts-voiceclone 复刻。");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        sub.setTextColor(MUTED);
        sub.setLineSpacing(dp(3), 1f);
        sub.setPadding(0, dp(8), 0, dp(18));
        root.addView(sub);

        TextView script = new TextView(this);
        script.setText("照着念（约 12 秒）：\n\n"
                + "“今天天气不错，我把窗户打开，让风慢慢吹进来。"
                + "下午打算把没做完的事情收个尾，然后出门走一走，"
                + "顺便买点水果回来。”\n\n"
                + "念的时候保持平常说话的语速和音量，不要刻意压低或拖长，"
                + "中间别停顿太久——这段音频会决定合成出来的音色。");
        script.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        script.setTextColor(TEXT);
        script.setLineSpacing(dp(4), 1f);
        script.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable scriptBg = new GradientDrawable();
        scriptBg.setCornerRadius(dp(12));
        scriptBg.setColor(0xFF1C1C1E);
        script.setBackground(scriptBg);
        root.addView(script);

        timer = new TextView(this);
        timer.setText("0 s / " + MAX_SECONDS + " s");
        timer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f);
        timer.setTextColor(0xFFFFFFFF);
        timer.setGravity(Gravity.CENTER);
        timer.setPadding(0, dp(28), 0, dp(6));
        root.addView(timer);

        status = new TextView(this);
        status.setText("准备好了就点下面开始");
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        status.setTextColor(MUTED);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 0, 0, dp(18));
        root.addView(status);

        wave = new LinearLayout(this);
        wave.setOrientation(LinearLayout.HORIZONTAL);
        wave.setGravity(Gravity.CENTER);
        wave.setPadding(0, 0, 0, dp(20));
        root.addView(wave);
        buildWave();

        action = button("开始录音");
        action.setOnClickListener(v -> {
            if (recording) {
                stop();
            } else {
                start();
            }
        });
        root.addView(action);

        TextView back = button("返回设置");
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        root.addView(back, lp);
    }

    private void buildWave() {
        wave.removeAllViews();
        for (int i = 0; i < 24; i++) {
            View bar = new View(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(3));
            bg.setColor(0xFF2A2A34);
            bar.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(6), dp(18));
            lp.rightMargin = dp(4);
            wave.addView(bar, lp);
        }
    }

    private void start() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            return;
        }
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(min, 4096);
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                status.setText("麦克风被占用，先关掉其他录音应用");
                recorder.release();
                recorder = null;
                return;
            }
            pcm.reset();
            recorder.startRecording();
            recording = true;
            startedAt = System.currentTimeMillis();
            action.setText("停止并保存");
            status.setText("正在录音…");
            status.setTextColor(ACCENT);
            ui.post(ticker);
            worker = new Thread(this::pump, "AicyRecorder");
            worker.setDaemon(true);
            worker.start();
        } catch (Throwable error) {
            status.setText("启动录音失败：" + error.getClass().getSimpleName());
            status.setTextColor(WARN);
        }
    }

    private void pump() {
        byte[] buffer = new byte[4096];
        while (recording) {
            int read = recorder.read(buffer, 0, buffer.length);
            if (read <= 0) continue;
            synchronized (pcm) {
                pcm.write(buffer, 0, read);
                if (pcm.size() > SAMPLE_RATE * 2 * MAX_SECONDS) break;
            }
        }
    }

    private void stop() {
        if (!recording) return;
        recording = false;
        ui.removeCallbacks(ticker);
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
            }
        } catch (Throwable ignored) {
            // already released
        }
        recorder = null;

        byte[] raw;
        synchronized (pcm) {
            raw = pcm.toByteArray();
        }
        int seconds = raw.length / (SAMPLE_RATE * 2);
        if (raw.length < SAMPLE_RATE) {
            status.setText("太短了（不到 1 秒），再录一次");
            status.setTextColor(WARN);
            action.setText("重新录音");
            timer.setText("0 s / " + MAX_SECONDS + " s");
            return;
        }
        try {
            byte[] wav = wav(raw);
            save(wav);
            timer.setText(seconds + " s");
            status.setText("已保存：" + seconds + " 秒 · " + (wav.length / 1024) + " KB，"
                    + "回 Aicy 选中「＋ 新建音色」即可");
            status.setTextColor(0xFF4ADE80);
            action.setText("重新录音");
        } catch (Throwable error) {
            status.setText("保存失败：" + error.getClass().getSimpleName());
            status.setTextColor(WARN);
        }
    }

    /** 44 字节标准 WAV 头 + PCM16 LE。 */
    private static byte[] wav(byte[] raw) {
        int dataLen = raw.length;
        int total = dataLen + 44;
        byte[] out = new byte[total];
        out[0] = 'R'; out[1] = 'I'; out[2] = 'F'; out[3] = 'F';
        putInt(out, 4, total - 8);
        out[8] = 'W'; out[9] = 'A'; out[10] = 'V'; out[11] = 'E';
        out[12] = 'f'; out[13] = 'm'; out[14] = 't'; out[15] = ' ';
        putInt(out, 16, 16);
        putShort(out, 20, (short) 1);
        putShort(out, 22, (short) 1);
        putInt(out, 24, SAMPLE_RATE);
        putInt(out, 28, SAMPLE_RATE * 2);
        putShort(out, 32, (short) 2);
        putShort(out, 34, (short) 16);
        out[36] = 'd'; out[37] = 'a'; out[38] = 't'; out[39] = 'a';
        putInt(out, 40, dataLen);
        System.arraycopy(raw, 0, out, 44, dataLen);
        return out;
    }

    private static void putInt(byte[] out, int at, int value) {
        out[at] = (byte) value;
        out[at + 1] = (byte) (value >> 8);
        out[at + 2] = (byte) (value >> 16);
        out[at + 3] = (byte) (value >> 24);
    }

    private static void putShort(byte[] out, int at, short value) {
        out[at] = (byte) value;
        out[at + 1] = (byte) (value >> 8);
    }

    private void save(byte[] wav) throws Exception {
        // 备份一份到应用目录，方便以后再取。备份留全长，存储的那份才裁。
        File file = new File(getExternalFilesDir(null), "voice-sample.wav");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(wav);
        } catch (Throwable ignored) {
            // 备份失败不影响主流程
        }

        // MiMo 每次请求都重发这段样本，没有 voice_id 可缓存：15 秒的原样上传要
        // 480 KB，光等响应头就3~5 秒。复刻只需要音色特征，裁到 4 秒（约 125 KB）。
        File work = new File(getExternalFilesDir(null), "recorded-clip.wav");
        byte[] stored = wav;
        try (FileOutputStream out = new FileOutputStream(work)) {
            out.write(wav);
        } catch (Throwable ignored) {
            // 落盘失败就直接用原始字节，裁剪在内存里还能做
        }
        ClipConverter.Result converted = ClipConverter.toWav(work);
        if (converted.ok()) {
            stored = converted.wav;
        }
        try {
            work.delete();
        } catch (Throwable ignored) {
            // nothing to do
        }

        String base64 = android.util.Base64.encodeToString(stored, android.util.Base64.NO_WRAP);

        // Attach to the timbre that opened this screen. Without a target we fall
        // back to the first profile, which is what a cold start means.
        String targetId = getIntent() == null ? null
                : getIntent().getStringExtra(EXTRA_PROFILE_ID);
        VoiceProfile profile = null;
        List<VoiceProfile> list = VoiceStore.load(this);
        for (VoiceProfile p : list) {
            if (targetId != null && targetId.equals(p.id)) {
                profile = p;
                break;
            }
        }
        if (profile == null && !list.isEmpty()) profile = list.get(0);
        if (profile == null) {
            Toast.makeText(this, "没有可写入的音色，请先在模块里新建一条", Toast.LENGTH_LONG).show();
            return;
        }
        String key = profile.sampleKey.isEmpty() ? "v" + System.currentTimeMillis()
                : profile.sampleKey;
        VoiceStore.putClip(this, key, base64);
        VoiceStore.putProfile(this, new VoiceProfile(profile.id, profile.name,
                MiMoTts.MODEL_CLONE, profile.desc, profile.presetVoice, key, profile.avatar));
        Toast.makeText(this, "参考音已保存到「" + profile.name + "」", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_MIC) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            start();
        } else {
            status.setText("没有麦克风权限，先用「选择音频」导入一个文件");
            status.setTextColor(WARN);
        }
    }

    @Override
    protected void onDestroy() {
        recording = false;
        ui.removeCallbacks(ticker);
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
            }
        } catch (Throwable ignored) {
            // already gone
        }
        recorder = null;
        super.onDestroy();
    }

    private TextView button(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setTextColor(0xFFEDEBFF);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(14), dp(10), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0xFF2A2A34);
        bg.setStroke(dp(1), 0xFF3F3F52);
        tv.setBackground(bg);
        return tv;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
