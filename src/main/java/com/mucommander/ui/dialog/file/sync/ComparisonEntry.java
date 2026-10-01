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

import com.mucommander.commons.file.AbstractFile;

/**
 * One compared file: its path relative to the synchronized roots, the file found on either side, the
 * {@link ComparisonStatus outcome} of comparing the two and the {@link SyncAction} to apply to them.
 *
 * <p>At least one of {@link #getLeftFile()} and {@link #getRightFile()} is non-null.</p>
 *
 * @author Oleg Trifonov
 */
public class ComparisonEntry {

    /** Path relative to the synchronized root folders, using '/' as a separator. */
    private final String relativePath;

    /** The folder part of {@link #relativePath}, empty for files directly below the roots. */
    private final String parentPath;

    /** The file's own name, without any folders. */
    private final String name;

    /** The file on the left side, <code>null</code> if it only exists on the right. */
    private final AbstractFile leftFile;

    /** The file on the right side, <code>null</code> if it only exists on the left. */
    private final AbstractFile rightFile;

    private final ComparisonStatus status;

    private SyncAction action;

    /**
     * @param relativePath path relative to the synchronized roots.
     * @param leftFile     the file on the left side, <code>null</code> if absent there.
     * @param rightFile    the file on the right side, <code>null</code> if absent there.
     * @param status       how the two relate.
     * @param asymmetric   whether the left side is treated as the master copy.
     */
    public ComparisonEntry(String relativePath, AbstractFile leftFile, AbstractFile rightFile,
                           ComparisonStatus status, boolean asymmetric) {
        this.relativePath = relativePath;
        int lastSeparator = relativePath.lastIndexOf('/');
        this.parentPath = lastSeparator < 0 ? "" : relativePath.substring(0, lastSeparator);
        this.name = lastSeparator < 0 ? relativePath : relativePath.substring(lastSeparator + 1);
        this.leftFile = leftFile;
        this.rightFile = rightFile;
        this.status = status;
        this.action = defaultActionFor(status, asymmetric);
    }

    /**
     * Returns the action a freshly compared file starts out with.
     *
     * <p>Symmetrically, that means copying towards the side that is missing or older and leaving equal
     * files alone. Asymmetrically, the left side is the master: everything differing is pushed to the
     * right, even if the right file is the newer one, and files that exist only on the right are
     * removed so that the right side ends up as a copy of the left.</p>
     *
     * @param  status     the outcome of the comparison.
     * @param  asymmetric whether the left side is treated as the master copy.
     * @return the action to preselect.
     */
    private static SyncAction defaultActionFor(ComparisonStatus status, boolean asymmetric) {
        if (status == ComparisonStatus.EQUAL) {
            return SyncAction.NONE;
        }
        if (asymmetric) {
            return status == ComparisonStatus.RIGHT_ONLY ? SyncAction.DELETE_RIGHT : SyncAction.COPY_TO_RIGHT;
        }
        switch (status) {
            case LEFT_ONLY:
            case LEFT_NEWER:
                return SyncAction.COPY_TO_RIGHT;
            case RIGHT_ONLY:
            case RIGHT_NEWER:
                return SyncAction.COPY_TO_LEFT;
            default:
                // DIFFERENT carries no direction, so it is left to the user to decide.
                return SyncAction.NONE;
        }
    }

    public String getRelativePath() {
        return relativePath;
    }

    /**
     * Returns the folder this file sits in, relative to the synchronized roots. The result table groups
     * its rows by this value.
     *
     * @return the folder part of the relative path, empty for files directly below the roots.
     */
    public String getParentPath() {
        return parentPath;
    }

    public String getName() {
        return name;
    }

    public AbstractFile getLeftFile() {
        return leftFile;
    }

    public AbstractFile getRightFile() {
        return rightFile;
    }

    public ComparisonStatus getStatus() {
        return status;
    }

    public SyncAction getAction() {
        return action;
    }

    public void setAction(SyncAction action) {
        this.action = action;
    }

    /**
     * Returns the file the configured action reads from, <code>null</code> if nothing is copied.
     *
     * @return the file the configured action reads from, <code>null</code> if nothing is copied.
     */
    public AbstractFile getSourceFile() {
        switch (action) {
            case COPY_TO_RIGHT:
                return leftFile;
            case COPY_TO_LEFT:
                return rightFile;
            default:
                return null;
        }
    }

    /**
     * Returns the file the configured action deletes, <code>null</code> if nothing is deleted.
     *
     * @return the file the configured action deletes, <code>null</code> if nothing is deleted.
     */
    public AbstractFile getFileToDelete() {
        switch (action) {
            case DELETE_LEFT:
                return leftFile;
            case DELETE_RIGHT:
                return rightFile;
            default:
                return null;
        }
    }

    /**
     * Returns <code>true</code> if both sides hold a file, i.e. if the two can be diffed against each other.
     *
     * @return <code>true</code> if both sides hold a file.
     */
    public boolean existsOnBothSides() {
        return leftFile != null && rightFile != null;
    }
}
