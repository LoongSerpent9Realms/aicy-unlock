package dev.loong.aicyunlock.power;

import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Runs the confirmed power action.
 *
 * <p>Three tiers, narrowest first:
 * <ol>
 *   <li><b>Temporary-root mode</b> - the device is only rooted for the session
 *       and might not survive a cold cycle, so a shutdown request is refused
 *       and a reboot request is downgraded to a soft reboot (framework only,
 *       the kernel never goes down).</li>
 *   <li><b>Rehearsal</b> - the whole flow runs but nothing is sent.</li>
 *   <li><b>Armed</b> - Aicy's own system permission first, root shell after.</li>
 * </ol>
 */
public final class PowerActions {

    /** Restart the framework through init; never touches the kernel. */
    private static final String SOFT_REBOOT =
            "setprop ctl.restart zygote; sleep 2; kill -9 $(pidof system_server)";

    public static void execute(final Context context, final boolean reboot) {
        final boolean tempRoot = PowerPrefs.tempRoot(context);
        final boolean dryRun = PowerPrefs.dryRun(context);

        if (tempRoot && !reboot) {
            toast(context, "临时 root 模式：关机已拦截");
            return;
        }
        if (dryRun) {
            String what = tempRoot ? "软重启" : (reboot ? "重启" : "关机");
            toast(context, "已确认" + what + "（演练模式，未执行）");
            return;
        }
        if (tempRoot) {
            toast(context, "临时 root 模式：软重启中，请稍候");
            new Thread(PowerActions::softReboot, "power-soft").start();
            return;
        }
        if (trySystem(context, reboot)) return;
        new Thread(() -> runRoot(reboot), "power-exec").start();
    }

    private static boolean trySystem(Context context, boolean reboot) {
        try {
            if (reboot) {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm == null) return false;
                pm.reboot(null);
                return true;
            }
            Intent intent = new Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN");
            intent.putExtra("android.intent.extra.KEY_CONFIRM", false);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    private static void runRoot(boolean reboot) {
        String[][] commands = reboot
                ? new String[][]{{"reboot"}, {"svc power reboot"}}
                : new String[][]{{"reboot -p"}, {"svc power shutdown"}};
        for (String[] command : commands) {
            if (runSu(command[0])) return;
        }
    }

    /**
     * Soft reboot: init tears down zygote and starts it again, so every app is
     * restarted while the kernel, the ramdisk and the root solution stay up.
     * The fallback kills system_server, which init answers by respawning zygote.
     */
    private static void softReboot() {
        runSu(SOFT_REBOOT);
    }

    private static boolean runSu(String command) {
        Process process = null;
        try {
            process = start(command);
            drain(process);
            boolean finished = process.waitFor(7000,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                // The framework is already going down, so silence is expected.
                return true;
            }
            return process.exitValue() == 0;
        } catch (Throwable error) {
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    /**
     * Runs one root command and returns what it printed, or null when root
     * could not be reached at all.
     *
     * <p>The status code is deliberately not part of the answer: commands like
     * {@code am force-stop} on a system-uid package exit non-zero even when
     * they did exactly what was asked, so callers have to read the output.
     */
    public static String shell(String command) {
        Process process = null;
        try {
            process = start(command);
            String out = drain(process);
            process.waitFor(7000, java.util.concurrent.TimeUnit.MILLISECONDS);
            return out;
        } catch (Throwable error) {
            return null;
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static Process start(String command) throws IOException {
        return new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true).start();
    }

    private static String drain(Process process) throws IOException {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static void toast(Context context, String text) {
        try {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    private PowerActions() {
    }
}
