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

package uk.org.ngo.squeezer.framework;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import uk.org.ngo.squeezer.R;
import uk.org.ngo.squeezer.Util;
import uk.org.ngo.squeezer.model.Plugin;

/**
 * Base class for SqueezeServer data. Specializations must implement all the necessary boilerplate
 * code. This is okay for now, because we only have few data types.
 *
 * @author Kurt Aaholst
 */
public abstract class Item implements Parcelable {
    private String id;
    @NonNull private String name;
    public String text2;
    @NonNull private Uri icon;
    private String node;
    private int weight;
    private String type;

    public Action.NextWindow nextWindow;
    public Input input;
    public String inputValue;
    public Window window;
    public boolean doAction;
    public Action goAction;
    public Action playAction;
    public Action addAction;
    public Action insertAction;
    public Action moreAction;
    public List<Plugin> subItems;
    public boolean showBigArtwork;
    public int selectedIndex;
    public String[] choiceStrings;
    public Boolean checkbox;
    public Map<Boolean, Action> checkboxActions;
    public Boolean radio;
    public Slider slider;

    public Item() {
        name = "";
        icon = Uri.EMPTY;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    @NonNull
    public String getName() {
        return name;
    }

    public Item setName(@NonNull String name) {
        this.name = name;
        return this;
    }

    /**
     * @return Relative URL path to an icon for this radio or music service, for example
     * "plugins/Picks/html/images/icon.png"
     */
    @NonNull
    public Uri getIcon() {
        if (icon.equals(Uri.EMPTY) && window != null) {
            return window.icon;
        }
        return icon;
    }

    /**
     * @return Whether the song has artwork associated with it.
     */
    public boolean hasArtwork() {
        return ! (getIcon().equals(Uri.EMPTY));
    }

    /**
     * @return Icon resource for this item if it is embedded in the Squeezer app, or an empty icon.
     */
    public Drawable getIconDrawable(Context context) {
        @DrawableRes int foreground = getItemIcon();
        if (foreground != 0) {
            Drawable background = AppCompatResources.getDrawable(context, R.drawable.icon_background);
            Drawable icon = AppCompatResources.getDrawable(context, foreground);
            return new LayerDrawable(new Drawable[]{background, icon});
        }

        return AppCompatResources.getDrawable(context, getSlimIcon());
    }

    @DrawableRes private int getSlimIcon() {
        @DrawableRes Integer iconResource = slimIcons.get(id);
        return iconResource == null ? R.drawable.icon_pending_artwork : iconResource;
    }

    private static Map<String, Integer> slimIcons = initializeSlimIcons();

    private static Map<String, Integer> initializeSlimIcons() {
        Map<String, Integer> result = new HashMap<>();

        result.put("myMusic", R.drawable.icon_mymusic);
        result.put("myMusicArtists", R.drawable.icon_ml_artists);
        result.put("myMusicGenres", R.drawable.icon_ml_genres);
        result.put("myMusicYears", R.drawable.icon_ml_years);
        result.put("myMusicNewMusic", R.drawable.icon_ml_new_music);
        result.put("extras", R.drawable.icon_settings_adv);
        result.put("settings", R.drawable.icon_settings);
        result.put("settingsAlarm", R.drawable.icon_alarm);
        result.put("appletCustomizeHome", R.drawable.icon_settings_home);
        result.put("settingsPlayerNameChange", R.drawable.icon_settings_name);
        result.put("advancedSettings", R.drawable.icon_settings_adv);

        return result;
    }

    @DrawableRes private int getItemIcon() {
        @DrawableRes Integer iconResource = itemIcons.get(id);
        return iconResource == null ? 0 : iconResource;
    }

    private static Map<String, Integer> itemIcons = initializeItemIcons();

    private static Map<String, Integer> initializeItemIcons() {
        Map<String, Integer> result = new HashMap<>();

        result.put("radio", R.drawable.internet_radio);
        result.put("radios", R.drawable.internet_radio);
        result.put("favorites", R.drawable.favorites);
        result.put("globalSearch", R.drawable.search);
        result.put("homeSearchRecent", R.drawable.search);
        result.put("playerpower", R.drawable.power);
        result.put("myMusicSearch", R.drawable.search);
        result.put("myMusicSearchArtists", R.drawable.search);
        result.put("myMusicSearchAlbums", R.drawable.search);
        result.put("myMusicSearchSongs", R.drawable.search);
        result.put("myMusicSearchPlaylists", R.drawable.search);
        result.put("myMusicSearchRecent", R.drawable.search);
        result.put("myMusicAlbums", R.drawable.ml_albums);
        result.put("myMusicMusicFolder", R.drawable.ml_folder);
        result.put("myMusicPlaylists", R.drawable.ml_playlist);
        result.put("randomplay", R.drawable.ml_random);
        result.put("settingsShuffle", R.drawable.shuffle);
        result.put("settingsRepeat", R.drawable.settings_repeat);
        result.put("settingsAudio", R.drawable.settings_audio);
        result.put("settingsSleep", R.drawable.settings_sleep);
        result.put("settingsSync", R.drawable.settings_sync);

        return result;
    }


    public String getNode() {
        return node;
    }

    public int getWeight() {
        return weight;
    }

    public String getType() {
        return type;
    }


    public boolean isSelectable() {
        return (goAction != null || nextWindow != null || hasSubItems()|| node != null || checkbox != null);
    }

    public boolean hasContextMenu() {
        return (playAction != null || addAction != null || insertAction != null || moreAction != null || checkbox != null || radio != null);
    }


    public Item(Map<String, Object> record) {
        setId(getString(record, record.containsKey("cmd") ? "cmd" : "id"));
        splitItemText(getStringOrEmpty(record, record.containsKey("name") ? "name" : "text"));
        icon = getImageUrl(record, record.containsKey("icon-id") ? "icon-id" : "icon");
        node = getString(record, "node");
        weight = getInt(record, "weight");
        type = getString(record, "type");
        Map<String, Object> baseRecord = getRecord(record, "base");
        Map<String, Object> baseActions = (baseRecord != null ? getRecord(baseRecord, "actions") : null);
        Map<String, Object> baseWindow = (baseRecord != null ? getRecord(baseRecord, "window") : null);
        Map<String, Object> actionsRecord = getRecord(record, "actions");
        nextWindow = Action.NextWindow.fromString(getString(record, "nextWindow"));
        input = extractInput(getRecord(record, "input"));
        window = extractWindow(getRecord(record, "window"), baseWindow);

        // do takes precedence over go
        goAction = extractAction("do", baseActions, actionsRecord, record, baseRecord);
        doAction = (goAction != null);
        if (goAction == null) {
            // check if item instructs us to use a different action
            String goActionName = record.containsKey("goAction") ? getString(record, "goAction") : "go";
            goAction = extractAction(goActionName, baseActions, actionsRecord, record, baseRecord);
        }

        playAction = extractAction("play", baseActions, actionsRecord, record, baseRecord);
        addAction = extractAction("add", baseActions, actionsRecord, record, baseRecord);
        insertAction = extractAction("add-hold", baseActions, actionsRecord, record, baseRecord);
        moreAction = extractAction("more", baseActions, actionsRecord, record, baseRecord);
        if (moreAction != null) {
            moreAction.action.params.put("xmlBrowseInterimCM", 1);
        }

        subItems = extractSubItems((Object[]) record.get("item_loop"));
        showBigArtwork = record.containsKey("showBigArtwork");

        selectedIndex = getInt(record, "selectedIndex");
        choiceStrings = Util.getStringArray((Object[]) record.get("choiceStrings"));
        if (goAction != null && goAction.action != null && goAction.action.cmd.length == 0) {
            doAction = true;
        }

        if (record.containsKey("checkbox")) {
            checkbox = (getInt(record, "checkbox") != 0);
            checkboxActions = new HashMap<>();
            checkboxActions.put(true, extractAction("on", baseActions, actionsRecord, record, baseRecord));
            checkboxActions.put(false, extractAction("off", baseActions, actionsRecord, record, baseRecord));
        }

        if (record.containsKey("radio")) {
            radio = (getInt(record, "radio") != 0);
        }

        if (record.containsKey("slider")) {
            slider = new Slider();
            slider.min = getInt(record, "min");
            slider.max = getInt(record, "max");
            slider.adjust = getInt(record, "adjust");
            slider.initial = getInt(record, "initial");
            slider.sliderIcons = getString(record, "sliderIcons");
            slider.help = getString(record, "help");
        }
    }

    public Item(Parcel source) {
        setId(source.readString());
        name = source.readString();
        text2 = source.readString();
        icon = Uri.parse(source.readString());
        node = source.readString();
        weight = source.readInt();
        type = source.readString();
        nextWindow = Action.NextWindow.fromString(source.readString());
        input = Input.readFromParcel(source);
        window = source.readParcelable(getClass().getClassLoader());
        goAction = source.readParcelable(getClass().getClassLoader());
        playAction = source.readParcelable(getClass().getClassLoader());
        addAction = source.readParcelable(getClass().getClassLoader());
        insertAction = source.readParcelable(getClass().getClassLoader());
        moreAction = source.readParcelable(getClass().getClassLoader());
        subItems = source.createTypedArrayList(Plugin.CREATOR);
        doAction = (source.readByte() != 0);
        showBigArtwork = (source.readByte() != 0);
        selectedIndex = source.readInt();
        choiceStrings = source.createStringArray();
        checkbox = (Boolean) source.readValue(getClass().getClassLoader());
        if (checkbox != null) {
            checkboxActions = new HashMap<>();
            checkboxActions.put(true, (Action) source.readParcelable(getClass().getClassLoader()));
            checkboxActions.put(false, (Action) source.readParcelable(getClass().getClassLoader()));
        }
        radio = (Boolean) source.readValue(getClass().getClassLoader());
        slider = source.readParcelable(getClass().getClassLoader());
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(getId());
        dest.writeString(name);
        dest.writeString(text2);
        dest.writeString(icon.toString());
        dest.writeString(node);
        dest.writeInt(weight);
        dest.writeString(type);
        dest.writeString(nextWindow == null ? null : nextWindow.toString());
        Input.writeToParcel(dest, input);
        dest.writeParcelable(window, flags);
        dest.writeParcelable(goAction, flags);
        dest.writeParcelable(playAction, flags);
        dest.writeParcelable(addAction, flags);
        dest.writeParcelable(insertAction, flags);
        dest.writeParcelable(moreAction, flags);
        dest.writeTypedList(subItems);
        dest.writeByte((byte) (doAction ? 1 : 0));
        dest.writeByte((byte) (showBigArtwork ? 1 : 0));
        dest.writeInt(selectedIndex);
        dest.writeStringArray(choiceStrings);
        dest.writeValue(checkbox);
        if (checkbox != null) {
            dest.writeParcelable(checkboxActions.get(true), flags);
            dest.writeParcelable(checkboxActions.get(false), flags);
        }
        dest.writeValue(radio);
        dest.writeParcelable(slider, flags);
    }


    public boolean hasInput() {
        return hasInputField() || hasChoices();
    }

    public boolean hasInputField() {
        return (input != null);
    }

    public boolean hasChoices() {
        return (choiceStrings.length > 0);
    }

    public boolean hasSlider() {
        return (slider != null);
    }

    public boolean isInputReady() {
        return !TextUtils.isEmpty(inputValue);
    }

    public boolean hasSubItems() {
        return (subItems != null);
    }


    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public int hashCode() {
        return (getId() != null ? getId().hashCode() : 0);
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }

        if (o == null) {
            return false;
        }

        if (o.getClass() != getClass()) {
            // There is no guarantee that SqueezeServer items have globally unique IDs.
            return false;
        }

        // Both might be empty items. For example a Song initialised
        // with an empty token map, because no song is currently playing.
        if (getId() == null && ((Item) o).getId() == null) {
            return true;
        }

        return getId() != null && getId().equals(((Item) o).getId());
    }

    protected String toStringOpen() {
        return getClass().getSimpleName() + " { id: " + getId()
                + ", name: " + getName()
                + ", node: " + node
                + ", weight: " + getWeight()
                + ", go: " + goAction
                + ", play: " + playAction
                + ", add: " + addAction
                + ", insert: " + insertAction
                + ", more: " + moreAction
                + ", window: " + window;

    }

    @Override
    public String toString() {
        return toStringOpen() + " }";
    }


    private Map<String, Object> getRecord(Map<String, Object> record, String recordName) {
        return Util.getRecord(record, recordName);
    }

    protected int getInt(Map<String, Object> record, String fieldName) {
        return Util.getInt(record, fieldName);
    }

    protected int getInt(Map<String, Object> record, String fieldName, int defaultValue) {
        return Util.getInt(record, fieldName, defaultValue);
    }

    protected static String getString(Map<String, Object> record, String fieldName) {
        return Util.getString(record, fieldName);
    }

    @NonNull
    protected String getStringOrEmpty(Map<String, Object> record, String fieldName) {
        return Util.getStringOrEmpty(record, fieldName);
    }

    @NonNull
    private static Uri getImageUrl(Map<String, Object> record, String fieldName) {
        return Util.getImageUrl(record, fieldName);
    }

    private void splitItemText(String text) {
        // This happens enough for regular expressions to be ineffective
        int nameEnd = text.indexOf('\n');
        if (nameEnd > 0) {
            name = text.substring(0, nameEnd);
            text2 = text.substring(nameEnd+1);
        } else {
            name = text;
            text2 = "";
        }
    }

    public static Window extractWindow(Map<String, Object> itemWindow, Map<String, Object> baseWindow) {
        if (itemWindow == null && baseWindow == null) return null;

        Map<String, Object> params = new HashMap<>();
        if (baseWindow != null) params.putAll(baseWindow);
        if (itemWindow != null) params.putAll(itemWindow);

        Window window = new Window();
        window.windowId = getString(params, "windowId");
        window.text = getString(params, "text");
        window.textarea = getString(params, "textarea");
        window.textareaToken = getString(params, "textAreaToken");
        window.help = getString(params, "help");
        window.icon = getImageUrl(params, params.containsKey("icon-id") ? "icon-id" : "icon");
        window.titleStyle = getString(params, "titleStyle");

        String menuStyle = getString(params, "menuStyle");
        String windowStyle = getString(params, "windowStyle");
        window.windowStyle = Window.WindowStyle.get(windowStyle);
        if (window.windowStyle == null) {
            window.windowStyle = menu2window.get(menuStyle);
            if (window.windowStyle == null) {
                window.windowStyle = Window.WindowStyle.TEXT_ONLY;
            }
        }

        return window;
    }

    /**
     * legacy map of menuStyles to windowStyles
     * <p>
     * make an educated guess at window style when one is not sent but a menu style is
     */
    private static Map<String, Window.WindowStyle> menu2window = initializeMenu2Window();

    private static Map<String, Window.WindowStyle> initializeMenu2Window() {
        Map<String, Window.WindowStyle> result = new HashMap<>();

        result.put("album", Window.WindowStyle.ICON_LIST);
        result.put("playlist", Window.WindowStyle.PLAY_LIST);

        return result;
    }


    private Input extractInput(Map<String, Object> record) {
        if (record == null) return null;

        Input input = new Input();
        input.len = getInt(record, "len");
        input.softbutton1 = getString(record, "softbutton1");
        input.softbutton2 = getString(record, "softbutton2");
        input.inputStyle = getString(record, "_inputStyle");
        input.title = getString(record, "title");
        input.initialText = getString(record, "initialText");
        input.allowedChars = getString(record, "allowedChars");
        Map<String, Object> helpRecord = getRecord(record, "help");
        if (helpRecord != null) {
            input.help = new HelpText();
            input.help.text = getString(helpRecord, "text");
            input.help.token = getString(helpRecord, "token");
        }
        return input;
    }

    private Action extractAction(String actionName, Map<String, Object> baseActions, Map<String, Object> itemActions, Map<String, Object> record, Map<String, Object> baseRecord) {
        Map<String, Object> actionRecord = null;
        Map<String, Object> itemParams = null;

        Object itemAction = (itemActions != null ? itemActions.get(actionName) : null);
        if (itemAction instanceof Map) {
            actionRecord = (Map<String, Object>) itemAction;
        }
        if (actionRecord == null && baseActions != null) {
            Map<String, Object> baseAction = getRecord(baseActions, actionName);
            if (baseAction != null) {
                String itemsParams = (String) baseAction.get("itemsParams");
                if (itemsParams != null) {
                    itemParams = getRecord(record, itemsParams);
                    if (itemParams != null) {
                        actionRecord = baseAction;
                    }
                }
            }
        }
        if (actionRecord == null) return null;

        Action actionHolder = new Action();

        if (actionRecord.containsKey("choices")) {
            Object[] choices = (Object[]) actionRecord.get("choices");
            actionHolder.choices = new Action.JsonAction[choices.length];
            for (int i = 0; i < choices.length; i++) {
                actionRecord = (Map<String, Object>) choices[i];
                actionHolder.choices[i]= extractJsonAction(baseRecord, actionRecord, itemParams);
            }
        } else {
            actionHolder.action = extractJsonAction(baseRecord, actionRecord, itemParams);
        }

        return actionHolder;
    }

    private Action.JsonAction extractJsonAction(Map<String, Object> baseRecord, Map<String, Object> actionRecord, Map<String, Object> itemParams) {
        Action.JsonAction action = new Action.JsonAction();

        action.nextWindow = Action.NextWindow.fromString(getString(actionRecord, "nextWindow"));
        if (action.nextWindow == null) action.nextWindow = nextWindow;
        if (action.nextWindow == null && baseRecord != null)
            action.nextWindow = Action.NextWindow.fromString(getString(baseRecord, "nextWindow"));

        action.cmd = Util.getStringArray((Object[]) actionRecord.get("cmd"));
        action.params = new HashMap<>();
        Map<String, Object> params = getRecord(actionRecord, "params");
        if (params != null) {
            action.params.putAll(params);
        }
        if (itemParams != null) {
            action.params.putAll(itemParams);
        }
        action.params.put("useContextMenu", "1");


        // Work around an issue in LMS which assumes the number of sub items equals itemsPerResponse.
        // This causes the playControl action of our initial request of one item, to not include the
        // "Play All" item, because it thinks there is only one item.
        if (getInt(action.params, "_quantity") == 1) {
            action.params.put("_quantity", 2);
        }

        Map<String, Object> windowRecord = getRecord(actionRecord, "window");
        if (windowRecord != null) {
            action.window = new Action.ActionWindow(getInt(windowRecord, "isContextMenu") != 0);
        }

        // LMS may send isContextMenu in the itemParams, but this is ignored by squeezeplay, so we must do the same.
        action.isContextMenu = (params != null && params.containsKey("isContextMenu")) || (action.window != null && action.window.isContextMenu);

        return action;
    }

    private List<Plugin> extractSubItems(Object[] item_loop) {
        if (item_loop != null) {
            List<Plugin> items = new ArrayList<>();
            for (Object item_d : item_loop) {
                Map<String, Object> record = (Map<String, Object>) item_d;
                items.add(new Plugin(record));
            }
            return items;
        }

        return null;
    }

}
