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

#include <memory>

#include "olap/rowset/segment_v2/inverted_index/query_v2/query.h"

namespace doris::segment_v2::idx_query_v2 {

class TermQuery : public Query {
public:
    TermQuery(const std::shared_ptr<lucene::search::IndexSearcher>& searcher,
              const TQueryOptions& query_options, QueryInfo query_info);
    ~TermQuery() override;

    void execute(const std::shared_ptr<roaring::Roaring>& result) {}

    int32_t doc_id() const { return _iter->doc_id(); }
    int32_t next_doc() const { return _iter->next_doc(); }
    int32_t advance(int32_t target) const { return _iter->advance(target); }
    int64_t cost() const { return _iter->doc_freq(); }

private:
    TermDocs* _term_docs = nullptr;
    TermIterPtr _iter;
};

} // namespace doris::segment_v2::idx_query_v2