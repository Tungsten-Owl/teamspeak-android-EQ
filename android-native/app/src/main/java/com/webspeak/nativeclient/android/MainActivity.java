package com.webspeak.nativeclient.android;

import com.webspeak.nativeclient.R;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.webspeak.nativeclient.core.ChatEntry;
import com.webspeak.nativeclient.core.Json;
import com.webspeak.nativeclient.core.PokeEvent;
import com.webspeak.nativeclient.core.ServerEventEntry;
import com.webspeak.nativeclient.core.SessionConfig;
import com.webspeak.nativeclient.net.HttpCall;
import com.webspeak.nativeclient.voice.VoiceSession;
import com.webspeak.nativeclient.voice.VoiceState;

import java.util.Map;

/**
 * Join form. It collects the same inputs the browser join form posts to
 * {@code /api/join-ticket}, then hands them to the process-wide session.
 */
public final class MainActivity extends Activity implements WebSpeakApp.UiListener {

    private static final int REQUEST_RECORD_AUDIO = 41;

    private WebSpeakApp app;
    private Settings settings;
    private EditText gatewayField;
    private EditText nicknameField;
    private EditText channelField;
    private EditText targetField;
    private EditText passwordField;
    private EditText inviteField;
    private EditText identityField;
    private CheckBox rememberBox;
    private CheckBox trustBox;
    private TextView statusView;
    private ProgressBar progress;
    private Button connectButton;
    private Button probeButton;

    private SessionConfig pendingConfig;
    private boolean roomOpened;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        app = (WebSpeakApp) getApplication();
        settings = new Settings(this);
        gatewayField = findViewById(R.id.main_gateway);
        nicknameField = findViewById(R.id.main_nickname);
        channelField = findViewById(R.id.main_channel);
        targetField = findViewById(R.id.main_target);
        passwordField = findViewById(R.id.main_server_password);
        inviteField = findViewById(R.id.main_invite);
        identityField = findViewById(R.id.main_identity);
        rememberBox = findViewById(R.id.main_remember);
        trustBox = findViewById(R.id.main_trust_self_signed);
        statusView = findViewById(R.id.main_status);
        progress = findViewById(R.id.main_progress);
        connectButton = findViewById(R.id.main_connect);
        probeButton = findViewById(R.id.main_probe);

        gatewayField.setText(settings.gateway());
        nicknameField.setText(settings.nickname());
        channelField.setText(settings.channel());
        targetField.setText(settings.target());
        passwordField.setText(settings.serverPassword());
        inviteField.setText(settings.invite());
        identityField.setText(settings.identity());
        rememberBox.setChecked(settings.rememberIdentity());
        trustBox.setChecked(settings.trustSelfSigned());

        connectButton.setOnClickListener(view -> connect());
        probeButton.setOnClickListener(view -> probeGateway());
    }

    @Override
    protected void onStart() {
        super.onStart();
        app.setUiListener(this);
        VoiceSession session = app.session();
        if (session != null) {
            session.setForeground(true);
            // The gateway returns the identity it actually used when "remember
            // identity" was requested, which is how the browser keeps the field
            // current for the next connection.
            String updatedIdentity = session.identity();
            if (rememberBox.isChecked() && updatedIdentity != null && !updatedIdentity.isEmpty()) {
                identityField.setText(updatedIdentity);
                settings.saveIdentity(updatedIdentity);
            }
            renderState(session.state());
            if (session.isConnected()) openRoom();
        } else {
            renderState(VoiceState.idle());
        }
        String error = app.takeLastError();
        if (!error.isEmpty()) showStatus(error, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        VoiceSession session = app.session();
        if (roomOpened && (session == null || !session.isConnected())) roomOpened = false;
    }

    @Override
    protected void onStop() {
        app.setUiListener(null);
        super.onStop();
    }

    // ------------------------------------------------------------------ actions

    private void connect() {
        SessionConfig config;
        try {
            config = buildConfig();
        } catch (IllegalArgumentException error) {
            showStatus(error.getMessage() == null ? "连接参数无效" : error.getMessage(), true);
            return;
        }
        settings.saveForm(gatewayField.getText().toString().trim(), config.nickname,
                config.channel, config.target, config.serverPassword, config.invite,
                identityField.getText().toString().trim(), rememberBox.isChecked(), trustBox.isChecked());
        pendingConfig = config;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
        } else {
            startSession(config);
        }
    }

    private SessionConfig buildConfig() {
        String identity = identityField.getText().toString().trim();
        boolean remember = rememberBox.isChecked();
        return new SessionConfig.Builder()
                .gatewayUrl(gatewayField.getText().toString())
                .nickname(nicknameField.getText().toString())
                .channel(channelField.getText().toString())
                .target(targetField.getText().toString())
                .serverPassword(passwordField.getText().toString())
                .invite(inviteField.getText().toString())
                .identity(remember ? identity : "")
                .rememberIdentity(remember)
                .trustSelfSigned(trustBox.isChecked())
                .build();
    }

    private void startSession(SessionConfig config) {
        app.startSession(config);
        VoiceSession session = app.session();
        if (session != null) session.setMuted(settings.muted());
        renderState(session == null ? VoiceState.idle() : session.state());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RECORD_AUDIO) return;
        SessionConfig config = pendingConfig;
        if (config == null) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            showStatus("未授予麦克风权限：可以收发文字并收听，但无法说话", true);
        }
        startSession(config);
    }

    private void probeGateway() {
        final String gateway;
        final boolean trust;
        try {
            gateway = SessionConfig.normalizeGatewayUrl(gatewayField.getText().toString());
            trust = trustBox.isChecked();
        } catch (IllegalArgumentException error) {
            showStatus(error.getMessage(), true);
            return;
        }
        probeButton.setEnabled(false);
        showStatus("正在读取网关信息…", false);
        new Thread(() -> {
            String message;
            try {
                HttpCall call = new HttpCall("GET", gateway + "/api/public-config", trust);
                call.header("Accept", "application/json");
                call.header("Origin", gateway);
                HttpCall.Result result = call.execute(null);
                if (result.status != 200) throw new IllegalStateException("HTTP " + result.status);
                Map<String, Object> body = Json.asObject(Json.parse(result.body));
                String siteName = body == null ? null : Json.string(body, "siteName");
                String target = body == null ? null : Json.string(body, "target");
                String accessMode = body == null ? null : Json.string(body, "accessMode");
                boolean initialized = body != null && Json.bool(body, "initialized", false);
                StringBuilder text = new StringBuilder();
                text.append("站点名称：").append(siteName == null || siteName.isEmpty() ? "未设置" : siteName).append('\n');
                text.append("访问模式：").append("open".equals(accessMode) ? "开放（客户端填写服务器地址）" : "固定（网关配置的服务器）").append('\n');
                text.append("默认服务器：").append(target == null || target.isEmpty() ? "未设置" : target).append('\n');
                text.append("初始化：").append(initialized ? "已完成" : "尚未完成");
                message = text.toString();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    probeButton.setEnabled(true);
                    showStatus("网关可用", false);
                    if (target != null && !target.isEmpty() && targetField.getText().toString().trim().isEmpty()) {
                        targetField.setText(target);
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("网关信息")
                            .setMessage(message)
                            .setPositiveButton("知道了", null)
                            .show();
                });
            } catch (Exception error) {
                String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    probeButton.setEnabled(true);
                    showStatus("无法读取网关信息：" + detail, true);
                });
            }
        }, "gateway-probe").start();
    }

    // ------------------------------------------------------------------ ui state

    private void openRoom() {
        if (roomOpened || isFinishing()) return;
        roomOpened = true;
        startActivity(new Intent(this, RoomActivity.class));
    }

    private void renderState(VoiceState state) {
        if (state == null) state = VoiceState.idle();
        boolean busy = state.phase == VoiceState.Phase.TICKET || state.phase == VoiceState.Phase.CONNECTING;
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        connectButton.setEnabled(!busy);
        switch (state.phase) {
            case IDLE:
                showStatus(getString(R.string.main_status_idle), false);
                break;
            case TICKET:
                showStatus("正在申请会话票据…", false);
                break;
            case CONNECTING:
                showStatus(state.message, false);
                break;
            case CONNECTED:
                showStatus("已连接" + (state.serverName.isEmpty() ? "" : "：" + state.serverName), false);
                break;
            case RECONNECTING:
                showStatus(state.message, false);
                break;
            case FAILED:
                showStatus(state.message, true);
                connectButton.setEnabled(true);
                break;
            case CLOSED:
                showStatus("已断开连接", false);
                break;
            default:
                break;
        }
    }

    private void showStatus(String message, boolean error) {
        statusView.setText(message == null ? "" : message);
        statusView.setTextColor(getResources().getColor(error ? R.color.ws_danger : R.color.ws_accent, getTheme()));
    }

    // ------------------------------------------------------------------ listener

    @Override
    public void onState(VoiceState state) {
        renderState(state);
        if (state.phase == VoiceState.Phase.CONNECTED) openRoom();
    }

    @Override
    public void onServerInfo(String name, String welcomeMessage) {
        // The room title shows the server name; the form only confirms success.
    }

    @Override
    public void onDirectoryChanged() {
    }

    @Override
    public void onChat(ChatEntry entry) {
    }

    @Override
    public void onServerEvent(ServerEventEntry event) {
    }

    @Override
    public void onPoke(PokeEvent poke) {
    }

    @Override
    public void onWhisperTargets(int[] targetIds, boolean active) {
    }

    @Override
    public void onNotice(String message) {
        Ui.toast(this, message);
    }

    @Override
    public void onAudioChanged() {
    }
}
