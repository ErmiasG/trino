/*
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
 */

package io.trino.plugin.teradata;

import com.google.inject.Inject;
import io.trino.plugin.base.mapping.IdentifierMapping;
import io.trino.plugin.jdbc.BaseJdbcClient;
import io.trino.plugin.jdbc.BaseJdbcConfig;
import io.trino.plugin.jdbc.ColumnMapping;
import io.trino.plugin.jdbc.ConnectionFactory;
import io.trino.plugin.jdbc.ForBaseJdbc;
import io.trino.plugin.jdbc.JdbcColumnHandle;
import io.trino.plugin.jdbc.JdbcOutputTableHandle;
import io.trino.plugin.jdbc.JdbcTableHandle;
import io.trino.plugin.jdbc.JdbcTypeHandle;
import io.trino.plugin.jdbc.QueryBuilder;
import io.trino.plugin.jdbc.WriteMapping;
import io.trino.plugin.jdbc.logging.RemoteQueryModifier;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ColumnPosition;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.type.CharType;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarcharType;

import java.sql.Connection;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;

import static io.trino.plugin.jdbc.StandardColumnMappings.bigintColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.booleanColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.charColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.dateColumnMappingUsingLocalDate;
import static io.trino.plugin.jdbc.StandardColumnMappings.decimalColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.doubleColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.integerColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.realColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.smallintColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.timeColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.timestampColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.tinyintColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.varbinaryColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.varcharColumnMapping;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.type.DecimalType.createDecimalType;
import static io.trino.spi.type.TimeType.createTimeType;
import static io.trino.spi.type.TimestampType.createTimestampType;
import static io.trino.spi.type.VarcharType.createUnboundedVarcharType;
import static io.trino.spi.type.VarcharType.createVarcharType;

/**
 * Read support for Teradata over its own JDBC driver.
 *
 * Teradata quotes identifiers with double quotes and compares object names without regard to case,
 * so nothing here has to preserve or fold case the way Oracle and Snowflake do: a quoted name
 * resolves to the same object as an unquoted one.
 */
public class TeradataClient
        extends BaseJdbcClient
{
    // Teradata's DECIMAL/NUMBER tops out at 38 digits, which is also Trino's maximum, so a declared
    // precision always fits. NUMBER declared without one reports precision 0 and has no fixed-point
    // type to map to; see toColumnMapping.
    private static final int MAX_DECIMAL_PRECISION = 38;

    @Inject
    public TeradataClient(
            BaseJdbcConfig config,
            @ForBaseJdbc ConnectionFactory connectionFactory,
            QueryBuilder queryBuilder,
            IdentifierMapping identifierMapping,
            RemoteQueryModifier queryModifier)
    {
        super("\"", connectionFactory, queryBuilder, config.getJdbcTypesMappedToVarchar(), identifierMapping, queryModifier, false);
    }

    @Override
    public Optional<ColumnMapping> toColumnMapping(ConnectorSession session, Connection connection, JdbcTypeHandle typeHandle)
    {
        Optional<ColumnMapping> forced = getForcedMappingToVarchar(typeHandle);
        if (forced.isPresent()) {
            return forced;
        }

        return switch (typeHandle.jdbcType()) {
            case Types.BIT, Types.BOOLEAN -> Optional.of(booleanColumnMapping());
            case Types.TINYINT -> Optional.of(tinyintColumnMapping());
            case Types.SMALLINT -> Optional.of(smallintColumnMapping());
            case Types.INTEGER -> Optional.of(integerColumnMapping());
            case Types.BIGINT -> Optional.of(bigintColumnMapping());
            case Types.REAL -> Optional.of(realColumnMapping());
            case Types.FLOAT, Types.DOUBLE -> Optional.of(doubleColumnMapping());
            case Types.NUMERIC, Types.DECIMAL -> {
                int precision = typeHandle.columnSize().orElse(0);
                int scale = typeHandle.decimalDigits().orElse(0);
                // NUMBER with no declared precision, and anything reporting a scale wider than its
                // precision, has no fixed-point type that keeps every value. Leaving it unmapped
                // hides the column, which is honest: the alternative is silently rounding.
                if (precision <= 0 || precision > MAX_DECIMAL_PRECISION || scale < 0 || scale > precision) {
                    yield Optional.empty();
                }
                yield Optional.of(decimalColumnMapping(createDecimalType(precision, scale)));
            }
            case Types.CHAR -> Optional.of(charColumnMapping(CharType.createCharType(typeHandle.requiredColumnSize()), false));
            case Types.VARCHAR, Types.LONGVARCHAR, Types.CLOB -> {
                int size = typeHandle.columnSize().orElse(VarcharType.UNBOUNDED_LENGTH);
                yield Optional.of(varcharColumnMapping(
                        size == VarcharType.UNBOUNDED_LENGTH ? createUnboundedVarcharType() : createVarcharType(size),
                        false));
            }
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> Optional.of(varbinaryColumnMapping());
            case Types.DATE -> Optional.of(dateColumnMappingUsingLocalDate());
            case Types.TIME -> Optional.of(timeColumnMapping(createTimeType(typeHandle.decimalDigits().orElse(6))));
            case Types.TIMESTAMP -> Optional.of(timestampColumnMapping(createTimestampType(typeHandle.decimalDigits().orElse(6))));
            // Teradata's TIME/TIMESTAMP WITH TIME ZONE carry an offset, and trino-base-jdbc ships no
            // standard mapping for either. Falling through to the default would drop the column from
            // the table entirely, so they are read as text, which keeps both the value and its offset.
            case Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE -> Optional.of(varcharColumnMapping(createUnboundedVarcharType(), false));
            // PERIOD, INTERVAL, ST_Geometry, XML, JSON and the rest have no Trino equivalent.
            // An unmapped column is dropped from the table rather than failing the whole scan.
            default -> Optional.empty();
        };
    }

    @Override
    public WriteMapping toWriteMapping(ConnectorSession session, Type type)
    {
        throw readOnly("Writing to Teradata");
    }

    /*
     * Read-only is enforced here, operation by operation, and not by toWriteMapping alone.
     *
     * toWriteMapping is consulted only where a Trino type has to be turned into a Teradata column
     * type, which is the CREATE TABLE and INSERT path. DELETE, UPDATE, TRUNCATE, DROP and the
     * schema and column mutations never go near it: DefaultJdbcMetadata calls the JdbcClient
     * methods below directly, and BaseJdbcClient implements every one of them by building remote
     * SQL. Overriding only toWriteMapping therefore leaves a connector that refuses INSERT while
     * happily issuing DROP TABLE against the source.
     *
     * That matters because the chart grants a project's data owners DELETE, UPDATE and OWNERSHIP on
     * their own catalogs, so the only thing standing between those grants and the customer's
     * Teradata tables is the privilege of the configured connection user. A data source is credible
     * as read-only only if the connector refuses before any remote statement is issued.
     */
    private static TrinoException readOnly(String operation)
    {
        return new TrinoException(NOT_SUPPORTED, operation + " is not supported: the Teradata connector is read-only.");
    }

    @Override
    public void createTable(ConnectorSession session, ConnectorTableMetadata tableMetadata)
    {
        throw readOnly("Creating a Teradata table");
    }

    @Override
    public JdbcOutputTableHandle beginCreateTable(
            ConnectorSession session,
            ConnectorTableMetadata tableMetadata,
            Consumer<Runnable> cleanupTasks)
    {
        throw readOnly("Creating a Teradata table");
    }

    @Override
    public void commitCreateTable(ConnectorSession session, JdbcOutputTableHandle handle, Set<Long> pageSinkIds)
    {
        throw readOnly("Creating a Teradata table");
    }

    @Override
    public JdbcOutputTableHandle beginInsertTable(
            ConnectorSession session,
            JdbcTableHandle tableHandle,
            List<JdbcColumnHandle> columns)
    {
        throw readOnly("Inserting into Teradata");
    }

    @Override
    public void finishInsertTable(ConnectorSession session, JdbcOutputTableHandle handle, Set<Long> pageSinkIds)
    {
        throw readOnly("Inserting into Teradata");
    }

    @Override
    public void dropTable(ConnectorSession session, JdbcTableHandle handle)
    {
        throw readOnly("Dropping a Teradata table");
    }

    @Override
    public void renameTable(ConnectorSession session, JdbcTableHandle handle, SchemaTableName newTableName)
    {
        throw readOnly("Renaming a Teradata table");
    }

    @Override
    public void setTableProperties(
            ConnectorSession session,
            JdbcTableHandle handle,
            Map<String, Optional<Object>> properties)
    {
        throw readOnly("Altering Teradata table properties");
    }

    @Override
    public void setTableComment(ConnectorSession session, JdbcTableHandle handle, Optional<String> comment)
    {
        throw readOnly("Commenting a Teradata table");
    }

    @Override
    public void setColumnComment(
            ConnectorSession session,
            JdbcTableHandle handle,
            JdbcColumnHandle column,
            Optional<String> comment)
    {
        throw readOnly("Commenting a Teradata column");
    }

    @Override
    public void addColumn(
            ConnectorSession session,
            JdbcTableHandle handle,
            ColumnMetadata column,
            ColumnPosition position)
    {
        throw readOnly("Adding a Teradata column");
    }

    @Override
    public void dropColumn(ConnectorSession session, JdbcTableHandle handle, JdbcColumnHandle column)
    {
        throw readOnly("Dropping a Teradata column");
    }

    @Override
    public void renameColumn(
            ConnectorSession session,
            JdbcTableHandle handle,
            JdbcColumnHandle jdbcColumn,
            String newColumnName)
    {
        throw readOnly("Renaming a Teradata column");
    }

    @Override
    public void setColumnType(ConnectorSession session, JdbcTableHandle handle, JdbcColumnHandle column, Type type)
    {
        throw readOnly("Changing a Teradata column type");
    }

    @Override
    public void dropNotNullConstraint(ConnectorSession session, JdbcTableHandle handle, JdbcColumnHandle column)
    {
        throw readOnly("Dropping a Teradata NOT NULL constraint");
    }

    @Override
    public void createSchema(ConnectorSession session, String schemaName)
    {
        throw readOnly("Creating a Teradata database");
    }

    @Override
    public void dropSchema(ConnectorSession session, String schemaName, boolean cascade)
    {
        throw readOnly("Dropping a Teradata database");
    }

    @Override
    public void renameSchema(ConnectorSession session, String schemaName, String newSchemaName)
    {
        throw readOnly("Renaming a Teradata database");
    }

    @Override
    public OptionalLong delete(ConnectorSession session, JdbcTableHandle handle)
    {
        throw readOnly("Deleting from Teradata");
    }

    @Override
    public OptionalLong update(ConnectorSession session, JdbcTableHandle handle)
    {
        throw readOnly("Updating Teradata");
    }

    @Override
    public void truncateTable(ConnectorSession session, JdbcTableHandle handle)
    {
        throw readOnly("Truncating a Teradata table");
    }
}
