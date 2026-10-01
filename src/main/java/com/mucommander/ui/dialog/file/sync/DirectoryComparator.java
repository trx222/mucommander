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
import com.mucommander.commons.file.filter.WildcardFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Walks two directories side by side and reports how their contents differ.
 *
 * <p>An instance is configured once and then run via {@link #compare(AbstractFile, AbstractFile)}, which is
 * expected to be called off the event dispatch thread. {@link #cancel()} may be called from any thread to
 * stop a running comparison early; the entries gathered so far are returned.</p>
 *
 * @author Oleg Trifonov
 */
public class DirectoryComparator {

    private static final Logger LOGGER = LoggerFactory.getLogger(DirectoryComparator.class);

    /**
     * Timestamps closer together than this are treated as equal. FAT stores modification times with a
     * two second granularity, so copying between file systems routinely shifts them by up to that much.
     */
    private static final long TIME_TOLERANCE_MS = 2000;

    /** Buffer size used by the content comparison. */
    private static final int CONTENT_BUFFER_SIZE = 64 * 1024;

    /** Whether to descend into subdirectories. */
    private final boolean recurse;

    /** Whether modification dates take part in the comparison. */
    private final boolean useDate;

    /** Whether files that look equal are additionally compared byte by byte. */
    private final boolean compareContent;

    /** Whether the left side is treated as the master copy. */
    private final boolean asymmetric;

    /** Filters a file has to pass to be compared, empty if every file is compared. */
    private final List<WildcardFileFilter> fileMasks;

    /**
     * Names at the root of the compared folders to restrict the comparison to, <code>null</code> to
     * compare everything.
     */
    private final Set<String> restrictToNames;

    private volatile boolean cancelled;

    /** Notified as the comparison progresses, may be <code>null</code>. */
    private final Listener listener;

    /**
     * Receives progress while a comparison runs. Called from the comparing thread, so implementations
     * must hop onto the event dispatch thread before touching Swing components.
     */
    public interface Listener {
        /**
         * @param relativePath the path currently being looked at.
         * @param found        the number of files gathered so far.
         */
        void onProgress(String relativePath, int found);
    }

    /**
     * @param recurse         whether to descend into subdirectories.
     * @param useDate         whether modification dates take part in the comparison.
     * @param compareContent  whether to additionally compare file contents byte by byte.
     * @param asymmetric      whether the left side is treated as the master copy.
     * @param fileMask        comma separated list of wildcard patterns (e.g. <code>*.png,*.jpg</code>),
     *                        <code>null</code> or empty to compare every file.
     * @param restrictToNames names at the root of the compared folders to restrict the comparison to,
     *                        <code>null</code> to compare everything.
     * @param listener        notified as the comparison progresses, may be <code>null</code>.
     */
    public DirectoryComparator(boolean recurse, boolean useDate, boolean compareContent, boolean asymmetric,
                               String fileMask, Set<String> restrictToNames, Listener listener) {
        this.recurse = recurse;
        this.useDate = useDate;
        this.compareContent = compareContent;
        this.asymmetric = asymmetric;
        this.fileMasks = parseFileMask(fileMask);
        this.restrictToNames = restrictToNames;
        this.listener = listener;
    }

    /**
     * Turns a comma separated list of wildcard patterns into filters, dropping the patterns that would
     * match everything anyway.
     *
     * @param  fileMask comma separated list of wildcard patterns.
     * @return the filters to apply, empty if every file is to be compared.
     */
    private static List<WildcardFileFilter> parseFileMask(String fileMask) {
        List<WildcardFileFilter> result = new ArrayList<>();
        if (fileMask == null) {
            return result;
        }
        for (String pattern : fileMask.split(",")) {
            pattern = pattern.trim();
            if (pattern.isEmpty() || "*".equals(pattern) || "*.*".equals(pattern)) {
                // Matches everything, so an empty filter list (= no filtering) is equivalent and cheaper.
                return new ArrayList<>();
            }
            result.add(new WildcardFileFilter(pattern, false));
        }
        return result;
    }

    /**
     * Stops a running comparison. The call returns immediately; {@link #compare(AbstractFile, AbstractFile)}
     * returns what it had gathered up to that point.
     */
    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Compares the two folders and returns one entry per compared file.
     *
     * @param  left  the folder shown on the left.
     * @param  right the folder shown on the right.
     * @return one entry per compared file, including the ones found to be equal.
     */
    public List<ComparisonEntry> compare(AbstractFile left, AbstractFile right) {
        List<ComparisonEntry> entries = new ArrayList<>();
        compareFolders(left, right, "", entries, true);
        return entries;
    }

    /**
     * Compares one folder level and, if recursing, calls itself for the subfolders found in either side.
     *
     * @param left         the left folder, <code>null</code> if it does not exist on that side.
     * @param right        the right folder, <code>null</code> if it does not exist on that side.
     * @param relativePath path of this level relative to the compared roots, empty at the root.
     * @param entries      collects the result.
     * @param isRoot       whether this is the top level, where {@link #restrictToNames} applies.
     */
    private void compareFolders(AbstractFile left, AbstractFile right, String relativePath,
                                List<ComparisonEntry> entries, boolean isRoot) {
        if (cancelled) {
            return;
        }
        if (listener != null) {
            listener.onProgress(relativePath, entries.size());
        }

        Map<String, AbstractFile> leftChildren = listChildren(left);
        Map<String, AbstractFile> rightChildren = listChildren(right);

        // ls() returns children in no particular order, so the union of both sides is sorted to give
        // the result a predictable, readable ordering.
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(leftChildren.keySet());
        names.addAll(rightChildren.keySet());

        for (String name : names) {
            if (cancelled) {
                return;
            }
            if (isRoot && restrictToNames != null && !restrictToNames.contains(name)) {
                continue;
            }

            AbstractFile leftChild = leftChildren.get(name);
            AbstractFile rightChild = rightChildren.get(name);
            String childPath = relativePath.isEmpty() ? name : relativePath + '/' + name;

            boolean leftIsDir = leftChild != null && leftChild.isDirectory();
            boolean rightIsDir = rightChild != null && rightChild.isDirectory();

            if (leftIsDir || rightIsDir) {
                if (!recurse) {
                    continue;
                }
                if (leftChild != null && rightChild != null && leftIsDir != rightIsDir) {
                    // A directory on one side and a file on the other: nothing sensible to copy or
                    // delete, so it is reported as differing and left to the user.
                    entries.add(new ComparisonEntry(childPath, leftChild, rightChild,
                            ComparisonStatus.DIFFERENT, asymmetric));
                    continue;
                }
                compareFolders(leftIsDir ? leftChild : null, rightIsDir ? rightChild : null, childPath, entries, false);
                continue;
            }

            if (!matchesMask(name)) {
                continue;
            }
            entries.add(new ComparisonEntry(childPath, leftChild, rightChild,
                    compareFiles(leftChild, rightChild), asymmetric));
        }
    }

    /**
     * Lists a folder's children keyed by name.
     *
     * @param  folder the folder to list, may be <code>null</code>.
     * @return the children by name, empty if the folder is <code>null</code> or could not be read.
     */
    private Map<String, AbstractFile> listChildren(AbstractFile folder) {
        Map<String, AbstractFile> result = new LinkedHashMap<>();
        if (folder == null) {
            return result;
        }
        AbstractFile[] children;
        try {
            children = folder.ls();
        } catch (IOException e) {
            // An unreadable folder is reported as empty rather than aborting the whole comparison.
            LOGGER.debug("Cannot list " + folder.getAbsolutePath(), e);
            return result;
        }
        for (AbstractFile child : children) {
            result.put(child.getName(), child);
        }
        return result;
    }

    /**
     * Returns <code>true</code> if the given file name passes the configured file masks.
     *
     * @param  name the file name to test.
     * @return <code>true</code> if the file is to be compared.
     */
    private boolean matchesMask(String name) {
        if (fileMasks.isEmpty()) {
            return true;
        }
        for (WildcardFileFilter filter : fileMasks) {
            if (filter.accept(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compares one file against its counterpart on the other side.
     *
     * @param  leftFile  the left file, <code>null</code> if absent on that side.
     * @param  rightFile the right file, <code>null</code> if absent on that side.
     * @return how the two relate.
     */
    private ComparisonStatus compareFiles(AbstractFile leftFile, AbstractFile rightFile) {
        if (rightFile == null) {
            return ComparisonStatus.LEFT_ONLY;
        }
        if (leftFile == null) {
            return ComparisonStatus.RIGHT_ONLY;
        }

        boolean sameSize = leftFile.getSize() == rightFile.getSize();

        long timeDelta = leftFile.getLastModifiedDate() - rightFile.getLastModifiedDate();
        boolean sameTime = Math.abs(timeDelta) <= TIME_TOLERANCE_MS;

        // Contents are only worth reading when the sizes match: differing sizes already settle the question.
        boolean sameContent = true;
        if (compareContent && sameSize) {
            sameContent = hasEqualContent(leftFile, rightFile);
        }

        boolean differs = !sameSize || !sameContent || (useDate && !sameTime);
        if (!differs) {
            return ComparisonStatus.EQUAL;
        }
        if (useDate && !sameTime) {
            return timeDelta > 0 ? ComparisonStatus.LEFT_NEWER : ComparisonStatus.RIGHT_NEWER;
        }
        // The files differ but nothing points at one of them being the newer one.
        return ComparisonStatus.DIFFERENT;
    }

    /**
     * Reads both files and reports whether their contents are identical. A file that cannot be read is
     * reported as differing, so that it shows up for the user instead of being silently called equal.
     *
     * @param  leftFile  the left file.
     * @param  rightFile the right file.
     * @return <code>true</code> if both files hold the same bytes.
     */
    private boolean hasEqualContent(AbstractFile leftFile, AbstractFile rightFile) {
        byte[] leftBuffer = new byte[CONTENT_BUFFER_SIZE];
        byte[] rightBuffer = new byte[CONTENT_BUFFER_SIZE];
        try (InputStream leftIn = leftFile.getInputStream();
             InputStream rightIn = rightFile.getInputStream()) {
            while (!cancelled) {
                int leftRead = readFully(leftIn, leftBuffer);
                int rightRead = readFully(rightIn, rightBuffer);
                if (leftRead != rightRead) {
                    return false;
                }
                if (leftRead <= 0) {
                    return true;
                }
                if (!Arrays.equals(leftBuffer, 0, leftRead, rightBuffer, 0, rightRead)) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            LOGGER.debug("Cannot compare contents of " + leftFile.getAbsolutePath(), e);
            return false;
        }
    }

    /**
     * Fills the buffer as far as the stream allows, as a single <code>read</code> may return less than
     * requested even well before the end of the file. Returning short therefore means end of stream.
     *
     * @param  in     the stream to read.
     * @param  buffer the buffer to fill.
     * @return the number of bytes read, 0 at the end of the stream.
     * @throws IOException if reading fails.
     */
    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = in.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }
}
