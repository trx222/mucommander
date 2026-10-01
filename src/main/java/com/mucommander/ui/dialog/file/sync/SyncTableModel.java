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
import com.mucommander.ui.main.table.views.BaseFileTableModel;
import com.mucommander.utils.text.CustomDateFormat;
import com.mucommander.utils.text.SizeFormat;
import com.mucommander.utils.text.Translator;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Backs the synchronization result table. Holds every compared file and exposes the subset currently
 * let through by the status filter, grouped by the folder the files sit in.
 *
 * @author Oleg Trifonov
 */
public class SyncTableModel extends AbstractTableModel {

    public static final int COLUMN_LEFT_NAME = 0;
    public static final int COLUMN_LEFT_SIZE = 1;
    public static final int COLUMN_LEFT_DATE = 2;
    public static final int COLUMN_ACTION = 3;
    public static final int COLUMN_RIGHT_DATE = 4;
    public static final int COLUMN_RIGHT_SIZE = 5;
    public static final int COLUMN_RIGHT_NAME = 6;

    private static final int COLUMN_COUNT = 7;

    /**
     * One table row: either a group header naming a folder, or a compared file.
     */
    public static class Row {
        /** The folder this header names, <code>null</code> for file rows. */
        private final String headerPath;

        /** The compared file, <code>null</code> for header rows. */
        private final ComparisonEntry entry;

        private Row(String headerPath, ComparisonEntry entry) {
            this.headerPath = headerPath;
            this.entry = entry;
        }

        public boolean isHeader() {
            return entry == null;
        }

        public String getHeaderPath() {
            return headerPath;
        }

        public ComparisonEntry getEntry() {
            return entry;
        }
    }

    /** Every compared file. */
    private final List<ComparisonEntry> allEntries = new ArrayList<>();

    /** The rows currently shown, headers included. */
    private final List<Row> visibleRows = new ArrayList<>();

    /** The statuses currently let through. */
    private Set<ComparisonStatus> visibleStatuses = EnumSet.allOf(ComparisonStatus.class);

    /**
     * Replaces the model's contents with the given entries.
     *
     * @param entries the new contents.
     */
    public void setEntries(List<ComparisonEntry> entries) {
        allEntries.clear();
        allEntries.addAll(entries);
        rebuildRows();
    }

    /**
     * Sets which statuses are shown and refreshes the table.
     *
     * @param statuses the statuses to let through.
     */
    public void setVisibleStatuses(Set<ComparisonStatus> statuses) {
        this.visibleStatuses = EnumSet.copyOf(statuses);
        rebuildRows();
    }

    /**
     * Rebuilds the visible rows from the current filter, inserting a header before each folder's files.
     * Files sitting directly below the synchronized roots get no header, as there is no folder to name.
     */
    private void rebuildRows() {
        visibleRows.clear();

        // The entries arrive sorted by path, so grouping by folder keeps them in order.
        Map<String, List<ComparisonEntry>> byFolder = new LinkedHashMap<>();
        for (ComparisonEntry entry : allEntries) {
            if (visibleStatuses.contains(entry.getStatus())) {
                byFolder.computeIfAbsent(entry.getParentPath(), k -> new ArrayList<>()).add(entry);
            }
        }

        for (Map.Entry<String, List<ComparisonEntry>> group : byFolder.entrySet()) {
            if (!group.getKey().isEmpty()) {
                visibleRows.add(new Row(group.getKey(), null));
            }
            for (ComparisonEntry entry : group.getValue()) {
                visibleRows.add(new Row(null, entry));
            }
        }
        fireTableDataChanged();
    }

    /**
     * Returns the row at the given index.
     *
     * @param  row a row index.
     * @return the row at that index.
     */
    public Row getRow(int row) {
        return visibleRows.get(row);
    }

    /**
     * Returns the entry shown in the given row.
     *
     * @param  row a row index.
     * @return the entry, <code>null</code> if that row is a folder header.
     */
    public ComparisonEntry getEntry(int row) {
        return visibleRows.get(row).getEntry();
    }

    /**
     * Returns every compared file, including the ones the filter currently hides.
     *
     * @return every compared file.
     */
    public List<ComparisonEntry> getAllEntries() {
        return allEntries;
    }

    /**
     * Returns the entries scheduled for copying or deletion, hidden ones included.
     *
     * @return the entries whose action is not {@link SyncAction#NONE}.
     */
    public List<ComparisonEntry> getEntriesToProcess() {
        List<ComparisonEntry> result = new ArrayList<>();
        for (ComparisonEntry entry : allEntries) {
            if (entry.getAction() != SyncAction.NONE) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * Counts the compared files carrying the given status.
     *
     * @param  status the status to count.
     * @return how many compared files carry it.
     */
    public int countByStatus(ComparisonStatus status) {
        int count = 0;
        for (ComparisonEntry entry : allEntries) {
            if (entry.getStatus() == status) {
                count++;
            }
        }
        return count;
    }

    /**
     * Counts the compared files that exist on both sides but differ.
     *
     * @return how many compared files differ.
     */
    public int countDiffering() {
        int count = 0;
        for (ComparisonEntry entry : allEntries) {
            if (entry.getStatus().isDiffering()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Counts the entries scheduled for the given action.
     *
     * @param  action the action to count.
     * @return how many entries are scheduled for it.
     */
    public int countByAction(SyncAction action) {
        int count = 0;
        for (ComparisonEntry entry : allEntries) {
            if (entry.getAction() == action) {
                count++;
            }
        }
        return count;
    }

    @Override
    public int getRowCount() {
        return visibleRows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMN_COUNT;
    }

    @Override
    public String getColumnName(int column) {
        switch (column) {
            case COLUMN_LEFT_NAME:
            case COLUMN_RIGHT_NAME:
                return Translator.get("name");
            case COLUMN_LEFT_SIZE:
            case COLUMN_RIGHT_SIZE:
                return Translator.get("size");
            case COLUMN_LEFT_DATE:
            case COLUMN_RIGHT_DATE:
                return Translator.get("date");
            case COLUMN_ACTION:
                return "<=>";
            default:
                return "";
        }
    }

    @Override
    public Object getValueAt(int row, int column) {
        Row tableRow = visibleRows.get(row);
        if (tableRow.isHeader()) {
            // The header's folder goes into the leftmost column; the renderer spells out that the row
            // is a header by drawing it differently.
            return column == COLUMN_LEFT_NAME ? tableRow.getHeaderPath() : null;
        }

        ComparisonEntry entry = tableRow.getEntry();
        switch (column) {
            case COLUMN_LEFT_NAME:
                return entry.getLeftFile() == null ? null : entry.getName();
            case COLUMN_LEFT_SIZE:
                return sizeOf(entry.getLeftFile());
            case COLUMN_LEFT_DATE:
                return dateOf(entry.getLeftFile());
            case COLUMN_ACTION:
                return symbolFor(entry);
            case COLUMN_RIGHT_DATE:
                return dateOf(entry.getRightFile());
            case COLUMN_RIGHT_SIZE:
                return sizeOf(entry.getRightFile());
            case COLUMN_RIGHT_NAME:
                return entry.getRightFile() == null ? null : entry.getName();
            default:
                return null;
        }
    }

    /**
     * Returns what the middle column shows: the scheduled action's arrow where one is scheduled, and
     * the plain comparison outcome otherwise.
     *
     * @param  entry the entry to describe.
     * @return the symbol for the middle column.
     */
    private static String symbolFor(ComparisonEntry entry) {
        if (entry.getAction() != SyncAction.NONE) {
            return entry.getAction().getSymbol();
        }
        return entry.getStatus() == ComparisonStatus.EQUAL ? "=" : "≠";
    }

    /**
     * Returns a file's size for the table to render.
     *
     * @param  file the file, may be <code>null</code>.
     * @return the size, <code>null</code> if the file is absent or a directory.
     */
    private static Long sizeOf(AbstractFile file) {
        return file == null || file.isDirectory() ? null : file.getSize();
    }

    /**
     * Returns a file's modification date for the table to render.
     *
     * @param  file the file, may be <code>null</code>.
     * @return the modification date, <code>null</code> if the file is absent.
     */
    private static Long dateOf(AbstractFile file) {
        return file == null ? null : file.getLastModifiedDate();
    }

    /**
     * Formats a size the way the file tables do, so the two read alike.
     *
     * @param  value the size, may be <code>null</code>.
     * @return the formatted size, empty if there is none.
     */
    static String formatSize(Object value) {
        return value == null ? "" : SizeFormat.format((Long) value, BaseFileTableModel.getSizeFormat());
    }

    /**
     * Formats a date using the user's configured format.
     *
     * @param  value the date, may be <code>null</code>.
     * @return the formatted date, empty if there is none.
     */
    static String formatDate(Object value) {
        return value == null ? "" : CustomDateFormat.format((Long) value);
    }
}
