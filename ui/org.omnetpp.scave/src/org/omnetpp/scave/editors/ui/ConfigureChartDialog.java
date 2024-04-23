/*--------------------------------------------------------------*
  Copyright (C) 2006-2015 OpenSim Ltd.

  This file is distributed WITHOUT ANY WARRANTY. See the file
  'License' for details on this and other legal matters.
*--------------------------------------------------------------*/

package org.omnetpp.scave.editors.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.omnetpp.common.util.UIUtils;
import org.omnetpp.scave.ScaveImages;
import org.omnetpp.scave.ScavePlugin;
import org.omnetpp.scave.editors.ChartScriptEditor;
import org.omnetpp.scave.editors.ScaveEditor;
import org.omnetpp.scave.editors.VectorOperations;
import org.omnetpp.scave.editors.VectorOperations.VectorOp;
import org.omnetpp.scave.model.Chart;
import org.omnetpp.scave.model.Chart.DialogPage;
import org.omnetpp.scave.model.commands.AdjustChartPropertiesCommand;
import org.omnetpp.scave.model.commands.CompoundCommand;
import org.omnetpp.scave.model.commands.SetChartDialogPagesCommand;
import org.omnetpp.scave.model.commands.SetChartPropertyCommand;

/**
 * This is the edit dialog for charts.
 */
public class ConfigureChartDialog extends TitleAreaDialog {

    private ScaveEditor editor;
    private Chart chart;
    private ChartEditForm form;

    public ConfigureChartDialog(Shell parentShell, Chart chart, ScaveEditor editor) {
        super(parentShell);
        setShellStyle(getShellStyle() | SWT.RESIZE);
        this.editor = editor;
        this.chart = chart;

        // If the chart is open in an editor page, try to get the vector operations specific to it,
        // otherwise, fall back to the built-in ones.
        FormEditorPage editorPage = editor.getEditorPage(chart);
        List<VectorOp> vectorOperations = (editorPage != null)
                ? ((ChartPage)editorPage).getChartScriptEditor().getVectorOpData()
                : VectorOperations.getBuiltinVectorOpData();

        List<String> columnNames = (editorPage != null)
                ? ((ChartPage)editorPage).getChartScriptEditor().getObservedColumnNames()
                : null;

        this.form = new ChartEditForm(chart, editor.getChartTemplateRegistry(), editor.getResultFileManager(), vectorOperations, columnNames);
    }

    @Override
    protected IDialogSettings getDialogBoundsSettings() {
        return UIUtils.getDialogSettings(ScavePlugin.getDefault(), getClass().getName());
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText("Configure Chart");
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (IDialogConstants.APPLY_ID == buttonId)
            applyPressed();
        else
            super.buttonPressed(buttonId);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createEditPagesButton(parent);

        createButton(parent, IDialogConstants.OK_ID, IDialogConstants.OK_LABEL, true);
        createButton(parent, IDialogConstants.APPLY_ID, IDialogConstants.APPLY_LABEL, false);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    protected void createEditPagesButton(Composite parent) {
        ((GridLayout) parent.getLayout()).numColumns++;
        Button button = new Button(parent, SWT.PUSH|SWT.FLAT);
        button.setImage(ScavePlugin.getCachedImage(ScaveImages.IMG_ETOOL16_COGWHEEL_GRAY));
        button.setToolTipText("Edit Dialog Pages");
        button.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        button.addSelectionListener(SelectionListener.widgetSelectedAdapter((e) -> editChartDialogPages()));
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite composite = (Composite)super.createDialogArea(parent);

        Composite panel = new Composite(composite, SWT.NONE);
        panel.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        setTitle("Chart '" + chart.getName() + "'");

        setMessage(makeMessage(chart));

        form.populatePanel(panel);
        return composite;
    }

    protected String makeMessage(Chart chart) {
        String type = "n/a";
        switch (chart.getType()) {
            case BAR: type = "bar chart"; break;
            case LINE: type = "line plot"; break;
            case HISTOGRAM: type = "histogram plot"; break;
            case MATPLOTLIB: type = "Matplotlib plot"; break;
        }
        return "Type: " + type;
    }

    @Override
    protected void okPressed() {
        applyChanges();
        super.okPressed();
    }

    protected void applyPressed() {
        applyChanges();
    }

    protected void editChartDialogPages() {
        EditChartPagesDialog dialog = new EditChartPagesDialog(this.getShell(), chart);
        if (dialog.open() == Dialog.OK) {
            List<DialogPage> editedPages = dialog.getResult();
            if (!chart.getDialogPages().equals(editedPages)) {
                CompoundCommand command = new CompoundCommand("Edit chart pages");
                command.append(new SetChartDialogPagesCommand(chart, editedPages));
                command.append(new AdjustChartPropertiesCommand(chart, collectEditableProperties(editedPages)));
                editor.getActiveCommandStack().execute(command);
                form.rebuild();
            }
        }
    }

    protected static Map<String,String> collectEditableProperties(List<DialogPage> pages) {
        Map<String,String> properties = new HashMap<>();
        for (DialogPage page : pages) {
            try {
                properties.putAll(ScaveUtil.collectEditablePropertiesFromXswt(page.xswtForm));
            }
            catch (Exception e) {
                // ignore errors
            }
        }
        return properties;
    }

//    protected void updateButtonsAndErrorMessage() {
//        IStatus status = form.validate();
//        boolean enabled = !status.matches(IStatus.ERROR);
//        Button okButton = getButton(IDialogConstants.OK_ID);
//        if (okButton != null)
//            okButton.setEnabled(enabled);
//        Button applyButton = getButton(IDialogConstants.APPLY_ID);
//        if (applyButton != null)
//            applyButton.setEnabled(enabled);
//        setErrorMessage(status);
//    }
//
//    private void setErrorMessage(IStatus status) {
//        String message = null;
//        if (status.matches(IStatus.ERROR)) {
//            IStatus error = StatusUtil.getFirstDescendantWithSeverity(status, IStatus.ERROR);
//            Assert.isNotNull(error);
//            message = status.isMultiStatus() ? status.getMessage() + ": " : "";
//            message += error.getMessage();
//        }
//        else if (status.matches(IStatus.WARNING)) {
//            IStatus warning = StatusUtil.getFirstDescendantWithSeverity(status, IStatus.WARNING);
//            Assert.isNotNull(warning);
//            message = status.isMultiStatus() ? status.getMessage() + ": " : "";
//            message += warning.getMessage();
//        }
//        setErrorMessage(message);
//    }

    private void applyChanges() {

        CompoundCommand command = new CompoundCommand("Configure Chart");

        Map<String, String> props = form.collectProperties();

        for (String k : props.keySet())
            command.append(new SetChartPropertyCommand(chart, k, props.get(k)));

        FormEditorPage editorPage = editor.getEditorPage(chart);
        if (editorPage instanceof ChartPage) {
            ChartScriptEditor chartScriptEditor = ((ChartPage)editorPage).chartScriptEditor;
            chartScriptEditor.getCommandStack().execute(command);
            chartScriptEditor.refreshChart();

            if (chart.isTemporary()) {
                // KLUDGE: Fake a selection change so that the Properties view is updated.
                // (This is needed because temp charts are not part of the Analysis, so changing
                // them does not result in a model change notification.)
                editor.setSelection(new StructuredSelection());
                editor.setSelection(new StructuredSelection(chart));
            }
        }
        else {
            editor.getChartsPage().getCommandStack().execute(command);
        }
    }

}
