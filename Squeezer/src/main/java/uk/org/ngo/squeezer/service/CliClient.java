/*
 * Copyright (c) 2011 Kurt Aaholst <kaaholst@gmail.com>
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

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.google.common.base.Joiner;

import com.crashlytics.android.Crashlytics;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import de.greenrobot.event.EventBus;
import uk.org.ngo.squeezer.Preferences;
import uk.org.ngo.squeezer.R;
import uk.org.ngo.squeezer.Squeezer;
import uk.org.ngo.squeezer.Util;
import uk.org.ngo.squeezer.framework.Item;
import uk.org.ngo.squeezer.itemlist.IServiceItemListCallback;
import uk.org.ngo.squeezer.model.Album;
import uk.org.ngo.squeezer.model.Artist;
import uk.org.ngo.squeezer.model.Genre;
import uk.org.ngo.squeezer.model.MusicFolderItem;
import uk.org.ngo.squeezer.model.Player;
import uk.org.ngo.squeezer.model.PlayerState;
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

class CliClient extends AbstractClient {

    private static final String TAG = "CliClient";

    final ConnectionState connectionState = new ConnectionState();

    /** {@link java.util.regex.Pattern} that splits strings on spaces. */
    private static final Pattern mSpaceSplitPattern = Pattern.compile(" ");

    /**
     * Join multiple strings (skipping nulls) together with newlines.
     */
    private static final Joiner mNewlineJoiner = Joiner.on("\n").skipNulls();

    /** Map Player IDs to the {@link uk.org.ngo.squeezer.model.Player} with that ID. */
    private final Map<String, Player> mPlayers = new HashMap<String, Player>();

    private final AtomicReference<Player> mActivePlayer = new AtomicReference<Player>();

    /** Executor for off-main-thread work. */
    @NonNull private final ScheduledThreadPoolExecutor mExecutor = new ScheduledThreadPoolExecutor(1);

    /** The types of command handler. */
    enum HandlerList {
        GLOBAL,

        PREFIXED,

        PLAYER_SPECIFIC,

        GLOBAL_PLAYER_SPECIFIC,

        PREFIXED_PLAYER_SPECIFIC
    }

    /**
     * Represents a command that can be sent to the server using the extended query format.
     * <p>
     * Extended queries have the following structure:
     * <p>
     * <code>[&lt;playerid>] &lt;command> &lt;start> &lt;itemsPerResponse> &lt;tagged-params> ...</code>
     * <ul>
     *     <li><code>&lt;playerid></code> - unique player identifier</li>
     *     <li><code>&lt;command></code> - command to send</li>
     *     <li><code>&lt;start></code> - 0-based index of the first item to return</li>
     *     <li><code>&lt;itemsPerResponse></code> - number of items to return per chunk</li>
     *     <li><code>&lt;tagged-params></code> - one or more <code>tag:value</code> pairs</li>
     * </ul>
     */
    static class ExtendedQueryFormatCmd {
        private static final HashSet<HandlerList> PLAYER_SPECIFIC_HANDLER_LISTS =
                new HashSet<HandlerList>(Arrays.asList(HandlerList.PLAYER_SPECIFIC,
                        HandlerList.GLOBAL_PLAYER_SPECIFIC, HandlerList.PREFIXED_PLAYER_SPECIFIC));
        private static final HashSet<HandlerList> PREFIXED_HANDLER_LISTS = new HashSet<HandlerList>(
                Arrays.asList(HandlerList.PREFIXED, HandlerList.PREFIXED_PLAYER_SPECIFIC));

        final HandlerList handlerList;

        /** True if this is a player-specific command (i.e., the command should send a player ID). */
        final private boolean playerSpecific;


        final private boolean prefixed;

        final String cmd;

        final private Set<String> taggedParameters;

        final private SqueezeParserInfo[] parserInfos;

        /**
         * A command sent to the server.
         *
         * @param handlerList The command's type.
         * @param cmd The command to send.
         * @param taggedParameters Tagged parameters to send
         * @param parserInfos ?
         */
        public ExtendedQueryFormatCmd(HandlerList handlerList, String cmd,
                                      Set<String> taggedParameters, SqueezeParserInfo... parserInfos) {
            this.handlerList = handlerList;
            playerSpecific = PLAYER_SPECIFIC_HANDLER_LISTS.contains(handlerList);
            prefixed = PREFIXED_HANDLER_LISTS.contains(handlerList);
            this.cmd = cmd;
            this.taggedParameters = taggedParameters;
            this.parserInfos = parserInfos;
        }

        public ExtendedQueryFormatCmd(String cmd, Set<String> taggedParameters,
                                      ListHandler<? extends Item> handler, String... columns) {
            this(HandlerList.GLOBAL, cmd, taggedParameters, new SqueezeParserInfo(handler, columns));
        }

        /**
         * A global command to the server where items in the response have a delimiter other than "id:".
         *
         * @param cmd The command to send to the server.
         * @param taggedParameters The keys for any tagged parameters to send.
         * @param itemDelimiter The identifier of the tagged parameter that marks the start of
         *    a new block of information.
         * @param handler The handler used to construct new model objects from the response.
         */
        public ExtendedQueryFormatCmd(String cmd, Set<String> taggedParameters,
                                      String itemDelimiter, ListHandler<? extends Item> handler) {
            this(HandlerList.GLOBAL, cmd, taggedParameters, new SqueezeParserInfo(itemDelimiter, handler));
        }

        /**
         * A command to the server where items in the response are delimited by id: tags.
         *
         * @param cmd The command to send to the server.
         * @param taggedParameters The keys for any tagged parameters to send.
         * @param handler The handler used to construct new model objects from the response.
         */
        public ExtendedQueryFormatCmd(String cmd, Set<String> taggedParameters,
                ListHandler<? extends Item> handler) {
            this(HandlerList.GLOBAL, cmd, taggedParameters, new SqueezeParserInfo(handler));
        }

        public String toString() {
            return "{ cmd:'" + cmd + "', list:" + handlerList + ", player specific:" + playerSpecific + ", prefixed:" + prefixed + " }";
        }

    }

    final ExtendedQueryFormatCmd[] extQueryFormatCmds = initializeExtQueryFormatCmds();

    final Map<String, ExtendedQueryFormatCmd> extQueryFormatCmdMap
            = initializeExtQueryFormatCmdMap();

    private ExtendedQueryFormatCmd[] initializeExtQueryFormatCmds() {
        List<ExtendedQueryFormatCmd> list = new ArrayList<ExtendedQueryFormatCmd>();

        list.add(
                new ExtendedQueryFormatCmd(
                        "players",
                        new HashSet<String>(Arrays.asList("playerprefs", "charset")),
                        "playerindex",
                        new BaseListHandler<Player>() {}
                )
        );
        list.add(
                new ExtendedQueryFormatCmd(
                        "artists",
                        new HashSet<String>(
                                Arrays.asList("search", "genre_id", "album_id", "tags", "charset")),
                        new ArtistListHandler()
                )
        );
        list.add(
                new ExtendedQueryFormatCmd(
                        "albums",
                        new HashSet<String>(
                                Arrays.asList("search", "genre_id", "artist_id", "track_id", "year",
                                        "compilation", "sort", "tags", "charset")),
                        new AlbumListHandler()
                )
        );
        list.add(
                new ExtendedQueryFormatCmd(
                        "years",
                        new HashSet<String>(Arrays.asList("charset")),
                        "year",
                        new BaseListHandler<Year>(){}
                )
        );
        list.add(
                new ExtendedQueryFormatCmd(
                        "genres",
                        new HashSet<String>(
                                Arrays.asList("search", "artist_id", "album_id", "track_id", "year",
                                        "tags", "charset")),
                        new GenreListHandler()
                )
        );
        list.add(
                new ExtendedQueryFormatCmd(
                        "musicfolder",
                        new HashSet<String>(Arrays.asList("folder_id", "url", "tags", "charset")),
                        new BaseListHandler<MusicFolderItem>(){}
                )
        );
        list.add(
                new ExtendedQueryFormatCmd(
                        "songs",
                        new HashSet<String>(
                                Arrays.asList("genre_id", "artist_id", "album_id", "year", "search",
                                        "tags", "sort", "charset")),
                        new SongListHandler()
                )
        );
        list.add(
                new ExtendedQueryFormatCmd(
                        "playlists",
                        new HashSet<String>(Arrays.asList("search", "tags", "charset")),
                        new BaseListHandler<Playlist>(){})
        );
        list.add(
                new ExtendedQueryFormatCmd(
                        "playlists tracks",
                        new HashSet<String>(Arrays.asList("playlist_id", "tags", "charset")),
                        "playlist index",
                        new SongListHandler())
        );
        list.add(
                new ExtendedQueryFormatCmd(
                        HandlerList.GLOBAL,
                        "search",
                        new HashSet<String>(Arrays.asList("term", "charset")),
                        new SqueezeParserInfo("genres_count", new GenreListHandler(), "genre_id"),
                        new SqueezeParserInfo("albums_count", new AlbumListHandler(), "album_id"),
                        new SqueezeParserInfo("contributors_count", new ArtistListHandler()
                                , "contributor_id"),
                        new SqueezeParserInfo("tracks_count", new SongListHandler(), "track_id")
                )
        );
        list.add(
                new ExtendedQueryFormatCmd(
                        HandlerList.PLAYER_SPECIFIC,
                        "status",
                        new HashSet<String>(Arrays.asList("tags", "charset", "subscribe")),
                        new SqueezeParserInfo("playlist_tracks", new SongListHandler(),
                                "playlist index")
                )
        );
        list.add(
                new ExtendedQueryFormatCmd(
                        "radios",
                        new HashSet<String>(Arrays.asList("sort", "charset")),
                        new PluginListHandler(),
                        "cmd", "name", "type", "icon", "weight"
                )

        );
        list.add(
                new ExtendedQueryFormatCmd(
                        "apps",
                        new HashSet<String>(Arrays.asList("sort", "charset")),
                        "icon",
                        new PluginListHandler())
        );
        list.add(
                new ExtendedQueryFormatCmd(
                        HandlerList.PREFIXED_PLAYER_SPECIFIC,
                        "items",
                        new HashSet<String>(
                                Arrays.asList("item_id", "search", "want_url", "charset")),
                        new SqueezeParserInfo(new BaseListHandler<PluginItem>(){}))
        );

        return list.toArray(new ExtendedQueryFormatCmd[list.size()]);
    }

    private Map<String, ExtendedQueryFormatCmd> initializeExtQueryFormatCmdMap() {
        Map<String, ExtendedQueryFormatCmd> map = new HashMap<String, ExtendedQueryFormatCmd>();
        for (ExtendedQueryFormatCmd cmd : extQueryFormatCmds) {
            map.put(cmd.cmd, cmd);
        }
        return map;
    }

    private final int pageSize = Squeezer.getContext().getResources().getInteger(R.integer.PageSize);

    CliClient(@NonNull EventBus eventBus) {
        super(eventBus);
    }

    // Call through to connectionState implementation for the moment.
    void disconnect(boolean loginFailed) {
        connectionState.disconnect(mEventBus, loginFailed);
        mPlayers.clear();
        mActivePlayer.set(null);
    }

    // All requests are tagged with a correlation id, which can be used when
    // asynchronous responses are received.
    private int _correlationid = 0;


    /**
     * Send the supplied commands to the SqueezeboxServer.
     * <p/>
     * <b>All</b> data to the server goes through this method
     * <p/>
     * <b>Note</b> don't call this from the main (UI) thread. If you are unsure if you are on the
     * main thread, then use {@link #sendCommand(String...)} instead.
     *
     * @param commands List of commands to send
     */
    synchronized void sendCommandImmediately(String... commands) {
        if (commands.length == 0) {
            return;
        }
        PrintWriter writer = connectionState.getSocketWriter();
        if (writer == null) {
            return;
        }

        String formattedCommands = mNewlineJoiner.join(commands);
        Log.v(TAG, "SEND: " + formattedCommands);

        // Make sure that username/password do not make it to Crashlytics.
        if (commands[0].startsWith("login ")) {
            Crashlytics.setString("lastCommands", "login [username] [password]");
        } else {
            Crashlytics.setString("lastCommands", formattedCommands);
        }

        writer.println(formattedCommands);
        writer.flush();
    }

    /**
     * Send the supplied commands to the SqueezeboxServer.
     * <p/>
     * This method takes care to avoid performing network operations on the main thread. Use {@link
     * #sendCommandImmediately(String...)} if you are sure you are not on the main thread (eg if
     * called from the listening thread).
     *
     * @param commands List of commands to send
     */
    void sendCommand(final String... commands) {
        if (Looper.getMainLooper() != Looper.myLooper()) {
            sendCommandImmediately(commands);
        } else {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    sendCommandImmediately(commands);
                }
            });
        }
    }

    /**
     * Send the specified command for the specified player to the SqueezeboxServer
     *
     * @param command The command to send
     */
    public void sendPlayerCommand(final Player player, final String command) {
        sendCommand(Util.encode(player.getId()) + " " + command);
    }

    /**
     * Keeps track of asynchronous request waiting for a reply
     * <p>
     * When a request is made, the callback is put this list, along with a
     * unique correlation id.
     * <p>
     * When the reply comes the callback is called, and the request is removed from this list.
     * <p>
     * When the client hosting callbacks goes away, all requests with callbacks hosted by it, is
     * removed from this list.
     * <p>
     * If a reply with with matching entry is this list comes in, it is discarded.
     */
    private final Map<Integer, IServiceItemListCallback> pendingRequests
            = new ConcurrentHashMap<Integer, IServiceItemListCallback>();

    public void cancelClientRequests(Object client) {
        for (Map.Entry<Integer, IServiceItemListCallback> entry : pendingRequests.entrySet()) {
            if (entry.getValue().getClient() == client) {
                Log.i(TAG, "cancel request: [" + entry.getKey() + ";" + entry.getValue() +"]");
                pendingRequests.remove(entry.getKey());
            }
        }
    }

    /**
     * Send an asynchronous request to the SqueezeboxServer for the specified items.
     * <p/>
     * Items are requested in chunks of <code>R.integer.PageSize</code>, and returned
     * to the caller via the specified callback.
     * <p/>
     * If start is zero, this will order one item, to quickly learn the number of items
     * from the server. When the server response with this item it is transferred to the
     * caller. The remaining items in the first page are then ordered, and transferred
     * to the caller when they arrive.
     * <p/>
     * If start is < 0, it means the caller wants the entire list. They are ordered in
     * pages, and transferred to the caller as they arrive.
     * <p/>
     * Otherwise request a page of items starting from start.
     * <p/>
     * See {@link #parseSqueezerList(CliClient.ExtendedQueryFormatCmd, List)} for details.
     *
     * @param playerId Id of the current player or null
     * @param cmd Identifies the type of items
     * @param start First item to return
     * @param parameters Item specific parameters for the request
     * @see #parseSqueezerList(CliClient.ExtendedQueryFormatCmd, List)
     */
    private void requestItems(String playerId, String cmd, int start, List<String> parameters, IServiceItemListCallback callback) {
        boolean full_list = (start < 0);

        pendingRequests.put(_correlationid, callback);
        final StringBuilder sb = new StringBuilder(
                cmd + " " + (full_list ? 0 : start) + " " + (start == 0 ? 1 : pageSize));
        if (playerId != null) {
            sb.insert(0, Util.encode(playerId) + " ");
        }
        if (parameters != null) {
            for (String parameter : parameters) {
                sb.append(" ").append(Util.encode(parameter));
            }
        }
        if (full_list)
            sb.append(" full_list:1");
        sb.append(" correlationid:");
        sb.append(_correlationid++);
        sendCommand(sb.toString());
    }

    void requestItems(String cmd, int start, List<String> parameters, IServiceItemListCallback callback) {
        requestItems(null, cmd, start, parameters, callback);
    }

    void requestItems(String cmd, int start, IServiceItemListCallback callback) {
        requestItems(cmd, start, null, callback);
    }

    void requestPlayerItems(@Nullable Player player, String cmd, int start, List<String> parameters, IServiceItemListCallback callback) {
        if (player == null) {
            return;
        }
        requestItems(player.getId(), cmd, start, parameters, callback);
    }

    /**
     * Data for {@link CliClient#parseSqueezerList(CliClient.ExtendedQueryFormatCmd, List)}
     *
     * @author kaa
     */
    private static class SqueezeParserInfo {

        private final Set<String> columns;

        private final String count_id;

        private final ListHandler<? extends Item> handler;

        /**
         * @param countId The label for the tag which contains the total number of results, normally
         * "count".
         * @param handler Callback to receive the parsed data.
         * @param columns If one column is specified, it is the item delimiter as defined for each
         *                extended query format command in the SqueezeServer CLI documentation.
         *                Multiple columns is supported to workaround of a bug in recent server
         *                versions.
         */
        public SqueezeParserInfo(String countId, ListHandler<? extends Item> handler, String... columns) {
            count_id = countId;
            this.columns = new HashSet<String>(Arrays.asList(columns));
            this.handler = handler;
        }

        public SqueezeParserInfo(String itemDelimiter, ListHandler<? extends Item> handler) {
            this("count", handler, itemDelimiter);
        }

        public SqueezeParserInfo(ListHandler<? extends Item> handler, String... columns) {
            this("count", handler, columns);
        }

        public SqueezeParserInfo(ListHandler<? extends Item> handler) {
            this("id", handler);
        }

        public boolean isComplete(Map<String, String> record) {
            for (String column : columns) {
                if (!record.containsKey(column)) return false;
            }
            return true;
        }
    }

    /**
     * Generic method to parse replies for queries in extended query format
     * <p/>
     * This is the control center for asynchronous and paging receiving of data from SqueezeServer.
     * <p/>
     * Transfer of each data type are started by an asynchronous request by one of the public method
     * in this module. This method will forward the data using the supplied {@link ListHandler}, and
     * and order the next page if necessary, repeating the current query parameters.
     * <p/>
     * Activities should just initiate the request, and supply a callback to receive a page of
     * data.
     *
     * @param cmd Describes of the CLI command
     * @param tokens List of tokens with value or key:value.
     */
    void parseSqueezerList(ExtendedQueryFormatCmd cmd, List<String> tokens) {
        Log.v(TAG, "Parsing list, cmd: " +cmd + ", tokens: " + tokens);

        final int ofs = mSpaceSplitPattern.split(cmd.cmd).length + (cmd.playerSpecific ? 1 : 0) + (cmd.prefixed ? 1 : 0);
        int actionsCount = 0;
        final String playerid = (cmd.playerSpecific ? tokens.get(0) + " " : "");
        final String prefix = (cmd.prefixed ? tokens.get(cmd.playerSpecific ? 1 : 0) + " " : "");
        final int start = Util.parseDecimalIntOrZero(tokens.get(ofs));
        final int itemsPerResponse = Util.parseDecimalIntOrZero(tokens.get(ofs + 1));

        int correlationId = 0;
        boolean rescan = false;
        boolean full_list = false;
        final Map<String, String> taggedParameters = new HashMap<String, String>();
        final Map<String, String> parameters = new HashMap<String, String>();
        final Set<String> countIdSet = new HashSet<String>();
        final Map<String, SqueezeParserInfo> itemDelimeterMap = new HashMap<String, SqueezeParserInfo>();
        final Map<String, Integer> counts = new HashMap<String, Integer>();
        final Map<String, String> record = new HashMap<String, String>();

        for (SqueezeParserInfo parserInfo : cmd.parserInfos) {
            parserInfo.handler.clear();
            countIdSet.add(parserInfo.count_id);
            for (String column : parserInfo.columns) itemDelimeterMap.put(column, parserInfo);
        }

        SqueezeParserInfo parserInfo = null;
        for (int idx = ofs + 2; idx < tokens.size(); idx++) {
            String token = tokens.get(idx);
            int colonPos = token.indexOf("%3A");
            if (colonPos == -1) {
                Log.e(TAG, "Expected colon in list token. '" + token + "'");
                return;
            }
            String key = Util.decode(token.substring(0, colonPos));
            String value = Util.decode(token.substring(colonPos + 3));
            Log.v(TAG, "key=" + key + ", value: " + value);

            if ("rescan".equals(key)) {
                rescan = (Util.parseDecimalIntOrZero(value) == 1);
            } else if ("full_list".equals(key)) {
                full_list = (Util.parseDecimalIntOrZero(value) == 1);
                taggedParameters.put(key, token);
            } else if ("correlationid".equals(key)) {
                correlationId = Util.parseDecimalIntOrZero(value);
                taggedParameters.put(key, token);
            } else if ("actions".equals(key)) {
                // Apparently squeezer returns some commands which are
                // included in the count of the current request
                actionsCount++;
            }
            if (countIdSet.contains(key)) {
                counts.put(key, Util.parseDecimalIntOrZero(value));
            } else {
                SqueezeParserInfo newParserInfo = itemDelimeterMap.get(key);
                if (newParserInfo != null && parserInfo != null && parserInfo.isComplete(record)) {
                    parserInfo.handler.add(record);
                    Log.v(TAG, "record=" + record);
                    record.clear();
                }
                if (newParserInfo != null) parserInfo = newParserInfo;
                if (parserInfo != null) {
                    record.put(key, value);
                } else if (cmd.taggedParameters.contains(key)) {
                    taggedParameters.put(key, token);
                } else {
                    parameters.put(key, value);
                }
            }
        }

        if (parserInfo != null && !record.isEmpty()) {
            parserInfo.handler.add(record);
            Log.v(TAG, "record=" + record);
        }

        // Process the lists for all the registered handlers
        int end = start + itemsPerResponse;
        int max = 0;
        IServiceItemListCallback callback = pendingRequests.get(correlationId);
        for (SqueezeParserInfo parser : cmd.parserInfos) {
            Integer count = counts.get(parser.count_id);
            int countValue = (count == null ? 0 : count);
            if (count != null || start == 0) {
                if (callback != null) {
                    callback.onItemsReceived(countValue - actionsCount, start, parameters, parser.handler.getItems(), parser.handler.getDataType());
                }
                if (countValue > max) {
                    max = countValue;
                }
            }
        }

        // If the client is still around check if we need to order more items,
        // otherwise were done, so remove the callback
        if (callback != null) {
            if ((full_list || end % pageSize != 0) && end < max) {
                int count = (end + pageSize > max ? max - end : full_list ? pageSize : pageSize - itemsPerResponse);
                StringBuilder cmdline = new StringBuilder();
                cmdline.append(playerid);
                cmdline.append(prefix);
                cmdline.append(cmd.cmd);
                cmdline.append(" ");
                cmdline.append(end);
                cmdline.append(" ");
                cmdline.append(count);
                for (String parameter : taggedParameters.values()) {
                    cmdline.append(" ").append(parameter);
                }
                sendCommandImmediately(cmdline.toString());
            } else
                pendingRequests.remove(correlationId);
        }
    }

    private class GenreListHandler extends BaseListHandler<Genre> {}

    private class ArtistListHandler extends BaseListHandler<Artist> {}

    private class AlbumListHandler extends BaseListHandler<Album> {}

    private class SongListHandler extends BaseListHandler<Song> {}

    private class PluginListHandler extends BaseListHandler<Plugin> {}

    // Shims around ConnectionState methods.

    void startConnect(final SqueezeService service, String hostPort, final String userName,
                      final String password) {
        connectionState.startConnect(service, mEventBus, mExecutor, this, hostPort, userName, password);

    }

    private interface CmdHandler {
        public void handle(List<String> tokens);
    }

    private final Map<String, CmdHandler> globalHandlers = initializeGlobalHandlers();

    private final Map<String, CmdHandler> prefixedHandlers = initializePrefixedHandlers();

    /**
     * Command handlers that are specific to a given player. The first token passed to any
     * handler is always the player ID.
     */
    private final Map<String, CmdHandler> playerSpecificHandlers
            = initializePlayerSpecificHandlers();

    private final Map<String, CmdHandler> globalPlayerSpecificHandlers
            = initializeGlobalPlayerSpecificHandlers();

    private final Map<String, CmdHandler> prefixedPlayerSpecificHandlers
            = initializePrefixedPlayerSpecificHandlers();

    private Map<String, CmdHandler> initializeGlobalHandlers() {
        Map<String, CmdHandler> handlers = new HashMap<String, CmdHandler>();

        for (final CliClient.ExtendedQueryFormatCmd cmd : extQueryFormatCmds) {
            if (cmd.handlerList == CliClient.HandlerList.GLOBAL) {
                handlers.put(cmd.cmd, new CmdHandler() {
                    @Override
                    public void handle(List<String> tokens) {
                        parseSqueezerList(cmd, tokens);
                    }
                });
            }
        }
        handlers.put("playlists", new CmdHandler() {
            @Override
            public void handle(List<String> tokens) {
                if ("delete".equals(tokens.get(1))) {
                    ;
                } else if ("edit".equals(tokens.get(1))) {
                    ;
                } else if ("new".equals(tokens.get(1))) {
                    HashMap<String, String> tokenMap = parseTokens(tokens);
                    if (tokenMap.get("overwritten_playlist_id") != null) {
                        mEventBus.post(new PlaylistCreateFailed(Squeezer.getContext().getString(R.string.PLAYLIST_EXISTS_MESSAGE,
                                tokenMap.get("name"))));
                    }
                } else if ("rename".equals(tokens.get(1))) {
                    HashMap<String, String> tokenMap = parseTokens(tokens);
                    if (tokenMap.get("dry_run") != null) {
                        if (tokenMap.get("overwritten_playlist_id") != null) {
                            mEventBus.post(new PlaylistRenameFailed(Squeezer.getContext().getString(R.string.PLAYLIST_EXISTS_MESSAGE,
                                    tokenMap.get("newname"))));
                        } else {
                            sendCommandImmediately(
                                    "playlists rename playlist_id:" + tokenMap.get("playlist_id")
                                            + " newname:" + Util.encode(tokenMap.get("newname")));
                        }
                    }
                } else if ("tracks".equals(tokens.get(1))) {
                    parseSqueezerList(extQueryFormatCmdMap.get("playlists tracks"), tokens);
                } else {
                    parseSqueezerList(extQueryFormatCmdMap.get("playlists"), tokens);
                }
            }
        });
        handlers.put("login", new CmdHandler() {
            @Override
            public void handle(List<String> tokens) {
                Log.i(TAG, "Authenticated: " + tokens);
                onAuthenticated();
            }
        });
        handlers.put("pref", new CmdHandler() {
            @Override
            public void handle(List<String> tokens) {
                Log.i(TAG, "Preference received: " + tokens);
                if ("httpport".equals(tokens.get(1)) && tokens.size() >= 3) {
                    connectionState.setHttpPort(Integer.parseInt(tokens.get(2)));
                }
                if ("jivealbumsort".equals(tokens.get(1)) && tokens.size() >= 3) {
                    connectionState.setPreferedAlbumSort(tokens.get(2));
                }
                if ("mediadirs".equals(tokens.get(1)) && tokens.size() >= 3) {
                    connectionState.setMediaDirs(Util.decode(tokens.get(2)));
                }
            }
        });
        handlers.put("can", new CmdHandler() {
            @Override
            public void handle(List<String> tokens) {
                Log.i(TAG, "Capability received: " + tokens);
                if ("favorites".equals(tokens.get(1)) && tokens.size() >= 4) {
                    connectionState.setCanFavorites(Util.parseDecimalIntOrZero(tokens.get(3)) == 1);
                }
                if ("musicfolder".equals(tokens.get(1)) && tokens.size() >= 3) {
                    connectionState
                            .setCanMusicfolder(Util.parseDecimalIntOrZero(tokens.get(2)) == 1);
                }
                if ("myapps".equals(tokens.get(1)) && tokens.size() >= 4) {
                    connectionState.setCanMyApps(Util.parseDecimalIntOrZero(tokens.get(3)) == 1);
                }
                if ("randomplay".equals(tokens.get(1)) && tokens.size() >= 3) {
                    connectionState
                            .setCanRandomplay(Util.parseDecimalIntOrZero(tokens.get(2)) == 1);
                }
            }
        });
        handlers.put("getstring", new CmdHandler() {
            @Override
            public void handle(List<String> tokens) {
                int maxOrdinal = 0;
                Map<String, String> tokenMap = parseTokens(tokens);
                for (Map.Entry<String, String> entry : tokenMap.entrySet()) {
                    if (entry.getValue() != null) {
                        ServerString serverString = ServerString.valueOf(entry.getKey());
                        serverString.setLocalizedString(entry.getValue());
                        if (serverString.ordinal() > maxOrdinal) {
                            maxOrdinal = serverString.ordinal();
                        }
                    }
                }

                // Fetch the next strings until the list is completely translated
                if (maxOrdinal < ServerString.values().length - 1) {
                    sendCommandImmediately(
                            "getstring " + ServerString.values()[maxOrdinal + 1].name());
                }
            }
        });
        handlers.put("version", new CmdHandler() {
            /**
             * Seeing the <code>version</code> result indicates that the
             * handshake has completed (see
             * {@link ConnectionState#onCliPortConnectionEstablished(EventBus, CliClient, String, String)},
             * post a {@link HandshakeComplete} event.
             */
            @Override
            public void handle(List<String> tokens) {
                Log.i(TAG, "Version received: " + tokens);
                Crashlytics.setString("server_version", tokens.get(1));

                mEventBus.postSticky(new HandshakeComplete(
                        connectionState.canFavorites(), connectionState.canMusicfolder(),
                        connectionState.canMusicfolder(), connectionState.canRandomplay()));
            }
        });

        return handlers;
    }

    private Map<String, CmdHandler> initializePrefixedHandlers() {
        Map<String, CmdHandler> handlers = new HashMap<String, CmdHandler>();

        for (final CliClient.ExtendedQueryFormatCmd cmd : extQueryFormatCmds) {
            if (cmd.handlerList == CliClient.HandlerList.PREFIXED) {
                handlers.put(cmd.cmd, new CmdHandler() {
                    @Override
                    public void handle(List<String> tokens) {
                        parseSqueezerList(cmd, tokens);
                    }
                });
            }
        }

        return handlers;
    }

    /**
     * Initialise handlers for player-specific commands.
     * <p/>
     * All commands processed by these handlers start with the player ID.
     *
     * @return
     */
    private Map<String, CmdHandler> initializePlayerSpecificHandlers() {
        Map<String, CmdHandler> handlers = new HashMap<String, CmdHandler>();

        for (final CliClient.ExtendedQueryFormatCmd cmd : extQueryFormatCmds) {
            if (cmd.handlerList == CliClient.HandlerList.PLAYER_SPECIFIC) {
                handlers.put(cmd.cmd, new CmdHandler() {
                    @Override
                    public void handle(List<String> tokens) {
                        parseSqueezerList(cmd, tokens);
                    }
                });
            }
        }
        handlers.put("play", new CmdHandler() {
            @Override
            public void handle(List<String> tokens) {
                Log.v(TAG, "play registered");
                updatePlayStatus(Util.decode(tokens.get(0)), PlayerState.PlayStatus.play);
            }
        });
        handlers.put("stop", new CmdHandler() {
            @Override
            public void handle(List<String> tokens) {
                Log.v(TAG, "stop registered");
                updatePlayStatus(Util.decode(tokens.get(0)), PlayerState.PlayStatus.stop);
            }
        });
        handlers.put("pause", new CmdHandler() {
            /**
             * <code>&lt;playerid> pause &lt;0|1|></code>
             * @param tokens
             */
            @Override
            public void handle(List<String> tokens) {
                Log.v(TAG, "pause registered: " + tokens);
                updatePlayStatus(Util.decode(tokens.get(0)), parsePause(tokens.size() >= 3 ? tokens.get(2) : null));
            }
        });
        handlers.put("playlist", new CmdHandler() {
            @Override
            public void handle(List<String> tokens) {
                parsePlaylistNotification(tokens);
            }
        });

        return handlers;
    }

    private Map<String, CmdHandler> initializeGlobalPlayerSpecificHandlers() {
        Map<String, CmdHandler> handlers = new HashMap<String, CmdHandler>();

        // &lt;playerid> client &lt;new|disconnect|reconnect>
        handlers.put("client", new CmdHandler() {
            @Override
            public void handle(List<String> tokens) {
                Log.i(TAG, "client received: " + tokens);
                // Something has happened to the player list, we just fetch the full list again.
                //
                // Reasons to do this:
                //
                // Issuing a "<playerid> status" request will not return the same information that
                // "players" does, missing "model", "displaytype", "isplayer", "displaytype", and
                // "canpoweroff" information.

                fetchPlayers();
            }
        });
        handlers.put("status", new CmdHandler() {
            @Override
            public void handle(List<String> tokens) {
                if (tokens.size() >= 3 && "-".equals(tokens.get(2))) {
                    Player player = mPlayers.get(Util.decode(tokens.get(0)));

                    // XXX: Can we ever see a status for a player we don't know about?
                    // XXX: Maybe the better thing to do is to add it.
                    if (player == null)
                        return;

                    Log.d(TAG, "Status handler: current: " + player.getId() + ": active: " + getActivePlayerId());
                    PlayerState playerState = player.getPlayerState();

                    HashMap<String, String> tokenMap = parseTokens(tokens);

                    boolean unknownRepeatStatus = playerState.getRepeatStatus() == null;
                    boolean unknownShuffleStatus = playerState.getShuffleStatus() == null;

                    boolean changedPower = playerState.setPoweredOn(Util.parseDecimalIntOrZero(tokenMap.get("power")) == 1);
                    boolean changedShuffleStatus = playerState.setShuffleStatus(tokenMap.get("playlist shuffle"));
                    boolean changedRepeatStatus = playerState.setRepeatStatus(tokenMap.get("playlist repeat"));
                    boolean changedCurrentPlaylistIndex = playerState.setCurrentPlaylistIndex(Util.parseDecimalIntOrZero(tokenMap.get("playlist_cur_index")));
                    boolean changedCurrentPlaylist = playerState.setCurrentPlaylist(tokenMap.get("playlist_name"));
                    boolean changedSleep = playerState.setSleep(Util.parseDecimalIntOrZero(tokenMap.get("will_sleep_in")));
                    boolean changedSleepDuration = playerState.setSleepDuration(Util.parseDecimalIntOrZero(tokenMap.get("sleep")));
                    boolean changedSong = playerState.setCurrentSong(new Song(tokenMap));
                    boolean changedSongDuration = playerState.setCurrentSongDuration(Util.parseDecimalIntOrZero(tokenMap.get("duration")));
                    boolean changedSongTime = playerState.setCurrentTimeSecond(Util.parseDecimalIntOrZero(tokenMap.get("time")));
                    boolean changedVolume = playerState.setCurrentVolume(Util.parseDecimalIntOrZero(tokenMap.get("mixer volume")));
                    boolean changedSyncMaster = playerState.setSyncMaster(tokenMap.get("sync_master"));
                    boolean changedSyncSlaves = playerState.setSyncSlaves(Splitter.on(",").omitEmptyStrings().splitToList(Strings.nullToEmpty(tokenMap.get("sync_slaves"))));
                    boolean changedSubscription = playerState.setSubscriptionType(tokenMap.get("subscribe"));

                    player.setPlayerState(playerState);

                    // Kept as its own method because other methods call it, unlike the explicit
                    // calls to the callbacks below.
                    updatePlayStatus(player.getId(), tokenMap.get("mode"));

                    // XXX: Handled by onEvent(PlayStatusChanged) in the service.
                    //updatePlayerSubscription(player, calculateSubscriptionTypeFor(player));

                    // Note to self: The problem here is that with second-to-second updates enabled
                    // the playerlistactivity callback will be called every second.  Thinking that
                    // a better approach would be for clients to register a single callback and a
                    // bitmask of events they're interested in based on the change* variables.
                    // Each callback would be called a maximum of once, with the new player and a
                    // bitmask that corresponds to which changes happened (so the client can
                    // distinguish between the types of changes).

                    // Might also be worth investigating Otto as an event bus instead.

                    // Quick and dirty fix -- only call onPlayerStateReceived for changes to the
                    // player state (ignore changes to Song, SongDuration, SongTime).

                    if (changedPower || changedSleep || changedSleepDuration || changedVolume
                            || changedSong || changedSyncMaster || changedSyncSlaves) {
                        mEventBus.post(new PlayerStateChanged(player, playerState));
                    }

                    if (player.getId().equals(getActivePlayerId())) {
                        // Power status
                        if (changedPower) {
                            mEventBus.post(new PowerStatusChanged(
                                    !player.getPlayerState().isPoweredOn(),
                                    !player.getPlayerState().isPoweredOn()));
                        }

                        // Current song
                        if (changedSong) {
                            mEventBus.post(new MusicChanged(playerState));
                        }

                        // Shuffle status.
                        if (changedShuffleStatus) {
                            mEventBus.post(new ShuffleStatusChanged(
                                    unknownShuffleStatus, playerState.getShuffleStatus()));
                        }

                        // Repeat status.
                        if (changedRepeatStatus) {
                            mEventBus.post(new RepeatStatusChanged(
                                    unknownRepeatStatus, playerState.getRepeatStatus()));
                        }

                        // Position in song
                        if (changedSongDuration || changedSongTime) {
                            mEventBus.post(new SongTimeChanged(
                                    playerState.getCurrentTimeSecond(),
                                    playerState.getCurrentSongDuration()));
                        }
                    }
                } else {
                    parseSqueezerList(extQueryFormatCmdMap.get("status"), tokens);
                }
            }
        });
        handlers.put("prefset", new CmdHandler() {
            @Override
            public void handle(List<String> tokens) {
                Log.v(TAG, "Prefset received: " + tokens);
                if (tokens.size() > 4 && "server".equals(tokens.get(2)) && "volume".equals(
                        tokens.get(3))) {
                    Player player = mPlayers.get(Util.decode(tokens.get(0)));
                    int newVolume = Util.parseDecimalIntOrZero(tokens.get(4));
                    player.getPlayerState().setCurrentVolume(newVolume);
                    mEventBus.post(new PlayerVolume(newVolume, player));
                }
            }
        });

        return handlers;
    }

    private Map<String, CmdHandler> initializePrefixedPlayerSpecificHandlers() {
        Map<String, CmdHandler> handlers = new HashMap<String, CmdHandler>();

        for (final CliClient.ExtendedQueryFormatCmd cmd : extQueryFormatCmds) {
            if (cmd.handlerList == CliClient.HandlerList.PREFIXED_PLAYER_SPECIFIC) {
                handlers.put(cmd.cmd, new CmdHandler() {
                    @Override
                    public void handle(List<String> tokens) {
                        parseSqueezerList(cmd, tokens);
                    }
                });
            }
        }

        return handlers;
    }

    void onLineReceived(String serverLine) {
        Log.v(TAG, "RECV: " + serverLine);

        // Make sure that username/password do not make it to Crashlytics.
        if (serverLine.startsWith("login ")) {
            Crashlytics.setString("lastReceivedLine", "login [username] [password]");
        } else {
            Crashlytics.setString("lastReceivedLine", serverLine);
        }

        List<String> tokens = Arrays.asList(mSpaceSplitPattern.split(serverLine));
        if (tokens.size() < 2) {
            return;
        }

        CmdHandler handler;
        if ((handler = globalHandlers.get(tokens.get(0))) != null) {
            handler.handle(tokens);
            return;
        }
        if ((handler = prefixedHandlers.get(tokens.get(1))) != null) {
            handler.handle(tokens);
            return;
        }
        if ((handler = globalPlayerSpecificHandlers.get(tokens.get(1))) != null) {
            handler.handle(tokens);
            return;
        }

        // Player-specific commands for our active player.
        if (Util.decode(tokens.get(0)).equals(getActivePlayerId())) {
            if ((handler = playerSpecificHandlers.get(tokens.get(1))) != null) {
                handler.handle(tokens);
                return;
            }
            if (tokens.size() > 2
                    && (handler = prefixedPlayerSpecificHandlers.get(tokens.get(2))) != null) {
                handler.handle(tokens);
            }
        }
    }

    private HashMap<String, String> parseTokens(List<String> tokens) {
        HashMap<String, String> tokenMap = new HashMap<String, String>();
        String[] kv;
        for (String token : tokens) {
            kv = parseToken(token);
            if (kv.length == 0)
                continue;

            tokenMap.put(kv[0], kv[1]);
        }
        return tokenMap;
    }

    /**
     * Parse a token in to a key-value pair.  The value is optional.
     * <p/>
     * The token is assumed to be URL encoded, with the key and value separated by ':' (encoded
     * as '%3A').
     *
     * @param token The string to decode.
     * @return An array -- empty if token is null or empty, otherwise with two elements. The first
     * is the key, the second, which may be null, is the value. The elements are decoded.
     */
    private String[] parseToken(@Nullable String token) {
        String key, value;

        if (token == null || token.length() == 0) {
            return new String[]{};
        }

        int colonPos = token.indexOf("%3A");
        if (colonPos == -1) {
            key = Util.decode(token);
            value = null;
        } else {
            key = Util.decode(token.substring(0, colonPos));
            value = Util.decode(token.substring(colonPos + 3));
        }

        return new String[]{key, value};
    }

    private PlayerState.PlayStatus parsePause(String explicitPause) {
        if ("0".equals(explicitPause)) {
            return PlayerState.PlayStatus.play;
            //updatePlayStatus(PlayerState.PlayStatus.play);
        } else if ("1".equals(explicitPause)) {
            return PlayerState.PlayStatus.pause;
            //updatePlayStatus(PlayerState.PlayStatus.pause);
        }
        //updateAllPlayerSubscriptionStates();

        // XXX: This is probably not correct. Log and return something else?
        return PlayerState.PlayStatus.pause;
    }

    private void parsePlaylistNotification(List<String> tokens) {
        Log.v(TAG, "Playlist notification received: " + tokens);
        String notification = tokens.get(2);
        if ("newsong".equals(notification)) {
            // When we don't subscribe to the current players status, we rely
            // on playlist notifications and order song details here.
            // TODO keep track of subscribe status
            sendPlayerCommand(mActivePlayer.get(), "status - 1 tags:" + SqueezeService.SONGTAGS);
        } else if ("play".equals(notification)) {
            updatePlayStatus(Util.decode(tokens.get(0)), PlayerState.PlayStatus.play);
        } else if ("stop".equals(notification)) {
            updatePlayStatus(Util.decode(tokens.get(0)), PlayerState.PlayStatus.stop);
        } else if ("pause".equals(notification)) {
            updatePlayStatus(Util.decode(tokens.get(0)), parsePause(tokens.size() >= 4 ? tokens.get(3) : null));
        } else if ("addtracks".equals(notification)) {
            mEventBus.post(new PlaylistTracksAdded());
        } else if ("delete".equals(notification)) {
            mEventBus.post(new PlaylistTracksDeleted());
        }
    }

    private void updatePlayStatus(@NonNull String playerId, @NonNull String mode) {
        if ("play".equals(mode)) {
            updatePlayStatus(playerId, PlayerState.PlayStatus.play);
            return;
        }

        if ("stop".equals(mode)) {
            updatePlayStatus(playerId, PlayerState.PlayStatus.stop);
            return;
        }

        if ("pause".equals(mode)) {
            updatePlayStatus(playerId, PlayerState.PlayStatus.pause);
            return;
        }
    }
    private void updatePlayStatus(@NonNull String playerId, PlayerState.PlayStatus playStatus) {
        Player player = mPlayers.get(playerId);

        if (player == null) {
            return;
        }

        PlayerState playerState = player.getPlayerState();

        if (playerState.setPlayStatus(playStatus)) {
            mEventBus.post(new PlayStatusChanged(playStatus, player));
        }

    }

    /**
     * Handshake with the SqueezeServer, learn some of its supported features, and start listening
     * for asynchronous updates of server state.
     *
     * Note: Authentication may not actually have completed at this point. The server has
     * responded to the "login" request, but if the username/password pair was incorrect it
     * has (probably) not yet disconnected the socket. See
     * {@link uk.org.ngo.squeezer.service.ConnectionState.ListeningThread#run()} for the code
     * that determines whether authentication succeeded.
     */
    private void onAuthenticated() {
        mEventBus.post(new ConnectionChanged(ConnectionState.LOGIN_COMPLETED));
        fetchPlayers();
        sendCommandImmediately(
                "listen 1", // subscribe to all server notifications
                "can musicfolder ?", // learn music folder browsing support
                "can randomplay ?", // learn random play function functionality
                "can favorites items ?", // learn support for "Favorites" plugin
                "can myapps items ?", // learn support for "MyApps" plugin
                "pref httpport ?", // learn the HTTP port (needed for images)
                "pref jivealbumsort ?", // learn the preferred album sort order
                "pref mediadirs ?", // learn the base path(s) of the server music library

                // Fetch the version number. This must be the last thing
                // fetched, as seeing the result triggers the
                // "handshake is complete" logic elsewhere.
                "version ?"
        );
    }

    /**
     * Queries for all players known by the server.
     * </p>
     * Posts a PlayersChanged message if the list of players has changed.
     */
    private void fetchPlayers() {
        requestItems("players", -1, new IServiceItemListCallback<Player>() {
            private final HashMap<String, Player> players = new HashMap<String, Player>();

            @Override
            public void onItemsReceived(int count, int start, Map<String, String> parameters,
                                        List<Player> items, Class<Player> dataType) {
                for (Player player : items) {
                    players.put(player.getId(), player);
                }

                // If all players have been received then determine the new active player.
                if (start + items.size() >= count) {
                    if (players.equals(mPlayers)) {
                        return;
                    }

                    mPlayers.clear();
                    mActivePlayer.set(null);

                    mPlayers.putAll(players);
                    mActivePlayer.set(getPreferredPlayer());

                    // XXX: postSticky?
                    mEventBus.postSticky(new PlayersChanged(mPlayers, mActivePlayer.get()));
                }
            }

            @Override
            public Object getClient() {
                return CliClient.this;
            }
        });
    }

    /**
     * @return The player that should be chosen as the (new) active player. This is either the
     *     last active player (if known), the first player the server knows about if there are
     *     connected players, or null if there are no connected players.
     */
    private @Nullable Player getPreferredPlayer() {
        final SharedPreferences preferences = Squeezer.getContext().getSharedPreferences(Preferences.NAME,
                Context.MODE_PRIVATE);
        final String lastConnectedPlayer = preferences.getString(Preferences.KEY_LAST_PLAYER,
                null);
        Log.i(TAG, "lastConnectedPlayer was: " + lastConnectedPlayer);

        ArrayList<Player> players = new ArrayList<Player>(mPlayers.values());
        Log.i(TAG, "mPlayers empty?: " + mPlayers.isEmpty());
        for (Player player : players) {
            if (player.getId().equals(lastConnectedPlayer)) {
                return player;
            }
        }
        return !players.isEmpty() ? players.get(0) : null;
    }

    /**
     * Change the player that is controlled by Squeezer (the "active" player).
     *
     * @param newActivePlayer May be null, in which case no players are controlled.
     */
    void changeActivePlayer(@Nullable final Player newActivePlayer) {
        Player prevActivePlayer = mActivePlayer.get();

        // Do nothing if they player hasn't actually changed.
        if (prevActivePlayer == newActivePlayer) {
            return;
        }

        mActivePlayer.set(newActivePlayer);
        Log.i(TAG, "Active player now: " + newActivePlayer);

        // If this is a new player then start an async fetch of its status.
        if (newActivePlayer != null) {
            sendPlayerCommand(newActivePlayer, "status - 1 tags:" + SqueezeService.SONGTAGS);
        }

        // NOTE: this involves a write and can block (sqlite lookup via binder call), so
        // should be done off-thread, so we can process service requests & send our callback
        // as quickly as possible.
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final SharedPreferences preferences = Squeezer.getContext().getSharedPreferences(Preferences.NAME,
                        Squeezer.MODE_PRIVATE);
                SharedPreferences.Editor editor = preferences.edit();

                if (newActivePlayer == null) {
                    Log.v(TAG, "Clearing " + Preferences.KEY_LAST_PLAYER);
                    editor.remove(Preferences.KEY_LAST_PLAYER);
                } else {
                    Log.v(TAG, "Saving " + Preferences.KEY_LAST_PLAYER + "=" + newActivePlayer.getId());
                    editor.putString(Preferences.KEY_LAST_PLAYER, newActivePlayer.getId());
                }

                editor.commit();
            }
        });

        mEventBus.postSticky(new PlayersChanged(mPlayers, newActivePlayer));
    }

    @Nullable Player getActivePlayer() {
        return mActivePlayer.get();
    }

    @Nullable String getActivePlayerId() {
        Player player = mActivePlayer.get();
        if (player == null) {
            return null;
        }

        return player.getId();
    }

    boolean isConnected() {
        return connectionState.isConnected();
    }

    boolean isConnectInProgress() {
        return connectionState.isConnectInProgress();
    }

    int getHttpPort() {
        return connectionState.getHttpPort();
    }

    String getCurrentHost() {
        return connectionState.getCurrentHost();
    }

    String[] getMediaDirs() {
        return connectionState.getMediaDirs();
    }

    public String getPreferredAlbumSort() {
        return connectionState.getPreferredAlbumSort();
    }

}
