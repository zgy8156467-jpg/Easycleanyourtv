package com.fake.vendorhome;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

/** Stand-in for the vendor's idle carousel: any class containing "screensaver" is taken over. */
public class ScreenSaverActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView label = new TextView(this);
        label.setText("Stock screensaver (fake)");
        label.setTextSize(28);
        label.setTextColor(Color.WHITE);
        label.setGravity(Gravity.CENTER);
        label.setBackgroundColor(Color.rgb(10, 10, 60));
        setContentView(label);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(MainActivityUserMode.TAG, "screensaver resumed at " + System.currentTimeMillis());
    }
}
