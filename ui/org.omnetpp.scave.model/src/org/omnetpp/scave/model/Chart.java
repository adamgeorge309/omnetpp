/*--------------------------------------------------------------*
  Copyright (C) 2006-2020 OpenSim Ltd.

  This file is distributed WITHOUT ANY WARRANTY. See the file
  'License' for details on this and other legal matters.
*--------------------------------------------------------------*/

package org.omnetpp.scave.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.Assert;

public class Chart extends AnalysisItem {

    public static class DialogPage {

        public DialogPage(DialogPage other) {
            this.id = other.id;
            this.label = other.label;
            this.xswtForm = other.xswtForm;
        }

        public DialogPage(String id, String label, String xswtForm) {
            this.id = id;
            this.label = label;
            this.xswtForm = xswtForm;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null || getClass() != obj.getClass())
                return false;
            DialogPage other = (DialogPage) obj;
            return id.equals(other.id) && label.equals(other.label) && xswtForm.equals(other.xswtForm);
        }

        public String id;
        public String label;
        public String xswtForm;
    }

    public static enum ChartType {
        BAR,
        HISTOGRAM,
        LINE,
        MATPLOTLIB
    }

    protected String script;
    protected List<Property> properties = new ArrayList<Property>();
    protected boolean temporary;

    protected String templateID;
    protected List<DialogPage> dialogPages = new ArrayList<>();
    protected ChartType type;
    protected String iconPath = "";
    protected int supportedResultTypes; // a bitwise OR of the constants in ResultFileManager
    protected String createdWith; // used to store the OMNeT++ version at creation

    public Chart() {
    }

    public Chart(ChartType type) {
        this.type = type;
    }

    public Chart(ChartType type, String name) {
        this.type = type;
        this.name = name;
    }

    public void copyFrom(Chart other) {
        try {
            name = other.name;
            script = other.script;
            temporary = other.temporary;

            properties = new ArrayList<Property>(other.properties.size());

            for (int i = 0; i < other.properties.size(); ++i)
                properties.add(other.properties.get(i).clone());

            // other fields are copied from the template upon creation and are never changed
            notifyListeners();

        } catch (CloneNotSupportedException e) {
            e.printStackTrace();
        }
    }

    public String getScript() {
        return script;
    }

    public void setScript(String script) {
        this.script = script;
        notifyListeners();
    }

    public List<Property> getProperties() {
        return Collections.unmodifiableList(properties);
    }

    public void setProperties(List<Property> properties) {
        for (Property p : this.properties)
            p.parent = null;
        this.properties = properties;
        for (Property p : this.properties)
            p.parent = this;
        notifyListeners();
    }

    public Property getProperty(String name) {
        for (Property p : properties)
            if (name.equals(p.getName()))
                return p;
        return null;
    }

    public void addProperty(Property property) {
        Assert.isTrue(getProperty(property.getName()) == null,
                "Duplicate property key: " + property.getName() + " on chart " + getName());
        property.parent = this;
        properties.add(property);
        notifyListeners();
    }

    public void removeProperty(Property property) {
        property.parent = null;
        properties.remove(property);
        notifyListeners();
    }

    public String getPropertyValue(String name) {
        for (Property p : properties)
            if (name.equals(p.getName()))
                return p.getValue();
        return null;
    }

    public void setPropertyValue(String name, String value) {
        Property property = getProperty(name);
        if (property != null)
            property.setValue(value);
        else
            addProperty(new Property(name, value));
    }

    public List<String> getPropertyNames() {
        List<String> result = new ArrayList<>();
        for (Property p : properties)
            result.add(p.getName());
        return result;
    }

    public Map<String,String> getPropertiesAsMap() {
        Map<String,String> result = new HashMap<>();
        for (Property p : properties)
            result.put(p.getName(), p.getValue());
        return result;
    }

    public void setProperties(Map<String,String> properties) {
        // set existing/missing properties with the specified value
        Map<String,String> origProperties = getPropertiesAsMap();
        for (String name : properties.keySet()) {
            String value = properties.get(name);
            if (!value.equals(origProperties.get(name)))
                setPropertyValue(name, value);
        }

        // remove excess properties
        for (String name : origProperties.keySet())
            if (!properties.containsKey(name))
                removeProperty(getProperty(name));
    }

    public void adjustProperties(Map<String,String> defaults) {
        // add missing properties with the default value
        Map<String,String> origProperties = getPropertiesAsMap();
        for (String name : defaults.keySet())
            if (!origProperties.containsKey(name))
                addProperty(new Property(name, defaults.get(name)));

        // remove excess properties
        for (String name : origProperties.keySet())
            if (!defaults.containsKey(name))
                removeProperty(getProperty(name));
    }

    public boolean isTemporary() {
        return temporary;
    }

    public void setTemporary(boolean temporary) {
        this.temporary = temporary;
        notifyListeners();
    }

    public String getTemplateID() {
        return templateID;
    }

    public void setTemplateID(String templateID) {
        this.templateID = templateID;
        notifyListeners();
    }

    public List<DialogPage> getDialogPages() {
        return Collections.unmodifiableList(dialogPages);
    }

    public void setDialogPages(List<DialogPage> dialogPages) {
        this.dialogPages = dialogPages;
        notifyListeners();
    }

    public ChartType getType() {
        return type;
    }

    public void setType(ChartType type) {
        this.type = type;
        notifyListeners();
    }

    public String getIconPath() {
        return iconPath;
    }

    public void setIconPath(String iconPath) {
        this.iconPath = iconPath;
        notifyListeners();
    }

    public int getSupportedResultTypes() {
        return supportedResultTypes;
    }

    public void setSupportedResultTypes(int supportedResultTypes) {
        this.supportedResultTypes = supportedResultTypes;
        notifyListeners();
    }

    public String getCreatedWith() {
        return createdWith;
    }

    public void setCreatedWith(String createdWith) {
        this.createdWith = createdWith;
        notifyListeners();
    }

    @Override
    protected Chart clone() throws CloneNotSupportedException {
        Chart clone = (Chart) super.clone();

        clone.properties = new ArrayList<Property>(properties.size());

        for (int i = 0; i < properties.size(); ++i)
            clone.properties.add(properties.get(i).clone());

        clone.dialogPages = new ArrayList<Chart.DialogPage>(dialogPages.size());

        for (int i = 0; i < dialogPages.size(); ++i)
            clone.dialogPages.add(new Chart.DialogPage(dialogPages.get(i)));

        return clone;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj.getClass() != getClass())
            return false;
        Chart other = (Chart)obj;

        if (!super.equals(other))
            return false;

        if (!name.equals(other.name) || !script.equals(other.script) || !temporary == other.temporary)
            return false;

        if (properties.size() != other.properties.size())
            return false;

        return properties.equals(other.properties); // TODO refine, allow different ordering
    }
}
