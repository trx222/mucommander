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

import com.mucommander.cache.TextHistory;
import com.mucommander.commons.file.AbstractFile;
import com.mucommander.auth.CredentialsManager;
import com.mucommander.auth.CredentialsMapping;
import com.mucommander.bookmark.BookmarkManager;
import com.mucommander.commons.file.FileFactory;
import com.mucommander.commons.file.FileURL;
import com.mucommander.commons.file.util.FileSet;
import com.mucommander.job.SynchronizeJob;
import com.mucommander.ui.action.impl.CompareFilesAction;
import com.mucommander.ui.dialog.DialogToolkit;
import com.mucommander.ui.dialog.FocusDialog;
import com.mucommander.ui.dialog.InformationDialog;
import com.mucommander.ui.dialog.file.ProgressDialog;
import com.mucommander.ui.layout.XBoxPanel;
import com.mucommander.ui.layout.YBoxPanel;
import com.mucommander.ui.main.MainFrame;
import com.mucommander.ui.text.FilePathField;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.TableColumnModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Compares the contents of two folders and lets the user copy or delete the differing files on either
 * side, in the spirit of Total Commander's "synchronize directories".
 *
 * @author Oleg Trifonov
 */
public class SynchronizeDirectoriesDialog extends FocusDialog implements ActionListener {

    /**
     * A generous cap that effectively lifts the size limit: {@link FocusDialog#pack()} would otherwise
     * pin the window's maximum to its packed size and stop the user from enlarging it.
     */
    private static final Dimension NO_SIZE_LIMIT = new Dimension(32000, 32000);

    /** Size the result table asks for before the user has resized the window. */
    private static final Dimension DEFAULT_TABLE_SIZE = new Dimension(900, 420);

    /** How many file masks are kept in the drop-down. */
    private static final int FILE_MASK_HISTORY_SIZE = 10;

    private final MainFrame mainFrame;

    /**
     * The folders the two path fields were filled with. Kept as instances because they carry the
     * credentials and protocol-specific properties that their path strings do not.
     */
    private final AbstractFile initialLeftFolder;
    private final AbstractFile initialRightFolder;

    // Comparison settings
    private final FilePathField leftPathField;
    private final FilePathField rightPathField;
    private final JComboBox<String> fileMaskComboBox;
    private final JCheckBox asymmetricCheckBox;
    private final JCheckBox recurseCheckBox;
    private final JCheckBox compareContentCheckBox;
    private final JCheckBox ignoreDateCheckBox;
    private final JCheckBox selectedOnlyCheckBox;
    private final JButton compareButton;

    // Which statuses the result table shows
    private final Map<ComparisonStatus, JCheckBox> statusFilters = new EnumMap<>(ComparisonStatus.class);

    // Result
    private final SyncTableModel tableModel = new SyncTableModel();
    private final JTable resultTable;
    private final JLabel statusLabel;
    private final JButton synchronizeButton;
    private final JButton closeButton;

    /** The folders of the comparison currently shown, null until the first comparison has run. */
    private AbstractFile comparedLeftFolder;
    private AbstractFile comparedRightFolder;

    /** The comparison currently running, null if none is. */
    private DirectoryComparator runningComparator;
    private SwingWorker<List<ComparisonEntry>, Void> runningWorker;

    public SynchronizeDirectoriesDialog(MainFrame mainFrame) {
        // Centred on the screen rather than on the main window: FocusDialog centres on the given
        // component without checking the screen's bounds, which pushes a dialog this tall off the top
        // edge whenever the main window is shorter than it.
        super(mainFrame.getJFrame(), i18n("sync_directories.title"), null);
        this.mainFrame = mainFrame;

        initialLeftFolder = mainFrame.getLeftPanel().getCurrentFolder();
        initialRightFolder = mainFrame.getRightPanel().getCurrentFolder();

        leftPathField = new FilePathField(
                initialLeftFolder == null ? "" : initialLeftFolder.getAbsolutePath(), 30);
        rightPathField = new FilePathField(
                initialRightFolder == null ? "" : initialRightFolder.getAbsolutePath(), 30);
        fileMaskComboBox = createFileMaskComboBox();

        asymmetricCheckBox = new JCheckBox(i18n("sync_directories.asymmetric"), false);
        asymmetricCheckBox.setToolTipText(i18n("sync_directories.asymmetric.tooltip"));
        recurseCheckBox = new JCheckBox(i18n("sync_directories.recurse"), true);
        compareContentCheckBox = new JCheckBox(i18n("sync_directories.compare_content"), false);
        ignoreDateCheckBox = new JCheckBox(i18n("sync_directories.ignore_date"), false);
        selectedOnlyCheckBox = new JCheckBox(i18n("sync_directories.selected_only"), false);

        compareButton = new JButton(i18n("sync_directories.compare"));
        synchronizeButton = new JButton(i18n("sync_directories.synchronize"));
        closeButton = new JButton(i18n("close"));
        statusLabel = new JLabel(i18n("sync_directories.press_compare"));

        resultTable = new JTable(tableModel);

        JPanel panel = new JPanel(new BorderLayout(0, 5));
        panel.add(createSettingsPanel(), BorderLayout.NORTH);
        panel.add(createResultPanel(), BorderLayout.CENTER);
        panel.add(createBottomPanel(), BorderLayout.SOUTH);
        // Added to the existing content pane rather than replacing it: FocusDialog binds Escape to the
        // pane it created, and swapping that pane out would take the binding with it.
        getContentPane().add(panel, BorderLayout.CENTER);

        compareButton.addActionListener(this);
        synchronizeButton.addActionListener(this);
        closeButton.addActionListener(this);
        getRootPane().setDefaultButton(compareButton);
        setInitialFocusComponent(compareButton);

        // Without an explicit maximum the dialog cannot be enlarged beyond its packed size.
        setMaximumSizeDialog(NO_SIZE_LIMIT);
        updateButtons();

        // Left non-modal so the file panels stay usable while a comparison is on screen.
        setModal(false);
    }

    /**
     * Builds the file mask drop-down, filled with the masks used previously.
     *
     * @return the file mask combo box.
     */
    private static JComboBox<String> createFileMaskComboBox() {
        JComboBox<String> comboBox = new JComboBox<>();
        comboBox.setEditable(true);
        for (String mask : TextHistory.getInstance().getList(TextHistory.Type.SYNC_FILE_MASK)) {
            comboBox.addItem(mask);
        }
        if (comboBox.getItemCount() == 0) {
            comboBox.addItem("*.*");
        }
        comboBox.setSelectedIndex(0);
        return comboBox;
    }

    /**
     * Returns the file mask currently entered.
     *
     * @return the file mask currently entered.
     */
    private String getFileMask() {
        Object selected = fileMaskComboBox.getEditor().getItem();
        return selected == null ? "" : selected.toString().trim();
    }

    /**
     * Remembers the mask just used, so it can be picked from the drop-down next time.
     *
     * @param mask the mask to remember.
     */
    private void rememberFileMask(String mask) {
        if (mask.isEmpty()) {
            return;
        }
        TextHistory.getInstance().add(TextHistory.Type.SYNC_FILE_MASK, mask, true);

        fileMaskComboBox.removeAllItems();
        int added = 0;
        for (String item : TextHistory.getInstance().getList(TextHistory.Type.SYNC_FILE_MASK)) {
            if (added++ >= FILE_MASK_HISTORY_SIZE) {
                break;
            }
            fileMaskComboBox.addItem(item);
        }
        fileMaskComboBox.getEditor().setItem(mask);
    }

    /**
     * Builds the upper part of the dialog: the two paths, the file mask and the comparison options.
     *
     * @return the settings panel.
     */
    private JPanel createSettingsPanel() {
        YBoxPanel panel = new YBoxPanel();

        JPanel pathsPanel = new JPanel(new GridLayout(2, 1, 0, 3));
        pathsPanel.add(createLabelledRow(i18n("sync_directories.left_folder"), leftPathField));
        pathsPanel.add(createLabelledRow(i18n("sync_directories.right_folder"), rightPathField));
        panel.add(pathsPanel);
        panel.addSpace(6);

        XBoxPanel maskRow = new XBoxPanel();
        maskRow.add(new JLabel(i18n("sync_directories.file_mask")));
        maskRow.addSpace(5);
        fileMaskComboBox.setMaximumSize(new Dimension(220, fileMaskComboBox.getPreferredSize().height));
        maskRow.add(fileMaskComboBox);
        maskRow.addSpace(15);
        maskRow.add(compareButton);
        maskRow.add(Box.createHorizontalGlue());
        panel.add(maskRow);
        panel.addSpace(6);

        JPanel optionsPanel = new JPanel(new GridLayout(3, 2, 10, 2));
        optionsPanel.add(recurseCheckBox);
        optionsPanel.add(withHelpButton(asymmetricCheckBox, "sync_directories.asymmetric.help"));
        optionsPanel.add(compareContentCheckBox);
        optionsPanel.add(ignoreDateCheckBox);
        optionsPanel.add(selectedOnlyCheckBox);
        panel.add(optionsPanel);
        panel.addSpace(4);
        panel.add(new JSeparator());

        return panel;
    }

    /**
     * Puts a small help button next to a check box whose meaning is not obvious from its label.
     *
     * @param  checkBox the check box to annotate.
     * @param  helpKey  dictionary key holding the explanation to show.
     * @return the check box and its help button, side by side.
     */
    private JPanel withHelpButton(JCheckBox checkBox, String helpKey) {
        JButton helpButton = new JButton("?");
        helpButton.setMargin(new Insets(0, 4, 0, 4));
        helpButton.setFocusable(false);
        helpButton.setToolTipText(i18n("sync_directories.whats_this"));
        helpButton.addActionListener(e -> InformationDialog.showDialog(
                InformationDialog.INFORMATION_DIALOG_TYPE, this, checkBox.getText(), i18n(helpKey), null, null));

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.add(checkBox);
        panel.add(helpButton);
        return panel;
    }

    /**
     * Builds one labelled row.
     *
     * @param  label the row's label.
     * @param  field the row's field.
     * @return the row.
     */
    private static JPanel createLabelledRow(String label, JComponent field) {
        JPanel row = new JPanel(new BorderLayout(5, 0));
        JLabel jLabel = new JLabel(label);
        jLabel.setPreferredSize(new Dimension(70, jLabel.getPreferredSize().height));
        row.add(jLabel, BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        return row;
    }

    /**
     * Builds the middle part of the dialog: the display filters and the result table.
     *
     * @return the result panel.
     */
    private JPanel createResultPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));

        JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        filterPanel.add(new JLabel(i18n("sync_directories.show")));
        for (ComparisonStatus status : ComparisonStatus.values()) {
            // Equal files are the uninteresting majority, so they start out hidden.
            JCheckBox checkBox = new JCheckBox(i18n(status.getLabelKey()), status != ComparisonStatus.EQUAL);
            checkBox.addActionListener(e -> applyStatusFilter());
            statusFilters.put(status, checkBox);
            filterPanel.add(checkBox);
        }
        panel.add(filterPanel, BorderLayout.NORTH);

        resultTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        resultTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        resultTable.setShowGrid(false);
        resultTable.getTableHeader().setReorderingAllowed(false);
        resultTable.setDefaultRenderer(Object.class, new SyncTableRenderer(tableModel));

        TableColumnModel columns = resultTable.getColumnModel();
        columns.getColumn(SyncTableModel.COLUMN_LEFT_NAME).setPreferredWidth(240);
        columns.getColumn(SyncTableModel.COLUMN_LEFT_SIZE).setPreferredWidth(80);
        columns.getColumn(SyncTableModel.COLUMN_LEFT_DATE).setPreferredWidth(130);
        columns.getColumn(SyncTableModel.COLUMN_ACTION).setPreferredWidth(50);
        columns.getColumn(SyncTableModel.COLUMN_RIGHT_DATE).setPreferredWidth(130);
        columns.getColumn(SyncTableModel.COLUMN_RIGHT_SIZE).setPreferredWidth(80);
        columns.getColumn(SyncTableModel.COLUMN_RIGHT_NAME).setPreferredWidth(240);

        resultTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 2) {
                    handleDoubleClick(e);
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(resultTable);
        scrollPane.setPreferredSize(DEFAULT_TABLE_SIZE);
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Builds the lower part of the dialog: the status line and the closing buttons.
     *
     * @return the bottom panel.
     */
    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(statusLabel, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        buttons.add(synchronizeButton);
        buttons.add(closeButton);
        panel.add(buttons, BorderLayout.EAST);
        return panel;
    }

    /**
     * Handles a double click: on the middle column it cycles the copy direction, elsewhere it opens
     * the two files in the platform's diff tool.
     *
     * @param e the mouse event.
     */
    private void handleDoubleClick(MouseEvent e) {
        int row = resultTable.rowAtPoint(e.getPoint());
        if (row < 0 || tableModel.getEntry(row) == null) {
            return;
        }
        if (resultTable.columnAtPoint(e.getPoint()) == SyncTableModel.COLUMN_ACTION) {
            cycleDirection(row);
        } else {
            diffSelectedEntry();
        }
    }

    /**
     * Steps one entry through the copy directions that are actually possible for it, leaving deletions
     * to the context menu so they cannot be triggered by a stray double click.
     *
     * @param row the row to change.
     */
    private void cycleDirection(int row) {
        ComparisonEntry entry = tableModel.getEntry(row);

        List<SyncAction> choices = new ArrayList<>();
        choices.add(SyncAction.NONE);
        if (entry.getLeftFile() != null) {
            choices.add(SyncAction.COPY_TO_RIGHT);
        }
        if (entry.getRightFile() != null) {
            choices.add(SyncAction.COPY_TO_LEFT);
        }

        int current = choices.indexOf(entry.getAction());
        entry.setAction(choices.get((current + 1) % choices.size()));

        tableModel.fireTableRowsUpdated(row, row);
        updateButtons();
    }

    /**
     * Shows the context menu if the given event asks for it.
     *
     * @param e the mouse event to inspect.
     */
    private void maybeShowPopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int row = resultTable.rowAtPoint(e.getPoint());
        if (row < 0) {
            return;
        }
        // Right-clicking outside the selection acts on the clicked row instead, as elsewhere in the app.
        if (!resultTable.isRowSelected(row)) {
            resultTable.setRowSelectionInterval(row, row);
        }
        createContextMenu().show(resultTable, e.getX(), e.getY());
    }

    /**
     * Builds the result table's context menu for the current selection.
     *
     * @return the context menu.
     */
    private JPopupMenu createContextMenu() {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem diffItem = new JMenuItem(i18n("sync_directories.menu.compare_files"));
        diffItem.addActionListener(e -> diffSelectedEntry());
        diffItem.setEnabled(canDiffSelectedEntry());
        menu.add(diffItem);
        menu.add(new JSeparator());

        addActionItem(menu, SyncAction.COPY_TO_RIGHT);
        addActionItem(menu, SyncAction.COPY_TO_LEFT);
        addActionItem(menu, SyncAction.NONE);

        JMenuItem reverseItem = new JMenuItem(i18n("sync_directories.menu.reverse"));
        reverseItem.addActionListener(e -> reverseSelection());
        menu.add(reverseItem);

        menu.add(new JSeparator());
        addActionItem(menu, SyncAction.DELETE_LEFT);
        addActionItem(menu, SyncAction.DELETE_RIGHT);

        return menu;
    }

    /**
     * Adds a menu item that applies the given action to the selection.
     *
     * @param menu   the menu to add to.
     * @param action the action the item applies.
     */
    private void addActionItem(JPopupMenu menu, SyncAction action) {
        JMenuItem item = new JMenuItem(i18n(action.getLabelKey()));
        item.addActionListener(e -> applyActionToSelection(action));
        menu.add(item);
    }

    /**
     * Returns <code>true</code> if exactly one row holding a file on either side is selected and an
     * external diff tool is available for it.
     *
     * @return <code>true</code> if the selected entry can be diffed.
     */
    private boolean canDiffSelectedEntry() {
        if (resultTable.getSelectedRowCount() != 1 || !CompareFilesAction.supported()) {
            return false;
        }
        ComparisonEntry entry = tableModel.getEntry(resultTable.getSelectedRow());
        return entry != null
                && entry.existsOnBothSides()
                && !entry.getLeftFile().isDirectory()
                && !entry.getRightFile().isDirectory();
    }

    /**
     * Opens the selected entry's two files in the platform's diff tool.
     */
    private void diffSelectedEntry() {
        if (!canDiffSelectedEntry()) {
            return;
        }
        ComparisonEntry entry = tableModel.getEntry(resultTable.getSelectedRow());
        // The paths go to ExecutorUtils as separate arguments, so they must not be shell-escaped here.
        CompareFilesAction.compareTwoFiles(entry.getLeftFile().getAbsolutePath(),
                entry.getRightFile().getAbsolutePath());
    }

    /**
     * Sets the given action on every selected file row, skipping the rows it cannot apply to.
     *
     * @param action the action to set.
     */
    private void applyActionToSelection(SyncAction action) {
        for (int row : resultTable.getSelectedRows()) {
            ComparisonEntry entry = tableModel.getEntry(row);
            if (entry == null || !isApplicable(action, entry)) {
                continue;
            }
            entry.setAction(action);
            tableModel.fireTableRowsUpdated(row, row);
        }
        updateButtons();
    }

    /**
     * Turns the direction of every selected file row around.
     */
    private void reverseSelection() {
        for (int row : resultTable.getSelectedRows()) {
            ComparisonEntry entry = tableModel.getEntry(row);
            if (entry == null) {
                continue;
            }
            SyncAction reversed = entry.getAction().reversed();
            if (isApplicable(reversed, entry)) {
                entry.setAction(reversed);
                tableModel.fireTableRowsUpdated(row, row);
            }
        }
        updateButtons();
    }

    /**
     * Returns whether an action makes sense for an entry: copying needs a source and deleting needs a
     * file on the side being deleted from.
     *
     * @param  action the action to check.
     * @param  entry  the entry to check it against.
     * @return <code>true</code> if the action can be applied.
     */
    private static boolean isApplicable(SyncAction action, ComparisonEntry entry) {
        switch (action) {
            case COPY_TO_RIGHT:
            case DELETE_LEFT:
                return entry.getLeftFile() != null;
            case COPY_TO_LEFT:
            case DELETE_RIGHT:
                return entry.getRightFile() != null;
            default:
                return true;
        }
    }

    /**
     * Narrows the result table down to the statuses currently ticked.
     */
    private void applyStatusFilter() {
        Set<ComparisonStatus> visible = new HashSet<>();
        for (Map.Entry<ComparisonStatus, JCheckBox> filter : statusFilters.entrySet()) {
            if (filter.getValue().isSelected()) {
                visible.add(filter.getKey());
            }
        }
        tableModel.setVisibleStatuses(visible);
        updateButtons();
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        Object source = e.getSource();
        if (source == compareButton) {
            if (runningWorker != null) {
                cancelComparison();
            } else {
                startComparison();
            }
        } else if (source == synchronizeButton) {
            synchronize();
        } else if (source == closeButton) {
            dispose();
        }
    }

    /**
     * Kicks off the comparison on a background thread.
     *
     * <p>The entered paths are resolved there rather than here: reaching a remote folder opens a
     * connection, which must not happen on the event dispatch thread.</p>
     */
    private void startComparison() {
        String leftPath = leftPathField.getText().trim();
        String rightPath = rightPathField.getText().trim();
        if (leftPath.isEmpty() || rightPath.isEmpty()) {
            InformationDialog.showErrorDialog(this, i18n("sync_directories.invalid_folder",
                    leftPath.isEmpty() ? leftPath : rightPath));
            return;
        }

        Set<String> restrictToNames = selectedOnlyCheckBox.isSelected() ? collectMarkedNames() : null;
        if (restrictToNames != null && restrictToNames.isEmpty()) {
            InformationDialog.showErrorDialog(this, i18n("sync_directories.nothing_selected"));
            return;
        }

        String fileMask = getFileMask();
        rememberFileMask(fileMask);

        DirectoryComparator comparator = new DirectoryComparator(
                recurseCheckBox.isSelected(),
                !ignoreDateCheckBox.isSelected(),
                compareContentCheckBox.isSelected(),
                asymmetricCheckBox.isSelected(),
                fileMask,
                restrictToNames,
                (path, found) -> SwingUtilities.invokeLater(
                        () -> statusLabel.setText(i18n("sync_directories.comparing", path))));
        runningComparator = comparator;

        runningWorker = new SwingWorker<List<ComparisonEntry>, Void>() {
            private AbstractFile left;
            private AbstractFile right;

            @Override
            protected List<ComparisonEntry> doInBackground() throws IOException {
                left = resolveFolder(leftPath, initialLeftFolder);
                if (left == null) {
                    throw new IOException(i18n("sync_directories.invalid_folder", leftPath));
                }
                right = resolveFolder(rightPath, initialRightFolder);
                if (right == null) {
                    throw new IOException(i18n("sync_directories.invalid_folder", rightPath));
                }
                return comparator.compare(left, right);
            }

            @Override
            protected void done() {
                comparedLeftFolder = left;
                comparedRightFolder = right;
                comparisonFinished(this);
            }
        };

        setComparisonRunning(true);
        runningWorker.execute();
    }

    /**
     * Stops a running comparison; its partial result is still shown.
     */
    private void cancelComparison() {
        if (runningComparator != null) {
            runningComparator.cancel();
        }
    }

    /**
     * Takes the finished worker's result into the table.
     *
     * @param worker the worker that just finished.
     */
    private void comparisonFinished(SwingWorker<List<ComparisonEntry>, Void> worker) {
        List<ComparisonEntry> entries;
        try {
            entries = worker.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            entries = new ArrayList<>();
        } catch (ExecutionException e) {
            InformationDialog.showErrorDialog(this, i18n("sync_directories.failed", String.valueOf(e.getCause())));
            entries = new ArrayList<>();
        }

        runningWorker = null;
        runningComparator = null;
        setComparisonRunning(false);

        tableModel.setEntries(entries);
        applyStatusFilter();
    }

    /**
     * Switches the dialog between the comparing and the idle state.
     *
     * @param running whether a comparison is running.
     */
    private void setComparisonRunning(boolean running) {
        compareButton.setText(running ? i18n("cancel") : i18n("sync_directories.compare"));
        leftPathField.setEnabled(!running);
        rightPathField.setEnabled(!running);
        fileMaskComboBox.setEnabled(!running);
        asymmetricCheckBox.setEnabled(!running);
        recurseCheckBox.setEnabled(!running);
        compareContentCheckBox.setEnabled(!running);
        ignoreDateCheckBox.setEnabled(!running);
        selectedOnlyCheckBox.setEnabled(!running);
        updateButtons();
    }

    /**
     * Refreshes the synchronize button and the status line from the model's current contents.
     */
    private void updateButtons() {
        synchronizeButton.setEnabled(runningWorker == null && !tableModel.getEntriesToProcess().isEmpty());
        updateStatusLabel();
    }

    /**
     * Writes the comparison's tally into the status line, the way Total Commander reports it.
     */
    private void updateStatusLabel() {
        if (runningWorker != null) {
            return;
        }
        int total = tableModel.getAllEntries().size();
        if (total == 0) {
            statusLabel.setText(i18n("sync_directories.press_compare"));
            return;
        }
        statusLabel.setText(i18n("sync_directories.summary",
                String.valueOf(total),
                String.valueOf(tableModel.countByStatus(ComparisonStatus.EQUAL)),
                String.valueOf(tableModel.countDiffering()),
                String.valueOf(tableModel.countByStatus(ComparisonStatus.LEFT_ONLY)),
                String.valueOf(tableModel.countByStatus(ComparisonStatus.RIGHT_ONLY))));
    }

    /**
     * Resolves one of the entered paths into an existing folder. Called off the event dispatch thread,
     * as reaching a remote folder opens a connection.
     *
     * @param  path    the path to resolve.
     * @param  initial the folder the field was filled with, may be <code>null</code>.
     * @return the folder, <code>null</code> if the path does not name one.
     */
    private static AbstractFile resolveFolder(String path, AbstractFile initial) {
        // An untouched field is answered with the panel's own folder. That instance carries its
        // credentials and protocol-specific properties, both of which are absent from its path string
        // and would be lost by resolving the text again.
        if (initial != null && path.equals(initial.getAbsolutePath())) {
            return initial;
        }

        AbstractFile file = resolveTypedPath(path);
        return file != null && file.exists() && file.isDirectory() ? file : null;
    }

    /**
     * Resolves a path the user typed, completing it the same way a folder change in the main window
     * would: with the SSH key or other properties of a matching bookmark, and with stored credentials.
     * That is what lets an <code>sftp://</code> path entered here reach the server.
     *
     * @param  path the path to resolve.
     * @return the file, <code>null</code> if the path cannot be resolved.
     */
    private static AbstractFile resolveTypedPath(String path) {
        FileURL url;
        try {
            url = FileURL.getFileURL(path);
        } catch (MalformedURLException e) {
            // Not a URL, so it can only be a plain local path.
            return FileFactory.getFile(path);
        }

        BookmarkManager.applyBookmarkProperties(url);
        if (url.getCredentials() == null) {
            CredentialsMapping[] matches = CredentialsManager.getMatchingCredentials(url);
            if (matches.length > 0) {
                CredentialsManager.authenticate(url, matches[0]);
            }
        }
        return FileFactory.getFile(url);
    }

    /**
     * Collects the names marked in either panel, which is what "selected only" restricts the comparison to.
     *
     * @return the marked names.
     */
    private Set<String> collectMarkedNames() {
        Set<String> names = new HashSet<>();
        addFileNames(names, mainFrame.getLeftPanel().getFileTable().getSelectedFiles());
        addFileNames(names, mainFrame.getRightPanel().getFileTable().getSelectedFiles());
        return names;
    }

    /**
     * Adds the names of the given files to the given set.
     *
     * @param names collects the names.
     * @param files the files whose names to add, may be <code>null</code>.
     */
    private static void addFileNames(Set<String> names, FileSet files) {
        if (files == null) {
            return;
        }
        for (AbstractFile file : files) {
            names.add(file.getName());
        }
    }

    /**
     * Lets the user review and adjust the planned work and, once confirmed, carries it out.
     */
    private void synchronize() {
        List<ComparisonEntry> entries = tableModel.getEntriesToProcess();
        if (entries.isEmpty() || comparedLeftFolder == null || comparedRightFolder == null) {
            return;
        }

        List<ComparisonEntry> toRight = new ArrayList<>();
        List<ComparisonEntry> toLeft = new ArrayList<>();
        List<ComparisonEntry> toDelete = new ArrayList<>();
        for (ComparisonEntry entry : entries) {
            if (entry.getAction() == SyncAction.COPY_TO_RIGHT) {
                toRight.add(entry);
            } else if (entry.getAction() == SyncAction.COPY_TO_LEFT) {
                toLeft.add(entry);
            } else if (entry.getAction().isDelete()) {
                toDelete.add(entry);
            }
        }

        SyncOptions options = new SyncOptionsDialog(this, toRight, toLeft, toDelete,
                comparedLeftFolder, comparedRightFolder).getOptions();
        if (options == null) {
            return;
        }

        ProgressDialog progressDialog = new ProgressDialog(mainFrame, i18n("progress_dialog.processing_files"));
        SynchronizeJob job = new SynchronizeJob(progressDialog, mainFrame, comparedLeftFolder,
                entries, options, this::startComparison);
        progressDialog.start(job);
    }

    @Override
    public void pack() {
        super.pack();
        // FocusDialog skips its own screen check once a maximum size is set, and the one set above
        // lifts the size limit, so the dialog is kept within the screen here instead.
        DialogToolkit.fitToScreen(this);
    }

    @Override
    public void dispose() {
        cancelComparison();
        super.dispose();
    }
}
