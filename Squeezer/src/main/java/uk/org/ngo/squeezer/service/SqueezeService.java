/*
 * Copyright (c) 2009 Google Inc.  All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.org.ngo.squeezer.service;

import android.annotation.TargetApi;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Base64;
import android.util.Log;

import com.crashlytics.android.Crashlytics;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import uk.org.ngo.squeezer.NowPlayingActivity;
import uk.org.ngo.squeezer.Preferences;
import uk.org.ngo.squeezer.R;
import uk.org.ngo.squeezer.Util;
import uk.org.ngo.squeezer.framework.FilterItem;
import uk.org.ngo.squeezer.framework.PlaylistItem;
import uk.org.ngo.squeezer.itemlist.IServiceItemListCallback;
import uk.org.ngo.squeezer.itemlist.dialog.AlbumViewDialog;
import uk.org.ngo.squeezer.itemlist.dialog.SongViewDialog;
import uk.org.ngo.squeezer.model.Album;
import uk.org.ngo.squeezer.model.Artist;
import uk.org.ngo.squeezer.model.Genre;
import uk.org.ngo.squeezer.model.MusicFolderItem;
import uk.org.ngo.squeezer.model.Player;
import uk.org.ngo.squeezer.model.PlayerState;
import uk.org.ngo.squeezer.model.PlayerState.PlayStatus;
import uk.org.ngo.squeezer.model.PlayerState.ShuffleStatus;
import uk.org.ngo.squeezer.model.Playlist;
import uk.org.ngo.squeezer.model.Plugin;
import uk.org.ngo.squeezer.model.PluginItem;
import uk.org.ngo.squeezer.model.Song;
import uk.org.ngo.squeezer.model.Year;
import uk.org.ngo.squeezer.service.event.ConnectionChanged;
import uk.org.ngo.squeezer.service.event.HandshakeComplete;
import uk.org.ngo.squeezer.service.event.MusicChanged;
import uk.org.ngo.squeezer.service.event.PlayStatusChanged;
import uk.org.ngo.squeezer.service.event.PlayerStateChanged;
import uk.org.ngo.squeezer.service.event.PlayerVolume;
import uk.org.ngo.squeezer.service.event.PlayersChanged;
import uk.org.ngo.squeezer.service.event.PlaylistCreateFailed;
import uk.org.ngo.squeezer.service.event.PlaylistRenameFailed;
import uk.org.ngo.squeezer.service.event.PlaylistTracksAdded;
import uk.org.ngo.squeezer.service.event.PlaylistTracksDeleted;
import uk.org.ngo.squeezer.service.event.PowerStatusChanged;
import uk.org.ngo.squeezer.service.event.RepeatStatusChanged;
import uk.org.ngo.squeezer.service.event.ShuffleStatusChanged;
import uk.org.ngo.squeezer.service.event.SongTimeChanged;
import uk.org.ngo.squeezer.util.Scrobble;


public class SqueezeService extends Service implements ServiceCallbackList.ServicePublisher {

    private static final String TAG = "SqueezeService";

    private static final int PLAYBACKSERVICE_STATUS = 1;

    /** {@link java.util.regex.Pattern} that splits strings on spaces. */
    private static final Pattern mSpaceSplitPattern = Pattern.compile(" ");

    private static final String ALBUMTAGS = "alyj";

    /**
     * Information that will be requested about songs.
     * <p/>
     * a: artist name<br/>
     * C: compilation (1 if true, missing otherwise)<br/>
     * d: duration, in seconds<br/>
     * e: album ID<br/>
     * j: coverart (1 if available, missing otherwise)<br/>
     * J: artwork_track_id (if available, missing otherwise)<br/>
     * K: URL to remote artwork<br/>
     * l: album name<br/>
     * s: artist id<br/>
     * t: tracknum, if known<br/>
     * x: 1, if this is a remote track<br/>
     * y: song year<br/>
     * u: Song file url
     */
    // This should probably be a field in Song.
    public static final String SONGTAGS = "aCdejJKlstxyu";

    final ScheduledThreadPoolExecutor mExecutor = new ScheduledThreadPoolExecutor(1);

    /** Service-specific eventbus. All events generated by the service will be sent here. */
    private final EventBus mEventBus = new EventBus();

    /** True if the handshake with the server has completed, otherwise false. */
    private volatile boolean mHandshakeComplete = false;

    /**
     * Keeps track of all subscriptions, so we can cancel all subscriptions for a client at once
     */
    final Map<ServiceCallback, ServiceCallbackList> callbacks = new ConcurrentHashMap<ServiceCallback, ServiceCallbackList>();

    @Override
    public void addClient(ServiceCallbackList callbackList, ServiceCallback item) {
        callbacks.put(item, callbackList);
    }

    @Override
    public void removeClient(ServiceCallback item) {
        callbacks.remove(item);
    }

    final CliClient cli = new CliClient(mEventBus, mExecutor);

    /**
     * Is scrobbling enabled?
     */
    private boolean scrobblingEnabled;

    /**
     * Was scrobbling enabled?
     */
    private boolean scrobblingPreviouslyEnabled;

    boolean mUpdateOngoingNotification;

    int mFadeInSecs;

    @Nullable String mUsername;

    @Nullable String mPassword;

    /** Map Player IDs to the {@link uk.org.ngo.squeezer.model.Player} with that ID. */
    private final Map<String, Player> mPlayers = new HashMap<String, Player>();

    /** The active player (the player to which commands are sent by default). */
    private final AtomicReference<Player> activePlayer = new AtomicReference<Player>();

    /**
     * Thrown when the service is asked to send a command to the server before the server
     * handshake completes.
     */
    public static class HandshakeNotCompleteException extends IllegalStateException {
        public HandshakeNotCompleteException() { super(); }
        public HandshakeNotCompleteException(String message) { super(message); }
        public HandshakeNotCompleteException(String message, Throwable cause) { super(message, cause); }
        public HandshakeNotCompleteException(Throwable cause) { super(cause); }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Clear leftover notification in case this service previously got killed while playing
        NotificationManager nm = (NotificationManager) getSystemService(
                Context.NOTIFICATION_SERVICE);
        nm.cancel(PLAYBACKSERVICE_STATUS);

        getPreferences();

        setWifiLock(((WifiManager) getSystemService(Context.WIFI_SERVICE)).createWifiLock(
                WifiManager.WIFI_MODE_FULL, "Squeezer_WifiLock"));

        mEventBus.register(this, 1);  // Get events before other subscribers
        cli.initialize();
    }

    private void getPreferences() {
        final SharedPreferences preferences = getSharedPreferences(Preferences.NAME, MODE_PRIVATE);
        scrobblingEnabled = preferences.getBoolean(Preferences.KEY_SCROBBLE_ENABLED, false);
        mFadeInSecs = preferences.getInt(Preferences.KEY_FADE_IN_SECS, 0);
        mUpdateOngoingNotification = preferences
                .getBoolean(Preferences.KEY_NOTIFY_OF_CONNECTION, false);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return (IBinder) squeezeService;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disconnect();
        mEventBus.unregister(this);
    }

    void disconnect() {
        disconnect(false);
    }

    void disconnect(boolean isServerDisconnect) {
        cli.disconnect(isServerDisconnect && !mHandshakeComplete);
    }

    private String getActivePlayerId() {
        return (activePlayer.get() != null ? activePlayer.get().getId() : null);
    }

    @Nullable
    public PlayerState getPlayerState(String playerId) {
        Player player = mPlayers.get(playerId);

        if (player == null)
            return null;

        return player.getPlayerState();
    }

    /**
     * Send the specified command for the active player to the SqueezeboxServer
     *
     * @param command The command to send
     */
    public void sendActivePlayerCommand(final String command) {
        Player player = activePlayer.get();
        if (player == null) {
            return;
        }
        cli.sendPlayerCommand(player, command);
    }

    @Nullable public PlayerState getActivePlayerState() {
        if (activePlayer.get() == null)
            return null;

        return activePlayer.get().getPlayerState();
    }

    /**
     * Updates the playing status of the current player.
     * <p/>
     * Updates the Wi-Fi lock and ongoing status notification as necessary.
     */
    public void onEvent(PlayStatusChanged event) {
        if (event.player.equals(activePlayer.get())) {
            updateWifiLock(event.player.getPlayerState().isPlaying());
            updateOngoingNotification();
        }

        updatePlayerSubscription(event.player, calculateSubscriptionTypeFor(event.player));
    }

    /**
     * Updates the shuffle status of the current player.
     * <p/>
     * If the shuffle status has changed then posts a
     * {@link ShuffleStatusChanged} message.
     *
     * @param shuffleStatus The new shuffle status.
     */
    private void updateShuffleStatus(ShuffleStatus shuffleStatus) {
        if (shuffleStatus != null && shuffleStatus != getActivePlayerState().getShuffleStatus()) {
            boolean wasUnknown = getActivePlayerState().getShuffleStatus() == null;
            getActivePlayerState().setShuffleStatus(shuffleStatus);
            mEventBus.post(new ShuffleStatusChanged(wasUnknown, shuffleStatus));
        }
    }

    /**
     * Change the player that is controlled by Squeezer (the "active" player).
     *
     * @param newActivePlayer May be null, in which case no players are controlled.
     */
    void changeActivePlayer(@Nullable final Player newActivePlayer) {
        activePlayer.set(newActivePlayer);
        updateAllPlayerSubscriptionStates();
    }

    /**
     * Adjusts the subscription to players' status updates.
     */
    private void updateAllPlayerSubscriptionStates() {
        for (Player player : mPlayers.values()) {
            updatePlayerSubscription(player, calculateSubscriptionTypeFor(player));
        }
    }

    /**
     * Determine the correct status subscription type for the given player, based on
     * how frequently we need to know its status.
     */
    private PlayerState.PlayerSubscriptionType calculateSubscriptionTypeFor(Player player) {
        Player activePlayer = this.activePlayer.get();

        if (mEventBus.hasSubscriberForEvent(PlayerStateChanged.class) ||
                (mEventBus.hasSubscriberForEvent(SongTimeChanged.class) && player.equals(activePlayer))) {
            if (player.equals(activePlayer)) {
                // If it's the active player then get second-to-second updates.
                return PlayerState.PlayerSubscriptionType.real_time;
            } else {
                // For other players get updates only when the player status changes...
                // ... unless the player has a sleep duration set. In that case we need
                // real_time updates, as on_change events are not fired as the will_sleep_in
                // timer counts down.
                if (player.getPlayerState().getSleep() > 0) {
                    return PlayerState.PlayerSubscriptionType.real_time;
                } else {
                    return PlayerState.PlayerSubscriptionType.on_change;
                }
            }
        } else {
            // Disable subscription for this player's status updates.
            return PlayerState.PlayerSubscriptionType.none;
        }
    }

    /**
     * Manage subscription to a player's status updates.
     *
     * @param player player to manage.
     * @param playerSubscriptionType the new subscription type
     */
    private void updatePlayerSubscription(Player player, PlayerState.PlayerSubscriptionType playerSubscriptionType) {
        PlayerState playerState = player.getPlayerState();

        // Do nothing if the player subscription type hasn't changed. This prevents sending a
        // subscription update "status" message which will be echoed back by the server and
        // trigger processing of the status message by the service.
        if (playerState != null) {
            if (playerState.getSubscriptionType() == playerSubscriptionType) {
                return;
            }
        }

        switch (playerSubscriptionType) {
            case none:
                cli.sendPlayerCommand(player, "status - 1 subscribe:- tags:" + SONGTAGS);
                break;

            case on_change:
                cli.sendPlayerCommand(player, "status - 1 subscribe:0 tags:" + SONGTAGS);
                break;

            case real_time:
                cli.sendPlayerCommand(player, "status - 1 subscribe:1 tags:" + SONGTAGS);
                break;

        }
    }

    /**
     * Manages the state of any ongoing notification based on the player and connection state.
     */
    private void updateOngoingNotification() {
        PlayerState activePlayerState = getActivePlayerState();

        // Update scrobble state, if either we're currently scrobbling, or we
        // were (to catch the case where we started scrobbling a song, and the
        // user went in to settings to disable scrobbling).
        if (scrobblingEnabled || scrobblingPreviouslyEnabled) {
            scrobblingPreviouslyEnabled = scrobblingEnabled;
            Scrobble.scrobbleFromPlayerState(this, activePlayerState);
        }

        Song currentSong = (activePlayerState != null ? activePlayerState.getCurrentSong() : null);
        boolean playing = (activePlayerState != null && activePlayerState.isPlaying());
        String playerName = activePlayer.get() != null ? activePlayer.get().getName() : "squeezer";

        if (!playing) {
            if (!mUpdateOngoingNotification) {
                clearOngoingNotification();
                return;
            }
        }

        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification status = new Notification();
        //status.contentView = views;
        Intent showNowPlaying = new Intent(this, NowPlayingActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent pIntent = PendingIntent.getActivity(this, 0, showNowPlaying, 0);
        if (playing && currentSong != null) {
            status.setLatestEventInfo(this,
                    getString(R.string.notification_playing_text, playerName), currentSong.getName(), pIntent);
            status.flags |= Notification.FLAG_ONGOING_EVENT;
            status.icon = R.drawable.stat_notify_musicplayer;
        } else {
            status.setLatestEventInfo(this,
                    getString(R.string.notification_connected_text, playerName), "-", pIntent);
            status.flags |= Notification.FLAG_ONGOING_EVENT;
            status.icon = R.drawable.ic_launcher;
        }
        nm.notify(PLAYBACKSERVICE_STATUS, status);
    }

    private void clearOngoingNotification() {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(PLAYBACKSERVICE_STATUS);
    }

    public void onEvent(ConnectionChanged event) {
        if (event.connectionState == ConnectionState.DISCONNECTED) {
            mPlayers.clear();
            mEventBus.removeAllStickyEvents();
            activePlayer.set(null);
            mHandshakeComplete = false;
            clearOngoingNotification();
        }
    }

    public void onEvent(HandshakeComplete event) {
        mHandshakeComplete = true;
        strings();
    }

    public void onEvent(MusicChanged event) {
        updateOngoingNotification();
    }

    public void onEvent(PlayersChanged event) {
        mPlayers.clear();
        mPlayers.putAll(event.players);
        changeActivePlayer(event.activePlayer);
    }

    /* Start an asynchronous fetch of the squeezeservers localized strings */
    private void strings() {
        cli.sendCommandImmediately("getstring " + ServerString.values()[0].name());
    }

    /** A download request will be passed to the download manager for each song called back to this */
    private final IServiceItemListCallback<Song> songDownloadCallback = new IServiceItemListCallback<Song>() {
        @Override
        public void onItemsReceived(int count, int start, Map<String, String> parameters, List<Song> items, Class<Song> dataType) {
            for (Song item : items) {
                downloadSong(item.getId(), item.getName(), item.getUrl());
            }
        }

        @Override
        public Object getClient() {
            return this;
        }
    };

    /**
     * For each item called to this:
     * If it is a folder: recursive lookup items in the folder
     * If is is a track: Enqueue a download request to the download manager
     */
    private final IServiceItemListCallback<MusicFolderItem> musicFolderDownloadCallback = new IServiceItemListCallback<MusicFolderItem>() {
        @Override
        public void onItemsReceived(int count, int start, Map<String, String> parameters, List<MusicFolderItem> items, Class<MusicFolderItem> dataType) {
            for (MusicFolderItem item : items) {
                squeezeService.downloadItem(item);
            }
        }

        @Override
        public Object getClient() {
            return this;
        }
    };

    @TargetApi(Build.VERSION_CODES.GINGERBREAD)
    private void downloadSong(String songId, String title, @NonNull String serverUrl) {
        if (songId == null) {
            return;
        }

        // If running on Gingerbread or greater use the Download Manager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            DownloadManager downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            Uri uri = Uri.parse(squeezeService.getSongDownloadUrl(songId));
            DownloadDatabase downloadDatabase = new DownloadDatabase(this);
            String localPath = getLocalFile(serverUrl);
            String tempFile = UUID.randomUUID().toString();
            String credentials = mUsername + ":" + mPassword;
            String base64EncodedCredentials = Base64.encodeToString(credentials.getBytes(), Base64.NO_WRAP);
            DownloadManager.Request request = new DownloadManager.Request(uri)
                    .setTitle(title)
                    .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_MUSIC, tempFile)
                    .setVisibleInDownloadsUi(false)
                    .addRequestHeader("Authorization", "Basic " + base64EncodedCredentials);
            long downloadId = downloadManager.enqueue(request);

            Crashlytics.log("Registering new download");
            Crashlytics.log("downloadId: " + downloadId);
            Crashlytics.log("tempFile: " + tempFile);
            Crashlytics.log("localPath: " + localPath);

            if (!downloadDatabase.registerDownload(downloadId, tempFile, localPath)) {
                Crashlytics.log(Log.WARN, TAG, "Could not register download entry for: " + downloadId);
                downloadManager.remove(downloadId);
            }
        }
    }

    /**
     * Tries to get the path relative to the server music library.
     * <p/>
     * If this is not possible resort to the last path segment of the server path.
     * In both cases replace dangerous characters by safe ones.
     */
    private String getLocalFile(@NonNull String serverUrl) {
        Uri serverUri = Uri.parse(serverUrl);
        String serverPath = serverUri.getPath();
        String mediaDir = null;
        String path = null;
        for (String dir : cli.getMediaDirs()) {
            if (serverPath.startsWith(dir)) {
                mediaDir = dir;
                break;
            }
        }
        if (mediaDir != null)
            path = serverPath.substring(mediaDir.length(), serverPath.length());
        else
            path = serverUri.getLastPathSegment();

        // Convert VFAT-unfriendly characters to "_".
        return path.replaceAll("[?<>\\\\:*|\"]", "_");
    }

    private WifiManager.WifiLock wifiLock;

    void setWifiLock(WifiManager.WifiLock wifiLock) {
        this.wifiLock = wifiLock;
    }

    void updateWifiLock(boolean state) {
        // TODO: this might be running in the wrong thread.  Is wifiLock thread-safe?
        if (state && !wifiLock.isHeld()) {
            Log.v(TAG, "Locking wifi while playing.");
            wifiLock.acquire();
        }
        if (!state && wifiLock.isHeld()) {
            Log.v(TAG, "Unlocking wifi.");
            try {
                wifiLock.release();
                // Seen a crash here with:
                //
                // Permission Denial: broadcastIntent() requesting a sticky
                // broadcast
                // from pid=29506, uid=10061 requires
                // android.permission.BROADCAST_STICKY
                //
                // Catching the exception (which seems harmless) seems better
                // than requesting an additional permission.

                // Seen a crash here with
                //
                // java.lang.RuntimeException: WifiLock under-locked
                // Squeezer_WifiLock
                //
                // Both crashes occurred when the wifi was disabled, on HTC Hero
                // devices running 2.1-update1.
            } catch (SecurityException e) {
                Log.v(TAG, "Caught odd SecurityException releasing wifilock");
            }
        }
    }

    private final ISqueezeService squeezeService = new SqueezeServiceBinder();
    private class SqueezeServiceBinder extends Binder implements ISqueezeService {

        @Override
        @NonNull
        public EventBus getEventBus() {
            return mEventBus;
        }

        @Override
        public void adjustVolumeTo(Player player, int newVolume) {
            cli.sendPlayerCommand(player, "mixer volume " + Math.min(100, Math.max(0, newVolume)));
        }

        @Override
        public void adjustVolumeTo(int newVolume) {
            sendActivePlayerCommand("mixer volume " + Math.min(100, Math.max(0, newVolume)));
        }

        @Override
        public void adjustVolumeBy(int delta) {
            if (delta > 0) {
                sendActivePlayerCommand("mixer volume %2B" + delta);
            } else if (delta < 0) {
                sendActivePlayerCommand("mixer volume " + delta);
            }
        }

        @Override
        public boolean isConnected() {
            return cli.isConnected();
        }

        @Override
        public boolean isConnectInProgress() {
            return cli.isConnectInProgress();
        }

        @Override
        public void startConnect(String hostPort, String userName, String password) {
            mUsername = userName;
            mPassword = password;
            cli.startConnect(SqueezeService.this, hostPort, userName, password);
        }

        @Override
        public void disconnect() {
            if (!isConnected()) {
                return;
            }
            SqueezeService.this.disconnect();
        }

        @Override
        public void powerOn() {
            sendActivePlayerCommand("power 1");
        }

        @Override
        public void powerOff() {
            sendActivePlayerCommand("power 0");
        }

        @Override
        public void togglePower(Player player) {
            cli.sendPlayerCommand(player, "power");
        }

        @Override
        public void playerRename(Player player, String newName) {
            cli.sendPlayerCommand(player, "name " + Util.encode(newName));
        }

        @Override
        public void sleep(Player player, int duration) {
            cli.sendPlayerCommand(player, "sleep " + duration);
        }

        @Override
        public void syncPlayerToPlayer(@NonNull Player slave, @NonNull String masterId) {
            Player master = mPlayers.get(masterId);
            cli.sendPlayerCommand(master, "sync " + Util.encode(slave.getId()));
        }

        @Override
        public void unsyncPlayer(@NonNull Player player) {
            cli.sendPlayerCommand(player, "sync -");
        }


        @Override
        @Nullable
        public PlayerState getActivePlayerState() {
            if (activePlayer == null) {
                return null;
            }
            Player p = activePlayer.get();
            if (p == null) {
                return null;
            }
            return getPlayerState(p.getId());
        }

        @Override
        @Nullable
        public PlayerState getPlayerState(String playerId) {
            return mPlayers.get(playerId).getPlayerState();
        }

        @Override
        public boolean canPowerOn() {
            PlayerState playerState = getActivePlayerState();
            return canPower() && playerState != null && !playerState.isPoweredOn();
        }

        @Override
        public boolean canPowerOff() {
            PlayerState playerState = getActivePlayerState();
            return canPower() && playerState != null && playerState.isPoweredOn();
        }

        private boolean canPower() {
            Player player = activePlayer.get();
            return cli.isConnected() && player != null && player.isCanpoweroff();
        }

        @Override
        public String preferredAlbumSort() throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            return cli.getPreferredAlbumSort();
        }

        @Override
        public void setPreferredAlbumSort(String preferredAlbumSort) {
            if (isConnected()) {
                cli.sendCommand("pref jivealbumsort " + Util.encode(preferredAlbumSort));
            }
        }

        private String fadeInSecs() {
            return mFadeInSecs > 0 ? " " + mFadeInSecs : "";
        }

        @Override
        public boolean togglePausePlay() {
            if (!isConnected()) {
                return false;
            }

            PlayerState activePlayerState = getActivePlayerState();

            // May be null (e.g., connected to a server with no connected
            // players. TODO: Handle this better, since it's not obvious in the
            // UI.
            if (activePlayerState == null)
                return false;

            PlayerState.PlayStatus playStatus = activePlayerState.getPlayStatus();

            // May be null -- race condition when connecting to a server that
            // has a player. Squeezer knows the player exists, but has not yet
            // determined its state.
            if (playStatus == null)
                return false;

            switch (playStatus) {
                case play:
                    // NOTE: we never send ambiguous "pause" toggle commands (without the '1')
                    // because then we'd get confused when they came back in to us, not being
                    // able to differentiate ours coming back on the listen channel vs. those
                    // of those idiots at the dinner party messing around.
                    sendActivePlayerCommand("pause 1");
                    break;
                case stop:
                    sendActivePlayerCommand("play" + fadeInSecs());
                    break;
                case pause:
                    sendActivePlayerCommand("pause 0" + fadeInSecs());
                    break;
            }
            return true;
        }

        @Override
        public boolean play() {
            if (!isConnected()) {
                return false;
            }
            sendActivePlayerCommand("play" + fadeInSecs());
            return true;
        }

        @Override
        public boolean stop() {
            if (!isConnected()) {
                return false;
            }
            sendActivePlayerCommand("stop");
            return true;
        }

        @Override
        public boolean nextTrack() {
            if (!isConnected() || !isPlaying()) {
                return false;
            }
            sendActivePlayerCommand("button jump_fwd");
            return true;
        }

        @Override
        public boolean previousTrack() {
            if (!isConnected() || !isPlaying()) {
                return false;
            }
            sendActivePlayerCommand("button jump_rew");
            return true;
        }

        @Override
        public boolean toggleShuffle() {
            if (!isConnected()) {
                return false;
            }
            sendActivePlayerCommand("playlist shuffle");
            return true;
        }

        @Override
        public boolean toggleRepeat() {
            if (!isConnected()) {
                return false;
            }
            sendActivePlayerCommand("playlist repeat");
            return true;
        }

        @Override
        public boolean playlistControl(String cmd, PlaylistItem playlistItem) {
            if (!isConnected()) {
                return false;
            }

            sendActivePlayerCommand(
                    "playlistcontrol cmd:" + cmd + " " + playlistItem.getPlaylistParameter());
            return true;
        }

        @Override
        public boolean randomPlay(String type) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            sendActivePlayerCommand("randomplay " + type);
            return true;
        }

        /**
         * Start playing the song in the current playlist at the given index.
         *
         * @param index the index to jump to
         */
        @Override
        public boolean playlistIndex(int index) {
            if (!isConnected()) {
                return false;
            }
            sendActivePlayerCommand("playlist index " + index + fadeInSecs());
            return true;
        }

        @Override
        public boolean playlistRemove(int index) {
            if (!isConnected()) {
                return false;
            }
            sendActivePlayerCommand("playlist delete " + index);
            return true;
        }

        @Override
        public boolean playlistMove(int fromIndex, int toIndex) {
            if (!isConnected()) {
                return false;
            }
            sendActivePlayerCommand("playlist move " + fromIndex + " " + toIndex);
            return true;
        }

        @Override
        public boolean playlistClear() {
            if (!isConnected()) {
                return false;
            }
            sendActivePlayerCommand("playlist clear");
            return true;
        }

        @Override
        public boolean playlistSave(String name) {
            if (!isConnected()) {
                return false;
            }
            sendActivePlayerCommand("playlist save " + Util.encode(name));
            return true;
        }

        @Override
        public boolean pluginPlaylistControl(Plugin plugin, String cmd, String itemId) {
            if (!isConnected()) {
                return false;
            }
            sendActivePlayerCommand(plugin.getId() + " playlist " + cmd + " item_id:" + itemId);
            return true;

        }

        private boolean isPlaying() {
            PlayerState playerState = getActivePlayerState();
            return playerState != null && playerState.isPlaying();
        }

        @Override
        public void setActivePlayer(final Player player) {
            cli.changeActivePlayer(player);
        }

        @Override
        @Nullable
        public Player getActivePlayer() {
            return activePlayer.get();
        }

        @Override
        public List<Player> getPlayers() {
            // TODO: Return a Collection, instead of casting? Or return an ImmutableList?
            return (List<Player>) new ArrayList<Player>(mPlayers.values());
        }

        @Override
        public PlayerState getPlayerState() {
            return getActivePlayerState();
        }

        /**
         * @return null if there is no active player, otherwise the name of the current playlist,
         *     which may be the empty string.
         */
        @Override
        @Nullable
        public String getCurrentPlaylist() {
            PlayerState playerState = getActivePlayerState();

            if (playerState == null)
                return null;

            return playerState.getCurrentPlaylist();
        }

        @Override
        public String getAlbumArtUrl(String artworkTrackId) throws HandshakeNotCompleteException {
            return getAbsoluteUrl(artworkTrackIdUrl(artworkTrackId));
        }

        private String artworkTrackIdUrl(String artworkTrackId) {
            return "/music/" + artworkTrackId + "/cover.jpg";
        }

        /**
         * Returns a URL to download a song.
         *
         * @param songId the song ID
         * @return The URL (as a string)
         */
        @Override
        public String getSongDownloadUrl(String songId) throws HandshakeNotCompleteException {
            return getAbsoluteUrl(songDownloadUrl(songId));
        }

        private String songDownloadUrl(String songId) {
            return "/music/" + songId + "/download";
        }

        @Override
        public String getIconUrl(String icon) throws HandshakeNotCompleteException {
            if (isRelative(icon))
                return getAbsoluteUrl(icon.startsWith("/") ? icon : '/' + icon);
            else
                return icon;
        }

        private String getAbsoluteUrl(String relativeUrl) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            Integer port = cli.getHttpPort();
            if (port == null || port == 0) {
                return "";
            }
            return "http://" + cli.getCurrentHost() + ":" + port + relativeUrl;
        }

        private boolean isRelative(String url) {
            return Uri.parse(url).isRelative();
        }

        @Override
        public boolean setSecondsElapsed(int seconds) {
            if (!isConnected()) {
                return false;
            }
            if (seconds < 0) {
                return false;
            }

            sendActivePlayerCommand("time " + seconds);

            return true;
        }

        @Override
        public void preferenceChanged(String key) {
            Log.i(TAG, "Preference changed: " + key);
            if (Preferences.KEY_NOTIFY_OF_CONNECTION.equals(key)) {
                updateOngoingNotification();
                return;
            }

            // If the server address changed then disconnect.
            if (key.startsWith(Preferences.KEY_SERVER_ADDRESS)) {
                disconnect();
                return;
            }

            getPreferences();
        }


        @Override
        public void cancelItemListRequests(Object client) {
            cli.cancelClientRequests(client);
        }

        @Override
        public void cancelSubscriptions(Object client) {
            for (Entry<ServiceCallback, ServiceCallbackList> entry : callbacks.entrySet()) {
                if (entry.getKey().getClient() == client) {
                    entry.getValue().unregister(entry.getKey());
                }
            }
            updateAllPlayerSubscriptionStates();
        }

        // XXX: Is this method needed? What calls it?
        @Override
        public void players() throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            //fetchPlayers();
        }

        /* Start an async fetch of the SqueezeboxServer's albums, which are matching the given parameters */
        @Override
        public void albums(IServiceItemListCallback<Album> callback, int start, String sortOrder, String searchString, FilterItem... filters) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            List<String> parameters = new ArrayList<String>();
            parameters.add("tags:" + ALBUMTAGS);
            parameters.add("sort:" + sortOrder);
            if (searchString != null && searchString.length() > 0) {
                parameters.add("search:" + searchString);
            }
            for (FilterItem filter : filters)
                if (filter != null)
                    parameters.add(filter.getFilterParameter());
            cli.requestItems("albums", start, parameters, callback);
        }


        /* Start an async fetch of the SqueezeboxServer's artists */
        @Override
        public void artists(IServiceItemListCallback<Artist> callback, int start, String searchString, FilterItem... filters) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            List<String> parameters = new ArrayList<String>();
            if (searchString != null && searchString.length() > 0) {
                parameters.add("search:" + searchString);
            }
            for (FilterItem filter : filters)
                if (filter != null)
                    parameters.add(filter.getFilterParameter());
            cli.requestItems("artists", start, parameters, callback);
        }

        /* Start an async fetch of the SqueezeboxServer's years */
        @Override
        public void years(int start, IServiceItemListCallback<Year> callback) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            cli.requestItems("years", start, callback);
        }

        /* Start an async fetch of the SqueezeboxServer's genres */
        @Override
        public void genres(int start, String searchString, IServiceItemListCallback<Genre> callback) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            List<String> parameters = new ArrayList<String>();
            if (searchString != null && searchString.length() > 0) {
                parameters.add("search:" + searchString);
            }
            cli.requestItems("genres", start, parameters, callback);
        }

        /**
         * Starts an async fetch of the contents of a SqueezerboxServer's music
         * folders in the given folderId.
         * <p>
         * folderId may be null, in which case the contents of the root music
         * folder are returned.
         * <p>
         * Results are returned through the given callback.
         *
         * @param start Where in the list of folders to start.
         * @param musicFolderItem The folder to view.
         * @param callback Results will be returned through this
         */
        @Override
        public void musicFolders(int start, MusicFolderItem musicFolderItem, IServiceItemListCallback<MusicFolderItem> callback) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }

            List<String> parameters = new ArrayList<String>();

            parameters.add("tags:u");//TODO only available from version 7.6 so instead keep track of path
            if (musicFolderItem != null) {
                parameters.add(musicFolderItem.getFilterParameter());
            }

            cli.requestItems("musicfolder", start, parameters, callback);
        }

        /* Start an async fetch of the SqueezeboxServer's songs */
        @Override
        public void songs(IServiceItemListCallback<Song> callback, int start, String sortOrder, String searchString, FilterItem... filters) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            List<String> parameters = new ArrayList<String>();
            parameters.add("tags:" + SONGTAGS);
            parameters.add("sort:" + sortOrder);
            if (searchString != null && searchString.length() > 0) {
                parameters.add("search:" + searchString);
            }
            for (FilterItem filter : filters)
                if (filter != null)
                    parameters.add(filter.getFilterParameter());
            cli.requestItems("songs", start, parameters, callback);
        }

        /* Start an async fetch of the SqueezeboxServer's current playlist */
        @Override
        public void currentPlaylist(int start, IServiceItemListCallback<Song> callback) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            cli.requestPlayerItems(activePlayer.get(), "status", start, Arrays.asList("tags:" + SONGTAGS), callback);
        }

        /* Start an async fetch of the songs of the supplied playlist */
        @Override
        public void playlistSongs(int start, Playlist playlist, IServiceItemListCallback<Song> callback) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            cli.requestItems("playlists tracks", start,
                    Arrays.asList(playlist.getFilterParameter(), "tags:" + SONGTAGS), callback);
        }

        /* Start an async fetch of the SqueezeboxServer's playlists */
        @Override
        public void playlists(int start, IServiceItemListCallback<Playlist> callback) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            cli.requestItems("playlists", start, callback);
        }

        @Override
        public boolean playlistsDelete(Playlist playlist) {
            if (!isConnected()) {
                return false;
            }
            cli.sendCommand("playlists delete " + playlist.getFilterParameter());
            return true;
        }

        @Override
        public boolean playlistsMove(Playlist playlist, int index, int toindex) {
            if (!isConnected()) {
                return false;
            }
            cli.sendCommand("playlists edit cmd:move " + playlist.getFilterParameter()
                    + " index:" + index + " toindex:" + toindex);
            return true;
        }

        @Override
        public boolean playlistsNew(String name) {
            if (!isConnected()) {
                return false;
            }
            cli.sendCommand("playlists new name:" + Util.encode(name));
            return true;
        }

        @Override
        public boolean playlistsRemove(Playlist playlist, int index) {
            if (!isConnected()) {
                return false;
            }
            cli.sendCommand("playlists edit cmd:delete " + playlist.getFilterParameter() + " index:"
                    + index);
            return true;
        }

        @Override
        public boolean playlistsRename(Playlist playlist, String newname) {
            if (!isConnected()) {
                return false;
            }
            cli.sendCommand(
                    "playlists rename " + playlist.getFilterParameter() + " dry_run:1 newname:"
                            + Util.encode(newname));
            return true;
        }

        /* Start an asynchronous search of the SqueezeboxServer's library */
        @Override
        public void search(int start, String searchString, IServiceItemListCallback itemListCallback) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }

            AlbumViewDialog.AlbumsSortOrder albumSortOrder = AlbumViewDialog.AlbumsSortOrder
                    .valueOf(
                            preferredAlbumSort());

            artists(itemListCallback, start, searchString);
            albums(itemListCallback, start, albumSortOrder.name().replace("__", ""), searchString);
            genres(start, searchString, itemListCallback);
            songs(itemListCallback, start, SongViewDialog.SongsSortOrder.title.name(), searchString);
        }

        /* Start an asynchronous fetch of the squeezeservers radio type plugins */
        @Override
        public void radios(int start, IServiceItemListCallback<Plugin> callback) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            cli.requestItems("radios", start, callback);
        }

        /* Start an asynchronous fetch of the squeezeservers radio application plugins */
        @Override
        public void apps(int start, IServiceItemListCallback<Plugin> callback) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            cli.requestItems("apps", start, callback);
        }


        /* Start an asynchronous fetch of the squeezeservers items of the given type */
        @Override
        public void pluginItems(int start, Plugin plugin, PluginItem parent, String search, IServiceItemListCallback<PluginItem> callback) throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            List<String> parameters = new ArrayList<String>();
            if (parent != null) {
                parameters.add("item_id:" + parent.getId());
            }
            if (search != null && search.length() > 0) {
                parameters.add("search:" + search);
            }
            cli.requestPlayerItems(activePlayer.get(), plugin.getId() + " items", start, parameters, callback);
        }

        @Override
        public void downloadItem(FilterItem item) throws HandshakeNotCompleteException {
            if (item instanceof Song) {
                Song song = (Song) item;
                if (!song.isRemote()) {
                    downloadSong(song.getId(), song.getName(), song.getUrl());
                }
            } else if (item instanceof Playlist) {
                playlistSongs(-1, (Playlist) item, songDownloadCallback);
            } else if (item instanceof MusicFolderItem) {
                MusicFolderItem musicFolderItem = (MusicFolderItem) item;
                if ("track".equals(musicFolderItem.getType())) {
                    String url = musicFolderItem.getUrl();
                    if (url != null) {
                        downloadSong(item.getId(), musicFolderItem.getName(), url);
                    }
                } else if ("folder".equals(musicFolderItem.getType())) {
                    musicFolders(-1, musicFolderItem, musicFolderDownloadCallback);
                }
            } else if (item != null) {
                songs(songDownloadCallback, -1, SongViewDialog.SongsSortOrder.title.name(), null, item);
            }
        }
    }

    /**
     * Calculate and set player subscription states every time a client of the bus
     * un/registers.
     * <p/>
     * For example, this ensures that if a new client subscribes and needs real
     * time updates, the player subscription states will be updated accordingly.
     */
    class EventBus extends de.greenrobot.event.EventBus {

        @Override
        public void register(Object subscriber) {
            super.register(subscriber);
            updateAllPlayerSubscriptionStates();
        }

        @Override
        public void register(Object subscriber, int priority) {
            super.register(subscriber, priority);
            updateAllPlayerSubscriptionStates();
        }

        @Override
        public void post(Object event) {
            Log.i("EventBus", "post() " + event.getClass().getSimpleName() + ": " + event);
            super.post(event);
        }

        @Override
        public void postSticky(Object event) {
            Log.i("EventBus", "postSticky() " + event.getClass().getSimpleName() + ": " + event);
            super.postSticky(event);
        }

        @Override
        public void registerSticky(Object subscriber) {
            super.registerSticky(subscriber);
            updateAllPlayerSubscriptionStates();
        }

        @Override
        public void registerSticky(Object subscriber, int priority) {
            super.registerSticky(subscriber, priority);
            updateAllPlayerSubscriptionStates();
        }

        @Override
        public synchronized void unregister(Object subscriber) {
            super.unregister(subscriber);
            updateAllPlayerSubscriptionStates();
        }
    }
}
