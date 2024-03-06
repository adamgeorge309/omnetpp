/*--------------------------------------------------------------*
  Copyright (C) 2006-2015 OpenSim Ltd.

  This file is distributed WITHOUT ANY WARRANTY. See the file
  'License' for details on this and other legal matters.
*--------------------------------------------------------------*/

package org.omnetpp.cdt.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.ArrayUtils;
import org.eclipse.cdt.core.cdtvariables.CdtVariableException;
import org.eclipse.cdt.core.settings.model.ICConfigurationDescription;
import org.eclipse.cdt.core.settings.model.ICProjectDescription;
import org.eclipse.cdt.core.settings.model.ICSettingEntry;
import org.eclipse.cdt.core.settings.model.ICSourceEntry;
import org.eclipse.cdt.core.settings.model.util.CDataUtil;
import org.eclipse.cdt.internal.core.cdtvariables.CdtVariableManager;
import org.eclipse.cdt.managedbuilder.core.ManagedBuildManager;
import org.eclipse.cdt.ui.newui.CDTPropertyManager;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.AssertionFailedException;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.MessageDialogWithToggle;
import org.eclipse.jface.preference.IPreferencePageContainer;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Item;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.ui.dialogs.PropertyPage;
import org.eclipse.ui.internal.dialogs.PropertyDialog;
import org.eclipse.ui.model.WorkbenchContentProvider;
import org.omnetpp.cdt.Activator;
import org.omnetpp.cdt.CDTUtils;
import org.omnetpp.cdt.build.BuildSpecification;
import org.omnetpp.cdt.build.MakefileTools;
import org.omnetpp.cdt.build.Makemake;
import org.omnetpp.cdt.build.MakemakeOptions;
import org.omnetpp.cdt.build.MakemakeOptions.Type;
import org.omnetpp.cdt.build.MetaMakemake;
import org.omnetpp.common.color.ColorFactory;
import org.omnetpp.common.project.ProjectUtils;
import org.omnetpp.common.ui.HoverSupport;
import org.omnetpp.common.ui.HtmlHoverInfo;
import org.omnetpp.common.ui.IHoverInfoProvider;
import org.omnetpp.common.util.StringUtils;

/**
 * This property page is shown for an OMNeT++ CDT Project, and lets the user
 * manage the C++ configuration.
 *
 * @author Andras
 */
@SuppressWarnings("restriction")
//TODO progress bar while source files are scanned when dialog opens
public class ProjectMakemakePropertyPage extends PropertyPage {
    private static final String SOURCE_FOLDER_IMG = "icons/full/obj16/folder_srcfolder.gif";
    private static final String SOURCE_SUBFOLDER_IMG = "icons/full/obj16/folder_srcsubfolder.gif";
    private static final String NONSRC_FOLDER_IMG = "icons/full/obj16/folder_nonsrc.gif";

    protected static final String OVR_MAKEMAKE_IMG = "icons/full/ovr16/ovr_makemake.png";
    protected static final String OVR_CUSTOMMAKE_IMG = "icons/full/ovr16/ovr_custommake.png";
    protected static final String OVR_WARNING_IMG = "icons/full/ovr16/warning.gif";
    protected static final String OVR_BUILDROOT_IMG = "icons/full/ovr16/buildroot.png";

    // state
    protected BuildSpecification buildSpec;

    protected static boolean suppressExcludeProjectRootQuestion = false; // per-session variable

    // controls
    protected Link errorMessageLabel;
    protected TreeViewer treeViewer;
    protected Button makemakeButton;
    protected Button customMakeButton;
    protected Button noMakeButton;
    protected Button optionsButton;
    protected Button sourceLocationButton;
    protected Button excludeButton;
    protected Button includeButton;
    protected Button exportButton;

    protected static class FolderInfo {
        String label;
        Image image;
        String tooltipBody;
    }
    protected Map<IContainer, FolderInfo> folderInfoCache = new HashMap<IContainer, FolderInfo>();

    /**
     * Constructor.
     */
    public ProjectMakemakePropertyPage() {
        super();
    }

    /**
     * @see PreferencePage#createContents(Composite)
     */
    protected Control createContents(Composite parent) {
        final Composite composite = new Composite(parent, SWT.NONE);
        composite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        composite.setLayout(new GridLayout(2,false));
        ((GridLayout)composite.getLayout()).marginWidth = 0;
        ((GridLayout)composite.getLayout()).marginHeight = 0;

        String text =
            "On this page you can configure source folders and makefile generation; " +
            "these two are independent of each other. All changes apply to all configurations.";
        // Note: do NOT add reference/link to the "Path and Symbols" page! It confuses users.
        final Label bannerTextLabel = createLabel(composite, text, 2);
        bannerTextLabel.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false, 2, 1));
        ((GridData)bannerTextLabel.getLayoutData()).widthHint = 300;

        errorMessageLabel = new Link(composite, SWT.WRAP);
        errorMessageLabel.setForeground(ColorFactory.RED2);
        errorMessageLabel.setLayoutData(new GridData(SWT.FILL, SWT.FILL, false, false, 2, 1));
        ((GridData)errorMessageLabel.getLayoutData()).widthHint = 300;

        treeViewer = new TreeViewer(composite, SWT.BORDER);
        treeViewer.getTree().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        ((GridData)treeViewer.getTree().getLayoutData()).widthHint = 300;
        ((GridData)treeViewer.getTree().getLayoutData()).heightHint = 280;

        Composite buttons = new Composite(composite, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.FILL, false, true));
        buttons.setLayout(new GridLayout(1,false));

        Composite buildGroup = createGroup(buttons, "Build", 1);
        makemakeButton = createButton(buildGroup, SWT.RADIO, "&Makemake", "Automatic makefile generation enabled for the selected folder");
        optionsButton = createButton(buildGroup, SWT.PUSH, "&Options...", "Edit makefile generation options");
        ((GridData)(optionsButton.getLayoutData())).horizontalIndent = 20;
        customMakeButton = createButton(buildGroup, SWT.RADIO, "&Custom Makefile", "Selected folder contains a custom makefile");
        noMakeButton = createButton(buildGroup, SWT.RADIO, "&No Makefile", "Selected folder contains no makefile");
        createLabel(buttons, "", 1);

        Composite sourceGroup = createGroup(buttons, "Source", 1);
        sourceLocationButton = createButton(sourceGroup, SWT.RADIO, "&Source Location", "Selected folder is a source location");
        excludeButton = createButton(sourceGroup, SWT.RADIO, "&Excluded", "Selected folder is excluded from sources");
        includeButton = createButton(sourceGroup, SWT.RADIO, "&Included", "Selected folder is included in sources");
        createLabel(buttons, "", 1);

        exportButton = createButton(buttons, SWT.PUSH, "E&xport", "Export settings to \"makemakefiles\" file");

//        pathsAndSymbolsLink.addSelectionListener(new SelectionListener(){
//            public void widgetSelected(SelectionEvent e) {
//                gotoPathsAndSymbolsPage();
//            }
//            public void widgetDefaultSelected(SelectionEvent e) {
//                gotoPathsAndSymbolsPage();
//            }
//        });

        errorMessageLabel.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                ProjectConfigurationUtils.fixProblem(getProject(), buildSpec, e.text);
                updatePageState();
            }
        });

        makemakeButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                if (makemakeButton.getSelection()) // filter out deselection events
                    setFolderMakeType(getTreeSelection(), BuildSpecification.MAKEMAKE);
            }
        });
        customMakeButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                if (customMakeButton.getSelection()) // filter out deselection events
                    setFolderMakeType(getTreeSelection(), BuildSpecification.CUSTOM);
            }
        });
        noMakeButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                if (noMakeButton.getSelection()) // filter out deselection events
                    setFolderMakeType(getTreeSelection(), BuildSpecification.NONE);
            }
        });
        optionsButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                editFolderOptions(getTreeSelection());
            }
        });
        sourceLocationButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                if (sourceLocationButton.getSelection()) // filter out deselection events
                    markAsSourceLocation(getTreeSelection());
            }
        });
        excludeButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                if (excludeButton.getSelection()) // filter out deselection events
                    excludeFolder(getTreeSelection());
            }
        });
        includeButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                if (includeButton.getSelection()) // filter out deselection events
                    includeFolder(getTreeSelection());
            }
        });
        exportButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                exportMakemakefiles();
            }
        });

        // register this page in the CDT property manager (note: if we skip this,
        // "Mark as source folder" won't work on the page until we visit a CDT property page)
        CDTPropertyManager.getProjectDescription(this, getProject());

        // the "Paths and Symbols" CDT page tends to display out-of-date info at the first
        // invocation; the following, seemingly no-op code apparently cures that...
        if (CDTPropertyManager.getProjectDescription(getProject()) != null)
            for (ICConfigurationDescription cfgDes : CDTPropertyManager.getProjectDescription(getProject()).getConfigurations())
                ManagedBuildManager.getConfigurationForDescription(cfgDes);  // the magic!

        // configure the tree
        treeViewer.setContentProvider(new WorkbenchContentProvider() {
            @Override
            public Object[] getChildren(Object element) {
                if (element instanceof IWorkspaceRoot)
                    return new Object[] {getProject()};
                List<Object> result = new ArrayList<Object>();
                for (Object object : super.getChildren(element))
                    if (object instanceof IContainer && MakefileTools.isGoodFolder((IContainer)object))
                        result.add(object);
                return result.toArray();
            }
        });

        treeViewer.setLabelProvider(new LabelProvider() {
            public Image getImage(Object element) {
                if (element instanceof IContainer)
                    return getFolderInfo((IContainer)element).image;
                return null;
            }

            public String getText(Object element) {
                if (element instanceof IContainer)
                    return getFolderInfo((IContainer)element).label;
                if (element instanceof IResource) // new files tend to appear in the tree as well...
                    return ((IResource)element).getName();
                return element.toString();
            }
        });

        treeViewer.getTree().addSelectionListener(new SelectionAdapter() {
            public void widgetDefaultSelected(SelectionEvent e) {
                editFolderOptions(getTreeSelection());
            }
        });
        treeViewer.getTree().addKeyListener(new KeyListener() {
            public void keyPressed(KeyEvent e) {
                if (e.keyCode == '\r' && e.stateMask == SWT.ALT)  // Alt+Enter
                    editFolderOptions(getTreeSelection());
            }
            public void keyReleased(KeyEvent e) {
            }
        });

        new HoverSupport().adapt(treeViewer.getTree(), new IHoverInfoProvider() {
            @Override
            public HtmlHoverInfo getHoverFor(Control control, int x, int y) {
                Item item = treeViewer.getTree().getItem(new Point(x,y));
                Object element = item==null ? null : item.getData();
                if (element instanceof IContainer)
                    return new HtmlHoverInfo(HoverSupport.addHTMLStyleSheet(getFolderInfo((IContainer)element).tooltipBody));
                return null;
            }
        });

        // make the error text label wrap properly; see https://bugs.eclipse.org/bugs/show_bug.cgi?id=9866
        composite.addControlListener(new ControlAdapter(){
            public void controlResized(ControlEvent e){
                GridLayout layout = (GridLayout)composite.getLayout();
                GridData data = (GridData)errorMessageLabel.getLayoutData();
                data.widthHint = composite.getClientArea().width - 2*layout.marginWidth;
                GridData data2 = (GridData)bannerTextLabel.getLayoutData();
                data2.widthHint = composite.getClientArea().width - 2*layout.marginWidth;
                composite.layout(true);
            }
        });

        loadBuildSpecFile();

        treeViewer.setInput(getProject().getParent());

        treeViewer.addSelectionChangedListener(new ISelectionChangedListener() {
            public void selectionChanged(SelectionChangedEvent event) {
                updatePageState();
            }
        });

        // open interesting tree nodes
        for (IContainer f : buildSpec.getMakeFolders()) {
            treeViewer.expandToLevel(f, 0);
        }
        ICProjectDescription projectDescription = CDTPropertyManager.getProjectDescription(getProject());
        ICConfigurationDescription configuration = projectDescription==null ? null : projectDescription.getActiveConfiguration();
        if (configuration != null) {
            ICSourceEntry[] sourceEntries = configuration.getSourceEntries();
            for (IContainer f : CDTUtils.getSourceLocations(getProject(), sourceEntries)) {
                treeViewer.expandToLevel(f, 0);
            }
        }

        updatePageState();
        return composite;
    }

    protected void setFolderMakeType(IContainer folder, int makeType) {
        buildSpec.setFolderMakeType(folder, makeType);
        if (makeType == BuildSpecification.MAKEMAKE) {
            ICProjectDescription projectDescription = CDTPropertyManager.getProjectDescription(folder.getProject());
            ICConfigurationDescription configuration = projectDescription.getActiveConfiguration();
            boolean isExcluded = CDTUtils.isExcluded(folder, configuration.getSourceEntries());
            if (isExcluded)
                buildSpec.getMakemakeOptions(folder).type = Type.NOLINK;
        }
        updatePageState();

        if (!folder.equals(getProject()) && makeType!=BuildSpecification.NONE)
            maybeOfferToExcludeProjectRoot(folder);
    }

    // currently unused (we don't want to encourage users to change the build root, so we don't add a [Mark As Root] button)
    protected void markAsRoot(IContainer folder) {
        try {
            IProject project = getProject();
            ICProjectDescription projectDescription = CDTPropertyManager.getProjectDescription(project);
            for (ICConfigurationDescription configuration : projectDescription.getConfigurations())
                configuration.getBuildSetting().setBuilderCWD(new Path("${workspace_loc:"+folder.getFullPath().toString()+"}"));
        }
        catch (Exception e) {
            errorDialog(e.getMessage(), e);
        }
        updatePageState();
    }

    protected void editFolderOptions(IContainer folder) {
        if (buildSpec.getFolderMakeType(folder) != BuildSpecification.MAKEMAKE)
            return;
        MakemakeOptionsDialog dialog = new MakemakeOptionsDialog(getShell(), folder, buildSpec);
        if (dialog.open() == Dialog.OK) {
            buildSpec.setMakemakeOptions(folder, dialog.getResult());
            updatePageState();
        }
    }

    protected void markAsSourceLocation(IContainer folder) {
        try {
            addToSourceEntries(folder);
        }
        catch (Exception e) {
            errorDialog(e.getMessage(), e);
        }
        updatePageState();

        maybeOfferToExcludeProjectRoot(folder);
    }

    protected void addToSourceEntries(IContainer folder) throws CoreException {
        IProject project = getProject();
        ICProjectDescription projectDescription = CDTPropertyManager.getProjectDescription(project);
        for (ICConfigurationDescription configuration : projectDescription.getConfigurations()) {
            ICSourceEntry[] entries = configuration.getSourceEntries();
            ICSourceEntry entry = (ICSourceEntry)CDataUtil.createEntry(ICSettingEntry.SOURCE_PATH, folder.getProjectRelativePath().toString(), folder.getProjectRelativePath().toString(), new IPath[0], ICSettingEntry.VALUE_WORKSPACE_PATH);
            entries = (ICSourceEntry[]) ArrayUtils.add(entries, entry);
            configuration.setSourceEntries(entries);
        }
    }

    protected void maybeOfferToExcludeProjectRoot(IContainer selectedFolder) {
        if (!suppressExcludeProjectRootQuestion && !selectedFolder.equals(getProject())) {
            IProject project = getProject();
            ICSourceEntry[] sourceEntries = CDTPropertyManager.getProjectDescription(project).getActiveConfiguration().getSourceEntries();
            boolean isRootExcluded = CDTUtils.isExcluded(project, sourceEntries);
            if (!isRootExcluded) {
                MessageDialogWithToggle dialog = MessageDialogWithToggle.openYesNoQuestion(this.getShell(),
                        "Recommendation: Exclude Root",
                        "If you have all source files in subdirectories (e.g under src/), " +
                        "you don't need the root folder to be a source folder.\n\n" +
                        "Do you want to exclude the root?",
                        "Do not ask this question again in this session", false, null, null);
                if (dialog.getReturnCode() == IDialogConstants.YES_ID) {
                    try {
                        if (sourceEntries.length==1)
                            addToSourceEntries(selectedFolder);  // otherwise we won't be able to exclude the root
                        // exclude project root
                        setExcluded(getProject(), true);
                        // put back current folder if it became excluded
                        sourceEntries = CDTPropertyManager.getProjectDescription(project).getActiveConfiguration().getSourceEntries();
                        if (CDTUtils.isExcluded(selectedFolder, sourceEntries))
                            setExcluded(getProject(), false);
                    }
                    catch (Exception e) {
                        errorDialog(e.getMessage(), e);
                    }
                    updatePageState();
                }
                if (dialog.getToggleState()==true)
                    suppressExcludeProjectRootQuestion = true;
            }
        }
    }

    //note: this method is unused -- the "Exclude" button seems to work just fine
    protected void removeSourceLocation(IContainer folder) {
        try {
            IProject project = getProject();
            ICProjectDescription projectDescription = CDTPropertyManager.getProjectDescription(project);
            for (ICConfigurationDescription configuration : projectDescription.getConfigurations()) {
                ICSourceEntry[] entries = configuration.getSourceEntries();
                ICSourceEntry entry = CDTUtils.getSourceEntryFor(folder, entries);
                entries = (ICSourceEntry[]) ArrayUtils.removeElement(entries, entry); // works for null too
                configuration.setSourceEntries(entries);
            }
        }
        catch (Exception e) {
            errorDialog(e.getMessage(), e);
        }
        updatePageState();
    }

    protected void excludeFolder(IContainer folder) {
        removeSourceLocation(folder);
        setExcluded(folder, true);
    }

    protected void includeFolder(IContainer folder) {
        removeSourceLocation(folder);
        setExcluded(folder, false);
    }

    protected void setExcluded(IContainer folder, boolean exclude) {
        try {
            IProject project = getProject();
            ICProjectDescription projectDescription = CDTPropertyManager.getProjectDescription(project);
            for (ICConfigurationDescription configuration : projectDescription.getConfigurations()) {
                ICSourceEntry[] newEntries = CDTUtils.setExcluded(folder, exclude, configuration.getSourceEntries());
                configuration.setSourceEntries(newEntries);
            }
        }
        catch (CoreException e) {
            errorDialog(e.getMessage(), e);
        }
        catch (Exception e) {
            errorDialog(e.getMessage(), e);
        }
        updatePageState();
    }

    protected void exportMakemakefiles() {
        try {
            final String MAKEMAKEFILE_NAME = "makemakefiles";

            // prelude
            String text =
                "#\n" +
                "# Usage:\n" +
                "#    make -f " + MAKEMAKEFILE_NAME + "\n" +
                "# or, for Microsoft Visual C++:\n" +
                "#    nmake -f " + MAKEMAKEFILE_NAME + " MMOPT=-n\n" +
                "#\n" +
                "\n" +
                "MAKEMAKE=opp_makemake $(MMOPT)\n" +
                "\n";

            IProject project = getProject();
            IProject[] referencedProjects = ProjectUtils.getAllReferencedProjects(project, false, false);
            for (IProject refProj : referencedProjects) {
                String variable = MetaMakemake.makeSymbolicProjectName(refProj);
                IPath path = MakefileTools.makeRelativePath(refProj.getLocation(), project.getLocation());
                text += variable + "=" + path + "\n";
            }
            if (referencedProjects.length>0)
                text += "\n";

            // generate rules
            text += "all:\n";
            for (IContainer folder : buildSpec.getMakemakeFolders()) {
                if (folder.exists()) {
                    ICProjectDescription projectDescription = CDTPropertyManager.getProjectDescription(project);
                    ICConfigurationDescription configuration = projectDescription.getActiveConfiguration();
                    MakemakeOptions translatedOptions = MetaMakemake.translateOptions(folder, buildSpec, configuration, null);
                    text += "\t";
                    if (!folder.equals(project))
                        text += "cd " + folder.getProjectRelativePath().toString() + " && ";
                    String escapedOptsStr = StringUtils.escapeBash(translatedOptions.toString());
                    text += "$(MAKEMAKE) " + escapedOptsStr + "\n";
                }
            }

            // save
            IFile makemakefile = project.getFile(MAKEMAKEFILE_NAME);
            boolean existed = makemakefile.exists();
            if (!makemakefile.exists() || MessageDialog.openConfirm(getShell(), "Export", "Overwrite existing " + makemakefile.getFullPath().toString() + "?")) {
                byte[] bytes = text.getBytes();
                MakefileTools.ensureFileContent(makemakefile, bytes, null);
                makemakefile.refreshLocal(0, null);
                if (!existed)
                    MessageDialog.openInformation(getShell(), "Export", makemakefile.getFullPath().toString() + " created.");
            }
        }
        catch (CoreException e) {
            Activator.logError(e);
            errorDialog(e.getMessage(), e);
        }
    }

    // currently unused (we don't want to encourage the user to mess with CDT settings directly)
    protected void gotoPathsAndSymbolsPage() {
        IPreferencePageContainer container = getContainer();
        if (container instanceof PropertyDialog)
            ((PropertyDialog)container).setCurrentPageId(MakemakeOptionsPanel.PROPERTYPAGE_PATH_AND_SYMBOLS);
    }

    protected Group createGroup(Composite composite, String text, int numColumns) {
        Group group = new Group(composite, SWT.NONE);
        group.setText(text);
        group.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
        group.setLayout(new GridLayout(numColumns,false));
        return group;
    }

    protected Label createLabel(Composite composite, String text, int hspan) {
        Label label = new Label(composite, SWT.WRAP);
        label.setText(text);
        label.setLayoutData(new GridData());
        ((GridData)label.getLayoutData()).horizontalSpan = hspan;
        return label;
    }

    protected Button createButton(Composite parent, int style, String text, String tooltip) {
        Button button = new Button(parent, style);
        button.setText(text);
        if (tooltip != null)
            button.setToolTipText(tooltip);
        button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return button;
    }

    protected Link createLink(Composite composite, String text) {
        Link link = new Link(composite, SWT.NONE);
        link.setText(text);
        link.setLayoutData(new GridData());
        return link;
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (visible == true)
            updatePageState();
    }

    protected void updatePageState() {
        // display warnings about CDT misconfiguration, etc
        String message = ProjectConfigurationUtils.getDiagnosticMessage(getProject(), buildSpec, true);
        setDiagnosticMessage(message);

        folderInfoCache.clear();
        treeViewer.refresh();

        IProject project = getProject();
        ICProjectDescription projectDescription = CDTPropertyManager.getProjectDescription(project);
        ICConfigurationDescription configuration = projectDescription==null ? null : projectDescription.getActiveConfiguration();

        IContainer folder = getTreeSelection();
        if (folder == null || configuration == null) {
            // there's no tree selection or no active CDT configuration: disable all buttons
            makemakeButton.setEnabled(false);
            customMakeButton.setEnabled(false);
            noMakeButton.setEnabled(false);
            makemakeButton.setSelection(false);
            customMakeButton.setSelection(false);
            noMakeButton.setSelection(false);

            optionsButton.setEnabled(false);
            sourceLocationButton.setEnabled(false);
            excludeButton.setEnabled(false);
            includeButton.setEnabled(false);
            exportButton.setEnabled(false);
        }
        else {
            // enable/disable buttons
            int makeType = buildSpec.getFolderMakeType(folder);
            makemakeButton.setEnabled(true);
            customMakeButton.setEnabled(true);
            noMakeButton.setEnabled(true);
            makemakeButton.setSelection(makeType==BuildSpecification.MAKEMAKE);
            customMakeButton.setSelection(makeType==BuildSpecification.CUSTOM);
            noMakeButton.setSelection(makeType==BuildSpecification.NONE);

            optionsButton.setEnabled(makeType==BuildSpecification.MAKEMAKE);

            ICSourceEntry[] sourceEntries = configuration.getSourceEntries();
            boolean isExcluded = CDTUtils.isExcluded(folder, sourceEntries);
            boolean isSourceLocation = CDTUtils.getSourceLocations(project, sourceEntries).contains(folder);
            boolean isUnderSourceLocation = CDTUtils.getSourceEntryThatCovers(folder, sourceEntries) != null;
            boolean isNestedSourceLocation = isSourceLocation && !(folder instanceof IProject) && CDTUtils.getSourceEntryThatCovers(folder.getParent(), sourceEntries) != null;

            sourceLocationButton.setSelection(isSourceLocation);
            excludeButton.setSelection(isExcluded);
            includeButton.setSelection(!isSourceLocation && !isExcluded);

            sourceLocationButton.setEnabled(!isSourceLocation || sourceLocationButton.getSelection());
            excludeButton.setEnabled(isNestedSourceLocation || (isUnderSourceLocation && !isExcluded) || excludeButton.getSelection());
            includeButton.setEnabled(isNestedSourceLocation || (isUnderSourceLocation && isExcluded) || includeButton.getSelection());
            exportButton.setEnabled(true);
        }
    }

    private void setDiagnosticMessage(String message) {
        errorMessageLabel.setText(message==null ? "" : message);
        ((GridData)errorMessageLabel.getLayoutData()).exclude = (message==null);
        errorMessageLabel.setVisible(message!=null);
        errorMessageLabel.getParent().layout(true);
    }

    protected IContainer getTreeSelection() {
        Object element = ((IStructuredSelection)treeViewer.getSelection()).getFirstElement();
        return element instanceof IContainer ? (IContainer)element : null;
    }

    protected boolean isDirty() {
        return false;
    }

    protected FolderInfo getFolderInfo(IContainer folder) {
        FolderInfo info = folderInfoCache.get(folder);
        if (info == null) {
            info = calculateFolderInfo(folder);
            folderInfoCache.put(folder, info);
        }
        return info;
    }

    protected FolderInfo calculateFolderInfo(IContainer folder) {
        FolderInfo info = new FolderInfo();

        // useful variables
        IProject project = getProject();
        ICProjectDescription projectDescription = CDTPropertyManager.getProjectDescription(project);
        ICConfigurationDescription configuration = projectDescription==null ? null : projectDescription.getActiveConfiguration();
        if (configuration == null) {
            // not a CDT project, or there's no active configuration...
            info.label = folder.getFullPath().toString();
            info.image = Activator.getCachedImage(NONSRC_FOLDER_IMG);
            info.tooltipBody = null;
            return info;
        }

        ICSourceEntry[] sourceEntries = configuration.getSourceEntries();
        boolean isExcluded = CDTUtils.isExcluded(folder, sourceEntries);
        boolean isSrcFolder = CDTUtils.getSourceEntryFor(folder, sourceEntries)!=null;
        boolean isSourceLocation = CDTUtils.getSourceLocations(project, sourceEntries).contains(folder);
        //boolean isUnderSourceLocation = CDTUtils.getSourceEntryThatCovers(folder, sourceEntries) != null;
        String buildLocation = configuration.getBuildSetting().getBuilderCWD().toString();
        IContainer buildFolder = resolveFolderLocation(buildLocation, project, configuration);
        boolean isBuildRoot = folder.equals(buildFolder);
        int makeType = buildSpec.getFolderMakeType(folder);

        // find which makefile covers this folder (ignoring makefile type and scope for now)
        IContainer parentMakefileFolder = null;
        boolean stopOnExcluded = makeType==BuildSpecification.NONE;
        for (IContainer f = folder.getParent(); !(f instanceof IWorkspaceRoot) && (buildSpec.getMakeFolders().contains(f) || !stopOnExcluded || !CDTUtils.isExcluded(f, sourceEntries)); f = f.getParent())
            if (buildSpec.getMakeFolders().contains(f)) {
                parentMakefileFolder = f; break;}
        int parentMakeFolderType = buildSpec.getFolderMakeType(parentMakefileFolder);
        MakemakeOptions parentMakeOptions = buildSpec.getMakemakeOptions(parentMakefileFolder);

        // check reachability
        boolean isReachable;
        if (makeType == BuildSpecification.NONE)
            isReachable = false; // no makefile here, so we don't care
        else if (isBuildRoot)
            isReachable = true;  // build root is reachable
        else if (parentMakefileFolder == null)
            isReachable = false;  // no parent makefile
        else if (parentMakeFolderType == BuildSpecification.CUSTOM)
            isReachable = true;  // we assume that the custom makefile is written well
        else if (parentMakeOptions.metaRecurse)
            isReachable = true;  // OK
        else {
            String subpath = MakefileTools.makeRelativePath(folder.getFullPath(), parentMakefileFolder.getFullPath()).toString();
            isReachable = parentMakeOptions.submakeDirs.contains(subpath);
        }

        //
        // CALCULATE LABEL
        //
        String label = folder.getName();
        if (makeType==BuildSpecification.MAKEMAKE) {
            MakemakeOptions options = buildSpec.getMakemakeOptions(folder);

            String target = options.target == null ? project.getName() : options.target;
            switch (options.type) {
                case NOLINK: target = null; break;
                case EXE: target += " (executable)"; break;
                case SHAREDLIB: target += " (dynamic lib)"; break;
                case STATICLIB: target += " (static lib)"; break;
            }

            label +=
                ": makemake (" +
                (isExcluded ? "no src" : options.isDeep ? "deep" : "shallow") + ", "+
                (options.metaRecurse ? "recurse" : "no-recurse") + ")" +
                (target==null ? "" : " --> " + target);
        }
        if (makeType==BuildSpecification.CUSTOM) {
            label += ": custom makefile";
        }
        info.label = label;

        //
        // CALCULATE HOVER TEXT
        //
        String folderTypeText = "";
        if (isSourceLocation)
            folderTypeText = "source location";
        else if (isExcluded)
            folderTypeText = "excluded from compile";
        else
            folderTypeText = "source folder";

        String what = folderTypeText; // then we're going to append stuff to this
        String comments = "";
        boolean hasWarning = false;

        if (isBuildRoot)
            what += "; build root folder";

        if (makeType==BuildSpecification.MAKEMAKE) {
            what += "; makefile generation enabled";

            List<String> submakeDirs = null;
            List<String> sourceDirs = null;
            try {
                MakemakeOptions translatedOptions = MetaMakemake.translateOptions(folder, buildSpec, configuration, null);
                submakeDirs = translatedOptions.submakeDirs;
                sourceDirs = new Makemake().getSourceDirs(folder, translatedOptions);
            }
            catch (CoreException e) {
                Activator.logError(e);
            }

            if (!isReachable) {
                comments = "<p>WARNING: This makefile never gets invoked";
                comments += (parentMakefileFolder!=null ? " by parent makefile" : " (not build root and has no parent makefile)");
                hasWarning = true;
            }
            if (parentMakefileFolder != null)
                comments += "<p>Parent makefile: " + parentMakefileFolder.getFullPath().toString();
            if (submakeDirs != null && sourceDirs != null) { // i.e. there was no error above
                comments += "<p>Invokes make in: " + StringUtils.defaultIfEmpty(StringUtils.join(submakeDirs, ", "), "-");
                if (submakeDirs.size() > 2)
                    comments += "<p>HINT: To control invocation order, add dependency rules between subdir targets to the Makefrag file (on the Custom page of the Makemake Options dialog)";
                comments += "<p>Compiles files in the following folders: " + StringUtils.defaultIfEmpty(StringUtils.join(sourceDirs, ", "), "-");
            }
            if (folder instanceof IProject && !isExcluded && buildSpec.getMakeFolders().size()>1)
                comments =
                    "<p>HINT: You may want to exclude this (project root) folder from compilation, " +
                    "and leave compilation to subdirectory makefiles.";
        }
        else if (makeType==BuildSpecification.CUSTOM) {
            what += "; custom makefile";

            //TODO "Should invoke make in:" / "Should compile the following folders"
            IFile makefile = folder.getFile(new Path("Makefile")); //XXX Makefile.vc?
            if (!makefile.exists()) {
                comments = "<p>WARNING: Custom makefile \"Makefile\" missing";
                hasWarning = true;
            }
            if (!isReachable) {
                comments = "<p>WARNING: This makefile never gets invoked";
                hasWarning = true;
            }
            if (parentMakefileFolder != null)
                comments += "<p>Parent makefile: " + parentMakefileFolder.getFullPath().toString();
            if (isExcluded)
                comments =
                    "<p>Note: The makefile is not supposed to compile any (potentially existing) source files " +
                    "from this folder, because the folder is excluded from build. However, it may " +
                    "invoke other makefiles, may create executables or libraries by invoking the linker, " +
                    "or may contain other kinds of targets.";
        }
        else if (makeType==BuildSpecification.NONE) {
            if (isExcluded)
                /*NOP*/;
            else if (parentMakefileFolder == null) {
                comments = "<p>WARNING: This is a source folder, but it is not covered by any makefile. Is this intentional?";
                hasWarning = true;
            }
            else if (parentMakeFolderType==BuildSpecification.CUSTOM)
                comments = "<p>This source folder is supposed to be covered by the custom makefile in " + parentMakefileFolder.getFullPath();
            else if (parentMakeFolderType==BuildSpecification.MAKEMAKE && !buildSpec.getMakemakeOptions(parentMakefileFolder).isDeep) {
                comments = "<p>WARNING: This is a source folder but it is not covered by any makefile, because the generated makefile in " + parentMakefileFolder.getFullPath() + " is not deep. Is this intentional?";
                hasWarning = true;
            }
            else if (parentMakeFolderType==BuildSpecification.MAKEMAKE)
                comments = "<p>This source folder is covered by the generated makefile in " + parentMakefileFolder.getFullPath();
            else
                Activator.logError(new AssertionFailedException("Tooltip logic error"));
        }

//        if (!isExcluded) {
//            //TODO "Includes files from: directly: ...   Indirectly: ..."
//            Set<IContainer> set = Activator.getDependencyCache().getFolderDependencies(project).get(folder);
//            if (set != null) {
//                List<String> folderNames = new ArrayList<String>();
//                for (IContainer f : set)
//                    folderNames.add(f.getProject().equals(project) ? f.getProjectRelativePath().toString() : f.getFullPath().toString());
//                comments += "<p>Includes files from: " + (folderNames.size()==0 ? "-" : StringUtils.join(folderNames, ", "));
//            }
//        }

        String result = "<b>" + folder.getFullPath() + "</b> (" + what + ")";
        if (!StringUtils.isEmpty(comments))
            result += comments;
        info.tooltipBody = result;

        //
        // CALCULATE IMAGE
        //
        String imagePath = isSrcFolder ? SOURCE_FOLDER_IMG : !isExcluded ? SOURCE_SUBFOLDER_IMG : NONSRC_FOLDER_IMG;
        String overlayImagePath = null;
        switch (makeType) {
            case BuildSpecification.MAKEMAKE: overlayImagePath = OVR_MAKEMAKE_IMG; break;
            case BuildSpecification.CUSTOM: overlayImagePath = OVR_CUSTOMMAKE_IMG; break;
            case BuildSpecification.NONE: overlayImagePath = null; break;
        }
        info.image = Activator.getCachedDecoratedImage(imagePath,
                new String[] {isBuildRoot ? OVR_BUILDROOT_IMG : null, // TOP_LEFT
                              null,                                   // TOP_RIGHT
                              hasWarning ? OVR_WARNING_IMG : null,    // BOTTOM_LEFT
                              overlayImagePath});                     // BOTTOM_RIGHT

        return info;
    }

    protected static IContainer resolveFolderLocation(String location, IProject withinProject, ICConfigurationDescription configuration) {
        try {
            location = CdtVariableManager.getDefault().resolveValue(location, "[unknown-macro]", ",", configuration);
        }
        catch (CdtVariableException e) {
            Activator.logError("Cannot resolve macros in build directory spec "+location, e);
        }
        IContainer[] folderPossibilities = ResourcesPlugin.getWorkspace().getRoot().findContainersForLocation(new Path(location));
        IContainer folder = null;
        for (IContainer f : folderPossibilities)
            if (f.getProject().equals(withinProject))
                folder = f;
        return folder;
    }

    /**
     * The resource for which the Properties dialog was brought up.
     */
    protected IProject getProject() {
        return (IProject) getElement().getAdapter(IProject.class);
    }

    @Override
    public boolean performOk() {
        if (WorkspaceJob.getJobManager().find(ResourcesPlugin.FAMILY_MANUAL_BUILD).length != 0) {
            MessageDialog.openError(Display.getCurrent().getActiveShell(), "Error", "Cannot update settings while build is in progress.");
            return false;
        }
        // note: performApply() delegates here too
        saveBuildSpecFile();
        CDTPropertyManager.performOkForced(this);
        CDTUtils.invalidateDiscoveredPathInfo(getProject());  //FIXME only if source entries actually changed?
        return true;
    }

    protected void loadBuildSpecFile() {
        IProject project = getProject();
        try {
            buildSpec = BuildSpecification.readBuildSpecFile(project);
        }
        catch (CoreException e) {
            errorDialog("Cannot read build specification, reverting page content to the default settings.", e);
        }

        if (buildSpec == null)
            buildSpec = BuildSpecification.createBlank(project);
    }

    protected void saveBuildSpecFile() {
        try {
            // purge entries that correspond to nonexistent (deleted/renamed/moved) folders
            for (IContainer folder: buildSpec.getMakeFolders())
                if (!folder.isAccessible())
                    buildSpec.setFolderMakeType(folder, BuildSpecification.NONE);

            // save
            buildSpec.save();
        }
        catch (CoreException e) {
            errorDialog("Cannot store build specification", e);
        }
    }

    protected void errorDialog(String message, Throwable e) {
        Activator.logError(message, e);
        IStatus status = new Status(IMarker.SEVERITY_ERROR, Activator.PLUGIN_ID, e.getMessage(), e);
        ErrorDialog.openError(Display.getCurrent().getActiveShell(), "Error", message, status);
    }
}

