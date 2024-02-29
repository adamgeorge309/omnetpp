/*--------------------------------------------------------------*
  Copyright (C) 2006-2015 OpenSim Ltd.

  This file is distributed WITHOUT ANY WARRANTY. See the file
  'License' for details on this and other legal matters.
*--------------------------------------------------------------*/

package org.omnetpp.scave.editors.ui;


import java.util.List;

import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IMemento;
import org.omnetpp.common.Debug;
import org.omnetpp.common.ui.DropdownAction;
import org.omnetpp.common.ui.FocusManager;
import org.omnetpp.scave.ScaveImages;
import org.omnetpp.scave.ScavePlugin;
import org.omnetpp.scave.actions.ClearCachesAction;
import org.omnetpp.scave.actions.CopySelectionAsFilterAction;
import org.omnetpp.scave.actions.CreateTempChartFromGalleryAction;
import org.omnetpp.scave.actions.CreateTempChartFromTemplateAction;
import org.omnetpp.scave.actions.analysismodel.SetChartFilterAction;
import org.omnetpp.scave.actions.ui.CopySelectedCellAction;
import org.omnetpp.scave.actions.ui.DecreaseDecimalPlacesAction;
import org.omnetpp.scave.actions.ui.FlatModuleTreeAction;
import org.omnetpp.scave.actions.ui.IncreaseDecimalPlacesAction;
import org.omnetpp.scave.actions.ui.SetFilterBySelectedCellAction;
import org.omnetpp.scave.editors.IDListSelection;
import org.omnetpp.scave.editors.ScaveEditor;
import org.omnetpp.scave.editors.ScaveEditorActions;
import org.omnetpp.scave.editors.datatable.ChooseTableColumnsAction;
import org.omnetpp.scave.editors.datatable.DataTable;
import org.omnetpp.scave.editors.datatable.DataTree;
import org.omnetpp.scave.editors.datatable.FilteredDataPanel;
import org.omnetpp.scave.editors.datatable.FilteredDataTabFolder;
import org.omnetpp.scave.editors.datatable.IDataControl;
import org.omnetpp.scave.editors.datatable.IDataListener;
import org.omnetpp.scave.editors.datatable.PanelType;
import org.omnetpp.scave.engine.IDList;
import org.omnetpp.scave.engine.ResultFileManager;
import org.omnetpp.scave.engineext.IResultFilesChangeListener;
import org.omnetpp.scave.engineext.ResultFileManagerChangeEvent;
import org.omnetpp.scave.model.Chart;
import org.omnetpp.scave.model.ChartTemplate;
import org.omnetpp.scave.model2.ScaveModelUtil;

/**
 * This is the "Browse Data" page of Scave Editor
 */
//TODO remove page description; display "empty-table message" instead (stacklayout?)
//TODO save filter bar sash positions
//TODO focus issue (currently a toolbar icon gets the focus by default?)
public class BrowseDataPage extends FormEditorPage {
    public static final int PROGRESSDIALOG_DELAY_MILLIS = 5000;

    // UI elements
    private Label label;
    private FilteredDataTabFolder tabFolder;

    private IResultFilesChangeListener fileChangeListener;
    private Runnable scheduledUpdate;
    private boolean isContentValid = false;

    private boolean numberFormattingEnabled = true;
    private int numericPrecision = 6;

    private DropdownAction treeLevelsAction;
    private FlatModuleTreeAction flatModuleTreeAction;
    private ChooseTableColumnsAction chooseTableColumnsAction;

    public BrowseDataPage(Composite parent, ScaveEditor editor) {
        super(parent, SWT.NONE, editor);
        initialize();
        hookListeners();
        addDisposeListener((e) -> { // Overriding dispose() does not work, see Widget.dispose()
            unhookListeners();
        });
    }

    public FilteredDataPanel getAllPanel() {
        return tabFolder.getAllPanel();
    }

    public FilteredDataPanel getScalarsPanel() {
        return tabFolder.getScalarsPanel();
    }

    public FilteredDataPanel getParametersPanel() {
        return tabFolder.getParametersPanel();
    }

    public FilteredDataPanel getVectorsPanel() {
        return tabFolder.getVectorsPanel();
    }

    public FilteredDataPanel getHistogramsPanel() {
        return tabFolder.getHistogramsPanel();
    }

    public FilteredDataPanel getActivePanel() {
        return tabFolder.getActivePanel();
    }

    public PanelType getActivePanelType() {
        return tabFolder.getActivePanel().getType();
    }

    public void setActivePanel(FilteredDataPanel panel) {
        tabFolder.setActivePanel(panel);
    }

    public void setActivePanel(PanelType type) {
        tabFolder.setActivePanel(tabFolder.getPanel(type));
    }

    private void initialize() {
        // set up UI
        setPageTitle("Browse Data");
        setFormTitle("Browse Data");
        //setBackground(ColorFactory.WHITE);
        getContent().setLayout(new GridLayout());
        label = new Label(getContent(), SWT.WRAP);
        label.setText("Here you can see all data that come from the files specified in the Inputs page.");

        tabFolder = new FilteredDataTabFolder(getContent(), SWT.NONE);
        tabFolder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        configureFilteredDataPanel(tabFolder.getAllPanel());
        configureFilteredDataPanel(tabFolder.getVectorsPanel());
        configureFilteredDataPanel(tabFolder.getScalarsPanel());
        configureFilteredDataPanel(tabFolder.getParametersPanel());
        configureFilteredDataPanel(tabFolder.getHistogramsPanel());
        configureContextMenu(tabFolder.getAllPanel());
        configureContextMenu(tabFolder.getScalarsPanel());
        configureContextMenu(tabFolder.getParametersPanel());
        configureContextMenu(tabFolder.getVectorsPanel());
        configureContextMenu(tabFolder.getHistogramsPanel());

        // set up contents
        tabFolder.setResultFileManager(scaveEditor.getResultFileManager());

        setNumericPrecision(getNumericPrecision()); // make sure it takes effect

        // ensure that focus gets restored correctly after user goes somewhere else and then comes back
        setFocusManager(new FocusManager(this));

        // add actions to the toolbar
        ScaveEditorActions actions = scaveEditor.getActions();
        addToToolbar(actions.copyRowsToClipboardAction);

        DropdownAction exportDataAction = new DropdownAction("Export Data", "Export In: ", ScavePlugin.getImageDescriptor(ScaveImages.IMG_ETOOL16_EXPORT), false);
        actions.createExportDataMenu(exportDataAction.getMenuManager());
        addToToolbar(exportDataAction);

        addSeparatorToToolbar();

        treeLevelsAction = new DropdownAction("Tree Levels", "Switch To: ", ScavePlugin.getImageDescriptor(ScaveImages.IMG_ETOOL16_TREELEVELS), true);
        DataTree dataTree = (DataTree)getAllPanel().getDataControl();
        dataTree.contributeTreeLevelsActionsTo(treeLevelsAction.getMenuManager());
        addToToolbar(treeLevelsAction);

        flatModuleTreeAction = ((DataTree)getAllPanel().getDataControl()).getFlatModuleTreeAction();
        addToToolbar(flatModuleTreeAction);

        chooseTableColumnsAction = new ChooseTableColumnsAction(null);
        addToToolbar(chooseTableColumnsAction);

        addToToolbar(actions.showFieldsAsScalarsAction);
        addSeparatorToToolbar();

        addToToolbar(new IncreaseDecimalPlacesAction());
        addToToolbar(new DecreaseDecimalPlacesAction());  //TODO get these refreshed when min/max precision is reached
        addSeparatorToToolbar();

        addToToolbar(actions.refreshResultFilesAction);
        addSeparatorToToolbar();

        addToToolbar(actions.plotAction);

        // show/hide actions that are specific to tab pages
        tabFolder.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                updateIconVisibility();
            }
        });
        updateIconVisibility();
    }

    private void updateIconVisibility() {
        boolean isAllPanelActive = (getActivePanel() == getAllPanel());
        setToolbarActionVisible(flatModuleTreeAction, isAllPanelActive);
        setToolbarActionVisible(treeLevelsAction, isAllPanelActive);
        setToolbarActionVisible(chooseTableColumnsAction, !isAllPanelActive);
        if (!isAllPanelActive)
            chooseTableColumnsAction.setDataTable((DataTable)getActivePanel().getDataControl());
    }

    private void configureContextMenu(FilteredDataPanel panel) {
        // populate the popup menu of the panel
        IMenuManager contextMenuManager = panel.getDataControl().getContextMenuManager();
        contextMenuManager.removeAll();

        ScaveEditorActions actions = scaveEditor.getActions();

        IDList selectedIDs = panel.getDataControl().getSelectedIDs();
        if (selectedIDs != null) {
            int selectionItemTypes = selectedIDs.getItemTypes();
            List<ChartTemplate> supportingChartTemplates = scaveEditor.getChartTemplateRegistry().getChartTemplatesForResultTypes(selectionItemTypes);

            for (ChartTemplate templ : supportingChartTemplates)
                contextMenuManager.add(new CreateTempChartFromTemplateAction(templ));
            contextMenuManager.add(new Separator());

            contextMenuManager.add(new CreateTempChartFromGalleryAction(selectionItemTypes));
            contextMenuManager.add(new Separator());
        }


        if (panel == getScalarsPanel())
            contextMenuManager.add(actions.showFieldsAsScalarsAction);
        contextMenuManager.add(actions.copyRowsToClipboardAction);
        if (panel != getAllPanel())
            contextMenuManager.add(new CopySelectedCellAction());
        contextMenuManager.add(new CopySelectionAsFilterAction());
        contextMenuManager.add(new Separator());

        if (panel != getAllPanel())
            contextMenuManager.add(new SetFilterBySelectedCellAction());
        MenuManager setFilterSubmenu = new MenuManager("Set Filter of Chart", ScavePlugin.getImageDescriptor(ScaveImages.IMG_ETOOL16_SETFILTER), null);
        List<Chart> chartsThatHaveFilter = ScaveModelUtil.collectCharts(scaveEditor.getAnalysis().getRootFolder(), chart -> chart.getProperty("filter") != null);
        for (Chart chart : chartsThatHaveFilter)
            setFilterSubmenu.add(new SetChartFilterAction(chart));
        contextMenuManager.add(setFilterSubmenu);
        if (Debug.inDevelopment())
            contextMenuManager.add(new ClearCachesAction());
        contextMenuManager.add(new Separator());

        contextMenuManager.add(actions.createExportDataMenu("Export Data"));
        contextMenuManager.add(new Separator());

        if (panel.getDataControl() instanceof DataTable)
            contextMenuManager.add(new ChooseTableColumnsAction((DataTable)panel.getDataControl()));
        if (panel.getDataControl() instanceof DataTree)
            ((DataTree)panel.getDataControl()).contributeToContextMenu(contextMenuManager);
        if (PanelType.VECTORS.equals(panel.getType())) {
            contextMenuManager.add(new Separator());
            contextMenuManager.add(actions.showOutputVectorViewAction);
        }
        // XXX call getSite().registerContexMenu() ?
    }

    /**
     * Utility function configure data panel to display selection count in the status bar.
     */
    private void configureFilteredDataPanel(FilteredDataPanel panel) {
        final IDataControl control = panel.getDataControl();
        control.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                showStatusMessage(String.format("Selected %d out of %d rows", control.getSelectionCount(), control.getItemCount()));
            }
        });
        control.addDataListener(new IDataListener() {
            @Override
            public void contentChanged(IDataControl control) {
                showStatusMessage(String.format("Selected %d out of %d rows", control.getSelectionCount(), control.getItemCount()));
            }
        });
    }

    private void hookListeners() {
        // asynchronously update the page contents on result file changes (ie. input file load/unload)
        if (fileChangeListener == null) {
            fileChangeListener = new IResultFilesChangeListener() {
                @Override
                public void resultFileManagerChanged(ResultFileManagerChangeEvent event) {
                    if (isDisposed())
                        return;
                    final ResultFileManager manager = event.getResultFileManager();
                    if (scheduledUpdate == null) {
                        scheduledUpdate = new Runnable() {
                            @Override
                            public void run() {
                                scheduledUpdate = null;
                                if (!isDisposed()) {
                                    ResultFileManager.runWithReadLock(manager, () -> {
                                        refreshPage(manager);
                                    });
                                }
                            }
                        };
                        getDisplay().asyncExec(scheduledUpdate);
                    }
                }
            };
            scaveEditor.getResultFilesTracker().addChangeListener(fileChangeListener);
        }

        // when they double-click in the vectors panel, open chart
        SelectionListener selectionListener = new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                FilteredDataPanel panel = tabFolder.getActivePanel();
                if (panel != null) {
                    Object source = e.getSource();
                    if (source == panel.getDataControl() || source == tabFolder) {
                        Debug.time("Browse Data page selection change handling", 1, () -> {
                            ResultFileManager.runWithReadLock(panel.getResultFileManager(), () -> {
                                updateSelection();
                            });
                        });
                    }
                }
            }

            @Override
            public void widgetDefaultSelected(SelectionEvent e) {
                scaveEditor.getActions().plotAction.run();
            }
        };

        tabFolder.getAllPanel().getDataControl().addSelectionListener(selectionListener);
        tabFolder.getVectorsPanel().getDataControl().addSelectionListener(selectionListener);
        tabFolder.getScalarsPanel().getDataControl().addSelectionListener(selectionListener);
        tabFolder.getParametersPanel().getDataControl().addSelectionListener(selectionListener);
        tabFolder.getHistogramsPanel().getDataControl().addSelectionListener(selectionListener);
        tabFolder.addSelectionListener(selectionListener);
    }

    private void unhookListeners() {
        if (fileChangeListener != null) {
            scaveEditor.getResultFilesTracker().removeChangeListener(fileChangeListener);
            fileChangeListener = null;
        }
    }

    /**
     * Updates the page: retrieves the list of vectors, scalars, etc from
     * ResultFileManager, updates the data controls with them, and updates
     * the tab labels as well.
     */
    protected void refreshPage(ResultFileManager manager) {
        if (isActivePage()) {
            doRefreshPage(manager);
            isContentValid = true;
        }
        else {
            isContentValid = false;
        }
    }

    @Override
    public void pageActivated() {
        if (!isContentValid)
            refreshPage(scaveEditor.getResultFileManager());
        updateSelection();
    }

    protected void doRefreshPage(ResultFileManager manager) {
        IDList items = manager.getAllItems(tabFolder.getAllPanel().getShowFieldsAsScalars());
        tabFolder.getAllPanel().setIDList(items);

        IDList scalars = manager.getAllScalars(tabFolder.getScalarsPanel().getShowFieldsAsScalars());
        tabFolder.getScalarsPanel().setIDList(scalars);

        IDList parameters = manager.getAllParameters();
        tabFolder.getParametersPanel().setIDList(parameters);

        IDList vectors = manager.getAllVectors();
        tabFolder.getVectorsPanel().setIDList(vectors);

        IDList statisticsAndHistograms = manager.getAllStatistics().unionWith(manager.getAllHistograms());
        tabFolder.getHistogramsPanel().setIDList(statisticsAndHistograms);

        tabFolder.refreshPanelTitles();
    }

    /**
     * Sets the editor selection to the selection of control of the active panel.
     */
    protected void updateSelection() {
        FilteredDataPanel panel = tabFolder.getActivePanel();
        if (panel != null) {
            IDataControl control = panel.getDataControl();
            IDList idList = Debug.timed("Getting selected IDs", 1, () -> control.getSelectedIDs());
            if (idList == null)
                idList = new IDList();
            scaveEditor.setSelection(new IDListSelection(idList, control.getResultFileManager(), panel));

            Debug.time("Browse Data page configureContextMenus", 1, () -> {
                // TODO FIXME HACK this is just to make the "available templates" actually update when the selection changes,
                // but actually, we do not need to be this eager, we would be fine if we did this every time
                // the user requests a context menu
                configureContextMenu(tabFolder.getAllPanel());
                configureContextMenu(tabFolder.getScalarsPanel());
                configureContextMenu(tabFolder.getParametersPanel());
                configureContextMenu(tabFolder.getVectorsPanel());
                configureContextMenu(tabFolder.getHistogramsPanel());
            });
        }
    }

    public void setNumberFormattingEnabled(boolean enabled) {
        this.numberFormattingEnabled = enabled;
        getAllPanel().getDataControl().setNumberFormattingEnabled(enabled);
        getScalarsPanel().getDataControl().setNumberFormattingEnabled(enabled);
        getParametersPanel().getDataControl().setNumberFormattingEnabled(enabled);
        getVectorsPanel().getDataControl().setNumberFormattingEnabled(enabled);
        getHistogramsPanel().getDataControl().setNumberFormattingEnabled(enabled);
    }

    public boolean getNumberFormattingEnabled() {
        return numberFormattingEnabled;
    }

    public void setNumericPrecision(int prec) {
        this.numericPrecision = prec;
        getAllPanel().getDataControl().setNumericPrecision(prec);
        getScalarsPanel().getDataControl().setNumericPrecision(prec);
        getParametersPanel().getDataControl().setNumericPrecision(prec);
        getVectorsPanel().getDataControl().setNumericPrecision(prec);
        getHistogramsPanel().getDataControl().setNumericPrecision(prec);
    }

    public int getNumericPrecision() {
        return numericPrecision;
    }

    public void setShowFieldsAsScalars(boolean show) {
        getAllPanel().setShowFieldsAsScalars(show);
        getScalarsPanel().setShowFieldsAsScalars(show);
        refreshPage(scaveEditor.getResultFileManager());
        scaveEditor.getActions().showFieldsAsScalarsAction.setChecked(show);
    }

    @Override
    public void saveState(IMemento memento) {
        super.saveState(memento);
        memento.putInteger("activeTab", tabFolder.getSelectionIndex());
        memento.putInteger("numericPrecision", getNumericPrecision());
        memento.putBoolean("showFieldsAsScalars", getAllPanel().getShowFieldsAsScalars());
        //TODO contents of filter combos  (note: table columns are magically saved somehow)
    }

    @Override
    public void restoreState(IMemento memento) {
        Integer activeTab = memento.getInteger("activeTab");
        if (activeTab != null)
            tabFolder.setSelection(activeTab);

        // show/hide actions that are specific to tab pages
        updateIconVisibility();

        Integer prec = memento.getInteger("numericPrecision");
        if (prec != null)
            setNumericPrecision(prec);

        Boolean show = memento.getBoolean("showFieldsAsScalars");
        if (show != null)
            setShowFieldsAsScalars(show);
    }

}
