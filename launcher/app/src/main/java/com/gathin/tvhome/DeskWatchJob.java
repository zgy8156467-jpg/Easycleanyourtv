package com.gathin.tvhome;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

/**
 * Boot path for {@link DeskWatchService} on firmware that filters BOOT_COMPLETED (LeTV EUI shipped
 * the desk without starting it after a reboot). A persisted periodic job lives in the system's
 * scheduler and survives reboots, so the service gets started even when the boot broadcast never
 * arrives. Runs every 15 minutes as a side effect, which also brings the service back if the vendor
 * cleaner killed it.
 */
public class DeskWatchJob extends JobService {
    private static final int JOB_ID = 0x4b2;

    static void schedule(Context ctx) {
        if (Build.VERSION.SDK_INT < 21) return;
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, DeskWatchJob.class))
                .setPersisted(true)
                .setPeriodic(15 * 60 * 1000L)
                .build();
        try {
            js.schedule(job);
        } catch (RuntimeException ignored) {
            // Some vendor schedulers reject persisted jobs; the boot receiver still covers those.
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        DeskWatchService.start(this);
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
