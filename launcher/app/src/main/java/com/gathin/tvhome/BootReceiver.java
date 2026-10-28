package com.gathin.tvhome;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;

/**
 * Xiaomi TVs refuse to make a third-party launcher the default HOME without root, so the stock
 * PatchWall still starts at boot. This puts the tvOS-style desk straight back on top afterwards.
 * The HOME intent goes through a PendingIntent because MIUI drops a plain startActivity from a
 * background receiver. A second try covers the case where PatchWall's window opens on top of the
 * first one; the service does the same from its side, so whichever starts first wins.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        DeskWatchService.start(app);
        // The revive alarm only needs the service back; it must not reopen the desk on top of
        // whatever the member is watching.
        if (DeskWatchService.TICK.equals(intent.getAction())) return;
        final Handler handler = new Handler(app.getMainLooper());
        for (long delay : new long[]{1500, 4000}) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Intent desk = new Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_HOME)
                            .setPackage(app.getPackageName())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    try {
                        PendingIntent.getActivity(app, 0, desk,
                                PendingIntent.FLAG_UPDATE_CURRENT).send();
                    } catch (PendingIntent.CanceledException ignored) {
                    }
                }
            }, delay);
        }
    }
}
