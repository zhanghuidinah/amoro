package com.netease.arctic.flink.catalog;

import static com.netease.arctic.flink.table.descriptors.ArcticValidator.TABLE_FORMAT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.netease.arctic.ams.api.TableFormat;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.Schema;
import org.apache.flink.table.catalog.CatalogBaseTable;
import org.apache.flink.table.catalog.CatalogDatabaseImpl;
import org.apache.flink.table.catalog.CatalogTable;
import org.apache.flink.table.catalog.Column;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.flink.table.catalog.ResolvedCatalogTable;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.catalog.exceptions.DatabaseAlreadyExistException;
import org.apache.flink.table.catalog.exceptions.DatabaseNotEmptyException;
import org.apache.flink.table.catalog.exceptions.DatabaseNotExistException;
import org.apache.flink.table.catalog.exceptions.TableAlreadyExistException;
import org.apache.flink.table.catalog.exceptions.TableNotExistException;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.thrift.TException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

class TestFlinkCatalogs {
  static FlinkCatalogContext flinkCatalogContext = new FlinkCatalogContext();

  @BeforeAll
  public static void setupCatalogMeta() throws Exception {
    flinkCatalogContext.initial();
  }

  @AfterAll
  public static void tearDown() {
    flinkCatalogContext.close();
  }

  @ParameterizedTest
  @MethodSource("com.netease.arctic.flink.catalog.FlinkCatalogContext#getFlinkCatalogAndTable")
  void testListDatabases(FlinkUnifiedCatalog flinkUnifiedCatalog) throws TException {
    List<String> expects = flinkCatalogContext.getHMSClient().getAllDatabases();
    assertEquals(expects, flinkUnifiedCatalog.listDatabases());
  }

  @ParameterizedTest
  @MethodSource("com.netease.arctic.flink.catalog.FlinkCatalogContext#getFlinkCatalogAndTable")
  void testDatabaseExists(FlinkUnifiedCatalog flinkUnifiedCatalog) {
    assertTrue(flinkUnifiedCatalog.databaseExists("default"));
    assertFalse(flinkUnifiedCatalog.databaseExists("not_exists_db"));
  }

  @ParameterizedTest
  @MethodSource("com.netease.arctic.flink.catalog.FlinkCatalogContext#getFlinkCatalogAndTable")
  void testCreateAndDropDatabase(FlinkUnifiedCatalog flinkUnifiedCatalog)
      throws DatabaseAlreadyExistException, DatabaseNotEmptyException, DatabaseNotExistException {
    flinkUnifiedCatalog.createDatabase(
        "test", new CatalogDatabaseImpl(Collections.emptyMap(), "test"), false);
    assertTrue(flinkUnifiedCatalog.databaseExists("test"));

    flinkUnifiedCatalog.dropDatabase("test", false);
    assertFalse(flinkUnifiedCatalog.databaseExists("test"));
  }

  @ParameterizedTest
  @MethodSource("com.netease.arctic.flink.catalog.FlinkCatalogContext#getFlinkCatalogAndTable")
  void testAlterDatabase(
      FlinkUnifiedCatalog flinkUnifiedCatalog, CatalogTable table, TableFormat tableFormat)
      throws DatabaseNotExistException {
    try {
      flinkUnifiedCatalog.alterDatabase(
          "default", new CatalogDatabaseImpl(Collections.emptyMap(), "default"), false);
    } catch (UnsupportedOperationException e) {
      // Mixed-format catalog does not support altering database.
      if (tableFormat != TableFormat.MIXED_HIVE && tableFormat != TableFormat.MIXED_ICEBERG) {
        throw e;
      }
    }
  }

  @ParameterizedTest
  @MethodSource("com.netease.arctic.flink.catalog.FlinkCatalogContext#getFlinkCatalogAndTable")
  void testCreateGetAndDropTable(
      FlinkUnifiedCatalog flinkUnifiedCatalog, CatalogTable table, TableFormat tableFormat)
      throws TableAlreadyExistException, DatabaseNotExistException, TableNotExistException {
    ObjectPath objectPath = flinkCatalogContext.objectPath;

    flinkUnifiedCatalog.createTable(flinkCatalogContext.objectPath, table, false);
    assertTrue(flinkUnifiedCatalog.tableExists(objectPath));

    CatalogBaseTable actualTable = flinkUnifiedCatalog.getTable(objectPath);
    assertEquals(table.getUnresolvedSchema(), actualTable.getUnresolvedSchema());
    assertEquals(tableFormat.toString(), actualTable.getOptions().get(TABLE_FORMAT.key()));

    flinkUnifiedCatalog.dropTable(objectPath, false);
    assertFalse(flinkUnifiedCatalog.tableExists(objectPath));
  }

  @ParameterizedTest
  @MethodSource("com.netease.arctic.flink.catalog.FlinkCatalogContext#getFlinkCatalogAndTable")
  void testAlterTable(
      FlinkUnifiedCatalog flinkUnifiedCatalog, CatalogTable table, TableFormat tableFormat)
      throws TableNotExistException, TableAlreadyExistException, DatabaseNotExistException {
    try {
      flinkUnifiedCatalog.createTable(flinkCatalogContext.objectPath, table, true);

      ResolvedSchema newResolvedSchema =
          ResolvedSchema.of(
              Column.physical("name", DataTypes.STRING()),
              Column.physical("age", DataTypes.INT()),
              Column.physical("address", DataTypes.STRING()));
      String comment = "Flink new Table";
      Map<String, String> newProperties = Maps.newHashMap();
      newProperties.put("new_key", "new_value");

      CatalogBaseTable newTable =
          new ResolvedCatalogTable(
              CatalogTable.of(
                  Schema.newBuilder().fromResolvedSchema(newResolvedSchema).build(),
                  comment,
                  new ArrayList<>(),
                  newProperties),
              newResolvedSchema);
      try {
        flinkUnifiedCatalog.alterTable(flinkCatalogContext.objectPath, newTable, false);
      } catch (UnsupportedOperationException e) {
        // https://github.com/NetEase/amoro/issues/2 altering Mixed format table is not supported.
        if (tableFormat != TableFormat.MIXED_HIVE && tableFormat != TableFormat.MIXED_ICEBERG) {
          throw e;
        }
      }
    } finally {
      flinkUnifiedCatalog.dropTable(flinkCatalogContext.objectPath, true);
    }
  }
}
