/*--------------------------------------------------------------*
  Copyright (C) 2006-2015 OpenSim Ltd.

  This file is distributed WITHOUT ANY WARRANTY. See the file
  'License' for details on this and other legal matters.
*--------------------------------------------------------------*/

package org.omnetpp.scave.editors;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.lang3.ArrayUtils;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.MessageDialogWithToggle;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.util.LocalSelectionTransfer;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.INavigationLocation;
import org.eclipse.ui.INavigationLocationProvider;
import org.eclipse.ui.IPartListener;
import org.eclipse.ui.ISaveablePart2;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.ListDialog;
import org.eclipse.ui.dialogs.ListSelectionDialog;
import org.eclipse.ui.dialogs.SaveAsDialog;
import org.eclipse.ui.ide.IGotoMarker;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.views.contentoutline.ContentOutlinePage;
import org.eclipse.ui.views.contentoutline.IContentOutlinePage;
import org.eclipse.ui.views.properties.IPropertyDescriptor;
import org.eclipse.ui.views.properties.IPropertySheetEntry;
import org.eclipse.ui.views.properties.IPropertySheetPage;
import org.eclipse.ui.views.properties.PropertySheetPage;
import org.eclipse.ui.views.properties.PropertySheetSorter;
import org.omnetpp.common.Debug;
import org.omnetpp.common.ui.LocalTransfer;
import org.omnetpp.common.ui.MultiPageEditorPartExt;
import org.omnetpp.common.ui.ViewerDragAdapter;
import org.omnetpp.common.util.DetailedPartInitException;
import org.omnetpp.common.util.ReflectionUtils;
import org.omnetpp.common.util.StringUtils;
import org.omnetpp.common.util.UIUtils;
import org.omnetpp.scave.AnalysisLoader;
import org.omnetpp.scave.AnalysisSaver;
import org.omnetpp.scave.LegacyAnalysisLoader;
import org.omnetpp.scave.ScaveImages;
import org.omnetpp.scave.ScavePlugin;
import org.omnetpp.scave.charting.PlotBase;
import org.omnetpp.scave.charttemplates.ChartTemplateRegistry;
import org.omnetpp.scave.editors.ui.BrowseDataPage;
import org.omnetpp.scave.editors.ui.ChartPage;
import org.omnetpp.scave.editors.ui.ChartsPage;
import org.omnetpp.scave.editors.ui.FormEditorPage;
import org.omnetpp.scave.editors.ui.InputsPage;
import org.omnetpp.scave.engine.ResultFileManager;
import org.omnetpp.scave.engineext.ResultFileManagerEx;
import org.omnetpp.scave.model.Analysis;
import org.omnetpp.scave.model.AnalysisItem;
import org.omnetpp.scave.model.Chart;
import org.omnetpp.scave.model.ChartTemplate;
import org.omnetpp.scave.model.Folder;
import org.omnetpp.scave.model.IModelChangeListener;
import org.omnetpp.scave.model.InputFile;
import org.omnetpp.scave.model.Inputs;
import org.omnetpp.scave.model.ModelChangeEvent;
import org.omnetpp.scave.model.ModelObject;
import org.omnetpp.scave.model.commands.AddInputFileCommand;
import org.omnetpp.scave.model.commands.CommandStack;
import org.omnetpp.scave.model.commands.SetChartContentsCommand;
import org.omnetpp.scave.model2.ResultItemRef;
import org.omnetpp.scave.model2.ScaveModelUtil;
import org.omnetpp.scave.pychart.PythonProcessPool;
import org.omnetpp.scave.python.ChartViewerBase;
import org.omnetpp.scave.python.NativeChartViewer;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * OMNeT++ Analysis tool.
 *
 * @author andras, tomi
 */
public class ScaveEditor extends MultiPageEditorPartExt
        implements ISelectionProvider, IGotoMarker, INavigationLocationProvider, IModelChangeListener, ISaveablePart2 {
    public static final String ACTIVE_PAGE = "ActivePage", PAGE = "Page",
            PAGE_ID = "PageId", TEMPLATE_TIMESTAMPS = "TemplateTimestamps";

    protected static final String PREF_DONT_SHOW_PYTHON_EXECUTION_WARNING_DIALOG = "dont_show_python_execution_warning_dialog";

    private InputsPage inputsPage;
    private BrowseDataPage browseDataPage;
    private ChartsPage chartsPage;

    private Map<AnalysisItem, Control> closablePages = new LinkedHashMap<AnalysisItem, Control>();

    private PythonProcessPool processPool = new PythonProcessPool(2);
    private ChartTemplateRegistry chartTemplateRegistry = new ChartTemplateRegistry();

    /**
     * This is the content outline page.
     */
    protected ScaveEditorContentOutlinePage contentOutlinePage;

    /**
     * This is a kludge...
     */
    protected IStatusLineManager contentOutlineStatusLineManager;

    /**
     * This is the content outline page's viewer.
     */
    protected TreeViewer contentOutlineViewer;

    /**
     * This is the property sheet page.
     */
    protected List<PropertySheetPage> propertySheetPages = new ArrayList<PropertySheetPage>();

    /**
     * The selection change listener added to all viewers
     */
    protected ISelectionChangedListener selectionChangedListener = new ISelectionChangedListener() {
        public void selectionChanged(SelectionChangedEvent selectionChangedEvent) {
            handleSelectionChange(selectionChangedEvent.getSelection());
        }
    };

    protected boolean selectionChangeInProgress = false; // to prevent recursive notifications

    /**
     * This keeps track of all the
     * {@link org.eclipse.jface.viewers.ISelectionChangedListener}s that are
     * listening to this editor. We need this because we implement
     * ISelectionProvider which includes having to manage a listener list.
     */
    protected Collection<ISelectionChangedListener> selectionChangedListeners = new ArrayList<ISelectionChangedListener>();

    /**
     * This keeps track of the selection of the editor as a whole.
     */
    protected ISelection editorSelection = StructuredSelection.EMPTY;


    protected Analysis analysis;

    protected ScaveEditorActions actions = new ScaveEditorActions(this);

    /**
     * This listens for when the outline becomes active
     */
    protected IPartListener partListener = new IPartListener() {
        public void partActivated(IWorkbenchPart p) {
            if (p == ScaveEditor.this) {
                ChartScriptEditor activeChartScriptEditor = getActiveChartScriptEditor();
                if (activeChartScriptEditor != null)
                    activeChartScriptEditor.pageActivated();
            }
        }

        public void partBroughtToTop(IWorkbenchPart p) {
        }

        public void partClosed(IWorkbenchPart p) {
            if (p == ScaveEditor.this) {
                getSite().getPage().removePartListener(this);
                saveState();
            }
        }

        public void partDeactivated(IWorkbenchPart p) {
        }

        public void partOpened(IWorkbenchPart p) {
        }
    };

    /**
     * ResultFileManager containing all files of the analysis.
     */
    private ResultFileManagerEx manager = new ResultFileManagerEx();

    /**
     * Loads/unloads result files in manager, according to changes in the model and
     * in the workspace.
     */
    private ResultFilesTracker tracker;

    /**
     * Memoization support for speeding up Python result queries.
     */
    private MemoizationCache memoizationCache;

    /**
     * Caches filter results
     */
    private FilterCache filterCache;

    /**
     * The constructor.
     */
    public ScaveEditor() {
    }

    public ResultFileManagerEx getResultFileManager() {
        return manager;
    }

    public ResultFilesTracker getResultFilesTracker() {
        return tracker;
    }

    public MemoizationCache getMemoizationCache() {
        return memoizationCache;
    }

    public FilterCache getFilterCache() {
        return filterCache;
    }

    public InputsPage getInputsPage() {
        return inputsPage;
    }

    public BrowseDataPage getBrowseDataPage() {
        return browseDataPage;
    }

    public ChartsPage getChartsPage() {
        return chartsPage;
    }

    @Override
    public void init(IEditorSite site, IEditorInput editorInput) throws PartInitException {
        if (!(editorInput instanceof IFileEditorInput))
            throw new DetailedPartInitException(
                    "Invalid input, it must be a file in the workspace: " + editorInput.getName(),
                    "Please make sure the project is open before trying to open a file in it.");
        IFile fileInput = ((IFileEditorInput) editorInput).getFile();
        if (!editorInput.exists())
            throw new PartInitException("File '" + fileInput.getFullPath().toString() + "' does not exist");

        // init super. Note that this does not load the model yet -- it's done in
        // createModel() called from createPages().
        setSite(site);
        setInputWithNotify(editorInput);
        setPartName(editorInput.getName());
        site.setSelectionProvider(this);
        site.getPage().addPartListener(partListener);

        chartTemplateRegistry.setProject(fileInput.getProject());
    }

    @Override
    public void dispose() {

        processPool.dispose();

        if (tracker != null)
            analysis.removeListener(tracker);

        if (getSite() != null && getSite().getPage() != null)
            getSite().getPage().removePartListener(partListener);

        for (PropertySheetPage propertySheetPage : propertySheetPages)
            propertySheetPage.dispose();

        if (contentOutlinePage != null)
            contentOutlinePage.dispose();

        getSite().setSelectionProvider(null);

        if (manager != null) {
            manager.delete(); // ensure that memory is freed even if the ScaveEditor object or parts of it are leaked
            manager = null;
        }

        if (memoizationCache != null) {
            memoizationCache.clear(); // ditto: ensure that memory is freed even if the ScaveEditor object or parts of it are leaked
            memoizationCache = null;
        }

        if (filterCache != null) {
            filterCache.clear();
            filterCache = null;
        }

        super.dispose();

    }

    // Modified DropAdapter to convert drop events.
    // The original EditingDomainViewerDropAdapter tries to add
    // files to the ResourceSet as XMI documents (what probably
    // causes a parse error). Here we convert the URIs of the
    // drop source to InputFiles and modify the drop target.
    // class DropAdapter
    // {
    // List<InputFile> inputFilesInSource = null;
    //
    // public DropAdapter(EditingDomain domain, Viewer viewer) {
    // super(domain, viewer);
    // }
    //
    // @Override
    // protected Collection<?> extractDragSource(Object object) {
    // Collection<?> collection = super.extractDragSource(object);
    //
    // // find URIs in source and convert them InputFiles
    // inputFilesInSource = null;
    // for (Object element : collection) {
    // if (element instanceof URI) {
    // String workspacePath = getWorkspacePathFromURI((URI)element);
    // if (workspacePath != null) {
    // if (inputFilesInSource == null)
    // inputFilesInSource = new ArrayList<InputFile>();
    // if (workspacePath.endsWith(".sca") || workspacePath.endsWith(".vec")) {
    // InputFile file = new InputFile(workspacePath);
    // inputFilesInSource.add(file);
    // }
    // }
    // }
    // }
    //
    // return inputFilesInSource != null ? inputFilesInSource : collection;
    // }
    //
    // @Override
    // protected Object extractDropTarget(Widget item) {
    // Object target = super.extractDropTarget(item);
    // if (inputFilesInSource != null) {
    // if (target instanceof InputFile || target == null)
    // target = getAnalysis().getInputs();
    // }
    // return target;
    // }
    // }

    // private String getWorkspacePathFromURI(URI uri) {
    // if (uri.isFile()) {
    // IPath path = new Path(uri.toFileString());
    // IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
    // IFile file = root.getFileForLocation(path);
    // return file != null ? file.getFullPath().toString() : null;
    // }
    // else if (uri.isPlatformResource())
    // return uri.toPlatformString(true);
    // else
    // return null;
    // }

    protected void setupDragAndDropSupportFor(StructuredViewer viewer) {
        int dndOperations = DND.DROP_COPY | DND.DROP_MOVE | DND.DROP_LINK;
        // XXX FileTransfer causes an exception
        Transfer[] transfers = new Transfer[] { LocalTransfer.getInstance(),
                LocalSelectionTransfer.getTransfer()/* , FileTransfer.getInstance() */ };
        viewer.addDragSupport(dndOperations, transfers, new ViewerDragAdapter(viewer));

        // TODO
        // viewer.addDropSupport(dndOperations, transfers, new
        // DropAdapter(editingDomain, viewer));
    }

    public void loadAnalysis() {
        // Assumes that the input is a file object.
        IFileEditorInput modelFile = (IFileEditorInput) getEditorInput();

        try {
            DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            modelFile.getFile().refreshLocal(1, null);
            Document d = db.parse(modelFile.getFile().getContents());

            NodeList nl = d.getChildNodes();

            Node rootNode = nl.item(0);

            if ("scave:Analysis".equals(rootNode.getNodeName())) {
                MessageBox messageBox = new MessageBox(getSite().getShell(), SWT.OK | SWT.CANCEL | SWT.ICON_QUESTION);
                messageBox.setText("Convert Analysis File?");
                messageBox.setMessage("File " + modelFile.getFile().getFullPath() + " is in an older file format.\n"
                        + "Do you want to open and convert it?\n");
                int result = messageBox.open();
                switch (result) {
                case SWT.CANCEL:
                    analysis = null;
                    break;
                case SWT.OK:
                    ArrayList<String> errors = new ArrayList<>();
                    analysis = new LegacyAnalysisLoader(getChartTemplateRegistry(), errors).loadLegacyAnalysis(rootNode);

                    if (!errors.isEmpty()) {
                        ListDialog errorDialog = new ListDialog(Display.getCurrent().getActiveShell());
                        errorDialog.setInput(errors);
                        errorDialog.setContentProvider(new ArrayContentProvider());
                        errorDialog.setLabelProvider(new LabelProvider());
                        errorDialog.setMessage("Conversion errors:");
                        errorDialog.open();
                    }

                    break;
                }
            }
            else {
                analysis = AnalysisLoader.loadNewAnalysis(rootNode, getChartTemplateRegistry());
            }
        }
        catch (SAXException | IOException | CoreException | ParserConfigurationException | RuntimeException e) {
            MessageDialog.openError(getSite().getShell(), "Error",
                    "Could not open resource " + modelFile.getFile().getFullPath() + "\n\n" + e.getMessage());
            ScavePlugin.logError("could not load resource", e);
            analysis = null;
        }

        if (analysis == null)
            return;

        IFile inputFile = ((IFileEditorInput) getEditorInput()).getFile();
        tracker = new ResultFilesTracker(manager, analysis.getInputs(), inputFile.getParent());
        memoizationCache = new MemoizationCache(manager);
        filterCache = new FilterCache(manager);
        analysis.addListener(this);
        analysis.addListener(tracker);
    }

    protected void doCreatePages() {
        // add fixed pages: Inputs, Browse Data, Charts
        FillLayout layout = new FillLayout();
        getContainer().setLayout(layout);


        createInputsPage();
        createBrowseDataPage();
        createChartsPage();

        final CTabFolder tabfolder = getTabFolder();
        tabfolder.setMRUVisible(true);
        tabfolder.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                int newPageIndex = tabfolder.indexOf((CTabItem) e.item);
                pageChangedByUser(newPageIndex);
                ensureFixedPagesAlwaysVisible();
            }

            protected void ensureFixedPagesAlwaysVisible() {
                try {
                    Field field = CTabFolder.class.getDeclaredField("priority");
                    field.setAccessible(true);

                    // prio[] contains tab indices, in decreasing order of importance (wrt. which ones to hide)
                    int[] prio = (int[])field.get(tabfolder);

                    // move the first 3 pages (Inputs,Browse,Charts) to the front of the array
                    // also move the selected page to the very front, ensuring it is never hidden
                    int currentlySelected = prio[0];
                    prio = ArrayUtils.removeElements(prio, 0, 1, 2);
                    prio = ArrayUtils.addAll(new int[] {1, 2, 0}, prio);
                    prio = ArrayUtils.removeElement(prio, currentlySelected); // currentlySelected may be one of {0,1,2}
                    prio = ArrayUtils.add(prio, 0, currentlySelected);

                    field.set(tabfolder, prio);
                }
                catch (IllegalArgumentException | IllegalAccessException | NoSuchFieldException | SecurityException e1) {
                    ScavePlugin.logError("Tweaking CTabFolder tab priorities failed", e1);
                }
            }
        });

        // We use asyncExec here so that if there's an ANF file open at startup,
        // the workbench window appears before the "Loading result files" progress
        // dialog does
        Display.getCurrent().asyncExec(() -> {
            // We can load the result files now.
            refreshResultFiles();

            // now we can restore chart pages (and other closable pages)
            ResultFileManager.runWithReadLock(manager, () -> restoreState());
        });

    }

    protected void showPythonExecutionWarningDialogIfNeeded() {
        if (analysis.getRootFolder().getItems().isEmpty())
            return; // empty analysis -- not applicable

        IPreferenceStore preferences = ScavePlugin.getDefault().getPreferenceStore();
        String pref = preferences.getString(PREF_DONT_SHOW_PYTHON_EXECUTION_WARNING_DIALOG);
        if (MessageDialogWithToggle.ALWAYS.equals(pref))
            return; // "always don't show" -> never show... ha!

//TODO temporarily commented out, because it could interfere with the "Loading files" progress dialog (also modal), and cause the UI to lock.
// Probably asyncExec() is not a good idea (that's what causes this dialog to come up while another modal is already active.)
// Currently, asyncExec() is there to prevent the dialog from coming up too early, while the workbench
// is loading (splash screen is visible but workbench window not yet) -- but then some other solution need to be found for that?
//
//        Display.getCurrent().asyncExec(() -> {
//            MessageDialogWithToggle.openWarning(Display.getCurrent().getActiveShell(),
//                    "Security Warning",
//                    "The Analysis you've just opened contains charts which employ user-editable Python code to produce the content when they are opened. "
//                        + "These Python scripts, in addition to being extremely useful, can also be a potential hazard, since they "
//                        + "have as much access to your computer as any user program. There is no sandboxing.\n\n"
//                        + "Be sure to only open ANF files from trusted sources.",
//                    "Don't show this message again", false,
//                    preferences, PREF_DONT_SHOW_PYTHON_EXECUTION_WARNING_DIALOG);
//        });
    }

    public IPropertySheetPage getPropertySheetPage() {

        PropertySheetPage propertySheetPage = new PropertySheetPage() {
            // this is a constructor fragment --Andras
            {
                // turn off sorting for our INonsortablePropertyDescriptors
                setSorter(new PropertySheetSorter() {
                    public int compare(IPropertySheetEntry entryA, IPropertySheetEntry entryB) {
                        IPropertyDescriptor descriptorA = (IPropertyDescriptor) ReflectionUtils.getFieldValue(entryA,
                                "descriptor"); // it's f***ing private --Andras
                        if (descriptorA instanceof INonsortablePropertyDescriptor)
                            return 0;
                        else
                            return super.compare(entryA, entryB);
                    }
                });
            }

            @Override
            public void selectionChanged(IWorkbenchPart part, ISelection selection) {
                if (selection instanceof IDListSelection) {
                    // re-package element into ResultItemRef, otherwise property sheet
                    // only gets a Long due to sel.toArray() in base class. We only want
                    // to show properties if there's only one item in the selection.
                    IDListSelection idListSelection = (IDListSelection) selection;
                    if (idListSelection.size() == 1) {
                        long id = idListSelection.getIDList().get(0);
                        ResultItemRef resultItemRef = new ResultItemRef(id, idListSelection.getResultFileManager());
                        selection = new StructuredSelection(resultItemRef);
                    }
                }

                super.selectionChanged(part, selection);
            }
        };

        propertySheetPages.add(propertySheetPage);

        if (propertySheetPage instanceof PropertySheetPage) {
            propertySheetPage.setPropertySourceProvider(new ScavePropertySourceProvider(manager));
        }
        return propertySheetPage;

    }

    /**
     * Adds a fixed (non-closable) editor page at the last position
     */
    public int addFixedPage(FormEditorPage page) {
        int index = addPage(page);
        setPageText(index, page.getPageTitle());
        return index;
    }

    /**
     * Adds a closable editor page at the last position
     */
    public int addClosablePage(Control page, String pageTitle) {
        int index = getPageCount();
        addClosablePage(index, page);
        setPageText(index, pageTitle);
        return index;
    }

    public int addClosablePage(FormEditorPage page) {
        return addClosablePage(page, page.getPageTitle());
    }

    public int addClosablePage(IEditorPart editor, IEditorInput input, String pageTitle) throws PartInitException {
        int index = getPageCount();
        addClosablePage(index, editor, input);
        setPageText(index, pageTitle);
        return index;
    }

    public FormEditorPage getActiveEditorPage() {
        int i = getActivePage();
        if (i >= 0)
            return getEditorPage(i);
        else
            return null;
    }

    public void setActiveEditorPage(FormEditorPage page) {
        int pageIndex = getIndexOfEditorPage(page);
        if (pageIndex != -1)
            setActivePage(pageIndex);
    }

    public ChartScriptEditor getActiveChartScriptEditor() {
        FormEditorPage page = getActiveEditorPage();
        return page instanceof ChartPage ? ((ChartPage)page).getChartScriptEditor() : null;
    }

    public FormEditorPage getEditorPage(int pageIndex) {
        Control control = getControl(pageIndex);
        if (control instanceof FormEditorPage)
            return (FormEditorPage)control;
        return null;
    }

    public int getIndexOfEditorPage(FormEditorPage page) {
        for (int pageIndex = 0; pageIndex < getPageCount(); ++pageIndex)
            if (getEditorPage(pageIndex) == page)
                return pageIndex;
        return -1;
    }

    public ChartViewerBase getActiveChartViewer() {
        FormEditorPage activePage = getActiveEditorPage();
        return (activePage != null) ? activePage.getActiveChartViewer() : null;
    }

    /**
     * Returns the active native plot widget if the current page has one,
     * else (e.g. if the page contains a Matplotlib plot) returns null.
     */
    public PlotBase getActivePlot() {
        ChartViewerBase chartViewer = getActiveChartViewer();
        return chartViewer instanceof NativeChartViewer ? ((NativeChartViewer)chartViewer).getPlot() : null;
    }

    public void applyChartEdits(ChartScriptEditor editor) {

        Chart orig = editor.getOriginalChart();
        Chart chart = editor.getChart();

        chart.setEditedScript(null);
        chart.setScript(editor.getDocument().get());

        SetChartContentsCommand command = new SetChartContentsCommand(chart, orig, (Chart)chart.dup());
        if (!command.isEmpty())
            getChartsPage().getCommandStack().addExecuted(command);
    }

    public boolean askToKeepEditedTemporaryChart(ChartScriptEditor editor) {
        Chart chart = editor.getChart();

        if (!editor.getCommandStack().wasObjectAffected(chart) && !editor.isDirty())
            return true;

        if (chart.isTemporary()) {
            int result = MessageDialog.open(MessageDialog.QUESTION_WITH_CANCEL, Display.getCurrent().getActiveShell(),
                    "Keep Temporary Chart?",
                    "Keep chart '" + chart.getName() + "' as part of the analysis? If you choose 'No', your edits will be lost.",
                    SWT.NONE, "Yes", "No", "Cancel");

            switch (result) {
            case 0:
                String suggestedName = StringUtils.nullToEmpty(editor.getSuggestedChartName());
                InputDialog dialog = new InputDialog(getSite().getShell(), "Keep Chart", "Enter chart name", suggestedName, null);

                if (dialog.open() == InputDialog.OK) {
                    ScaveModelUtil.saveChart(chartsPage.getCommandStack(), chart, dialog.getValue(), getCurrentFolder());
                    return true;
                }
                else
                    return false;
            case 1:
                // no-op
                return true;
            case 2:
                return false;
            }
        }

        return true;
    }

    public Analysis getAnalysis() {
        return analysis;
    }

    public IFile getAnfFile() {
        IEditorInput input = getEditorInput();
        IFile file = ((FileEditorInput) input).getFile();
        return file;
    }

    public Folder getCurrentFolder() {
        return chartsPage.getCurrentFolder();
    }

    public void setCurrentFolder(Folder folder) {
        chartsPage.setCurrentFolder(folder);
    }

    /**
     * Opens the given <code>item</code> (chart), or switches
     * to it if already opened.
     */
    public FormEditorPage openPage(AnalysisItem item) {
        int pageIndex = getOrCreateClosablePage(item);
        setActivePage(pageIndex);
        return getEditorPage(pageIndex);
    }

    /**
     * Closes the page displaying the given <code>object</code>. If no such page,
     * nothing happens.
     */
    public void closePage(AnalysisItem object) {
        Control page = closablePages.get(object);
        if (page != null) {
            removePage(page);
        }
    }

    public void showInputsPage() {
        showPage(getInputsPage());
    }

    public void showBrowseDataPage() {
        showPage(getBrowseDataPage());
    }

    public void showChartsPage() {
        showPage(getChartsPage());
    }

    public void showPage(FormEditorPage page) {
        int pageIndex = findPage(page);
        if (pageIndex >= 0)
            setActivePage(pageIndex);
    }

    public void showAnalysisItem(AnalysisItem item) {
        showChartsPage();
        getChartsPage().getViewer().setSelection(new StructuredSelection(item));
        getChartsPage().getViewer().reveal(item);
    }

    public void gotoObject(Object object) {
        if (getAnalysis() == null)
            return;

        // if object (practically, a chart) is open, switch to that page
        if (object instanceof AnalysisItem) {
            FormEditorPage editorPage = getEditorPage((AnalysisItem)object);
            if (editorPage != null) {
                showPage(editorPage);
                return;
            }
        }

        // if active page can show it, do that
        FormEditorPage activePage = getActiveEditorPage();
        if (activePage != null && activePage.gotoObject(object))
            return;

        // find first page that can show it, and switch there
        int activePageIndex = -1;
        for (int pageIndex = getPageCount() - 1; pageIndex >= 0; --pageIndex) {
            FormEditorPage page = getEditorPage(pageIndex);
            if (page != null && page.gotoObject(object)) {
                activePageIndex = pageIndex;
                break;
            }
        }
        if (activePageIndex >= 0) {
            setActivePage(activePageIndex);
        }
    }

    public void setPageTitle(FormEditorPage page, String title) {
        int pageIndex = findPage(page);
        if (pageIndex >= 0)
            setPageText(pageIndex, title);
    }

    private void createInputsPage() {
        inputsPage = new InputsPage(getContainer(), this);
        addFixedPage(inputsPage);
    }

    private void createBrowseDataPage() {
        browseDataPage = new BrowseDataPage(getContainer(), this);
        addFixedPage(browseDataPage);
    }

    private void createChartsPage() {
        chartsPage = new ChartsPage(getContainer(), this);
        addFixedPage(chartsPage);
    }

    /**
     * Creates a closable page. These pages are closed automatically when the
     * displayed object (chart) is removed from the model. Their tabs contain a
     * small (x), so the user can also close them.
     */
    private int createClosablePage(AnalysisItem item) {
        if (item instanceof Chart)
            try {
                int index = openChartScriptEditor((Chart) item);
                closablePages.put(item, getControl(index));
                return index;
            } catch (PartInitException e) {
                ScavePlugin.logError(e);
                return -1;
            }
        else
            throw new IllegalArgumentException("Cannot create editor page for " + item);
    }

    @Override
    protected boolean pageCloseEvent(Control control) {
        saveState();

        ChartPage chartPage = (ChartPage)getEditorPage(findPage(control));
        ChartScriptEditor editor = chartPage.getChartScriptEditor();

        boolean allowClose = askToKeepEditedTemporaryChart(editor);

        if (allowClose)
            applyChartEdits(editor);

        return allowClose;
    }

    @Override
    protected void pageClosed(Control control) {
        // Assert.isTrue(closablePages.containsValue(control));

        // remove it from the map
        Iterator<Map.Entry<AnalysisItem, Control>> entries = closablePages.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<AnalysisItem, Control> entry = entries.next();
            if (control.equals(entry.getValue()))
                entries.remove();
        }

        // Open temporary charts should make the analysis dirty. When the last temp chart is closed,
        // the DIRTY flag should be cleared. Therefore, we now fire a property change to force Eclipse
        // re-evaluate the dirty status (via isDirty()). Note: It must be done in asyncExec(),
        // because isDirty() otherwise loops through this half-closed editor page too, resulting
        // in a "Widget already disposed" SWT exception.
        Display.getDefault().asyncExec(() -> firePropertyChange(IEditorPart.PROP_DIRTY));
    }

    /**
     * Returns the page displaying {@code item}.
     */
    public FormEditorPage getEditorPage(AnalysisItem item) {
        Control control = closablePages.get(item);
        if (control instanceof FormEditorPage)
            return (FormEditorPage)control;
        return null;
    }

    /**
     * Returns the page displaying <code>object</code>. If the object already has a
     * page it is returned, otherwise a new page created.
     */
    public int getOrCreateClosablePage(AnalysisItem item) {
        Control page = closablePages.get(item);
        int pageIndex = page != null ? findPage(page) : createClosablePage(item);
        Assert.isTrue(pageIndex >= 0);
        return pageIndex;
    }

    public void handleSelectionChange(ISelection selection) {
        // FIXME merge this method into fireSelectionChangedEvent()!!! that's where the
        // guard should be as well!
        boolean selectionReallyChanged = selection != editorSelection && !selection.equals(editorSelection);
        if (!selectionChangeInProgress || selectionReallyChanged) {
            try {
                selectionChangeInProgress = true; // "finally" ensures this gets reset in any case
                editorSelection = selection;
                // FIXME notifying the view about IDListSelections would remove the selection
                // from the editor!
                if (!(selection instanceof IDListSelection)) {
                    // setViewerSelectionNoNotify(contentOutlineViewer, selection);
                    updateStatusLineManager(contentOutlineStatusLineManager, selection);
                }
                updateStatusLineManager(getEditorSite().getActionBars().getStatusLineManager(), selection);
                actions.updateActions();
                Debug.println("selection changed: " + selection);
                fireSelectionChangedEvent(selection);
            } finally {
                selectionChangeInProgress = false;
            }
        }
    }

    private static class SaveOnCloseDialog extends ListSelectionDialog {
        @SuppressWarnings("deprecation")
        private SaveOnCloseDialog(Shell parentShell, String fileName, List<ChartPage> temporaryChartPages) {
            super(parentShell, temporaryChartPages,
                    new ArrayContentProvider(),
                    new LabelProvider() {
                        @Override
                        public String getText(Object element) {
                            ChartPage chartPage = (ChartPage)element;
                            String suggestedName = chartPage.getChartScriptEditor().getSuggestedChartName();
                            String text = chartPage.getChart().getName();
                            if (suggestedName != null)
                                text += " -> " + suggestedName;
                            return text;
                        }
                    },
                    "Do you want to save '" + fileName + "'?\n\nAlso, keep the following temporary charts as part of the analysis:");
            setInitialElementSelections(temporaryChartPages);
            setTitle("Save Changes");
        }

        @Override
        protected void createButtonsForButtonBar(Composite parent) {
            createButton(parent, IDialogConstants.NO_ID, "Don't Save", false);
            createButton(parent, IDialogConstants.CANCEL_ID, "Cancel", false);
            createButton(parent, IDialogConstants.YES_ID, "Save", true);
        }

        @Override
        protected void buttonPressed(int buttonId) {
            setReturnCode(buttonId);
            if (buttonId == IDialogConstants.YES_ID)
                super.okPressed(); // to set the selected result list; implies close()
            else if (buttonId == IDialogConstants.CANCEL_ID || buttonId == IDialogConstants.NO_ID) // note: the Select All/Deselect All buttons delegate here too, not only the Yes/No/Cancel ones!
                close();
        }
    }

    class ScaveEditorContentOutlinePage extends ContentOutlinePage {
        public void createControl(Composite parent) {
            super.createControl(parent);
            TreeViewer viewer = getTreeViewer();
            Tree tree = viewer.getTree();
            if (tree != null) {
                tree.addSelectionListener(new SelectionAdapter() {
                    @Override
                    public void widgetDefaultSelected(SelectionEvent e) {
                        if (e.item instanceof TreeItem) {
                            Object object = ((TreeItem) e.item).getData();
                            if (object instanceof Chart)
                                openPage((Chart)object);
                            else if (object instanceof Folder) {
                                setCurrentFolder((Folder)object);
                                showChartsPage();
                            }
                        }
                    }
                });
            }

            contentOutlineViewer = getTreeViewer();
            contentOutlineViewer.addSelectionChangedListener(this);

            // Set up the tree viewer.

            contentOutlineViewer.setContentProvider(new ITreeContentProvider() {

                @Override
                public boolean hasChildren(Object element) {
                    return getChildren(element).length > 0;
                }

                @Override
                public Object getParent(Object element) {
                    if (element instanceof ModelObject)
                        return ((ModelObject) element).getParent();
                    return null;
                }

                @Override
                public Object[] getElements(Object inputElement) {
                    return getChildren(inputElement);
                }

                @Override
                public Object[] getChildren(Object element) {
                    if (element instanceof Analysis)
                        return new Object[] { ((Analysis)element).getInputs(), ((Analysis)element).getRootFolder() };
                    else if (element instanceof Inputs)
                        return ((Inputs) element).getInputs().toArray();
                    else if (element instanceof Folder)
                        return ((Folder) element).getItems().toArray();
                    else
                        return new Object[0];
                }
            });
            contentOutlineViewer.setLabelProvider(new LabelProvider() {
                @Override
                public String getText(Object element) {
                    if (element instanceof Analysis)
                        return "Analysis";
                    else if (element instanceof Inputs)
                        return "Inputs";
                    else if (element instanceof Folder && ((Folder)element).getParentFolder() == null)
                        return "Charts";
                    else if (element instanceof InputFile)
                        return ((InputFile) element).getName();
                    else if (element instanceof AnalysisItem)
                        return ((AnalysisItem) element).getName();
                    else
                        return element.toString();
                }

                @Override
                public Image getImage(Object element) {
                    if (element instanceof Analysis)
                        return ScavePlugin.getCachedImage(ScaveImages.IMG_OBJ16_FOLDER);
                    else if (element instanceof Inputs)
                        return ScavePlugin.getCachedImage(ScaveImages.IMG_OBJ16_FOLDER);
                    else if (element instanceof Folder)
                        return ScavePlugin.getCachedImage(ScaveImages.IMG_OBJ16_FOLDER);
                    else if (element instanceof InputFile)
                        return ScavePlugin.getCachedImage(ScaveImages.IMG_OBJ16_INPUTFILE);
                    else if (element instanceof Chart)
                        return ScavePlugin.getCachedImage(ScaveImages.IMG_OBJ16_CHART);
                    else if (element instanceof Folder)
                        return ScavePlugin.getCachedImage(ScaveImages.IMG_OBJ16_FOLDER);
                    else
                        return null;
                }
            });
            contentOutlineViewer.setInput(getAnalysis());
            contentOutlineViewer.expandToLevel(3);

            // Make sure our popups work.
            UIUtils.createContextMenuFor(contentOutlineViewer.getControl(), true, new IMenuListener() {
                @Override
                public void menuAboutToShow(IMenuManager menuManager) {
                    actions.populateContextMenu(menuManager, false);
                }
            });
        }

        public void makeContributions(IMenuManager menuManager, IToolBarManager toolBarManager,
                IStatusLineManager statusLineManager) {
            super.makeContributions(menuManager, toolBarManager, statusLineManager);
            contentOutlineStatusLineManager = statusLineManager;

        }

        public void refresh() {
            getTreeViewer().refresh();
        }
    }

    public IContentOutlinePage getContentOutlinePage() {
        if (contentOutlinePage == null) {
            contentOutlinePage = new ScaveEditorContentOutlinePage();
            contentOutlinePage.addSelectionChangedListener(selectionChangedListener);
            contentOutlinePage.addSelectionChangedListener(new ISelectionChangedListener() {
                @Override
                public void selectionChanged(SelectionChangedEvent event) {
                    contentOutlineSelectionChanged(event.getSelection());
                }
            });
        }

        return contentOutlinePage;
    }

    protected void contentOutlineSelectionChanged(ISelection selection) {
        if (selection instanceof IStructuredSelection) {
            Object object = ((IStructuredSelection) selection).getFirstElement();
            // Debug.println("Selected: "+object);
            if (object != null)
                gotoObject(object);
        }
    }

    /**
     * Adds the given workspace file to Inputs.
     */
    public void addWorkspaceFileToInputs(IFile resource) {
        String resourcePath = resource.getFullPath().toPortableString();

        // add resourcePath to Inputs if not already there
        List<InputFile> inputs = getAnalysis().getInputs().getInputs();
        boolean found = false;
        for (Object inputFileObj : inputs) {
            InputFile inputFile = (InputFile) inputFileObj;
            if (inputFile.getName().equals(resourcePath))
                found = true;
        }

        if (!found) {
            InputFile inputFile = new InputFile(resourcePath);
            inputsPage.getCommandStack().execute(new AddInputFileCommand(analysis, inputFile));
        }
    }

    /**
     * Utility function: finds an IFile for an existing file given with OS path.
     * Returns null if the file was not found.
     */
    public static IFile findFileInWorkspace(String fileName) {
        IFile[] iFiles = ResourcesPlugin.getWorkspace().getRoot().findFilesForLocation(new Path(fileName));
        IFile iFile = null;
        for (IFile f : iFiles) {
            if (f.exists()) {
                iFile = f;
                break;
            }
        }
        return iFile;
    }

    /**
     * Utility function to access the active editor in the workbench.
     */
    public static ScaveEditor getActiveScaveEditor() {
        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench.getActiveWorkbenchWindow() != null) {
            IWorkbenchPage page = workbench.getActiveWorkbenchWindow().getActivePage();
            if (page != null) {
                IEditorPart part = page.getActiveEditor();
                if (part instanceof ScaveEditor)
                    return (ScaveEditor) part;
            }
        }
        return null;
    }

    public static CommandStack getActiveScaveCommandStack() {
        ScaveEditor activeEditor = getActiveScaveEditor();
        return activeEditor == null ? null : activeEditor.getActiveCommandStack();
    }

    public ISelectionChangedListener getSelectionChangedListener() {
        return selectionChangedListener;
    }

    @Override
    public void modelChanged(ModelChangeEvent event) {
        if (Debug.debug) {
            try {
                analysis.checkIdUniqueness();
            }
            catch (Exception ex) {
                ScavePlugin.logError(ex);
                MessageDialog.openError(Display.getCurrent().getActiveShell(), "Error", ex.getMessage());
            }
        }

        firePropertyChange(ISaveablePart2.PROP_DIRTY);

        //TODO temp chart name changes currently do not propagate to the tabitem text, as we do not receive model change notification about their change!

        // close pages whose content was deleted, except temporary charts
        for (AnalysisItem item : closablePages.keySet().toArray(new AnalysisItem[0])) { // toArray() is for preventing ConcurrentModificationException
            if (!analysis.contains(item) && !(item instanceof Chart && ((Chart)item).isTemporary()))
                removePage(closablePages.get(item));
        }

        // update contents of pages
        int pageCount = getPageCount();
        for (int pageIndex = 0; pageIndex < pageCount; ++pageIndex) {
            FormEditorPage editorPage = getEditorPage(pageIndex);
            editorPage.modelChanged(event);
        }

        // strictly speaking, some actions (undo, redo) should be updated when the
        // command stack changes, but there are more of those.
        actions.updateActions();

        if (contentOutlinePage != null)
            contentOutlinePage.refresh();

        for (Iterator<PropertySheetPage> i = propertySheetPages.iterator(); i.hasNext();) {
            PropertySheetPage propertySheetPage = i.next();
            if (propertySheetPage.getControl() == null || propertySheetPage.getControl().isDisposed())
                i.remove();
            else
                propertySheetPage.refresh();
        }

    }

    @Override
    protected void pageChange(int newPageIndex) {
        super.pageChange(newPageIndex);

        IEditorPart editor = getEditor(newPageIndex);
        if (editor != null) {
            if (editor instanceof ChartScriptEditor)
                ((ChartScriptEditor) editor).pageActivated();
        }
        else {
            Control page = getControl(newPageIndex);

            if (page instanceof FormEditorPage)
                ((FormEditorPage) page).pageActivated();
        }

        actions.updateActions();
    }

    public String getPageId(FormEditorPage page) {
        if (page.equals(inputsPage))
            return "Inputs";
        else if (page.equals(browseDataPage))
            return "BrowseData";
        else if (page.equals(chartsPage))
            return "Charts";
        else if (page instanceof ChartPage)
            return Integer.toString(((ChartPage)page).getChart().getId());
        else
            return null;
    }

    public FormEditorPage getEditorPageByPageId(String pageId) {
        for (int i = 0; i < getPageCount(); i++) {
            FormEditorPage page = getEditorPage(i);
            if (page != null && getPageId(page).equals(pageId))
                return page;
        }
        return null;
    }

    /*
     * Per input persistent state.
     */
    public IFile getInputFile() {
        IEditorInput input = getEditorInput();
        if (input instanceof IFileEditorInput)
            return ((IFileEditorInput) input).getFile();
        else
            return null;
    }

    private IMemento getMementoFor(FormEditorPage page) {
        IFile file = getInputFile();
        if (file != null) {
            try {
                ScaveEditorMemento memento = new ScaveEditorMemento(file);
                String editorPageId = getPageId(page);
                for (IMemento pageMemento : memento.getChildren(PAGE)) {
                    String pageId = pageMemento.getString(PAGE_ID);
                    if (pageId != null && pageId.equals(editorPageId))
                        return pageMemento;
                }
            } catch (CoreException e) {
            }
        }
        return null;
    }

    private void saveState(IMemento memento) {
        memento.putInteger(ACTIVE_PAGE, getActivePage());
        memento.putString(TEMPLATE_TIMESTAMPS, getChartTemplateRegistry().storeTimestamps());

        for (int pageIndex = 0; pageIndex < getPageCount(); pageIndex++) {
            FormEditorPage page = getEditorPage(pageIndex);
            if (page != null) {
                IMemento pageMemento = memento.createChild(PAGE);
                pageMemento.putString(PAGE_ID, getPageId(page));
                page.saveState(pageMemento);
            }
        }
    }

    private void restoreState(IMemento memento) {
        for (IMemento pageMemento : memento.getChildren(PAGE)) {
            String pageId = pageMemento.getString(PAGE_ID);
            if (pageId != null) {
                FormEditorPage page = getEditorPageByPageId(pageId);
                if (page != null)
                    page.restoreState(pageMemento);
                else if (pageId.matches("\\d+")) {
                    // pageId is the id of the item to be opened
                    int itemId = Integer.parseInt(pageId);
                    AnalysisItem item = analysis.getRootFolder().findRecursivelyById(itemId);
                    if (item != null)
                        createClosablePage(item); // note: this includes restoreState()
                }
            }
        }

        Integer activePage = memento.getInteger(ACTIVE_PAGE);
        if (activePage != null && activePage >= 0 && activePage < getPageCount())
            setActivePage(activePage);

        String timestamps = memento.getString(TEMPLATE_TIMESTAMPS);
        if (!StringUtils.isBlank(timestamps))
            getChartTemplateRegistry().restoreTimestamps(timestamps);
    }

    public void saveState() {
        try {
            IFile file = getInputFile();
            if (file != null) {
                ScaveEditorMemento memento = new ScaveEditorMemento();
                saveState(memento);
                memento.save(file);
            }
        } catch (Exception e) {
            ScavePlugin.logError(e);
        }
    }

    private void restoreState() {
        try {
            IFile file = getInputFile();
            if (file != null) {
                ScaveEditorMemento memento = new ScaveEditorMemento(file);
                restoreState(memento);
            }
        } catch (CoreException e) {
            ScavePlugin.log(e.getStatus());
        } catch (Exception e) {
            ScavePlugin.logError(e);
        }
    }

    /*
     * Navigation
     */
    @Override
    public INavigationLocation createEmptyNavigationLocation() {
        return new ScaveNavigationLocation(this, true);
    }

    @Override
    public INavigationLocation createNavigationLocation() {
        return new ScaveNavigationLocation(this, false);
    }

    public void markNavigationLocation() {
        getSite().getPage().getNavigationHistory().markLocation(this);
    }

    public void pageChangedByUser(int newPageIndex) {
        Control page = getControl(newPageIndex);
        if (page instanceof FormEditorPage) {
            markNavigationLocation();
        }
    }

    public final Chart findOpenChartById(int chartId) {
        int count = getPageCount();
        for (int i = 0; i < count; i++) {
            IEditorPart editor = getEditor(i);
            if (editor != null && editor.getEditorInput() instanceof ChartScriptEditorInput) {
                Chart chart = ((ChartScriptEditorInput)editor.getEditorInput()).getChart();
                if (chart.getId() == chartId)
                    return chart;
            }
        }
        return null;
    }

    /*
     * IGotoMarker
     */
    @Override
    public void gotoMarker(IMarker marker) {
        try {
            String sourceId = marker.getAttribute(IMarker.SOURCE_ID).toString();
            boolean sourceIdIsInteger = sourceId != null && sourceId.matches("[\\d]+");

            if (marker.getType().equals(IMarker.PROBLEM) && sourceIdIsInteger) {
                int chartId = Integer.parseInt(sourceId);

                AnalysisItem item = analysis.getRootFolder().findRecursivelyById(chartId);
                Chart chart = item instanceof Chart ? (Chart)item : null;

                if (chart == null)
                    chart = findOpenChartById(chartId); // it may be a temporary chart

                if (chart == null)
                    return;

                IEditorPart[] parts = findEditors(new ChartScriptEditorInput(chart));

                for (IEditorPart part : parts) {
                    ChartScriptEditor scriptEditor = (ChartScriptEditor) part;
                    setActiveEditor(scriptEditor);
                    scriptEditor.gotoMarker(marker);
                    break;
                }
            } else {
                // TODO
                // List<?> targetObjects = markerHelper.getTargetObjects(editingDomain, marker);
                // if (!targetObjects.isEmpty())
                // setSelectionToViewer(targetObjects);
            }
        } catch (CoreException exception) {
            ScavePlugin.logError(exception);
        }
    }

    /**
     * This sets the selection into whichever viewer is active.
     */
    public void setSelectionToViewer(Collection<?> collection) {
        handleSelectionChange(new StructuredSelection(collection.toArray()));
    }

    /**
     * Notify listeners on {@link org.eclipse.jface.viewers.ISelectionProvider}
     * about a selection change.
     */
    protected void fireSelectionChangedEvent(ISelection selection) {
        Debug.time("Notifying selection change listeners", 10, () -> {
            for (ISelectionChangedListener listener : selectionChangedListeners)
                listener.selectionChanged(new SelectionChangedEvent(this, selection));
        });
    }

    /**
     * This is the method used by the framework to install your own controls.
     */
    public void createPages() {

        loadAnalysis();

        if (analysis != null) {
            doCreatePages();
            showPythonExecutionWarningDialogIfNeeded();
        }
        else {
            addFixedPage(new FormEditorPage(getContainer(), SWT.NONE, this) {
                {
                    // set up UI
                    setPageTitle("Error");
                    setFormTitle("Error");

                    getContent().setLayout(new GridLayout());

                    Label label = new Label(getContent(), SWT.WRAP);
                    label.setText("Analysis could not be opened.");
                    label.setLayoutData(new GridData(SWT.BEGINNING, SWT.BEGINNING, false, false));
                }
            });
        }
    }

    /**
     * This is how the framework determines which interfaces we implement.
     */
    @SuppressWarnings("unchecked")
    public <T> T getAdapter(Class<T> key) {
        if (key.equals(IContentOutlinePage.class))
            return showOutlineView() ? (T) getContentOutlinePage() : null;
        else if (key.equals(IPropertySheetPage.class))
            return (T) getPropertySheetPage();
        else if (key.equals(IGotoMarker.class))
            return (T) this;
        else
            return super.getAdapter(key);
    }

    /**
     * This is for implementing {@link IEditorPart} and simply tests the command
     * stack. Since some refactoring operations call this from a background
     * thread, the actual implementation is syncExec'd here by the Display.
     */
    @Override
    public boolean isDirty() {
        boolean[] result = new boolean[1];
        Display.getDefault().syncExec(() -> {
            result[0] = isDirtyImpl();
        });
        return result[0];
    }

    /**
     * Provides the implementation for `isDirty`. This should only be
     * called from the UI thread, as it calls into some widgets.
     */
    protected boolean isDirtyImpl() {
        for (int i = 0; i < getPageCount(); ++i) {
            FormEditorPage editorPage = getEditorPage(i);
            if (editorPage instanceof InputsPage) {
                InputsPage inputsPage = (InputsPage)editorPage;

                CommandStack commandStack = inputsPage.getCommandStack();
                if (commandStack.isSaveNeeded())
                    return true;
            }
            else if (editorPage instanceof ChartsPage) {
                ChartsPage chartsPage = (ChartsPage)editorPage;

                CommandStack commandStack = chartsPage.getCommandStack();
                if (commandStack.isSaveNeeded())
                    return true;
            }
            else if (editorPage instanceof ChartPage) {
                ChartPage chartPage = (ChartPage)editorPage;

                ChartScriptEditor chartScriptEditor = chartPage.getChartScriptEditor();
                Chart chart = chartScriptEditor.getChart();

                // We must report dirty if there is any temporary chart to force Eclipse
                // to call promptToSaveOnClose(), bringing up the save dialog.
                if (chart.isTemporary())
                    return true;

                if (!chart.isTemporary()) {
                    if (chartScriptEditor.isDirty())
                        return true;

                    CommandStack commandStack = chartScriptEditor.getCommandStack();
                    if (commandStack.isSaveNeeded())
                        return true;
                }
            }
        }

        return false;
    }

    /**
     * This is for implementing {@link IEditorPart} and simply saves the model file.
     */
    public void doSave(IProgressMonitor progressMonitor) {
        IFileEditorInput modelFile = (IFileEditorInput) getEditorInput();
        IFile f = modelFile.getFile();

        try {
            AnalysisSaver.saveAnalysis(analysis, f);

            // Refresh the necessary state.

            for (int i = 0; i < getPageCount(); ++i) {
                FormEditorPage editorPage = getEditorPage(i);
                if (editorPage instanceof InputsPage) {
                    InputsPage inputsPage = (InputsPage)editorPage;

                    CommandStack commandStack = inputsPage.getCommandStack();
                    commandStack.saved();
                }
                else if (editorPage instanceof ChartsPage) {
                    ChartsPage chartsPage = (ChartsPage)editorPage;

                    CommandStack commandStack = chartsPage.getCommandStack();
                    commandStack.saved();
                }
                else if (editorPage instanceof ChartPage) {
                    ChartPage chartPage = (ChartPage)editorPage;
                    if (!chartPage.getChart().isTemporary()) {
                        ChartScriptEditor chartScriptEditor = chartPage.getChartScriptEditor();
                        chartScriptEditor.saved();
                    }
                }
            }

            firePropertyChange(IEditorPart.PROP_DIRTY);
        } catch (CoreException e) {
            MessageDialog.openError(Display.getCurrent().getActiveShell(), "Cannot save .anf file", e.getMessage());
        }
    }

    /**
     * This always returns true because it is not currently supported.
     */
    public boolean isSaveAsAllowed() {
        return true;
    }

    /**
     * "Save As" also changes the editor's input.
     */
    public void doSaveAs() {
        SaveAsDialog saveAsDialog = new SaveAsDialog(getSite().getShell());
        saveAsDialog.open();
        IPath path = saveAsDialog.getResult();
        if (path != null) {
            IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(path);
            if (file != null)
                doSaveAs(new FileEditorInput(file));
        }
    }

    /**
     * Perform "Save As"
     */
    protected void doSaveAs(IEditorInput editorInput) {
        setInputWithNotify(editorInput);
        setPartName(editorInput.getName());
        IStatusLineManager statusLineManager = getEditorSite().getActionBars().getStatusLineManager();
        IProgressMonitor progressMonitor = statusLineManager != null ? statusLineManager.getProgressMonitor() : new NullProgressMonitor();
        IFile fileInput = ((FileEditorInput)editorInput).getFile();
        chartTemplateRegistry.setProject(fileInput.getProject());
        doSave(progressMonitor);
    }

    /**
     * This implements {@link org.eclipse.jface.viewers.ISelectionProvider}.
     */
    public void addSelectionChangedListener(ISelectionChangedListener listener) {
        selectionChangedListeners.add(listener);
    }

    /**
     * This implements {@link org.eclipse.jface.viewers.ISelectionProvider}.
     */
    public void removeSelectionChangedListener(ISelectionChangedListener listener) {
        selectionChangedListeners.remove(listener);
    }

    /**
     * This implements {@link org.eclipse.jface.viewers.ISelectionProvider} to
     * return this editor's overall selection.
     */
    public ISelection getSelection() {
        return editorSelection;
    }

    /**
     * This implements {@link org.eclipse.jface.viewers.ISelectionProvider} to set
     * this editor's overall selection. Calling this will result in notifying the
     * listeners.
     */
    public void setSelection(ISelection selection) {
        handleSelectionChange(selection);
    }

    /**
     * Utility method to update "Selected X objects" text on the status bar.
     */
    protected void updateStatusLineManager(IStatusLineManager statusLineManager, ISelection selection) {
        if (statusLineManager != null) {
            if (selection instanceof IDListSelection) {
                IDListSelection idlistSelection = (IDListSelection) selection;
                int scalars = idlistSelection.getScalarsCount();
                int vectors = idlistSelection.getVectorsCount();
                int statistics = idlistSelection.getStatisticsCount();
                int histograms = idlistSelection.getHistogramsCount();
                if (scalars + vectors + statistics + histograms == 0)
                    statusLineManager.setMessage("No item selected");
                else {
                    List<String> strings = new ArrayList<String>(3);
                    if (scalars > 0)
                        strings.add(StringUtils.formatCounted(scalars, "scalar"));
                    if (vectors > 0)
                        strings.add(StringUtils.formatCounted(vectors, "vector"));
                    if (statistics > 0)
                        strings.add(StringUtils.formatCounted(statistics, "statistics"));
                    if (histograms > 0)
                        strings.add(StringUtils.formatCounted(histograms, "histogram"));
                    String message = "Selected " + StringUtils.join(strings, ", ", " and ");
                    statusLineManager.setMessage(message);
                }
            } else if (selection instanceof IStructuredSelection) {
                Collection<?> collection = ((IStructuredSelection) selection).toList();
                if (collection.size() == 0) {
                    statusLineManager.setMessage("No item selected");
                } else if (collection.size() == 1) {
                    Object object = collection.iterator().next();
                    String text = (object instanceof AnalysisItem) ?
                            object.getClass().getSimpleName() + " '" + ((AnalysisItem)object).getName() + "'" :
                            object.toString();
                    statusLineManager.setMessage("Selected: " + text);
                } else {
                    statusLineManager.setMessage("" + collection.size() + " items selected");
                }
            } else {
                statusLineManager.setMessage("");
            }
        }
    }

    public String makeNameForNewChart(ChartTemplate template) {
        return template.getName();
    }

    public ScaveEditorActions getActions() {
        return actions;
    }

    protected boolean showOutlineView() {
        return true;
    }

    public PythonProcessPool getPythonProcessPool() {
        return processPool;
    }

    public ChartTemplateRegistry getChartTemplateRegistry() {
        return chartTemplateRegistry;
    }

    protected int openChartScriptEditor(Chart chart) throws PartInitException {
        ChartScriptEditor editor = new ChartScriptEditor(this, chart);
        ChartScriptEditorInput input = new ChartScriptEditorInput(chart);

        int pageIndex = addClosablePage(editor, input, editor.getChartDisplayName());
        FormEditorPage page = getEditorPage(pageIndex);
        IMemento memento = getMementoFor(page);
        if (memento != null)
            page.restoreState(memento);

        // NOTE: Open temporary charts should make the analysis dirty.
        firePropertyChange(IEditorPart.PROP_DIRTY);

        return pageIndex;
    }

    public CommandStack getActiveCommandStack() {
        FormEditorPage activeEditorPage = getActiveEditorPage();
        if (activeEditorPage == inputsPage)
            return inputsPage.getCommandStack();
        else if (activeEditorPage == chartsPage)
            return chartsPage.getCommandStack();
        else if (activeEditorPage instanceof ChartPage)
            return ((ChartPage)activeEditorPage).getChartScriptEditor().getCommandStack();
        return null;
    }

    public void reloadResultFiles() {
        tracker.reloadResultFiles();
    }

    public void refreshResultFiles() {
        tracker.refreshResultFiles();
    }

    @Override
    public int promptToSaveOnClose() {
        List<ChartPage> temporaryChartPages = new ArrayList<ChartPage>();
        for (int i = 0; i < getPageCount(); ++i) {
            FormEditorPage editorPage = getEditorPage(i);
            if (editorPage instanceof ChartPage) {
                ChartPage chartPage = (ChartPage)editorPage;
                if (chartPage.getChart().isTemporary())
                    temporaryChartPages.add(chartPage);
            }
        }

        if (temporaryChartPages.isEmpty())
            return DEFAULT;

        SaveOnCloseDialog dialog = new SaveOnCloseDialog(Display.getCurrent().getActiveShell(), getInputFile().getName(), temporaryChartPages);
        dialog.open();

        if (dialog.getReturnCode() == IDialogConstants.CANCEL_ID)
            return CANCEL;
        if (dialog.getReturnCode() == IDialogConstants.NO_ID)
            return NO;

        List<Object> selectedObjects = Arrays.asList(dialog.getResult());

        for (Object obj : selectedObjects) {
            ChartPage chartPage = (ChartPage)obj;
            String name = StringUtils.defaultString(chartPage.getChartScriptEditor().getSuggestedChartName(), chartPage.getChart().getName());
            ScaveModelUtil.saveChart(chartsPage.getCommandStack(), chartPage.getChart(), name, getCurrentFolder());
        }

        return YES;
    }

}
