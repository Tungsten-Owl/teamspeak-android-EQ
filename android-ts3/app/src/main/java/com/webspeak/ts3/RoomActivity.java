package com.webspeak.ts3;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.Toast;

import com.webspeak.ts3.core.ChannelInfo;
import com.webspeak.ts3.core.ChatMessage;
import com.webspeak.ts3.core.MemberInfo;
import com.webspeak.ts3.core.SessionEvent;
import com.webspeak.ts3.core.Ts3Session;
import com.webspeak.ts3.core.VoiceEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Direct TeamSpeak 3 room: channels, members, chat, events and voice controls. */
public final class RoomActivity extends BaseActivity {
    private static final int REQUEST_MICROPHONE = 41;

    private Ts3Session session;
    private AndroidAudioEngine audio;
    private VoiceEngine voice;
    private SoundEffects sounds;

    private ChannelAdapter channelAdapter;
    private MemberAdapter memberAdapter;
    private ChatAdapter chatAdapter;
    private EventAdapter eventAdapter;
    private AvatarRepository avatars;

    private ListView channelList;
    private ListView memberList;
    private ListView chatList;
    private ListView eventList;

    private TextView titleView;
    private TextView statusView;
    private Button microphoneButton;
    private Button speakerButton;
    private Button settingsButton;
    private Button leaveButton;
    private Button sendButton;
    private EditText chatInput;
    private Spinner chatTarget;
    private TextView selfNameView;
    private View chatBar;
    private TabHost tabHost;

    private String selfNickname = "";
    private String hostName = "";

    private int privateTargetId;
    private String privateTargetName = "";
    private boolean microphoneWanted;
    private boolean reconnecting;
    private boolean eventSounds = true;
    private int eventSoundVolume = 100;
    private boolean resumed;
    private boolean networkLost;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private WifiManager.WifiLock wifiLock;
    private AlertDialog microphoneIntro;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_room);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        session = Ts3App.get().session();
        audio = Ts3App.get().audio();
        voice = Ts3App.get().voice();
        eventSounds = getSharedPreferences("ts3", MODE_PRIVATE)
                .getBoolean("eventSounds", true);
        eventSoundVolume = getSharedPreferences("ts3", MODE_PRIVATE)
                .getInt("eventSoundVolume", 100);
        sounds = new SoundEffects(this);
        sounds.setVolume(eventSoundVolume / 100f);
        audio.setNoiseSuppressionEnabled(getSharedPreferences("ts3", MODE_PRIVATE)
                .getBoolean("noiseSuppression", false));
        audio.setUseEarpiece(getSharedPreferences("ts3", MODE_PRIVATE)
                .getBoolean("useEarpiece", true));
        audio.setVoiceOptimization(getSharedPreferences("ts3", MODE_PRIVATE)
                .getBoolean("voiceOptimization", true));
        audio.setQqVoiceMode(getSharedPreferences("ts3", MODE_PRIVATE)
                .getBoolean("qqVoiceMode", true));
        audio.setRawMicrophone(getSharedPreferences("ts3", MODE_PRIVATE)
                .getBoolean("rawMicrophone", false));
        audio.setRnnoiseWet(getSharedPreferences("ts3", MODE_PRIVATE)
                .getInt("rnnoiseWet", 80) / 100f);
        audio.setRnnoiseGate(getSharedPreferences("ts3", MODE_PRIVATE)
                .getInt("rnnoiseGate", 0) / 100f);
        audio.setMicGain(getSharedPreferences("ts3", MODE_PRIVATE)
                .getInt("micGain", 100) / 100f);
        audio.setMicEq(
                getSharedPreferences("ts3", MODE_PRIVATE).getInt("eqLow", 0),
                getSharedPreferences("ts3", MODE_PRIVATE).getInt("eqMid", 0),
                getSharedPreferences("ts3", MODE_PRIVATE).getInt("eqHigh", 0));
        // Packaged ONNX denoisers win when they are installed: they run on the hop
        // the microphone already produces. Order is the MIT 48 kHz model, then the
        // causal 48 kHz DFSMN, then DeepFilterNet3 (only on a device that can carry
        // a 30 ms model inside a 20 ms frame), then the light RNNoise.
        boolean pureVoxPresent = PureVoxDenoiser.isAssetPresent(this);
        boolean dfsmnPresent = DfsmnDenoiser.isAssetPresent(this);
        boolean deepFilterPresent = DeepFilterNet.isAvailable();
        boolean enoughCores = Runtime.getRuntime().availableProcessors() >= 6;
        String fallbackModel = deepFilterPresent && enoughCores
                ? AndroidAudioEngine.MODEL_DEEPFILTER : AndroidAudioEngine.MODEL_RNNOISE;
        String defaultModel = pureVoxPresent ? AndroidAudioEngine.MODEL_PUREVOX
                : dfsmnPresent ? AndroidAudioEngine.MODEL_DFSMN
                        : fallbackModel;
        String savedModel = getSharedPreferences("ts3", MODE_PRIVATE)
                .getString("denoiseModel", defaultModel);
        if (AndroidAudioEngine.MODEL_PUREVOX.equals(savedModel) && !pureVoxPresent) {
            savedModel = dfsmnPresent ? AndroidAudioEngine.MODEL_DFSMN : fallbackModel;
        } else if (AndroidAudioEngine.MODEL_DFSMN.equals(savedModel) && !dfsmnPresent) {
            savedModel = pureVoxPresent ? AndroidAudioEngine.MODEL_PUREVOX : fallbackModel;
        } else if (AndroidAudioEngine.MODEL_DEEPFILTER.equals(savedModel) && !deepFilterPresent) {
            savedModel = pureVoxPresent ? AndroidAudioEngine.MODEL_PUREVOX
                    : dfsmnPresent ? AndroidAudioEngine.MODEL_DFSMN : AndroidAudioEngine.MODEL_RNNOISE;
        }
        audio.setDenoiseModel(savedModel);
        audio.setDfnAttenuationDb(getSharedPreferences("ts3", MODE_PRIVATE)
                .getInt("dfnAttenDb", 30));
        audio.setDfnPostFilter(getSharedPreferences("ts3", MODE_PRIVATE)
                .getInt("dfnPostFilter", 0) / 100f * 0.05f);
        if (!session.isConnected()) {
            finish();
            return;
        }

        titleView = findViewById(R.id.room_title);
        statusView = findViewById(R.id.room_status);
        microphoneButton = findViewById(R.id.room_mic);
        speakerButton = findViewById(R.id.room_speaker);
        settingsButton = findViewById(R.id.room_settings);
        leaveButton = findViewById(R.id.room_leave);
        sendButton = findViewById(R.id.room_chat_send);
        chatInput = findViewById(R.id.room_chat_input);
        chatTarget = findViewById(R.id.room_chat_target);
        chatBar = findViewById(R.id.room_chat_bar);
        selfNameView = findViewById(R.id.room_self_name);

        View root = findViewById(R.id.room_root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();

        selfNickname = session.getConfig().nickname;
        hostName = session.getConfig().host;
        titleView.setText(R.string.app_name);
        selfNameView.setText(selfNickname + " (you)");
        statusView.setText(selfNickname + " · " + hostName);

        tabHost = findViewById(android.R.id.tabhost);
        tabHost.setup();
        LayoutInflater inflater = LayoutInflater.from(this);
        View channelsView = inflater.inflate(R.layout.tab_channels, tabHost.getTabContentView(), false);
        View membersView = inflater.inflate(R.layout.tab_members, tabHost.getTabContentView(), false);
        View chatView = inflater.inflate(R.layout.tab_chat, tabHost.getTabContentView(), false);
        View eventsView = inflater.inflate(R.layout.tab_events, tabHost.getTabContentView(), false);
        tabHost.getTabContentView().addView(channelsView);
        tabHost.getTabContentView().addView(membersView);
        tabHost.getTabContentView().addView(chatView);
        tabHost.getTabContentView().addView(eventsView);
        tabHost.addTab(tabHost.newTabSpec("channels")
                .setIndicator(getString(R.string.channels))
                .setContent(channelsView.getId()));
        tabHost.addTab(tabHost.newTabSpec("members")
                .setIndicator(getString(R.string.members))
                .setContent(membersView.getId()));
        tabHost.addTab(tabHost.newTabSpec("chat")
                .setIndicator(getString(R.string.chat))
                .setContent(chatView.getId()));
        tabHost.addTab(tabHost.newTabSpec("events")
                .setIndicator(getString(R.string.events))
                .setContent(eventsView.getId()));
        tabHost.setOnTabChangedListener(tabId ->
                chatBar.setVisibility("chat".equals(tabId) ? View.VISIBLE : View.GONE));
        tabHost.setCurrentTab(0);
        chatBar.setVisibility(View.GONE);

        channelList = channelsView.findViewById(R.id.channel_list);
        memberList = membersView.findViewById(R.id.member_list);
        chatList = chatView.findViewById(R.id.chat_list);
        eventList = eventsView.findViewById(R.id.event_list);

        avatars = new AvatarRepository(this, session);
        channelAdapter = new ChannelAdapter(this, avatars);
        memberAdapter = new MemberAdapter(this);
        chatAdapter = new ChatAdapter(this, avatars);
        eventAdapter = new EventAdapter(this);
        avatars.setOnLoaded(() -> runOnUiThread(() -> {
            channelAdapter.notifyDataSetChanged();
            chatAdapter.notifyDataSetChanged();
        }));
        channelList.setAdapter(channelAdapter);
        memberList.setAdapter(memberAdapter);
        chatList.setAdapter(chatAdapter);
        eventList.setAdapter(eventAdapter);
        channelAdapter.setSelfId(session.getSelfId());

        ArrayAdapter<String> targets = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{getString(R.string.channel_target),
                        getString(R.string.server_target),
                        getString(R.string.private_target)});
        targets.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        chatTarget.setAdapter(targets);
        chatTarget.setSelection(0);

        channelList.setOnItemClickListener((parent, view, position, id) -> {
            ChannelRow row = channelAdapter.getItem(position);
            if (row.isMember()) showMemberActions(row.member);
            else joinChannel(row.channel);
        });
        memberList.setOnItemClickListener((parent, view, position, id) ->
                showMemberActions(memberAdapter.getItem(position)));
        microphoneButton.setOnClickListener(view -> toggleMicrophone());
        speakerButton.setOnClickListener(view -> toggleSpeaker());
        settingsButton.setOnClickListener(view -> showSettings());
        leaveButton.setOnClickListener(view -> leave());
        sendButton.setOnClickListener(view -> sendChat());
        chatInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                    || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                    && event.getAction() == android.view.KeyEvent.ACTION_DOWN)) {
                sendChat();
            }
            return true;
        });

        voice.setSpeakerMuted(false);
        audio.stopMicrophone();
        microphoneWanted = false;
        voice.setMicrophoneMuted(true);
        audio.startPlayback();
        updateSpeakerButton();
        updateMicrophoneButton();

        session.setListener(new Ts3Session.Listener() {
            @Override
            public void onStateChanged(Ts3Session.State state, String detail) {
                runOnUiThread(() -> {
                    statusView.setText(detail);
                    if (state == Ts3Session.State.RECONNECTING) {
                        if (!reconnecting) {
                            // Drop the old AudioTrack/AudioRecord and all
                            // queued PCM. Reusing them after a network change
                            // can leave playback or capture stuck.
                            audio.stopMicrophone();
                            audio.stopPlayback();
                        }
                        reconnecting = true;
                        return;
                    }
                    boolean wasReconnecting = reconnecting;
                    reconnecting = false;
                    if (state == Ts3Session.State.CONNECTED && wasReconnecting && resumed) {
                        // Do not reuse the old AudioTrack after a reconnect:
                        // its stream buffer and speaker queues can still hold
                        // frames from the pre-reconnect session.
                        audio.restartPlayback();
                        if (microphoneWanted && hasMicrophonePermission()) startMicrophone();
                    }
                    if (state == Ts3Session.State.DISCONNECTED || state == Ts3Session.State.ERROR) {
                        Toast.makeText(RoomActivity.this, detail, Toast.LENGTH_LONG).show();
                        finish();
                    }
                });
            }

            @Override
            public void onDirectoryChanged() {
                runOnUiThread(RoomActivity.this::refreshDirectory);
            }

            @Override
            public void onChatMessage(ChatMessage message) {
                runOnUiThread(() -> {
                    chatAdapter.add(message);
                    chatList.setSelection(Math.max(0, chatAdapter.getCount() - 1));
                });
            }

            @Override
            public void onSessionEvent(SessionEvent event) {
                runOnUiThread(() -> {
                    eventAdapter.add(event);
                    eventList.setSelection(Math.max(0, eventAdapter.getCount() - 1));
                });
            }

            @Override
            public void onMemberJoined(MemberInfo member) {
                if (!eventSounds || sounds == null) return;
                sounds.memberJoined();
            }

            @Override
            public void onMemberLeft(MemberInfo member, boolean disconnected) {
                if (!eventSounds || sounds == null) return;
                if (disconnected) sounds.memberDisconnected();
                else sounds.memberLeft();
            }
        });

        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                if (!networkLost) return;
                networkLost = false;
                runOnUiThread(() -> session.reconnectNow());
            }

            @Override
            public void onLost(Network network) {
                networkLost = true;
                runOnUiThread(() -> session.reconnectNow());
            }
        };
        if (connectivityManager != null) {
            try {
                connectivityManager.registerDefaultNetworkCallback(networkCallback);
            } catch (Throwable ignored) {
                networkCallback = null;
            }
        }

        chatAdapter.clear();
        for (ChatMessage message : session.getChatHistory()) chatAdapter.add(message);
        eventAdapter.clear();
        for (SessionEvent event : session.getEvents()) eventAdapter.add(event);
        refreshDirectory();
        showMicrophoneIntro(savedInstanceState == null);
    }

    private void showMicrophoneIntro(boolean firstCreate) {
        if (!firstCreate || microphoneWanted || audio.isMicrophoneRunning()) return;
        microphoneIntro = new AlertDialog.Builder(this)
                .setTitle(R.string.mic_intro_title)
                .setMessage(R.string.mic_intro_message)
                .setPositiveButton(R.string.mic_intro_enable,
                        (dialog, which) -> toggleMicrophone())
                .setNegativeButton(R.string.mic_intro_later, null)
                .setCancelable(true)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        acquireWifiLock();
        if (reconnecting) return;
        audio.startPlayback();
        if (microphoneWanted && hasMicrophonePermission()) startMicrophone();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        audio.stopMicrophone();
        releaseWifiLock();
    }

    private void acquireWifiLock() {
        if (wifiLock == null) {
            WifiManager manager = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            if (manager != null) {
                wifiLock = manager.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ts3:voice");
                wifiLock.setReferenceCounted(false);
            }
        }
        if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
    }

    private void releaseWifiLock() {
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }

    @Override
    protected void onDestroy() {
        releaseWifiLock();
        if (microphoneIntro != null) {
            microphoneIntro.dismiss();
            microphoneIntro = null;
        }
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Throwable ignored) {
            }
            networkCallback = null;
        }
        if (avatars != null) avatars.close();
        if (sounds != null) {
            sounds.release();
            sounds = null;
        }
        if (isFinishing()) {
            session.setListener(null);
            audio.stopMicrophone();
            audio.stopPlayback();
            session.disconnect();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        leave();
    }

    private void refreshDirectory() {
        List<ChannelInfo> channels = session.getChannels();
        List<MemberInfo> members = session.getMembers();
        Map<Integer, Integer> counts = new HashMap<>();
        for (MemberInfo member : members) {
            counts.put(member.channelId, counts.getOrDefault(member.channelId, 0) + 1);
        }
        for (ChannelInfo channel : channels) {
            Integer count = counts.get(channel.id);
            channel.memberCount = count == null ? 0 : count;
        }
        List<ChannelRow> rows = flatten(channels, members);
        channelAdapter.setRows(rows);
        channelAdapter.setSelfId(session.getSelfId());
        memberAdapter.setData(members, channels, session.getSelfId());
        int memberCount = members.size();
        if (reconnecting) {
            statusView.setText(R.string.reconnecting);
        } else {
            statusView.setText(getString(R.string.room_status, selfNickname, hostName,
                    channels.size(), memberCount, session.getSelfId()));
        }
    }

    private static List<ChannelRow> flatten(List<ChannelInfo> channels, List<MemberInfo> members) {
        Map<Integer, ChannelInfo> byId = new HashMap<>();
        for (ChannelInfo channel : channels) byId.put(channel.id, channel);

        Map<Integer, List<ChannelInfo>> children = new HashMap<>();
        List<ChannelInfo> roots = new ArrayList<>();
        for (ChannelInfo channel : channels) {
            if (channel.parentId == 0 || !byId.containsKey(channel.parentId)) {
                roots.add(channel);
            } else {
                children.computeIfAbsent(channel.parentId, key -> new ArrayList<>()).add(channel);
            }
        }
        java.util.Comparator<ChannelInfo> order = (left, right) -> {
            int result = Integer.compare(left.order, right.order);
            return result != 0 ? result : left.name.compareToIgnoreCase(right.name);
        };
        roots.sort(order);
        for (List<ChannelInfo> list : children.values()) list.sort(order);

        List<ChannelRow> channelRows = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        ArrayList<ChannelRow> stack = new ArrayList<>();
        for (int i = roots.size() - 1; i >= 0; i--) stack.add(new ChannelRow(roots.get(i), 0));
        while (!stack.isEmpty()) {
            ChannelRow row = stack.remove(stack.size() - 1);
            if (!visited.add(row.channel.id)) continue;
            channelRows.add(row);
            List<ChannelInfo> kids = children.get(row.channel.id);
            if (kids != null) {
                for (int i = kids.size() - 1; i >= 0; i--) {
                    stack.add(new ChannelRow(kids.get(i), row.depth + 1));
                }
            }
        }

        Map<Integer, List<MemberInfo>> byChannel = new HashMap<>();
        for (MemberInfo member : members) {
            byChannel.computeIfAbsent(member.channelId, key -> new ArrayList<>()).add(member);
        }
        for (List<MemberInfo> list : byChannel.values()) {
            list.sort((left, right) -> left.nickname.compareToIgnoreCase(right.nickname));
        }

        List<ChannelRow> result = new ArrayList<>();
        for (ChannelRow row : channelRows) {
            result.add(row);
            if (row.channel.isSpacer()) continue;
            List<MemberInfo> room = byChannel.get(row.channel.id);
            if (room == null) continue;
            for (MemberInfo member : room) {
                result.add(new ChannelRow(row.channel, member, row.depth + 1));
            }
        }
        return result;
    }

    private void joinChannel(ChannelInfo channel) {
        if (channel == null) return;
        if (channel.isSpacer()) {
            Toast.makeText(this, R.string.spacer_channel, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!channel.password) {
            joinOnWorker(channel, "", false);
            return;
        }
        String remembered = Ts3App.get().channelPassword(channel.id);
        if (!remembered.isEmpty()) {
            joinOnWorker(channel, remembered, true);
            return;
        }
        promptChannelPassword(channel);
    }

    private void promptChannelPassword(ChannelInfo channel) {
        EditText input = new EditText(this);
        input.setHint("Channel password");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle("Join " + channel.name)
                .setView(input)
                .setPositiveButton("Join",
                        (dialog, which) -> joinOnWorker(channel, input.getText().toString(), false))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void joinOnWorker(ChannelInfo channel, String password, boolean remembered) {
        new Thread(() -> {
            try {
                session.joinChannel(channel.id, password);
                if (channel.password && !password.isEmpty()) {
                    Ts3App.get().rememberChannelPassword(channel.id, password);
                }
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.joined_channel, channel.name),
                        Toast.LENGTH_SHORT).show());
            } catch (Throwable error) {
                if (channel.password) Ts3App.get().forgetChannelPassword(channel.id);
                runOnUiThread(() -> {
                    eventAdapter.add(new SessionEvent(SessionEvent.ERROR,
                            "Join failed: " + messageOf(error)));
                    if (channel.password && remembered) {
                        // The remembered password was rejected by this server.
                        promptChannelPassword(channel);
                    }
                });
            }
        }, "ts3-join").start();
    }

    private void showMemberActions(MemberInfo member) {
        if (member.id == session.getSelfId()) return;
        String[] actions = {"Private message", "Poke"};
        new AlertDialog.Builder(this)
                .setTitle(member.nickname)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        privateTargetId = member.id;
                        privateTargetName = member.nickname;
                        chatTarget.setSelection(2);
                        chatInput.requestFocus();
                        Toast.makeText(this, "Private target: " + member.nickname,
                                Toast.LENGTH_SHORT).show();
                    } else {
                        promptPoke(member);
                    }
                })
                .show();
    }

    private void promptPoke(MemberInfo member) {
        EditText input = new EditText(this);
        input.setHint("Poke message");
        new AlertDialog.Builder(this)
                .setTitle("Poke " + member.nickname)
                .setView(input)
                .setPositiveButton("Poke", (dialog, which) -> {
                    String message = input.getText().toString().trim();
                    if (message.isEmpty()) message = "Poke";
                    final String finalMessage = message;
                    new Thread(() -> {
                        try {
                            session.poke(member.id, finalMessage);
                        } catch (Throwable error) {
                            runOnUiThread(() -> eventAdapter.add(new SessionEvent(SessionEvent.ERROR,
                                    "Poke failed: " + messageOf(error))));
                        }
                    }, "ts3-poke").start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void sendChat() {
        String text = chatInput.getText().toString().trim();
        if (text.isEmpty()) return;
        int target = chatTarget.getSelectedItemPosition();
        if (target == 2 && privateTargetId == 0) {
            Toast.makeText(this, "Choose a member for a private message first",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        chatInput.setText("");
        new Thread(() -> {
            try {
                if (target == 1) session.sendServerMessage(text);
                else if (target == 2) session.sendPrivateMessage(privateTargetId, text);
                else session.sendChannelMessage(text);
            } catch (Throwable error) {
                runOnUiThread(() -> eventAdapter.add(new SessionEvent(SessionEvent.ERROR,
                        "Send failed: " + messageOf(error))));
            }
        }, "ts3-chat").start();
    }

    private void toggleMicrophone() {
        if (audio.isMicrophoneRunning()) {
            microphoneWanted = false;
            audio.stopMicrophone();
            voice.setMicrophoneMuted(true);
            updateMicrophoneButton();
            return;
        }
        microphoneWanted = true;
        if (!hasMicrophonePermission()) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
            return;
        }
        startMicrophone();
    }

    private void startMicrophone() {
        voice.setMicrophoneMuted(false);
        if (!audio.startMicrophone(this)) {
            microphoneWanted = false;
            voice.setMicrophoneMuted(true);
            Toast.makeText(this, "Microphone could not be started", Toast.LENGTH_LONG).show();
        }
        updateMicrophoneButton();
    }

    private void updateMicrophoneButton() {
        boolean on = audio.isMicrophoneRunning();
        microphoneButton.setText(on ? R.string.mic_on : R.string.mic_off);
    }

    private void showSettings() {
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(padding, padding, padding, 0);

        TextView languageLabel = new TextView(this);
        languageLabel.setText(R.string.language);
        languageLabel.setTextSize(13);
        box.addView(languageLabel);

        RadioGroup languages = new RadioGroup(this);
        languages.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton chinese = new RadioButton(this);
        chinese.setId(View.generateViewId());
        chinese.setText(R.string.chinese);
        RadioButton english = new RadioButton(this);
        english.setId(View.generateViewId());
        english.setText(R.string.english);
        languages.addView(chinese);
        languages.addView(english);
        if ("en".equals(AppLanguage.get(this))) english.setChecked(true);
        else chinese.setChecked(true);
        box.addView(languages);

        TextView label = new TextView(this);
        label.setText(R.string.settings_volume);
        label.setTextSize(13);
        SeekBar volume = new SeekBar(this);
        volume.setMax(200);
        volume.setProgress(Math.round(voice.getVolume() * 100f));
        box.addView(label);
        box.addView(volume);

        TextView micGainLabel = new TextView(this);
        micGainLabel.setTextSize(13);
        micGainLabel.setText(getString(R.string.mic_gain, Math.round(audio.getMicGain() * 100)));
        box.addView(micGainLabel);
        SeekBar micGain = new SeekBar(this);
        micGain.setMax(200);
        micGain.setProgress(Math.round(audio.getMicGain() * 100));
        micGain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                micGainLabel.setText(getString(R.string.mic_gain, progress));
                audio.setMicGain(progress / 100f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        box.addView(micGain);

        TextView eqTitle = new TextView(this);
        eqTitle.setText(R.string.mic_eq);
        eqTitle.setTextSize(13);
        box.addView(eqTitle);
        addEqBand(box, R.string.mic_eq_low, 0);
        addEqBand(box, R.string.mic_eq_mid, 1);
        addEqBand(box, R.string.mic_eq_high, 2);

        // Master switch first: it decides whether any denoising runs, and the
        // model list under it only matters while it is on.
        CheckBox noiseSuppression = new CheckBox(this);
        noiseSuppression.setText(R.string.noise_suppression);
        noiseSuppression.setChecked(audio.isNoiseSuppressionEnabled());
        box.addView(noiseSuppression);

        // The whole model section only exists while denoising is enabled: with the
        // master switch off there is no model to choose and no parameter to tune.
        LinearLayout modelGroup = new LinearLayout(this);
        modelGroup.setOrientation(LinearLayout.VERTICAL);

        TextView modelLabel = new TextView(this);
        modelLabel.setText(R.string.denoise_model);
        modelGroup.addView(modelLabel);
        RadioGroup models = new RadioGroup(this);
        models.setOrientation(RadioGroup.VERTICAL);
        RadioButton rnnoiseModel = new RadioButton(this);
        rnnoiseModel.setId(View.generateViewId());
        rnnoiseModel.setText(R.string.model_rnnoise);
        models.addView(rnnoiseModel);
        RadioButton deepFilterModel = new RadioButton(this);
        deepFilterModel.setId(View.generateViewId());
        boolean deepFilterAvailable = DeepFilterNet.isAvailable();
        deepFilterModel.setText(deepFilterAvailable
                ? R.string.model_deepfilter : R.string.model_unavailable);
        deepFilterModel.setEnabled(deepFilterAvailable);
        models.addView(deepFilterModel);
        RadioButton pureVoxModel = new RadioButton(this);
        pureVoxModel.setId(View.generateViewId());
        boolean pureVoxAvailable = PureVoxDenoiser.isAssetPresent(this);
        pureVoxModel.setText(pureVoxAvailable
                ? R.string.model_purevox : R.string.model_purevox_missing);
        pureVoxModel.setEnabled(pureVoxAvailable);
        models.addView(pureVoxModel);
        RadioButton dfsmnModel = new RadioButton(this);
        dfsmnModel.setId(View.generateViewId());
        boolean dfsmnAvailable = DfsmnDenoiser.isAssetPresent(this);
        dfsmnModel.setText(dfsmnAvailable
                ? R.string.model_dfsmn : R.string.model_dfsmn_missing);
        dfsmnModel.setEnabled(dfsmnAvailable);
        models.addView(dfsmnModel);
        if (pureVoxAvailable && AndroidAudioEngine.MODEL_PUREVOX.equals(audio.getDenoiseModel())) {
            pureVoxModel.setChecked(true);
        } else if (dfsmnAvailable && AndroidAudioEngine.MODEL_DFSMN.equals(audio.getDenoiseModel())) {
            dfsmnModel.setChecked(true);
        } else if (deepFilterAvailable
                && AndroidAudioEngine.MODEL_DEEPFILTER.equals(audio.getDenoiseModel())) {
            deepFilterModel.setChecked(true);
        } else {
            rnnoiseModel.setChecked(true);
        }
        modelGroup.addView(models);
        box.addView(modelGroup);

        // Each model owns its own controls: the two groups below are shown only
        // while that model is selected, so the panel never mixes RNNoise sliders
        // with DFN3 ones. PureVox and DFSMN have no runtime knobs of their own.
        LinearLayout dfnGroup = new LinearLayout(this);
        dfnGroup.setOrientation(LinearLayout.VERTICAL);

        TextView dfnAttenLabel = new TextView(this);
        dfnAttenLabel.setText(getString(R.string.dfn_attenuation,
                Math.round(audio.getDfnAttenuationDb())));
        dfnGroup.addView(dfnAttenLabel);
        SeekBar dfnAtten = new SeekBar(this);
        dfnAtten.setMax(40);
        dfnAtten.setProgress(Math.round(audio.getDfnAttenuationDb()));
        dfnAtten.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                dfnAttenLabel.setText(getString(R.string.dfn_attenuation, progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        dfnGroup.addView(dfnAtten);

        TextView dfnPostLabel = new TextView(this);
        int initialPost = Math.round(audio.getDfnPostFilter() / 0.05f * 100f);
        dfnPostLabel.setText(getString(R.string.dfn_postfilter, initialPost));
        dfnGroup.addView(dfnPostLabel);
        SeekBar dfnPost = new SeekBar(this);
        dfnPost.setMax(100);
        dfnPost.setProgress(initialPost);
        dfnPost.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                dfnPostLabel.setText(getString(R.string.dfn_postfilter, progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        dfnGroup.addView(dfnPost);
        box.addView(dfnGroup);

        LinearLayout rnGroup = new LinearLayout(this);
        rnGroup.setOrientation(LinearLayout.VERTICAL);

        TextView rnWetLabel = new TextView(this);
        rnWetLabel.setText(getString(R.string.rnnoise_strength,
                Math.round(audio.getRnnoiseWet() * 100)));
        rnGroup.addView(rnWetLabel);
        SeekBar rnWet = new SeekBar(this);
        rnWet.setMax(100);
        rnWet.setProgress(Math.round(audio.getRnnoiseWet() * 100));
        rnWet.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                rnWetLabel.setText(getString(R.string.rnnoise_strength, progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        rnGroup.addView(rnWet);

        TextView rnGateLabel = new TextView(this);
        rnGateLabel.setText(getString(R.string.rnnoise_gate,
                Math.round(audio.getRnnoiseGate() * 100)));
        rnGroup.addView(rnGateLabel);
        SeekBar rnGate = new SeekBar(this);
        rnGate.setMax(100);
        rnGate.setProgress(Math.round(audio.getRnnoiseGate() * 100));
        rnGate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                rnGateLabel.setText(getString(R.string.rnnoise_gate, progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        rnGroup.addView(rnGate);
        box.addView(rnGroup);

        // One master switch decides whether any denoising runs. While it is off the
        // model list and every model's parameters are hidden, so there is nothing
        // to configure; turning it on reveals the list, and the selected model's
        // own sliders with it.
        Runnable syncModelControls = () -> {
            boolean on = noiseSuppression.isChecked();
            modelGroup.setVisibility(on ? View.VISIBLE : View.GONE);
            rnGroup.setVisibility(on && rnnoiseModel.isChecked() ? View.VISIBLE : View.GONE);
            dfnGroup.setVisibility(on && deepFilterModel.isChecked() ? View.VISIBLE : View.GONE);
        };
        models.setOnCheckedChangeListener((group, checkedId) -> syncModelControls.run());
        noiseSuppression.setOnCheckedChangeListener((button, checked) -> syncModelControls.run());
        syncModelControls.run();

        CheckBox eventSoundsBox = new CheckBox(this);
        eventSoundsBox.setText(R.string.event_sounds);
        eventSoundsBox.setChecked(eventSounds);
        box.addView(eventSoundsBox);

        TextView eventVolumeLabel = new TextView(this);
        eventVolumeLabel.setTextSize(13);
        eventVolumeLabel.setText(getString(R.string.event_sound_volume, eventSoundVolume));
        box.addView(eventVolumeLabel);
        SeekBar eventVolume = new SeekBar(this);
        eventVolume.setMax(100);
        eventVolume.setProgress(eventSoundVolume);
        eventVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                eventVolumeLabel.setText(getString(R.string.event_sound_volume, progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Play one prompt at the level being picked so the setting can be
                // judged by ear, then fall back to the saved level: Cancel leaves
                // the preference untouched, and the preview must not outlive it.
                if (sounds == null) return;
                sounds.setVolume(seekBar.getProgress() / 100f);
                sounds.memberJoined();
                sounds.setVolume(eventSoundVolume / 100f);
            }
        });
        box.addView(eventVolume);

        CheckBox earpiece = new CheckBox(this);
        earpiece.setText(R.string.use_earpiece);
        earpiece.setChecked(audio.isUseEarpiece());
        box.addView(earpiece);

        CheckBox voiceOptimization = new CheckBox(this);
        voiceOptimization.setText(R.string.voice_optimization);
        voiceOptimization.setChecked(audio.isVoiceOptimization());
        box.addView(voiceOptimization);

        CheckBox qqVoice = new CheckBox(this);
        qqVoice.setText(R.string.qq_voice_mode);
        qqVoice.setChecked(audio.isQqVoiceMode());
        box.addView(qqVoice);

        CheckBox rawMicrophone = new CheckBox(this);
        rawMicrophone.setText(R.string.raw_microphone);
        rawMicrophone.setChecked(audio.isRawMicrophone());
        box.addView(rawMicrophone);

        android.widget.ScrollView settingsScroll = new android.widget.ScrollView(this);
        settingsScroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings)
                .setView(settingsScroll)
                .setNeutralButton(R.string.about, (dialog, which) -> startActivity(
                        new android.content.Intent(RoomActivity.this, AboutActivity.class)
                                .putExtra(AboutActivity.EXTRA_GATE, false)))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    voice.setVolume(volume.getProgress() / 100f);
                    eventSounds = eventSoundsBox.isChecked();
                    eventSoundVolume = eventVolume.getProgress();
                    if (sounds != null) sounds.setVolume(eventSoundVolume / 100f);
                    getSharedPreferences("ts3", MODE_PRIVATE).edit()
                            .putBoolean("eventSounds", eventSounds)
                            .putInt("eventSoundVolume", eventSoundVolume)
                            .apply();
                    audio.setNoiseSuppressionEnabled(noiseSuppression.isChecked());
                    audio.setRnnoiseWet(rnWet.getProgress() / 100f);
                    audio.setRnnoiseGate(rnGate.getProgress() / 100f);
                    String selectedModel = pureVoxModel.isChecked()
                            ? AndroidAudioEngine.MODEL_PUREVOX
                            : dfsmnModel.isChecked()
                                    ? AndroidAudioEngine.MODEL_DFSMN
                                    : deepFilterModel.isChecked()
                                            ? AndroidAudioEngine.MODEL_DEEPFILTER
                                            : AndroidAudioEngine.MODEL_RNNOISE;
                    boolean modelChanged = !selectedModel.equals(audio.getDenoiseModel());
                    boolean dfnChanged = Math.round(audio.getDfnAttenuationDb()) != dfnAtten.getProgress()
                            || Math.round(audio.getDfnPostFilter() / 0.05f * 100f)
                            != dfnPost.getProgress();
                    audio.setDenoiseModel(selectedModel);
                    audio.setDfnAttenuationDb(dfnAtten.getProgress());
                    audio.setDfnPostFilter(dfnPost.getProgress() / 100f * 0.05f);
                    boolean routeChanged = audio.isUseEarpiece() != earpiece.isChecked()
                            || audio.isVoiceOptimization() != voiceOptimization.isChecked();
                    boolean rawChanged = audio.isRawMicrophone() != rawMicrophone.isChecked();
                    audio.setUseEarpiece(earpiece.isChecked());
                    audio.setVoiceOptimization(voiceOptimization.isChecked());
                    audio.setQqVoiceMode(qqVoice.isChecked());
                    audio.setRawMicrophone(rawMicrophone.isChecked());
                    getSharedPreferences("ts3", MODE_PRIVATE).edit()
                            .putBoolean("noiseSuppression", noiseSuppression.isChecked())
                            .putInt("rnnoiseWet", rnWet.getProgress())
                            .putInt("rnnoiseGate", rnGate.getProgress())
                            .putInt("micGain", micGain.getProgress())
                            .putInt("eqLow", Math.round(audio.getEqLowDb()))
                            .putInt("eqMid", Math.round(audio.getEqMidDb()))
                            .putInt("eqHigh", Math.round(audio.getEqHighDb()))
                            .putString("denoiseModel", selectedModel)
                            .putInt("dfnAttenDb", dfnAtten.getProgress())
                            .putInt("dfnPostFilter", dfnPost.getProgress())
                            .putBoolean("useEarpiece", earpiece.isChecked())
                            .putBoolean("voiceOptimization", voiceOptimization.isChecked())
                            .putBoolean("qqVoiceMode", qqVoice.isChecked())
                            .putBoolean("rawMicrophone", rawMicrophone.isChecked())
                            .apply();
                    if ((modelChanged || dfnChanged || rawChanged) && microphoneWanted
                            && audio.isMicrophoneRunning()) {
                        audio.stopMicrophone();
                        startMicrophone();
                    }
                    if (routeChanged) audio.restartPlayback();
                    if (routeChanged && microphoneWanted && audio.isMicrophoneRunning()) {
                        audio.stopMicrophone();
                        startMicrophone();
                    }
                    String selected = english.isChecked() ? "en" : "zh";
                    String previous = AppLanguage.get(this);
                    AppLanguage.set(this, selected);
                    if (!previous.equals(selected)) recreate();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * One EQ band row: a ±12 dB slider that applies live, so the change is audible
     * while dragging instead of only after OK.
     */
    private void addEqBand(LinearLayout parent, int titleRes, int band) {
        float value = band == 0 ? audio.getEqLowDb()
                : band == 1 ? audio.getEqMidDb() : audio.getEqHighDb();
        TextView label = new TextView(this);
        label.setText(getString(titleRes, Math.round(value)));
        parent.addView(label);
        SeekBar bar = new SeekBar(this);
        bar.setMax(24);
        bar.setProgress(Math.round(value) + 12);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float db = progress - 12;
                label.setText(getString(titleRes, Math.round(db)));
                if (band == 0) audio.setMicEq(db, audio.getEqMidDb(), audio.getEqHighDb());
                else if (band == 1) audio.setMicEq(audio.getEqLowDb(), db, audio.getEqHighDb());
                else audio.setMicEq(audio.getEqLowDb(), audio.getEqMidDb(), db);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        parent.addView(bar);
    }

    private void toggleSpeaker() {
        boolean muted = !voice.isSpeakerMuted();
        voice.setSpeakerMuted(muted);
        if (!muted) audio.startPlayback();
        updateSpeakerButton();
    }

    private void updateSpeakerButton() {
        speakerButton.setText(voice.isSpeakerMuted() ? R.string.speaker_off : R.string.speaker_on);
    }

    private boolean hasMicrophonePermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_MICROPHONE) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startMicrophone();
        } else {
            microphoneWanted = false;
            voice.setMicrophoneMuted(true);
            updateMicrophoneButton();
            Toast.makeText(this, R.string.microphone_required,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void leave() {
        session.setListener(null);
        audio.stopMicrophone();
        audio.stopPlayback();
        session.disconnect();
        finish();
    }

    private static String messageOf(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
