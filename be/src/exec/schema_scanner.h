// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#pragma once

#include <gen_cpp/Data_types.h>
#include <gen_cpp/Descriptors_types.h>

#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "cctz/time_zone.h"
#include "common/factory_creator.h"
#include "common/status.h"
#include "runtime/define_primitive_type.h"
#include "util/runtime_profile.h"

namespace doris {

// forehead declare class, because jni function init in DorisServer.

class RuntimeState;
class ObjectPool;
class TUserIdentity;

namespace vectorized {
class Block;
}

namespace pipeline {
class Dependency;
}

struct SchemaScannerCommonParam {
    SchemaScannerCommonParam()
            : db(nullptr),
              table(nullptr),
              wild(nullptr),
              user(nullptr),
              user_ip(nullptr),
              current_user_ident(nullptr),
              ip(nullptr),
              port(0),
              catalog(nullptr) {}
    const std::string* db = nullptr;
    const std::string* table = nullptr;
    const std::string* wild = nullptr;
    const std::string* user = nullptr;                 // deprecated
    const std::string* user_ip = nullptr;              // deprecated
    const TUserIdentity* current_user_ident = nullptr; // to replace the user and user ip
    const std::string* ip = nullptr;                   // frontend ip
    int32_t port;                                      // frontend thrift port
    int64_t thread_id;
    const std::string* catalog = nullptr;
    std::set<TNetworkAddress> fe_addr_list;
};

// scanner parameter from frontend
struct SchemaScannerParam {
    std::shared_ptr<SchemaScannerCommonParam> common_param;
    std::unique_ptr<RuntimeProfile> profile;

    SchemaScannerParam() : common_param(new SchemaScannerCommonParam()) {}
};

// virtual scanner for all schema table
class SchemaScanner {
public:
    struct ColumnDesc {
        const char* name = nullptr;
        PrimitiveType type;
        int size;
        bool is_null;
        /// Only set if type == TYPE_DECIMAL
        int precision = -1;
        /// Only set if type == TYPE_DECIMAL or DATETIMEV2
        int scale = -1;
    };
    SchemaScanner(const std::vector<ColumnDesc>& columns,
                  TSchemaTableType::type type = TSchemaTableType::SCH_INVALID);
    virtual ~SchemaScanner();

    // init object need information, schema etc.
    virtual Status init(RuntimeState* state, SchemaScannerParam* param, ObjectPool* pool);
    Status get_next_block(RuntimeState* state, vectorized::Block* block, bool* eos);
    // Start to work
    virtual Status start(RuntimeState* state);
    virtual Status get_next_block_internal(vectorized::Block* block, bool* eos) = 0;
    const std::vector<ColumnDesc>& get_column_desc() const { return _columns; }
    // factory function
    static std::unique_ptr<SchemaScanner> create(TSchemaTableType::type type);
    TSchemaTableType::type type() const { return _schema_table_type; }
    void set_dependency(std::shared_ptr<pipeline::Dependency> dep) { _dependency = dep; }
    Status get_next_block_async(RuntimeState* state);

protected:
    void _init_block(vectorized::Block* src_block);
    Status fill_dest_column_for_range(vectorized::Block* block, size_t pos,
                                      const std::vector<void*>& datas);

    Status insert_block_column(TCell cell, int col_index, vectorized::Block* block,
                               PrimitiveType type);

    // get dbname from catalogname.dbname
    // if full_name does not have catalog part, just return origin name.
    std::string get_db_from_full_name(const std::string& full_name);

    bool _is_init;
    // this is used for sub class
    SchemaScannerParam* _param = nullptr;
    // schema table's column desc
    std::vector<ColumnDesc> _columns;

    TSchemaTableType::type _schema_table_type;

    RuntimeProfile::Counter* _get_db_timer = nullptr;
    RuntimeProfile::Counter* _get_table_timer = nullptr;
    RuntimeProfile::Counter* _get_describe_timer = nullptr;
    RuntimeProfile::Counter* _fill_block_timer = nullptr;

    std::shared_ptr<pipeline::Dependency> _dependency = nullptr;

    std::unique_ptr<vectorized::Block> _data_block;
    AtomicStatus _scanner_status;
    std::atomic<bool> _eos = false;
    std::atomic<bool> _opened = false;
    std::atomic<bool> _async_thread_running = false;
    cctz::time_zone _timezone_obj;
};

} // namespace doris
