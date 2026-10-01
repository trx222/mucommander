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
package com.mucommander.job;

import com.mucommander.commons.file.AbstractFile;
import com.mucommander.commons.file.util.FileSet;
import com.mucommander.desktop.AbstractTrash;
import com.mucommander.desktop.DesktopManager;
import com.mucommander.ui.dialog.file.FileCollisionDialog;
import com.mucommander.ui.dialog.file.ProgressDialog;
import com.mucommander.ui.dialog.file.sync.ComparisonEntry;
import com.mucommander.ui.dialog.file.sync.SyncAction;
import com.mucommander.ui.dialog.file.sync.SyncOptions;
import com.mucommander.ui.main.MainFrame;
import com.mucommander.utils.text.Translator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Carries out the copy and delete actions configured in the directory synchronization dialog.
 *
 * <p>Unlike {@link CopyJob}, which copies a set of files into one destination folder, every file handled
 * here has a destination of its own: the same relative path below whichever of the two synchronized
 * roots the file is headed for. Deletions go to the trash where the platform offers one, so that an
 * unintended run can still be undone.</p>
 *
 * @author Oleg Trifonov
 */
public class SynchronizeJob extends AbstractCopyJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(SynchronizeJob.class);

    /**
     * What is to happen to one file, looked up by its absolute path. Paths are used as keys rather than
     * the files themselves so that the lookup does not depend on AbstractFile equality.
     */
    private final Map<String, Operation> operations;

    /** The trash deletions go to, <code>null</code> if the platform offers none. */
    private final AbstractTrash trash;

    /** Run on the event dispatch thread once the job is through, may be <code>null</code>. */
    private final Runnable onFinished;

    /** One planned operation: either a copy to a given root, or a deletion. */
    private static final class Operation {
        private final boolean delete;
        private final AbstractFile destRoot;
        private final String relativePath;

        private Operation(boolean delete, AbstractFile destRoot, String relativePath) {
            this.delete = delete;
            this.destRoot = destRoot;
            this.relativePath = relativePath;
        }
    }

    /**
     * @param progressDialog the dialog showing the job's progress.
     * @param mainFrame      the main frame this job belongs to.
     * @param leftFolder     the folder synchronized on the left.
     * @param entries        the entries to act on; those switched off in the options are ignored.
     * @param options        what the user settled on in the options dialog.
     * @param onFinished     run on the event dispatch thread once the job is through, may be <code>null</code>.
     */
    public SynchronizeJob(ProgressDialog progressDialog, MainFrame mainFrame, AbstractFile leftFolder,
                          List<ComparisonEntry> entries, SyncOptions options, Runnable onFinished) {
        super(progressDialog, mainFrame, buildFileSet(leftFolder, entries, options), leftFolder, null,
                options.isConfirmOverwrite()
                        ? FileCollisionDialog.ASK_ACTION
                        : FileCollisionDialog.OVERWRITE_ACTION);

        this.onFinished = onFinished;
        this.errorDialogTitle = Translator.get("copy_dialog.error_title");
        this.trash = DesktopManager.getTrash();
        this.operations = new HashMap<>();
        setIntegrityCheckEnabled(options.isVerify());

        for (ComparisonEntry entry : entries) {
            if (!options.includes(entry)) {
                continue;
            }
            AbstractFile source = entry.getSourceFile();
            if (source != null) {
                // The targets come from the options, as the user may have redirected either direction.
                AbstractFile destRoot = entry.getAction() == SyncAction.COPY_TO_RIGHT
                        ? options.getRightTarget()
                        : options.getLeftTarget();
                operations.put(source.getAbsolutePath(), new Operation(false, destRoot, entry.getRelativePath()));
                continue;
            }
            AbstractFile toDelete = entry.getFileToDelete();
            if (toDelete != null) {
                operations.put(toDelete.getAbsolutePath(), new Operation(true, null, entry.getRelativePath()));
            }
        }
    }

    /**
     * Collects the files the switched-on entries act on into the set the job iterates over.
     *
     * @param  baseFolder the set's base folder.
     * @param  entries    the entries to act on.
     * @param  options    what the user switched on.
     * @return the files to process.
     */
    private static FileSet buildFileSet(AbstractFile baseFolder, List<ComparisonEntry> entries, SyncOptions options) {
        FileSet files = new FileSet(baseFolder);
        for (ComparisonEntry entry : entries) {
            if (!options.includes(entry)) {
                continue;
            }
            AbstractFile source = entry.getSourceFile();
            if (source != null) {
                files.add(source);
                continue;
            }
            AbstractFile toDelete = entry.getFileToDelete();
            if (toDelete != null) {
                files.add(toDelete);
            }
        }
        return files;
    }

    @Override
    protected boolean processFile(AbstractFile file, Object recurseParams) {
        if (getState() == State.INTERRUPTED) {
            return false;
        }

        Operation operation = operations.get(file.getAbsolutePath());
        if (operation == null) {
            // Nothing was scheduled for this file, which should not happen as the set is built from the
            // same entries. Skipping is the safe response.
            LOGGER.debug("No operation for " + file.getAbsolutePath());
            return false;
        }

        return operation.delete ? deleteFile(file) : copyFile(file, operation);
    }

    /**
     * Copies one file to the same relative path below its destination root, creating the folders it
     * needs along the way.
     *
     * @param  file      the file to copy.
     * @param  operation where it is headed.
     * @return <code>true</code> if the file was copied.
     */
    private boolean copyFile(AbstractFile file, Operation operation) {
        AbstractFile destFile;
        try {
            String relativePath = operation.relativePath;
            String separator = operation.destRoot.getSeparator();
            if (!"/".equals(separator)) {
                relativePath = relativePath.replace("/", separator);
            }
            destFile = operation.destRoot.getChild(relativePath);
        } catch (IOException e) {
            LOGGER.debug("Cannot resolve destination for " + file.getAbsolutePath(), e);
            showErrorDialog(errorDialogTitle, Translator.get("cannot_write_file", operation.relativePath));
            return false;
        }

        destFile = checkForCollision(file, destFile.getParent(), destFile, false);
        if (destFile == null) {
            // The file was skipped or the user cancelled the job.
            return false;
        }

        // The relative path may well reach into folders that only exist on the source side.
        AbstractFile destParent = destFile.getParent();
        if (destParent != null && !destParent.exists()) {
            try {
                destParent.mkdirs();
            } catch (IOException e) {
                LOGGER.debug("Cannot create " + destParent.getAbsolutePath(), e);
                showErrorDialog(errorDialogTitle, Translator.get("cannot_create_folder", destParent.getName()));
                return false;
            }
        }

        return tryCopyFile(file, destFile, append, errorDialogTitle);
    }

    /**
     * Removes one file, preferring the trash so that the deletion can still be undone.
     *
     * @param  file the file to remove.
     * @return <code>true</code> if the file was removed.
     */
    private boolean deleteFile(AbstractFile file) {
        try {
            if (trash != null && trash.canMoveToTrash(file)) {
                trash.moveToTrash(file);
            } else {
                file.delete();
            }
            return true;
        } catch (IOException e) {
            LOGGER.debug("Cannot delete " + file.getAbsolutePath(), e);
            showErrorDialog(errorDialogTitle, Translator.get("cannot_delete_file", file.getName()));
            return false;
        }
    }

    @Override
    protected boolean hasFolderChanged(AbstractFile folder) {
        // Files land below either synchronized root, so both panels are refreshed either way.
        return true;
    }

    @Override
    protected void jobCompleted() {
        super.jobCompleted();
        if (trash != null) {
            trash.waitForPendingOperations();
        }
        if (onFinished != null) {
            SwingUtilities.invokeLater(onFinished);
        }
    }
}
