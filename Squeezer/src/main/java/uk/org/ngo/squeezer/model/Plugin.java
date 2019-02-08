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

package uk.org.ngo.squeezer.model;

import android.os.Parcel;

import java.util.Map;

import uk.org.ngo.squeezer.framework.Item;


public class Plugin extends Item {
    private String name;

    @Override
    public String getName() {
        return name;
    }

    public Plugin setName(String name) {
        this.name = name;
        return this;
    }

    public Plugin(Map<String, Object> record) {
        super(record);
        name = getString(record, record.containsKey("name") ? "name" : "text");
    }

    public static final Creator<Plugin> CREATOR = new Creator<Plugin>() {
        @Override
        public Plugin[] newArray(int size) {
            return new Plugin[size];
        }

        @Override
        public Plugin createFromParcel(Parcel source) {
            return new Plugin(source);
        }
    };

    private Plugin(Parcel source) {
        super(source);
        name = source.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString(name);
    }

}
