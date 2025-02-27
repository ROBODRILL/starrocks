// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/be/test/runtime/memory_scratch_sink_test.cpp

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

#include "runtime/memory_scratch_sink.h"

#include <gtest/gtest.h>
#include <stdio.h>
#include <stdlib.h>

#include <iostream>

#include "common/config.h"
#include "common/logging.h"
#include "exec/csv_scan_node.h"
#include "exprs/expr.h"
#include "gen_cpp/Exprs_types.h"
#include "gen_cpp/PlanNodes_types.h"
#include "gen_cpp/StarRocksExternalService_types.h"
#include "gen_cpp/Types_types.h"
#include "runtime/bufferpool/reservation_tracker.h"
#include "runtime/exec_env.h"
#include "runtime/mem_tracker.h"
#include "runtime/primitive_type.h"
#include "runtime/result_queue_mgr.h"
#include "runtime/row_batch.h"
#include "runtime/runtime_state.h"
#include "runtime/thread_resource_mgr.h"
#include "runtime/tuple_row.h"
#include "storage/options.h"
#include "storage/row.h"
#include "testutil/desc_tbl_builder.h"
#include "util/blocking_queue.hpp"
#include "util/logging.h"

namespace starrocks {

class MemoryScratchSinkTest : public testing::Test {
public:
    MemoryScratchSinkTest() {
        {
            TExpr expr;
            {
                TExprNode node;
                node.node_type = TExprNodeType::INT_LITERAL;
                node.type = gen_type_desc(TPrimitiveType::INT, "int_column");
                node.num_children = 0;
                TIntLiteral data;
                data.value = 1;
                node.__set_int_literal(data);
                expr.nodes.push_back(node);
            }
            _exprs.push_back(expr);
        }
    }

    ~MemoryScratchSinkTest() {
        delete _state;
        delete _mem_tracker;
        delete _exec_env->_result_queue_mgr;
        delete _exec_env->_thread_mgr;
        delete _exec_env->_buffer_reservation;
    }

    virtual void SetUp() {
        config::periodic_counter_update_period_ms = 500;
        config::storage_root_path = "./data";

        system("mkdir -p ./test_run/output/");
        system("pwd");
        system("cp -r ./be/test/runtime/test_data/ ./test_run/.");

        init();
    }

    virtual void TearDown() {
        _obj_pool.clear();
        system("rm -rf ./test_run");
    }

    void init();
    void init_desc_tbl();
    void init_runtime_state();

private:
    ObjectPool _obj_pool;
    ExecEnv* _exec_env = nullptr;
    // std::vector<TExpr> _exprs;
    TDescriptorTable _t_desc_table;
    RuntimeState* _state = nullptr;
    TPlanNode _tnode;
    RowDescriptor* _row_desc = nullptr;
    TMemoryScratchSink _tsink;
    MemTracker* _mem_tracker = nullptr;
    DescriptorTbl* _desc_tbl = nullptr;
    std::vector<TExpr> _exprs;
};

void MemoryScratchSinkTest::init() {
    _exec_env = ExecEnv::GetInstance();
    init_desc_tbl();
    init_runtime_state();
}

void MemoryScratchSinkTest::init_runtime_state() {
    _exec_env->_result_queue_mgr = new ResultQueueMgr();
    _exec_env->_thread_mgr = new ThreadResourceMgr();
    _exec_env->_buffer_reservation = new ReservationTracker();
    TQueryOptions query_options;
    query_options.batch_size = 1024;
    TUniqueId query_id;
    query_id.lo = 10;
    query_id.hi = 100;
    _state = new RuntimeState(query_id, query_options, TQueryGlobals(), _exec_env);
    _state->init_instance_mem_tracker();
    _mem_tracker = new MemTracker(-1, "MemoryScratchSinkTest", _state->instance_mem_tracker());
    _state->set_desc_tbl(_desc_tbl);
    _state->_load_dir = "./test_run/output/";
    _state->init_mem_trackers(TUniqueId());
}

void MemoryScratchSinkTest::init_desc_tbl() {
    // TTableDescriptor
    TTableDescriptor t_table_desc;
    t_table_desc.id = 0;
    t_table_desc.tableType = TTableType::OLAP_TABLE;
    t_table_desc.numCols = 0;
    t_table_desc.numClusteringCols = 0;
    t_table_desc.olapTable.tableName = "test";
    t_table_desc.tableName = "test_table_name";
    t_table_desc.dbName = "test_db_name";
    t_table_desc.__isset.olapTable = true;

    _t_desc_table.tableDescriptors.push_back(t_table_desc);
    _t_desc_table.__isset.tableDescriptors = true;

    // TSlotDescriptor
    std::vector<TSlotDescriptor> slot_descs;
    int offset = 1;
    int i = 0;
    // int_column
    {
        TSlotDescriptor t_slot_desc;
        t_slot_desc.__set_id(i);
        t_slot_desc.__set_slotType(gen_type_desc(TPrimitiveType::INT));
        t_slot_desc.__set_columnPos(i);
        t_slot_desc.__set_byteOffset(offset);
        t_slot_desc.__set_nullIndicatorByte(0);
        t_slot_desc.__set_nullIndicatorBit(-1);
        t_slot_desc.__set_slotIdx(i);
        t_slot_desc.__set_isMaterialized(true);
        t_slot_desc.__set_colName("int_column");

        slot_descs.push_back(t_slot_desc);
        offset += sizeof(int32_t);
    }
    _t_desc_table.__set_slotDescriptors(slot_descs);

    // TTupleDescriptor
    TTupleDescriptor t_tuple_desc;
    t_tuple_desc.id = 0;
    t_tuple_desc.byteSize = offset;
    t_tuple_desc.numNullBytes = 1;
    t_tuple_desc.tableId = 0;
    t_tuple_desc.__isset.tableId = true;
    _t_desc_table.tupleDescriptors.push_back(t_tuple_desc);

    DescriptorTbl::create(&_obj_pool, _t_desc_table, &_desc_tbl);

    std::vector<TTupleId> row_tids;
    row_tids.push_back(0);

    std::vector<bool> nullable_tuples;
    nullable_tuples.push_back(false);
    _row_desc = _obj_pool.add(new RowDescriptor(*_desc_tbl, row_tids, nullable_tuples));

    // node
    _tnode.node_id = 0;
    _tnode.node_type = TPlanNodeType::CSV_SCAN_NODE;
    _tnode.num_children = 0;
    _tnode.limit = -1;
    _tnode.row_tuples.push_back(0);
    _tnode.nullable_tuples.push_back(false);
    _tnode.csv_scan_node.tuple_id = 0;

    _tnode.csv_scan_node.__set_column_separator(",");
    _tnode.csv_scan_node.__set_row_delimiter("\n");

    // column_type_mapping
    std::map<std::string, TColumnType> column_type_map;
    {
        TColumnType column_type;
        column_type.__set_type(TPrimitiveType::INT);
        column_type_map["int_column"] = column_type;
    }

    _tnode.csv_scan_node.__set_column_type_mapping(column_type_map);

    std::vector<std::string> columns;
    columns.push_back("int_column");
    _tnode.csv_scan_node.__set_columns(columns);

    _tnode.csv_scan_node.__isset.unspecified_columns = true;
    _tnode.csv_scan_node.__isset.default_values = true;
    _tnode.csv_scan_node.max_filter_ratio = 0.5;
    _tnode.__isset.csv_scan_node = true;
}

TEST_F(MemoryScratchSinkTest, work_flow_normal) {
    MemoryScratchSink sink(*_row_desc, _exprs, _tsink);
    TDataSink data_sink;
    data_sink.memory_scratch_sink = _tsink;
    ASSERT_TRUE(sink.init(data_sink).ok());
    ASSERT_TRUE(sink.prepare(_state).ok());
    std::vector<std::string> file_paths;
    file_paths.push_back("./test_run/test_data/csv_data");
    _tnode.csv_scan_node.__set_file_paths(file_paths);

    CsvScanNode scan_node(&_obj_pool, _tnode, *_desc_tbl);
    Status status = scan_node.prepare(_state);
    ASSERT_TRUE(status.ok());

    status = scan_node.open(_state);
    ASSERT_TRUE(status.ok());

    std::unique_ptr<MemTracker> mem_tracker(new MemTracker(-1));
    RowBatch row_batch(scan_node._row_descriptor, _state->batch_size(), mem_tracker.get());
    bool eos = false;

    while (!eos) {
        status = scan_node.get_next(_state, &row_batch, &eos);
        ASSERT_TRUE(status.ok());
        // int num = std::min(row_batch.num_rows(), 10);
        int num = row_batch.num_rows();

        ASSERT_EQ(6, num);
        ASSERT_TRUE(sink.send(_state, &row_batch).ok());
        ASSERT_TRUE(sink.close(_state, Status::OK()).ok());
    }

    ASSERT_TRUE(scan_node.close(_state).ok());
}

} // namespace starrocks
