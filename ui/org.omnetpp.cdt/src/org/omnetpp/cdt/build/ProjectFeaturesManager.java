/*--------------------------------------------------------------*
  Copyright (C) 2006-2015 OpenSim Ltd.

  This file is distributed WITHOUT ANY WARRANTY. See the file
  'License' for details on this and other legal matters.
*--------------------------------------------------------------*/

package org.omnetpp.cdt.build;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.collections.CollectionUtils;
import org.eclipse.cdt.core.model.CoreModel;
import org.eclipse.cdt.core.parser.util.ArrayUtil;
import org.eclipse.cdt.core.settings.model.ICConfigurationDescription;
import org.eclipse.cdt.core.settings.model.ICFolderDescription;
import org.eclipse.cdt.core.settings.model.ICLanguageSetting;
import org.eclipse.cdt.core.settings.model.ICLanguageSettingEntry;
import org.eclipse.cdt.core.settings.model.ICProjectDescription;
import org.eclipse.cdt.core.settings.model.ICSettingEntry;
import org.eclipse.cdt.core.settings.model.ICSourceEntry;
import org.eclipse.cdt.core.settings.model.WriteAccessException;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;
import org.omnetpp.cdt.Activator;
import org.omnetpp.cdt.CDTUtils;
import org.omnetpp.common.Debug;
import org.omnetpp.common.project.NedSourceFoldersConfiguration;
import org.omnetpp.common.project.ProjectUtils;
import org.omnetpp.common.util.FileUtils;
import org.omnetpp.common.util.StringUtils;
import org.omnetpp.common.util.XmlUtils;
import org.omnetpp.ned.core.INedResources;
import org.omnetpp.ned.core.NedResourcesPlugin;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Loads the contents of an ".oppfeatures" file into ProjectFeature objects, and
 * provides functionality to enable/disable features, to validate ".oppfeatures",
 * to check the project state, and other related functions.
 *
 * Feature enablements are stored in the ".oppfeaturestate" file.
 *
 * IMPORTANT: File names and formats need to be kept compatible with opp_featuretool.
 *
 * @author Andras
 */
public class ProjectFeaturesManager {
    public static final String PROJECTFEATURES_FILENAME = ".oppfeatures";
    public static final String PROJECTFEATURESTATE_FILENAME = ".oppfeaturestate";

    // XML element and attribute names for ".oppfeatures" and ".oppfeaturestates"
    private static final String ELMNT_FEATURES = "featurestates"; //TODO rename to "features"
    private static final String ELMNT_FEATURE = "feature";
    protected static final String ATT_ID = "id";
    private static final String ATT_NAME = "name";
    private static final String ATT_DESCRIPTION = "description";
    private static final String ATT_INITIALLYENABLED = "initiallyEnabled";
    private static final String ATT_LABELS = "labels";
    private static final String ATT_REQUIRES = "requires";
    private static final String ATT_NEDPACKAGES = "nedPackages";
    private static final String ATT_EXTRASOURCEFOLDERS = "extraSourceFolders";
    private static final String ATT_COMPILEFLAGS = "compileFlags";
    private static final String ATT_LINKERFLAGS = "linkerFlags";
    private static final String ATT_CPPSOURCEROOTS = "cppSourceRoots"; // attribute of root element
    private static final String ATT_DEFINESFILE = "definesFile"; // attribute of root element
    private static final String ATT_ENABLED = "enabled"; // in ".oppfeaturestates"

    private static final String[] ALL_ROOT_ATTRS = new String[] {
        ATT_CPPSOURCEROOTS, ATT_DEFINESFILE
    };
    private static final String[] ALL_FEATURE_ATTRS = new String[] {
        ATT_ID, ATT_NAME, ATT_DESCRIPTION, ATT_INITIALLYENABLED, ATT_LABELS, ATT_REQUIRES,
        ATT_NEDPACKAGES, ATT_EXTRASOURCEFOLDERS, ATT_COMPILEFLAGS,  ATT_LINKERFLAGS
    };

    private static final String DEFINESFILE_BANNER =
            "//\n" +
            "// Generated file, do not edit!\n" +
            "//\n" +
            "// This file defines symbols contributed by the currently active project features,\n" +
            "// and it is regenerated every time a project feature is enabled or disabled.\n" +
            "// See the Project Features dialog in the IDE, and opp_featuretool.\n" +
            "//\n";

    // state
    private IProject project;
    private Document doc;  // DOM tree of ".oppfeatures"; only kept for validate()
    private Map<String, ProjectFeature> features;  // indexed by Id
    private Set<String> cppSourceRoots = null; // project relative paths of C++ source locations, e.g. "src" in INET; cppSourceRoots==null means the whole project is a C++ source location
    private IFile definesFile = null; // the header file to be generated

    /**
     * Creates an empty object. Use loadFeaturesFile() to populate it with the
     * contents of the project's feature description file.
     */
    public ProjectFeaturesManager(IProject project) {
        this.project = project;
        this.features = new HashMap<String, ProjectFeature>();
    }

    /**
     * Returns the project associated with this features collection.
     */
    public IProject getProject() {
        return project;
    }

    /**
     * Returns true if there are no project features in this object.
     */
    public boolean isEmpty() {
        return features.isEmpty();
    }

    /**
     * Returns the project-relative path of the header file to be generated,
     * with the defines contributed by the enabled project features. Returns null
     * if a defines file is not specified.
     */
    public IFile getDefinesFile() {
        return definesFile;
    }

    /**
     * Returns the project features in this object.
     */
    public Collection<ProjectFeature> getFeatures() {
        return features.values();
    }

    /**
     * Returns the IDs of the stored project features.
     */
    public Set<String> getFeatureIds() {
        return features.keySet();
    }

    /**
     * Returns the project feature with the given ID, or null if there isn't one.
     */
    public ProjectFeature getFeature(String id) {
        return features.get(id);
    }

    /**
     * Returns the list of enabled project features for the project, using saved state.
     * For features that have no enablement state saved, it uses their getInitiallyEnabled() method.
     */
    public List<ProjectFeature> getEnabledFeatures() throws CoreException {
        Map<String, Boolean> featureEnablements = loadFeatureEnablements();
        List<ProjectFeature> result = new ArrayList<ProjectFeature>();
        for (ProjectFeature f : getFeatures()) {
            Boolean savedEnabled = featureEnablements.get(f.getId());
            boolean enabled = (savedEnabled != null) ? savedEnabled : f.getInitiallyEnabled();
            if (enabled)
                result.add(f);
        }
        return result;
    }

    protected Map<String, Boolean> loadFeatureEnablements() throws CoreException {
        Map<String, Boolean> result = new HashMap<String, Boolean>();
        Document doc = readXmlFile(getFeatureStatesFile());
        if (doc != null) {
            Element root = doc.getDocumentElement();
            NodeList featureElements = root.getElementsByTagName(ELMNT_FEATURE);
            for (int i = 0; i < featureElements.getLength(); i++) {
                Element e = (Element)featureElements.item(i);
                String id = getAttribute(e, ATT_ID);
                boolean enabled = "true".equals(getAttribute(e, ATT_ENABLED));
                result.put(id, enabled);
            }
        }
        return result;
    }

    /**
     * Stores the enablement state of project features for this project.
     */
    public void saveFeatureEnablement(List<ProjectFeature> enabledFeatures) throws CoreException {
        try {
            // build DOM tree
            DocumentBuilder docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = docBuilder.newDocument();
            Element root = doc.createElement(ELMNT_FEATURES);
            doc.appendChild(root);

            for (ProjectFeature f : getFeatures()) {
                boolean enabled = enabledFeatures.contains(f);
                Element e = doc.createElement(ELMNT_FEATURE);
                e.setAttribute(ATT_ID, f.getId());
                e.setAttribute(ATT_ENABLED, enabled ? "true" : "false");
                root.appendChild(e);
            }

            // save it
            IFile file = getFeatureStatesFile();
            String content = XmlUtils.serialize(doc);
            if (!file.exists())
                file.create(new ByteArrayInputStream(content.getBytes()), IFile.FORCE, null);
            else
                file.setContents(new ByteArrayInputStream(content.getBytes()), IFile.FORCE, null);
        }
        catch (Exception e) {  // catches: ParserConfigurationException,ClassCastException,ClassNotFoundException,InstantiationException,IllegalAccessException
            throw Activator.wrapIntoCoreException("Cannot save project feature enablements: " + e.getMessage(), e);
        }
    }

    /**
     * Saves the defines file.
     */
    public void saveDefinesFile(List<ProjectFeature> enabledFeatures) throws CoreException {
        Map<String, String> defines = collectDefines(enabledFeatures);
        String content = makeDefinesFileContent(defines);

        // save the file if its content differs
        byte[] bytes = content.getBytes();
        if (definesFile != null)
            MakefileTools.ensureFileContent(definesFile, bytes, null);
    }

    protected String makeDefinesFileContent(Map<String, String> defines) {
        StringBuilder sb = new StringBuilder();
        sb.append(DEFINESFILE_BANNER);
        String[] symbols = defines.keySet().toArray(new String[0]);
        Arrays.sort(symbols);
        for (String symbol : symbols) {
            String value = defines.get(symbol);
            sb.append("#ifndef ").append(symbol).append("\n#define ").append(symbol);
            if (value != null)
                sb.append(" ").append(value);
            sb.append("\n#endif\n\n");
        }
        return sb.toString();
    }

    /**
     * Extract and return the defines from the compile flags of the given features.
     */
    protected Map<String, String> collectDefines(List<ProjectFeature> enabledFeatures) throws CoreException {
        Map<String,String> defines = new HashMap<String,String>();
        for (ProjectFeature feature : enabledFeatures) {
            for (String cflag : feature.getCompileFlags().split("\\s+")) {
                if (cflag.startsWith("-D") && cflag.length() > 2) {
                    String symbol = cflag.substring(2).replaceFirst("=.*", "");
                    String value = cflag.contains("=") ? StringUtils.substringAfter(cflag, "=") : null;
                    defines.put(symbol, value);
                }
            }
        }
        return defines;
    }

    /**
     * Populates the object with the contents of the project's feature
     * description file, if one exists. Returns true on success.
     * If the file doesn't exist, it returns false. If the file exists
     * but could not be read or parsed, it throws an exception.
     */
    public boolean loadFeaturesFile() throws CoreException {
        doc = readXmlFile(getFeatureDescriptionFile());
        if (doc == null)
            return false;
        this.features = extractFeatures(doc);
        return true;
    }

    /**
     * Returns the handle for the features description file of the given project.
     */
    public IFile getFeatureDescriptionFile() {
        return project.getFile(PROJECTFEATURES_FILENAME);
    }

    /**
     * Returns the handle for the feature state file of the given project.
     */
    public IFile getFeatureStatesFile() {
        return project.getFile(PROJECTFEATURESTATE_FILENAME);
    }

    /**
     * Reads the given file into an XML document, without interpreting it.
     * Returns null if the file does not exist. Throws CoreException on
     * parse error, file read error, etc.
     */
    protected Document readXmlFile(IFile file) throws CoreException {
        if (!file.exists()) {
            return null;
        }
        else {
            try {
                if (!project.getWorkspace().isTreeLocked())
                    file.refreshLocal(IResource.DEPTH_ZERO, null);
                DocumentBuilder docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
                return docBuilder.parse(file.getContents());
            }
            catch (Exception e) { // catches ParserConfigurationException, SAXException, IOException
                throw Activator.wrapIntoCoreException(file.getFullPath() + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * Extracts the features from the given document. Makes an attempt NOT to throw an exception on any input.
     */
    protected Map<String, ProjectFeature> extractFeatures(Document doc) {
        Map<String, ProjectFeature> result = new LinkedHashMap<String, ProjectFeature>();
        Element root = doc.getDocumentElement();
        cppSourceRoots = root.getAttributeNode(ATT_CPPSOURCEROOTS)==null ? null : getListAttribute(root, ATT_CPPSOURCEROOTS);
        String definesFileName = getAttribute(root, ATT_DEFINESFILE);
        definesFile = StringUtils.isNotEmpty(definesFileName) ? project.getFile(new Path(definesFileName)) : null;
        NodeList featureElements = root.getElementsByTagName(ELMNT_FEATURE);
        int unnamedCount = 0;
        for (int i=0; i<featureElements.getLength(); i++) {
            Element e = (Element)featureElements.item(i);
            String id = getAttribute(e, ATT_ID);
            if (id.equals(""))
                id = "unnamed" + (++unnamedCount);
            ProjectFeature feature = new ProjectFeature(
                    id,
                    getAttribute(e, ATT_NAME),
                    getAttribute(e, ATT_DESCRIPTION),
                    getBooleanAttribute(e, ATT_INITIALLYENABLED, true),
                    getListAttribute(e, ATT_LABELS),
                    getListAttribute(e, ATT_REQUIRES),
                    getListAttribute(e, ATT_NEDPACKAGES),
                    getListAttribute(e, ATT_EXTRASOURCEFOLDERS),
                    getAttribute(e, ATT_COMPILEFLAGS),
                    getAttribute(e, ATT_LINKERFLAGS)
            );
            result.put(feature.getId(), feature);
        }
        return result;
    }

    private Set<String> getListAttribute(Element parent, String name) {
        Set<String> result = new LinkedHashSet<String>();
        String content = getAttribute(parent, name);
        if (StringUtils.isNotBlank(content))
            result.addAll(Arrays.asList(content.split("\\s+")));
        return result;
    }

    private boolean getBooleanAttribute(Element parent, String name, boolean defaultValue) {
        String value = getAttribute(parent, name);
        if (StringUtils.isEmpty(value))
            return defaultValue;
        return value.equals("true");
    }

    private String getAttribute(Element parent, String name) {
        String value = parent.getAttribute(name);
        value = value.trim().replaceAll("\\s+", " ");
        return value;
    }

    /**
     * Validates the feature description file, and returns the list of errors found in it as text.
     */
    public List<String> validateFeatureDescriptions() {
        if (doc == null)
            return new ArrayList<String>();  // no oppfeatures file loaded

        List<String> errors = new ArrayList<String>();

        validateDomTree(errors);

        if (definesFile == null)
            errors.add("Missing or invalid \"" + ATT_DEFINESFILE + "\" attribute on root element, required to specify the header file in which defines (-D) from enabled features are to be generated");

        for (ProjectFeature f : getFeatures())
            validateFeatures(f, errors);

        return errors;
    }

    protected boolean featuresContainDefines() {
        List<String> featureCFlags = getFeatureCFlags(getFeatures());
        for (String cflag : featureCFlags)
            if (cflag.substring(0,2).equals("-D"))
                return true;
        return false;
    }

    protected void validateDomTree(List<String> errors) {
        // check the DOM tree
        Element root = doc.getDocumentElement();
        for (Node child : toArray(root.getAttributes()))
            if (!ArrayUtil.contains(ALL_ROOT_ATTRS, child.getNodeName()))
                errors.add("<" + root.getNodeName() + ">: ignoring unknown attribute \"" + child.getNodeName() + "\"");

        for (Node child : toArray(root.getChildNodes()))
            if (child instanceof Element && !child.getNodeName().equals(ELMNT_FEATURE))
                errors.add("<" + root.getNodeName() + ">: ignoring unknown child element <" + child.getNodeName() + ">");

        // check <feature> elements
        for (Node elem : toArray(root.getElementsByTagName(ELMNT_FEATURE))) {
            Element feature = (Element) elem;
            String id = feature.getAttribute(ATT_ID);
            String prefix = "<" + feature.getNodeName() + " " + ATT_ID + "=\"" + id + "\">: ";

            // check attributes
            if (StringUtils.isEmpty(id))
                errors.add(prefix + ATT_ID + " attribute cannot be empty");
            else if (!ProjectFeature.isValidId(id))
                errors.add(prefix + ATT_ID + " attribute contains invalid characters");
            if (StringUtils.isEmpty(feature.getAttribute(ATT_NAME)))
                errors.add(prefix + ATT_NAME + " attribute cannot be empty");
            String enable = feature.getAttribute(ATT_INITIALLYENABLED);
            if (!StringUtils.isEmpty(enable) && !enable.equals("true") && !enable.equals("false"))
                errors.add(prefix + ATT_INITIALLYENABLED + " attribute must be 'true' or 'false'");

            // check for extra attributes and child elements
            for (Node child : toArray(feature.getAttributes()))
                if (!ArrayUtil.contains(ALL_FEATURE_ATTRS, child.getNodeName()))
                    errors.add(prefix + "ignoring unknown attribute \"" + child.getNodeName() + "\"");
            for (Node child : toArray(feature.getChildNodes()))
                if (child instanceof Element)
                    errors.add(prefix + "ignoring child element <" + child.getNodeName() + ">");
        }
    }

    protected static Node[] toArray(NodeList nodes) {
        Node[] result = new Node[nodes.getLength()];
        for (int i = 0; i < nodes.getLength(); i++)
            result[i] = nodes.item(i);
        return result;
    }

    protected static Node[] toArray(NamedNodeMap nodes) {
        Node[] result = new Node[nodes.getLength()];
        for (int i = 0; i < nodes.getLength(); i++)
            result[i] = nodes.item(i);
        return result;
    }

    protected void validateFeatures(ProjectFeature f, List<String> errors) {
        // check that features mentioned as dependencies really exist
        String prefix = f.getId() + ": ";
        Set<String> unrecognized = new LinkedHashSet<String>();
        for (String dep : f.getDependencies())
            if (!features.containsKey(dep))
                unrecognized.add(dep);
        if (!unrecognized.isEmpty())
            errors.add(prefix + "unknown feature(s) in dependency list: " + StringUtils.join(unrecognized, ", "));

        // check for circular dependencies
        checkForCircularDependencies(f, new Stack<ProjectFeature>(), errors);

        // check compile flags. Note: keep this code in sync with addFeatureCFlagsTo()
        for (String cflag : f.getCompileFlags().split("\\s+")) {
            if (cflag.length()>0 && !cflag.startsWith("-D") && !cflag.startsWith("-I"))
                errors.add(prefix + "unknown compiler option \"" + cflag + "\": only -D and -I are supported here");
            if (cflag.equals("-D") || cflag.equals("-I"))
                errors.add(prefix + "compiler options: arguments of -D and -I are expected to be written together with the option name (i.e. without space)");
        }

        // check linker flags. Note: keep this code in sync with addFeatureLDFlagsTo()
        for (String ldflag : f.getLinkerFlags().split("\\s+")) {
            if (ldflag.length()>0 && !ldflag.startsWith("-l") && !ldflag.startsWith("-L"))
                errors.add(prefix + "unknown linker option \"" + ldflag + "\": only -l and -L are supported here");
            if (ldflag.equals("-l") || ldflag.equals("-L"))
                errors.add(prefix + "linker options: arguments of -l and -L are expected to be written together with the option name (i.e. without space)");
        }

        // check for nonexistent NED packages
        INedResources nedResources = NedResourcesPlugin.getNedResources();
        if (!nedResources.isLoadingInProgress()) {
            for (String pkg : f.getNedPackages()) {
                IContainer[] folders = nedResources.getFoldersForPackage(project, pkg);
                if (folders.length == 0)
                    errors.add(prefix + "no such NED package: " + pkg);
                boolean found = false;
                for (IContainer folder : folders)
                    if (folder.exists())
                        found = true;

                if (!found)
                    errors.add(prefix + "no folder corresponds to NED package " + pkg);
            }
        }

        // check for C++ source folders
        for (String extraSrcFolder : f.getExtraSourceFolders())
            if (!project.getFolder(new Path(extraSrcFolder)).exists())
                errors.add(prefix + "no such folder: \"" + extraSrcFolder + "\"");

        // check that directories in features don't overlap (otherwise exclusion won't work properly)
        for (String pkg : f.getNedPackages())
            for (ProjectFeature f2 : getFeatures())
                if (f != f2)
                    for (String pkg2 : f2.getNedPackages())
                        if (pkg.equals(pkg2) || pkg2.startsWith(pkg+"."))
                            errors.add(prefix + "has overlapping NED packages with " + f2.getId() + " (" + pkg + " vs. " + pkg2 + ")");
        for (IContainer folder : getExtraCxxSourceFolders(f))
            for (ProjectFeature f2 : getFeatures())
                if (f != f2)
                    for (IContainer folder2 : getExtraCxxSourceFolders(f2))
                        if (folder.equals(folder2) || folder.getFullPath().isPrefixOf(folder2.getFullPath()))
                            errors.add(prefix + "has overlapping extra C++ source folders with " + f2.getId() + " (" + folder.getFullPath() + " vs. " + folder2.getFullPath() + ")");
        for (IContainer folder : getNedBasedCxxSourceFolders(f))
            for (ProjectFeature f2 : getFeatures())
                for (IContainer folder2 : getExtraCxxSourceFolders(f2))
                    if (folder.equals(folder2) || folder.getFullPath().isPrefixOf(folder2.getFullPath()) || folder2.getFullPath().isPrefixOf(folder.getFullPath()))
                        errors.add(prefix + "has NED package based C++ source folder overlapping with extra C++ source folder of " + f2.getId() + " (" + folder.getFullPath() + " vs. " + folder2.getFullPath() + ")");
    }

    protected void checkForCircularDependencies(ProjectFeature f, Stack<ProjectFeature> stack, List<String> errors) {
        if (stack.contains(f)) {
            if (stack.get(0) == f) // only report the error for cycle members, not for all that refer to the cycle
                errors.add("Circular dependency: " + StringUtils.join(stack, " -> ") + " -> " + f);
            return;
        }
        stack.push(f);
        for (String id : f.getDependencies())
            if (getFeature(id) != null)
                checkForCircularDependencies(getFeature(id), stack, errors);
        stack.pop();
    }

    /**
     * Return the compile flags from the given features.
     */
    public List<String> getFeatureCFlags(Collection<ProjectFeature> features) {
        List<String> cflags = new ArrayList<String>();
        for (ProjectFeature f : features)
            cflags.addAll(Arrays.asList(f.getCompileFlags().split("\\s+")));
        return cflags;
    }

    /**
     * Return the linker flags from the given features.
     */
    public List<String> getFeatureLDFlags(Collection<ProjectFeature> features) {
        List<String> ldflags = new ArrayList<String>();
        for (ProjectFeature f : features)
            ldflags.addAll(Arrays.asList(f.getLinkerFlags().split("\\s+")));
        return ldflags;
    }

    /**
     * Initializes the project state (CDT, NED) according to the enabled features, using getEnabledFeatures())
     */
    public void initializeProjectState() throws CoreException {
        fixupProjectState();
    }

    /**
     * Updates project configuration (CDT and NED) so that it reflects the currently enabled features.
     */
    public void fixupProjectState() throws CoreException {
        // load NED and CDT configurations
        List<ProjectFeature> enabledFeatures = getEnabledFeatures();
        ICProjectDescription projectDescription = CoreModel.getDefault().getProjectDescription(project);
        ICConfigurationDescription[] configurations = projectDescription!=null ? projectDescription.getConfigurations() : new ICConfigurationDescription[0];
        NedSourceFoldersConfiguration nedSourceFoldersConfig = ProjectUtils.readNedFoldersFile(project);

        // fix them up
        fixupExcludedNedPackages(nedSourceFoldersConfig, enabledFeatures);
        fixupConfigurations(configurations, enabledFeatures);

        // save them
        ProjectUtils.saveNedFoldersFile(project, nedSourceFoldersConfig);
        CoreModel.getDefault().setProjectDescription(project, projectDescription);

        // fix up defines file
        if (definesFile != null)
            saveDefinesFile(enabledFeatures);
    }

    /**
     * Adjusts the excluded NED packages in the given configuration object to
     * reflect the given feature enablement state.
     */
    public void fixupExcludedNedPackages(NedSourceFoldersConfiguration nedSourceFoldersConfig, List<ProjectFeature> enabledFeatures) throws CoreException {
        for (ProjectFeature feature : getFeatures()) {
            boolean enabled = enabledFeatures.contains(feature);
            adjustExcludedNedPackages(feature, enabled, nedSourceFoldersConfig);
        }
    }

    /**
     * Adjusts the given CDT configurations to reflect the given feature enablement state.
     */
    public void fixupConfigurations(ICConfigurationDescription[] configurations, List<ProjectFeature> enabledFeatures) throws CoreException {
        for (ProjectFeature feature : getFeatures()) {
            boolean enabled = enabledFeatures.contains(feature);
            adjustConfigurations(feature, enabled, configurations);
        }

        // since feature macros are now defined in header files, remove them from the configuration
        for (ProjectFeature feature : getFeatures()) {
            for (String cflag : feature.getCompileFlags().split("\\s+")) {
                if (cflag.startsWith("-D") && cflag.length() > 2) {
                    String symbol = cflag.substring(2).replaceAll("=.*", "");
                    if (isMacroSet(project, configurations, symbol))
                        removeMacroInAllConfigurationsAndFoldersAndLanguages(project, configurations, symbol);
                }
            }
        }

    }

    /**
     * Computes and returns the set of features that the given feature directly or indirectly
     * depends on (requires).
     */
    public Set<ProjectFeature> collectDependencies(ProjectFeature feature) {
        Assert.isTrue(getFeature(feature.getId()) == feature, "Alien feature!");
        Set<ProjectFeature> dependencies = new LinkedHashSet<ProjectFeature>();
        dependencies.add(feature); // temporarily

        boolean changed;
        do {
            changed = false;
            for (ProjectFeature f : dependencies.toArray(new ProjectFeature[]{})) {
                for (String id : f.getDependencies()) {
                    ProjectFeature dep = getFeature(id);
                    if (dep != null && !dependencies.contains(dep)) {
                        dependencies.add(dep);
                        changed = true;
                    }
                }
            }
        } while (changed);

        dependencies.remove(feature);

        return dependencies;
    }

    /**
     * Computes and returns the set of features that directly or indirectly depend on (require)
     * the given feature.
     */
    public Set<ProjectFeature> collectDependentFeatures(ProjectFeature feature) {
        Assert.isTrue(getFeature(feature.getId()) == feature, "Alien feature!");
        Set<ProjectFeature> dependentFeatures = new LinkedHashSet<ProjectFeature>();
        dependentFeatures.add(feature); // temporarily

        boolean changed;
        do {
            changed = false;
            for (ProjectFeature f : dependentFeatures.toArray(new ProjectFeature[]{})) {
                for (ProjectFeature dep : getDependentFeatures(f)) {
                    if (!dependentFeatures.contains(dep)) {
                        dependentFeatures.add(dep);
                        changed = true;
                    }
                }
            }
        } while (changed);

        dependentFeatures.remove(feature);

        return dependentFeatures;
    }

    /**
     * Returns the set of features that directly list the given feature among their dependencies.
     */
    public Set<ProjectFeature> getDependentFeatures(ProjectFeature feature) {
        Set<ProjectFeature> result = new LinkedHashSet<ProjectFeature>();
        for (ProjectFeature f : getFeatures())
            if (f.getDependencies().contains(feature.getId()))
                result.add(f);
        return result;
    }

    public void setFeatureEnabledRec(ICConfigurationDescription[] configurations, NedSourceFoldersConfiguration nedSourceFoldersConfig, ProjectFeature feature, boolean enable) throws CoreException {
        setFeatureEnabled(feature, enable, configurations, nedSourceFoldersConfig);
        Set<ProjectFeature> affectedFeatures = enable ? collectDependencies(feature) : collectDependentFeatures(feature);
        for (ProjectFeature f : affectedFeatures)
            setFeatureEnabled(f, enable, configurations, nedSourceFoldersConfig);
    }

    /**
     * Sets the list of enabled features in the given NED and CDT configurations.
     * This method ignores dependencies, i.e. it is possible to create an inconsistent
     * state with it.
     */
    public void setEnabledFeatures(List<ProjectFeature> enabledFeatures, ICConfigurationDescription[] configurations, NedSourceFoldersConfiguration nedSourceFoldersConfig) throws CoreException {
        for (ProjectFeature feature : getFeatures()) {
            boolean enabled = enabledFeatures.contains(feature);
            setFeatureEnabled(feature, enabled, configurations, nedSourceFoldersConfig);
        }
    }

    /**
     * Enables or disables the given feature in the given NED and CDT configurations.
     * This method ignores dependencies, i.e. it is possible to create an inconsistent
     * state with it.
     */
    public void setFeatureEnabled(ProjectFeature feature, boolean enable, ICConfigurationDescription[] configurations, NedSourceFoldersConfiguration nedSourceFoldersConfig) throws CoreException {
        Assert.isTrue(getFeature(feature.getId()) == feature, "Alien feature!");
        Debug.println((enable ? "enabling" : "disabling") + " feature " + feature.getId());

        adjustExcludedNedPackages(feature, enable, nedSourceFoldersConfig);
        adjustConfigurations(feature, enable, configurations);
    }

    /**
     * Update the list of excluded NED packages in the given configuration object
     * according to the feature's enablement state.
     */
    protected void adjustExcludedNedPackages(ProjectFeature feature, boolean enable, NedSourceFoldersConfiguration nedSourceFoldersConfig) {
        Set<String> excludedPackages = new HashSet<String>();
        excludedPackages.addAll(Arrays.asList(nedSourceFoldersConfig.getExcludedPackages()));
        if (enable)
            excludedPackages.removeAll(feature.getNedPackages());
        else
            excludedPackages.addAll(feature.getNedPackages());
        nedSourceFoldersConfig.setExcludedPackages(excludedPackages.toArray(new String[]{}));
    }

    /**
     * Update the given CTD configurations according to the feature's enablement state.
     */
    protected void adjustConfigurations(ProjectFeature feature, boolean enable, ICConfigurationDescription[] configurations) throws CoreException {
        // exclude/include C++ source folders that correspond to the feature's NED packages
        List<IContainer> folders = getAllCxxSourceFolders(feature);
        for (IContainer folder : folders)
            setFolderExcluded(configurations, folder, !enable);
    }

    protected List<IContainer> getAllCxxSourceFolders(ProjectFeature feature) {
        List<IContainer> result = new ArrayList<IContainer>();
        result.addAll(getNedBasedCxxSourceFolders(feature));
        result.addAll(getExtraCxxSourceFolders(feature));
        return result;
    }

    protected List<IContainer> getNedBasedCxxSourceFolders(ProjectFeature feature) {
        // NED packages locations
        List<IContainer> result = new ArrayList<IContainer>();
        INedResources nedResources = NedResourcesPlugin.getNedResources();
        for (String nedPackage: feature.getNedPackages())
            for (IContainer folder : nedResources.getFoldersForPackage(project, nedPackage))
                if (folder.exists() && isCxxSourceFolder(folder))
                    result.add(folder);
        return result;
    }

    protected List<IContainer> getExtraCxxSourceFolders(ProjectFeature feature) {
        // extra C++ source folders
        List<IContainer> result = new ArrayList<IContainer>();
        for (String sourceFolder : feature.getExtraSourceFolders()) {
            IContainer folder = project.getFolder(new Path(sourceFolder));
            if (folder.exists())
                result.add(folder);
        }
        return result;
    }

    protected boolean isCxxSourceFolder(IContainer folder) {
        if (cppSourceRoots == null)
            return true;
        for (String cppSourceRoot : cppSourceRoots)
            if (new Path(cppSourceRoot).isPrefixOf(folder.getProjectRelativePath()))
                return true;
        return false;
    }

    protected static boolean isMacroSet(IProject project, ICConfigurationDescription[] configurations, String name) throws CoreException {
        for (ICConfigurationDescription configuration : configurations) {
            // check it on all source folders
            List<IContainer> sourceLocations = CDTUtils.getSourceLocations(project, configuration.getSourceEntries());
            for (IContainer folder : sourceLocations) {
                ICFolderDescription folderDescription = CDTUtils.getOrCreateFolderDescription(configuration, folder);
                ICLanguageSetting[] folderLanguageSettings = folderDescription.getLanguageSettings();
                for (ICLanguageSetting languageSetting : folderLanguageSettings) {
                    if (languageSetting.supportsEntryKind(ICSettingEntry.MACRO)) {
                        ICLanguageSettingEntry macro = CDTUtils.getMacro(languageSetting, name);
                        if (macro != null)
                            return true;
                    }
                }
            }
            // also check it on the root folder (this setting may not be effective, but it's more discoverable for the user than folder settings)
            ICLanguageSetting[] rootLanguageSettings = configuration.getRootFolderDescription().getLanguageSettings();
            for (ICLanguageSetting languageSetting : rootLanguageSettings) {
                if (languageSetting.supportsEntryKind(ICSettingEntry.MACRO)) {
                    ICLanguageSettingEntry macro = CDTUtils.getMacro(languageSetting, name);
                    if (macro != null)
                        return true;
                }
            }
        }
        return false;
    }

    protected static void removeMacroInAllConfigurationsAndFoldersAndLanguages(IProject project, ICConfigurationDescription[] configurations, String name) throws WriteAccessException, CoreException {
        for (ICConfigurationDescription configuration : configurations) {
            // set it on all source folders
            List<IContainer> sourceLocations = CDTUtils.getSourceLocations(project, configuration.getSourceEntries());
            for (IContainer folder : sourceLocations) {
                ICFolderDescription folderDescription = CDTUtils.getOrCreateFolderDescription(configuration, folder);
                ICLanguageSetting[] folderLanguageSettings = folderDescription.getLanguageSettings();
                for (ICLanguageSetting languageSetting : folderLanguageSettings)
                    if (languageSetting.supportsEntryKind(ICSettingEntry.MACRO))
                        CDTUtils.setMacro(languageSetting, name, null);
            }
            // also set it on the root folder (this setting may not be effective, but it's more discoverable for the user than folder settings)
            ICLanguageSetting[] rootLanguageSettings = configuration.getRootFolderDescription().getLanguageSettings();
            for (ICLanguageSetting languageSetting : rootLanguageSettings)
                if (languageSetting.supportsEntryKind(ICSettingEntry.MACRO))
                    CDTUtils.setMacro(languageSetting, name, null);
        }
    }

    protected void setFolderExcluded(ICConfigurationDescription[] configurations, IContainer folder, boolean exclude) throws CoreException {
        if (!folder.exists())
            return;
        for (ICConfigurationDescription configuration : configurations) {
            ICSourceEntry[] newEntries = CDTUtils.setExcluded(folder, exclude, configuration.getSourceEntries());
            configuration.setSourceEntries(newEntries);
        }
    }

    protected Boolean isFolderExcluded(ICConfigurationDescription[] configurations, IContainer folder) throws CoreException {
        Assert.isTrue(folder.exists());
        int excludeCount = 0, includeCount = 0;
        for (ICConfigurationDescription configuration : configurations) {
            if (CDTUtils.isExcluded(folder, configuration.getSourceEntries()))
                excludeCount++;
            else
                includeCount++;
        }
        return (excludeCount>0 && includeCount>0) ? null /*misc*/ : excludeCount>0;
    }

    protected static String joinNamesOf(Collection<ProjectFeature> features) {
        List<String> names = new ArrayList<String>();
        for (ProjectFeature f : features)
            names.add(f.getName());
        return StringUtils.join(names, ", ", " and ");
    }

    public static class Problem {
        public ProjectFeature feature;
        public String message;
        public String toString() {return (feature==null ? "" : "\"" + feature.getName() + "\": ") + message;}
    }

    public List<Problem> validateProjectState() throws CoreException {
        // load NED and CDT configuration
        ICProjectDescription projectDescription = CoreModel.getDefault().getProjectDescription(project);
        ICConfigurationDescription[] configurations = projectDescription!=null ? projectDescription.getConfigurations() : new ICConfigurationDescription[0];
        NedSourceFoldersConfiguration nedSourceFoldersConfig = ProjectUtils.readNedFoldersFile(project);

        // return list of problems
        return validateProjectState(configurations, nedSourceFoldersConfig, getEnabledFeatures());
    }

    /**
     * Checks that the project state, as represented by the configurations and nedSouerceFoldersConfig arguments,
     * corresponds to enablement state of the project features, that is. It checks that NED folders and C++ source folders
     * covered by the features are correctly enabled/disabled, symbols are defined, etc. Errors are returned
     * in a string list.
     */
    protected List<Problem> validateProjectState(ICConfigurationDescription[] configurations, NedSourceFoldersConfiguration nedSourceFoldersConfig, List<ProjectFeature> enabledFeatures) throws CoreException {
        //dumpMacros(project, configurations, "WITH_.*");

        List<Problem> problems = new ArrayList<Problem>();
        List<String> excludedPackages = Arrays.asList(nedSourceFoldersConfig.getExcludedPackages());

        for (ProjectFeature feature : getFeatures()) {
            boolean enabled = enabledFeatures.contains(feature);

            // check NED excluded folders
            if (enabled) {
                // feature is enabled: make sure its NED packages are NOT excluded
                //FIXME check for prefixes not equality!!! i.e. if feature contains "inet.netw.ipv6", then neither "inet.netw" nor "inet.netw.ipv6.util" should be excluded!
                @SuppressWarnings("unchecked")
                Collection<String> wronglyExcludedPackages = CollectionUtils.intersection(excludedPackages, feature.getNedPackages());
                if (!wronglyExcludedPackages.isEmpty())
                    addProblem(problems, feature, "Feature is enabled but some of its NED packages are excluded: " + StringUtils.join(wronglyExcludedPackages, ", "));
            }
            else {
                @SuppressWarnings("unchecked")
                Collection<String> wronglyIncludedPackages = CollectionUtils.subtract(feature.getNedPackages(), excludedPackages);
                if (!wronglyIncludedPackages.isEmpty())
                    addProblem(problems, feature, "Feature is disabled but some of its NED packages are not excluded: " + StringUtils.join(wronglyIncludedPackages, ", "));
            }


            // check exclusion state of C++ source folders that correspond to the feature's NED packages
            List<IContainer> folders = getAllCxxSourceFolders(feature);
            for (IContainer folder : folders) {
                Boolean isFolderExcluded = isFolderExcluded(configurations, folder); // null if mixed
                if (enabled) {
                    if (isFolderExcluded==null || isFolderExcluded)
                        addProblem(problems, feature, "Feature is enabled but folder " + folder.getFullPath() + " is excluded from C++ build in at least one configuration");
                }
                else {
                    if (isFolderExcluded==null || !isFolderExcluded)
                        addProblem(problems, feature, "Feature is disabled but folder " + folder.getFullPath() + " is not excluded from C++ build in at least one configuration");
                }
            }
        }

        // warn if the Paths&Symbols page has some of the defines mentioned in the oppfeatures file
        outerloop:
        for (ProjectFeature feature : getFeatures()) {
            for (String cflag : feature.getCompileFlags().split("\\s+")) {
                if (cflag.startsWith("-D") && cflag.length() > 2) {
                    String symbol = cflag.substring(2).replaceAll("=.*", "");
                    if (isMacroSet(project, configurations, symbol)) {
                        addProblem(problems, null, "Feature macro " + symbol + " and possibly others are set on the Paths & Symbols page.");
                        break outerloop;
                    }
                }
            }
        }

        // check that the defines file has the right content
        if (definesFile != null) {
            Map<String, String> defines = collectDefines(enabledFeatures);
            String content = makeDefinesFileContent(defines);

            try {
                if (!definesFile.exists())
                    addProblem(problems, null, "Missing defines file " + definesFile.getFullPath());
                else if (!areDefinesFilesEqual(FileUtils.readTextFile(definesFile.getContents(), definesFile.getCharset()), content))
                    addProblem(problems, null, "Generated defines file is out of date: " + definesFile.getFullPath());
            }
            catch (IOException e) {
                throw Activator.wrapIntoCoreException(e);
            }
        }

        return problems;
    }

    protected boolean areDefinesFilesEqual(String content1, String content2) {
        // make comparison insensitive to comment, whitespace and order differences
        String short1 = shortenDefinesFile(content1);
        String short2 = shortenDefinesFile(content2);
        return short1.equals(short2);
    }

    protected String shortenDefinesFile(String content) {
        content += "\n";
        content = content.replaceAll("\r\n", "\n");  // unix lines
        content = content.replaceAll("//.*\n", "");  // remove comments
        content = content.replaceAll("\n\n+", "\n"); // remove blank lines
        content = content.replaceAll("[ \t]", " ");  // remove multiple spaces
        content = content.replaceAll(" *# *", "#");  // remove spaces from preprocessor directives
        content = content.replaceAll("#ifndef .*\n#define (.*)\n#endif\n+", "#define_if_new $1\n");
        String[] lines = content.split("\n");
        Arrays.sort(lines);
        content = StringUtils.join(lines, "\n");
        return content;
    }

    protected void addProblem(List<Problem> problems, ProjectFeature feature, String message) {
        Problem problem = new Problem();
        problem.feature = feature;
        problem.message = message;
        problems.add(problem);
    }

    @Override
    public String toString() {
        return StringUtils.join(getFeatureIds(), ",");
    }
}
