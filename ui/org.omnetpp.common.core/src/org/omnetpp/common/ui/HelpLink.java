package org.omnetpp.common.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Link;
import org.omnetpp.common.util.StringUtils;

/**
 * A control for which displays a text with embedded link(s), and clicking
 * on a link will display a hover text in a window. The hover text can be given
 * the with setHoverText method.
 *
 * @author Andras
 */
public class HelpLink extends Composite {
    private Link link;
    private String hoverText;
    private HoverSupport hoverSupport;
    private SizeConstraint sizeConstraint = new SizeConstraint();

    public HelpLink(Composite parent, int style) {
        super(parent, style);
        setLayout(new FillLayout());

        link = new Link(this, SWT.NONE);

        hoverSupport = new HoverSupport();
        hoverSupport.adapt(link, new IHoverInfoProvider() {
            @Override
            public HtmlHoverInfo getHoverFor(Control control, int x, int y) {
                return new HtmlHoverInfo(getHoverText(x, y), sizeConstraint);
            }
        });

        link.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                hoverSupport.makeHoverSticky(link);
            }
        });
    }

    protected String getHoverText(int x, int y) {
        if (StringUtils.isEmpty(hoverText))
            return null;
        return HoverSupport.addHTMLStyleSheet(hoverText);
    }

    public Link getLinkControl() {
        return link;
    }

    public void setText(String text) {
        link.setText(text);
    }

    public String getText() {
        return link.getText();
    }

    public void setHoverText(String hoverText) {
        this.hoverText = hoverText;
    }

    public String getHoverText() {
        return hoverText;
    }

    public int getHoverMinimumWidth() {
        return sizeConstraint.minimumHeight;
    }

    public void setHoverMinimumWidth(int minimumWidth) {
        sizeConstraint.minimumWidth = minimumWidth;
    }

    public int getHoverMinimumHeight() {
        return sizeConstraint.minimumHeight;
    }

    public void setHoverMinimumHeight(int minimumHeight) {
        sizeConstraint.minimumHeight = minimumHeight;
    }

    public int getHoverPreferredWidth() {
        return sizeConstraint.preferredWidth;
    }

    public void setHoverPreferredWidth(int preferredWidth) {
        sizeConstraint.preferredWidth = preferredWidth;
    }

    public int getHoverPreferredHeight() {
        return sizeConstraint.preferredHeight;
    }

    public void setHoverPreferredHeight(int preferredHeight) {
        sizeConstraint.preferredHeight = preferredHeight;
    }
}
