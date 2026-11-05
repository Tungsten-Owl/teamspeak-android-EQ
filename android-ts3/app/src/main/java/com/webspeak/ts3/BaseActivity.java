package com.webspeak.ts3;

import android.app.Activity;
import android.content.Context;

/** Applies the saved in-app language before the activity inflates resources. */
public abstract class BaseActivity extends Activity {
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLanguage.wrap(newBase));
    }
}
