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

package org.apache.doris.datasource.hive;

import org.apache.doris.analysis.TableScanParams;
import org.apache.doris.analysis.TableSnapshot;
import org.apache.doris.catalog.Column;
import org.apache.doris.catalog.Env;
import org.apache.doris.catalog.ListPartitionItem;
import org.apache.doris.catalog.MTMV;
import org.apache.doris.catalog.PartitionItem;
import org.apache.doris.catalog.PartitionType;
import org.apache.doris.catalog.PrimitiveType;
import org.apache.doris.catalog.ScalarType;
import org.apache.doris.catalog.Type;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.common.Config;
import org.apache.doris.common.DdlException;
import org.apache.doris.common.UserException;
import org.apache.doris.datasource.ExternalSchemaCache;
import org.apache.doris.datasource.ExternalSchemaCache.SchemaCacheKey;
import org.apache.doris.datasource.ExternalTable;
import org.apache.doris.datasource.SchemaCacheValue;
import org.apache.doris.datasource.TablePartitionValues;
import org.apache.doris.datasource.hudi.HudiSchemaCacheKey;
import org.apache.doris.datasource.hudi.HudiSchemaCacheValue;
import org.apache.doris.datasource.hudi.HudiUtils;
import org.apache.doris.datasource.iceberg.IcebergMvccSnapshot;
import org.apache.doris.datasource.iceberg.IcebergSchemaCacheKey;
import org.apache.doris.datasource.iceberg.IcebergUtils;
import org.apache.doris.datasource.mvcc.EmptyMvccSnapshot;
import org.apache.doris.datasource.mvcc.MvccSnapshot;
import org.apache.doris.datasource.mvcc.MvccTable;
import org.apache.doris.datasource.mvcc.MvccUtil;
import org.apache.doris.datasource.property.storage.StorageProperties;
import org.apache.doris.datasource.systable.SupportedSysTables;
import org.apache.doris.datasource.systable.SysTable;
import org.apache.doris.fs.FileSystemDirectoryLister;
import org.apache.doris.mtmv.MTMVBaseTableIf;
import org.apache.doris.mtmv.MTMVRefreshContext;
import org.apache.doris.mtmv.MTMVRelatedTableIf;
import org.apache.doris.mtmv.MTMVSnapshotIf;
import org.apache.doris.nereids.exceptions.NotSupportedException;
import org.apache.doris.nereids.trees.plans.logical.LogicalFileScan.SelectedPartitions;
import org.apache.doris.qe.GlobalVariable;
import org.apache.doris.statistics.AnalysisInfo;
import org.apache.doris.statistics.BaseAnalysisTask;
import org.apache.doris.statistics.ColumnStatistic;
import org.apache.doris.statistics.ColumnStatisticBuilder;
import org.apache.doris.statistics.HMSAnalysisTask;
import org.apache.doris.statistics.StatsType;
import org.apache.doris.statistics.util.StatisticsUtil;
import org.apache.doris.thrift.THiveTable;
import org.apache.doris.thrift.TTableDescriptor;
import org.apache.doris.thrift.TTableType;

import com.google.common.collect.BiMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsData;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsObj;
import org.apache.hadoop.hive.metastore.api.DateColumnStatsData;
import org.apache.hadoop.hive.metastore.api.DecimalColumnStatsData;
import org.apache.hadoop.hive.metastore.api.DoubleColumnStatsData;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.LongColumnStatsData;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.StringColumnStatsData;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.ql.io.AcidUtils;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.internal.schema.InternalSchema;
import org.apache.hudi.internal.schema.Types;
import org.apache.hudi.internal.schema.convert.AvroInternalSchemaConverter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Hive metastore external table.
 */
public class HMSExternalTable extends ExternalTable implements MTMVRelatedTableIf, MTMVBaseTableIf, MvccTable {
    private static final Logger LOG = LogManager.getLogger(HMSExternalTable.class);

    public static final Set<String> SUPPORTED_HIVE_FILE_FORMATS;
    public static final Set<String> SUPPORTED_HIVE_TOPN_LAZY_FILE_FORMATS;

    public static final Set<String> SUPPORTED_HIVE_TRANSACTIONAL_FILE_FORMATS;
    public static final Set<String> SUPPORTED_HUDI_FILE_FORMATS;

    private static final Map<StatsType, String> MAP_SPARK_STATS_TO_DORIS;

    private static final String TBL_PROP_TRANSIENT_LAST_DDL_TIME = "transient_lastDdlTime";

    private static final String NUM_ROWS = "numRows";

    private static final String SPARK_COL_STATS = "spark.sql.statistics.colStats.";
    private static final String SPARK_STATS_MAX = ".max";
    private static final String SPARK_STATS_MIN = ".min";
    private static final String SPARK_STATS_NDV = ".distinctCount";
    private static final String SPARK_STATS_NULLS = ".nullCount";
    private static final String SPARK_STATS_AVG_LEN = ".avgLen";
    private static final String SPARK_STATS_MAX_LEN = ".avgLen";
    private static final String SPARK_STATS_HISTOGRAM = ".histogram";

    private static final String USE_HIVE_SYNC_PARTITION = "use_hive_sync_partition";

    static {
        SUPPORTED_HIVE_FILE_FORMATS = Sets.newHashSet();
        SUPPORTED_HIVE_FILE_FORMATS.add("org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat");
        SUPPORTED_HIVE_FILE_FORMATS.add("org.apache.hadoop.hive.ql.io.orc.OrcInputFormat");
        SUPPORTED_HIVE_FILE_FORMATS.add("org.apache.hadoop.mapred.TextInputFormat");
        // Some hudi table use HoodieParquetInputFormatBase as input format
        // But we can't treat it as hudi table.
        // So add to SUPPORTED_HIVE_FILE_FORMATS and treat is as a hive table.
        // Then Doris will just list the files from location and read parquet files directly.
        SUPPORTED_HIVE_FILE_FORMATS.add("org.apache.hudi.hadoop.HoodieParquetInputFormatBase");

        SUPPORTED_HIVE_TRANSACTIONAL_FILE_FORMATS = Sets.newHashSet();
        SUPPORTED_HIVE_TRANSACTIONAL_FILE_FORMATS.add("org.apache.hadoop.hive.ql.io.orc.OrcInputFormat");

        SUPPORTED_HIVE_TOPN_LAZY_FILE_FORMATS = Sets.newHashSet();
        SUPPORTED_HIVE_TOPN_LAZY_FILE_FORMATS.add("org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat");
        SUPPORTED_HIVE_TOPN_LAZY_FILE_FORMATS.add("org.apache.hadoop.hive.ql.io.orc.OrcInputFormat");
    }

    static {
        SUPPORTED_HUDI_FILE_FORMATS = Sets.newHashSet();
        SUPPORTED_HUDI_FILE_FORMATS.add("org.apache.hudi.hadoop.HoodieParquetInputFormat");
        SUPPORTED_HUDI_FILE_FORMATS.add("com.uber.hoodie.hadoop.HoodieInputFormat");
        SUPPORTED_HUDI_FILE_FORMATS.add("org.apache.hudi.hadoop.realtime.HoodieParquetRealtimeInputFormat");
        SUPPORTED_HUDI_FILE_FORMATS.add("com.uber.hoodie.hadoop.realtime.HoodieRealtimeInputFormat");
    }

    static {
        MAP_SPARK_STATS_TO_DORIS = Maps.newHashMap();
        MAP_SPARK_STATS_TO_DORIS.put(StatsType.NDV, SPARK_STATS_NDV);
        MAP_SPARK_STATS_TO_DORIS.put(StatsType.AVG_SIZE, SPARK_STATS_AVG_LEN);
        MAP_SPARK_STATS_TO_DORIS.put(StatsType.MAX_SIZE, SPARK_STATS_MAX_LEN);
        MAP_SPARK_STATS_TO_DORIS.put(StatsType.NUM_NULLS, SPARK_STATS_NULLS);
        MAP_SPARK_STATS_TO_DORIS.put(StatsType.MIN_VALUE, SPARK_STATS_MIN);
        MAP_SPARK_STATS_TO_DORIS.put(StatsType.MAX_VALUE, SPARK_STATS_MAX);
        MAP_SPARK_STATS_TO_DORIS.put(StatsType.HISTOGRAM, SPARK_STATS_HISTOGRAM);
    }

    private volatile org.apache.hadoop.hive.metastore.api.Table remoteTable = null;

    private DLAType dlaType = DLAType.UNKNOWN;

    private HMSDlaTable dlaTable;

    // record the event update time when enable hms event listener
    protected volatile long eventUpdateTime;

    public enum DLAType {
        UNKNOWN, HIVE, HUDI, ICEBERG
    }

    /**
     * Create hive metastore external table.
     *
     * @param id Table id.
     * @param name Table name.
     * @param remoteName Remote table name.
     * @param catalog HMSExternalDataSource.
     * @param db Database.
     */
    public HMSExternalTable(long id, String name, String remoteName, HMSExternalCatalog catalog,
            HMSExternalDatabase db) {
        super(id, name, remoteName, catalog, db, TableType.HMS_EXTERNAL_TABLE);
    }

    // Will throw NotSupportedException if not supported hms table.
    // Otherwise, return true.
    public boolean isSupportedHmsTable() {
        makeSureInitialized();
        return true;
    }

    protected synchronized void makeSureInitialized() {
        super.makeSureInitialized();
        if (!objectCreated) {
            remoteTable = ((HMSExternalCatalog) catalog).getClient().getTable(dbName, name);
            if (remoteTable == null) {
                throw new IllegalArgumentException("Hms table not exists, table: " + getNameWithFullQualifiers());
            } else {
                if (supportedIcebergTable()) {
                    dlaType = DLAType.ICEBERG;
                    dlaTable = new IcebergDlaTable(this);
                } else if (supportedHoodieTable()) {
                    dlaType = DLAType.HUDI;
                    dlaTable = new HudiDlaTable(this);
                } else if (supportedHiveTable()) {
                    dlaType = DLAType.HIVE;
                    dlaTable = new HiveDlaTable(this);
                } else {
                    // Should not reach here. Because `supportedHiveTable` will throw exception if not return true.
                    throw new NotSupportedException("Unsupported dlaType for table: " + getNameWithFullQualifiers());
                }
            }
            objectCreated = true;
        }
    }

    /**
     * Now we only support cow table in iceberg.
     */
    private boolean supportedIcebergTable() {
        Map<String, String> paras = remoteTable.getParameters();
        if (paras == null) {
            return false;
        }
        return paras.containsKey("table_type") && paras.get("table_type").equalsIgnoreCase("ICEBERG");
    }

    /**
     * `HoodieParquetInputFormat`: `Snapshot Queries` on cow and mor table and `Read Optimized Queries` on cow table
     */
    private boolean supportedHoodieTable() {
        if (remoteTable.getSd() == null) {
            return false;
        }
        Map<String, String> paras = remoteTable.getParameters();
        String inputFormatName = remoteTable.getSd().getInputFormat();
        // compatible with flink hive catalog
        return (paras != null && "hudi".equalsIgnoreCase(paras.get("flink.connector")))
                || (inputFormatName != null && SUPPORTED_HUDI_FILE_FORMATS.contains(inputFormatName));
    }

    public boolean isHoodieCowTable() {
        if (remoteTable.getSd() == null) {
            return false;
        }
        String inputFormatName = remoteTable.getSd().getInputFormat();
        Map<String, String> params = remoteTable.getParameters();
        return "org.apache.hudi.hadoop.HoodieParquetInputFormat".equals(inputFormatName)
                || "skip_merge".equals(getCatalogProperties().get("hoodie.datasource.merge.type"))
                || (params != null && "COPY_ON_WRITE".equalsIgnoreCase(params.get("flink.table.type")));
    }

    /**
     * Some data lakes (such as Hudi) will synchronize their partition information to HMS,
     * then we can quickly obtain the partition information of the table from HMS.
     */
    public boolean useHiveSyncPartition() {
        return Boolean.parseBoolean(catalog.getProperties().getOrDefault(USE_HIVE_SYNC_PARTITION, "false"));
    }

    /**
     * Now we only support three file input format hive tables: parquet/orc/text.
     * Support managed_table and external_table.
     */
    private boolean supportedHiveTable() {
        // we will return false if null, which means that the table type maybe unsupported.
        if (remoteTable.getSd() == null) {
            throw new NotSupportedException("remote table's storage descriptor is null");
        }
        // If this is hive view, no need to check file format.
        if (remoteTable.isSetViewExpandedText() || remoteTable.isSetViewOriginalText()) {
            return true;
        }
        String inputFileFormat = remoteTable.getSd().getInputFormat();
        if (inputFileFormat == null) {
            throw new NotSupportedException("remote table's storage input format is null");
        }
        boolean supportedFileFormat = SUPPORTED_HIVE_FILE_FORMATS.contains(inputFileFormat);
        if (!supportedFileFormat) {
            // for easier debugging, need return error message if unsupported input format is used.
            // NotSupportedException is required by some operation.
            throw new NotSupportedException("Unsupported hive input format: " + inputFileFormat);
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("hms table {} is {} with file format: {}", name, remoteTable.getTableType(), inputFileFormat);
        }
        return true;
    }

    /**
     * Only support /orc/orc transactional/parquet table.
     */
    public boolean supportedHiveTopNLazyTable() {
        if (remoteTable.getSd() == null) {
            return false;
        }

        if (remoteTable.isSetViewExpandedText() || remoteTable.isSetViewOriginalText()) {
            return false;
        }

        String inputFileFormat = remoteTable.getSd().getInputFormat();
        if (inputFileFormat == null) {
            return false;
        }
        return SUPPORTED_HIVE_TOPN_LAZY_FILE_FORMATS.contains(inputFileFormat);
    }

    /**
     * Get the related remote hive metastore table.
     */
    public org.apache.hadoop.hive.metastore.api.Table getRemoteTable() {
        makeSureInitialized();
        return remoteTable;
    }

    @Override
    public List<Column> getFullSchema() {
        makeSureInitialized();
        ExternalSchemaCache cache = Env.getCurrentEnv().getExtMetaCacheMgr().getSchemaCache(catalog);
        if (getDlaType() == DLAType.HUDI) {
            return ((HudiDlaTable) dlaTable).getHudiSchemaCacheValue(MvccUtil.getSnapshotFromContext(this))
                    .getSchema();
        } else if (getDlaType() == DLAType.ICEBERG) {
            return IcebergUtils.getIcebergSchema(this, getCatalog(), getDbName(), getName());
        }
        Optional<SchemaCacheValue> schemaCacheValue = cache.getSchemaValue(dbName, name);
        return schemaCacheValue.map(SchemaCacheValue::getSchema).orElse(null);
    }

    public List<Type> getPartitionColumnTypes(Optional<MvccSnapshot> snapshot) {
        makeSureInitialized();
        if (getDlaType() == DLAType.HUDI) {
            return ((HudiDlaTable) dlaTable).getHudiSchemaCacheValue(snapshot).getPartitionColTypes();
        }
        Optional<SchemaCacheValue> schemaCacheValue = getSchemaCacheValue();
        return schemaCacheValue.map(value -> ((HMSSchemaCacheValue) value).getPartitionColTypes())
                .orElse(Collections.emptyList());
    }

    public List<Type> getHudiPartitionColumnTypes(long timestamp) {
        makeSureInitialized();
        ExternalSchemaCache cache = Env.getCurrentEnv().getExtMetaCacheMgr().getSchemaCache(catalog);
        Optional<SchemaCacheValue> schemaCacheValue = cache.getSchemaValue(
                new HudiSchemaCacheKey(dbName, name, timestamp));
        return schemaCacheValue.map(value -> ((HMSSchemaCacheValue) value).getPartitionColTypes())
                .orElse(Collections.emptyList());
    }

    public List<Column> getPartitionColumns() {
        return getPartitionColumns(MvccUtil.getSnapshotFromContext(this));
    }

    @Override
    public List<Column> getPartitionColumns(Optional<MvccSnapshot> snapshot) {
        makeSureInitialized();
        return dlaTable.getPartitionColumns(snapshot);
    }

    @Override
    public boolean supportInternalPartitionPruned() {
        return getDlaType() == DLAType.HIVE || getDlaType() == DLAType.HUDI;
    }

    public SelectedPartitions initHudiSelectedPartitions(Optional<TableSnapshot> tableSnapshot) {
        if (getDlaType() != DLAType.HUDI) {
            return SelectedPartitions.NOT_PRUNED;
        }

        if (getPartitionColumns().isEmpty()) {
            return SelectedPartitions.NOT_PRUNED;
        }
        TablePartitionValues tablePartitionValues = HudiUtils.getPartitionValues(tableSnapshot, this);

        Map<Long, PartitionItem> idToPartitionItem = tablePartitionValues.getIdToPartitionItem();
        Map<Long, String> idToNameMap = tablePartitionValues.getPartitionIdToNameMap();

        Map<String, PartitionItem> nameToPartitionItems = Maps.newHashMapWithExpectedSize(idToPartitionItem.size());
        for (Entry<Long, PartitionItem> entry : idToPartitionItem.entrySet()) {
            nameToPartitionItems.put(idToNameMap.get(entry.getKey()), entry.getValue());
        }

        return new SelectedPartitions(nameToPartitionItems.size(), nameToPartitionItems, false);
    }

    @Override
    public Map<String, PartitionItem> getNameToPartitionItems(Optional<MvccSnapshot> snapshot) {
        return getNameToPartitionItems();
    }

    public Map<String, PartitionItem> getNameToPartitionItems() {
        if (CollectionUtils.isEmpty(this.getPartitionColumns())) {
            return Collections.emptyMap();
        }
        HiveMetaStoreCache cache = Env.getCurrentEnv().getExtMetaCacheMgr()
                .getMetaStoreCache((HMSExternalCatalog) this.getCatalog());
        List<Type> partitionColumnTypes = this.getPartitionColumnTypes(MvccUtil.getSnapshotFromContext(this));
        HiveMetaStoreCache.HivePartitionValues hivePartitionValues = cache.getPartitionValues(
                this.getDbName(), this.getName(), partitionColumnTypes);
        Map<Long, PartitionItem> idToPartitionItem = hivePartitionValues.getIdToPartitionItem();
        // transfer id to name
        BiMap<Long, String> idToName = hivePartitionValues.getPartitionNameToIdMap().inverse();
        Map<String, PartitionItem> nameToPartitionItem = Maps.newHashMapWithExpectedSize(idToPartitionItem.size());
        for (Entry<Long, PartitionItem> entry : idToPartitionItem.entrySet()) {
            nameToPartitionItem.put(idToName.get(entry.getKey()), entry.getValue());
        }
        return nameToPartitionItem;
    }

    public boolean isHiveTransactionalTable() {
        return dlaType == DLAType.HIVE && AcidUtils.isTransactionalTable(remoteTable);
    }

    private boolean isSupportedFullAcidTransactionalFileFormat() {
        // Sometimes we meet "transactional" = "true" but format is parquet, which is not supported.
        // So we need to check the input format for transactional table.
        String inputFormatName = remoteTable.getSd().getInputFormat();
        return inputFormatName != null && SUPPORTED_HIVE_TRANSACTIONAL_FILE_FORMATS.contains(inputFormatName);
    }

    public boolean isFullAcidTable() throws UserException {
        if (dlaType == DLAType.HIVE && AcidUtils.isFullAcidTable(remoteTable)) {
            if (!isSupportedFullAcidTransactionalFileFormat()) {
                throw new UserException("This table is full Acid Table, but no Orc Format.");
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean isView() {
        makeSureInitialized();
        return remoteTable.isSetViewOriginalText() || remoteTable.isSetViewExpandedText();
    }

    @Override
    public String getComment() {
        return "";
    }

    @Override
    public long getCreateTime() {
        return 0;
    }

    private long getRowCountFromExternalSource() {
        long rowCount = UNKNOWN_ROW_COUNT;
        try {
            switch (dlaType) {
                case HIVE:
                    rowCount = StatisticsUtil.getHiveRowCount(this);
                    break;
                case ICEBERG:
                    rowCount = IcebergUtils.getIcebergRowCount(getCatalog(), getDbName(), getName());
                    break;
                default:
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("getRowCount for dlaType {} is not supported.", dlaType);
                    }
            }
        } catch (Exception e) {
            LOG.info("Failed to get row count for table {}.{}.{}", getCatalog().getName(), getDbName(), getName(), e);
        }
        return rowCount > 0 ? rowCount : UNKNOWN_ROW_COUNT;
    }

    @Override
    public long getDataLength() {
        return 0;
    }

    @Override
    public long getAvgRowLength() {
        return 0;
    }

    public long getLastCheckTime() {
        return 0;
    }

    /**
     * get the dla type for scan node to get right information.
     */
    public DLAType getDlaType() {
        makeSureInitialized();
        return dlaType;
    }

    @Override
    public TTableDescriptor toThrift() {
        List<Column> schema = getFullSchema();
        THiveTable tHiveTable = new THiveTable(dbName, name, new HashMap<>());
        TTableDescriptor tTableDescriptor = new TTableDescriptor(getId(), TTableType.HIVE_TABLE, schema.size(), 0,
                getName(), dbName);
        tTableDescriptor.setHiveTable(tHiveTable);
        return tTableDescriptor;
    }

    @Override
    public BaseAnalysisTask createAnalysisTask(AnalysisInfo info) {
        makeSureInitialized();
        return new HMSAnalysisTask(info);
    }

    public String getViewText() {
        String viewText = getViewExpandedText();
        if (StringUtils.isNotEmpty(viewText)) {
            if (!viewText.equals("/* Presto View */")) {
                return viewText;
            }
        }

        String originalText = getViewOriginalText();
        return parseTrinoViewDefinition(originalText);
    }

    /**
     * Parse Trino/Presto view definition from the original text.
     * The definition is stored in the format: /* Presto View: <base64-encoded-json> * /
     *
     * The base64 encoded JSON contains the following fields:
     * {
     *   "originalSql": "SELECT * FROM employees",  // The original SQL statement
     *   "catalog": "hive",                        // The data catalog name
     *   "schema": "mmc_hive",                     // The schema name
     *   ...
     * }
     *
     * @param originalText The original view definition text
     * @return The parsed SQL statement, or original text if parsing fails
     */
    private String parseTrinoViewDefinition(String originalText) {
        if (originalText == null || !originalText.contains("/* Presto View: ")) {
            return originalText;
        }

        try {
            String base64String = originalText.substring(
                    originalText.indexOf("/* Presto View: ") + "/* Presto View: ".length(),
                    originalText.lastIndexOf(" */")
            ).trim();
            byte[] decodedBytes = Base64.getDecoder().decode(base64String);
            String decodedString = new String(decodedBytes, StandardCharsets.UTF_8);
            JsonObject jsonObject = new Gson().fromJson(decodedString, JsonObject.class);

            if (jsonObject.has("originalSql")) {
                return jsonObject.get("originalSql").getAsString();
            }
        } catch (Exception e) {
            LOG.warn("Decoding Presto view definition failed", e);
        }
        return originalText;
    }

    public String getViewExpandedText() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("View expanded text of hms table [{}.{}.{}] : {}",
                    this.getCatalog().getName(), this.getDbName(), this.getName(), remoteTable.getViewExpandedText());
        }
        return remoteTable.getViewExpandedText();
    }

    public String getViewOriginalText() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("View original text of hms table [{}.{}.{}] : {}",
                    this.getCatalog().getName(), this.getDbName(), this.getName(), remoteTable.getViewOriginalText());
        }
        return remoteTable.getViewOriginalText();
    }

    public String getMetastoreUri() {
        return ((HMSExternalCatalog) catalog).getHiveMetastoreUris();
    }

    public Map<String, String> getCatalogProperties() {
        return catalog.getProperties();
    }

    public Map<StorageProperties.Type, StorageProperties> getStoragePropertiesMap() {
        return catalog.getCatalogProperty().getStoragePropertiesMap();
    }

    public Map<String, String> getHadoopProperties() {
        return catalog.getCatalogProperty().getHadoopProperties();
    }

    public List<ColumnStatisticsObj> getHiveTableColumnStats(List<String> columns) {
        HMSCachedClient client = ((HMSExternalCatalog) catalog).getClient();
        return client.getTableColumnStatistics(dbName, name, columns);
    }

    public Map<String, List<ColumnStatisticsObj>> getHivePartitionColumnStats(
            List<String> partNames, List<String> columns) {
        HMSCachedClient client = ((HMSExternalCatalog) catalog).getClient();
        return client.getPartitionColumnStatistics(dbName, name, partNames, columns);
    }

    public Partition getPartition(List<String> partitionValues) {
        HMSCachedClient client = ((HMSExternalCatalog) catalog).getClient();
        return client.getPartition(dbName, name, partitionValues);
    }

    @Override
    public Set<String> getPartitionNames() {
        makeSureInitialized();
        HMSCachedClient client = ((HMSExternalCatalog) catalog).getClient();
        List<String> names = client.listPartitionNames(dbName, name);
        return new HashSet<>(names);
    }

    @Override
    public Optional<SchemaCacheValue> initSchemaAndUpdateTime(SchemaCacheKey key) {
        org.apache.hadoop.hive.metastore.api.Table table = ((HMSExternalCatalog) catalog).getClient()
                .getTable(dbName, name);
        // try to use transient_lastDdlTime from hms client
        schemaUpdateTime = MapUtils.isNotEmpty(table.getParameters())
                && table.getParameters().containsKey(TBL_PROP_TRANSIENT_LAST_DDL_TIME)
                ? Long.parseLong(table.getParameters().get(TBL_PROP_TRANSIENT_LAST_DDL_TIME)) * 1000
                // use current timestamp if lastDdlTime does not exist (hive views don't have this prop)
                : System.currentTimeMillis();
        return initSchema(key);
    }

    public long getLastDdlTime() {
        makeSureInitialized();
        Map<String, String> parameters = remoteTable.getParameters();
        if (parameters == null || !parameters.containsKey(TBL_PROP_TRANSIENT_LAST_DDL_TIME)) {
            return 0L;
        }
        return Long.parseLong(parameters.get(TBL_PROP_TRANSIENT_LAST_DDL_TIME)) * 1000;
    }

    @Override
    public Optional<SchemaCacheValue> initSchema(SchemaCacheKey key) {
        makeSureInitialized();
        if (dlaType.equals(DLAType.ICEBERG)) {
            return getIcebergSchema(key);
        } else if (dlaType.equals(DLAType.HUDI)) {
            return getHudiSchema(key);
        } else {
            return getHiveSchema();
        }
    }

    private Optional<SchemaCacheValue> getIcebergSchema(SchemaCacheKey key) {
        return IcebergUtils.loadSchemaCacheValue(
            catalog, dbName, name, ((IcebergSchemaCacheKey) key).getSchemaId(), isView());
    }

    private Optional<SchemaCacheValue> getHudiSchema(SchemaCacheKey key) {
        boolean[] enableSchemaEvolution = {false};
        HudiSchemaCacheKey hudiSchemaCacheKey = (HudiSchemaCacheKey) key;
        InternalSchema hudiInternalSchema = HiveMetaStoreClientHelper.getHudiTableSchema(this, enableSchemaEvolution,
                Long.toString(hudiSchemaCacheKey.getTimestamp()));
        org.apache.avro.Schema hudiSchema = AvroInternalSchemaConverter.convert(hudiInternalSchema, name);
        List<Column> tmpSchema = Lists.newArrayListWithCapacity(hudiSchema.getFields().size());
        List<String> colTypes = Lists.newArrayList();
        for (int i = 0; i < hudiSchema.getFields().size(); i++) {
            Types.Field hudiInternalfield = hudiInternalSchema.getRecord().fields().get(i);
            org.apache.avro.Schema.Field hudiAvroField =  hudiSchema.getFields().get(i);
            String columnName = hudiAvroField.name().toLowerCase(Locale.ROOT);
            tmpSchema.add(new Column(columnName, HudiUtils.fromAvroHudiTypeToDorisType(hudiAvroField.schema()),
                    true, null, true, null, "", true, null,
                    hudiInternalfield.fieldId(), null));
            colTypes.add(HudiUtils.convertAvroToHiveType(hudiAvroField.schema()));
        }
        List<Column> partitionColumns = initPartitionColumns(tmpSchema);
        HudiSchemaCacheValue hudiSchemaCacheValue =
                new HudiSchemaCacheValue(tmpSchema, partitionColumns, enableSchemaEvolution[0]);
        hudiSchemaCacheValue.setColTypes(colTypes);
        return Optional.of(hudiSchemaCacheValue);
    }

    private Optional<SchemaCacheValue> getHiveSchema() {
        boolean getFromTable = catalog.getCatalogProperty()
                .getOrDefault(HMSExternalCatalog.GET_SCHEMA_FROM_TABLE, "false")
                .equalsIgnoreCase("true");
        List<FieldSchema> schema = null;
        Map<String, String> colDefaultValues = Maps.newHashMap();
        if (getFromTable) {
            schema = getSchemaFromRemoteTable();
        } else {
            HMSCachedClient client = ((HMSExternalCatalog) catalog).getClient();
            schema = client.getSchema(dbName, name);
            colDefaultValues = client.getDefaultColumnValues(dbName, name);
        }
        List<Column> columns = Lists.newArrayListWithCapacity(schema.size());
        for (FieldSchema field : schema) {
            String fieldName = field.getName().toLowerCase(Locale.ROOT);
            String defaultValue = colDefaultValues.getOrDefault(fieldName, null);
            columns.add(new Column(fieldName,
                    HiveMetaStoreClientHelper.hiveTypeToDorisType(field.getType()), true, null,
                    true, defaultValue, field.getComment(), true, -1));
        }
        List<Column> partitionColumns = initPartitionColumns(columns);
        return Optional.of(new HMSSchemaCacheValue(columns, partitionColumns));
    }

    private List<FieldSchema> getSchemaFromRemoteTable() {
        // Here we should get a new remote table instead of using this.remoteTable
        // Because we need to get the latest schema from HMS.
        Table newTable = ((HMSExternalCatalog) catalog).getClient().getTable(dbName, name);
        List<FieldSchema> schema = Lists.newArrayList();
        schema.addAll(newTable.getSd().getCols());
        schema.addAll(newTable.getPartitionKeys());
        return schema;
    }

    @Override
    public long fetchRowCount() {
        makeSureInitialized();
        // Get row count from hive metastore property.
        long rowCount = getRowCountFromExternalSource();
        // Only hive table supports estimate row count by listing file.
        if (rowCount == UNKNOWN_ROW_COUNT && dlaType.equals(DLAType.HIVE)) {
            LOG.info("Will estimate row count for table {} from file list.", name);
            rowCount = getRowCountFromFileList();
        }
        return rowCount;
    }

    private List<Column> initPartitionColumns(List<Column> schema) {
        List<String> partitionKeys = remoteTable.getPartitionKeys().stream().map(FieldSchema::getName)
                .collect(Collectors.toList());
        List<Column> partitionColumns = Lists.newArrayListWithCapacity(partitionKeys.size());
        for (String partitionKey : partitionKeys) {
            // Do not use "getColumn()", which will cause dead loop
            for (Column column : schema) {
                if (partitionKey.equalsIgnoreCase(column.getName())) {
                    // For partition column, if it is string type, change it to varchar(65535)
                    // to be same as doris managed table.
                    // This is to avoid some unexpected behavior such as different partition pruning result
                    // between doris managed table and external table.
                    if (column.getType().getPrimitiveType() == PrimitiveType.STRING) {
                        column.setType(ScalarType.createVarcharType(ScalarType.MAX_VARCHAR_LENGTH));
                    }
                    partitionColumns.add(column);
                    break;
                }
            }
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("get {} partition columns for table: {}", partitionColumns.size(), name);
        }
        return partitionColumns;
    }

    public boolean hasColumnStatistics(String colName) {
        Map<String, String> parameters = remoteTable.getParameters();
        return parameters.keySet().stream().anyMatch(k -> k.startsWith(SPARK_COL_STATS + colName + "."));
    }

    public boolean fillColumnStatistics(String colName, Map<StatsType, String> statsTypes, Map<String, String> stats) {
        makeSureInitialized();
        if (!hasColumnStatistics(colName)) {
            return false;
        }

        Map<String, String> parameters = remoteTable.getParameters();
        for (StatsType type : statsTypes.keySet()) {
            String key = SPARK_COL_STATS + colName + MAP_SPARK_STATS_TO_DORIS.getOrDefault(type, "-");
            // 'NULL' should not happen, spark would have all type (except histogram)
            stats.put(statsTypes.get(type), parameters.getOrDefault(key, "NULL"));
        }
        return true;
    }

    @Override
    public Optional<ColumnStatistic> getColumnStatistic(String colName) {
        makeSureInitialized();
        switch (dlaType) {
            case HIVE:
                return getHiveColumnStats(colName);
            case ICEBERG:
                if (GlobalVariable.enableFetchIcebergStats) {
                    return StatisticsUtil.getIcebergColumnStats(colName,
                            Env.getCurrentEnv().getExtMetaCacheMgr().getIcebergMetadataCache().getIcebergTable(
                                    catalog, dbName, name
                            ));
                } else {
                    break;
                }
            default:
                LOG.warn("get column stats for dlaType {} is not supported.", dlaType);
        }
        return Optional.empty();
    }

    private Optional<ColumnStatistic> getHiveColumnStats(String colName) {
        List<ColumnStatisticsObj> tableStats = getHiveTableColumnStats(Lists.newArrayList(colName));
        if (tableStats == null || tableStats.isEmpty()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug(String.format("No table stats found in Hive metastore for column %s in table %s.",
                        colName, name));
            }
            return Optional.empty();
        }

        Column column = getColumn(colName);
        if (column == null) {
            LOG.warn(String.format("No column %s in table %s.", colName, name));
            return Optional.empty();
        }
        Map<String, String> parameters = remoteTable.getParameters();
        if (!parameters.containsKey(NUM_ROWS) || Long.parseLong(parameters.get(NUM_ROWS)) == 0) {
            return Optional.empty();
        }
        long count = Long.parseLong(parameters.get(NUM_ROWS));
        ColumnStatisticBuilder columnStatisticBuilder = new ColumnStatisticBuilder(count);
        // The tableStats length is at most 1.
        for (ColumnStatisticsObj tableStat : tableStats) {
            if (!tableStat.isSetStatsData()) {
                continue;
            }
            ColumnStatisticsData data = tableStat.getStatsData();
            setStatData(column, data, columnStatisticBuilder, count);
        }

        return Optional.of(columnStatisticBuilder.build());
    }

    private void setStatData(Column col, ColumnStatisticsData data, ColumnStatisticBuilder builder, long count) {
        long ndv = 0;
        long nulls = 0;
        double colSize = 0;
        if (!data.isSetStringStats()) {
            colSize = count * col.getType().getSlotSize();
        }
        // Collect ndv, nulls, min and max for different data type.
        if (data.isSetLongStats()) {
            LongColumnStatsData longStats = data.getLongStats();
            ndv = longStats.getNumDVs();
            nulls = longStats.getNumNulls();
        } else if (data.isSetStringStats()) {
            StringColumnStatsData stringStats = data.getStringStats();
            ndv = stringStats.getNumDVs();
            nulls = stringStats.getNumNulls();
            double avgColLen = stringStats.getAvgColLen();
            colSize = Math.round(avgColLen * count);
        } else if (data.isSetDecimalStats()) {
            DecimalColumnStatsData decimalStats = data.getDecimalStats();
            ndv = decimalStats.getNumDVs();
            nulls = decimalStats.getNumNulls();
        } else if (data.isSetDoubleStats()) {
            DoubleColumnStatsData doubleStats = data.getDoubleStats();
            ndv = doubleStats.getNumDVs();
            nulls = doubleStats.getNumNulls();
        } else if (data.isSetDateStats()) {
            DateColumnStatsData dateStats = data.getDateStats();
            ndv = dateStats.getNumDVs();
            nulls = dateStats.getNumNulls();
        } else {
            LOG.warn(String.format("Not suitable data type for column %s", col.getName()));
        }
        builder.setNdv(ndv);
        builder.setNumNulls(nulls);
        builder.setDataSize(colSize);
        builder.setAvgSizeByte(colSize / count);
        builder.setMinValue(Double.NEGATIVE_INFINITY);
        builder.setMaxValue(Double.POSITIVE_INFINITY);
    }

    public void setEventUpdateTime(long updateTime) {
        this.eventUpdateTime = updateTime;
    }

    @Override
    // get the max value of `schemaUpdateTime` and `eventUpdateTime`
    // eventUpdateTime will be refreshed after processing events with hms event listener enabled
    public long getUpdateTime() {
        return Math.max(this.schemaUpdateTime, this.eventUpdateTime);
    }

    @Override
    public void gsonPostProcess() throws IOException {
        super.gsonPostProcess();
    }

    @Override
    public List<Long> getChunkSizes() {
        HiveMetaStoreCache.HivePartitionValues partitionValues = getAllPartitionValues();
        List<HiveMetaStoreCache.FileCacheValue> filesByPartitions = getFilesForPartitions(partitionValues, 0);
        List<Long> result = Lists.newArrayList();
        for (HiveMetaStoreCache.FileCacheValue files : filesByPartitions) {
            for (HiveMetaStoreCache.HiveFileStatus file : files.getFiles()) {
                result.add(file.getLength());
            }
        }
        return result;
    }

    @Override
    public long getDataSize(boolean singleReplica) {
        long totalSize = StatisticsUtil.getTotalSizeFromHMS(this);
        // Usually, we can get total size from HMS parameter.
        if (totalSize > 0) {
            return totalSize;
        }
        // If not found the size in HMS, calculate it by sum all files' size in table.
        List<Long> chunkSizes = getChunkSizes();
        long total = 0;
        for (long size : chunkSizes) {
            total += size;
        }
        return total;
    }

    @Override
    public boolean isDistributionColumn(String columnName) {
        return getRemoteTable().getSd().getBucketCols().stream().map(String::toLowerCase)
                .collect(Collectors.toSet()).contains(columnName.toLowerCase());
    }

    @Override
    public Set<String> getDistributionColumnNames() {
        return getRemoteTable().getSd().getBucketCols().stream().map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    @Override
    public PartitionType getPartitionType(Optional<MvccSnapshot> snapshot) {
        makeSureInitialized();
        return dlaTable.getPartitionType(snapshot);
    }

    public Set<String> getPartitionColumnNames() {
        return getPartitionColumnNames(MvccUtil.getSnapshotFromContext(this));
    }

    @Override
    public Set<String> getPartitionColumnNames(Optional<MvccSnapshot> snapshot) {
        makeSureInitialized();
        return dlaTable.getPartitionColumnNames(snapshot);
    }

    @Override
    public Map<String, PartitionItem> getAndCopyPartitionItems(Optional<MvccSnapshot> snapshot)
            throws AnalysisException {
        makeSureInitialized();
        return dlaTable.getAndCopyPartitionItems(snapshot);
    }

    @Override
    public MTMVSnapshotIf getPartitionSnapshot(String partitionName, MTMVRefreshContext context,
            Optional<MvccSnapshot> snapshot) throws AnalysisException {
        makeSureInitialized();
        return dlaTable.getPartitionSnapshot(partitionName, context, snapshot);
    }

    @Override
    public MTMVSnapshotIf getTableSnapshot(MTMVRefreshContext context, Optional<MvccSnapshot> snapshot)
            throws AnalysisException {
        makeSureInitialized();
        return dlaTable.getTableSnapshot(context, snapshot);
    }

    @Override
    public MTMVSnapshotIf getTableSnapshot(Optional<MvccSnapshot> snapshot) throws AnalysisException {
        makeSureInitialized();
        return dlaTable.getTableSnapshot(snapshot);
    }

    @Override
    public long getNewestUpdateVersionOrTime() {
        HiveMetaStoreCache cache = Env.getCurrentEnv().getExtMetaCacheMgr()
                .getMetaStoreCache((HMSExternalCatalog) getCatalog());
        HiveMetaStoreCache.HivePartitionValues hivePartitionValues = cache.getPartitionValues(getDbName(), getName(),
                getPartitionColumnTypes(MvccUtil.getSnapshotFromContext(this)));
        List<HivePartition> partitionList = cache.getAllPartitionsWithCache(getDbName(), getName(),
                Lists.newArrayList(hivePartitionValues.getPartitionValuesMap().values()));
        if (CollectionUtils.isEmpty(partitionList)) {
            return 0;
        }
        return partitionList.stream().mapToLong(HivePartition::getLastModifiedTime).max().orElse(0);
    }

    @Override
    public boolean isPartitionColumnAllowNull() {
        makeSureInitialized();
        return dlaTable.isPartitionColumnAllowNull();
    }

    /**
     * Estimate hive table row count : totalFileSize/estimatedRowSize
     */
    private long getRowCountFromFileList() {
        if (!GlobalVariable.enable_get_row_count_from_file_list) {
            return UNKNOWN_ROW_COUNT;
        }
        if (isView()) {
            LOG.info("Table {} is view, return -1.", name);
            return UNKNOWN_ROW_COUNT;
        }
        long rows = UNKNOWN_ROW_COUNT;
        try {
            HiveMetaStoreCache.HivePartitionValues partitionValues = getAllPartitionValues();
            // Get files for all partitions.
            int samplePartitionSize = Config.hive_stats_partition_sample_size;
            List<HiveMetaStoreCache.FileCacheValue> filesByPartitions =
                    getFilesForPartitions(partitionValues, samplePartitionSize);
            LOG.info("Number of files selected for hive table {} is {}", name, filesByPartitions.size());
            long totalSize = 0;
            // Calculate the total file size.
            for (HiveMetaStoreCache.FileCacheValue files : filesByPartitions) {
                for (HiveMetaStoreCache.HiveFileStatus file : files.getFiles()) {
                    totalSize += file.getLength();
                }
            }
            // Estimate row count: totalSize/estimatedRowSize
            long estimatedRowSize = 0;
            List<Column> partitionColumns = getPartitionColumns();
            for (Column column : getFullSchema()) {
                // Partition column shouldn't count to the row size, because it is not in the data file.
                if (partitionColumns != null && partitionColumns.contains(column)) {
                    continue;
                }
                estimatedRowSize += column.getDataType().getSlotSize();
            }
            if (estimatedRowSize == 0) {
                LOG.warn("Table {} estimated size is 0, return -1.", name);
                return UNKNOWN_ROW_COUNT;
            }

            int totalPartitionSize = partitionValues == null ? 1 : partitionValues.getIdToPartitionItem().size();
            if (samplePartitionSize != 0 && samplePartitionSize < totalPartitionSize) {
                LOG.info("Table {} sampled {} of {} partitions, sampled size is {}",
                        name, samplePartitionSize, totalPartitionSize, totalSize);
                totalSize = totalSize * totalPartitionSize / samplePartitionSize;
            }
            rows = totalSize / estimatedRowSize;
            LOG.info("Table {} rows {}, total size is {}, estimatedRowSize is {}",
                    name, rows, totalSize, estimatedRowSize);
        } catch (Exception e) {
            LOG.info("Failed to get row count for table {}.{}.{}", getCatalog().getName(), getDbName(), getName(), e);
        }
        return rows > 0 ? rows : UNKNOWN_ROW_COUNT;
    }

    // Get all partition values from cache.
    private HiveMetaStoreCache.HivePartitionValues getAllPartitionValues() {
        if (isView()) {
            return null;
        }
        HiveMetaStoreCache cache = Env.getCurrentEnv().getExtMetaCacheMgr()
                .getMetaStoreCache((HMSExternalCatalog) catalog);
        List<Type> partitionColumnTypes = getPartitionColumnTypes(MvccUtil.getSnapshotFromContext(this));
        HiveMetaStoreCache.HivePartitionValues partitionValues = null;
        // Get table partitions from cache.
        if (!partitionColumnTypes.isEmpty()) {
            // It is ok to get partition values from cache,
            // no need to worry that this call will invalid or refresh the cache.
            // because it has enough space to keep partition info of all tables in cache.
            partitionValues = cache.getPartitionValues(dbName, name, partitionColumnTypes);
            if (partitionValues == null || partitionValues.getPartitionNameToIdMap() == null) {
                LOG.warn("Partition values for hive table {} is null", name);
            } else {
                LOG.info("Partition values size for hive table {} is {}",
                        name, partitionValues.getPartitionNameToIdMap().size());
            }
        }
        return partitionValues;
    }

    // Get all files related to given partition values
    // If sampleSize > 0, randomly choose part of partitions of the whole table.
    private List<HiveMetaStoreCache.FileCacheValue> getFilesForPartitions(
            HiveMetaStoreCache.HivePartitionValues partitionValues, int sampleSize) {
        if (isView()) {
            return Lists.newArrayList();
        }
        HiveMetaStoreCache cache = Env.getCurrentEnv().getExtMetaCacheMgr()
                .getMetaStoreCache((HMSExternalCatalog) catalog);
        List<HivePartition> hivePartitions = Lists.newArrayList();
        if (partitionValues != null) {
            Map<Long, PartitionItem> idToPartitionItem = partitionValues.getIdToPartitionItem();
            int totalPartitionSize = idToPartitionItem.size();
            Collection<PartitionItem> partitionItems;
            List<List<String>> partitionValuesList;
            // If partition number is too large, randomly choose part of them to estimate the whole table.
            if (sampleSize > 0 && sampleSize < totalPartitionSize) {
                List<PartitionItem> items = new ArrayList<>(idToPartitionItem.values());
                Collections.shuffle(items);
                partitionItems = items.subList(0, sampleSize);
                partitionValuesList = Lists.newArrayListWithCapacity(sampleSize);
            } else {
                partitionItems = idToPartitionItem.values();
                partitionValuesList = Lists.newArrayListWithCapacity(totalPartitionSize);
            }
            for (PartitionItem item : partitionItems) {
                partitionValuesList.add(((ListPartitionItem) item).getItems().get(0).getPartitionValuesAsStringList());
            }
            // get partitions without cache, so that it will not invalid the cache when executing
            // non query request such as `show table status`
            hivePartitions = cache.getAllPartitionsWithoutCache(dbName, name, partitionValuesList);
            LOG.info("Partition list size for hive partition table {} is {}", name, hivePartitions.size());
        } else {
            hivePartitions.add(new HivePartition(dbName, name, true,
                    getRemoteTable().getSd().getInputFormat(),
                    getRemoteTable().getSd().getLocation(), null, Maps.newHashMap()));
        }
        // Get files for all partitions.
        if (LOG.isDebugEnabled()) {
            for (HivePartition partition : hivePartitions) {
                LOG.debug("Chosen partition for table {}. [{}]", name, partition.toString());
            }
        }
        return cache.getFilesByPartitionsWithoutCache(hivePartitions,
                new FileSystemDirectoryLister(), null);
    }

    @Override
    public boolean isPartitionedTable() {
        makeSureInitialized();
        return !isView() && remoteTable.getPartitionKeysSize() > 0;
    }

    @Override
    public void beforeMTMVRefresh(MTMV mtmv) throws DdlException {
    }

    @Override
    public MvccSnapshot loadSnapshot(Optional<TableSnapshot> tableSnapshot, Optional<TableScanParams> scanParams) {
        if (getDlaType() == DLAType.HUDI) {
            return HudiUtils.getHudiMvccSnapshot(tableSnapshot, this);
        } else if (getDlaType() == DLAType.ICEBERG) {
            return new IcebergMvccSnapshot(
                IcebergUtils.getIcebergSnapshotCacheValue(
                    tableSnapshot, getCatalog(), getDbName(), getName(), scanParams));
        } else {
            return new EmptyMvccSnapshot();
        }
    }

    public boolean firstColumnIsString() {
        List<Column> columns = getColumns();
        if (columns == null || columns.isEmpty()) {
            return false;
        }
        return columns.get(0).getType().isScalarType(PrimitiveType.STRING);
    }

    public HoodieTableMetaClient getHudiClient() {
        return Env.getCurrentEnv()
            .getExtMetaCacheMgr()
            .getMetaClientProcessor(getCatalog())
            .getHoodieTableMetaClient(
                getDbName(),
                getName(),
                getRemoteTable().getSd().getLocation(),
                getCatalog().getConfiguration());
    }

    public boolean isValidRelatedTable() {
        makeSureInitialized();
        return dlaTable.isValidRelatedTable();
    }

    @Override
    public List<SysTable> getSupportedSysTables() {
        makeSureInitialized();
        switch (dlaType) {
            case HIVE:
                return SupportedSysTables.HIVE_SUPPORTED_SYS_TABLES;
            case ICEBERG:
                return SupportedSysTables.ICEBERG_SUPPORTED_SYS_TABLES;
            case HUDI:
                return SupportedSysTables.HUDI_SUPPORTED_SYS_TABLES;
            default:
                return Lists.newArrayList();
        }
    }

}
