/*--------------------------------------------------------------*
  Copyright (C) 2006-2015 OpenSim Ltd.

  This file is distributed WITHOUT ANY WARRANTY. See the file
  'License' for details on this and other legal matters.
*--------------------------------------------------------------*/

package org.omnetpp.scave.editors.datatable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.ListenerList;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.TableColumn;
import org.omnetpp.common.Debug;
import org.omnetpp.common.engine.BigDecimal;
import org.omnetpp.common.largetable.AbstractLargeTableRowRenderer;
import org.omnetpp.common.largetable.LargeTable;
import org.omnetpp.common.ui.TimeTriggeredProgressMonitorDialog2;
import org.omnetpp.common.util.CsvWriter;
import org.omnetpp.scave.ScavePlugin;
import org.omnetpp.scave.editors.ui.ScaveUtil;
import org.omnetpp.scave.engine.Histogram;
import org.omnetpp.scave.engine.HistogramResult;
import org.omnetpp.scave.engine.IDList;
import org.omnetpp.scave.engine.IntVector;
import org.omnetpp.scave.engine.InterruptedFlag;
import org.omnetpp.scave.engine.ParameterResult;
import org.omnetpp.scave.engine.ResultItem;
import org.omnetpp.scave.engine.ScalarResult;
import org.omnetpp.scave.engine.Scave;
import org.omnetpp.scave.engine.StatisticsResult;
import org.omnetpp.scave.engine.VectorResult;
import org.omnetpp.scave.engineext.ResultFileManagerEx;

/**
 * This is a preconfigured VIRTUAL table, which displays a list of
 * output vectors, output scalars, parameters, or histograms, given
 * an IDList and the corresponding ResultFileManager as input. It is
 * optimized for very large amounts of data. (Display time is constant,
 * so it can be used with even millions of table lines without
 * performance degradation).
 *
 * The user is responsible to keep contents up-to-date in case
 * ResultFileManager or IDList contents change. Refreshing can be
 * done either by a call to setIDList(), or by refresh().
 *
 * @author andras
 */
//TODO use s/histogram/statistics/ in the whole file
public class DataTable extends LargeTable implements IDataControl {

    /**
     * Keys used in getData(),setData()
     */
    public static final String COLUMN_KEY = "DataTable.Column";
    public static final String ITEM_KEY = "DataTable.Item";

    private static final StyledString NA = new StyledString("-"); // "not applicable"

    static class Column {

        private String text;
        private String fieldName;
        private int defaultWidth;
        private boolean defaultVisible;
        private boolean rightAligned;
        private boolean maskTooLongValues;

        public Column(String text, String fieldName, int defaultWidth, boolean defaultVisible, boolean rightAligned, boolean maskTooLongValues) {
            this.text = text;
            this.fieldName = fieldName;
            this.defaultWidth = defaultWidth;
            this.defaultVisible = defaultVisible;
            this.rightAligned = rightAligned;
            this.maskTooLongValues = maskTooLongValues;
        }

        @Override
        public Column clone() {
            return new Column(this.text, this.fieldName, this.defaultWidth, this.defaultVisible, this.rightAligned, this.maskTooLongValues);
        }

        @Override
        public boolean equals(Object other) {
            if (other == this)
                return true;
            if (!(other instanceof Column))
                return false;
            Column o = (Column)other;

            return StringUtils.equals(text, o.text)
                && StringUtils.equals(fieldName, o.fieldName)
                //&& defaultWidth == o.defaultWidth
                //&& defaultVisible == o.defaultVisible
                && rightAligned == o.rightAligned
                && maskTooLongValues == o.maskTooLongValues;
        }

        @Override
        public int hashCode() {
            HashCodeBuilder hashBuilder = new HashCodeBuilder();

            hashBuilder.append(text);
            hashBuilder.append(fieldName);
            //hashBuilder.append(defaultWidth);
            //hashBuilder.append(defaultVisible);
            hashBuilder.append(rightAligned);
            hashBuilder.append(maskTooLongValues);

            return hashBuilder.toHashCode();
        }
    }

    private static final Column
        COL_DIRECTORY = new Column("Folder", null, 60, false, false, false),
        COL_FILE = new Column("File", Scave.FILE, 120, false, false, false),
        COL_CONFIG = new Column("Config", Scave.RUNATTR_PREFIX + Scave.CONFIGNAME, 120, false, false, false),
        COL_RUNNUMBER = new Column("Run number", Scave.RUNATTR_PREFIX + Scave.RUNNUMBER, 60, false, false, false),
        COL_RUN_ID = new Column("RunId", Scave.RUN, 100, false, false, false),
        COL_EXPERIMENT = new Column("Experiment", Scave.RUNATTR_PREFIX + Scave.EXPERIMENT, 120, true, false, false),
        COL_MEASUREMENT = new Column("Measurement", Scave.RUNATTR_PREFIX + Scave.MEASUREMENT, 160, true, false, false),
        COL_REPLICATION = new Column("Replication", Scave.RUNATTR_PREFIX + Scave.REPLICATION, 60, true, false, false),
        COL_MODULE = new Column("Module", Scave.MODULE, 160, true, false, false),
        COL_NAME = new Column("Name", Scave.NAME, 120, true, false, false),
        COL_PARAM_VALUE = new Column("Value", null, 120, true, true, false),
        COL_SCALAR_VALUE = new Column("Value", null, 120, true, true, true),
        COL_KIND = new Column("Kind", null, 40, true, false, false),
        COL_COUNT = new Column("Count", null, 80, true, true, true),
        COL_SUMWEIGHTS = new Column("SumWeights", null, 120, true, true, true),
        COL_MEAN = new Column("Mean", null, 120, true, true, true),
        COL_STDDEV = new Column("StdDev", null, 120, true, true, true),
        COL_VARIANCE = new Column("Variance", null, 120, true, true, true),
        COL_MIN = new Column("Min", null, 120, false, true, true),
        COL_MAX = new Column("Max", null, 120, false, true, true),
        COL_NUMBINS = new Column("#Bins", null, 40, true, true, true),
        COL_HISTOGRAMRANGE = new Column("Hist. Range", null, 120, true, true, true),
        COL_VECTOR_ID = new Column("Vector id", null, 40, false, true, true),
        COL_MIN_TIME = new Column("Min time", null, 120, false, true, true),
        COL_MAX_TIME = new Column("Max time", null, 120, false, true, true);

    private static final Column[] allScalarColumns = new Column[] {
        COL_DIRECTORY, COL_FILE, COL_CONFIG, COL_RUNNUMBER, COL_RUN_ID,
        COL_EXPERIMENT, COL_MEASUREMENT, COL_REPLICATION,
        COL_MODULE, COL_NAME,
        COL_SCALAR_VALUE
    };

    private static final Column[] allParameterColumns = new Column[] {
        COL_DIRECTORY, COL_FILE, COL_CONFIG, COL_RUNNUMBER, COL_RUN_ID,
        COL_EXPERIMENT, COL_MEASUREMENT, COL_REPLICATION,
        COL_MODULE, COL_NAME,
        COL_PARAM_VALUE
    };

    private static final Column[] allVectorColumns = new Column[] {
        COL_DIRECTORY, COL_FILE, COL_CONFIG, COL_RUNNUMBER, COL_RUN_ID,
        COL_EXPERIMENT, COL_MEASUREMENT, COL_REPLICATION,
        COL_MODULE, COL_NAME,
        COL_VECTOR_ID,
        COL_COUNT, COL_MEAN, COL_STDDEV, COL_VARIANCE, COL_MIN, COL_MAX, COL_MIN_TIME, COL_MAX_TIME
    };

    private static final Column[] allHistogramColumns = new Column[] {
        COL_DIRECTORY, COL_FILE, COL_CONFIG, COL_RUNNUMBER, COL_RUN_ID,
        COL_EXPERIMENT, COL_MEASUREMENT, COL_REPLICATION,
        COL_MODULE, COL_NAME, COL_KIND,
        COL_COUNT, COL_SUMWEIGHTS, COL_MEAN, COL_STDDEV, COL_VARIANCE, COL_MIN, COL_MAX,
        COL_NUMBINS, COL_HISTOGRAMRANGE
    };

    private PanelType type;
    private ResultFileManagerEx manager;
    private IDList idList = new IDList();
    private int numericPrecision = 6;
    private ListenerList<IDataListener> listeners;
    private int minColumnWidth = 5; // for usability
    private List<Column> visibleColumns; // list of visible columns, this list will be saved and restored
    private IPreferenceStore preferences = ScavePlugin.getDefault().getPreferenceStore();

    // holds actions for the context menu for this data table
    private MenuManager contextMenuManager = new MenuManager("#PopupMenu");

    private static final ResultItem[] NULL_SELECTION = new ResultItem[0];

    private TableColumn selectedColumn; // the last column selected by a mouse click

    public DataTable(Composite parent, int style, PanelType type) {
        super(parent, style | SWT.VIRTUAL | SWT.FULL_SELECTION);
        Assert.isTrue(type==PanelType.SCALARS || type==PanelType.PARAMETERS || type==PanelType.VECTORS || type==PanelType.HISTOGRAMS);
        this.type = type;
        setLinesVisible(true);
        initDefaultState();
        initColumns();
        setRowRenderer(new AbstractLargeTableRowRenderer() {
            @Override
            public String getText(int rowIndex, int columnIndex) {
                return getCellValue(rowIndex, columnIndex, null, -1).getString();
            }

            @Override
            public StyledString getStyledText(int rowIndex, int columnIndex, GC gc, int alignment) {
                String value = "";
                // the last, blank column is not included in visibleColumns
                if (columnIndex < visibleColumns.size()) {
                    Column column = visibleColumns.get(columnIndex);
                    value = getCellValue(rowIndex, column, null, -1).getString();

                    if (column.maskTooLongValues
                            && gc.textExtent(value).x > (getColumn(columnIndex).getWidth() - CELL_HORIZONTAL_MARGIN*2))
                        value = "#".repeat(value.length());
                }
                return new StyledString(value);
            }

            @Override
            public String getTooltipText(int rowIndex, int columnIndex) {
                return null;
            }

            @Override
            public int getIndentation(int rowIndex, int columnIndex) {
                return 0;
            }

            @Override
            public Image getImage(int rowIndex, int columnIndex) {
                return null;
            }
        });

        setMenu(contextMenuManager.createContextMenu(this));

        getCanvas().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseDown(MouseEvent event) {
                handleMouseDown(event);
            }
        });
    }

    /**
     * Override the ban on subclassing of Table, after having read the doc of
     * checkSubclass(). In this class we only build upon the public interface
     * of Table, so there can be no unwanted side effects. We prefer subclassing
     * to delegating all 1,000,000 Table methods to an internal Table instance.
     */
    @Override
    protected void checkSubclass() {
    }

    public PanelType getType() {
        return type;
    }

    public void setResultFileManager(ResultFileManagerEx manager) {
        this.manager = manager;
    }

    public ResultFileManagerEx getResultFileManager() {
        return manager;
    }

    public void setNumericPrecision(int numericPrecision) {
        if (this.numericPrecision != numericPrecision) {
            this.numericPrecision = numericPrecision;
            refresh();
        }
    }

    public int getNumericPrecision() {
        return numericPrecision;
    }

    public void setIDList(IDList newIdList) {
        if (newIdList.equals(this.idList))
            return;

        // save old focus. note: saving the selection is not done because:
        // (1) it's likely not too useful or missed by users, and at the
        // same time (2) difficult due to listener hell
        long focusID = getFocusedID();
        clearSelection();  // not really needed, as setItemCount() does it too

        // set new input
        setItemCount(newIdList.size()); // includes another clearSelection() & firing empty selection change
        this.idList = newIdList;
        restoreSortOrder(); // in-place sorts the IDList

        // try restoring old focus
        setFocusedID(focusID); // note: this indirectly fires selection change, so state should be fully updated at this point

        // refresh display, notify listeners
        refresh();
        fireContentChangedEvent();
    }

    public IDList getIDList() {
        return idList;
    }

    public long getFocusedID() {
        int index = getFocusIndex();
        return (index >= 0 && index < idList.size()) ? idList.get(index) : -1;
    }

    public void setFocusedID(long id) {
        int index = idList.indexOf(id); // index=-1 if not found
        if (index != -1)
            gotoIndex(index);
    }

    public IMenuManager getContextMenuManager() {
        return contextMenuManager;
    }

    protected Column[] getAllColumns() {
        switch (type) {
        case SCALARS:     return allScalarColumns;
        case PARAMETERS:  return allParameterColumns;
        case VECTORS:     return allVectorColumns;
        case HISTOGRAMS:  return allHistogramColumns;
        default: return null;
        }
    }

    public String[] getAllColumnNames() {
        Column[] columns = getAllColumns();
        String[] columnNames = new String[columns.length];
        for (int i = 0; i < columns.length; ++i)
            columnNames[i] = columns[i].text;
        return columnNames;
    }

    public String[] getVisibleColumnNames() {
        String[] columnNames = new String[visibleColumns.size()];
        for (int i = 0; i < visibleColumns.size(); ++i)
            columnNames[i] = visibleColumns.get(i).text;
        return columnNames;
    }

    public void setVisibleColumns(String[] columnTexts) {
        for (TableColumn c : getColumnsExceptLastBlank())
            c.dispose();
        visibleColumns.clear();

        for (Column column : getAllColumns()) {
            boolean requestedVisible = ArrayUtils.indexOf(columnTexts, column.text) != -1;
            if (requestedVisible)
                addColumn(column);
        }

        int[] columnOrder = new int[getColumnCount()];
        for (int i = 0; i < columnOrder.length; ++i)
            columnOrder[i] = i;

        table.setColumnOrder(columnOrder);

        saveState();
        refresh();

        // Workaround: Without the following, the right ~80% of the table disappears after OKing the
        // "Choose table columns" dialog, because the width of the ScrolledComposite's Composite child
        // becomes too small.
        //UIUtils.dumpLayout(this); // for debugging the layout

        composite.setSize(composite.computeSize(-1, -1));
        composite.layout(true);
    }

    public IDList getSelectedIDs() {
        int[] selectionIndices = getSelectionIndices().toArray();
        if (idList != null)
            return idList.getSubsetByIndices(selectionIndices);
        else
            return null;
    }

    public ResultItem[] getSelectedItems() {
        if (manager == null)
            return NULL_SELECTION;

        int[] selectionIndices = getSelectionIndices().toArray();
        ResultItem[] items = new ResultItem[selectionIndices.length];

        for (int i = 0; i < items.length; ++i)
            items[i] = manager.getItem(idList.get(selectionIndices[i]));

        return items;
    }

    protected void initColumns() {
        // add a last, blank column, otherwise the right edge of the last column sticks to the table's right border which is often inconvenient
        TableColumn blankColumn = createColumn(SWT.NONE);
        blankColumn.setWidth(minColumnWidth);

        visibleColumns = new ArrayList<>();
        loadState();
    }

    public TableColumn[] getColumnsExceptLastBlank() {
        TableColumn[] columns = super.getColumns();
        Assert.isTrue(columns[columns.length-1].getData(COLUMN_KEY) == null);
        return Arrays.copyOfRange(columns, 0, columns.length-1);
    }

    protected TableColumn getTableColumn(Column column) {
        for (TableColumn tableColumn : getColumnsExceptLastBlank())
            if (tableColumn.getData(COLUMN_KEY).equals(column))
                return tableColumn;
        return null;
    }

    protected int getTableColumnIndex(Column column) {
        TableColumn[] columns = getColumnsExceptLastBlank();
        for (int index = 0; index < columns.length; index++) {
            TableColumn tableColumn = columns[index];
            if (tableColumn.getData(COLUMN_KEY).equals(column))
                return index;
        }
        return -1;
    }

    protected TableColumn addColumn(Column newColumn) {
        visibleColumns.add(newColumn);
        TableColumn tableColumn = createColumn(newColumn.rightAligned ? SWT.RIGHT : SWT.NONE, getColumnsExceptLastBlank().length);
        tableColumn.setText(newColumn.text);
        tableColumn.setWidth(newColumn.defaultWidth);
        tableColumn.setData(COLUMN_KEY, newColumn);
        tableColumn.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                TableColumn tableColumn = (TableColumn)e.widget;
                if (!tableColumn.isDisposed()) {
                    int sortDirection = (getSortColumn() == tableColumn && getSortDirection() == SWT.UP ? SWT.DOWN : SWT.UP);
                    setSorting(tableColumn, sortDirection);
                }
            }
        });
        tableColumn.addControlListener(new ControlAdapter() {
            @Override
            public void controlResized(ControlEvent e) {
                ensureMinimumColumnWidths(); // this is an attempt to enforce a minimum column width; simply calling setWidth() on the resized column does not really take effect
                saveState();
            }
        });

        return tableColumn;
    }

    private void ensureMinimumColumnWidths() {
        for (TableColumn tableColumn : getColumns())
            if (tableColumn.getWidth() < minColumnWidth)
                tableColumn.setWidth(minColumnWidth);  // this will either take effect in the rendering or not... (GTK3)
    }

    private void restoreSortOrder() {
        TableColumn sortColumn = getSortColumn();
        int sortDirection = getSortDirection();
        if (sortColumn != null && sortDirection != SWT.NONE)
            sortRows(sortColumn, sortDirection);
    }

    public void setSorting(TableColumn tableColumn, int sortDirection) {
        setSortColumn(tableColumn);
        setSortDirection(sortDirection);
        sortRows(tableColumn, sortDirection);
        refresh();
        fireContentChangedEvent();
    }

    protected void sortRows(TableColumn sortColumn, int sortDirection) {
        if (manager == null || idList.isEmpty()) // no/empty input
            return;

        Column column = (Column)sortColumn.getData(COLUMN_KEY);
        if (column == null) // requested column has no sort key
            return;

        boolean allSelected = getSelectionCount() == idList.size();
        IntVector tmpSelectionIndices = allSelected ? new IntVector() : IntVector.fromArray(getSelectionIndices().toArray()); // optimize the common & expensive case when all items are selected
        long focusID = getFocusedID();

        TimeTriggeredProgressMonitorDialog2.runWithDialog("Sorting", (monitor) -> {
            InterruptedFlag interrupted = TimeTriggeredProgressMonitorDialog2.getActiveInstance().getInterruptedFlag();
            Debug.time("sorting", 1, () -> sortBy(idList, tmpSelectionIndices, column, sortDirection, interrupted)); // sort idList together with tmpSelectionIndices
        });

        if (!allSelected) {
            int[] array = tmpSelectionIndices.toArray();
            Arrays.sort(array);
            setSelectionIndices(array);
        }

        setFocusedID(focusID);
    }

    protected void sortBy(IDList idList, IntVector selectionIndices, Column column, int direction, InterruptedFlag interrupted) {
        boolean ascending = direction == SWT.UP;
        if (COL_DIRECTORY.equals(column))
            idList.sortByDirectory(manager, ascending, selectionIndices, interrupted);
        else if (COL_FILE.equals(column))
            idList.sortByFileName(manager, ascending, selectionIndices, interrupted);
        else if (COL_CONFIG.equals(column))
            idList.sortByRunAttribute(manager, Scave.CONFIGNAME, ascending, selectionIndices, interrupted);
        else if (COL_RUNNUMBER.equals(column))
            idList.sortByRunAttribute(manager, Scave.RUNNUMBER, ascending, selectionIndices, interrupted);
        else if (COL_RUN_ID.equals(column))
            idList.sortByRun(manager, ascending, selectionIndices, interrupted);
        else if (COL_MODULE.equals(column))
            idList.sortByModule(manager, ascending, selectionIndices, interrupted);
        else if (COL_NAME.equals(column))
            idList.sortByName(manager, ascending, selectionIndices, interrupted);
        else if (COL_PARAM_VALUE.equals(column))
            idList.sortParametersByValue(manager, ascending, selectionIndices, interrupted);
        else if (COL_SCALAR_VALUE.equals(column))
            idList.sortScalarsByValue(manager, ascending, selectionIndices, interrupted);
        else if (COL_VECTOR_ID.equals(column))
            idList.sortVectorsByVectorId(manager, ascending, selectionIndices, interrupted);
        else if (COL_KIND.equals(column))
            ; //TODO
        else if (COL_COUNT.equals(column)) {
            if (idList.areAllStatistics())
                idList.sortStatisticsByCount(manager, ascending, selectionIndices, interrupted);
            else if (idList.areAllVectors())
                idList.sortVectorsByCount(manager, ascending, selectionIndices, interrupted);
        }
        else if (COL_SUMWEIGHTS.equals(column)) {
            if (idList.areAllStatistics())
                idList.sortStatisticsBySumWeights(manager, ascending, selectionIndices, interrupted);
            else if (idList.areAllVectors())
                idList.sortStatisticsBySumWeights(manager, ascending, selectionIndices, interrupted);
        }
        else if (COL_MEAN.equals(column)) {
            if (idList.areAllStatistics())
                idList.sortStatisticsByMean(manager, ascending, selectionIndices, interrupted);
            else if (idList.areAllVectors())
                idList.sortVectorsByMean(manager, ascending, selectionIndices, interrupted);
        }
        else if (COL_STDDEV.equals(column)) {
            if (idList.areAllStatistics())
                idList.sortStatisticsByStdDev(manager, ascending, selectionIndices, interrupted);
            else if (idList.areAllVectors())
                idList.sortVectorsByStdDev(manager, ascending, selectionIndices, interrupted);
        }
        else if (COL_MIN.equals(column)) {
            if (idList.areAllStatistics())
                idList.sortStatisticsByMin(manager, ascending, selectionIndices, interrupted);
            else if (idList.areAllVectors())
                idList.sortVectorsByMin(manager, ascending, selectionIndices, interrupted);
        }
        else if (COL_MAX.equals(column)) {
            if (idList.areAllStatistics())
                idList.sortStatisticsByMax(manager, ascending, selectionIndices, interrupted);
            else if (idList.areAllVectors())
                idList.sortVectorsByMax(manager, ascending, selectionIndices, interrupted);
        }
        else if (COL_VARIANCE.equals(column)) {
            if (idList.areAllStatistics())
                idList.sortStatisticsByVariance(manager, ascending, selectionIndices, interrupted);
            else if (idList.areAllVectors())
                idList.sortVectorsByVariance(manager, ascending, selectionIndices, interrupted);
        }
        else if (COL_NUMBINS.equals(column)) {
            if (idList.areAllHistograms())
                idList.sortHistogramsByNumBins(manager, ascending, selectionIndices, interrupted);
        }
        else if (COL_HISTOGRAMRANGE.equals(column)) {
            if (idList.areAllHistograms())
                idList.sortHistogramsByHistogramRange(manager, ascending, selectionIndices, interrupted);
        }
        else if (COL_EXPERIMENT.equals(column))
            idList.sortByRunAttribute(manager, Scave.EXPERIMENT, ascending, selectionIndices, interrupted);
        else if (COL_MEASUREMENT.equals(column))
            idList.sortByRunAttribute(manager, Scave.MEASUREMENT, ascending, selectionIndices, interrupted);
        else if (COL_REPLICATION.equals(column))
            idList.sortByRunAttribute(manager, Scave.REPLICATION, ascending, selectionIndices, interrupted);
        else if (COL_MIN_TIME.equals(column))
            idList.sortVectorsByStartTime(manager, ascending, selectionIndices, interrupted);
        else if (COL_MAX_TIME.equals(column))
            idList.sortVectorsByEndTime(manager, ascending, selectionIndices, interrupted);
    }

    protected StyledString getCellValue(int rowIndex, int columnIndex, GC gc, int width) {
        if (columnIndex >= visibleColumns.size())
            return new StyledString("");
        Column column = visibleColumns.get(columnIndex);
        return getCellValue(rowIndex, column, gc, width);
    }

    protected StyledString getCellValue(int row, Column column, GC gc, int width) {
        if (manager == null)
            return new StyledString("");

        try {
            // Note: code very similar to ResultItemPropertySource -- make them common?
            long id = idList.get(row);
            ResultItem result = manager.getItem(id);

            String unit = result.getAttribute("unit");

            if (COL_DIRECTORY.equals(column))
                return new StyledString(result.getFile().getDirectory());
            else if (COL_FILE.equals(column))
                return new StyledString(result.getFile().getFileName());
            else if (COL_CONFIG.equals(column)) {
                String config = result.getFileRun().getRun().getAttribute(Scave.CONFIGNAME);
                return config != null ? new StyledString(config) : NA;
            }
            else if (COL_RUNNUMBER.equals(column)) {
                String runNumber = result.getFileRun().getRun().getAttribute(Scave.RUNNUMBER);
                return runNumber != null ? new StyledString(runNumber) : NA;
            }
            else if (COL_RUN_ID.equals(column))
                return new StyledString(result.getFileRun().getRun().getRunName());
            else if (COL_MODULE.equals(column))
                return new StyledString(result.getModuleName());
            else if (COL_NAME.equals(column))
                return new StyledString(result.getName());
            else if (COL_EXPERIMENT.equals(column)) {
                String experiment = result.getFileRun().getRun().getAttribute(Scave.EXPERIMENT);
                return experiment != null ? new StyledString(experiment) : NA;
            }
            else if (COL_MEASUREMENT.equals(column)) {
                String measurement = result.getFileRun().getRun().getAttribute(Scave.MEASUREMENT);
                return measurement != null ? new StyledString(measurement) : NA;
            }
            else if (COL_REPLICATION.equals(column)) {
                String replication = result.getFileRun().getRun().getAttribute(Scave.REPLICATION);
                return replication != null ? new StyledString(replication) : NA;
            }
            else if (COL_PARAM_VALUE.equals(column)) {
                ParameterResult parameter = (ParameterResult)result;
                return new StyledString(parameter.getValue());
            }
            else if (COL_SCALAR_VALUE.equals(column)) {
                ScalarResult scalar = (ScalarResult)result;
                return formatNumber(scalar.getValue(), unit);
            }
            else if (type == PanelType.VECTORS) {
                VectorResult vector = (VectorResult)result;
                if (COL_VECTOR_ID.equals(column)) {
                    return new StyledString(String.valueOf(vector.getVectorId()));
                }
                else if (COL_COUNT.equals(column)) {
                    long count = vector.getStatistics().getCount();
                    return count >= 0 ? new StyledString(String.valueOf(count)) : NA;
                }
                else if (COL_MEAN.equals(column)) {
                    double mean = vector.getStatistics().getMean();
                    return Double.isNaN(mean) ? NA : formatNumber(mean, unit);
                }
                else if (COL_STDDEV.equals(column)) {
                    double stddev = vector.getStatistics().getStddev();
                    return Double.isNaN(stddev) ? NA : formatNumber(stddev, unit);
                }
                else if (COL_VARIANCE.equals(column)) {
                    double variance = vector.getStatistics().getVariance();
                    String unitSquared = unit;
                    if (!unit.isEmpty())
                        unitSquared = unit + "\u00B2"; // "Superscript Two"
                    return Double.isNaN(variance) ? NA : formatNumber(variance, unitSquared);
                }
                else if (COL_MIN.equals(column)) {
                    double min = vector.getStatistics().getMin();
                    return Double.isNaN(min) ? NA : formatNumber(min, unit);
                }
                else if (COL_MAX.equals(column)) {
                    double max = vector.getStatistics().getMax();
                    return Double.isNaN(max) ? NA : formatNumber(max, unit);
                }
                else if (COL_MIN_TIME.equals(column)) {
                    BigDecimal minTime = vector.getStartTime();
                    return minTime == null || minTime.isNaN() ? NA : formatNumber(minTime);
                }
                else if (COL_MAX_TIME.equals(column)) {
                    BigDecimal maxTime = vector.getEndTime();
                    return maxTime == null || maxTime.isNaN() ? NA : formatNumber(maxTime);
                }
            }
            else if (type == PanelType.HISTOGRAMS) {
                StatisticsResult stats = (StatisticsResult)result;
                if (COL_KIND.equals(column)) {
                    boolean isHistogram = result instanceof HistogramResult;
                    boolean isWeighted = stats.getStatistics().isWeighted();
                    return new StyledString(isHistogram ? (isWeighted ? "wh" : "h") : (isWeighted ? "ws" : "s"));
                }
                else if (COL_COUNT.equals(column)) {
                    long count = stats.getStatistics().getCount();
                    return count >= 0 ? new StyledString(String.valueOf(count)) : NA;
                }
                else if (COL_SUMWEIGHTS.equals(column)) {
                    if (!stats.getStatistics().isWeighted())
                        return NA;
                    double sumWeights = stats.getStatistics().getSumWeights();
                    return sumWeights >= 0 ? formatNumber(sumWeights, "") : NA;
                }
                else if (COL_MEAN.equals(column)) {
                    double mean = stats.getStatistics().getMean();
                    return Double.isNaN(mean) ? NA : formatNumber(mean, unit);
                }
                else if (COL_STDDEV.equals(column)) {
                    double stddev = stats.getStatistics().getStddev();
                    return Double.isNaN(stddev) ? NA : formatNumber(stddev, unit);
                }
                else if (COL_VARIANCE.equals(column)) {
                    double variance = stats.getStatistics().getVariance();
                    String unitSquared = unit;
                    if (!unit.isEmpty())
                        unitSquared = unit + "\u00B2"; // "Superscript Two"
                    return Double.isNaN(variance) ? NA : formatNumber(variance, unitSquared);
                }
                else if (COL_MIN.equals(column)) {
                    double min = stats.getStatistics().getMin();
                    return Double.isNaN(min) ? NA : formatNumber(min, unit);
                }
                else if (COL_MAX.equals(column)) {
                    double max = stats.getStatistics().getMax();
                    return Double.isNaN(max) ? NA : formatNumber(max, unit);
                }
                else if (COL_NUMBINS.equals(column)) {
                    if (result instanceof HistogramResult)
                        return new StyledString(String.valueOf(((HistogramResult)result).getHistogram().getNumBins()));
                    else
                        return NA;
                }
                else if (COL_HISTOGRAMRANGE.equals(column)) {
                    if (result instanceof HistogramResult) {
                        Histogram bins = ((HistogramResult)result).getHistogram();
                        if (bins.getNumBins() == 0)
                            return NA;
                        double lo = bins.getBinEdge(0);
                        double up = bins.getBinEdge(bins.getNumBins());
                        return formatNumber(lo, "").append(" .. ").append(formatNumber(up, unit));
                    }
                    else
                        return NA;
                }
            }
        }
        catch (RuntimeException e) {
            // stale ID?
            return new StyledString("");
        }

        return new StyledString("");
    }

    protected StyledString formatNumber(double d, String unit) {
        String result = ScaveUtil.formatNumber(d, getNumericPrecision());
        if (!unit.isEmpty())
            result += " " + unit;
        return new StyledString(result);
    }

    protected StyledString formatNumber(BigDecimal d) {
        return new StyledString(ScaveUtil.formatNumber(d, getNumericPrecision()) + " s");
    }

    public void copyRowsToClipboard(IProgressMonitor monitor) throws InterruptedException {
        if (manager == null)
            return;

        ResultFileManagerEx.runWithReadLock(manager, () -> {
            CsvWriter writer = new CsvWriter('\t');

            // add header
            for (Column column : visibleColumns)
                writer.addField(column.text);
            writer.endRecord();

            // add selected lines
            int[] selection = getSelectionIndices().toArray();
            int batchSize = 100_000;
            monitor.beginTask("Copying", (selection.length + batchSize - 1) / batchSize);

            int count = 0;
            for (int rowIndex : selection) {
                for (Column column : visibleColumns)
                    writer.addField(getCellValue(rowIndex, column, null, -1).getString());
                writer.endRecord();

                if (++count % batchSize == 0) {
                    // update UI
                    monitor.worked(1);
                    while (Display.getCurrent().readAndDispatch());
                    if (monitor.isCanceled())
                        return;  // cannot throw checked exception InterruptedException :(
                }
            }

            // copy to clipboard
            Clipboard clipboard = new Clipboard(getDisplay());
            clipboard.setContents(new Object[] {writer.toString()}, new Transfer[] {TextTransfer.getInstance()});
            clipboard.dispose();
        });
        if (monitor.isCanceled())
            throw new InterruptedException();

    }

    public void addDataListener(IDataListener listener) {
        if (listeners == null)
            listeners = new ListenerList<>();
        listeners.add(listener);
    }

    public void removeDataListener(IDataListener listener) {
        if (listeners != null)
            listeners.remove(listener);
    }

    protected void fireContentChangedEvent() {
        if (listeners != null)
            for (IDataListener listener : listeners)
                listener.contentChanged(this);
    }

    protected String getPreferenceStoreKey(Column column, String field) {
        return "DataTable." + type + "." + column.text + "." + field;
    }

    protected void initDefaultState() {
        if (preferences != null) {
            for (Column column : getAllColumns()) {
                preferences.setDefault(getPreferenceStoreKey(column, "visible"), column.defaultVisible);
                preferences.setDefault(getPreferenceStoreKey(column, "width"), column.defaultWidth);
            }
        }
    }

    protected void loadState() {
        if (preferences != null) {
            visibleColumns.clear();
            for (Column column : getAllColumns()) {
                boolean visible = preferences.getBoolean(getPreferenceStoreKey(column, "visible"));
                if (visible) {
                    Column clone = column.clone();
                    clone.defaultWidth = preferences.getInt(getPreferenceStoreKey(column, "width"));
                    addColumn(clone);
                }
            }
        }
    }

    protected void saveState() {
        if (preferences != null) {
            for (Column column : getAllColumns()) {
                boolean visible = visibleColumns.indexOf(column) >= 0;
                preferences.setValue(getPreferenceStoreKey(column, "visible"), visible);
                if (visible)
                    preferences.setValue(getPreferenceStoreKey(column, "width"), getTableColumn(column).getWidth());
            }
        }
    }

    private void handleMouseDown(MouseEvent event) {
        if (isDisposed() || !isVisible())
            return;
        int columnIndex = getColumnIndexAt(event.x);
        selectedColumn = columnIndex < 0 ? null : getColumn(columnIndex);
        Debug.println("DataTable: selected column " + columnIndex + " which is " + selectedColumn.getText());
    }

    public String getSelectedField() {
        if (selectedColumn != null && !selectedColumn.isDisposed()) {
            Column column = (Column)selectedColumn.getData(COLUMN_KEY);
            if (column != null)
                return column.fieldName;
        }
        return null;
    }

    public String getSelectedCell() {
        if (getItemCount() == 0 || selectedColumn == null || selectedColumn.isDisposed())
            return null;
        Column column = (Column)selectedColumn.getData(COLUMN_KEY);
        return column == null ? null : getCellValue(getFocusIndex(), column, null, -1).getString();
    }

    public void setSelectedID(long id) {
        int index = idList.indexOf(id);
        if (index != -1)
            setSelectionIndex(index);
    }

    public void setSelectedIDs(IDList selectedIDList, InterruptedFlag interrupted) throws InterruptedException {
            int[] indices = getIndices(selectedIDList, interrupted);
            Arrays.sort(indices);
            setSelectionIndices(indices);
    }

    public int[] getIndices(IDList selectedIDList, InterruptedFlag interrupted) throws InterruptedException {
        // note: this method is O(n^2) which can be prohibitive for large input+selection pairs!
        ArrayList<Integer> indicesList = new ArrayList<>();
        for (int i = 0; i < selectedIDList.size(); i++) {
            int index = idList.indexOf(selectedIDList.get(i)); // note: linear search
            if (index != -1)
                indicesList.add(index);
            if ((i & 1023) == 0 && interrupted.getFlag())
                throw new InterruptedException();
        }
        int[] indices = new int[indicesList.size()];
        for (int i = 0; i < indices.length; i++)
            indices[i] = indicesList.get(i);
        return indices;
    }
}
