/*
 * This file is part of trolCommander, http://www.trolsoft.ru/en/soft/trolcommander
 * Copyright (C) 2013-2025 Oleg Trifonov
 *
 * trolCommander is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * trolCommander is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.mucommander.ui.dialog.file.sync;

/**
 * What the synchronization step is to do with a single compared file.
 *
 * @author Oleg Trifonov
 */
public enum SyncAction {

    /** Leave both sides alone. */
    NONE("sync_directories.action.none", "="),

    /** Copy the left file over the right one. */
    COPY_TO_RIGHT("sync_directories.action.copy_to_right", "→"),

    /** Copy the right file over the left one. */
    COPY_TO_LEFT("sync_directories.action.copy_to_left", "←"),

    /** Delete the file on the left side. */
    DELETE_LEFT("sync_directories.action.delete_left", "✘←"),

    /** Delete the file on the right side. */
    DELETE_RIGHT("sync_directories.action.delete_right", "→✘");

    private final String labelKey;
    private final String symbol;

    SyncAction(String labelKey, String symbol) {
        this.labelKey = labelKey;
        this.symbol = symbol;
    }

    /**
     * Returns the dictionary key holding this action's localized label.
     *
     * @return the dictionary key holding this action's localized label.
     */
    public String getLabelKey() {
        return labelKey;
    }

    /**
     * Returns the arrow shown in the table's middle column.
     *
     * @return the arrow shown in the table's middle column.
     */
    public String getSymbol() {
        return symbol;
    }

    /**
     * Returns <code>true</code> if this action copies a file.
     *
     * @return <code>true</code> if this action copies a file.
     */
    public boolean isCopy() {
        return this == COPY_TO_RIGHT || this == COPY_TO_LEFT;
    }

    /**
     * Returns <code>true</code> if this action deletes a file.
     *
     * @return <code>true</code> if this action deletes a file.
     */
    public boolean isDelete() {
        return this == DELETE_LEFT || this == DELETE_RIGHT;
    }

    /**
     * Returns the action pointing the other way, used by "reverse direction".
     *
     * @return the opposite action, or this one if it has no direction.
     */
    public SyncAction reversed() {
        switch (this) {
            case COPY_TO_RIGHT:
                return COPY_TO_LEFT;
            case COPY_TO_LEFT:
                return COPY_TO_RIGHT;
            case DELETE_LEFT:
                return DELETE_RIGHT;
            case DELETE_RIGHT:
                return DELETE_LEFT;
            default:
                return this;
        }
    }
}
