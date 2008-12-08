/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
#include "OutputInterceptor.h"
#include "Connection.h"
#include "Cluster.h"
#include "qpid/framing/ClusterConnectionDeliverDoOutputBody.h"
#include "qpid/framing/AMQFrame.h"
#include "qpid/log/Statement.h"
#include <boost/current_function.hpp>


namespace qpid {
namespace cluster {

using namespace framing;

OutputInterceptor::OutputInterceptor(cluster::Connection& p, sys::ConnectionOutputHandler& h)
    : parent(p), next(&h), sent(), moreOutput(), doingOutput()
{}

void OutputInterceptor::send(framing::AMQFrame& f) {
    parent.getCluster().checkQuorum();
    Locker l(lock); 
    next->send(f);
    if (!parent.isCatchUp())
        sent += f.encodedSize();
}

void OutputInterceptor::activateOutput() {
    Locker l(lock);
    if (parent.isCatchUp())
        next->activateOutput();
    else {
        QPID_LOG(trace,  parent << " activateOutput - sending doOutput");
        moreOutput = true;
        sendDoOutput();
    }
}

void OutputInterceptor::giveReadCredit(int32_t credit) { next->giveReadCredit(credit); }

// Called in write thread when the IO layer has no more data to write.
// We do nothing in the write thread, we run doOutput only on delivery
// of doOutput requests.
bool  OutputInterceptor::doOutput() {
    QPID_LOG(trace, parent << " write idle.");
    return false;
}

// Delivery of doOutput allows us to run the real connection doOutput()
// which tranfers frames to the codec for writing.
// 
void OutputInterceptor::deliverDoOutput(size_t requested) {
    Locker l(lock);
    size_t buf = next->getBuffered();
    if (parent.isLocal())
        writeEstimate.delivered(sent, buf); // Update the estimate.

    // Run the real doOutput() till we have added the requested data or there's nothing to output.
    sent = 0;
    do {
        sys::Mutex::ScopedUnlock u(lock);
        moreOutput = parent.getBrokerConnection().doOutput();
    } while (sent < requested && moreOutput);
    sent += buf;                // Include buffered data in the sent total.

    QPID_LOG(trace, "Delivered doOutput: requested=" << requested << " output=" << sent << " more=" << moreOutput);

    if (parent.isLocal() && moreOutput)  {
        QPID_LOG(trace,  parent << " deliverDoOutput - sending doOutput, more output available.");
        sendDoOutput();
    }
    else
        doingOutput = false;
}

// Send a doOutput request if one is not already in flight.
void OutputInterceptor::sendDoOutput() {
    // Call with lock held.
    if (!parent.isLocal()) return;

    doingOutput = true;
    size_t request = writeEstimate.sending(getBuffered());
    
    // Note we may send 0 size request if there's more than 2*estimate in the buffer.
    // Send it anyway to keep the doOutput chain going until we are sure there's no more output
    // (in deliverDoOutput)
    //
    // FIXME aconway 2008-10-16: use ++parent.mcastSeq as sequence no,not 0
    parent.getCluster().mcastControl(ClusterConnectionDeliverDoOutputBody(ProtocolVersion(), request), parent.getId(), 0);
    QPID_LOG(trace, parent << "Send doOutput request for " << request);
}

void OutputInterceptor::setOutputHandler(sys::ConnectionOutputHandler& h) {
    Locker l(lock);
    next = &h;
}

void OutputInterceptor::close() {
    Locker l(lock);
    next->close();
}

size_t OutputInterceptor::getBuffered() const {
    Locker l(lock);
    return next->getBuffered();
}

}} // namespace qpid::cluster
