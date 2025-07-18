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

package org.apache.doris.catalog;

import org.apache.doris.catalog.Resource.ResourceType;
import org.apache.doris.common.DdlException;
import org.apache.doris.common.FeConstants;
import org.apache.doris.common.io.DeepCopy;
import org.apache.doris.datasource.ExternalFunctionRules;
import org.apache.doris.thrift.TJdbcTable;
import org.apache.doris.thrift.TOdbcTableType;
import org.apache.doris.thrift.TTableDescriptor;
import org.apache.doris.thrift.TTableType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.gson.annotations.SerializedName;
import lombok.Setter;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections.map.CaseInsensitiveMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Setter
public class JdbcTable extends Table {
    private static final Logger LOG = LogManager.getLogger(JdbcTable.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String CATALOG_ID = "catalog_id";
    private static final String TABLE = "table";
    private static final String REMOTE_DATABASE = "remote_database";
    private static final String REMOTE_TABLE = "remote_table";
    private static final String REMOTE_COLUMNS = "remote_columns";
    private static final String RESOURCE = "resource";
    private static final String TABLE_TYPE = "table_type";
    private static final String URL = "jdbc_url";
    private static final String USER = "user";
    private static final String PASSWORD = "password";
    private static final String DRIVER_CLASS = "driver_class";
    private static final String DRIVER_URL = "driver_url";
    private static final String CHECK_SUM = "checksum";
    private static Map<String, TOdbcTableType> TABLE_TYPE_MAP;
    @SerializedName("rn")
    private String resourceName;
    @SerializedName("etn")
    private String externalTableName;

    // real name only for jdbc catalog
    @SerializedName("rdn")
    private String remoteDatabaseName;
    @SerializedName("rtn")
    private String remoteTableName;
    @SerializedName("rcn")
    private Map<String, String> remoteColumnNames;

    @SerializedName("jtn")
    private String jdbcTypeName;

    @SerializedName("jurl")
    private String jdbcUrl;
    @SerializedName("jusr")
    private String jdbcUser;
    @SerializedName("jpwd")
    private String jdbcPasswd;
    @SerializedName("dc")
    private String driverClass;
    @SerializedName("du")
    private String driverUrl;
    @SerializedName("cs")
    private String checkSum;

    @SerializedName("cid")
    private long catalogId = -1;

    private int connectionPoolMinSize;
    private int connectionPoolMaxSize;
    private int connectionPoolMaxWaitTime;
    private int connectionPoolMaxLifeTime;
    private boolean connectionPoolKeepAlive;

    private ExternalFunctionRules functionRules;

    static {
        Map<String, TOdbcTableType> tempMap = new CaseInsensitiveMap();
        tempMap.put("mysql", TOdbcTableType.MYSQL);
        tempMap.put("postgresql", TOdbcTableType.POSTGRESQL);
        tempMap.put("sqlserver", TOdbcTableType.SQLSERVER);
        tempMap.put("oracle", TOdbcTableType.ORACLE);
        tempMap.put("clickhouse", TOdbcTableType.CLICKHOUSE);
        tempMap.put("sap_hana", TOdbcTableType.SAP_HANA);
        tempMap.put("trino", TOdbcTableType.TRINO);
        tempMap.put("presto", TOdbcTableType.PRESTO);
        tempMap.put("oceanbase", TOdbcTableType.OCEANBASE);
        tempMap.put("oceanbase_oracle", TOdbcTableType.OCEANBASE_ORACLE);
        tempMap.put("db2", TOdbcTableType.DB2);
        tempMap.put("gbase", TOdbcTableType.GBASE);
        TABLE_TYPE_MAP = Collections.unmodifiableMap(tempMap);
    }

    public JdbcTable() {
        super(TableType.JDBC);
    }

    public JdbcTable(long id, String name, List<Column> schema, Map<String, String> properties)
            throws DdlException {
        super(id, name, TableType.JDBC, schema);
        validate(properties);
        // check and set external function rules
        checkAndSetExternalFunctionRules(properties);
    }

    public JdbcTable(long id, String name, List<Column> schema, TableType type) {
        super(id, name, type, schema);
    }

    public String getInsertSql(List<String> insertCols) {
        StringBuilder sb = new StringBuilder("INSERT INTO ");
        sb.append(getProperRemoteFullTableName(TABLE_TYPE_MAP.get(getTableTypeName())));
        sb.append("(");
        List<String> transformedInsertCols = insertCols.stream()
                .map(col -> getProperRemoteColumnName(TABLE_TYPE_MAP.get(getTableTypeName()), col))
                .collect(Collectors.toList());
        sb.append(String.join(",", transformedInsertCols));
        sb.append(")");
        sb.append(" VALUES (");
        for (int i = 0; i < insertCols.size(); ++i) {
            if (i != 0) {
                sb.append(", ");
            }
            sb.append("?");
        }
        sb.append(")");
        return sb.toString();
    }

    public String getCheckSum() {
        return checkSum;
    }

    public String getExternalTableName() {
        return externalTableName;
    }

    public String getJdbcTypeName() {
        return jdbcTypeName;
    }

    public String getJdbcUrl() {
        return getFromJdbcResourceOrDefault(JdbcResource.JDBC_URL, jdbcUrl);
    }

    public String getJdbcUser() {
        return getFromJdbcResourceOrDefault(JdbcResource.USER, jdbcUser);
    }

    public String getJdbcPasswd() {
        return getFromJdbcResourceOrDefault(JdbcResource.PASSWORD, jdbcPasswd);
    }

    public String getDriverClass() {
        return getFromJdbcResourceOrDefault(JdbcResource.DRIVER_CLASS, driverClass);
    }

    public String getDriverUrl() {
        return getFromJdbcResourceOrDefault(JdbcResource.DRIVER_URL, driverUrl);
    }

    public long getCatalogId() {
        return catalogId;
    }

    public int getConnectionPoolMinSize() {
        return Integer.parseInt(getFromJdbcResourceOrDefault(JdbcResource.CONNECTION_POOL_MIN_SIZE,
                String.valueOf(connectionPoolMinSize)));
    }

    public int getConnectionPoolMaxSize() {
        return Integer.parseInt(getFromJdbcResourceOrDefault(JdbcResource.CONNECTION_POOL_MAX_SIZE,
                String.valueOf(connectionPoolMaxSize)));
    }

    public int getConnectionPoolMaxWaitTime() {
        return Integer.parseInt(getFromJdbcResourceOrDefault(JdbcResource.CONNECTION_POOL_MAX_WAIT_TIME,
                String.valueOf(connectionPoolMaxWaitTime)));
    }

    public int getConnectionPoolMaxLifeTime() {
        return Integer.parseInt(getFromJdbcResourceOrDefault(JdbcResource.CONNECTION_POOL_MAX_LIFE_TIME,
                String.valueOf(connectionPoolMaxLifeTime)));
    }

    public boolean isConnectionPoolKeepAlive() {
        return Boolean.parseBoolean(getFromJdbcResourceOrDefault(JdbcResource.CONNECTION_POOL_KEEP_ALIVE,
                String.valueOf(connectionPoolKeepAlive)));
    }

    private String getFromJdbcResourceOrDefault(String key, String defaultVal) {
        if (Strings.isNullOrEmpty(resourceName)) {
            return defaultVal;
        }
        Resource resource = Env.getCurrentEnv().getResourceMgr().getResource(resourceName);
        if (resource instanceof JdbcResource) {
            return ((JdbcResource) resource).getProperty(key);
        }
        return defaultVal;
    }

    @Override
    public TTableDescriptor toThrift() {
        TJdbcTable tJdbcTable = new TJdbcTable();
        tJdbcTable.setCatalogId(catalogId);
        tJdbcTable.setJdbcUrl(getJdbcUrl());
        tJdbcTable.setJdbcUser(getJdbcUser());
        tJdbcTable.setJdbcPassword(getJdbcPasswd());
        tJdbcTable.setJdbcTableName(externalTableName);
        tJdbcTable.setJdbcDriverClass(getDriverClass());
        tJdbcTable.setJdbcDriverUrl(getDriverUrl());
        tJdbcTable.setJdbcResourceName(resourceName);
        tJdbcTable.setJdbcDriverChecksum(checkSum);
        tJdbcTable.setConnectionPoolMinSize(getConnectionPoolMinSize());
        tJdbcTable.setConnectionPoolMaxSize(getConnectionPoolMaxSize());
        tJdbcTable.setConnectionPoolMaxWaitTime(getConnectionPoolMaxWaitTime());
        tJdbcTable.setConnectionPoolMaxLifeTime(getConnectionPoolMaxLifeTime());
        tJdbcTable.setConnectionPoolKeepAlive(isConnectionPoolKeepAlive());
        TTableDescriptor tTableDescriptor = new TTableDescriptor(getId(), TTableType.JDBC_TABLE, fullSchema.size(), 0,
                getName(), "");
        tTableDescriptor.setJdbcTable(tJdbcTable);
        return tTableDescriptor;
    }

    public String getResourceName() {
        return resourceName;
    }

    public String getJdbcTable() {
        return externalTableName;
    }

    public String getRemoteDatabaseName() {
        return remoteDatabaseName;
    }

    public String getRemoteTableName() {
        return remoteTableName;
    }

    public String getProperRemoteFullTableName(TOdbcTableType tableType) {
        if (remoteDatabaseName == null || remoteTableName == null) {
            return databaseProperName(tableType, externalTableName);
        } else {
            return properNameWithRemoteName(tableType, remoteDatabaseName) + "." + properNameWithRemoteName(tableType,
                    remoteTableName);
        }
    }

    public String getProperRemoteColumnName(TOdbcTableType tableType, String columnName) {
        if (remoteColumnNames == null || remoteColumnNames.isEmpty() || !remoteColumnNames.containsKey(columnName)) {
            return databaseProperName(tableType, columnName);
        } else {
            return properNameWithRemoteName(tableType, remoteColumnNames.get(columnName));
        }
    }

    public String getTableTypeName() {
        return jdbcTypeName;
    }

    public TOdbcTableType getJdbcTableType() {
        return TABLE_TYPE_MAP.get(getTableTypeName());
    }

    @Override
    public String getSignature(int signatureVersion) {
        StringBuilder sb = new StringBuilder(signatureVersion);
        sb.append(name);
        sb.append(type);
        sb.append(resourceName);
        sb.append(externalTableName);
        sb.append(jdbcUrl);
        sb.append(jdbcUser);
        sb.append(jdbcPasswd);
        sb.append(driverClass);
        sb.append(driverUrl);
        sb.append(checkSum);

        String md5 = DigestUtils.md5Hex(sb.toString());
        if (LOG.isDebugEnabled()) {
            LOG.debug("get signature of odbc table {}: {}. signature string: {}", name, md5, sb.toString());
        }
        return md5;
    }

    @Override
    public JdbcTable clone() {
        JdbcTable copied = DeepCopy.copy(this, JdbcTable.class, FeConstants.meta_version);
        if (copied == null) {
            LOG.warn("failed to copy jdbc table: " + getName());
            return null;
        }
        return copied;
    }

    private void validate(Map<String, String> properties) throws DdlException {
        if (properties == null) {
            throw new DdlException("Please set properties of jdbc table, "
                    + "they are: host, port, user, password, database and table");
        }

        externalTableName = properties.get(TABLE);
        if (Strings.isNullOrEmpty(externalTableName)) {
            throw new DdlException("property " + TABLE + " must be set");
        }

        resourceName = properties.get(RESOURCE);
        if (Strings.isNullOrEmpty(resourceName)) {
            throw new DdlException("property " + RESOURCE + " must be set");
        }

        jdbcTypeName = properties.get(TABLE_TYPE);
        if (Strings.isNullOrEmpty(jdbcTypeName)) {
            throw new DdlException("property " + TABLE_TYPE + " must be set");
        }

        if (!TABLE_TYPE_MAP.containsKey(jdbcTypeName.toLowerCase())) {
            throw new DdlException("Unknown jdbc table type: " + jdbcTypeName);
        }

        Resource resource = Env.getCurrentEnv().getResourceMgr().getResource(resourceName);
        if (resource == null) {
            throw new DdlException("jdbc resource [" + resourceName + "] not exists");
        }
        if (resource.getType() != ResourceType.JDBC) {
            throw new DdlException("resource [" + resourceName + "] is not jdbc resource");
        }

        JdbcResource jdbcResource = (JdbcResource) resource;
        jdbcUrl = jdbcResource.getProperty(URL);
        jdbcUser = jdbcResource.getProperty(USER);
        jdbcPasswd = jdbcResource.getProperty(PASSWORD);
        driverClass = jdbcResource.getProperty(DRIVER_CLASS);
        driverUrl = jdbcResource.getProperty(DRIVER_URL);
        checkSum = jdbcResource.getProperty(CHECK_SUM);
        connectionPoolMinSize = Integer.parseInt(jdbcResource.getProperty(JdbcResource.CONNECTION_POOL_MIN_SIZE));
        connectionPoolMaxSize = Integer.parseInt(jdbcResource.getProperty(JdbcResource.CONNECTION_POOL_MAX_SIZE));
        connectionPoolMaxWaitTime = Integer.parseInt(
                jdbcResource.getProperty(JdbcResource.CONNECTION_POOL_MAX_WAIT_TIME));
        connectionPoolMaxLifeTime = Integer.parseInt(
                jdbcResource.getProperty(JdbcResource.CONNECTION_POOL_MAX_LIFE_TIME));
        connectionPoolKeepAlive = Boolean.parseBoolean(
                jdbcResource.getProperty(JdbcResource.CONNECTION_POOL_KEEP_ALIVE));

        String urlType = jdbcUrl.split(":")[1];
        if (!jdbcTypeName.equalsIgnoreCase(urlType)) {
            if (!(jdbcTypeName.equalsIgnoreCase("oceanbase_oracle") && urlType.equalsIgnoreCase("oceanbase"))
                    && !(jdbcTypeName.equalsIgnoreCase("sap_hana") && urlType.equalsIgnoreCase("sap"))) {
                throw new DdlException("property " + TABLE_TYPE + " must be same with resource url");
            }
        }
    }

    private void checkAndSetExternalFunctionRules(Map<String, String> properties) throws DdlException {
        ExternalFunctionRules.check(properties.getOrDefault(JdbcResource.FUNCTION_RULES, ""));
        this.functionRules = ExternalFunctionRules.create(jdbcTypeName,
                properties.getOrDefault(JdbcResource.FUNCTION_RULES, ""));
    }

    /**
     * Formats the provided name (for example, a database, table, or schema name) according to the specified parameters.
     *
     * @param name The name to be formatted.
     * @param wrapStart The character(s) to be added at the start of each name component.
     * @param wrapEnd The character(s) to be added at the end of each name component.
     * @param toUpperCase If true, convert the name to upper case.
     * @param toLowerCase If true, convert the name to lower case.
     *         <p>
     *         Note: If both toUpperCase and toLowerCase are true, the name will ultimately be converted to lower case.
     *         <p>
     *         The name is expected to be in the format of 'schemaName.tableName'. If there is no '.',
     *         the function will treat the entire string as one name component.
     *         If there is a '.', the function will treat the string before the first '.' as the schema name
     *         and the string after the '.' as the table name.
     * @return The formatted name.
     */
    public static String formatName(String name, String wrapStart, String wrapEnd, boolean toUpperCase,
            boolean toLowerCase) {
        int index = name.indexOf(".");
        if (index == -1) { // No dot in the name
            String newName = toUpperCase ? name.toUpperCase() : name;
            newName = toLowerCase ? newName.toLowerCase() : newName;
            return wrapStart + newName + wrapEnd;
        } else {
            String schemaName = toUpperCase ? name.substring(0, index).toUpperCase() : name.substring(0, index);
            schemaName = toLowerCase ? schemaName.toLowerCase() : schemaName;
            String tableName = toUpperCase ? name.substring(index + 1).toUpperCase() : name.substring(index + 1);
            tableName = toLowerCase ? tableName.toLowerCase() : tableName;
            return wrapStart + schemaName + wrapEnd + "." + wrapStart + tableName + wrapEnd;
        }
    }

    /**
     * Formats a database name according to the database type.
     * <p>
     * Rules:
     * - MYSQL, OCEANBASE: Wrap with backticks (`), case unchanged. Example: mySchema.myTable -> `mySchema.myTable`
     * - SQLSERVER: Wrap with square brackets ([]), case unchanged. Example: mySchema.myTable -> [mySchema].[myTable]
     * - POSTGRESQL, CLICKHOUSE, TRINO, OCEANBASE_ORACLE, SAP_HANA: Wrap with double quotes ("), case unchanged.
     * Example: mySchema.myTable -> "mySchema"."myTable"
     * - ORACLE: Wrap with double quotes ("), convert to upper case. Example: mySchema.myTable -> "MYSCHEMA"."MYTABLE"
     * For other types, the name is returned as is.
     *
     * @param tableType The database type.
     * @param name The name to be formatted, expected in 'schemaName.tableName' format. If no '.', treats entire string
     *         as one name component. If '.', treats string before first '.' as schema name and after as table name.
     * @return The formatted name.
     */
    public static String databaseProperName(TOdbcTableType tableType, String name) {
        switch (tableType) {
            case MYSQL:
            case OCEANBASE:
            case GBASE:
                return formatName(name, "`", "`", false, false);
            case SQLSERVER:
                return formatName(name, "[", "]", false, false);
            case POSTGRESQL:
            case CLICKHOUSE:
            case TRINO:
            case PRESTO:
            case OCEANBASE_ORACLE:
            case SAP_HANA:
                return formatName(name, "\"", "\"", false, false);
            case ORACLE:
            case DB2:
                return formatName(name, "\"", "\"", true, false);
            default:
                return name;
        }
    }

    public static String properNameWithRemoteName(TOdbcTableType tableType, String remoteName) {
        switch (tableType) {
            case MYSQL:
            case OCEANBASE:
            case GBASE:
                return formatNameWithRemoteName(remoteName, "`", "`");
            case SQLSERVER:
                return formatNameWithRemoteName(remoteName, "[", "]");
            case POSTGRESQL:
            case CLICKHOUSE:
            case TRINO:
            case PRESTO:
            case OCEANBASE_ORACLE:
            case ORACLE:
            case SAP_HANA:
            case DB2:
                return formatNameWithRemoteName(remoteName, "\"", "\"");
            default:
                return remoteName;
        }
    }

    public static String formatNameWithRemoteName(String remoteName, String wrapStart, String wrapEnd) {
        return wrapStart + remoteName + wrapEnd;
    }

    // This is used when converting JdbcExternalTable to JdbcTable.
    public void setExternalFunctionRules(ExternalFunctionRules functionRules) {
        this.functionRules = functionRules;
    }

    public ExternalFunctionRules getExternalFunctionRules() {
        return functionRules;
    }
}
