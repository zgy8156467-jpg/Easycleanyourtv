package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

/**
 * Shown on the launch after a fatal error. The TV has no log viewer, so the report has to be on the
 * screen: the user photographs it and sends it in.
 */
public class CrashActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crash);

        final String report = CrashLog.read(this);
        TextView text = findViewById(R.id.crashText);
        text.setText(report.isEmpty() ? getString(R.string.crash_empty) : report);

        // Send the report by itself: the group member usually just wants the desk back, and typing a
        // note with a remote is hopeless. Failure stays quiet — the photo is still the fallback.
        final TextView send = findViewById(R.id.crashSend);
        if (report.isEmpty()) {
            send.setVisibility(View.GONE);
        } else {
            send.setText(R.string.crash_sending);
            Remote.sendReport(getApplicationContext(), "crash", "", report,
                    (ok, message) -> send.setText(ok
                            ? R.string.crash_sent : R.string.crash_send_failed));
        }

        View again = findViewById(R.id.crashContinue);
        again.setOnClickListener(v -> {
            CrashLog.clear(this);
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
        again.requestFocus();
    }
}
