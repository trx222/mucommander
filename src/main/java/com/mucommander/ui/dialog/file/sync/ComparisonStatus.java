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
 * Outcome of comparing one relative path between the two synchronized directories.
 *
 * @author Oleg Trifonov
 */
public enum ComparisonStatus {

    /** The file exists on the left side only. */
    LEFT_ONLY("sync_directories.status.left_only"),

    /** The file exists on the right side only. */
    RIGHT_ONLY("sync_directories.status.right_only"),

    /** The file exists on both sides and the left one is the newer of the two. */
    LEFT_NEWER("sync_directories.status.left_newer"),

    /** The file exists on both sides and the right one is the newer of the two. */
    RIGHT_NEWER("sync_directories.status.right_newer"),

    /**
     * The file exists on both sides and differs, without one side being identifiable as the newer one.
     * This is the case when dates are ignored, when both sides carry the same date but differ in size,
     * or when a content comparison found a difference.
     */
    DIFFERENT("sync_directories.status.different"),

    /** The file exists on both sides and no difference was found. */
    EQUAL("sync_directories.status.equal");

    private final String labelKey;

    ComparisonStatus(String labelKey) {
        this.labelKey = labelKey;
    }

    /**
     * Returns the dictionary key holding this status' localized label.
     *
     * @return the dictionary key holding this status' localized label.
     */
    public String getLabelKey() {
        return labelKey;
    }

    /**
     * Returns <code>true</code> if the file is present on one side only.
     *
     * @return <code>true</code> if the file is present on one side only.
     */
    public boolean isSingleSided() {
        return this == LEFT_ONLY || this == RIGHT_ONLY;
    }

    /**
     * Returns <code>true</code> if the file is present on both sides but differs.
     *
     * @return <code>true</code> if the file is present on both sides but differs.
     */
    public boolean isDiffering() {
        return this == LEFT_NEWER || this == RIGHT_NEWER || this == DIFFERENT;
    }
}
