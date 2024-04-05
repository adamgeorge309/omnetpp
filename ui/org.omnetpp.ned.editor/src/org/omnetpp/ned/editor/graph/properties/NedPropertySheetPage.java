/*--------------------------------------------------------------*
  Copyright (C) 2006-2015 OpenSim Ltd.

  This file is distributed WITHOUT ANY WARRANTY. See the file
  'License' for details on this and other legal matters.
*--------------------------------------------------------------*/

package org.omnetpp.ned.editor.graph.properties;

import org.eclipse.gef.ui.properties.UndoablePropertySheetEntry;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.part.CellEditorActionHandler;
import org.eclipse.ui.part.IPageSite;
import org.eclipse.ui.views.properties.PropertySheetPage;
import org.omnetpp.common.util.ReflectionUtils;
import org.omnetpp.ned.editor.graph.GraphicalNedEditor;
import org.omnetpp.ned.editor.graph.properties.util.BasePreferrerPropertySheetSorter;

/**
 * Ned Property sheet page for the graphical ned editor
 */
public class NedPropertySheetPage extends PropertySheetPage {

    private final GraphicalNedEditor graphicalNedEditor;

    /**
     * @param graphicalNedEditor
     */
    public NedPropertySheetPage(GraphicalNedEditor graphicalNedEditor) {
        this.graphicalNedEditor = graphicalNedEditor;
    }

    @Override
    public void init(IPageSite pageSite) {
        super.init(pageSite);
        // set a sorter that places the Base group at the beginning. The rest
        // is alphabetically sorted
        setSorter(new BasePreferrerPropertySheetSorter());
        // integrates the GEF undo/redo stack
        setRootEntry(new UndoablePropertySheetEntry(graphicalNedEditor.getEditDomain().getCommandStack()));
    }

    @Override
    public void createControl(Composite parent) {
        super.createControl(parent);
        // source provider for editparts
        // we should not call this in the init method, because it causes NPE
        // BUG see: https://bugs.eclipse.org/bugs/show_bug.cgi?id=159747
        setPropertySourceProvider(new NedEditPartPropertySourceProvider());

        // set up a context menu with undo/redo items
    }

	@Override
	public void selectionChanged(IWorkbenchPart part, ISelection selection) {
		// Should handle only structured selection changes.
		//
		// Other selection changes like ITextSelection may happen right after
		// an IStructuredSelection (when the cursor in the text editor part
		// is moved in response to a selection in the graphical editor)
		// In response to this ITextSelection the property sheet would turn empty
		// as Eclipse would not know what NED model objects should be displayed.
		if (selection instanceof IStructuredSelection && !selection.isEmpty())
			super.selectionChanged(part, selection);
	}

	@Override
    public void setActionBars(IActionBars actionBars) {
        super.setActionBars(actionBars);
        // hook the editor's global undo/redo action to the cell editor
        getCellEditorActionHandler().setUndoAction(graphicalNedEditor.getActionRegistry().getAction(ActionFactory.UNDO.getId()));
        getCellEditorActionHandler().setRedoAction(graphicalNedEditor.getActionRegistry().getAction(ActionFactory.REDO.getId()));
    }

    /**
     * In Eclipse, there is no accessor for the private field so it is not possible to override
     * the setActionBars method. Once it is fixed remove this method.
     *
     * See BUG https://bugs.eclipse.org/bugs/show_bug.cgi?id=185081
     *
     * @return The private cell editor handler, so it is possible to override set action bars.
     */
    private CellEditorActionHandler getCellEditorActionHandler() {
        return (CellEditorActionHandler)ReflectionUtils.getFieldValue(this, "cellEditorActionHandler");
    }

}