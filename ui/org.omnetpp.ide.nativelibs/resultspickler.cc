//=========================================================================
//  RESULTSPICKLER.CC - part of
//                  OMNeT++/OMNEST
//           Discrete System Simulation in C++
//=========================================================================

/*--------------------------------------------------------------*
  Copyright (C) 1992-2020 Andras Varga
  Copyright (C) 2006-2020 OpenSim Ltd.

  This file is distributed WITHOUT ANY WARRANTY. See the file
  `license' for details on this and other legal matters.
*--------------------------------------------------------------*/

#include "resultspickler.h"
#include "shmmanager.h"

#include "common/stringutil.h"
#include "common/stlutil.h"
#include "sharedmemory.h"
#include "scave/xyarray.h"
#include "scave/vectorutils.h"
#include "scave/memoryutils.h"

using namespace omnetpp::common;

namespace omnetpp {
namespace scave {

std::pair<ShmSendBufferPtr, ShmSendBufferPtr> ResultsPickler::readVectorIntoShm(const ID& id, double simTimeStart, double simTimeEnd)
{
    size_t memoryLimitBytes = getSizeLimit();
    IDList vectorList(id);

    std::vector<XYArray *> vectorData = readVectorsIntoArrays(rfm, vectorList, false, false, memoryLimitBytes, simTimeStart, simTimeEnd, interrupted);
    // ASSERT(vectorData.size() == 1);
    auto array = std::unique_ptr<XYArray>(vectorData[0]);
    size_t size = array->length() * sizeof(double);

    ShmSendBufferPtr xBuffer = shmManager->create("vecx", size, false);
    ShmSendBufferPtr yBuffer = shmManager->create("vecy", size, false);

    memcpy(xBuffer->getAddress(), array->xs.data(), size);
    memcpy(yBuffer->getAddress(), array->ys.data(), size);

    array.release();
    malloc_trim(); // so the std::vector buffers (in array) are released to the operating system

    return {xBuffer,yBuffer};
}

size_t ResultsPickler::getSizeLimit()
{
    // we'll need about 1-2x the size of the pickle to unpack it in addition...
    return getAvailableMemoryBytes() / 3;
}

void ResultsPickler::pickleResultAttrs(Pickler& p, const IDList& resultIDs, bool onlyUnit)
{
    p.startList();

    ScalarResult buffer;
    for (int i = 0; i < resultIDs.size(); ++i) {
        const ResultItem *result = rfm->getItem(resultIDs.get(i), buffer);
        const StringMap& attrs = result->getAttributes();

        auto begin = attrs.begin();
        auto end = attrs.end();

        if (onlyUnit) {
            begin = attrs.find("unit");
            end = begin;
            if (begin != attrs.end())
                ++end;
        }

        for (auto it = begin; it != end; ++it) {
            const auto &a = *it;

            p.startTuple();

            p.pushString(result->getRun()->getRunName());
            p.pushString(result->getModuleName());
            p.pushString(result->getName());
            p.pushString(a.first); // attrname
            p.pushString(a.second); // attrvalue

            p.endTuple();
        }

        if ((i & 0xFF) == 0 && interrupted->flag)
            throw std::runtime_error("Result attribute pickling interrupted");
    }

    p.endList();
}

std::vector<ShmSendBufferPtr> ResultsPickler::getCsvResultsPickle(const char *filterExpression, std::vector<std::string> rowTypes, bool omitUnusedColumns, bool includeFieldsAsScalars, double simTimeStart, double simTimeEnd)
{
    if (opp_isempty(filterExpression))
        return getCsvResultsPickle(IDList(), rowTypes, omitUnusedColumns, simTimeStart, simTimeEnd);
    else {
        IDList allResults = rfm->getAllItems(includeFieldsAsScalars); // filter may match field scalars as well
        IDList results = rfm->filterIDList(allResults, filterExpression);
        return getCsvResultsPickle(IDList(), rowTypes, omitUnusedColumns, simTimeStart, simTimeEnd);
    }
}


std::vector<ShmSendBufferPtr> ResultsPickler::getCsvResultsPickle(const IDList& results, std::vector<std::string> rowTypes, bool omitUnusedColumns, double simTimeStart, double simTimeEnd)
{
    bool addRunAttrs, addIterVars, addConfigEntries, addScalars, addVectors, addStatistics, addHistograms, addParams, addAttrs;

    addRunAttrs = contains(rowTypes, std::string("runattr"));
    addIterVars = contains(rowTypes, std::string("itervar"));
    addConfigEntries = contains(rowTypes, std::string("config"));

    addScalars = contains(rowTypes, std::string("scalar"));
    addVectors = contains(rowTypes, std::string("vector"));
    addStatistics = contains(rowTypes, std::string("statistic"));
    addHistograms = contains(rowTypes, std::string("histogram"));
    addParams = contains(rowTypes, std::string("param"));

    addAttrs = contains(rowTypes, std::string("attr"));

    int resultTypesToAdd =
        (addScalars    ? ResultFileManager::SCALAR     : 0) |
        (addVectors    ? ResultFileManager::VECTOR     : 0) |
        (addStatistics ? ResultFileManager::STATISTICS : 0) |
        (addHistograms ? ResultFileManager::HISTOGRAM  : 0) |
        (addParams     ? ResultFileManager::PARAMETER  : 0);


    ShmSendBufferPtrVector buffers; // to store the vector data buffers
    buffers.push_back(nullptr); // to be overwritten by the pickle itself
    ShmPickler p(shmManager->create("results", 0, true), getSizeLimit());

    p.protocol();

    // not all tuples in the list will have all the elements though. we still have to pad them "in the middle",
    // so all data falls into the right column, but the rest will be filled in by numpy after unpickling
    p.startList();

    p.startTuple();
    for (auto header : {"runID", "type", "module", "name", "attrname", "attrvalue", "value",
                        "count", "sumweights", "mean", "stddev", "min", "max", "underflows", "overflows",
                        "binedges", "binvalues", "vectime", "vecvalue"})
        p.pushString(header);
    p.endTuple();

    // pickle runs of results

    RunList runs = rfm->getUniqueRuns(results);

    // is the order of this right, or should we sort the rows by type?
    for (Run *r : runs) {
        if (addRunAttrs) {
            // Run attributes
            const StringMap& attrs = r->getAttributes();

            for (auto a : attrs) {
                p.startTuple();

                p.pushString(r->getRunName());
                p.pushString("runattr");
                p.pushNone(); // module
                p.pushNone(); // name
                p.pushString(a.first); // attrname
                p.pushString(a.second); // attrvalue

                p.endTuple();
            }
        }

        if (addIterVars) {
            // Iteration variables
            const StringMap &itervars = r->getIterationVariables();

            for (auto iv : itervars) {
                p.startTuple();

                p.pushString(r->getRunName());
                p.pushString("itervar");
                p.pushNone(); // module
                p.pushNone(); // name
                p.pushString(iv.first); // attrname
                p.pushString(iv.second); // attrvalue

                //for (int j = 0; j < 13; ++j)
                //    p.pushNone();

                p.endTuple();
            }
        }

        if (addConfigEntries) {
            // Config entries
            const OrderedKeyValueList &entries = r->getConfigEntries();
            for (auto e : entries) {

                p.startTuple();

                p.pushString(r->getRunName());
                p.pushString("config");
                p.pushNone(); // module
                p.pushNone(); // name
                p.pushString(e.first); // attrname
                p.pushString(e.second); // attrvalue

                //for (int j = 0; j < 13; ++j)
                //    p.pushNone();

                p.endTuple();
            }
        }

        if (interrupted->flag)
            throw std::runtime_error("Result pickling interrupted");
    }

    // end of run data pickling

    ScalarResult buffer;
    for (const ID& id : results) {
        const ResultItem *result = rfm->getItem(id, buffer);
        auto type = result->getItemType();

        if (resultTypesToAdd & type) {

            p.startTuple();

            p.pushString(result->getRun()->getRunName());
            switch (type) {
                case ResultFileManager::PARAMETER:  p.pushString("param");     break; // parameter?
                case ResultFileManager::SCALAR:     p.pushString("scalar");    break;
                case ResultFileManager::VECTOR:     p.pushString("vector");    break;
                case ResultFileManager::STATISTICS: p.pushString("statistic"); break; // statistics?
                case ResultFileManager::HISTOGRAM:  p.pushString("histogram"); break;
            }

            p.pushString(result->getModuleName());
            p.pushString(result->getName());
            p.pushNone(); // attrname
            p.pushNone(); // attrvalue

            switch (type) {
                case ResultFileManager::PARAMETER: {
                    const ParameterResult *parameter = rfm->getParameter(id);
                    p.pushString(parameter->getValue());
                    break;
                }
                case ResultFileManager::SCALAR: {
                    const ScalarResult *scalar = rfm->getScalar(id, buffer);
                    p.pushDouble(scalar->getValue());
                    break;
                }
                case ResultFileManager::VECTOR: {
                    // value, count, sumweights, mean, stddev, min, max, underflows, overflows, binedges, binvalues,
                    for (int j = 0; j < 11; ++j)
                        p.pushNone();

                    auto shmBuffers = readVectorIntoShm(id, simTimeStart, simTimeEnd);

                    p.pushInt(buffers.size());
                    buffers.push_back(shmBuffers.first); // vectime
                    p.pushInt(buffers.size());
                    buffers.push_back(shmBuffers.second); // vecvalue

                    break;
                }
                case ResultFileManager::STATISTICS: {
                    const Statistics& statistics = rfm->getStatistics(id)->getStatistics();

                    p.pushNone(); // value column (for scalars/parameters)

                    p.pushDouble(statistics.getCount());
                    p.pushDouble(statistics.getSumWeights());
                    p.pushDouble(statistics.getMean());
                    p.pushDouble(statistics.getStddev());
                    p.pushDouble(statistics.getMin());
                    p.pushDouble(statistics.getMax());

                    break;
                }
                case ResultFileManager::HISTOGRAM: {
                    const HistogramResult *histogramResult = rfm->getHistogram(id);

                    const Statistics& statistics = histogramResult->getStatistics();
                    const Histogram& histogram = histogramResult->getHistogram();

                    p.pushNone(); // value column (for scalars/parameters)

                    p.pushDouble(statistics.getCount());
                    p.pushDouble(statistics.getSumWeights());
                    p.pushDouble(statistics.getMean());
                    p.pushDouble(statistics.getStddev());
                    p.pushDouble(statistics.getMin());
                    p.pushDouble(statistics.getMax());

                    p.pushDouble(histogram.getUnderflows());
                    p.pushDouble(histogram.getOverflows());

                    p.pushDoublesAsRawBytes(histogram.getBinEdges());
                    p.pushDoublesAsRawBytes(histogram.getBinValues());

                    break;
                }
            }

            p.endTuple();
        }


        if (addAttrs) {
            const StringMap &attrs = result->getAttributes();

            for (const auto& a : attrs) {
                p.startTuple();

                p.pushString(result->getRun()->getRunName());
                p.pushString("attr");
                p.pushString(result->getModuleName());
                p.pushString(result->getName());
                p.pushString(a.first); // attrname
                p.pushString(a.second); // attrvalue

                p.endTuple();
            }
        }

        if (interrupted->flag)
            throw std::runtime_error("Result pickling interrupted");
    }

    p.endList();

    p.stop();

    buffers[0] = p.getBuffer();
    return buffers;
}

ShmSendBufferPtr ResultsPickler::getScalarsPickle(const char *filterExpression, bool includeAttrs, bool includeFields)
{
    if (opp_isempty(filterExpression))
        return getScalarsPickle(IDList(), includeAttrs);
    else {
        IDList allScalars = rfm->getAllScalars(includeFields); // filter may match field scalars as well
        IDList scalars = rfm->filterIDList(allScalars, filterExpression, -1, interrupted);
        return getScalarsPickle(scalars, includeAttrs);
    }
}

std::vector<ShmSendBufferPtr> ResultsPickler::getVectorsPickle(const char *filterExpression, bool includeAttrs, double simTimeStart, double simTimeEnd)
{
    if (opp_isempty(filterExpression))
        return getVectorsPickle(IDList(), includeAttrs, simTimeStart, simTimeEnd);
    else {
        IDList allVectors = rfm->getAllVectors();
        IDList vectors = rfm->filterIDList(allVectors, filterExpression, -1, interrupted);
        return getVectorsPickle(vectors, includeAttrs, simTimeStart, simTimeEnd);
    }
}

ShmSendBufferPtr ResultsPickler::getStatisticsPickle(const char *filterExpression, bool includeAttrs)
{
    if (opp_isempty(filterExpression))
        return getStatisticsPickle(IDList(), includeAttrs);
    else {
        IDList allStatistics = rfm->getAllStatistics();
        IDList statistics = rfm->filterIDList(allStatistics, filterExpression, -1, interrupted);
        return getStatisticsPickle(statistics, includeAttrs);
    }
}

ShmSendBufferPtr ResultsPickler::getHistogramsPickle(const char *filterExpression, bool includeAttrs)
{
    if (opp_isempty(filterExpression))
        return getHistogramsPickle(IDList(), includeAttrs);
    else {
        IDList allHistograms = rfm->getAllHistograms();
        IDList histograms = rfm->filterIDList(allHistograms, filterExpression, -1, interrupted);
        return getHistogramsPickle(histograms, includeAttrs);
    }
}

ShmSendBufferPtr ResultsPickler::getParamValuesPickle(const char *filterExpression, bool includeAttrs)
{
    if (opp_isempty(filterExpression))
        return getParamValuesPickle(IDList(), includeAttrs);
    else {
        IDList allParamValues = rfm->getAllParameters();
        IDList paramValues = rfm->filterIDList(allParamValues, filterExpression, -1, interrupted);
        return getParamValuesPickle(paramValues, includeAttrs);
    }
}

ShmSendBufferPtr ResultsPickler::getScalarsPickle(const IDList& scalars, bool includeAttrs)
{
    size_t sizeLimit = getSizeLimit();
    ShmPickler p(shmManager->create("scalars", 0, true), sizeLimit);

    p.protocol();

    p.startList();

    ScalarResult buffer;
    for (int i = 0; i < scalars.size(); ++i) {
        const ScalarResult *result = rfm->getScalar(scalars.get(i), buffer);
        p.startTuple();

        p.pushString(result->getRun()->getRunName());
        p.pushString(result->getModuleName());
        p.pushString(result->getName());
        p.pushDouble(result->getValue());

        p.endTuple();

        if ((i & 0xFF) == 0 && interrupted->flag)
            throw std::runtime_error("Result pickling interrupted");
    }

    p.endList();

    if (!scalars.isEmpty())
        pickleResultAttrs(p, scalars, !includeAttrs);
    else
        p.pushNone();

    p.tuple2();

    p.stop();

    return p.getBuffer();
}


std::vector<ShmSendBufferPtr> ResultsPickler::getVectorsPickle(const IDList& vectors, bool includeAttrs, double simTimeStart, double simTimeEnd)
{
    size_t sizeLimit = getSizeLimit();
    ShmPickler p(shmManager->create("vectors", 0, true), sizeLimit);

    ShmSendBufferPtrVector buffers; // to store the vector data buffers
    buffers.push_back(nullptr); // to be overwritten by the pickle itself

    p.protocol();

    p.startList();

    for (int i = 0; i < vectors.size(); ++i) {
        const VectorResult *result = rfm->getVector(vectors.get(i));
        p.startTuple();

        p.pushString(result->getRun()->getRunName());
        p.pushString(result->getModuleName());
        p.pushString(result->getName());

        auto shmBuffers = readVectorIntoShm(vectors.get(i), simTimeStart, simTimeEnd);

        p.pushInt(buffers.size());
        buffers.push_back(shmBuffers.first); // vectime
        p.pushInt(buffers.size());
        buffers.push_back(shmBuffers.second); // vecvalue

        p.endTuple();

        if ((i & 0xFF) == 0 && interrupted->flag)
            throw std::runtime_error("Result pickling interrupted");
    }

    p.endList();

    if (!vectors.isEmpty())
        pickleResultAttrs(p, vectors, !includeAttrs);
    else
        p.pushNone();

    p.tuple2();

    p.stop();

    buffers[0] = p.getBuffer();
    return buffers;
}


ShmSendBufferPtr ResultsPickler::getParamValuesPickle(const IDList& params, bool includeAttrs)
{
    size_t sizeLimit = getSizeLimit();
    ShmPickler p(shmManager->create("paramvalues", 0, true), sizeLimit);

    p.protocol();

    p.startList();

    for (int i = 0; i < params.size(); ++i) {
        const ParameterResult *result = rfm->getParameter(params.get(i));
        p.startTuple();

        p.pushString(result->getRun()->getRunName());
        p.pushString(result->getModuleName());
        p.pushString(result->getName());
        p.pushString(result->getValue());

        p.endTuple();

        if ((i & 0xFF) == 0 && interrupted->flag)
            throw std::runtime_error("Result pickling interrupted");
    }

    p.endList();

    if (!params.isEmpty())
        pickleResultAttrs(p, params, !includeAttrs);
    else
        p.pushNone();

    p.tuple2();

    p.stop();

    return p.getBuffer();
}


ShmSendBufferPtr ResultsPickler::getStatisticsPickle(const IDList& statistics, bool includeAttrs)
{
    size_t sizeLimit = getSizeLimit();

    ShmPickler p(shmManager->create("statistics", 0, true), sizeLimit);

    p.protocol();

    p.startList();

    for (int i = 0; i < statistics.size(); ++i) {
        const StatisticsResult *result = rfm->getStatistics(statistics.get(i));

        const Statistics& statistics = result->getStatistics();

        p.startTuple();

        p.pushString(result->getRun()->getRunName());
        p.pushString(result->getModuleName());
        p.pushString(result->getName());

        p.pushDouble(statistics.getCount());
        p.pushDouble(statistics.getSumWeights());
        p.pushDouble(statistics.getMean());
        p.pushDouble(statistics.getStddev());
        p.pushDouble(statistics.getMin());
        p.pushDouble(statistics.getMax());

        p.endTuple();

        if ((i & 0xFF) == 0 && interrupted->flag)
            throw std::runtime_error("Result pickling interrupted");
    }

    p.endList();

    if (!statistics.isEmpty())
        pickleResultAttrs(p, statistics, !includeAttrs);
    else
        p.pushNone();

    p.tuple2();

    p.stop();

    return p.getBuffer();
}


ShmSendBufferPtr ResultsPickler::getHistogramsPickle(const IDList& histograms, bool includeAttrs)
{
    size_t sizeLimit = getSizeLimit();
    ShmPickler p(shmManager->create("histograms", 0, true), sizeLimit);

    p.protocol();

    p.startList();

    for (int i = 0; i < histograms.size(); ++i) {
        const HistogramResult *result = rfm->getHistogram(histograms.get(i));

        const Statistics& statistics = result->getStatistics();
        const Histogram& histogram = result->getHistogram();

        p.startTuple();

        p.pushString(result->getRun()->getRunName());
        p.pushString(result->getModuleName());
        p.pushString(result->getName());

        p.pushDouble(statistics.getCount());
        p.pushDouble(statistics.getSumWeights());
        p.pushDouble(statistics.getMean());
        p.pushDouble(statistics.getStddev());
        p.pushDouble(statistics.getMin());
        p.pushDouble(statistics.getMax());

        p.pushDouble(histogram.getUnderflows());
        p.pushDouble(histogram.getOverflows());

        p.pushDoublesAsRawBytes(histogram.getBinEdges());
        p.pushDoublesAsRawBytes(histogram.getBinValues());

        p.endTuple();

        if ((i & 0xFF) == 0 && interrupted->flag)
            throw std::runtime_error("Result pickling interrupted");
    }

    p.endList();

    if (!histograms.isEmpty())
        pickleResultAttrs(p, histograms, !includeAttrs);
    else
        p.pushNone();

    p.tuple2();

    p.stop();

    return p.getBuffer();
}

ShmSendBufferPtr ResultsPickler::getRunsPickle(const char *filterExpression)
{
    RunList runs = opp_isempty(filterExpression) ? RunList() : rfm->filterRunList(rfm->getRuns(), filterExpression);
    return getRunsPickle(runs);
}

ShmSendBufferPtr ResultsPickler::getRunattrsPickle(const char *filterExpression)
{
    RunAndValueList runAttrs = opp_isempty(filterExpression) ? RunAndValueList() : rfm->getMatchingRunattrs(rfm->getRuns(), filterExpression);
    return getRunattrsPickle(runAttrs);
}

ShmSendBufferPtr ResultsPickler::getItervarsPickle(const char *filterExpression)
{
    RunAndValueList itervars = opp_isempty(filterExpression) ? RunAndValueList() : rfm->getMatchingItervars(rfm->getRuns(), filterExpression);
    return getItervarsPickle(itervars);
}

ShmSendBufferPtr ResultsPickler::getConfigEntriesPickle(const char *filterExpression)
{
    RunAndValueList configEntries = opp_isempty(filterExpression) ? RunAndValueList() : rfm->getMatchingConfigEntries(rfm->getRuns(), filterExpression);
    return getConfigEntriesPickle(configEntries);
}

ShmSendBufferPtr ResultsPickler::getParamAssignmentsPickle(const char *filterExpression)
{
    RunAndValueList paramAssignments = opp_isempty(filterExpression) ? RunAndValueList() : rfm->getMatchingParamAssignmentConfigEntries(rfm->getRuns(), filterExpression);
    return getParamAssignmentsPickle(paramAssignments);
}

ShmSendBufferPtr ResultsPickler::getRunsPickle(const RunList& runs)
{
    size_t sizeLimit = getSizeLimit();
    ShmPickler p(shmManager->create("runs", 0, true), sizeLimit);

    p.protocol();

    p.startList();

    for (const auto &r : runs)
        p.pushString(r->getRunName());

    p.endList();

    p.stop();

    return p.getBuffer();
}


ShmSendBufferPtr ResultsPickler::getRunattrsPickle(const RunAndValueList& runAttrs)
{
    size_t sizeLimit = getSizeLimit();
    ShmPickler p(shmManager->create("runattrs", 0, true), sizeLimit);

    p.protocol();

    p.startList();

    for (const auto &ra : runAttrs) {
        Run *run = ra.first;
        const std::string &raName = ra.second;

        p.startTuple();

        p.pushString(run->getRunName());
        p.pushString(raName);
        p.pushString(run->getAttribute(raName));

        p.endTuple();
    }

    p.endList();

    p.stop();

    return p.getBuffer();
}


ShmSendBufferPtr ResultsPickler::getItervarsPickle(const RunAndValueList& itervars)
{
    size_t sizeLimit = getSizeLimit();
    ShmPickler p(shmManager->create("itervars", 0, true), sizeLimit);

    p.protocol();

    p.startList();

    for (const auto &iv : itervars) {
        Run *run = iv.first;
        const std::string &ivName = iv.second;

        p.startTuple();

        p.pushString(run->getRunName());
        p.pushString(ivName);
        p.pushString(run->getIterationVariable(ivName));

        p.endTuple();
    }

    p.endList();

    p.stop();

    return p.getBuffer();
}


ShmSendBufferPtr ResultsPickler::getConfigEntriesPickle(const RunAndValueList& configEntries)
{
    size_t sizeLimit = getSizeLimit();
    ShmPickler p(shmManager->create("configentries", 0, true), sizeLimit);

    p.protocol();

    p.startList();

    for (const auto &ce : configEntries) {
        Run *run = ce.first;
        const std::string &ceName = ce.second;

        p.startTuple();

        p.pushString(run->getRunName());
        p.pushString(ceName);
        p.pushString(run->getConfigValue(ceName));

        p.endTuple();
    }

    p.endList();

    p.stop();

    return p.getBuffer();
}


ShmSendBufferPtr ResultsPickler::getParamAssignmentsPickle(const RunAndValueList& paramAssignments)
{
    size_t sizeLimit = getSizeLimit();
    ShmPickler p(shmManager->create("paramassignments", 0, true), sizeLimit);

    p.protocol();

    p.startList();

    for (const auto &pa : paramAssignments) {
        Run *run = pa.first;
        const std::string &paName = pa.second;

        p.startTuple();

        p.pushString(run->getRunName());
        p.pushString(paName);
        p.pushString(run->getConfigValue(paName));

        p.endTuple();
    }

    p.endList();

    p.stop();

    return p.getBuffer();
}


ShmSendBufferPtr ResultsPickler::getRunattrsForRunsPickle(const std::vector<std::string>& runIds)
{
    size_t sizeLimit = getSizeLimit();
    ShmPickler p(shmManager->create("runattrs4r", 0, true), sizeLimit);

    p.protocol();

    p.startList();

    for (const std::string &runId : runIds) {
        Run *run = rfm->getRunByName(runId.c_str());
        const StringMap& runattrs = run->getAttributes();

        for (const auto &ra : runattrs) {
            p.startTuple();

            p.pushString(run->getRunName());
            p.pushString(ra.first);
            p.pushString(ra.second);

            p.endTuple();
        }
    }

    p.endList();

    p.stop();

    return p.getBuffer();
}


ShmSendBufferPtr ResultsPickler::getItervarsForRunsPickle(const std::vector<std::string>& runIds)
{
    size_t sizeLimit = getSizeLimit();
    ShmPickler p(shmManager->create("itervars4r", 0, true), sizeLimit);

    p.protocol();

    p.startList();

    for (const std::string &runId : runIds) {
        Run *run = rfm->getRunByName(runId.c_str());
        const StringMap& itervars = run->getIterationVariables();

        for (const auto &iv : itervars) {
            p.startTuple();

            p.pushString(run->getRunName());
            p.pushString(iv.first);
            p.pushString(iv.second);

            p.endTuple();
        }
    }

    p.endList();

    p.stop();

    return p.getBuffer();
}


ShmSendBufferPtr ResultsPickler::getConfigEntriesForRunsPickle(const std::vector<std::string>& runIds)
{
    size_t sizeLimit = getSizeLimit();
    ShmPickler p(shmManager->create("configentries4r", 0, true), sizeLimit);

    p.protocol();

    p.startList();

    for (const std::string &runId : runIds) {
        Run *run = rfm->getRunByName(runId.c_str());
        const OrderedKeyValueList& configentries = run->getConfigEntries();

        for (const auto &ce : configentries) {
            p.startTuple();

            p.pushString(run->getRunName());
            p.pushString(ce.first);
            p.pushString(ce.second);

            p.endTuple();
        }
    }

    p.endList();

    p.stop();

    return p.getBuffer();
}


ShmSendBufferPtr ResultsPickler::getParamAssignmentsForRunsPickle(const std::vector<std::string>& runIds)
{
    size_t sizeLimit = getSizeLimit();
    ShmPickler p(shmManager->create("paramassignments4r", 0, true), sizeLimit);

    p.protocol();

    p.startList();

    for (const std::string &runId : runIds) {
        Run *run = rfm->getRunByName(runId.c_str());
        const OrderedKeyValueList& paramassignments = run->getParamAssignmentConfigEntries();

        for (const auto &pa : paramassignments) {
            p.startTuple();

            p.pushString(run->getRunName());
            p.pushString(pa.first);
            p.pushString(pa.second);

            p.endTuple();
        }
    }

    p.endList();

    p.stop();

    return p.getBuffer();
}

}  // namespace scave
}  // namespace omnetpp
