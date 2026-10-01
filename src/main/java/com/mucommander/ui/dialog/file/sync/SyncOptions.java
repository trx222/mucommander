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
 * What the user settled on in the synchronization dialog: which of the planned parts to carry out,
 * where the copies are headed and how to go about them.
 *
 * @author Oleg Trifonov
 */
public class SyncOptions {

    /** Whether the left-to-right copies are carried out. */
    private final boolean copyToRight;

    /** Whether the right-to-left copies are carried out. */
    private final boolean copyToLeft;

    /** Whether the deletions are carried out. */
    private final boolean delete;

    /** Root the left-to-right copies are written to, normally the right folder. */
    private final AbstractFile rightTarget;

    /** Root the right-to-left copies are written to, normally the left folder. */
    private final AbstractFile leftTarget;

    /** Whether to ask before overwriting an existing file. */
    private final boolean confirmOverwrite;

    /** Whether to checksum source and destination after copying. */
    private final boolean verify;

    public SyncOptions(boolean copyToRight, boolean copyToLeft, boolean delete, AbstractFile rightTarget,
                       AbstractFile leftTarget, boolean confirmOverwrite, boolean verify) {
        this.copyToRight = copyToRight;
        this.copyToLeft = copyToLeft;
        this.delete = delete;
        this.rightTarget = rightTarget;
        this.leftTarget = leftTarget;
        this.confirmOverwrite = confirmOverwrite;
        this.verify = verify;
    }

    public boolean isCopyToRight() {
        return copyToRight;
    }

    public boolean isCopyToLeft() {
        return copyToLeft;
    }

    public boolean isDelete() {
        return delete;
    }

    public AbstractFile getRightTarget() {
        return rightTarget;
    }

    public AbstractFile getLeftTarget() {
        return leftTarget;
    }

    public boolean isConfirmOverwrite() {
        return confirmOverwrite;
    }

    public boolean isVerify() {
        return verify;
    }

    /**
     * Returns whether the given entry's action is switched on.
     *
     * @param  entry the entry to check.
     * @return <code>true</code> if the entry is to be acted on.
     */
    public boolean includes(ComparisonEntry entry) {
        switch (entry.getAction()) {
            case COPY_TO_RIGHT:
                return copyToRight;
            case COPY_TO_LEFT:
                return copyToLeft;
            case DELETE_LEFT:
            case DELETE_RIGHT:
                return delete;
            default:
                return false;
        }
    }
}
