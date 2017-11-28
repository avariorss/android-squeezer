/*
 * Copyright (c) 2015 Google Inc.  All Rights Reserved.
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

import java.util.Map;

import uk.org.ngo.squeezer.framework.Item;
import uk.org.ngo.squeezer.itemlist.IServiceItemListCallback;
import uk.org.ngo.squeezer.model.Player;
import uk.org.ngo.squeezer.model.PlayerState;

/**
 * Interface implemented by all network clients of the server.
 */
interface SlimClient {

    void initialize();  // XXX Call this onCreate()?

    /**
     * Start a connection LMS. Connection progress/status will be reported via
     * {@link de.greenrobot.event.EventBus}.
     *
     * @param service The service hosting this connection.
     * @param host server running LMS
     * @param cliPort socket listening for CLI request
     * @param httpPort socket listening for http requests (if known)
     * @param userName username if LMS is password protected
     * @param password password if LMS is password protected
     */
    void startConnect(final SqueezeService service, String host, int cliPort, int httpPort,
                      final String userName, final String password);

    // XXX: Document
    void disconnect(boolean loginFailed);

    // XXX: Document
    boolean isConnected();

    // XXX: Document
    boolean isConnectInProgress();

    /**
     * Execute the supplied command.
     *
     * @param player if non null this command is for a specific player
     * @param command LMS command
     */
    void command(Player player, String command);

    /** Calls {@link #command(Player, String)} with a null player */
    void command(String command);

    /** Like {@link #command(Player, String)} but does nothing if player is null */
    void playerCommand(Player player, String command);

    void playerCommand(Player player, String[] cmd, Map<String, Object> params);

    //XXX subscriptions ?

    /**
     * Send an asynchronous request to the SqueezeboxServer for the specified items.
     * <p>
     * Items are requested in chunks of <code>R.integer.PageSize</code>, and returned
     * to the caller via the specified callback.
     * <p>
     * If start is zero, this will order one item, to quickly learn the number of items
     * from the server. When the server response with this item it is transferred to the
     * caller. The remaining items in the first page are then ordered, and transferred
     * to the caller when they arrive.
     * <p>
     * If start is < 0, it means the caller wants the entire list. They are ordered in
     * pages, and transferred to the caller as they arrive.
     * <p>
     * Otherwise request a page of items starting from start.
     * <p>
     *
     * @param player if non null this command is for a specific player
     * @param cmd Array of command terms, f.e. ['playlist', 'jump']
     * @param params Hash of parameters, f.e. {sort = new}. Passed to the server in the form "key:value", f.e. 'sort:new'.
     * @param start index of the first item to fetch. -1 means to fetch all items in chunks of pageSize
     * @param pageSize Number of items to fetch in each request.
     * @param callback Received items are returned in this.
     */
    <T extends Item> void requestItems(Player player, String[] cmd, Map<String, Object> params, int start, int pageSize, IServiceItemListCallback<T> callback);

    /**
     * Calls {@link #requestItems(Player, String[], Map, int, int, IServiceItemListCallback)}
     * with null player, a single command term and default page size
     */
    <T extends Item> void requestItems(String cmd, int start, int pageSize, IServiceItemListCallback<T> callback);

    /**
     * Calls {@link #requestItems(Player, String[], Map, int, int, IServiceItemListCallback)}
     * with a single command term and default page size
     */
    <T extends Item> void requestItems(Player player, String[] cmd, Map<String, Object> params, int start, IServiceItemListCallback<T> callback);

    /**
     * Calls {@link #requestItems(Player, String[], Map, int, IServiceItemListCallback)}
     * with a single command term
     */
    <T extends Item> void requestItems(Player player, String cmd, Map<String, Object> params, int start, IServiceItemListCallback<T> callback);

    /**
     * Calls {@link #requestItems(Player, String[], Map, int, int, IServiceItemListCallback)}
     * with null player and default page size
     */
    <T extends Item> void requestItems(String[] cmd, Map<String, Object> params, int start, IServiceItemListCallback<T> callback);

    /**
     * Calls {@link #requestItems(String[], Map, int, IServiceItemListCallback)}
     * with a single command term and default page size
     */
    <T extends Item> void requestItems(String cmd, Map<String, Object> params, int start, IServiceItemListCallback<T> callback);

    /**
     * Calls {@link #requestItems(String, Map, int, IServiceItemListCallback)}
     * without extra parameters
     */
    <T extends Item> void requestItems(String cmd, int start, IServiceItemListCallback<T> callback);

    /**
     * Notify that the specified client (activity) nno longer wants messages from LMS.
     * @param object messages receiver to remove
     */
    void cancelClientRequests(Object object);

    void requestPlayerStatus(Player newActivePlayer);

    void subscribePlayerStatus(Player newActivePlayer,
                               @PlayerState.PlayerSubscriptionType String subscriptionType);

    String[] getMediaDirs();

    String getPreferredAlbumSort();

    String getServerVersion();

    String encode(String s);
}