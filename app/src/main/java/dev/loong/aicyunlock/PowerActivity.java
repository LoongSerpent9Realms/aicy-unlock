package dev.loong.aicyunlock;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;

import dev.loong.aicyunlock.power.PowerActions;
import dev.loong.aicyunlock.power.PowerSheet;

/**
 * Stand-alone host for the power slider.
 *
 * <p>Reached from the Aicy skill card: the card button is a {@code deep_link}
 * pointing at {@code aicyunlock://power-confirm?action=shutdown|reboot|cancel},
 * so the whole path (skill -> card -> this activity) needs no root at all.
 * The confirm button opens the very same {@link PowerSheet} the in-app hook
 * uses, so the look and feel do not change.</p>
 */
public final class PowerActivity extends Activity {

    private boolean executing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        String action = "shutdown";
        Uri uri = getIntent() == null ? null : getIntent().getData();
        if (uri != null) {
            String value = uri.getQueryParameter("action");
            if (value != null && !value.isEmpty()) action = value;
        }
        if ("cancel".equals(action)) {
            finish();
            return;
        }

        final boolean reboot = "reboot".equals(action);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final PowerSheet sheet = new PowerSheet(this, reboot, new Runnable() {
            @Override
            public void run() {
                executing = true;
                PowerActions.execute(getApplicationContext(), reboot);
                finish();
            }
        });
        // Called both when the user dismisses the sheet and right before the
        // perform callback fires. The short delay lets the perform callback
        // win the race when the slider was actually completed.
        sheet.setOnDismiss(new Runnable() {
            @Override
            public void run() {
                getWindow().getDecorView().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!executing) finish();
                    }
                }, 350L);
            }
        });

        setContentView(sheet.root);
        sheet.markShown();
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
