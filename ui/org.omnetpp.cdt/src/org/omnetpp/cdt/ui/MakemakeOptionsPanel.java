/*--------------------------------------------------------------*
  Copyright (C) 2006-2015 OpenSim Ltd.

  This file is distributed WITHOUT ANY WARRANTY. See the file
  'License' for details on this and other legal matters.
*--------------------------------------------------------------*/

package org.omnetpp.cdt.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.cdt.core.settings.model.ICConfigurationDescription;
import org.eclipse.cdt.core.settings.model.ICProjectDescription;
import org.eclipse.cdt.ui.newui.CDTPropertyManager;
import org.eclipse.cdt.utils.ui.controls.FileListControl;
import org.eclipse.cdt.utils.ui.controls.IFileListChangeListener;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.preference.IPreferencePageContainer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.dialogs.PropertyPage;
import org.eclipse.ui.preferences.IWorkbenchPreferenceContainer;
import org.omnetpp.cdt.Activator;
import org.omnetpp.cdt.build.BuildSpecification;
import org.omnetpp.cdt.build.MakemakeOptions;
import org.omnetpp.cdt.build.MakemakeOptions.Type;
import org.omnetpp.cdt.build.MetaMakemake;
import org.omnetpp.common.ui.HelpLink;
import org.omnetpp.common.ui.ToggleLink;
import org.omnetpp.common.util.FileUtils;
import org.omnetpp.common.util.StringUtils;


/**
 * UI for editing MakemakeOptions.
 *
 * @author Andras
 */
public class MakemakeOptionsPanel extends Composite {
    public static final String MAKEFRAG_FILENAME = "makefrag";

    // constants for CDT's FileListControl which are private;
    // see https://bugs.eclipse.org/bugs/show_bug.cgi?id=213188
    protected static final int BROWSE_NONE = 0;
    protected static final int BROWSE_FILE = 1;
    protected static final int BROWSE_DIR = 2;

    public static final String PROPERTYPAGE_PATH_AND_SYMBOLS = "org.eclipse.cdt.managedbuilder.ui.properties.Page_PathAndSymb";
    private static final String CCEXT_AUTODETECT = "autodetect";

    // the folder whose properties we're editing; needed for Preview panel / translated options
    private IContainer folder;
    private BuildSpecification buildSpec; // MetaMakemake needs it for preview; won't be modified
    private PropertyPage ownerPage; // null when we're in MakemakeOptionsDialog

    // controls
    private TabFolder tabfolder;
    private Composite targetPage;
    private Composite scopePage;
    private Composite compilePage;
    private Composite linkPage;
    private Composite customPage;
    private Composite previewPage;

    // "Scope" page
    private Button deepCheckbox;
    private Button recurseCheckbox;
    private ToggleLink scopePageToggle;
    private FileListControl submakeDirsList;

    // "Target" page
    private Button targetExecutableRadioButton;
    private Button targetSharedLibRadioButton;
    private Button targetStaticLibRadioButton;
    private Button targetCompileOnlyRadioButton;
    private Button defaultTargetNameRadionButton;
    private Button specifyTargetNameRadioButton;
    private Text targetNameText;
    private Button exportLibraryCheckbox;
    private Text outputDirText;

    // "Compile" page
    private FileListControl includePathList;
    private Button exportIncludePathCheckbox;
    private Button useExportedIncludePathsCheckbox;
    private Button useFeatureCFlagsCheckbox;
    private ToggleLink compilePageToggle;
    private Combo ccextCombo;
    private Button forceCompileForDllCheckbox;
    private Text dllSymbolText;

    // "Link" page
    private Button useExportedLibsCheckbox;
    private Button useFeatureLDFlagsCheckbox;
    private Combo userInterfaceCombo;
    private ToggleLink linkPageToggle;
    private FileListControl libsList;
    private FileListControl linkObjectsList;

    // "Custom" page
    private Text makefragText;
    private ToggleLink customPageToggle;
    private FileListControl makefragsList;

    // "Preview" page
    private Text optionsText;
    private Text translatedOptionsText;

    // Options without dialog fields
    private String defaultMode = null;
    private List<String> defines = new ArrayList<String>();
    private List<String> libDirs = new ArrayList<String>();
    private List<String> makefileVariables = new ArrayList<String>();

    // auxiliary variables
    private boolean beingUpdated = false;
    private int jobSerial = 0;


    public MakemakeOptionsPanel(Composite parent, int style) {
        super(parent, style);
        createContents();
    }

    protected Control createContents() {
        Composite composite = this;
        composite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        composite.setLayout(new GridLayout(1,false));

        tabfolder = new TabFolder(composite, SWT.TOP);
        tabfolder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        targetPage = createTabPage(tabfolder, "Target");
        scopePage = createTabPage(tabfolder, "Scope");
        compilePage = createTabPage(tabfolder, "Compile");
        linkPage = createTabPage(tabfolder, "Link");
        customPage = createTabPage(tabfolder, "Custom");
        previewPage = createTabPage(tabfolder, "Preview");
        tabfolder.setSelection(0);

        // "Target" page
        targetPage.setLayout(new GridLayout(1,false));
        Group group = createGroup(targetPage, "Target type", 1);
        targetExecutableRadioButton = createRadioButton(group, "Executable", null);
        targetSharedLibRadioButton = createRadioButton(group, "Shared library (.dll, .so or .dylib)", null);
        targetStaticLibRadioButton = createRadioButton(group, "Static library (.lib or .a)", null);
        exportLibraryCheckbox = createCheckbox(group, "Export this shared/static library for other projects", "Let dependent projects automatically use this library");
        ((GridData)exportLibraryCheckbox.getLayoutData()).horizontalIndent = 20;
        targetCompileOnlyRadioButton = createRadioButton(group, "No executable or library", null);
        createNote(group, "NOTE: To prevent the makefile from compiling any source file, exclude this folder from build.");

        Group targetNameGroup = createGroup(targetPage, "Target name", 2);
        defaultTargetNameRadionButton = createRadioButton(targetNameGroup, "Default", "The default is the project name");
        defaultTargetNameRadionButton.setLayoutData(new GridData());
        ((GridData)defaultTargetNameRadionButton.getLayoutData()).horizontalSpan = 2;
        specifyTargetNameRadioButton = createRadioButton(targetNameGroup, "Specify name (without extension/lib prefix): ", null);
        targetNameText = new Text(targetNameGroup, SWT.BORDER);
        targetNameText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Group outGroup = createGroup(targetPage, "Output", 2);
        outputDirText = createLabelAndText(outGroup, "Output directory:", "Specify project relative path. When empty, defaults to \"out\".");

        // "Scope" page
        scopePage.setLayout(new GridLayout(1,false));
        Group group1 = createGroup(scopePage, "Makefile Scope", 1);
        deepCheckbox = createCheckbox(group1, "Deep compile", "Compile all source files from this subdirectory tree");
        recurseCheckbox = createCheckbox(group1, "Recursive make", "Invoke makefiles any levels under this directory");
        createNote(group1, "NOTE: To control invocation order in recursive makefiles, add rules to Makefrag on the Custom tab.");
        Label submakeDirsLabel = createLabel(scopePage, "Additionally, invoke \"make\" in the following directories:");
        submakeDirsList = new FileListControl(scopePage, "Sub-make directories (relative path)", BROWSE_DIR);
        scopePageToggle = createToggleLink(scopePage, new Control[] {submakeDirsLabel, submakeDirsList.getListControl().getParent()});

        // "Compile" page
        compilePage.setLayout(new GridLayout(1,false));
        Group includeGroup = createGroup(compilePage, "Include Path", 1);
        includePathList = new FileListControl(includeGroup, "Include directories, relative to this makefile (-I)", BROWSE_DIR);
        exportIncludePathCheckbox = createCheckbox(includeGroup, "Export include path for other projects", null);
        useExportedIncludePathsCheckbox = createCheckbox(includeGroup, "Add include paths exported from referenced projects", null);
        useFeatureCFlagsCheckbox = createCheckbox(includeGroup, "Add include dirs and other compile options from enabled project features", null);
        createNote(includeGroup, "NOTE: Defines (-D options) contributed by project features will be placed into a generated header file as #define statements instead of being added to the compiler command line.");

        Group srcGroup = createGroup(compilePage, "Sources", 2);
        createLabel(srcGroup, "C++ file extension:");
        ccextCombo = new Combo(srcGroup, SWT.BORDER | SWT.READ_ONLY);
        ccextCombo.add(CCEXT_AUTODETECT);
        ccextCombo.add(".cc");
        ccextCombo.add(".cpp");

        Group dllGroup = createGroup(compilePage, "Windows DLLs", 2);
        HelpLink dllHelpLink = createHelpLink(dllGroup, "Hover or <A>click here</A> for more info on creating DLLs.", getHelpTextForBuildingDLLs());
        setColumnSpan(dllHelpLink, 2);
        forceCompileForDllCheckbox = createCheckbox(dllGroup, "Force compiling object files for use in DLLs", "Forces defining the FOO_EXPORT macro (where FOO is the DLL export/import symbol) even if the target is not a DLL");
        forceCompileForDllCheckbox.setLayoutData(new GridData(SWT.BEGINNING, SWT.BEGINNING, false, false, 2, 1));
        dllSymbolText = createLabelAndText(dllGroup, "DLL export/import symbol (e.g. FOO):", "Base name for the FOO_API, FOO_EXPORT and FOO_IMPORT macros");
        Link pathsPageLink2 = createLink(compilePage, "NOTE: Additional preprocessor symbols can be specified in the <A>Paths and Symbols</A> property page.");
        compilePageToggle = createToggleLink(compilePage, new Control[] {srcGroup, dllGroup, pathsPageLink2});

        // "Link" page
        linkPage.setLayout(new GridLayout(1,false));

        Group linkGroup = createGroup(linkPage, "Link", 2);
        useExportedLibsCheckbox = createCheckbox(linkGroup, "Link with libraries exported from referenced projects", null);
        ((GridData)useExportedLibsCheckbox.getLayoutData()).horizontalSpan = 2;
        useFeatureLDFlagsCheckbox = createCheckbox(linkGroup, "Add libraries and other linker options from enabled project features", null);
        ((GridData)useFeatureLDFlagsCheckbox.getLayoutData()).horizontalSpan = 2;
        createLabel(linkGroup, "User interface libraries to link with:");
        userInterfaceCombo = new Combo(linkGroup, SWT.BORDER | SWT.READ_ONLY);
        for (String i : new String[] {"All", "Qtenv", "Cmdenv"}) // note: should be consistent with populate()!
            userInterfaceCombo.add(i);
        libsList = new FileListControl(linkPage, "Additional libraries to link with: (-l option)", BROWSE_NONE);
        Link pathsPageLink3 = createLink(linkPage, "NOTE: Library paths can be specified in the <A>Paths and Symbols</A> property page.");
        linkObjectsList = new FileListControl(linkPage, "Additional objects to link with: (wildcards, macros allowed, e.g. $O/subdir/*.o)", BROWSE_NONE);
        linkPageToggle = createToggleLink(linkPage, new Control[] {libsList.getListControl().getParent(), pathsPageLink3, linkObjectsList.getListControl().getParent()});

        // "Custom" page
        customPage.setLayout(new GridLayout(1,false));
        createLabel(customPage, "Code fragment to be inserted into Makefile:");
        makefragText = new Text(customPage, SWT.MULTI | SWT.BORDER | SWT.H_SCROLL | SWT.V_SCROLL);
        makefragText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Label makefragsLabel = createLabel(customPage, "Other fragment files to include:");
        makefragsList = new FileListControl(customPage, "Makefile fragments", BROWSE_NONE);
        customPageToggle = createToggleLink(customPage, new Control[] {makefragsLabel, makefragsList.getListControl().getParent()});

        // "Preview" page
        previewPage.setLayout(new GridLayout(1,false));
        createLabel(previewPage, "Makemake options (editable):");
        optionsText = new Text(previewPage, SWT.MULTI | SWT.BORDER | SWT.WRAP | SWT.V_SCROLL);
        optionsText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        createLabel(previewPage, "Makemake options modified with CDT settings, and with meta-options resolved:");
        translatedOptionsText = new Text(previewPage, SWT.MULTI | SWT.BORDER | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        translatedOptionsText.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        translatedOptionsText.setBackground(translatedOptionsText.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));

        Dialog.applyDialogFont(composite);

        SelectionListener gotoListener = new SelectionListener(){
            public void widgetSelected(SelectionEvent e) {
                gotoPathsAndSymbolsPage();
            }
            public void widgetDefaultSelected(SelectionEvent e) {
                gotoPathsAndSymbolsPage();
            }
        };
        pathsPageLink2.addSelectionListener(gotoListener);
        pathsPageLink3.addSelectionListener(gotoListener);

        hookChangeListeners();

        return composite;
    }

    protected Label createLabel(Composite composite, String text) {
        Label label = new Label(composite, SWT.WRAP);
        label.setText(text);
        label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        return label;
    }

    protected Label createNote(Composite composite, String text) {
        Label label = new Label(composite, SWT.WRAP);
        label.setText(text);
        label.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));
        return label;
    }

    protected Link createLink(Composite composite, String text) {
        Link link = new Link(composite, SWT.NONE);
        link.setText(text);
        link.setLayoutData(new GridData());
        return link;
    }

    protected HelpLink createHelpLink(Composite composite, String text, String hoverText) {
        HelpLink link = new HelpLink(composite, SWT.NONE);
        link.setText(text);
        link.setHoverText(hoverText);
        link.setLayoutData(new GridData());
        return link;
    }

    protected void setColumnSpan(Control control, int columnSpan) {
        ((GridData)control.getLayoutData()).horizontalSpan = columnSpan;
    }

    protected Group createGroup(Composite composite, String text, int numColumns) {
        Group group = new Group(composite, SWT.NONE);
        group.setText(text);
        group.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
        group.setLayout(new GridLayout(numColumns,false));
        return group;
    }

    protected Button createButton(Composite parent, String text, String tooltip) {
        return createButton(parent, SWT.NONE, text, tooltip);
    }

    protected Button createCheckbox(Composite parent, String text, String tooltip) {
        return createButton(parent, SWT.CHECK, text, tooltip);
    }

    protected Button createRadioButton(Composite parent, String text, String tooltip) {
        return createButton(parent, SWT.RADIO, text, tooltip);
    }

    private Button createButton(Composite parent, int style, String text, String tooltip) {
        Button button = new Button(parent, style);
        button.setText(text);
        if (tooltip != null)
            button.setToolTipText(tooltip);
        button.setLayoutData(new GridData());
        return button;
    }

    protected Text createLabelAndText(Composite parent, String labelText, String tooltip) {
        Label label = createLabel(parent, labelText);
        Text text = new Text(parent, SWT.BORDER);
        text.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
        if (tooltip != null) {
            label.setToolTipText(tooltip);
            text.setToolTipText(tooltip);
        }
        return text;
    }

    protected Composite createTabPage(TabFolder tabfolder, String text) {
        TabItem item = new TabItem(tabfolder, SWT.NONE);
        item.setText(text);
        Composite composite = new Composite(tabfolder, SWT.NONE);
        composite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        item.setControl(composite);
        return composite;
    }

    protected Composite createCTabPage(CTabFolder tabfolder, String text) {
        CTabItem item = new CTabItem(tabfolder, SWT.NONE);
        item.setText(text);
        Composite composite = new Composite(tabfolder, SWT.NONE);
        composite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        item.setControl(composite);
        return composite;
    }

    protected ToggleLink createToggleLink(Composite parent, Control[] controls) {
        ToggleLink toggleLink = new ToggleLink(parent, SWT.NONE);
        toggleLink.setControls(controls);
        return toggleLink;
    }

    protected void hookChangeListeners() {
        // maintain consistency between dialog controls and the "Preview" page
        tabfolder.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                activeTabChanged();
            }
        });
        optionsText.addModifyListener(new ModifyListener() {
            public void modifyText(ModifyEvent e) {
                updateDialogState();
            }
        });

        // validate dialog contents on changes
        SelectionListener selectionChangeListener = new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                updateDialogState();
            }};
        ModifyListener modifyListener = new ModifyListener() {
            public void modifyText(ModifyEvent e) {
                updateDialogState();
            }};
        IFileListChangeListener fileListChangeListener = new IFileListChangeListener() {
            public void fileListChanged(FileListControl fileList, String[] oldValue, String[] newValue) {
                updateDialogState();
            }};

        deepCheckbox.addSelectionListener(selectionChangeListener);
        recurseCheckbox.addSelectionListener(selectionChangeListener);
        submakeDirsList.addChangeListener(fileListChangeListener);

        targetExecutableRadioButton.addSelectionListener(selectionChangeListener);
        targetSharedLibRadioButton.addSelectionListener(selectionChangeListener);
        targetStaticLibRadioButton.addSelectionListener(selectionChangeListener);
        targetCompileOnlyRadioButton.addSelectionListener(selectionChangeListener);

        defaultTargetNameRadionButton.addSelectionListener(selectionChangeListener);
        specifyTargetNameRadioButton.addSelectionListener(selectionChangeListener);
        targetNameText.addModifyListener(modifyListener);
        exportLibraryCheckbox.addSelectionListener(selectionChangeListener);
        outputDirText.addModifyListener(modifyListener);

        exportIncludePathCheckbox.addSelectionListener(selectionChangeListener);
        useExportedIncludePathsCheckbox.addSelectionListener(selectionChangeListener);
        useFeatureCFlagsCheckbox.addSelectionListener(selectionChangeListener);

        ccextCombo.addSelectionListener(selectionChangeListener);
        forceCompileForDllCheckbox.addSelectionListener(selectionChangeListener);
        dllSymbolText.addModifyListener(modifyListener);

        userInterfaceCombo.addSelectionListener(selectionChangeListener);
        useExportedLibsCheckbox.addSelectionListener(selectionChangeListener);
        useFeatureLDFlagsCheckbox.addSelectionListener(selectionChangeListener);
        libsList.addChangeListener(fileListChangeListener);
        linkObjectsList.addChangeListener(fileListChangeListener);

        makefragText.addModifyListener(modifyListener);
        makefragsList.addChangeListener(fileListChangeListener);
    }

    protected void activeTabChanged() {
        if (isPreviewPageSelected()) {
            // switched to "Preview" page -- update its contents
            MakemakeOptions options = getResult();
            optionsText.setText(options.toString());
            optionsText.setFocus();
            optionsText.setSelection(optionsText.getText().length());
            refreshTranslatedOptions(options);
        }
    }

    protected boolean isPreviewPageSelected() {
        return tabfolder.getSelection().length>0 && tabfolder.getSelection()[0].getControl() == previewPage;
    }

    /**
     * Set the makemake options to be edited. Note: buildSpec will NOT be modified;
     * to store the results, the user has to obtain the new options with getResult(),
     * and set it back on the buildSpec.
     */
    public void populate(IContainer folder, BuildSpecification buildSpec) {
        Assert.isTrue(buildSpec.getMakemakeOptions(folder)!=null);

        this.folder = folder;
        this.buildSpec = buildSpec;

        try {
            loadMakefragFiles();
        }
        catch (CoreException e) {
            errorDialog(e.getMessage(), e);
        }

        MakemakeOptions options = buildSpec.getMakemakeOptions(folder);
        if (isPreviewPageSelected())
            optionsText.setText(options.toString());
        else
            populateControls(options);

        updateDialogState();
    }

    protected void populateControls(MakemakeOptions options) {
        // "Scope" page
        deepCheckbox.setSelection(options.isDeep);
        recurseCheckbox.setSelection(options.metaRecurse);
        submakeDirsList.setList(options.submakeDirs.toArray(new String[]{}));

        // "Target" page
        targetExecutableRadioButton.setSelection(options.type==Type.EXE);
        targetSharedLibRadioButton.setSelection(options.type==Type.SHAREDLIB);
        targetStaticLibRadioButton.setSelection(options.type==Type.STATICLIB);
        targetCompileOnlyRadioButton.setSelection(options.type==Type.NOLINK);

        defaultTargetNameRadionButton.setSelection(StringUtils.isEmpty(options.target));
        defaultTargetNameRadionButton.setText("Default: " + folder.getProject().getName());
        specifyTargetNameRadioButton.setSelection(!StringUtils.isEmpty(options.target));
        targetNameText.setText(StringUtils.defaultIfEmpty(options.target, folder.getName()));
        exportLibraryCheckbox.setSelection(options.metaExportLibrary);
        outputDirText.setText(StringUtils.nullToEmpty(options.outRoot));

        // "Compile" page
        includePathList.setList(options.includeDirs.toArray(new String[]{}));
        exportIncludePathCheckbox.setSelection(options.metaExportIncludePath);
        useExportedIncludePathsCheckbox.setSelection(options.metaUseExportedIncludePaths);
        useFeatureCFlagsCheckbox.setSelection(options.metaFeatureCFlags);
        if (options.ccext == null)
            ccextCombo.setText(CCEXT_AUTODETECT);
        else
            ccextCombo.setText("." + options.ccext);
        forceCompileForDllCheckbox.setSelection(options.forceCompileForDll);
        dllSymbolText.setText(StringUtils.nullToEmpty(options.dllSymbol));

        // "Link" page
        userInterfaceCombo.setText(StringUtils.capitalize(options.userInterface.toLowerCase()));
        useExportedLibsCheckbox.setSelection(options.metaUseExportedLibs);
        useFeatureLDFlagsCheckbox.setSelection(options.metaFeatureLDFlags);
        libsList.setList(options.libs.toArray(new String[]{}));
        linkObjectsList.setList(options.extraArgs.toArray(new String[]{}));

        // "Custom" page
        // Note: makefrag texts need to be set differently
        makefragsList.setList(options.fragmentFiles.toArray(new String[]{}));

        // save options not stored in any GUI element
        defaultMode = options.defaultMode;
        defines = new ArrayList<String>(options.defines);
        libDirs = new ArrayList<String>(options.libDirs);
        makefileVariables = new ArrayList<String>(options.makefileVariables);

        // open ToggleLinks if controls are not empty
        if (submakeDirsList.getListControl().getItemCount() != 0)
            scopePageToggle.setExpanded(true);
        if (!ccextCombo.getText().equals(CCEXT_AUTODETECT) || forceCompileForDllCheckbox.getSelection() || !dllSymbolText.getText().isEmpty())
            compilePageToggle.setExpanded(true);
        if (libsList.getListControl().getItemCount() != 0 || linkObjectsList.getListControl().getItemCount() != 0)
            linkPageToggle.setExpanded(true);
        if (makefragsList.getListControl().getItemCount() != 0)
            customPageToggle.setExpanded(true);
    }

    protected void updateDialogState() {
        if (beingUpdated)
            return;  // prevent recursive invocations via listeners
        try {
            beingUpdated = true;

            if (isPreviewPageSelected()) {
                // re-parse options text modified by user
                MakemakeOptions updatedOptions = MakemakeOptions.parse(optionsText.getText());
                setErrorMessage(null);
                if (!updatedOptions.getParseErrors().isEmpty())
                    setErrorMessage("Error: " + updatedOptions.getParseErrors().get(0));
                populateControls(updatedOptions);
                refreshTranslatedOptions(updatedOptions);
            }
            else {
                // update enabled states
                Type type = getSelectedType();
                defaultTargetNameRadionButton.setEnabled(type!=Type.NOLINK);
                specifyTargetNameRadioButton.setEnabled(type!=Type.NOLINK);
                targetNameText.setEnabled(specifyTargetNameRadioButton.getSelection() && type!=Type.NOLINK);
                //TODO: outputDirText.setEnabled(type!=Type.NOLINK && !isExcluded);
                exportLibraryCheckbox.setEnabled(type==Type.STATICLIB || type==Type.SHAREDLIB);
                userInterfaceCombo.setEnabled(targetExecutableRadioButton.getSelection());
                useExportedLibsCheckbox.setEnabled(type==Type.EXE || type==Type.SHAREDLIB);
                useFeatureLDFlagsCheckbox.setEnabled(type==Type.EXE || type==Type.SHAREDLIB);
                libsList.setEnabled(type!=Type.NOLINK);
                linkObjectsList.setEnabled(type!=Type.NOLINK);

                // clear checkboxes that do not apply to the given target type
                // NOTE: we don't do it, because we'd lose settings when user
                // selects a different radio button then the original one
                //if (type!=Type.STATICLIB && type!=Type.SHAREDLIB)
                //    exportLibraryCheckbox.setSelection(false);
                //if (type!=Type.EXE && type!=Type.SHAREDLIB) {
                //    useExportedLibsCheckbox.setSelection(false);
                //    linkAllObjectsCheckbox.setSelection(false);
                //}

                // validate text field contents
                setErrorMessage(null);
                if (!targetNameText.getText().trim().matches("(?i)|([A-Z_][A-Z0-9_-]*)"))
                    setErrorMessage("Target name contains illegal characters");
                if (outputDirText.getText().matches(".*[:/\\\\].*"))
                    setErrorMessage("Output folder: cannot contain /, \\ or :.");
                if (!dllSymbolText.getText().trim().matches("(?i)|([A-Z_][A-Z0-9_-]*)"))
                    setErrorMessage("DLL export macro: contains illegal characters");
            }
        } finally {
            beingUpdated = false;
        }
    }

    protected void refreshTranslatedOptions(final MakemakeOptions updatedOptions) {
        // All the following is basically just for running the following code asynchronously:
        //   translatedOptionsText.setText(MetaMakemake.translateOptions(folder, updatedOptions).toString());

        if (translatedOptionsText.getText().equals(""))
            translatedOptionsText.setText("Processing...");

        // start background job with a new job serial number. Serial number is needed
        // so that we only put the result of the latest job into the dialog.
        final int thisJobSerial = ++jobSerial;
        Job job = new Job("Scanning source files...") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    // calculate
                    ICProjectDescription projectDescription = CDTPropertyManager.getProjectDescription(folder.getProject());
                    ICConfigurationDescription configuration = projectDescription.getActiveConfiguration();
                    BuildSpecification tempBuildSpec = buildSpec.clone();
                    tempBuildSpec.setMakemakeOptions(folder, updatedOptions);
                    final String translatedOptions = MetaMakemake.translateOptions(folder, tempBuildSpec, configuration, monitor).toString();

                    // display result if it's still relevant
                    if (jobSerial == thisJobSerial) {
                        Display.getDefault().asyncExec(new Runnable() {
                            public void run() {
                                if (jobSerial == thisJobSerial && !translatedOptionsText.isDisposed()) {
                                    translatedOptionsText.setText(translatedOptions);
                                }
                            }});
                    }
                }
                catch (CoreException e) {
                    return new Status(IStatus.ERROR, Activator.PLUGIN_ID, 0, "An error occurred during processing makemake options.", e);
                }
                return Status.OK_STATUS;
            }
        };
        job.setPriority(Job.INTERACTIVE); // high priority
        job.schedule();
    }

    protected void gotoPathsAndSymbolsPage() {
        if (ownerPage != null) {
            IPreferencePageContainer container = ownerPage.getContainer();
            if (container instanceof IWorkbenchPreferenceContainer)
                ((IWorkbenchPreferenceContainer)container).openPage(PROPERTYPAGE_PATH_AND_SYMBOLS, null);
        }
    }

    protected void setErrorMessage(String text) {
        if (ownerPage != null) {
            ownerPage.setErrorMessage(text);
            ownerPage.setValid(text == null);
        }
    }

    public void setOwnerPage(PropertyPage page) {
        this.ownerPage = page;
    }

    /**
     * Returns the current settings. The user needs to manually set this on the buildSpec
     * (the buildSpec won't be modified by this dialog.)
     */
    public MakemakeOptions getResult() {
        MakemakeOptions result = MakemakeOptions.createBlank();

        // "Scope" page
        result.isDeep = deepCheckbox.getSelection();
        result.metaRecurse = recurseCheckbox.getSelection();
        result.submakeDirs.addAll(Arrays.asList(submakeDirsList.getItems()));

        // "Target" page
        result.type = getSelectedType();
        result.target = defaultTargetNameRadionButton.getSelection() ? null : targetNameText.getText();
        result.metaExportLibrary = exportLibraryCheckbox.getSelection();
        result.outRoot = outputDirText.getText();

        // "Compile" page
        result.includeDirs.addAll(Arrays.asList(includePathList.getItems()));
        result.metaExportIncludePath = exportIncludePathCheckbox.getSelection();
        result.metaUseExportedIncludePaths = useExportedIncludePathsCheckbox.getSelection();
        result.metaFeatureCFlags = useFeatureCFlagsCheckbox.getSelection();
        String ccextText = ccextCombo.getText().trim().replace(".", "");
        result.ccext = (ccextText.equals("cc") || ccextText.equals("cpp")) ? ccextText : null;
        result.forceCompileForDll = forceCompileForDllCheckbox.getSelection();
        result.dllSymbol = dllSymbolText.getText().trim();

        // "Link" page
        result.userInterface = userInterfaceCombo.getText().trim();
        result.metaUseExportedLibs = useExportedLibsCheckbox.getSelection();
        result.metaFeatureLDFlags = useFeatureLDFlagsCheckbox.getSelection();
        result.libs.addAll(Arrays.asList(libsList.getItems()));
        result.extraArgs.addAll(Arrays.asList(linkObjectsList.getItems()));

        // "Custom" page
        result.fragmentFiles.addAll(Arrays.asList(makefragsList.getItems()));

        // Other options
        result.defaultMode = defaultMode;
        result.defines.addAll(defines);
        result.libDirs.addAll(libDirs);
        result.makefileVariables.addAll(makefileVariables);
        return result;
    }

    protected Type getSelectedType() {
        if (targetExecutableRadioButton.getSelection())
            return Type.EXE;
        else if (targetSharedLibRadioButton.getSelection())
            return Type.SHAREDLIB;
        else if (targetStaticLibRadioButton.getSelection())
            return Type.STATICLIB;
        else if (targetCompileOnlyRadioButton.getSelection())
            return Type.NOLINK;
        else
            return Type.EXE; // cannot happen
    }

    public String getMakefragContents() {
        return makefragText.getText();
    }

    public void loadMakefragFiles() throws CoreException {
        String makefragContents = readMakefrag(folder, MAKEFRAG_FILENAME);
        makefragText.setText(StringUtils.nullToEmpty(makefragContents));
    }

    public void saveMakefragFiles() throws CoreException {
        saveMakefrag(folder, MAKEFRAG_FILENAME, getMakefragContents());
    }

    protected String readMakefrag(IContainer sourceFolder, String makefragFilename) throws CoreException  {
        IFile makefragFile = sourceFolder.getFile(new Path(makefragFilename));
        if (makefragFile.exists()) {
            try {
                return FileUtils.readTextFile(makefragFile.getContents(), null);
            }
            catch (IOException e1) {
                throw Activator.wrapIntoCoreException("Cannot read "+makefragFile.toString(), e1);
            }
        }
        return null;
    }

    protected void saveMakefrag(IContainer sourceFolder, String makefragFilename, String makefragContents) throws CoreException {
        String currentContents = readMakefrag(sourceFolder, makefragFilename);
        if (StringUtils.isBlank(makefragContents))
            makefragContents = null;
        if (!StringUtils.equals(currentContents, makefragContents)) {
            IFile makefragFile = sourceFolder.getFile(new Path(makefragFilename));
            try {
                if (makefragContents == null)
                    makefragFile.delete(true, null);
                else
                    FileUtils.writeTextFile(makefragFile.getLocation().toFile(), makefragContents, makefragFile.getCharset());
                makefragFile.refreshLocal(IResource.DEPTH_ZERO, null);
            }
            catch (IOException e1) {
                throw Activator.wrapIntoCoreException("Cannot save "+makefragFile.toString(), e1);
            }
        }
    }

    protected void errorDialog(String message, Throwable e) {
        Activator.logError(message, e);
        IStatus status = new Status(IMarker.SEVERITY_ERROR, Activator.PLUGIN_ID, e.getMessage(), e);
        ErrorDialog.openError(Display.getCurrent().getActiveShell(), "Error", message, status);
    }

    protected String getHelpTextForBuildingDLLs() {
        return
        "Unlike Linux shared libraries which can be built from any C/C++ code,\n" +
        "Windows has special rules for code that goes into DLLs.\n" +
        "When a DLL is built, all symbols (functions, global variables, etc.)\n" +
        "that you want to expose as C/C++ API to users of your DLL need to be\n" +
        "marked with the <tt>__declspec(dllexport)</tt> qualifier.\n" +
        "Likewise, when you refer a symbol (function, variable, etc) that\n" +
        "comes from a DLL, the C/C++ declaration of that symbol needs to be\n" +
        "annotated with <tt>__declspec(dllimport)</tt>.\n" +
        "\n" +
        "<p>\n" +
        "In OMNeT++, we introduce a convention to automate the process\n" +
        "as far as possible, using macros. To build a DLL, you need to pick\n" +
        "a short name for it, say <tt>FOO</tt>, and add the following code\n" +
        "to a header file (say <tt>foodefs.h</tt>):\n" +
        "\n" +
        "<pre>\n" +
        "#include &lt;omnetpp.h&gt;\n" +
        "\n" +
        "#if defined(FOO_EXPORT)\n" +
        "#  define FOO_API OPP_DLLEXPORT\n" +
        "#elif defined(FOO_IMPORT)\n" +
        "#  define FOO_API OPP_DLLIMPORT\n" +
        "#else\n" +
        "#  define FOO_API\n" +
        "#endif\n" +
        "</pre>\n" +
        "\n" +
        "<p>\n" +
        "Then you need to include <tt>foodefs.h</tt> into all your header files, and\n" +
        "annotate public symbols in them with <tt>FOO_API</tt>. Also, insert the following in" +
        " your .msg files: \n" +
        "<pre>\n" +
        "cplusplus {{\n" +
        "  #include \"foodefs.h\"\n" +
        "}}\n" +
        "</pre>\n" +
        "<p>\n" +
        "When building the DLL, OMNeT++-generated makefiles will ensure that <tt>FOO_EXPORT</tt> is\n" +
        "defined, and so <tt>FOO_API</tt> becomes <tt>__declspec(dllexport)</tt>.\n" +
        "Likewise, when you use the DLL from external code, the makefile will define\n" +
        "<tt>FOO_IMPORT</tt>, causing <tt>FOO_API</tt> to become\n" +
        "<tt>__declspec(dllimport)</tt>. In all other cases, for example when compiling on\n" +
        "Linux, <tt>FOO_API</tt> will be empty.\n" +
        "\n" +
        "<p>\n" +
        "Here\'s how to annotate classes, functions and global variables:\n" +
        "\n" +
        "<pre>\n" +
        "class FOO_API SomeClass {\n" +
        "  ...\n" +
        "};\n" +
        "\n" +
        "int FOO_API someFunction(double a, int b);\n" +
        "\n" +
        "extern int FOO_API globalVariable; //note: global variables are discouraged\n" +
        "</pre>\n" +
        "If you have 3rd-party DLLs which use a different convention to handle " +
        "dllexport/dllimport, you need to manually specify the corresponding " +
        "macros on the \"Paths and symbols\" page of the project properties dialog.";
    }
}
