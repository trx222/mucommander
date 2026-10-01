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

import javax.swing.BorderFactory;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;

/**
 * Draws the synchronization table: folder headers stand out from the files below them, sizes and dates
 * are formatted, and rows scheduled for copying or deletion are tinted so the plan can be read at a glance.
 *
 * @author Oleg Trifonov
 */
public class SyncTableRenderer extends DefaultTableCellRenderer {

    /** Rows that will be copied. */
    private static final Color COPY_COLOR = new Color(0x1a7f37);

    /** Rows that will be deleted. */
    private static final Color DELETE_COLOR = new Color(0xcf222e);

    private final SyncTableModel model;

    public SyncTableRenderer(SyncTableModel model) {
        this.model = model;
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                   boolean hasFocus, int row, int column) {
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

        SyncTableModel.Row tableRow = model.getRow(table.convertRowIndexToModel(row));
        if (tableRow.isHeader()) {
            renderHeader(table, column, isSelected);
            return this;
        }
        renderEntry(table, tableRow.getEntry(), value, column, isSelected);
        return this;
    }

    /**
     * Draws a folder header: the folder's path in bold on a tinted band spanning the row.
     *
     * @param table      the table being drawn.
     * @param column     the column being drawn.
     * @param isSelected whether the row is selected.
     */
    private void renderHeader(JTable table, int column, boolean isSelected) {
        setHorizontalAlignment(SwingConstants.LEFT);
        setFont(table.getFont().deriveFont(Font.BOLD));
        setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
        if (!isSelected) {
            setBackground(headerBackground(table));
        }
        setBorder(BorderFactory.createEmptyBorder(1, column == SyncTableModel.COLUMN_LEFT_NAME ? 4 : 0, 1, 0));
    }

    /**
     * Draws one compared file.
     *
     * @param table      the table being drawn.
     * @param entry      the entry being drawn.
     * @param value      the cell's value.
     * @param column     the column being drawn.
     * @param isSelected whether the row is selected.
     */
    private void renderEntry(JTable table, ComparisonEntry entry, Object value, int column, boolean isSelected) {
        setFont(table.getFont());
        if (!isSelected) {
            setBackground(table.getBackground());
            setForeground(colorFor(entry, table));
        }
        setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));

        switch (column) {
            case SyncTableModel.COLUMN_LEFT_SIZE:
            case SyncTableModel.COLUMN_RIGHT_SIZE:
                setHorizontalAlignment(SwingConstants.RIGHT);
                setText(SyncTableModel.formatSize(value));
                break;
            case SyncTableModel.COLUMN_LEFT_DATE:
            case SyncTableModel.COLUMN_RIGHT_DATE:
                setHorizontalAlignment(SwingConstants.RIGHT);
                setText(SyncTableModel.formatDate(value));
                break;
            case SyncTableModel.COLUMN_ACTION:
                setHorizontalAlignment(SwingConstants.CENTER);
                setText(value == null ? "" : value.toString());
                break;
            default:
                setHorizontalAlignment(SwingConstants.LEFT);
                setText(value == null ? "" : value.toString());
                break;
        }
    }

    /**
     * Returns the colour a row's text is drawn in, which reflects what will happen to the file.
     *
     * @param  entry the entry being drawn.
     * @param  table the table being drawn.
     * @return the foreground colour to use.
     */
    private static Color colorFor(ComparisonEntry entry, JTable table) {
        if (entry.getAction().isDelete()) {
            return DELETE_COLOR;
        }
        if (entry.getAction().isCopy()) {
            return COPY_COLOR;
        }
        return table.getForeground();
    }

    /**
     * Returns the header band's colour, derived from the table's own background so that it works in
     * both light and dark themes.
     *
     * @param  table the table being drawn.
     * @return the header background colour.
     */
    private static Color headerBackground(JTable table) {
        Color base = table.getBackground();
        int brightness = (base.getRed() + base.getGreen() + base.getBlue()) / 3;
        int delta = brightness > 127 ? -22 : 26;
        return new Color(
                clamp(base.getRed() + delta),
                clamp(base.getGreen() + delta),
                clamp(base.getBlue() + delta));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
