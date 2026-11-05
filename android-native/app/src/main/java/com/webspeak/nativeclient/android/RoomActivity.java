package com.webspeak.nativeclient.android;

import com.webspeak.nativeclient.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TabHost;
import android.widget.TextView;

import com.webspeak.nativeclient.core.ChannelInfo;
import com.webspeak.nativeclient.core.ChatEntry;
import com.webspeak.nativeclient.core.Member;
import com.webspeak.nativeclient.core.MemberRow;
import com.webspeak.nativeclient.core.PokeEvent;
import com.webspeak.nativeclient.core.ServerEventEntry;
import com.webspeak.nativeclient.voice.CommandCallback;
import com.webspeak.nativeclient.voice.VoiceSession;
import com.webspeak.nativeclient.voice.VoiceState;

import java.util.ArrayList;
import java.util.List;

/**
 * Voice room: channel tree, members, chat and session events.
 *
 * <p>All mutations go through the process-wide {@link VoiceSession}; this activity
 * only presents state and asks for commands. Leaving the screen disconnects the
 * session so the gateway never keeps an orphaned TeamSpeak client.
 */
public final class RoomActivity extends Activity implements WebSpeakApp.UiListener {

    private WebSpeakApp app;
    private VoiceSession session;
    private Settings settings;
    private AudioEngine audio;

    private TextView titleView;
    private TextView subtitleView;
    private TextView statusView;
    private Button micButton;
    private TabHost tabs;
    private ListView channelList;
    private ListView memberList;
    private ListView chatList;
    private ListView eventList;
    private EditText input;
    private Spinner scope;
    private LinearLayout privateBar;
    private TextView privateLabel;

    private ChannelAdapter channelAdapter;
    private MemberAdapter memberAdapter;
    private ChatAdapter chatAdapter;
    private EventAdapter eventAdapter;
    private final List<EventAdapter.Line> notices = new ArrayList<>();

    private int privateTargetId;
    private String privateTargetName;
    private boolean finishing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = (WebSpeakApp) getApplication();
        session = app.session();
        if (session == null) {
            finish();
            return;
        }
        settings = new Settings(this);
        audio = app.audio();
        setContentView(R.layout.activity_room);

        titleView = findViewById(R.id.room_title);
        subtitleView = findViewById(R.id.room_subtitle);
        statusView = findViewById(R.id.room_status);
        micButton = findViewById(R.id.room_mic);
        tabs = findViewById(android.R.id.tabhost);
        channelList = findViewById(R.id.room_channels);
        memberList = findViewById(R.id.room_members);
        chatList = findViewById(R.id.room_chat);
        eventList = findViewById(R.id.room_events);
        input = findViewById(R.id.room_input);
        scope = findViewById(R.id.room_scope);
        privateBar = findViewById(R.id.room_private_bar);
        privateLabel = findViewById(R.id.room_private_label);

        setupTabs();
        setupAdapters();
        setupActions();
    }

    @Override
    protected void onStart() {
        super.onStart();
        app.setUiListener(this);
        session.setForeground(true);
        refreshAll();
        renderState(session.state());
    }

    @Override
    protected void onStop() {
        session.setForeground(false);
        app.setUiListener(null);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    // ------------------------------------------------------------------ setup

    private void setupTabs() {
        tabs.setup();
        tabs.addTab(tabs.newTabSpec("channels").setIndicator(getString(R.string.tab_channels))
                .setContent(R.id.room_channels));
        tabs.addTab(tabs.newTabSpec("members").setIndicator(getString(R.string.tab_members))
                .setContent(R.id.room_members));
        tabs.addTab(tabs.newTabSpec("chat").setIndicator(getString(R.string.tab_chat))
                .setContent(R.id.room_chat_tab));
        tabs.addTab(tabs.newTabSpec("events").setIndicator(getString(R.string.tab_events))
                .setContent(R.id.room_events));
    }

    private void setupAdapters() {
        channelAdapter = new ChannelAdapter(this);
        channelList.setAdapter(channelAdapter);
        channelList.setOnItemClickListener((parent, view, position, id) -> joinChannel(channelAdapter.row(position)));

        memberAdapter = new MemberAdapter(this, audio);
        memberList.setAdapter(memberAdapter);
        memberList.setOnItemClickListener((parent, view, position, id) -> {
            MemberRow row = memberAdapter.row(position);
            if (!row.isHeader() && row.member != null) showMemberActions(row.member);
        });

        chatAdapter = new ChatAdapter(this);
        chatList.setAdapter(chatAdapter);

        eventAdapter = new EventAdapter(this);
        eventList.setAdapter(eventAdapter);

        ArrayAdapter<String> scopes = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{getString(R.string.tab_channels), "服务器"});
        scopes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        scope.setAdapter(scopes);
    }

    private void setupActions() {
        micButton.setOnClickListener(view -> {
            boolean muted = !session.isMuted();
            session.setMuted(muted);
            settings.saveMuted(muted);
            updateMicButton();
            renderState(session.state());
        });
        Button disconnect = findViewById(R.id.room_disconnect);
        disconnect.setOnClickListener(view -> confirmLeave());
        Button send = findViewById(R.id.room_send);
        send.setOnClickListener(view -> sendMessage());
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                sendMessage();
                return true;
            }
            return false;
        });
        Button clearPrivate = findViewById(R.id.room_private_clear);
        clearPrivate.setOnClickListener(view -> clearPrivateTarget());
    }

    // ------------------------------------------------------------------ refresh

    private void refreshAll() {
        if (session == null) return;
        applySavedVolumes();
        channelAdapter.setData(session.directory().roots(), session.directory(),
                session.directory().selfChannelId());
        memberAdapter.setData(session.directory().memberRows());
        chatAdapter.setData(session.chatHistory());
        rebuildEvents();
        updateHeader();
        updateMicButton();
        updatePrivateBar();
        scrollChatToBottom();
    }

    /** Restores per-member volumes saved by UID; a new session's ids start at 100%. */
    private void applySavedVolumes() {
        for (Member member : session.directory().members()) {
            float stored = settings.volume(member.preferenceKey());
            if (Math.abs(stored - session.memberVolume(member.id)) > 0.001f) {
                session.setMemberVolume(member.id, stored);
            }
        }
    }

    private void updateHeader() {
        String name = session.serverName();
        titleView.setText(name == null || name.isEmpty() ? getString(R.string.room_title_default) : name);
        String channel = session.directory().selfChannelName();
        StringBuilder subtitle = new StringBuilder();
        subtitle.append(channel == null || channel.isEmpty() ? "未加入频道" : "频道：" + channel);
        subtitle.append(" · 成员 ").append(session.directory().members().size());
        subtitleView.setText(subtitle.toString());
    }

    private void updateMicButton() {
        boolean muted = session.isMuted();
        String label = muted ? getString(R.string.mic_muted) : getString(R.string.mic_on);
        micButton.setText(label);
        micButton.setTextColor(getResources().getColor(muted ? R.color.ws_warning : R.color.ws_text, getTheme()));
    }

    private void updatePrivateBar() {
        if (privateTargetId == 0) {
            privateBar.setVisibility(View.GONE);
            scope.setEnabled(true);
        } else {
            privateBar.setVisibility(View.VISIBLE);
            privateLabel.setText("私聊 → " + privateTargetName);
            scope.setEnabled(false);
        }
    }

    private void renderState(VoiceState state) {
        if (state == null) return;
        StringBuilder text = new StringBuilder(state.message == null || state.message.isEmpty()
                ? "已连接" : state.message);
        if (state.phase == VoiceState.Phase.CONNECTED) {
            text.append(" · 语音：WebSocket PCM/Opus");
            int[] whisper = session.whisperTargetIds();
            if (whisper.length > 0) text.append(" · 私语 ").append(whisper.length).append(" 人");
        }
        statusView.setText(text.toString());
        int color = state.phase == VoiceState.Phase.FAILED ? R.color.ws_danger
                : state.phase == VoiceState.Phase.RECONNECTING ? R.color.ws_warning : R.color.ws_accent;
        statusView.setTextColor(getResources().getColor(color, getTheme()));
        if (state.phase == VoiceState.Phase.FAILED || state.phase == VoiceState.Phase.CLOSED) {
            if (!finishing) {
                finishing = true;
                if (state.phase == VoiceState.Phase.FAILED) Ui.toast(this, state.message);
                finish();
            }
        }
    }

    private void scrollChatToBottom() {
        int count = chatAdapter.getCount();
        if (count > 0) chatList.post(() -> chatList.setSelection(count - 1));
    }

    private void rebuildEvents() {
        List<EventAdapter.Line> lines = new ArrayList<>();
        for (ServerEventEntry event : session.serverEvents()) {
            lines.add(new EventAdapter.Line("[" + event.kindLabel() + "] " + Ui.time(event.timestamp),
                    event.message, false));
        }
        lines.addAll(notices);
        eventAdapter.setData(lines);
    }

    private void addNotice(String message) {
        notices.add(new EventAdapter.Line("提示 · " + Ui.time(System.currentTimeMillis()), message, true));
        while (notices.size() > 200) notices.remove(0);
        rebuildEvents();
    }

    // ------------------------------------------------------------------ actions

    private void joinChannel(ChannelAdapter.Row row) {
        if (row == null) return;
        if (row.current) {
            Ui.toast(this, "你已经在该频道中");
            return;
        }
        if (row.channel.isFull()) {
            Ui.toast(this, "该频道已满");
            return;
        }
        if (row.channel.isPasswordProtected()) {
            EditText password = new EditText(this);
            password.setHint("频道密码");
            password.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            int padding = Ui.dp(this, 20);
            password.setPadding(padding, padding / 2, padding, 0);
            new AlertDialog.Builder(this)
                    .setTitle("加入 " + row.channel.name)
                    .setView(password)
                    .setPositiveButton("加入", (dialog, which) -> doSwitch(row.channel.id, password.getText().toString()))
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
        } else {
            doSwitch(row.channel.id, null);
        }
    }

    private void doSwitch(String channelId, String password) {
        session.switchChannel(channelId, password, new CommandCallback() {
            @Override
            public void onSuccess() {
                Ui.toast(RoomActivity.this, "已切换频道");
            }

            @Override
            public void onError(String code, String message) {
                Ui.toast(RoomActivity.this, message);
            }
        });
    }

    private void showMemberActions(Member member) {
        if (member.self) {
            Ui.toast(this, "这是你自己的客户端");
            return;
        }
        String[] actions = {"发送私聊", "戳一戳", "调整音量", "移动到频道", "查看资料"};
        new AlertDialog.Builder(this)
                .setTitle(member.nickname)
                .setItems(actions, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            startPrivateChat(member);
                            break;
                        case 1:
                            promptPoke(member);
                            break;
                        case 2:
                            promptVolume(member);
                            break;
                        case 3:
                            promptMove(member);
                            break;
                        default:
                            showMemberInfo(member);
                            break;
                    }
                })
                .show();
    }

    private void startPrivateChat(Member member) {
        privateTargetId = member.id;
        privateTargetName = member.nickname;
        updatePrivateBar();
        tabs.setCurrentTab(2);
        input.requestFocus();
    }

    private void clearPrivateTarget() {
        privateTargetId = 0;
        privateTargetName = null;
        updatePrivateBar();
    }

    private void promptPoke(Member member) {
        EditText message = new EditText(this);
        message.setText("戳一戳");
        int padding = Ui.dp(this, 20);
        message.setPadding(padding, padding / 2, padding, 0);
        new AlertDialog.Builder(this)
                .setTitle("戳一戳 " + member.nickname)
                .setView(message)
                .setPositiveButton("发送", (dialog, which) -> session.poke(member.id,
                        message.getText().toString(), new CommandCallback() {
                            @Override
                            public void onSuccess() {
                                Ui.toast(RoomActivity.this, "已发送戳一戳");
                            }

                            @Override
                            public void onError(String code, String text) {
                                Ui.toast(RoomActivity.this, text);
                            }
                        }))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void promptVolume(Member member) {
        int padding = Ui.dp(this, 20);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding / 2, padding, 0);
        TextView label = new TextView(this);
        SeekBar bar = new SeekBar(this);
        bar.setMax(400);
        int initial = Math.round(session.memberVolume(member.id) * 100);
        bar.setProgress(initial);
        label.setText("音量：" + initial + "%");
        label.setTextColor(getResources().getColor(R.color.ws_text, getTheme()));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                label.setText("音量：" + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        layout.addView(label);
        layout.addView(bar);
        new AlertDialog.Builder(this)
                .setTitle("成员音量 · " + member.nickname)
                .setView(layout)
                .setPositiveButton("保存", (dialog, which) -> {
                    float volume = bar.getProgress() / 100f;
                    session.setMemberVolume(member.id, volume);
                    settings.saveVolume(member.preferenceKey(), volume);
                    Ui.toast(RoomActivity.this, "已设置 " + member.nickname + " 的音量");
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void promptMove(Member member) {
        List<ChannelInfo> channels = session.directory().channels();
        if (channels.isEmpty()) {
            Ui.toast(this, "频道列表尚未同步");
            return;
        }
        String[] names = new String[channels.size()];
        for (int i = 0; i < channels.size(); i++) names[i] = channels.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle("移动 " + member.nickname + " 到频道")
                .setItems(names, (dialog, which) -> session.moveClient(member.id, channels.get(which).id,
                        new CommandCallback() {
                            @Override
                            public void onSuccess() {
                                Ui.toast(RoomActivity.this, "已请求移动成员");
                            }

                            @Override
                            public void onError(String code, String message) {
                                Ui.toast(RoomActivity.this, message);
                            }
                        }))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showMemberInfo(Member member) {
        String channelId = channelIdOf(member);
        ChannelInfo channel = session.directory().channel(channelId);
        StringBuilder text = new StringBuilder();
        text.append("昵称：").append(member.nickname).append('\n');
        text.append("客户端 ID：").append(member.id).append('\n');
        text.append("唯一标识：").append(member.uid == null || member.uid.isEmpty() ? "（未知）" : member.uid).append('\n');
        String flags = Ui.memberFlags(member);
        text.append("状态：").append(flags.isEmpty() ? "正常" : flags).append('\n');
        text.append("频道：").append(channel == null ? "（未知）" : channel.name);
        new AlertDialog.Builder(this)
                .setTitle("成员资料")
                .setMessage(text.toString())
                .setPositiveButton("知道了", null)
                .show();
    }

    /** The directory exposes placement through member rows, not per-member fields. */
    private String channelIdOf(Member member) {
        for (com.webspeak.nativeclient.core.MemberRow row : session.directory().memberRows()) {
            if (!row.isHeader() && row.member.id == member.id) return row.channel.id;
        }
        return "";
    }

    private void sendMessage() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        CommandCallback callback = new CommandCallback() {
            @Override
            public void onSuccess() {
                if (input.getText().toString().trim().equals(text)) input.setText("");
            }

            @Override
            public void onError(String code, String message) {
                Ui.toast(RoomActivity.this, message);
            }
        };
        if (privateTargetId != 0) {
            session.sendPrivateMessage(privateTargetId, text, callback);
            return;
        }
        if (scope.getSelectedItemPosition() == 1) {
            session.sendServerMessage(text, callback);
            return;
        }
        String channelId = session.directory().selfChannelId();
        if (channelId == null) {
            Ui.toast(this, "尚未加入任何频道，请先选择频道");
            return;
        }
        session.sendChannelMessage(text, channelId, callback);
    }

    private void confirmLeave() {
        new AlertDialog.Builder(this)
                .setTitle("断开连接")
                .setMessage("断开后将退出当前 TeamSpeak 服务器。")
                .setPositiveButton("断开", (dialog, which) -> leave())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void leave() {
        finishing = true;
        app.stopSession();
        finish();
    }

    @Override
    public void onBackPressed() {
        if (finishing) {
            super.onBackPressed();
            return;
        }
        confirmLeave();
    }

    // ------------------------------------------------------------------ listener

    @Override
    public void onState(VoiceState state) {
        if (finishing) return;
        renderState(state);
        if (state.phase == VoiceState.Phase.CONNECTED || state.phase == VoiceState.Phase.RECONNECTING) {
            updateHeader();
            updateMicButton();
        }
    }

    @Override
    public void onServerInfo(String name, String welcomeMessage) {
        updateHeader();
    }

    @Override
    public void onDirectoryChanged() {
        if (finishing) return;
        applySavedVolumes();
        channelAdapter.setData(session.directory().roots(), session.directory(),
                session.directory().selfChannelId());
        memberAdapter.setData(session.directory().memberRows());
        updateHeader();
    }

    @Override
    public void onChat(ChatEntry entry) {
        if (finishing) return;
        chatAdapter.setData(session.chatHistory());
        scrollChatToBottom();
    }

    @Override
    public void onServerEvent(ServerEventEntry event) {
        if (finishing) return;
        rebuildEvents();
    }

    @Override
    public void onPoke(PokeEvent poke) {
        if (finishing) return;
        addNotice(poke.invokerName + " 戳了你：" + poke.message);
        new AlertDialog.Builder(this)
                .setTitle("收到戳一戳")
                .setMessage(poke.invokerName + "：" + poke.message)
                .setPositiveButton("知道了", null)
                .show();
    }

    @Override
    public void onWhisperTargets(int[] targetIds, boolean active) {
        renderState(session.state());
    }

    @Override
    public void onNotice(String message) {
        addNotice(message);
        Ui.toast(this, message);
    }

    @Override
    public void onAudioChanged() {
        memberAdapter.notifyDataSetChanged();
    }
}
