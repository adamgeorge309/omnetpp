package org.omnetpp.scave.editors.datatable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.text.WordUtils;
import org.eclipse.core.runtime.Assert;
import org.eclipse.swt.graphics.Image;
import org.omnetpp.common.Debug;
import org.omnetpp.common.engine.BigDecimal;
import org.omnetpp.common.image.ImageFactory;
import org.omnetpp.common.util.StringUtils;
import org.omnetpp.scave.ScaveImages;
import org.omnetpp.scave.ScavePlugin;
import org.omnetpp.scave.editors.ui.ScaveUtil;
import org.omnetpp.scave.engine.DoubleVector;
import org.omnetpp.scave.engine.FileRun;
import org.omnetpp.scave.engine.Histogram;
import org.omnetpp.scave.engine.HistogramResult;
import org.omnetpp.scave.engine.IDList;
import org.omnetpp.scave.engine.IDListBuffer;
import org.omnetpp.scave.engine.IDListsByFile;
import org.omnetpp.scave.engine.IDListsByRun;
import org.omnetpp.scave.engine.ParameterResult;
import org.omnetpp.scave.engine.ResultFile;
import org.omnetpp.scave.engine.ResultFileList;
import org.omnetpp.scave.engine.ResultFileManager;
import org.omnetpp.scave.engine.ResultItem;
import org.omnetpp.scave.engine.Run;
import org.omnetpp.scave.engine.RunList;
import org.omnetpp.scave.engine.ScalarResult;
import org.omnetpp.scave.engine.Scave;
import org.omnetpp.scave.engine.Statistics;
import org.omnetpp.scave.engine.StatisticsResult;
import org.omnetpp.scave.engine.StringMap;
import org.omnetpp.scave.engine.StringVector;
import org.omnetpp.scave.engine.VectorResult;
import org.omnetpp.scave.engineext.ResultFileManagerEx;

/**
 * This is the content provider used with the DataTree widget.
 *
 * This class provides a customizable tree of various data from the result file manager.
 * The tree is built up from levels that may be freely reordered and switched on/off.
 * Levels include experiment, measurement, replication, config, run number, file name, run id, module path, module name, result item, result item attributes, etc.
 *
 * @author levy
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class DataTreeContentProvider {
    public final static Class[] LEVELS1 = new Class[] { ExperimentNode.class, MeasurementNode.class, ReplicationNode.class, ModulePathNode.class, ResultItemNode.class, ResultItemFieldOrAttributeNode.class};
    public final static Class[] LEVELS2 = new Class[] { ExperimentMeasurementReplicationNode.class, ModulePathNode.class, ResultItemNode.class, ResultItemFieldOrAttributeNode.class};
    public final static Class[] LEVELS3 = new Class[] { ConfigNode.class, RunNumberNode.class, ModulePathNode.class, ResultItemNode.class, ResultItemFieldOrAttributeNode.class};
    public final static Class[] LEVELS4 = new Class[] { ConfigRunNumberNode.class, ModulePathNode.class, ResultItemNode.class, ResultItemFieldOrAttributeNode.class};
    public final static Class[] LEVELS5 = new Class[] { FileNameNode.class, RunIdNode.class, ModulePathNode.class, ResultItemNode.class, ResultItemFieldOrAttributeNode.class};
    public final static Class[] LEVELS6 = new Class[] { RunIdNode.class, ModulePathNode.class, ResultItemNode.class, ResultItemFieldOrAttributeNode.class};

    public final static int GROUPSIZE = 1000;

    private static boolean debug = false;

    protected ResultFileManagerEx manager;

    protected IDList inputIdList;

    protected Class<? extends Node>[] levels;

    protected int numericPrecision = 6;

    protected Node[] rootNodes;

    public DataTreeContentProvider() {
        setDefaultLevels();
    }

    public void setResultFileManager(ResultFileManagerEx manager) {
        this.manager = manager;
        rootNodes = null;
    }

    public void setIDList(IDList idList) {
        this.inputIdList = idList;
        rootNodes = null;
    }

    public Class<? extends Node>[] getLevels() {
        return levels;
    }

    public void setLevels(Class<? extends Node>[] levels) {
        if (debug)
            Debug.println("setLevels(): " + levels);
        this.levels = levels;
        rootNodes = null;
    }

    public void setDefaultLevels() {
        setLevels(LEVELS2);
    }

    public void setNumericPrecision(int numericPrecision) {
        this.numericPrecision = numericPrecision; // note: corresponding refresh() call is in DataTree
    }

    public int getNumericPrecision() {
        return numericPrecision;
    }

    public Node[] getChildNodes(List<Node> path) {
        if (manager == null || inputIdList == null)
            return new Node[0];

        // note: path is actually reverse path, i.e. root comes last
        Node parentNode = path.size() == 0 ? null : path.get(0);

        // cache
        Node[] cachedChildren = parentNode == null ? rootNodes : parentNode.children;
        if (cachedChildren != null)
            return cachedChildren;

        // not cached, compute
        Node[] children = ResultFileManager.callWithReadLock(manager, () -> computeChildren(path));
        if (children.length > GROUPSIZE)
            children = makeGroups(children);

        // update cache
        if (parentNode == null)
            rootNodes = children;
        else
            parentNode.children = children;

        return children;
    }

    public Node[] makeGroups(Node[] children) {
        List<Node> groups = new ArrayList<>();
        Debug.time("Making groups", 10, () -> {
            for (int pos = 0; pos < children.length; pos += GROUPSIZE) {
                int end = Math.min(pos+GROUPSIZE, children.length);
                String label = "[" + pos + ".." + (end-1) + "]";
                GroupNode group = new GroupNode(label, children[pos].getImage());
                group.children = Arrays.copyOfRange(children, pos, end);
                IDListBuffer groupIds = new IDListBuffer();
                for (Node child : group.children)
                    groupIds.append(child.ids);
                group.ids = groupIds.toIDList();
                groups.add(group);
            }
        });
        return groups.toArray(new Node[0]);
    }

    protected Node[] computeChildren(List<Node> path) throws Exception {
        Node firstNode = path.size() == 0 ? null : path.get(0); // actually, the parent node whose children we are computing

        // determine currentLevelIndex
        int currentLevelIndex;
        if (firstNode == null)
            currentLevelIndex = -1;
        else {
            currentLevelIndex = ArrayUtils.indexOf(levels, firstNode.getClass());
            if (currentLevelIndex == -1)
                return new Node[0];
        }

        // determine nextLevelIndex
        int nextLevelIndex;
        if (firstNode instanceof ModuleNameNode) {
            ModuleNameNode moduleNameNode = (ModuleNameNode)firstNode;
            nextLevelIndex = currentLevelIndex + (moduleNameNode.leaf ? 1 : 0);
        }
        else
            nextLevelIndex = currentLevelIndex + 1;

        // determine collector, whatever TF it is
        boolean collector = false;
        for (int j = nextLevelIndex + 1; j < levels.length; j++)
            if (!levels[j].equals(ResultItemFieldOrAttributeNode.class))
                collector = true;

        // this is how we want the IDList to split up
        Class<? extends Node> nextLevelClass = nextLevelIndex < levels.length ? levels[nextLevelIndex] : null;
        if (nextLevelClass == null)
            return new Node[0];

        // sort the IDs into different child nodes, according to nextLevelClass
        IDList currentLevelIdList = firstNode == null ? inputIdList : firstNode.ids;
        Map<Node, IDListBuffer> nodeIdsMap = sortIdListToChildNodes(path, currentLevelIdList, nextLevelClass, collector);

        // get nodes[] from keyset, sort if necessary
        Node[] nodes = nodeIdsMap.keySet().toArray(new Node[0]);
        boolean shouldSort = !nextLevelClass.equals(ResultItemFieldOrAttributeNode.class);
        if (shouldSort) {
            Arrays.sort(nodes, new Comparator<Node>() {
                public int compare(Node o1, Node o2) {
                    return StringUtils.dictionaryCompare((o1).getColumnText(0), (o2).getColumnText(0));
                }
            });
        }

        // fill in ids[], value
        for (Node node : nodes) {
            node.ids = nodeIdsMap.get(node).toIDList();
            // add quick value if applicable
            if (node.ids.size() == 1 && !collector && StringUtils.isEmpty(node.value) &&
                (!(node instanceof ModuleNameNode) || ((ModuleNameNode)node).leaf))
            {
                ResultItem resultItem = manager.getItem(node.ids.get(0));
                node.value = getResultItemShortDescription(resultItem);
            }
        }
        return nodes;
    }

    protected Map<Node, IDListBuffer> sortIdListToChildNodes(List<Node> path, IDList idList, Class<? extends Node> nextLevelClass, boolean collector) {
        int idCount = idList.size();
        Map<Node,IDListBuffer> nodeIdsMap = new LinkedHashMap<>(); // preserve insertion order of children

        Class[] runFilterClasses = {
                ExperimentNode.class, MeasurementNode.class, ReplicationNode.class, ExperimentMeasurementReplicationNode.class,
                ConfigNode.class, RunNumberNode.class, ConfigRunNumberNode.class, RunIdNode.class
        };

        if (ArrayUtils.contains(runFilterClasses, nextLevelClass)) {
            // filter only refers to run attributes, so it makes sense to filter the runs instead of individual IDs
            IDListsByRun idListsByRun = Debug.timed("getPartitionByRun", 1, () -> manager.getPartitionByRun(idList));
            RunList runList = idListsByRun.getRuns();
            int numRuns = (int)runList.size();
            Debug.time("Classifying runs", 1, () -> {
                for (int i = 0 ; i < numRuns; i++) {
                    Run run = runList.get(i);
                    IDList idsInRun = idListsByRun.getIDList(run);
                    if (nextLevelClass.equals(ExperimentNode.class))
                        add(nodeIdsMap, new ExperimentNode(run.getAttribute(Scave.EXPERIMENT)), idsInRun);
                    else if (nextLevelClass.equals(MeasurementNode.class))
                        add(nodeIdsMap, new MeasurementNode(run.getAttribute(Scave.MEASUREMENT)), idsInRun);
                    else if (nextLevelClass.equals(ReplicationNode.class))
                        add(nodeIdsMap, new ReplicationNode(run.getAttribute(Scave.REPLICATION)), idsInRun);
                    else if (nextLevelClass.equals(ExperimentMeasurementReplicationNode.class))
                        add(nodeIdsMap, new ExperimentMeasurementReplicationNode(run.getAttribute(Scave.EXPERIMENT), run.getAttribute(Scave.MEASUREMENT), run.getAttribute(Scave.REPLICATION)), idsInRun);
                    else if (nextLevelClass.equals(ConfigNode.class))
                        add(nodeIdsMap, new ConfigNode(run.getAttribute(Scave.CONFIGNAME)), idsInRun);
                    else if (nextLevelClass.equals(RunNumberNode.class))
                        add(nodeIdsMap, new RunNumberNode(run.getAttribute(Scave.RUNNUMBER)), idsInRun);
                    else if (nextLevelClass.equals(ConfigRunNumberNode.class))
                        add(nodeIdsMap, new ConfigRunNumberNode(run.getAttribute(Scave.CONFIGNAME), run.getAttribute(Scave.RUNNUMBER)), idsInRun);
                    else if (nextLevelClass.equals(RunIdNode.class))
                        add(nodeIdsMap, new RunIdNode(run.getRunName()), idsInRun);
                }
            });

        }
        else if (nextLevelClass.equals(FileNameNode.class)) {
            // partition by file
            IDListsByFile idListsByFile = Debug.timed("getPartitionByFile", 1, () -> manager.getPartitionByFile(idList));
            ResultFileList fileList = idListsByFile.getFiles();
            int numFiles = (int)fileList.size();
            Debug.time("Classifying files", 1, () -> {
                for (int i = 0 ; i < numFiles; i++) {
                    ResultFile file = fileList.get(i);
                    IDList idsInFile = idListsByFile.getIDList(file);
                    add(nodeIdsMap, new FileNameNode(file.getFileName()), idsInFile);
                }
            });
        }
        else {
            // filter individual IDs
            for (int i = 0; i < idCount; i++) {
                long id = idList.get(i);
                MatchContext matchContext = new MatchContext(manager, id);
                if (nextLevelClass.equals(FileNameRunIdNode.class)) // this one should be done by partitioning by FileRuns, but we don't care, this isn't a very useful classification
                    add(nodeIdsMap, new FileNameRunIdNode(matchContext.getResultFile().getFileName(), matchContext.getRun().getRunName()), id);
                else if (nextLevelClass.equals(ModuleNameNode.class)) {
                    String moduleName = matchContext.getResultItem().getModuleName();
                    String modulePrefix = getModulePrefix(path, null);
                    if (moduleName.startsWith(modulePrefix)) {
                        String remainingName = StringUtils.removeStart(StringUtils.removeStart(moduleName, modulePrefix), ".");
                        String name = StringUtils.substringBefore(remainingName, ".");
                        add(nodeIdsMap, new ModuleNameNode(StringUtils.isEmpty(name) ? "." : name, !remainingName.contains(".")), id);
                    }
                }
                else if (nextLevelClass.equals(ModulePathNode.class))
                    add(nodeIdsMap, new ModulePathNode(matchContext.getResultItem().getModuleName()), id);
                else if (nextLevelClass.equals(ResultItemNode.class)) {
                    if (collector)
                        add(nodeIdsMap, new ResultItemNode(manager, -1, matchContext.getResultItem().getName()), id);
                    else
                        add(nodeIdsMap, new ResultItemNode(manager, id, null), id);
                }
                else if (nextLevelClass.equals(ResultItemFieldOrAttributeNode.class)) {
                    ResultItem resultItem = matchContext.getResultItem();
                    ResultItem.DataType type = resultItem.getDataType();
                    boolean isIntegerType = type == ResultItem.DataType.TYPE_INT;
                    add(nodeIdsMap, new ResultItemFieldOrAttributeNode("Module name", resultItem.getModuleName()), id);
                    if (type != ResultItem.DataType.TYPE_NA)
                        add(nodeIdsMap, new ResultItemFieldOrAttributeNode("Type", type.toString().replaceAll("TYPE_", "").toLowerCase()), id);
                    if (resultItem instanceof ScalarResult) {
                        ScalarResult scalar = (ScalarResult)resultItem;
                        add(nodeIdsMap, new ResultItemFieldOrAttributeNode("Value", toIntegerAwareString(scalar.getValue(), isIntegerType)), id);
                    }
                    else if (resultItem instanceof VectorResult) {
                        VectorResult vector = (VectorResult)resultItem;
                        Statistics stat = vector.getStatistics();
                        add(nodeIdsMap, new ResultItemFieldOrAttributeNode("Count", String.valueOf(stat.getCount())), id);
                        add(nodeIdsMap, new ResultItemFieldOrAttributeNode("Mean", formatNumber(stat.getMean())), id);
                        add(nodeIdsMap, new ResultItemFieldOrAttributeNode("StdDev", formatNumber(stat.getStddev())), id);
                        //add(nodeIdsMap, new ResultItemAttributeNode("Variance", formatNumber(stat.getVariance())), id);
                        add(nodeIdsMap, new ResultItemFieldOrAttributeNode("Min", toIntegerAwareString(stat.getMin(), isIntegerType)), id);
                        add(nodeIdsMap, new ResultItemFieldOrAttributeNode("Max", toIntegerAwareString(stat.getMax(), isIntegerType)), id);
                        add(nodeIdsMap, new ResultItemFieldOrAttributeNode("Start event number", String.valueOf(vector.getStartEventNum())), id);
                        add(nodeIdsMap, new ResultItemFieldOrAttributeNode("End event number", String.valueOf(vector.getEndEventNum())), id);
                        add(nodeIdsMap, new ResultItemFieldOrAttributeNode("Start time", formatNumber(vector.getStartTime())), id);
                        add(nodeIdsMap, new ResultItemFieldOrAttributeNode("End time", formatNumber(vector.getEndTime())), id);
                    }
                    else if (resultItem instanceof StatisticsResult) {
                        StatisticsResult statistics = (StatisticsResult)resultItem;
                        Statistics stat = statistics.getStatistics();
                        add(nodeIdsMap, new ResultItemFieldOrAttributeNode("Kind", (stat.isWeighted() ? "weighted" : "unweighted")), id);
                        add(nodeIdsMap, new ResultItemFieldOrAttributeNode("Count", String.valueOf(stat.getCount())), id);
                        add(nodeIdsMap, new ResultItemFieldOrAttributeNode("Sum of weights", formatNumber(stat.getSumWeights())), id);
                        add(nodeIdsMap, new ResultItemFieldOrAttributeNode("Mean", formatNumber(stat.getMean())), id);
                        add(nodeIdsMap, new ResultItemFieldOrAttributeNode("StdDev", formatNumber(stat.getStddev())), id);
                        add(nodeIdsMap, new ResultItemFieldOrAttributeNode("Min", toIntegerAwareString(stat.getMin(), isIntegerType)), id);
                        add(nodeIdsMap, new ResultItemFieldOrAttributeNode("Max", toIntegerAwareString(stat.getMax(), isIntegerType)), id);

                        if (resultItem instanceof HistogramResult) {
                            HistogramResult histogram = (HistogramResult)resultItem;
                            Histogram hist = histogram.getHistogram();

                            add(nodeIdsMap, new ResultItemFieldOrAttributeNode("Underflows", toIntegerAwareString(hist.getUnderflows(), isIntegerType)), id);
                            add(nodeIdsMap, new ResultItemFieldOrAttributeNode("Overflows", toIntegerAwareString(hist.getOverflows(), isIntegerType)), id);

                            DoubleVector binEdges = hist.getBinEdges();
                            DoubleVector binValues = hist.getBinValues();
                            if (binEdges.size() > 0 && binValues.size() > 0) {
                                int numBins = hist.getNumBins();
                                ResultItemFieldOrAttributeNode binsNode = new ResultItemFieldOrAttributeNode("Bins", String.valueOf(numBins));
                                List<Node> list = new ArrayList<Node>();
                                for (int j = 0; j < numBins; j++) {
                                    double lowerBound = binEdges.get(j);
                                    double upperBound = binEdges.get(j+1);
                                    double value = binValues.get(j);
                                    String name = "[" + toIntegerAwareString(lowerBound, isIntegerType) + ", ";
                                    if (isIntegerType)
                                        name += toIntegerAwareString(upperBound-1, isIntegerType) + "]";
                                    else
                                        name += formatNumber(upperBound) + ")";
                                    list.add(new NameValueNode(name, toIntegerAwareString(value, true)));
                                }
                                binsNode.children = list.toArray(new Node[0]);
                                add(nodeIdsMap, binsNode, id);
                            }
                        }
                    }
                    else if (resultItem instanceof ParameterResult) {
                        ParameterResult parameter = (ParameterResult)resultItem;
                        add(nodeIdsMap, new ResultItemFieldOrAttributeNode("Value", parameter.getValue()), id);
                    }
                    else {
                        throw new IllegalArgumentException();
                    }
                    StringMap attributes = resultItem.getAttributes();
                    StringVector keys = attributes.keys();
                    for (int j = 0; j < keys.size(); j++) {
                        String key = keys.get(j);
                        add(nodeIdsMap, new ResultItemFieldOrAttributeNode(StringUtils.capitalize(key), attributes.get(key)), id);
                    }
                }
                else
                    throw new IllegalArgumentException();
            }
        }
        return nodeIdsMap;
    }

    private static void add(Map<Node,IDListBuffer> map, Node key, long value) {
        IDListBuffer ids = map.get(key);
        if (ids == null)
            map.put(key, ids = new IDListBuffer());
        ids.add(value);
    }

    private static void add(Map<Node,IDListBuffer> map, Node key, IDList values) {
        IDListBuffer ids = map.get(key);
        if (ids == null)
            map.put(key, ids = new IDListBuffer());
        ids.append(values);
    }

    protected String toIntegerAwareString(double value, boolean isIntegerType) {
        if (!isIntegerType || Double.isInfinite(value) || Double.isNaN(value) || Math.floor(value) != value)
            return formatNumber(value);
        else
            return String.valueOf((long)value);
    }

    protected String formatNumber(double d) {
        return ScaveUtil.formatNumber(d, getNumericPrecision());
    }

    protected String formatNumber(BigDecimal d) {
        return ScaveUtil.formatNumber(d, getNumericPrecision());
    }

    protected static String getModulePrefix(final List<Node> path, Node nodeLimit) {
        StringBuffer modulePrefix = new StringBuffer();
        for (int i =  path.size() - 1; i >= 0; i--) {
            Node node = path.get(i);
            if (node == nodeLimit)
                break;
            String name = null;
            if (node instanceof ModuleNameNode)
                name = ((ModuleNameNode)node).name;
            else if (node instanceof ModulePathNode)
                name = ((ModulePathNode)node).path;
            if (name != null) {
                if (modulePrefix.length() == 0)
                    modulePrefix.append(name);
                else {
                    modulePrefix.append('.');
                    modulePrefix.append(name);
                }
            }
        }
        return modulePrefix.toString();
    }

    protected String getResultItemShortDescription(ResultItem resultItem) {
        if (resultItem instanceof ScalarResult) {
            ScalarResult scalar = (ScalarResult)resultItem;
            return formatNumber(scalar.getValue());
        }
        else if (resultItem instanceof VectorResult) {
            VectorResult vector = (VectorResult)resultItem;
            Statistics stat = vector.getStatistics();
            return formatNumber(stat.getMean()) + " (" + String.valueOf(stat.getCount()) + ")";
        }
        else if (resultItem instanceof HistogramResult) {  // note: should precede StatisticsResult branch
            HistogramResult histogram = (HistogramResult)resultItem;
            Statistics stat = histogram.getStatistics();
            Histogram hist = histogram.getHistogram();
            return formatNumber(stat.getMean()) + " (" + String.valueOf(stat.getCount()) + ") [" + (hist.getNumBins()) + " bins]";
        }
        else if (resultItem instanceof StatisticsResult) {
            StatisticsResult histogram = (StatisticsResult)resultItem;
            Statistics stat = histogram.getStatistics();
            return formatNumber(stat.getMean()) + " (" + String.valueOf(stat.getCount()) + ")";
        }
        else if (resultItem instanceof ParameterResult) {
            ParameterResult parameter = (ParameterResult)resultItem;
            return parameter.getValue();
        }
        else
            throw new IllegalArgumentException();
    }

    protected static String getResultItemReadableClassName(ResultItem resultItem) {
        return resultItem.getClass().getSimpleName().replaceAll("Result", "").toLowerCase();
    }

    /**
     * This class apparently doesn't do much, it just caches the results of various getters.
     */
    protected static class MatchContext {
        private ResultFileManager manager;
        private long id;
        private ResultItem resultItem;
        private FileRun fileRun;
        private ResultFile resultFile;
        private Run run;

        public MatchContext(ResultFileManager manager, long id) {
            this.manager = manager;
            this.id = id;
        }

        public String getRunAttribute(String key) {
            return StringUtils.defaultString(manager.getRunAttribute(id, key), "?");
        }

        public ResultItem getResultItem() {
            if (resultItem == null)
                resultItem = manager.getItem(id);
            return resultItem;
        }
        public FileRun getFileRun() {
            if (fileRun == null)
                fileRun = getResultItem().getFileRun();
            return fileRun;
        }

        public ResultFile getResultFile() {
            if (resultFile == null)
                resultFile = getFileRun().getFile();
            return resultFile;
        }
        public Run getRun() {
            if (run == null)
                run = getFileRun().getRun();
            return run;
        }
    }

    protected boolean matchesPath(List<Node> path, long id, MatchContext matchContext) {
        for (Node node : path)
            if (!node.matches(path, id, matchContext))
                return false;
        return true;
    }

    /* Various tree node types */

    public static Class[] getAvailableLevelClasses() {
        return new Class[] {
            ExperimentNode.class,
            MeasurementNode.class,
            ReplicationNode.class,
            ExperimentMeasurementReplicationNode.class,
            ConfigNode.class,
            RunNumberNode.class,
            ConfigRunNumberNode.class,
            FileNameNode.class,
            RunIdNode.class,
            FileNameRunIdNode.class,
            ModulePathNode.class,
            ModuleNameNode.class,
            ResultItemNode.class,
            ResultItemFieldOrAttributeNode.class};
    }

    private static final Map<Class,String> LEVELNAMES = new HashMap<>();

    static {
        LEVELNAMES.put(ExperimentNode.class, "Experiment");
        LEVELNAMES.put(MeasurementNode.class, "Measurement");
        LEVELNAMES.put(ReplicationNode.class, "Replication");
        LEVELNAMES.put(ExperimentMeasurementReplicationNode.class, "Experiment + Measurement + Replication");
        LEVELNAMES.put(ConfigNode.class, "Config");
        LEVELNAMES.put(RunNumberNode.class, "Run Number");
        LEVELNAMES.put(ConfigRunNumberNode.class, "Config + Run Number");
        LEVELNAMES.put(FileNameNode.class, "File");
        LEVELNAMES.put(RunIdNode.class, "Run Id");
        LEVELNAMES.put(FileNameRunIdNode.class, "File + Run Id");
        LEVELNAMES.put(ModulePathNode.class, "Module Path");
        LEVELNAMES.put(ModuleNameNode.class, "Module Name");
        LEVELNAMES.put(ResultItemNode.class, "Result Item");
        LEVELNAMES.put(ResultItemFieldOrAttributeNode.class, "Result Item Attribute");
    }

    public static String getLevelName(Class levelClass) {
        return LEVELNAMES.get(levelClass);
    }

    protected static abstract class Node {
        public IDList ids;
        public Node[] children;
        public String value = "";

        public boolean isExpandedByDefault() {
            return false;
        }

        public abstract Image getImage();

        public abstract String getColumnText(int index);

        public abstract boolean matches(List<Node> path, long id, MatchContext matchContext);
    }

    public static class GroupNode extends Node {
        public Image image;
        public String label; // index range, actually

        public GroupNode(String label, Image image) {
            this.label = label;
            this.image = image;
        }

        @Override
        public String getColumnText(int index) {
            return index == 0 ? label : "";
        }

        public Image getImage() {
            return image;
        }

        @Override
        public boolean matches(List<Node> path, long id, MatchContext matchContext) {
            return false; //TODO
        }

        @Override
        public int hashCode() {
            return label.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            GroupNode other = (GroupNode) obj;
            return label.equals(other.label);
        }
    }

    public static class NameValueNode extends Node {
        public String name;

        public NameValueNode(String name, String value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public String getColumnText(int index) {
            return index == 0 ? name : value;
        }

        @Override
        public boolean matches(List<Node> path, long id, MatchContext matchContext) {
            return true;
        }

        @Override
        public Image getImage() {
            return ImageFactory.global().getIconImage(ImageFactory.TOOLBAR_IMAGE_PROPERTIES);
        }

        @Override
        public int hashCode() {
            return 31*name.hashCode() + value.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            NameValueNode other = (NameValueNode) obj;
            return name.equals(other.name) && value.equals(other.value);
        }
    }

    public static class ExperimentNode extends Node {
        public String name;

        public ExperimentNode(String name) {
            this.name = name;
        }

        @Override
        public String getColumnText(int index) {
            return index == 0 ? name + " (experiment)" : value;
        }

        public Image getImage() {
            return ScavePlugin.getCachedImage(ScaveImages.IMG_OBJ16_EXPERIMENT);
        }

        @Override
        public boolean matches(List<Node> path, long id, MatchContext matchContext) {
            return name.equals(matchContext.getRun().getAttribute(Scave.EXPERIMENT));
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ExperimentNode other = (ExperimentNode) obj;
            return name.equals(other.name);
        }
    }

    public static class MeasurementNode extends Node {
        public String name;

        public MeasurementNode(String name) {
            this.name = name;
        }

        @Override
        public String getColumnText(int index) {
            return index == 0 ? StringUtils.defaultIfEmpty(name, "default")  + " (measurement)" : value;
        }

        public Image getImage() {
            return ScavePlugin.getCachedImage(ScaveImages.IMG_OBJ16_MEASUREMENT);
        }

        @Override
        public boolean matches(List<Node> path, long id, MatchContext matchContext) {
            return name.equals(matchContext.getRunAttribute(Scave.MEASUREMENT));
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            MeasurementNode other = (MeasurementNode) obj;
            return name.equals(other.name);
        }
    }

    public static class ReplicationNode extends Node {
        public String name;

        public ReplicationNode(String name) {
            this.name = name;
        }

        @Override
        public String getColumnText(int index) {
            return index == 0 ? name + " (replication)" : value;
        }

        public Image getImage() {
            return ScavePlugin.getCachedImage(ScaveImages.IMG_OBJ16_REPLICATION);
        }

        @Override
        public boolean matches(List<Node> path, long id, MatchContext matchContext) {
            return name.equals(matchContext.getRunAttribute(Scave.REPLICATION));
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ReplicationNode other = (ReplicationNode) obj;
            return name.equals(other.name);
        }
    }

    public static class ExperimentMeasurementReplicationNode extends Node {
        public String experiment;

        public String measurement;

        public String replication;

        public ExperimentMeasurementReplicationNode(String experiment, String measurement, String replication) {
            this.experiment = experiment;
            this.measurement = measurement;
            this.replication = replication;
        }

        @Override
        public String getColumnText(int index) {
            return index == 0 ? experiment + (StringUtils.isEmpty(measurement) ? "" : " : " + measurement) + " : " + replication : value;
        }

        public Image getImage() {
            return ScavePlugin.getCachedImage(ScaveImages.IMG_OBJ16_RUN);
        }

        @Override
        public boolean matches(List<Node> path, long id, MatchContext matchContext) {
            return experiment.equals(matchContext.getRunAttribute(Scave.EXPERIMENT)) && measurement.equals(matchContext.getRunAttribute(Scave.MEASUREMENT)) && replication.equals(matchContext.getRunAttribute(Scave.REPLICATION));
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((experiment == null) ? 0 : experiment.hashCode());
            result = prime * result + ((measurement == null) ? 0 : measurement.hashCode());
            result = prime * result + ((replication == null) ? 0 : replication.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ExperimentMeasurementReplicationNode other = (ExperimentMeasurementReplicationNode) obj;
            if (experiment == null) {
                if (other.experiment != null)
                    return false;
            }
            else if (!experiment.equals(other.experiment))
                return false;
            if (measurement == null) {
                if (other.measurement != null)
                    return false;
            }
            else if (!measurement.equals(other.measurement))
                return false;
            if (replication == null) {
                if (other.replication != null)
                    return false;
            }
            else if (!replication.equals(other.replication))
                return false;
            return true;
        }
    }

    public static class ConfigNode extends Node {
        public String name;

        public ConfigNode(String name) {
            this.name = name;
        }

        @Override
        public String getColumnText(int index) {
            return index == 0 ? name + " (config)" : value;
        }

        public Image getImage() {
            return ScavePlugin.getCachedImage(ScaveImages.IMG_OBJ16_CONFIGURATION);
        }

        @Override
        public boolean matches(List<Node> path, long id, MatchContext matchContext) {
            return matchContext.getRunAttribute(Scave.CONFIGNAME).equals(name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ConfigNode other = (ConfigNode) obj;
            return name.equals(other.name);
        }
    }

    public static class RunNumberNode extends Node {
        public String runNumber;

        public RunNumberNode(String runNumber) {
            this.runNumber = runNumber;
        }

        @Override
        public String getColumnText(int index) {
            return index == 0 ? runNumber + " (run number)" : value;
        }

        public Image getImage() {
            return ScavePlugin.getCachedImage(ScaveImages.IMG_OBJ16_RUNNUMBER);
        }

        @Override
        public boolean matches(List<Node> path, long id, MatchContext matchContext) {
            return matchContext.getRunAttribute(Scave.RUNNUMBER).equals(runNumber);
        }

        @Override
        public int hashCode() {
            return runNumber.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            RunNumberNode other = (RunNumberNode) obj;
            return runNumber.equals(other.runNumber);
        }

    }

    public static class ConfigRunNumberNode extends Node {
        public String config;
        public String runNumber;

        public ConfigRunNumberNode(String config, String runNumber) {
            this.config = config;
            this.runNumber = runNumber;
        }

        @Override
        public String getColumnText(int index) {
            return index == 0 ? config + " - #" + runNumber : value;
        }

        public Image getImage() {
            return ScavePlugin.getCachedImage(ScaveImages.IMG_OBJ16_RUN);
        }

        @Override
        public boolean matches(List<Node> path, long id, MatchContext matchContext) {
            return matchContext.getRunAttribute(Scave.CONFIGNAME).equals(config) && matchContext.getRunAttribute(Scave.RUNNUMBER).equals(runNumber);
        }

        @Override
        public int hashCode() {
            return 31*config.hashCode() + runNumber.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ConfigRunNumberNode other = (ConfigRunNumberNode) obj;
            if (config == null) {
                if (other.config != null)
                    return false;
            } else if (!config.equals(other.config))
                return false;
            if (runNumber == null) {
                if (other.runNumber != null)
                    return false;
            } else if (!runNumber.equals(other.runNumber))
                return false;
            return true;
        }

    }

    public static class FileNameNode extends Node {
        public String fileName;

        public FileNameNode(String name) {
            this.fileName = name;
        }

        @Override
        public String getColumnText(int index) {
            return index == 0 ? fileName + " (file name)" : value;
        }

        public Image getImage() {
            return ScavePlugin.getCachedImage(fileName.endsWith(".vec") ? ScaveImages.IMG_VECFILE : ScaveImages.IMG_SCAFILE);
        }

        @Override
        public boolean matches(List<Node> path, long id, MatchContext matchContext) {
            return matchContext.getResultFile().getFileName().equals(fileName);
        }

        @Override
        public int hashCode() {
            return fileName.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            FileNameNode other = (FileNameNode) obj;
            return fileName.equals(other.fileName);
        }
    }

    public static class RunIdNode extends Node {
        public String runId;

        public RunIdNode(String runId) {
            this.runId = runId;
        }

        @Override
        public String getColumnText(int index) {
            return index == 0 ? runId + " (run id)" : value;
        }

        public Image getImage() {
            return ScavePlugin.getCachedImage(ScaveImages.IMG_OBJ16_RUN);
        }

        @Override
        public boolean matches(List<Node> path, long id, MatchContext matchContext) {
            return matchContext.getRun().getRunName().equals(runId);
        }

        @Override
        public int hashCode() {
            return runId.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            RunIdNode other = (RunIdNode) obj;
            return runId.equals(other.runId);
        }
    }

    public static class FileNameRunIdNode extends Node {
        public String fileName;

        public String runId;

        public FileNameRunIdNode(String fileName, String runId) {
            this.fileName = fileName;
            this.runId = runId;
        }

        @Override
        public String getColumnText(int index) {
            return index == 0 ? fileName + " : " + runId : value;
        }

        public Image getImage() {
            return ScavePlugin.getCachedImage(ScaveImages.IMG_OBJ16_RUN);
        }

        @Override
        public boolean matches(List<Node> path, long id, MatchContext matchContext) {
            return matchContext.getResultFile().getFileName().equals(fileName) && matchContext.getRun().getRunName().equals(runId);
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((fileName == null) ? 0 : fileName.hashCode());
            result = prime * result + ((runId == null) ? 0 : runId.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            FileNameRunIdNode other = (FileNameRunIdNode) obj;
            if (fileName == null) {
                if (other.fileName != null)
                    return false;
            }
            else if (!fileName.equals(other.fileName))
                return false;
            if (runId == null) {
                if (other.runId != null)
                    return false;
            }
            else if (!runId.equals(other.runId))
                return false;
            return true;
        }
    }

    public static class ModulePathNode extends Node {
        public String path;

        public ModulePathNode(String path) {
            this.path = path;
        }

        @Override
        public String getColumnText(int index) {
            return index == 0 ? path : value;
        }

        @Override
        public boolean matches(List<Node> path, long id, MatchContext matchContext) {
            return matchContext.getResultItem().getModuleName().equals(this.path);
        }

        @Override
        public Image getImage() {
            return ImageFactory.global().getIconImage(ImageFactory.MODEL_IMAGE_SIMPLEMODULE);
        }

        @Override
        public int hashCode() {
            return path.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ModulePathNode other = (ModulePathNode) obj;
            return path.equals(other.path);
        }
    }

    public static class ModuleNameNode extends Node {
        public String name;

        public boolean leaf;

        public ModuleNameNode(String name, boolean leaf) {
            this.name = name;
            this.leaf = leaf;
        }

        @Override
        public String getColumnText(int index) {
            return index == 0 ? name : value;
        }

        @Override
        public boolean matches(List<Node> path, long id, MatchContext matchContext) {
            String modulePrefix = getModulePrefix(path, this);
            modulePrefix = StringUtils.isEmpty(modulePrefix) ? name : modulePrefix + "." + name;
            return matchContext.getResultItem().getModuleName().startsWith(modulePrefix);
        }

        @Override
        public Image getImage() {
            return ImageFactory.global().getIconImage(ImageFactory.MODEL_IMAGE_SIMPLEMODULE);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ModuleNameNode other = (ModuleNameNode) obj;
            return name.equals(other.name);
        }
    }

    public class ResultItemNode extends Node {  // note: non-static because it needs to call getResultItemShortDescription()
        public ResultFileManager manager;

        public long id;

        public String name;

        public ResultItemNode(ResultFileManager manager, long id, String name) {
            Assert.isTrue(id != -1 || name != null);
            this.manager = manager;
            this.id = id;
            this.name = name;
        }

        @Override
        public String getColumnText(int index) {
            if (name != null) {
                return index == 0 ? name : value;
            }
            else {
                ResultItem resultItem = manager.getItem(id);
                return index == 0 ? resultItem.getName() + " (" + getResultItemReadableClassName(resultItem) + ")" : getResultItemShortDescription(resultItem);
            }
        }

        @Override
        public boolean matches(List<Node> path, long id, MatchContext matchContext) {
            if (name != null)
                return matchContext.getResultItem().getName().equals(name);
            else
                return this.id == id;
        }

        @Override
        public Image getImage() {
            if (name != null) {
                int allType = ids.getItemTypes();
                if (allType == ResultFileManager.SCALAR)
                    return ScavePlugin.getCachedImage(ScaveImages.IMG_OBJ16_SCALAR);
                else if (allType == ResultFileManager.VECTOR)
                    return ScavePlugin.getCachedImage(ScaveImages.IMG_OBJ16_VECTOR);
                else if (allType == ResultFileManager.STATISTICS)
                    return ScavePlugin.getCachedImage(ScaveImages.IMG_OBJ16_STATISTIC);
                else if (allType == ResultFileManager.HISTOGRAM)
                    return ScavePlugin.getCachedImage(ScaveImages.IMG_OBJ16_HISTOGRAM);
                else if (allType == ResultFileManager.PARAMETER)
                    return ImageFactory.global().getIconImage(ImageFactory.TOOLBAR_IMAGE_PROPERTIES); // TODO: which icon? new one?
                else
                    return ImageFactory.global().getIconImage(ImageFactory.MODEL_IMAGE_FOLDER);
            }
            else {
                ResultItem resultItem = manager.getItem(id);
                if (resultItem instanceof ScalarResult)
                    return ScavePlugin.getCachedImage(ScaveImages.IMG_OBJ16_SCALAR);
                else if (resultItem instanceof VectorResult)
                    return ScavePlugin.getCachedImage(ScaveImages.IMG_OBJ16_VECTOR);
                else if (resultItem instanceof HistogramResult)
                    return ScavePlugin.getCachedImage(ScaveImages.IMG_OBJ16_HISTOGRAM);
                else if (resultItem instanceof StatisticsResult)
                    return ScavePlugin.getCachedImage(ScaveImages.IMG_OBJ16_STATISTIC);
                else if (resultItem instanceof ParameterResult)
                    return ImageFactory.global().getIconImage(ImageFactory.TOOLBAR_IMAGE_PROPERTIES); // TODO: which icon? new one?
                else
                    return ImageFactory.global().getIconImage(ImageFactory.MODEL_IMAGE_FOLDER);
            }
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + (int) (id ^ (id >>> 32));
            result = prime * result + ((name == null) ? 0 : name.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ResultItemNode other = (ResultItemNode) obj;
            if (id != other.id)
                return false;
            if (name == null) {
                if (other.name != null)
                    return false;
            }
            else if (!name.equals(other.name))
                return false;
            return true;
        }
    }

    public static class ResultItemFieldOrAttributeNode extends NameValueNode {
        private String methodName;

        public ResultItemFieldOrAttributeNode(String name, String value) {
            super(name, value);
            methodName = "get" + WordUtils.capitalize(name.toLowerCase()).replaceAll(" ", "");
        }

        public static String getLevelName() {
            return "Result Item Attribute";
        }

        @Override
        public boolean matches(List<Node> path, long id, MatchContext matchContext) {
            try {
                ResultItem resultItem = matchContext.getResultItem();
                Method method = resultItem.getClass().getMethod(methodName);
                return value.equals(String.valueOf(method.invoke(resultItem)));
            }
            catch (Exception e) {
                return false;
            }
        }
    }
}
