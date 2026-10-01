/*
 * This file is part of muCommander, http://www.mucommander.com
 * Copyright (C) 2002-2012 Maxence Bernard
 *
 * muCommander is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * muCommander is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */


package com.mucommander.bookmark;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents a bookmark.
 * <p>Bookmarks are simple name/location pairs:
 * <ul>
 *   <li>The name is a String describing the bookmark.</li>
 *   <li>
 *     The location should designate a path or file URL. The designated location may not exist or may not even be
 *     a valid path or URL, so it is up to classes that call {@link #getLocation()} to deal with it appropriately.
 *   </li>
 * </ul>
 *
 * @author Maxence Bernard
 */
public class Bookmark implements Cloneable {
    private String name;
    private String location;
    private String parent;

    /**
     * Protocol-specific properties that belong to the location but are absent from its string form,
     * such as an SFTP server's SSH key path. Created on first use, as most bookmarks carry none.
     */
    private Map<String, String> properties;


    /**
     * Creates a new Bookmark using the given name and location.
     *
     * @param name name given to this bookmark
     * @param location location (path or URL) this bookmark points to
     */
    public Bookmark(String name, String location, String parent) {
        // Use setters to checks for null values
        setName(name);
        setLocation(location);
        setParent(parent);
    }


    /**
     * Returns this bookmark's name.
     * @return this bookmark's name.
     * @see    #setName(String)
     */
    public String getName() {
        return name;
    }


    /**
     * Changes this bookmark's name to the given one and fires an event to registered {@link BookmarkListener}
     * instances.
     * @param newName bookmark's new name.
     * @see           #getName()
     */
    public void setName(String newName) {
        if (newName == null) {
            newName = "";
        }

        if (!newName.equals(this.name)) {
            this.name = newName;
            // Notify registered listeners of the change
            BookmarkManager.fireBookmarksChanged();
        }
    }


    /**
     * Returns this bookmark's location which should normally designate a path or file URL, but which isn't
     * necessarily valid nor exists.
     * @return this bookmark's location.
     * @see    #setLocation(String)
     */
    public String getLocation() {
        return location;
    }


    /**
     * Changes this bookmark's location to the given one and fires an event to registered {@link BookmarkListener}
     * instances.
     * @param newLocation bookmark's new location.
     * @see               #getLocation()
     */
    public void setLocation(String newLocation) {
        // Replace null values by empty strings
        if (newLocation == null) {
            newLocation = "";
        }

        if (!newLocation.equals(this.location)) {
            this.location = newLocation;

            // Notify registered listeners of the change
            BookmarkManager.fireBookmarksChanged();
        }
    }

    public String getParent() {
        return parent;
    }

    public void setParent(String parent) {
        if (parent != null && parent.trim().isEmpty()) {
            parent = null;
        }
        this.parent = parent;
    }


    /**
     * Returns the value of a protocol-specific property stored with this bookmark, for instance the
     * path of the SSH key an SFTP server is reached with.
     *
     * <p>Such properties live on {@link com.mucommander.commons.file.FileURL} but are absent from its
     * string representation, so they would be lost with a bookmark that only stores its location.</p>
     *
     * @param  name the property's name.
     * @return the property's value, <code>null</code> if this bookmark carries no such property.
     */
    public String getProperty(String name) {
        return properties == null ? null : properties.get(name);
    }

    /**
     * Stores a protocol-specific property with this bookmark, or removes it when the value is
     * <code>null</code> or empty.
     *
     * @param name  the property's name.
     * @param value the property's value, <code>null</code> or empty to remove it.
     */
    public void setProperty(String name, String value) {
        boolean changed;
        if (value == null || value.isEmpty()) {
            changed = properties != null && properties.remove(name) != null;
        } else {
            if (properties == null) {
                properties = new LinkedHashMap<>();
            }
            changed = !value.equals(properties.put(name, value));
        }

        if (changed) {
            // Notify registered listeners of the change
            BookmarkManager.fireBookmarksChanged();
        }
    }

    /**
     * Returns the properties stored with this bookmark.
     *
     * @return the properties, empty if this bookmark carries none.
     */
    public Map<String, String> getProperties() {
        return properties == null ? Collections.emptyMap() : Collections.unmodifiableMap(properties);
    }

    /**
     * Returns a clone of this bookmark.
     */
    @Override
    public Object clone() throws CloneNotSupportedException {
        Bookmark clone = (Bookmark)super.clone();
        // Object#clone() copies the reference, which would have the clone and this bookmark share
        // one property map and edits to either show up in both.
        if (properties != null) {
            clone.properties = new LinkedHashMap<>(properties);
        }
        return clone;
    }


    /**
     * Returns the bookmark's name.
     */
    public String toString() {
        if (parent != null) {
            return parent + " -> " + name;
        }
        return name;
    }

    public boolean equals(Object object) {
        if (!(object instanceof Bookmark bookmark)) {
            return false;
        }
        return bookmark.getName().equals(name);
    }
}
