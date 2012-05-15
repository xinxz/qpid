#ifndef QPID_HA_ENUM_H
#define QPID_HA_ENUM_H

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

#include "qpid/types/Variant.h"
#include <string>
#include <iosfwd>

namespace qpid {

namespace framing {
class FieldTable;
}

namespace ha {

/** Base class for enums with string conversion */
class EnumBase {
  public:
    EnumBase(const char* names_[], size_t count_, unsigned value)
        : value(value), names(names_), count(count_) {}

    /** Convert to string */
    std::string str() const;
    /** Parse from string, throw if unsuccessful */
    void parse(const std::string&);
    /** Parse from string, return false if unsuccessful. */
    bool parseNoThrow(const std::string&);

  protected:
    unsigned value;
    const char** names;
    size_t count;
};

std::ostream& operator<<(std::ostream&, EnumBase);
std::istream& operator>>(std::istream&, EnumBase&);

/** Wrapper template for enums with string conversion */
template <class T> class Enum : public EnumBase {
  public:
    Enum(T x=T()) : EnumBase(NAMES, N, x) {}
    T get() const { return T(value); }
    void operator=(T x) { value = x; }
  private:
    static const size_t N;
    static const char* NAMES[];
};

/** To print an enum x: o << printable(x) */
template <class T> Enum<T> printable(T x) { return Enum<T>(x); }

enum ReplicateLevel {
    NONE,                   ///< Nothing is replicated
    CONFIGURATION,          ///< Wiring is replicated but not messages
    ALL                     ///< Everything is replicated
};

/** State of a broker: see HaBroker::setStatus for state diagram */
enum BrokerStatus {
    JOINING,                    ///< New broker, looking for primary
    CATCHUP,                    ///< Backup: Connected to primary, catching up on state.
    READY,                      ///< Backup: Caught up, ready to take over.
    PROMOTING,                  ///< Primary: waiting for backups to connect & sync
    ACTIVE,                     ///< Primary: actively serving clients.
    STANDALONE                  ///< Not part of a cluster.
};

inline bool isPrimary(BrokerStatus s) {
    return s  == PROMOTING || s == ACTIVE || s == STANDALONE;
}

inline bool isBackup(BrokerStatus s) { return !isPrimary(s); }

extern const std::string QPID_REPLICATE;
}} // qpid::ha
#endif  /*!QPID_HA_ENUM_H*/
