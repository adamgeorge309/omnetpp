//=========================================================================
//  SHMMANAGER.CC - part of
//                  OMNeT++/OMNEST
//           Discrete System Simulation in C++
//=========================================================================

/*--------------------------------------------------------------*
  Copyright (C) 1992-2019 Andras Varga
  Copyright (C) 2006-2019 OpenSim Ltd.

  This file is distributed WITHOUT ANY WARRANTY. See the file
  `license' for details on this and other legal matters.
*--------------------------------------------------------------*/

#include "shmmanager.h"
#include <cstring>
#include "omnetpp/platdep/platmisc.h" // getpid()
#include "common/stlutil.h"

using namespace omnetpp::common;

namespace omnetpp {
namespace scave {

#define HEADERSIZE 8
#define RESERVESIZE ((1ull<<31)-1-HEADERSIZE)  // 2G (on macOS, size is int32_t)

#ifdef SHMSENDBUFFER_DEBUG
    int ShmSendBuffer::numInstances = 0;
#endif

ShmSendBuffer::ShmSendBuffer(const std::string& fullName, size_t commitSize, bool extendable) :
    name(fullName), committedSize(commitSize)
{
    reservedSize = extendable ? RESERVESIZE : commitSize;
    handle = createSharedMemory(name.c_str(), reservedSize + HEADERSIZE, false);
    mappedStart = mapSharedMemory(name.c_str(), reservedSize + HEADERSIZE);
    commitSharedMemory(mappedStart, committedSize + HEADERSIZE);
    memset(mappedStart, 0, HEADERSIZE);

#ifdef SHMSENDBUFFER_DEBUG
    numInstances++;
    std::cout << "ShmSendBuffer ctor, numInstances: " << numInstances << std::endl;
#endif
}

ShmSendBuffer::~ShmSendBuffer()
{
    unmapSharedMemory(mappedStart, reservedSize + HEADERSIZE);
    closeSharedMemory(handle);
    removeSharedMemory(name.c_str());

#ifdef SHMSENDBUFFER_DEBUG
    numInstances--;
    std::cout << "ShmSendBuffer dtor, numInstances: " << numInstances << std::endl;
#endif
}

void *ShmSendBuffer::getAddress() const
{
    return (char *)mappedStart + HEADERSIZE;
}

void ShmSendBuffer::extendTo(size_t newSize)
{
    Assert(newSize <= reservedSize);
    if (committedSize >= newSize)
        return;
    size_t increment = newSize - committedSize;
    commitSharedMemory((char *)mappedStart + HEADERSIZE + committedSize, increment);
    committedSize = newSize;
}

std::string ShmSendBuffer::getNameAndTotalSize() const
{
    return name + " " + std::to_string(HEADERSIZE + committedSize);
}

bool ShmSendBuffer::isConsumed() const
{
    return *(char *)mappedStart != 0;  // target process changed it from zero
}

std::vector<int8_t> ShmSendBuffer::getContentCopy() const
{
    std::vector<int8_t> result(committedSize);
    memcpy(result.data(), getAddress(), committedSize);
    return result;
}

//----

ShmSendBufferManager::~ShmSendBufferManager()
{
    Mutex mutex(lock.writeLock());
    for (ShmSendBufferPtr &p : buffers) {
        if (p && !p->isConsumed())
            std::cerr << "ShmSendBufferManager: unconsumed send buffer found in destructor! call clear() explicitly if this is normal\n";
#ifdef SHMSENDBUFFER_DEBUG
        if (p)
            std::cout << "ShmSendBufferManager dtor: Dropping reference to " << (void*)p.get() << std::endl;
#endif
        p = nullptr;
    }
}

ShmSendBufferPtr ShmSendBufferManager::create(const char *label, size_t commitSize, bool extendable)
{
    garbageCollect(); // when a new Python->IDE call occurs, there's a high chance that buffers from earlier calls are not longer needed -> GC them

    Mutex mutex(lock.writeLock());

    static int counter = 0;
    char name[OPP_SHM_NAME_MAX];
    snprintf(name, OPP_SHM_NAME_MAX, "/opp-%ld-%d-%s", getpid()%1000000L, ++counter, label);
    // It would be more idiomatic to use std::make_shared here, but I'drather keep the
    // constructor of ShmSendBuffer private, and make ShmSendBufferManager a friend of it.
    ShmSendBufferPtr result(new ShmSendBuffer(name, commitSize, extendable));
    buffers.push_back(result);
    return result;
}

ShmSendBufferPtr ShmSendBufferManager::create(const char *label, const std::vector<int8_t>& content)
{
    ShmSendBufferPtr buffer = create(label, content.size(), false);
    memcpy(buffer->getAddress(), content.data(), content.size());
    return buffer;
}

void ShmSendBufferManager::clear()
{
    Mutex mutex(lock.writeLock());
    for (ShmSendBufferPtr &p : buffers) {
#ifdef SHMSENDBUFFER_DEBUG
        if (p)
            std::cout << "ShmSendBufferManager clear(): dropping reference to " << (void*)p.get() << std::endl;
#endif
        p = nullptr;
    }
}

bool ShmSendBufferManager::garbageCollect()
{
    Mutex mutex(lock.writeLock());
    bool result = false;
    for (ShmSendBufferPtr &p : buffers)
        if (p && p->isConsumed()) {
#ifdef SHMSENDBUFFER_DEBUG
            std::cout << "ShmSendBufferManager garbageCollect(): dropping reference to " << (void*)p.get() << std::endl;
#endif
            p = nullptr;
            result = true;
        }

    common::remove(buffers, ShmSendBufferPtr(nullptr));

    return result;
}

}  // namespace scave
}  // namespace omnetpp
