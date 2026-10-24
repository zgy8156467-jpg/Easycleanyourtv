package com.fake.vendorhome;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

/**
 * PatchWall stand-in for emulator tests: same shape as the real thing as far as HomeKeyService and
 * DeviceProfile can tell — a CATEGORY_HOME activity named *UserMode in a vendor-launcher package.
 * Logs every resume so the takeover can be timed against it.
 */
public class MainActivityUserMode extends Activity {

    public static final String TAG = "FakeStockHome";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView label = new TextView(this);
        label.setText("Stock launcher (fake)\nMainActivityUserMode");
        label.setTextSize(28);
        label.setTextColor(Color.WHITE);
        label.setGravity(Gravity.CENTER);
        label.setBackgroundColor(Color.rgb(90, 10, 10));
        setContentView(label);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "resumed at " + System.currentTimeMillis());
    }
}
