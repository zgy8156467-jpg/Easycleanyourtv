package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Stands in for the desk on a phone. The desk itself is never built there: MainActivity.onResume
 * assumes the wall, the glass and the hero row all exist, so half-creating it would crash.
 */
public class PhoneNoticeActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(32);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xff111111);

        TextView title = new TextView(this);
        title.setText(R.string.phone_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText(R.string.phone_body);
        body.setTextColor(0xffbbbbbb);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, dp(16), 0, dp(32));
        root.addView(body);

        Button uninstall = new Button(this);
        uninstall.setText(R.string.phone_uninstall);
        uninstall.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_DELETE,
                        Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException ignored) {
            }
            finish();
        });
        root.addView(uninstall, new LinearLayout.LayoutParams(
                dp(200), ViewGroup.LayoutParams.WRAP_CONTENT));

        Button close = new Button(this);
        close.setText(R.string.phone_exit);
        close.setOnClickListener(v -> finish());
        root.addView(close, new LinearLayout.LayoutParams(
                dp(200), ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
