package com.webspeak.ts3;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.webspeak.ts3.core.SessionEvent;
import com.webspeak.ts3.core.Ts3Config;
import com.webspeak.ts3.core.Ts3Identity;
import com.webspeak.ts3.core.Ts3Session;

/** Connection form for a direct TeamSpeak 3 server. */
public final class MainActivity extends BaseActivity {
    private static final String PREFS = "ts3";

    private EditText hostField;
    private EditText portField;
    private EditText nicknameField;
    private EditText passwordField;
    private EditText channelField;
    private EditText tokenField;
    private EditText relayHostField;
    private EditText relayPortField;
    private EditText relayTokenField;
    private EditText identityField;
    private Button connectButton;
    private Button generateButton;
    private Button languageButton;
    private TextView statusView;

    private SharedPreferences preferences;
    private Ts3Session session;
    private volatile boolean connecting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // Same edge-to-edge handling as the room and the about page: on a window
        // that already fits the system bars the inset is 0, so this is a no-op.
        View mainRoot = findViewById(R.id.main_root);
        mainRoot.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        mainRoot.requestApplyInsets();

        // The notice is a translucent card over this form, so dragging it away
        // reveals the form that is already drawn behind it. Fresh launch only: a
        // recreation must not bring the gate back after it was dismissed.
        if (savedInstanceState == null) {
            startActivity(new Intent(this, AboutActivity.class)
                    .putExtra(AboutActivity.EXTRA_GATE, true));
            overridePendingTransition(0, 0);
        }
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        session = Ts3App.get().session();

        hostField = findViewById(R.id.main_host);
        portField = findViewById(R.id.main_port);
        nicknameField = findViewById(R.id.main_nickname);
        // Fixed prefix shown next to the field; the field itself only holds the name.
        ((TextView) findViewById(R.id.main_nickname_tag)).setText(Names.ANDROID_TAG);
        passwordField = findViewById(R.id.main_password);
        channelField = findViewById(R.id.main_channel);
        tokenField = findViewById(R.id.main_token);
        relayHostField = findViewById(R.id.main_relay_host);
        relayPortField = findViewById(R.id.main_relay_port);
        relayTokenField = findViewById(R.id.main_relay_token);
        identityField = findViewById(R.id.main_identity);
        connectButton = findViewById(R.id.main_connect);
        generateButton = findViewById(R.id.main_generate);
        languageButton = findViewById(R.id.main_language);
        statusView = findViewById(R.id.main_status);
        updateLanguageButton();
        languageButton.setOnClickListener(view -> {
            AppLanguage.set(this, "en".equals(AppLanguage.get(this)) ? "zh" : "en");
            recreate();
        });

        hostField.setText(preferences.getString("host", ""));
        portField.setText(Integer.toString(preferences.getInt("port", 9987)));
        // First run: a random nickname, so two fresh installs do not arrive with the
        // same name. The value is stored immediately, so it stays stable afterwards.
        String savedNickname = preferences.getString("nickname", null);
        if (savedNickname == null || savedNickname.trim().isEmpty()) {
            savedNickname = randomNickname();
            preferences.edit().putString("nickname", savedNickname).apply();
        }
        nicknameField.setText(savedNickname);
        channelField.setText(preferences.getString("channel", ""));
        tokenField.setText(preferences.getString("token", ""));
        relayHostField.setText(preferences.getString("relayHost", ""));
        int savedRelayPort = preferences.getInt("relayPort", 40000);
        relayPortField.setText(Integer.toString(savedRelayPort <= 0 ? 40000 : savedRelayPort));
        relayTokenField.setText(preferences.getString("relayToken", ""));
        identityField.setText(preferences.getString("identity", ""));

        connectButton.setOnClickListener(view -> connect());
        generateButton.setOnClickListener(view -> generateIdentity());

        if (session.isConnected()) {
            startActivity(new Intent(this, RoomActivity.class));
        }
    }

    /** Fresh-install nickname; the [Android] tag is added separately on connect. */
    private String randomNickname() {
        return getString(R.string.random_nickname, 1000 + new java.util.Random().nextInt(9000));
    }

    private void connect() {
        if (connecting) return;
        String host = hostField.getText().toString().trim();
        if (host.isEmpty()) {
            statusView.setText(R.string.enter_host);
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portField.getText().toString().trim());
        } catch (NumberFormatException error) {
            port = 9987;
        }
        if (port < 1 || port > 65535) port = 9987;
        final int finalPort = port;
        // The [Android] tag is ours, never the user's: whatever was typed is
        // normalised back to the bare nickname here, and the tag is added only to
        // the name the session registers with.
        String nickname = Names.withoutTag(nicknameField.getText().toString());
        if (nickname.isEmpty()) nickname = randomNickname();
        final String finalNickname = nickname;
        String relayHost = relayHostField.getText().toString().trim();
        int relayPort;
        try {
            relayPort = Integer.parseInt(relayPortField.getText().toString().trim());
        } catch (NumberFormatException error) {
            relayPort = 40000;
        }
        if (relayHost.isEmpty()) {
            relayPort = 0;
        } else if (relayPort < 1 || relayPort > 65535) {
            relayPort = 40000;
        }
        final int finalRelayPort = relayPort;
        final String relayToken = relayTokenField.getText().toString().trim();

        preferences.edit()
                .putString("host", host)
                .putInt("port", finalPort)
                .putString("nickname", finalNickname)
                .putString("channel", channelField.getText().toString().trim())
                .putString("token", tokenField.getText().toString().trim())
                .putString("relayHost", relayHost)
                .putInt("relayPort", finalRelayPort)
                .putString("relayToken", relayToken)
                .putString("identity", identityField.getText().toString().trim())
                .apply();

        final Ts3Config config = new Ts3Config(
                host,
                finalPort,
                Names.withTag(finalNickname),
                passwordField.getText().toString(),
                channelField.getText().toString().trim(),
                tokenField.getText().toString().trim(),
                identityField.getText().toString(),
                relayHost,
                finalRelayPort,
                relayToken
        );

        connecting = true;
        connectButton.setEnabled(false);
        generateButton.setEnabled(false);
        statusView.setText(relayHost.isEmpty()
                ? getString(R.string.connecting_status, host, finalPort)
                : getString(R.string.connecting_relay_status, relayHost, host, finalPort));

        session.setListener(new Ts3Session.Listener() {
            @Override
            public void onStateChanged(Ts3Session.State state, String detail) {
                runOnUiThread(() -> statusView.setText(state + ": " + detail));
            }

            @Override
            public void onDirectoryChanged() {
            }

            @Override
            public void onChatMessage(com.webspeak.ts3.core.ChatMessage message) {
            }

            @Override
            public void onSessionEvent(SessionEvent event) {
                runOnUiThread(() -> statusView.setText(event.text));
            }
        });

        new Thread(() -> {
            try {
                session.connect(config);
                String saved = session.getIdentityText();
                if (saved != null && !saved.isEmpty()) {
                    preferences.edit().putString("identity", saved).apply();
                }
                runOnUiThread(() -> {
                    connecting = false;
                    connectButton.setEnabled(true);
                    generateButton.setEnabled(true);
                    statusView.setText(R.string.connected_status);
                    startActivity(new Intent(MainActivity.this, RoomActivity.class));
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    connecting = false;
                    connectButton.setEnabled(true);
                    generateButton.setEnabled(true);
                    String message = error.getMessage();
                    String detail = message == null || message.isEmpty()
                            ? error.getClass().getSimpleName() : message;
                    statusView.setText(getString(R.string.connection_failed_status, detail));
                });
            }
        }, "ts3-connect").start();
    }

    private void updateLanguageButton() {
        languageButton.setText("en".equals(AppLanguage.get(this))
                ? R.string.english : R.string.chinese);
    }

    private void generateIdentity() {        generateButton.setEnabled(false);
        statusView.setText(R.string.generating_identity);
        new Thread(() -> {
            try {
                final String identity = Ts3Identity.generate(Ts3Identity.DEFAULT_SECURITY_LEVEL);
                runOnUiThread(() -> {
                    identityField.setText(identity);
                    statusView.setText(R.string.identity_generated);
                    generateButton.setEnabled(true);
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    statusView.setText(getString(R.string.identity_failed, String.valueOf(error)));
                    generateButton.setEnabled(true);
                });
            }
        }, "ts3-identity").start();
    }
}
