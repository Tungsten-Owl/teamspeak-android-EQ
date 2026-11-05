package com.webspeak.ts3;

import android.app.Activity;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.TextView;

/**
 * Credits and the non-affiliation notice.
 *
 * <p>Two modes. As the launcher entry it is a gate: the reader scrolls through the
 * notice and swipes left to reach the connection screen, and the back button is
 * hidden because there is nothing behind this page. Opened from the room settings
 * it is a plain page that simply closes.
 *
 * <p>The swipe drags the page with the finger rather than waiting for a finished
 * gesture. Touch handling happens in {@link #dispatchTouchEvent} so the movement is
 * seen before the {@code ScrollView}, which is cancelled once the drag becomes
 * horizontal; until then every event is passed through untouched, so vertical
 * scrolling and link taps behave normally.
 */
public final class AboutActivity extends Activity {

    /** False when opened from the room settings: that visit is informational only. */
    public static final String EXTRA_GATE = "com.webspeak.ts3.extra.GATE";

    /** Fraction of the width that has to be dragged before the page commits. */
    private static final float COMMIT_FRACTION = 0.30f;

    private boolean gate;
    private View root;
    private int touchSlop;
    private float downX;
    private float downY;
    private float startTranslation;
    private boolean dragging;
    private boolean childCancelled;
    private boolean handedOff;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        gate = getIntent().getBooleanExtra(EXTRA_GATE, true);
        root = findViewById(R.id.about_root);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        // targetSdk 35 draws edge-to-edge, so the title bar would otherwise sit
        // under the status bar. The inset is 0 on versions that still inset the
        // window for us, which makes this a no-op there.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();

        TextView body = findViewById(R.id.about_body);
        body.setText(Html.fromHtml(getString(R.string.about_body, versionName()),
                Html.FROM_HTML_MODE_LEGACY));
        body.setMovementMethod(LinkMovementMethod.getInstance());

        Button back = findViewById(R.id.about_back);
        TextView hint = findViewById(R.id.about_hint);
        back.setVisibility(gate ? View.GONE : View.VISIBLE);
        hint.setVisibility(gate ? View.VISIBLE : View.GONE);
        back.setOnClickListener(view -> finish());
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (!gate) return super.dispatchTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                dragging = false;
                childCancelled = false;
                root.animate().cancel();
                // A spring-back interrupted mid-flight must not make the next drag
                // jump: the page continues from where it currently sits.
                startTranslation = root.getTranslationX();
                break;
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (!dragging) {
                    // Vertical movement stays with the ScrollView; only a mostly
                    // horizontal drag is claimed here.
                    if (Math.abs(dx) <= touchSlop || Math.abs(dx) <= Math.abs(dy)) break;
                    dragging = true;
                }
                if (!childCancelled) {
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    childCancelled = true;
                }
                // Leftward drags the page; rightward is resisted, since the gate
                // only ever lets the reader continue.
                float offset = startTranslation + dx;
                root.setTranslationX(offset < 0f ? offset : offset * 0.2f);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!dragging) break;
                boolean released = event.getActionMasked() == MotionEvent.ACTION_UP;
                settle(released);
                dragging = false;
                return true;
            }
            default:
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    /** Slides the page out and continues, or springs it back to where it started. */
    private void settle(boolean released) {
        float width = getResources().getDisplayMetrics().widthPixels;
        float offset = root.getTranslationX();
        if (released && offset < -width * COMMIT_FRACTION) {
            root.animate().translationX(-width).setDuration(140)
                    .withEndAction(this::proceed)
                    .start();
        } else {
            root.animate().translationX(0f).setDuration(160).start();
        }
    }

    private void proceed() {
        // The slide-out animation's end action can fire twice when it is cancelled
        // and restarted; the hand-off must happen exactly once.
        if (handedOff) return;
        handedOff = true;
        // The connection form is already drawn behind this translucent card, so
        // dismissing the overlay is what reveals it; nothing is started here.
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    public void onBackPressed() {
        // As a gate the page has nothing behind it to return to; leaving the app is
        // what back meant before the card was an overlay.
        if (gate) {
            finishAffinity();
            return;
        }
        super.onBackPressed();
    }

    private String versionName() {
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return name == null ? "?" : name;
        } catch (Exception error) {
            return "?";
        }
    }
}
