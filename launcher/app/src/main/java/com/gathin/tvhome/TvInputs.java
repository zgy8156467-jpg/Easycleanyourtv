package com.gathin.tvhome;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Android 5.0's TV input framework. Kept out of MainActivity so a 4.x Dalvik never has to resolve a
 * class that does not exist there; the caller checks {@link #supported()} first.
 */
@TargetApi(21)   // only reached from MainActivity behind TvInputs.supported()
final class TvInputs {

    static final class Item {
        final String id;
        final String label;
        final boolean hdmi;
        /** -1 when the port state is unknown or the firmware reports every port as connected. */
        final int state;

        Item(String id, String label, boolean hdmi, int state) {
            this.id = id;
            this.label = label;
            this.hdmi = hdmi;
            this.state = state;
        }
    }

    static final class Listing {
        final List<Item> items = new ArrayList<>();
        String tunerId;
    }

    private TvInputs() {
    }

    static boolean supported() {
        return android.os.Build.VERSION.SDK_INT >= 21;
    }

    static Listing list(Context ctx) {
        Listing out = new Listing();
        TvInputManager tim;
        try {
            tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        } catch (RuntimeException e) {
            return out;
        }
        if (tim == null) return out;
        // tv_input can be down (system restarted, service died) while the manager still exists; the
        // binder is then null and every call throws NPE. That used to take the whole launcher down.
        try {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI;
                boolean hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(ctx))
                        .compareToIgnoreCase(String.valueOf(b.loadLabel(ctx)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (out.tunerId == null || info.getId().compareTo(out.tunerId) < 0)) {
                    out.tunerId = info.getId();
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(ctx);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(info.getId());
                out.items.add(new Item(info.getId(), label, hdmi, state));
            }
        } catch (RuntimeException e) {
            android.util.Log.w("TvHome", "tv input service unavailable: " + e);
        }
        return out;
    }

    /** Channel URI for one passthrough input, used both by the row and by the Live TV shortcut. */
    static Intent viewIntent(String id) {
        return new Intent(Intent.ACTION_VIEW).setDataAndType(
                TvContract.buildChannelUriForPassthroughInput(id), "vnd.android.cursor.item/channel");
    }
}
