package ai.nc.varcospaces;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.hardware.display.DisplayManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.service.media.MediaBrowserService;
import android.util.Log;
import android.view.Display;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Vehicle media owner. Browsing never starts audio; every play is gated by CarUx.
 * Lifecycle/focus restoration does not start playback, but OEM transport commands may. */
public final class PlaybackService extends MediaBrowserService {
    public static final String LOCAL_BIND_ACTION = "ai.nc.varcospaces.BIND_PLAYBACK";
    private static final String ROOT_ID = "varco-spaces";
    private static final String CHANNEL_ID = "varco_spaces_playback";
    private static final String ACTION_PLAY = "ai.nc.varcospaces.PLAY";
    private static final String ACTION_PAUSE = "ai.nc.varcospaces.PAUSE";
    private static final String ACTION_STOP = "ai.nc.varcospaces.STOP";
    private static final int NOTIFICATION_ID = 1;
    public interface Observer { void changed(); }
    public final class LocalBinder extends Binder {
        public PlaybackService getService() { return PlaybackService.this; }
    }
    private final LocalBinder localBinder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Observer> observers = new LinkedHashSet<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (preferences, key) -> {
        if ("language".equals(key)) { notificationKey = ""; publishState(); notifyChildrenChanged(ROOT_ID); }
    };
    private AudioEngine audio;
    private ParkedState vehicle;
    private MediaSession session;
    private NotificationManager notifications;
    private SharedPreferences preferences;
    private JSONObject catalog = new JSONObject();
    private SceneCatalog library;
    private SceneSettings settings;
    private String sceneId = "", error = "", notificationKey = "";
    private boolean allowed, destroyed, initialized, foreground, changingScene;
    private int lastStartId;
    private final Lifecycle lifecycle = new Lifecycle();
    private final Runnable idleCleanup = this::cleanupIdle;

    /** Pure state transitions, shared by real service events and host regression tests. */
    static final class Lifecycle {
        boolean stopped = true;
        private boolean requested, keepForeground;
        void play() { requested = true; stopped = false; keepForeground = true; }
        void end() { requested = false; stopped = true; keepForeground = false; }
        void interrupt() { requested = false; keepForeground = false; }
        boolean active(boolean allowed, boolean finished) { return requested && allowed && !finished && !stopped; }
        boolean keepForeground() { return keepForeground; }
    }

    @Override public void onCreate() {
        super.onCreate();
        preferences = getSharedPreferences("spaces", MODE_PRIVATE);
        try (InputStream input = getAssets().open("catalog.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] block = new byte[8192]; int count;
            while ((count = input.read(block)) != -1) {
                if (bytes.size() + count > 1024 * 1024) throw new IllegalStateException("Catalog exceeds budget");
                bytes.write(block, 0, count);
            }
            catalog = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception failure) { error = "콘텐츠 정보를 불러오지 못했어요."; }
        library = SceneCatalog.read(catalog, path -> {
            try (InputStream ignored = getAssets().open(path)) { return true; }
            catch (Exception missing) { return false; }
        });
        settings = new SceneSettings(library, preferences.getAll());
        notifications = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Park Lounge", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Park Lounge audio playback"); channel.setSound(null, null);
        notifications.createNotificationChannel(channel);
        session = new MediaSession(this, "VARCO Spaces");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setSessionActivity(activityIntent());
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { logMediaCommand("onPlay", true); playScene(sceneId); }
            @Override public void onPause() { audio.pause(); }
            @Override public void onStop() { stopMedia(); }
            @Override public void onPlayFromMediaId(String mediaId, Bundle extras) { logMediaCommand("onPlayFromMediaId", true); playScene(mediaId); }
            @Override public void onPlayFromSearch(String query, Bundle extras) {
                logMediaCommand("onPlayFromSearch", true);
                String match = library.searchSelection(query, sceneId);
                if (match != null) playScene(match);
                else { error = english() ? "No matching soundscape." : "일치하는 공간이 없어요."; publishState(); }
            }
        }, handler);
        setSessionToken(session.getSessionToken());
        audio = new AudioEngine(this, handler, new AudioEngine.Listener() {
            @Override public boolean mayPlay() { return isAllowed(); }
            @Override public void changed(String message) {
                if (destroyed) return;
                error = message; if (!message.isEmpty()) lifecycle.interrupt();
                publishState();
            }
            @Override public void focusLost(int change) { lifecycle.interrupt(); }
            @Override public void timerExpired() { lifecycle.end(); }
        });
        // Restoration selects content and mix, never playback or an active session.
        sceneId = settings.selected();
        audio.selectScene(findScene(sceneId));
        audio.setTimer(preferences.getInt("timerMinutes", 10));
        restoreMix();
        persist();
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        initialized = true;
        // Associate the service gate with the driver's default display, even without an Activity.
        DisplayManager displays = (DisplayManager)getSystemService(DISPLAY_SERVICE);
        Display driverDisplay = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
        Context vehicleContext = driverDisplay == null ? this : createDisplayContext(driverDisplay);
        vehicle = new ParkedState(vehicleContext, handler, "PlaybackService", parked -> {
            if (destroyed) return;
            allowed = parked;
            if (!parked) interruptForSafety();
            else publishState();
        });
        vehicle.connect();
        publishState();
        // Started state is required for onTaskRemoved, even when a browser only binds.
        try { startService(new Intent(this, PlaybackService.class)); }
        catch (IllegalStateException blocked) { error = english() ? "Open the app to start playback." : "앱을 열고 재생해 주세요."; publishState(); }
    }

    @Override public IBinder onBind(Intent intent) {
        return intent != null && LOCAL_BIND_ACTION.equals(intent.getAction()) ? localBinder : super.onBind(intent);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (destroyed) return START_NOT_STICKY;
        lastStartId = startId;
        if (!ensureForeground()) return START_NOT_STICKY;
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PLAY.equals(action)) { logMediaCommand("ACTION_PLAY", false); playScene(sceneId); }
        else if (ACTION_PAUSE.equals(action)) audio.pause();
        else if (ACTION_STOP.equals(action)) stopMedia();
        scheduleIdleCleanup();
        // Restart/browsing alone never starts audio. OEM transport play remains supported.
        return START_NOT_STICKY;
    }
    @Override public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        String[] packages = getPackageManager().getPackagesForUid(clientUid);
        if (packages == null) return null;
        for (String name : packages) if (name.equals(clientPackageName)) return new BrowserRoot(ROOT_ID, null);
        return null;
    }
    @Override public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        result.sendResult(ROOT_ID.equals(parentId) ? mediaItems(library.all()) : Collections.emptyList());
    }
    @Override public void onLoadItem(String itemId, Result<MediaBrowser.MediaItem> result) {
        SceneCatalog.Item item = library.get(itemId);
        result.sendResult(item == null ? null : mediaItems(Collections.singletonList(item)).get(0));
    }
    private List<MediaBrowser.MediaItem> mediaItems(List<SceneCatalog.Item> items) {
        List<MediaBrowser.MediaItem> result = new ArrayList<>();
        for (SceneCatalog.Item item : items) {
            MediaDescription description = new MediaDescription.Builder().setMediaId(item.id)
                .setTitle(item.title(english())).setSubtitle(item.subtitle(english()))
                .setDescription(english() ? "Ambient soundscape · Made with VARCO Sound" : "앰비언트 사운드 · VARCO Sound로 제작").build();
            result.add(new MediaBrowser.MediaItem(description, MediaBrowser.MediaItem.FLAG_PLAYABLE));
        }
        return result;
    }

    public AudioEngine audio() { return audio; }
    public JSONObject catalog() { return catalog; }
    public String sceneId() { return sceneId; }
    public boolean hasSelectedScene() { return settings != null && settings.hasSelected(); }
    public String error() { return error; }
    public boolean isAllowed() { return allowed && !destroyed; }
    public void addObserver(Observer observer) { if (observer != null) { observers.add(observer); observer.changed(); } }
    public void removeObserver(Observer observer) { observers.remove(observer); }
    public void playScene(String id) {
        if (destroyed) return;
        if (!isAllowed()) { error = english() ? "Available while parked." : "주차 상태에서 이용할 수 있어요."; publishState(); return; }
        if (!isPlayable(id)) { error = english() ? "This soundscape is unavailable." : "이 공간을 재생할 수 없어요."; publishState(); return; }
        if (!sceneId.equals(id) || !hasSelectedScene()) selectScene(id);
        // Duplicate PLAY commands do not restart the loop or its timer.
        if (audio.isPlaying() || audio.isPreparing()) return;
        error = "";
        try { startService(new Intent(this, PlaybackService.class)); }
        catch (IllegalStateException blocked) {
            lifecycle.interrupt(); error = english() ? "Open the app to start playback." : "앱을 열고 재생해 주세요."; publishState(); return;
        }
        if (!ensureForeground()) return;
        lifecycle.play();
        audio.play(findScene(sceneId));
    }
    public void selectScene(String id) {
        if (!isAllowed() || !isPlayable(id)) { publishState(); return; }
        // Confirming the initial default also dismisses first-run selection.
        // Selecting the current scene later does not interrupt an existing break.
        if (id.equals(sceneId) && hasSelectedScene()) { publishState(); return; }
        persist();
        changingScene = true;
        try {
            lifecycle.end(); settings.select(id); sceneId = id; error = "";
            audio.selectScene(findScene(id)); restoreMix(); persist();
        } finally { changingScene = false; }
        publishState();
    }
    public void setVolume(int index, double value) {
        if (!isAllowed() || index < 0 || index >= 3) return;
        audio.setVolume(index, value); persist(); publishState();
    }
    public void setTimer(int minutes) {
        if (!isAllowed() || (minutes != 5 && minutes != 10 && minutes != 15)) return;
        audio.setTimer(minutes); persist(); publishState();
    }
    public void stopMedia() {
        if (audio == null) return;
        lifecycle.end(); audio.pause();
        audio.setTimer(audio.getTimer());
        if (session != null) session.setActive(false);
        cleanupIdle();
    }
    /** Either native parking reader can interrupt playback; this is not a user pause. */
    void interruptForSafety() {
        if (destroyed || audio == null) return;
        lifecycle.interrupt();
        if (audio.isPlaying() || audio.isPreparing()) {
            audio.pause();
            error = english() ? "Available while parked." : "주차 상태에서 이용할 수 있어요.";
        }
        // Clear active/foreground ownership even if the other reader still reports P.
        // Restoring either reader alone must not reactivate the session.
        publishState();
    }
    private void persist() {
        JSONArray volumes = audio.volumesJson();
        float[] values = new float[3];
        for (int i = 0; i < 3; i++) values[i] = (float)volumes.optDouble(i, 0);
        settings.remember(values);
        SharedPreferences.Editor editor = preferences.edit().putInt("timerMinutes", audio.getTimer());
        for (Map.Entry<String, Object> entry : settings.values().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(entry.getKey(), (String)value);
            else if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean)value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float)value);
        }
        editor.apply();
    }
    private void restoreMix() {
        float[] values = settings.volumes();
        for (int i = 0; i < 3; i++) audio.setVolume(i, values[i]);
    }
    private JSONObject findScene(String id) {
        JSONArray scenes = catalog.optJSONArray("scenes");
        for (int index = 0; scenes != null && index < scenes.length(); index++) {
            JSONObject scene = scenes.optJSONObject(index);
            if (scene != null && id != null && id.equals(scene.optString("id"))) return scene;
        }
        return null;
    }
    private boolean isPlayable(String id) {
        return library != null && library.get(id) != null;
    }
    private boolean english() { return preferences != null && "en".equals(preferences.getString("language", "ko")); }
    private void logMediaCommand(String command, boolean sessionRequest) {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        String controller = "unavailable";
        if (sessionRequest) {
            // Valid only within the MediaSession callback; do not log queries or extras.
            try { controller = session.getCurrentControllerInfo().getPackageName(); }
            catch (RuntimeException ignored) { }
        }
        Log.d("VarcoMediaCommand", "command=" + command
            + " origin=" + (sessionRequest ? "media-session" : "service-intent")
            + " controller=" + controller);
    }
    private String sceneTitle() {
        SceneCatalog.Item item = library == null ? null : library.get(sceneId);
        return item == null ? "Park Lounge" : item.title(english());
    }
    private void publishState() {
        if (!initialized || destroyed || changingScene || audio == null || session == null) return;
        boolean playing = audio.isPlaying(), preparing = audio.isPreparing();
        boolean finished = audio.remainingSeconds() == 0;
        int state = preparing ? PlaybackState.STATE_BUFFERING : playing ? PlaybackState.STATE_PLAYING
            : lifecycle.stopped || finished ? PlaybackState.STATE_STOPPED : PlaybackState.STATE_PAUSED;
        long position = Math.max(0, audio.getTimer() * 60_000L - audio.remainingSeconds() * 1000L);
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP
            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID | PlaybackState.ACTION_PLAY_FROM_SEARCH;
        PlaybackState.Builder playback = new PlaybackState.Builder().setActions(actions).setState(state, position, playing ? 1f : 0f);
        if (!error.isEmpty()) playback.setErrorMessage(error);
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID, sceneId)
            .putString(MediaMetadata.METADATA_KEY_TITLE, sceneTitle()).putString(MediaMetadata.METADATA_KEY_ARTIST, "Made with VARCO Sound")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "Park Lounge")
            .putString(MediaMetadata.METADATA_KEY_GENRE, "Music / Ambient soundscape")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, audio.getTimer() * 60_000L).build());
        session.setPlaybackState(playback.build());
        // A user pause keeps transport resume available. Focus loss, stop, expiry,
        // errors and the parked gate remove this session from active controls.
        session.setActive(lifecycle.active(isAllowed(), finished));
        if (foreground) {
            String key = state + ":" + allowed + ":" + sceneTitle() + ":" + audio.getTimer() + ":" + error;
            if (!key.equals(notificationKey)) { notifications.notify(NOTIFICATION_ID, notification()); notificationKey = key; }
        }
        for (Observer observer : new ArrayList<>(observers)) observer.changed();
        scheduleIdleCleanup();
    }
    private void scheduleIdleCleanup() {
        handler.removeCallbacks(idleCleanup);
        if (!destroyed && !lifecycle.keepForeground()) handler.post(idleCleanup);
    }
    private void cleanupIdle() {
        if (destroyed || lifecycle.keepForeground() || (audio != null && (audio.isPlaying() || audio.isPreparing()))) return;
        handler.removeCallbacks(idleCleanup);
        stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; notificationKey = "";
        // An older asynchronous start command must not stop a newer play request.
        // Bound Activity/browser clients keep the service and session object alive.
        if (lastStartId > 0) stopSelfResult(lastStartId);
    }
    private boolean ensureForeground() {
        try { startForeground(NOTIFICATION_ID, notification()); foreground = true; return true; }
        catch (RuntimeException unavailable) {
            lifecycle.interrupt();
            if (audio != null) audio.pause();
            error = english() ? "Unable to start audio playback." : "오디오 재생을 시작할 수 없어요.";
            publishState(); scheduleIdleCleanup(); return false;
        }
    }
    private PendingIntent activityIntent() {
        Intent intent = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private PendingIntent controlIntent(String action, int requestCode) {
        return PendingIntent.getService(this, requestCode, new Intent(this, PlaybackService.class).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification() {
        boolean running = audio != null && (audio.isPlaying() || audio.isPreparing());
        int icon = getResources().getIdentifier("ic_stat_sound", "drawable", getPackageName());
        if (icon == 0) icon = android.R.drawable.ic_media_play;
        String text = !allowed ? (english() ? "Available while parked" : "주차 상태에서 이용할 수 있어요")
            : running ? (english() ? "Soundscape playing" : "자연의 소리 재생 중")
            : (english() ? "Paused · tap to return" : "일시정지 · 앱으로 돌아가기");
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID).setSmallIcon(icon)
            .setContentTitle(sceneTitle()).setContentText(text).setContentIntent(activityIntent())
            .setCategory(Notification.CATEGORY_TRANSPORT).setOnlyAlertOnce(true).setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(running)
            .addAction(new Notification.Action.Builder(running ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                running ? (english() ? "Pause" : "일시정지") : (english() ? "Play" : "재생"),
                controlIntent(running ? ACTION_PAUSE : ACTION_PLAY, 1)).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                english() ? "Stop" : "정지", controlIntent(ACTION_STOP, 2)).build())
            .setDeleteIntent(controlIntent(ACTION_STOP, 2));
        if (session != null) builder.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1));
        return builder.build();
    }
    @Override public void onTaskRemoved(Intent rootIntent) {
        stopMedia(); stopSelf(); super.onTaskRemoved(rootIntent);
    }
    @Override public void onDestroy() {
        destroyed = true; initialized = false; allowed = false;
        if (preferences != null) preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        if (vehicle != null) vehicle.close();
        if (audio != null) audio.close();
        if (session != null) { session.setActive(false); session.release(); }
        stopForeground(STOP_FOREGROUND_REMOVE); foreground = false;
        handler.removeCallbacksAndMessages(null); observers.clear();
        super.onDestroy();
    }
}
