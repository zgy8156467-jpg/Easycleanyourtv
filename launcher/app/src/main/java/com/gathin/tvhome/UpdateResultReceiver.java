package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/**
 * Result of the PackageInstaller session started by {@link Remote#install}. A successful install
 * replaces this app and kills the process, so only the failure and "your turn to tap" branches ever
 * run here.
 */
public class UpdateResultReceiver extends BroadcastReceiver {

    static final String ACTION = "com.gathin.tvhome.INSTALL_RESULT";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
        if (status == PackageInstaller.STATUS_SUCCESS) return;
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Some ROMs hand the confirmation dialog back to the app instead of showing it.
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                    return;
                } catch (Exception e) {
                    Errors.record("update", "install confirm: " + e.getClass().getSimpleName());
                }
            }
        } else {
            Errors.record("update", "install session " + status + ": "
                    + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
        Toast.makeText(context, R.string.update_install_failed, Toast.LENGTH_LONG).show();
    }
}
