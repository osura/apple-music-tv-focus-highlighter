package com.osura.focushl;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Home-screen tile that opens Apple Music (a phone app with no TV launcher entry). */
public class OpenAppleMusic extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.apple.android.music");
        if (launch != null) startActivity(launch);
        finish();
    }
}
