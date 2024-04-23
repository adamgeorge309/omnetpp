/*--------------------------------------------------------------*
  Copyright (C) 2006-2015 OpenSim Ltd.

  This file is distributed WITHOUT ANY WARRANTY. See the file
  'License' for details on this and other legal matters.
*--------------------------------------------------------------*/

package org.omnetpp.scave.editors.ui;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.fieldassist.IContentProposalProvider;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Text;
import org.omnetpp.common.Debug;
import org.omnetpp.common.contentassist.ContentAssistUtil;
import org.omnetpp.common.ui.SWTFactory;
import org.omnetpp.common.ui.StyledTextUndoRedoManager;
import org.omnetpp.common.ui.TimeTriggeredProgressMonitorDialog2;
import org.omnetpp.common.util.Converter;
import org.omnetpp.common.util.UIUtils;
import org.omnetpp.common.wizard.XSWTDataBinding;
import org.omnetpp.scave.ScavePlugin;
import org.omnetpp.scave.assist.FilterExpressionProposalProvider;
import org.omnetpp.scave.assist.LegendFormatStringContentProposalProvider;
import org.omnetpp.scave.assist.MatplotlibrcContentProposalProvider;
import org.omnetpp.scave.assist.NativePlotPropertiesContentProposalProvider;
import org.omnetpp.scave.assist.VectorOperationsContentProposalProvider;
import org.omnetpp.scave.charttemplates.ChartTemplateRegistry;
import org.omnetpp.scave.editors.VectorOperations.VectorOp;
import org.omnetpp.scave.engine.IDList;
import org.omnetpp.scave.engine.InterruptedFlag;
import org.omnetpp.scave.engine.ResultFileManager;
import org.omnetpp.scave.engine.Run;
import org.omnetpp.scave.engine.StringVector;
import org.omnetpp.scave.model.Chart;
import org.omnetpp.scave.model.Chart.DialogPage;
import org.omnetpp.scave.model2.FilterHintsCache;
import org.omnetpp.scave.model2.ResultSelectionFilterGenerator;
import org.xml.sax.SAXException;

import com.swtworkbench.community.xswt.XSWT;

/**
 * This is the guts of ConfigureChartDialog.
 */
public class ChartEditForm {

    public static final String PROP_ACTIVE_TAB = "active-tab";

    protected Chart chart;
    protected ChartTemplateRegistry chartTemplateRegistry;
    protected ResultFileManager manager;
    protected Map<String,Control> xswtWidgetMap = new HashMap<>();
    protected FilterHintsCache filterHintsCache = new FilterHintsCache();
    protected List<VectorOp> vectorOperations; // needed for content assist
    protected List<String> columnNames;
    protected Composite panel;


    protected static final String USER_DATA_KEY = "ChartEditForm";

    public ChartEditForm(Chart chart, ChartTemplateRegistry chartTemplateRegistry, ResultFileManager manager, List<VectorOp> vectorOperations, List<String> columnNames) {
        this.chart = chart;
        this.chartTemplateRegistry = chartTemplateRegistry;
        this.manager = manager;
        this.vectorOperations = vectorOperations;
        this.columnNames = columnNames;
    }

    /**
     * Creates the controls of the dialog.
     */
    public void populatePanel(Composite panel) {
        this.panel = panel;
        panel.setLayout(new GridLayout(1, false));
        final TabFolder tabfolder = createTabFolder(panel);

        for (DialogPage page : chart.getDialogPages())
            createTab(tabfolder, page.label, page.xswtForm);

        fillComboBoxes();
        populateControls();
        addExtraWidgetLogic();
        validatePropertyNames();

        // switch to the last used page
        String defaultPage = getDialogSettings().get(PROP_ACTIVE_TAB);
        if (defaultPage != null)
            for (TabItem tabItem : tabfolder.getItems())
                if (tabItem.getText().equals(defaultPage)) {
                    tabfolder.setSelection(tabItem);
                    tabItem.getControl().setFocus();
                    break;
                }

        // save current tab as dialog setting (the code is here because there's no convenient function that is invoked on dialog close (???))
        tabfolder.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                TabItem[] selectedTabs = tabfolder.getSelection();
                if (selectedTabs.length > 0) {
                    getDialogSettings().put(PROP_ACTIVE_TAB, selectedTabs[0].getText());
                    selectedTabs[0].getControl().setFocus();
                }

            }
        });
    }

    private void validatePropertyNames() {
        Set<String> propertyNames = new HashSet<>(chart.getPropertyNames());
        Set<String> formPropertyNames = new HashSet<>(xswtWidgetMap.keySet());

        if (!formPropertyNames.equals(propertyNames)) {
            Set<String> declaredNotOnForm = new HashSet<String>(propertyNames);
            declaredNotOnForm.removeAll(formPropertyNames);

            Set<String> onFormNotDeclared = new HashSet<String>(formPropertyNames);
            onFormNotDeclared.removeAll(propertyNames);

            if (!declaredNotOnForm.isEmpty())
                Debug.println("Uneditable properties of chart '" + chart.getName() + "' (template '" + chart.getTemplateID() + "') : " + declaredNotOnForm);
            if (!onFormNotDeclared.isEmpty())
                throw new RuntimeException("Edited properties not declared: " + onFormNotDeclared);
        }
    }

    protected IDialogSettings getDialogSettings() {
        return UIUtils.getDialogSettings(ScavePlugin.getDefault(), "ChartEditForm");
    }

    protected String[] getComboContents(String contentString) {
        Set<String> items = new HashSet<String>();

        ResultFileManager.runWithReadLock(manager, () -> {

            for (String part : contentString.split(",")) {
                if (part.startsWith("$")) {
                    switch (part) {
                    case "$scalarnames":
                        addAll(items, manager.getUniqueResultNames(manager.getAllScalars(true)).keys());
                        break;
                    case "$vectornames":
                        addAll(items, manager.getUniqueResultNames(manager.getAllVectors()).keys());
                        break;
                    case "$histogramnames":
                        addAll(items, manager.getUniqueResultNames(manager.getAllHistograms()).keys());
                        break;
                    case "$statisticnames":
                        addAll(items, manager.getUniqueResultNames(manager.getAllStatistics()).keys());
                        break;
                    case "$itervarnames":
                        for (Run run : manager.getRuns().toArray())
                            addAll(items, run.getIterationVariables().keys());
                        break;
                    case "$runattrnames":
                        for (Run run : manager.getRuns().toArray())
                            addAll(items, run.getAttributes().keys());
                        break;
                    default:
                        items.add("Unknown: " + part);
                        break;
                    }
                }
                else {
                    items.add(part);
                }
            }
        });

        String[] result = items.toArray(new String[]{});
        Arrays.sort(result);
        return result;
    }

    private static void addAll(Collection<String> result, StringVector items) {
        for (String name : items.toArray())
            result.add(name);
    }

    protected void populateControls() {
        populateControls(chart.getPropertiesAsMap());
    }

    protected void populateControls(Map<String,String> properties) {
        for (String propId : xswtWidgetMap.keySet()) {
            String value = properties.get(propId);
            if (value != null) {
                Control control = xswtWidgetMap.get(propId);
                try {
                    XSWTDataBinding.putValueIntoControl(control, value, null);
                } catch (Exception e) {
                    MessageDialog.openError(null, "Error", String.format("Error populating dialog field '%s' (%s) with value '%s': ", propId, control.getClass().getSimpleName(), value) + e.getMessage());
                }
            }
        }
    }

    protected void addExtraWidgetLogic() {
        // setting up content assist and "enabler" checkboxes
        for (String key : xswtWidgetMap.keySet()) {
            Control control = xswtWidgetMap.get(key);

            if (control instanceof StyledText) {
               StyledTextUndoRedoManager.getOrCreateManagerOf((StyledText)control);
            }

            String contentAssist = (String)control.getData("contentAssist");
            if (contentAssist != null)  {
                IContentProposalProvider proposalProvider = makeProposalProvider(contentAssist);

                if (proposalProvider != null) {
                    if (control instanceof Text)
                        ContentAssistUtil.configureText((Text)control, proposalProvider, " ~:().");
                    else if (control instanceof StyledText)
                        ContentAssistUtil.configureStyledText((StyledText)control, proposalProvider, " ~:().");
                    else
                        ScavePlugin.getDefault().getLog().warn("'contentAssist' attribute in XSWT file is ignored for widget of type '" + control.getClass().getSimpleName() + "', it is only valid for Text and StyledText widgets");
                }
            }

            String role = (String)control.getData("role");
            if (role != null) {
                if (role.equals("simplify") && control instanceof Button)
                    configureSimplifyButton((Button)control);
                else
                    ScavePlugin.getDefault().getLog().warn("'role' attribute in XSWT file is ignored for widget of type '" + control.getClass().getSimpleName() + "', only role='simplify' is supported for Button widgets");
            }

            String isEnabler = (String)control.getData("isEnabler");
            if (control instanceof Button && isEnabler != null && isEnabler.equalsIgnoreCase("true")) {
                Button button = (Button)control;

                SelectionAdapter listener = new SelectionAdapter() {
                    private boolean isRadio(Control control) {
                        return control instanceof Button && (((Button)control).getStyle() & SWT.RADIO) != 0;
                    }
                    @Override
                    public void widgetSelected(SelectionEvent e) {
                        boolean enable = button.getSelection();
                        for (Control sibling: button.getParent().getChildren())
                            if (sibling != control && (!isRadio(button) || !isRadio(sibling))) // don't touch itself and (if this is a radio button) other radio buttons
                                sibling.setEnabled(enable);
                    }
                };
                button.addSelectionListener(listener);

                // apply initial state
                listener.widgetSelected(null);
            }

            String enablerButtonId = (String)control.getData("enablerButton");
            if (StringUtils.isNotEmpty(enablerButtonId)) {
                Control enabler = xswtWidgetMap.get(enablerButtonId);
                if (!(enabler instanceof Button))
                    ScavePlugin.getDefault().getLog().warn("'enablerButton' attribute in XSWT file is ignored: widget with id=" + enablerButtonId + " not found or not a Button");
                else {
                    Button enablerButton = (Button)enabler;
                    SelectionListener listener = SelectionListener.widgetSelectedAdapter(e -> {
                        control.setEnabled(enablerButton.getSelection());
                    });
                    enablerButton.addSelectionListener(listener);

                    // apply initial state
                    listener.widgetSelected(null);
                }
            }
        }
    }

    protected IContentProposalProvider makeProposalProvider(String contentAssist) {
        switch (contentAssist.toLowerCase()) {
        case "filter":
            FilterExpressionProposalProvider expressionProposalProvider = new FilterExpressionProposalProvider();
            expressionProposalProvider.setFilterHintsCache(filterHintsCache);
            expressionProposalProvider.setIDList(manager, manager.getAllItems());
            return expressionProposalProvider;
        case "vectorops":
            return new VectorOperationsContentProposalProvider(vectorOperations);
        case "plotproperties":
            return new NativePlotPropertiesContentProposalProvider();
        case "matplotlibrc":
            return new MatplotlibrcContentProposalProvider();
        case "columns":
            return new LegendFormatStringContentProposalProvider(columnNames);
        default:
            ScavePlugin.getDefault().getLog().warn("Invalid value for 'contentAssist' attribute in XSWT file: '" + contentAssist + "'");
            return null;
        }
    }

    private void configureSimplifyButton(Button button) {
        // figure out parameters: filter control, result types
        String targetControlAttr = (String)button.getData("targetControl");
        if (targetControlAttr == null)
            targetControlAttr = "filter";
        Control filterControl = xswtWidgetMap.get(targetControlAttr);
        if (!(filterControl instanceof Text) && !(filterControl instanceof StyledText)) {
            ScavePlugin.getDefault().getLog().warn("Cannot configure 'simplify' button: no control of type Text or StyledText named '"+ targetControlAttr + "'");
            return;
        }
        String resultTypeAttr = (String)filterControl.getData("resultType");
        int tmpResultTypes = ~0; // alles
        if (resultTypeAttr != null) {
            switch (resultTypeAttr) {
            case "parameter": tmpResultTypes = ResultFileManager.PARAMETER; break;
            case "scalar": tmpResultTypes = ResultFileManager.SCALAR; break;
            case "vector": tmpResultTypes = ResultFileManager.VECTOR; break;
            case "statistics": tmpResultTypes = ResultFileManager.STATISTICS; break;
            case "histogram": tmpResultTypes = ResultFileManager.HISTOGRAM; break;
            default: ScavePlugin.getDefault().getLog().warn("Cannot configure 'simplify' button: invalid value for 'resultType'");
            }
        }
        final int resultTypes = tmpResultTypes;

        // configure button
        button.addSelectionListener(SelectionListener.widgetSelectedAdapter((e) -> {
            String filter = XSWTDataBinding.getValueFromControl(filterControl, null).toString();
            String[] result = new String[1];
            boolean ok = TimeTriggeredProgressMonitorDialog2.runWithDialog("Simplifying filter", (monitor) -> {
                IDList all = manager.getItems(resultTypes, true);
                IDList target = manager.filterIDList(all, filter);
                InterruptedFlag interrupted = TimeTriggeredProgressMonitorDialog2.getActiveInstance().getInterruptedFlag();
                result[0] = ResultSelectionFilterGenerator.getFilter(target, all, manager, monitor, interrupted);
            });
            if (ok)
                XSWTDataBinding.putValueIntoControl(filterControl, result[0], null);
        }));
    }

    protected void fillComboBoxes() {
        for (String key : xswtWidgetMap.keySet()) {
            Control control = xswtWidgetMap.get(key);
            String content = (String)control.getData("content");
            if (control instanceof Combo && content != null) {
                Combo combo = (Combo)control;
                for (String comboItem : getComboContents(content))
                    combo.add(comboItem);
            }
        }
    }

    private TabFolder createTabFolder(Composite parent) {
        TabFolder tabfolder = new TabFolder(parent, SWT.NONE);
        tabfolder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        return tabfolder;

    }

    protected Composite createTab(TabFolder tabfolder, String label, String xswtForm) {
        TabItem tabitem = new TabItem(tabfolder, SWT.NONE);
        tabitem.setText(label);

        try {
            Composite xswtHolder = SWTFactory.createComposite(tabfolder, 1, 1, SWTFactory.GRAB_AND_FILL_HORIZONTAL);
            tabitem.setControl(xswtHolder);
            validateXml(xswtForm); // because XSWT is not very good at it
            @SuppressWarnings("unchecked")
            Map<String,Control> tempWidgetMap = XSWT.create(xswtHolder, new ByteArrayInputStream(xswtForm.getBytes()));
            xswtWidgetMap.putAll(tempWidgetMap);
            return xswtHolder;
        }
        catch (Exception e) {
            // log
            IStatus status = new Status(IStatus.ERROR, ScavePlugin.PLUGIN_ID, "Cannot create (possibly user-edited) dialog page '" + label + "' for chart template '" + chart.getTemplateID() + "'", e);
            ScavePlugin.log(status);

            // show error page
            tabitem.setImage(UIUtils.ICON_ERROR);
            tabitem.getControl().dispose();
            Composite composite = SWTFactory.createComposite(tabfolder, 1, 1, SWTFactory.GRAB_AND_FILL_HORIZONTAL);
            tabitem.setControl(composite);
            Label heading = SWTFactory.createWrapLabel(composite, "An error occurred while setting up page from XSWT source", 1);
            heading.setFont(JFaceResources.getHeaderFont());
            SWTFactory.createWrapLabel(composite, e.getMessage(), 1);
            return null;
        }
    }

    protected void validateXml(String xswtForm) throws SAXException, IOException, ParserConfigurationException {
        DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        db.parse(new ByteArrayInputStream(xswtForm.getBytes()));
    }

    public Map<String, String> collectProperties() {
        Map<String, String> result = new HashMap<>();
        for (String k : xswtWidgetMap.keySet()) {
            Control control = xswtWidgetMap.get(k);
            Object value = XSWTDataBinding.getValueFromControl(control, null);

            String chartValueString = chart.getPropertyValue(k);
            String formValueString = Converter.objectToString(value);

            if (!formValueString.equals(chartValueString))
                result.put(k, formValueString);
        }
        return result;
    }

    /**
     * Returns the selected radio button as the enum value it represents.
     */
    @SuppressWarnings("unchecked")
    protected static <T extends Enum<T>> T getSelection(Button[] radios, Class<T> type) {
        for (int i = 0; i < radios.length; ++i)
            if (radios[i].getSelection())
                return (T)radios[i].getData(USER_DATA_KEY);
        return null;
    }

    protected static <T extends Enum<T>> T resolveEnum(String text, Class<T> type) {
        T[] values = type.getEnumConstants();
        for (int i = 0; i < values.length; ++i)
            if (values[i].toString().equals(text))
                return values[i];
        return null;
    }

    /**
     * Select the radio button representing the enum value.
     */
    protected static void setSelection(Button[] radios, Enum<?> value) {
        for (int i = 0; i < radios.length; ++i)
            radios[i].setSelection(radios[i].getData(USER_DATA_KEY) == value);
    }

    /**
     * Sets the enabled state of the controls under {@code composite}
     * except the given {@code control} to {@code enabled}.
     */
    protected void setEnabledDescendants(Composite composite, boolean enabled, Control except) {
        for (Control child : composite.getChildren()) {
            if (child != except)
                child.setEnabled(enabled);
            if (child instanceof Composite)
                setEnabledDescendants((Composite)child, enabled, except);
        }
    }

    public void rebuild() {
        // remember user edits
        Map<String,String> properties = collectProperties();

        // tear down and forget existing controls
        for (Control c : panel.getChildren())
            c.dispose();
        xswtWidgetMap.clear();

        // recreate with the updated pages
        populatePanel(panel);
        panel.requestLayout();

        // restore contents
        populateControls(properties);
    }
}
