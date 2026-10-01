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

import java.util.Map;

/**
 * Implementations of this interface are used to build bookmark sets.
 * @author Nicolas Rinaudo
 */
public interface BookmarkBuilder {
    /**
     * Notifies the builder that the bookmark list is starting.
     * @throws BookmarkException if an error occurs.
     */
    void startBookmarks() throws BookmarkException;

    /**
     * Notifies the builder of a new bookmark in the list.
     * @param  name              bookmark's name.
     * @param  location          bookmark's location.
     * @param  parent            bookmark's parent name.
     * @throws BookmarkException if an error occurs.
     */
    void addBookmark(String name, String location, String parent) throws BookmarkException;

    /**
     * Adds a bookmark along with the protocol-specific properties of its location, such as the path of
     * the SSH key an SFTP server is reached with.
     *
     * <p>Implementations that have no use for those properties need not override this method; the
     * default drops them and adds the bookmark as before.</p>
     *
     * @param  name               the bookmark's name.
     * @param  location           the bookmark's location.
     * @param  parent             the bookmark's parent, may be <code>null</code>.
     * @param  properties         the location's protocol-specific properties, may be empty.
     * @throws BookmarkException  if the bookmark could not be added.
     */
    default void addBookmark(String name, String location, String parent, Map<String, String> properties)
            throws BookmarkException {
        addBookmark(name, location, parent);
    }

    /**
     * Notifies the builder that the bookmark list is finished.
     * @throws BookmarkException if an error occurs.
     */
    void endBookmarks() throws BookmarkException;
}
