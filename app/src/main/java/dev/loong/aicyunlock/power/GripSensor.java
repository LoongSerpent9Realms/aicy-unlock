package dev.loong.aicyunlock.power;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Guesses which hand is holding the phone, the way the system's own
 * "smart grip" hint does.
 *
 * <p>There is no grip sensor: what the phone can actually feel is the pull of
 * gravity inside its own frame. Rotate the device clockwise while facing it and
 * the gravity vector swings toward +x, so
 *
 * <pre>roll = atan2(gx, gy)</pre>
 *
 * turns into a signed tilt angle in the screen plane. A phone lying on a table
 * reads near 0; a phone held one-handed leans, and the direction of the lean
 * follows the thumb that is doing the work.
 *
 * <p>One sample is useless - the hand wobbles - so the window is a few hundred
 * milliseconds and the answer is only accepted once the average settles. If the
 * phone is flat, in a mount, or being held level, the answer is FLAT and the
 * caller falls back to the strip under the status bar.
 */
public final class GripSensor {

    public static final int GRIP_FLAT = 0;
    public static final int GRIP_LEFT = 1;
    public static final int GRIP_RIGHT = 2;

    /** How long to watch before deciding. */
    private static final long WINDOW_MS = 380L;
    /** Below this tilt the phone is treated as level / set down. */
    private static final float FLAT_DEG = 9f;
    /** Smoothing on the incoming gravity vector. */
    private static final float ALPHA = 0.35f;
    /** A reading this fresh is reused instead of waking the sensor again. */
    private static final long CACHE_MS = 2500L;

    private static int cachedGrip = GRIP_FLAT;
    private static float cachedRoll;
    private static long cachedAt;

    public interface Callback {
        /**
         * @param grip   GRIP_FLAT / GRIP_LEFT / GRIP_RIGHT
         * @param roll   signed tilt in degrees, for the log
         */
        void onGrip(int grip, float roll);
    }

    private GripSensor() {
    }

    /**
     * Samples the accelerometer once and reports back on the main thread.
     *
     * <p>Battery: this is a single short window, taken only when the user opens
     * the slider and only when the position is set to follow the grip. The
     * listener is removed the moment the window closes, so nothing keeps
     * running in the background, and a reading under {@link #CACHE_MS} old is
     * reused rather than sampled again.
     */
    public static void detect(final Context context, final Callback callback) {
        if (callback == null) return;
        long age = SystemClock.uptimeMillis() - cachedAt;
        if (cachedAt != 0 && age >= 0 && age < CACHE_MS) {
            android.util.Log.i("AicyUnlock", "grip cached " + cachedGrip + " age=" + age);
            callback.onGrip(cachedGrip, cachedRoll);
            return;
        }
        final SensorManager manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (manager == null) {
            callback.onGrip(GRIP_FLAT, 0f);
            return;
        }
        Sensor pick = manager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        if (pick == null) pick = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (pick == null) {
            callback.onGrip(GRIP_FLAT, 0f);
            return;
        }
        final Sensor sensor = pick;

        final Handler main = new Handler(Looper.getMainLooper());
        final float[] gravity = new float[]{0f, 0f, 0f};
        final boolean[] primed = new boolean[]{false};
        final boolean[] done = new boolean[]{false};

        final SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (primed[0]) {
                    for (int i = 0; i < 3; i++) {
                        gravity[i] += (event.values[i] - gravity[i]) * ALPHA;
                    }
                } else {
                    System.arraycopy(event.values, 0, gravity, 0, 3);
                    primed[0] = true;
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };

        if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)) {
            callback.onGrip(GRIP_FLAT, 0f);
            return;
        }

        main.postDelayed(() -> {
            if (done[0]) return;
            done[0] = true;
            // Off the moment the window closes: no sampling while idle.
            try {
                manager.unregisterListener(listener);
            } catch (Throwable ignored) {
            }
            float gx = gravity[0];
            float gy = gravity[1];
            float gz = gravity[2];
            float roll = (float) Math.toDegrees(Math.atan2(gx, gy));
            int grip = classify(roll);
            android.util.Log.i("AicyUnlock", "grip raw gx=" + Math.round(gx)
                    + " gy=" + Math.round(gy) + " gz=" + Math.round(gz)
                    + " roll=" + Math.round(roll) + " -> " + grip
                    + " (" + sensor.getStringType() + ")");
            cachedGrip = grip;
            cachedRoll = roll;
            cachedAt = SystemClock.uptimeMillis();
            callback.onGrip(grip, roll);
        }, WINDOW_MS);
    }

    private static int classify(float roll) {
        if (roll > FLAT_DEG) return GRIP_RIGHT;
        if (roll < -FLAT_DEG) return GRIP_LEFT;
        return GRIP_FLAT;
    }
}
