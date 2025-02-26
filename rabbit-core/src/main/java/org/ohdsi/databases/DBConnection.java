/*******************************************************************************
 * Copyright 2023 Observational Health Data Sciences and Informatics & The Hyve
 *
 * This file is part of WhiteRabbit
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.ohdsi.databases;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.ohdsi.databases.configuration.DbType;
import org.ohdsi.utilities.files.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * DBConnection is a wrapper for java.sql.Connection
 *
 *
 * The latter one instantiates a java.sql.Connection instance itself.
 * The constructors of DBConnection ensure that one of the following is true:
 *  - a java.sql.Connection implementing object is provided, and used it its methods
 *  - a StorageHandler implementing object is provided, and used to create a java.sql.Connection interface
 *  - if neither of the above is valid at construction, a RuntimeException is thrown
 *
 * DBConnection provides a partial subset of the java.sql.Connection interface, just enough to satisfy the
 * needs of WhiteRabbit
 */
public class DBConnection {
    Logger logger = LoggerFactory.getLogger(DBConnection.class);

    private final Connection connection;
    private final DbType dbType;
    private boolean verbose;
    private final StorageHandler connectorInterface;
    private static DecimalFormat decimalFormat		= new DecimalFormat("#.#");


    public DBConnection(Connection connection, DbType dbType, boolean verbose) {
        this.connection = connection;
        this.dbType = dbType;
        this.connectorInterface = null;
        this.verbose = verbose;
    }

    public DBConnection(StorageHandler connectorInterface, DbType dbType, boolean verbose) {
        this.connectorInterface = connectorInterface;
        connectorInterface.checkInitialised();
        this.connection = connectorInterface.getDBConnection().getConnection();
        this.dbType = dbType;
        this.verbose = verbose;
    }

    public Connection getConnection() {
        return this.connection;
    }

    public StorageHandler getStorageHandler() {
        this.connectorInterface.checkInitialised();
        return this.connectorInterface;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public boolean hasStorageHandler() {
        return this.connectorInterface != null;
    }

    public Statement createStatement(int typeForwardOnly, int concurReadOnly) throws SQLException {
        return this.connection.createStatement(typeForwardOnly, concurReadOnly);
    }

    public DatabaseMetaData getMetaData() throws SQLException {
        return this.connection.getMetaData();
    }

    public void use(String database, DbType dbType) {
        if (this.hasStorageHandler()) {
            this.getStorageHandler().use(database);
        } else {
            if (database == null || dbType == DbType.MS_ACCESS || dbType == DbType.BIGQUERY || dbType == DbType.AZURE || dbType == DbType.IMPALA) {
                return;
            }

            if (dbType == DbType.ORACLE) {
                execute("ALTER SESSION SET current_schema = " + database);
            } else if (dbType == DbType.POSTGRESQL || dbType == DbType.REDSHIFT) {
                execute("SET search_path TO " + database);
            } else if (dbType == DbType.TERADATA) {
                execute("database " + database);
            } else {
                execute("USE " + database);
            }
        }
    }

    public QueryResult query(String sql) {
        return new QueryResult(sql, this, verbose);
    }

    public void execute(String sql) {
        execute(sql, false);
    }

    public void execute(String sql, boolean verbose) {
        Statement statement = null;
        try {
            if (StringUtils.isEmpty(sql)) {
                return;
            }

            statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            for (String subQuery : sql.split(";")) {
                if (verbose) {
                    String abbrSQL = subQuery.replace('\n', ' ').replace('\t', ' ').trim();
                    if (abbrSQL.length() > 100)
                        abbrSQL = abbrSQL.substring(0, 100).trim() + "...";
                    logger.info("Adding query to batch: " + abbrSQL);
                }

                statement.addBatch(subQuery);
            }
            long start = System.currentTimeMillis();
            if (verbose) {
                logger.info("Executing batch");
            }
            statement.executeBatch();
            if (verbose) {
                outputQueryStats(statement, System.currentTimeMillis() - start);
            }
        } catch (SQLException e) {
            logger.error(sql);
            logger.error(e.getMessage(), e);
        } finally {
            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException e) {
                    logger.error(e.getMessage());
                }
            }
        }
    }

    void outputQueryStats(Statement statement, long ms) throws SQLException {
        Throwable warning = statement.getWarnings();
        if (warning != null)
            logger.info("- SERVER: " + warning.getMessage());
        String timeString;
        if (ms < 1000)
            timeString = ms + " ms";
        else if (ms < 60000)
            timeString = decimalFormat.format(ms / 1000d) + " seconds";
        else if (ms < 3600000)
            timeString = decimalFormat.format(ms / 60000d) + " minutes";
        else
            timeString = decimalFormat.format(ms / 3600000d) + " hours";
        logger.info("- Query completed in " + timeString);
    }

    public List<String> getTableNames(String database) {
        if (this.hasStorageHandler()) {
            return this.getStorageHandler().getTableNames();
        } else {
            return getTableNamesClassic(database);
        }
    }

    public List<FieldInfo> fetchTableStructure(RichConnection connection, String database, String table, ScanParameters scanParameters) {
        List<FieldInfo> fieldInfos = new ArrayList<>();

        if (dbType.supportsStorageHandler()) {
            fieldInfos = dbType.getStorageHandler().fetchTableStructure(table, scanParameters);
        } else if (dbType == DbType.MS_ACCESS || dbType == DbType.IMPALA) {
            try {
                long tableSize = connection.getTableSize(table);
                try (ResultSet rs = getFieldNamesFromJDBC(table)) {
                    while (rs.next()) {
                        FieldInfo fieldInfo = jdbcRowToFieldInfo(rs, tableSize, scanParameters);
                        fieldInfos.add(fieldInfo);
                    }
            	}
            } catch (SQLException e) {
                throw new RuntimeException(e.getMessage());
            }
        } else {
            long tableSize = connection.getTableSize(table);
            String query = null;
            if (dbType == DbType.ORACLE)
                query = "SELECT COLUMN_NAME,DATA_TYPE FROM ALL_TAB_COLUMNS WHERE table_name = '" + table + "' AND owner = '" + database.toUpperCase() + "'";
            else if (dbType == DbType.SQL_SERVER || dbType == DbType.PDW) {
                String trimmedDatabase = database;
                if (database.startsWith("[") && database.endsWith("]"))
                    trimmedDatabase = database.substring(1, database.length() - 1);
                String[] parts = table.split("\\.");
                query = "SELECT COLUMN_NAME,DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_CATALOG='" + trimmedDatabase + "' AND TABLE_SCHEMA='" + parts[0] +
                        "' AND TABLE_NAME='" + parts[1] + "';";
            } else if (dbType == DbType.AZURE) {
                String[] parts = table.split("\\.");
                query = "SELECT COLUMN_NAME,DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='" + parts[0] +
                        "' AND TABLE_NAME='" + parts[1] + "';";
            } else if (dbType == DbType.MYSQL)
                query = "SELECT COLUMN_NAME,DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '" + database + "' AND TABLE_NAME = '" + table
                        + "';";
            else if (dbType == DbType.POSTGRESQL || dbType == DbType.REDSHIFT)
                query = "SELECT COLUMN_NAME,DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '" + database.toLowerCase() + "' AND TABLE_NAME = '"
                        + table.toLowerCase() + "' ORDER BY ordinal_position;";
            else if (dbType == DbType.TERADATA) {
                query = "SELECT ColumnName, ColumnType FROM dbc.columns WHERE DatabaseName= '" + database.toLowerCase() + "' AND TableName = '"
                        + table.toLowerCase() + "';";
            } else if (dbType == DbType.BIGQUERY) {
                query = "SELECT column_name AS COLUMN_NAME, data_type as DATA_TYPE FROM " + database + ".INFORMATION_SCHEMA.COLUMNS WHERE table_name = \"" + table + "\";";
            }

            if (StringUtils.isEmpty(query)) {
                throw new RuntimeException("No query was specified to obtain the table structure for DbType = " + dbType.name());
            }

            try (QueryResult qr = connection.query(query)) {
                for (org.ohdsi.utilities.files.Row row : qr) {
                    row.upperCaseFieldNames();
                    org.ohdsi.databases.FieldInfo fieldInfo = queryRowToFieldInfo(row, tableSize, scanParameters);
                    fieldInfos.add(fieldInfo);
                }
            }
        }
        return fieldInfos;
    }

    static FieldInfo jdbcRowToFieldInfo(ResultSet rs, long tableSize, ScanParameters scanParameters) throws SQLException {
        String columnName = rs.getString("COLUMN_NAME");
        String typeName = rs.getString("TYPE_NAME");
        int columnSize = rs.getInt("COLUMN_SIZE");
        int decimalDigits = rs.getInt("DECIMAL_DIGITS");
        int precisionRadix = rs.getInt("NUM_PREC_RADIX");
        FieldInfo fieldInfo = new FieldInfo(scanParameters, columnName);
        fieldInfo.type = typeName;
        fieldInfo.rowCount = tableSize;
        fieldInfo.typeLength = columnSize;
        if (precisionRadix > 0) {
            fieldInfo.decimalDigits = decimalDigits;
            fieldInfo.precisionRadix = precisionRadix;
        }
        return fieldInfo;
    }

    FieldInfo queryRowToFieldInfo(org.ohdsi.utilities.files.Row row, long tableSize, ScanParameters scanParameters) {
        String columnName;
        String datatype;
        if (dbType == DbType.TERADATA) {
            columnName = row.get("COLUMNNAME");
            datatype = row.get("COLUMNTYPE");
        } else {
            columnName = row.get("COLUMN_NAME");
            datatype = row.get("DATA_TYPE");
        }
        org.ohdsi.databases.FieldInfo fieldInfo = new org.ohdsi.databases.FieldInfo(scanParameters, columnName);
        fieldInfo.type = datatype;
        fieldInfo.rowCount = tableSize;
        return fieldInfo;
    }

    public ResultSet getFieldNamesFromJDBC(String table) {
        if (dbType == DbType.MS_ACCESS || dbType == DbType.IMPALA) {
            try {
                DatabaseMetaData metadata = connection.getMetaData();
                return metadata.getColumns(null, null, table, null);
            } catch (SQLException e) {
                throw new RuntimeException(e.getMessage());
            }
        } else {
            throw new RuntimeException("DB is not of supported type");
        }
    }

    public QueryResult fetchRowsFromTable(String table, long rowCount, ScanParameters scanParameters) {
        String query = null;
        int sampleSize = scanParameters.getSampleSize();

        if (dbType.supportsStorageHandler()) {
            query = dbType.getStorageHandler().getRowSampleQuery(table, rowCount, sampleSize);
        } else if (sampleSize == -1) {
            if (dbType == DbType.MS_ACCESS)
                query = "SELECT * FROM [" + table + "]";
            else if (dbType == DbType.SQL_SERVER || dbType == DbType.PDW || dbType == DbType.AZURE)
                query = "SELECT * FROM [" + table.replaceAll("\\.", "].[") + "]";
            else
                query = "SELECT * FROM " + table;
        } else {
            if (dbType == DbType.SQL_SERVER || dbType == DbType.AZURE)
                query = "SELECT * FROM [" + table.replaceAll("\\.", "].[") + "] TABLESAMPLE (" + sampleSize + " ROWS)";
            else if (dbType == DbType.MYSQL || dbType == DbType.IMPALA)
                query = "SELECT * FROM " + table + " ORDER BY RAND() LIMIT " + sampleSize;
            else if (dbType == DbType.PDW)
                query = "SELECT TOP " + sampleSize + " * FROM [" + table.replaceAll("\\.", "].[") + "] ORDER BY RAND()";
            else if (dbType == DbType.ORACLE) {
                if (sampleSize < rowCount) {
                    double percentage = 100 * sampleSize / (double) rowCount;
                    if (percentage < 100)
                        query = "SELECT * FROM " + table + " SAMPLE(" + percentage + ")";
                } else {
                    query = "SELECT * FROM " + table;
                }
            } else if (dbType == DbType.POSTGRESQL || dbType == DbType.REDSHIFT) {
                query = "SELECT * FROM " + table + " ORDER BY RANDOM() LIMIT " + sampleSize;
            }
            else if (dbType == DbType.MS_ACCESS) {
                query = "SELECT " + "TOP " + sampleSize + " * FROM [" + table + "]";
            }
            else if (dbType == DbType.BIGQUERY) {
                query = "SELECT * FROM " + table + " ORDER BY RAND() LIMIT " + sampleSize;
            }
        }


        if (StringUtils.isEmpty(query)) {
            throw new RuntimeException("No query was generated for database type " + dbType.name());
        }

        return createQueryResult(query);
    }


    private List<String> getTableNamesClassic(String database) {
        List<String> names = new ArrayList<>();
        String query = null;
        if (dbType == DbType.MYSQL) {
            query = "SHOW TABLES IN " + database;
        } else if (dbType == DbType.SQL_SERVER || dbType == DbType.PDW || dbType == DbType.AZURE) {
            query = "SELECT CONCAT(schemas.name, '.', tables_views.name) FROM " +
                    "(SELECT schema_id, name FROM %1$s.sys.tables UNION ALL SELECT schema_id, name FROM %1$s.sys.views) tables_views " +
                    "INNER JOIN %1$s.sys.schemas ON tables_views.schema_id = schemas.schema_id " +
                    "ORDER BY schemas.name, tables_views.name";
            query = String.format(query, database);
            logger.info(query);
        } else if (dbType == DbType.ORACLE) {
            query = "SELECT table_name FROM " +
                    "(SELECT table_name, owner FROM all_tables UNION ALL SELECT view_name, owner FROM all_views) tables_views " +
                    "WHERE owner='" + database.toUpperCase() + "'";
        } else if (dbType == DbType.POSTGRESQL || dbType == DbType.REDSHIFT) {
            query = "SELECT table_name FROM information_schema.tables WHERE table_schema = '" + database.toLowerCase() + "' ORDER BY table_name";
        } else if (dbType == DbType.MS_ACCESS) {
            query = "SELECT Name FROM sys.MSysObjects WHERE (Type=1 OR Type=5) AND Flags=0;";
        } else if (dbType == DbType.TERADATA) {
            query = "SELECT TableName from dbc.tables WHERE tablekind IN ('T','V') and databasename='" + database + "'";
        } else if (dbType == DbType.BIGQUERY) {
            query = "SELECT table_name from " + database + ".INFORMATION_SCHEMA.TABLES ORDER BY table_name;";
        }

        for (Row row : createQueryResult(query))
            names.add(row.get(row.getFieldNames().get(0)));
        return names;
    }

    private QueryResult createQueryResult(String sql) {
        return new QueryResult(sql, this, verbose);
    }

    public void close() throws SQLException {
        if (this.hasStorageHandler()) {
            this.getStorageHandler().close();
        } else {
            this.connection.close();
        }
    }

    public void setAutoCommit(boolean b) throws SQLException {
        this.connection.setAutoCommit(b);
    }

    public PreparedStatement prepareStatement(String statement) throws SQLException {
        return this.connection.prepareStatement(statement);
    }

    public void commit() throws SQLException {
        this.connection.commit();
    }

    public void clearWarnings() throws SQLException {
        this.connection.clearWarnings();
    }
}
