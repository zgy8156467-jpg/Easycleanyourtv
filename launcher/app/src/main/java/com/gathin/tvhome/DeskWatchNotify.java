package com.gathin.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;

/**
 * The Android 8+ half of {@link DeskWatchService}: starting a foreground service and its
 * notification. Kept apart so Dalvik on the 4.x legacy build never resolves NotificationChannel
 * or Context.startForegroundService — same reason {@link UsageScan} exists.
 */
final class DeskWatchNotify {
    private static final String CHANNEL = "desk_watch";
    private static final int ID = 0x4b1;

    private DeskWatchNotify() {
    }

    static void start(Context ctx) {
        try {
            ctx.startForegroundService(new Intent(ctx, DeskWatchService.class));
        } catch (RuntimeException ignored) {
            // Android 12+ refuses a foreground start from a plain background broadcast (the revive
            // alarm); the persisted job brings the service back instead.
        }
    }

    static void foreground(Service service) {
        NotificationManager nm =
                (NotificationManager) service.getSystemService(Service.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    service.getString(R.string.desk_watch_name), NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(service, 0,
                new Intent(service, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(service, CHANNEL)
                .setSmallIcon(R.drawable.ic_settings)
                .setContentTitle(service.getString(R.string.desk_watch_name))
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        service.startForeground(ID, n);
    }
}
