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
import com.mucommander.commons.file.FileFactory;
import com.mucommander.ui.dialog.FocusDialog;
import com.mucommander.ui.dialog.InformationDialog;
import com.mucommander.ui.layout.YBoxPanel;
import com.mucommander.ui.text.FilePathField;
import com.mucommander.utils.text.SizeFormat;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;

/**
 * Lets the user review and adjust what the synchronization will do before it starts: which of the
 * planned parts to carry out, where the copies go, and how to go about them.
 *
 * @author Oleg Trifonov
 */
public class SyncOptionsDialog extends FocusDialog {

    /** Deletions are drawn in this colour to set them apart from the copies. */
    private static final Color DELETE_COLOR = new Color(0xcf222e);

    private static final int SIZE_FORMAT =
            SizeFormat.DIGITS_MEDIUM | SizeFormat.UNIT_SHORT | SizeFormat.INCLUDE_SPACE;

    private final JCheckBox toRightCheckBox;
    private final JCheckBox toLeftCheckBox;
    private final JCheckBox deleteCheckBox;
    private final JCheckBox confirmOverwriteCheckBox;
    private final JCheckBox verifyCheckBox;
    private final FilePathField rightTargetField;
    private final FilePathField leftTargetField;
    private final JButton okButton;

    /** What the user settled on, <code>null</code> while the dialog is up or if it was cancelled. */
    private SyncOptions options;

    /**
     * @param owner       the dialog this one belongs to.
     * @param toRight     the files to be copied from left to right.
     * @param toLeft      the files to be copied from right to left.
     * @param toDelete    the files to be deleted.
     * @param leftFolder  the folder compared on the left.
     * @param rightFolder the folder compared on the right.
     */
    public SyncOptionsDialog(JDialog owner, List<ComparisonEntry> toRight, List<ComparisonEntry> toLeft,
                             List<ComparisonEntry> toDelete, AbstractFile leftFolder, AbstractFile rightFolder) {
        super(owner, i18n("sync_directories.options.title"), owner);

        okButton = new JButton(i18n("ok"));
        JButton cancelButton = new JButton(i18n("cancel"));

        toRightCheckBox = new JCheckBox(i18n("sync_directories.options.to_right",
                String.valueOf(toRight.size()), formatTotalSize(toRight)), !toRight.isEmpty());
        toLeftCheckBox = new JCheckBox(i18n("sync_directories.options.to_left",
                String.valueOf(toLeft.size()), formatTotalSize(toLeft)), !toLeft.isEmpty());
        deleteCheckBox = new JCheckBox(i18n("sync_directories.options.delete",
                String.valueOf(toDelete.size())), !toDelete.isEmpty());
        deleteCheckBox.setForeground(DELETE_COLOR);

        rightTargetField = new FilePathField(rightFolder.getAbsolutePath(), 40);
        leftTargetField = new FilePathField(leftFolder.getAbsolutePath(), 40);

        confirmOverwriteCheckBox = new JCheckBox(i18n("sync_directories.options.confirm_overwrite"), false);
        verifyCheckBox = new JCheckBox(i18n("sync_directories.options.verify"), false);

        // A part that has nothing to do cannot be switched on.
        setPartEnabled(toRightCheckBox, rightTargetField, !toRight.isEmpty());
        setPartEnabled(toLeftCheckBox, leftTargetField, !toLeft.isEmpty());
        deleteCheckBox.setEnabled(!toDelete.isEmpty());

        toRightCheckBox.addActionListener(e -> {
            rightTargetField.setEnabled(toRightCheckBox.isSelected());
            updateOkButton();
        });
        toLeftCheckBox.addActionListener(e -> {
            leftTargetField.setEnabled(toLeftCheckBox.isSelected());
            updateOkButton();
        });
        deleteCheckBox.addActionListener(e -> updateOkButton());

        YBoxPanel content = new YBoxPanel();
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));

        content.add(toRightCheckBox);
        content.add(indented(rightTargetField));
        content.addSpace(8);
        content.add(toLeftCheckBox);
        content.add(indented(leftTargetField));
        content.addSpace(8);
        content.add(deleteCheckBox);
        if (!toDelete.isEmpty()) {
            content.add(indented(new JLabel(i18n("sync_directories.options.delete_hint"))));
        }
        content.addSpace(8);
        content.add(new JSeparator());
        content.addSpace(8);
        content.add(confirmOverwriteCheckBox);
        content.add(verifyCheckBox);

        okButton.addActionListener(e -> confirm());
        cancelButton.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        buttons.add(okButton);
        buttons.add(cancelButton);

        JPanel pane = new JPanel(new BorderLayout(0, 10));
        pane.add(content, BorderLayout.CENTER);
        pane.add(buttons, BorderLayout.SOUTH);
        // Added to the existing content pane rather than replacing it: FocusDialog binds Escape to the
        // pane it created, and swapping that pane out would take the binding with it.
        getContentPane().add(pane, BorderLayout.CENTER);

        updateOkButton();
        setInitialFocusComponent(okButton);
        getRootPane().setDefaultButton(okButton);
    }

    /**
     * Switches one part of the plan on or off, greying out its target field along with it.
     *
     * @param checkBox the part's check box.
     * @param field    the part's target field.
     * @param enabled  whether the part has anything to do.
     */
    private static void setPartEnabled(JCheckBox checkBox, FilePathField field, boolean enabled) {
        checkBox.setEnabled(enabled);
        checkBox.setSelected(enabled);
        field.setEnabled(enabled);
    }

    /**
     * Wraps a component in a panel that indents it below its check box.
     *
     * @param  component the component to indent.
     * @return the indented component.
     */
    private static JPanel indented(java.awt.Component component) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(2, 22, 0, 0));
        panel.add(component, BorderLayout.CENTER);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, component.getPreferredSize().height + 4));
        return panel;
    }

    /**
     * Returns the total size of the files the given entries would copy.
     *
     * @param  entries the entries to add up.
     * @return the total size, formatted for display.
     */
    private static String formatTotalSize(List<ComparisonEntry> entries) {
        long total = 0;
        for (ComparisonEntry entry : entries) {
            AbstractFile source = entry.getSourceFile();
            if (source != null) {
                total += source.getSize();
            }
        }
        return SizeFormat.format(total, SIZE_FORMAT);
    }

    /**
     * Keeps the OK button from starting a run that would do nothing.
     */
    private void updateOkButton() {
        okButton.setEnabled(toRightCheckBox.isSelected() || toLeftCheckBox.isSelected()
                || deleteCheckBox.isSelected());
    }

    /**
     * Validates the target folders and, if they hold up, records the user's choices and closes.
     */
    private void confirm() {
        AbstractFile rightTarget = null;
        if (toRightCheckBox.isSelected()) {
            rightTarget = resolveFolder(rightTargetField.getText());
            if (rightTarget == null) {
                InformationDialog.showErrorDialog(this,
                        i18n("sync_directories.invalid_folder", rightTargetField.getText()));
                return;
            }
        }

        AbstractFile leftTarget = null;
        if (toLeftCheckBox.isSelected()) {
            leftTarget = resolveFolder(leftTargetField.getText());
            if (leftTarget == null) {
                InformationDialog.showErrorDialog(this,
                        i18n("sync_directories.invalid_folder", leftTargetField.getText()));
                return;
            }
        }

        options = new SyncOptions(
                toRightCheckBox.isSelected(),
                toLeftCheckBox.isSelected(),
                deleteCheckBox.isSelected(),
                rightTarget,
                leftTarget,
                confirmOverwriteCheckBox.isSelected(),
                verifyCheckBox.isSelected());
        dispose();
    }

    /**
     * Resolves a typed path into an existing folder.
     *
     * @param  path the path to resolve.
     * @return the folder, <code>null</code> if the path does not name one.
     */
    private static AbstractFile resolveFolder(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        AbstractFile file = FileFactory.getFile(path.trim());
        return file != null && file.exists() && file.isDirectory() ? file : null;
    }

    /**
     * Shows the dialog and returns what the user settled on.
     *
     * @return the chosen options, <code>null</code> if the user cancelled.
     */
    public SyncOptions getOptions() {
        showDialog();
        return options;
    }
}
