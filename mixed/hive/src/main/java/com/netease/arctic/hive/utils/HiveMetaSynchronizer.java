/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.hive.utils;

import com.netease.arctic.hive.HMSClientPool;
import com.netease.arctic.hive.HiveTableProperties;
import com.netease.arctic.hive.op.OverwriteHiveFiles;
import com.netease.arctic.hive.table.SupportHive;
import com.netease.arctic.op.OverwriteBaseFiles;
import com.netease.arctic.table.ArcticTable;
import com.netease.arctic.table.TableIdentifier;
import com.netease.arctic.table.TableProperties;
import com.netease.arctic.table.UnkeyedTable;
import com.netease.arctic.utils.TablePropertyUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.hadoop.hive.metastore.Warehouse;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.MetricsConfig;
import org.apache.iceberg.OverwriteFiles;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.UpdateSchema;
import org.apache.iceberg.data.TableMigrationUtil;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.mapping.NameMappingParser;
import org.apache.iceberg.relocated.com.google.common.annotations.VisibleForTesting;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.types.TypeUtil;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.StructLikeMap;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Utils for syncing the metadata between the hive table and the arctic table. */
public class HiveMetaSynchronizer {

  private static final Logger LOG = LoggerFactory.getLogger(HiveMetaSynchronizer.class);

  /**
   * Synchronize the schema change of the hive table to arctic table
   *
   * @param table arctic table to accept the schema change
   * @param hiveClient hive client
   */
  public static void syncHiveSchemaToArctic(ArcticTable table, HMSClientPool hiveClient) {
    try {
      if (!HiveTableUtil.checkExist(hiveClient, table.id())) {
        LOG.warn("Hive table {} does not exist, try to skip sync schema to amoro", table.id());
        return;
      }
      Table hiveTable =
          hiveClient.run(
              client -> client.getTable(table.id().getDatabase(), table.id().getTableName()));
      Schema hiveSchema =
          HiveSchemaUtil.convertHiveSchemaToIcebergSchema(
              hiveTable,
              table.isKeyedTable()
                  ? table.asKeyedTable().primaryKeySpec().fieldNames()
                  : new ArrayList<>());
      UpdateSchema updateSchema = table.updateSchema();
      boolean update =
          updateStructSchema(
              table.id(), updateSchema, null, table.schema().asStruct(), hiveSchema.asStruct());
      if (update) {
        updateSchema.commit();
      }
    } catch (TException | InterruptedException e) {
      throw new RuntimeException("Failed to get hive table:" + table.id(), e);
    }
  }

  private static boolean updateStructSchema(
      TableIdentifier tableIdentifier,
      UpdateSchema updateSchema,
      String parentName,
      Types.StructType icebergStruct,
      Types.StructType hiveStruct) {
    boolean update = false;
    for (Types.NestedField hiveField : hiveStruct.fields()) {
      // to check if lowercase matches
      List<Types.NestedField> fields =
          icebergStruct.fields().stream()
              .filter(f -> f.name().toLowerCase().equals(hiveField.name()))
              .collect(Collectors.toList());
      if (fields.isEmpty()) {
        updateSchema.addColumn(parentName, hiveField.name(), hiveField.type(), hiveField.doc());
        update = true;
        LOG.info("Table {} sync new hive column {} to arctic", tableIdentifier, hiveField);
      } else if (fields.size() == 1) {
        Types.NestedField icebergField = fields.get(0);
        if (!icebergField.type().equals(hiveField.type())
            || !Objects.equals(icebergField.doc(), (hiveField.doc()))) {
          if (hiveField.type().isPrimitiveType() && icebergField.type().isPrimitiveType()) {
            if (TypeUtil.isPromotionAllowed(
                icebergField.type().asPrimitiveType(), hiveField.type().asPrimitiveType())) {
              String columnName =
                  parentName == null ? hiveField.name() : parentName + "." + hiveField.name();
              updateSchema.updateColumn(
                  columnName, hiveField.type().asPrimitiveType(), hiveField.doc());
              update = true;
              LOG.info("Table {} sync hive column {} to arctic", tableIdentifier, hiveField);
            } else {
              LOG.warn(
                  "Table {} sync hive column {} to arctic failed, because of type mismatch",
                  tableIdentifier,
                  hiveField);
            }
          } else if (hiveField.type().isStructType() && icebergField.type().isStructType()) {
            String columnName =
                parentName == null ? hiveField.name() : parentName + "." + hiveField.name();
            update =
                update
                    || updateStructSchema(
                        tableIdentifier,
                        updateSchema,
                        columnName,
                        icebergField.type().asStructType(),
                        hiveField.type().asStructType());
          } else {
            LOG.warn(
                "Table {} sync hive column {} to arctic failed, because of type mismatch",
                tableIdentifier,
                hiveField);
          }
        }
      } else {
        throw new RuntimeException("Exist columns with the same name: " + fields.get(0));
      }
    }
    return update;
  }

  public static void syncHiveDataToArctic(SupportHive table, HMSClientPool hiveClient) {
    syncHiveDataToArctic(table, hiveClient, false);
  }

  /**
   * Synchronize the data change of the hive table to arctic table
   *
   * @param table arctic table to accept the data change
   * @param hiveClient hive client
   */
  public static void syncHiveDataToArctic(
      SupportHive table, HMSClientPool hiveClient, boolean force) {
    if (!HiveTableUtil.checkExist(hiveClient, table.id())) {
      LOG.warn("Hive table {} does not exist, try to skip sync data to amoro", table.id());
      return;
    }
    UnkeyedTable baseStore;
    if (table.isKeyedTable()) {
      baseStore = table.asKeyedTable().baseTable();
    } else {
      baseStore = table.asUnkeyedTable();
    }
    try {
      if (table.spec().isUnpartitioned()) {
        Table hiveTable =
            hiveClient.run(
                client -> client.getTable(table.id().getDatabase(), table.id().getTableName()));
        if (force || tableHasModified(baseStore, hiveTable)) {
          List<DataFile> hiveDataFiles =
              listHivePartitionFiles(table, Maps.newHashMap(), hiveTable.getSd().getLocation());
          List<DataFile> deleteFiles = Lists.newArrayList();
          try (CloseableIterable<FileScanTask> fileScanTasks = baseStore.newScan().planFiles()) {
            fileScanTasks.forEach(fileScanTask -> deleteFiles.add(fileScanTask.file()));
          } catch (IOException e) {
            throw new UncheckedIOException("Failed to close table scan of " + table.name(), e);
          }
          overwriteTable(table, deleteFiles, hiveDataFiles);
        }
      } else {
        // list all hive partitions.
        List<Partition> hivePartitions =
            hiveClient.run(
                client ->
                    client.listPartitions(
                        table.id().getDatabase(), table.id().getTableName(), Short.MAX_VALUE));
        // group arctic files by partition.
        StructLikeMap<Collection<DataFile>> filesGroupedByPartition =
            StructLikeMap.create(table.spec().partitionType());
        TableScan tableScan = baseStore.newScan();
        try (CloseableIterable<FileScanTask> fileScanTasks = tableScan.planFiles()) {
          for (org.apache.iceberg.FileScanTask fileScanTask : fileScanTasks) {
            filesGroupedByPartition
                .computeIfAbsent(fileScanTask.file().partition(), k -> Lists.newArrayList())
                .add(fileScanTask.file());
          }
        } catch (IOException e) {
          throw new UncheckedIOException("Failed to close table scan of " + table.name(), e);
        }
        List<DataFile> filesToDelete = Lists.newArrayList();
        List<DataFile> filesToAdd = Lists.newArrayList();
        List<StructLike> icebergPartitions = Lists.newArrayList(filesGroupedByPartition.keySet());
        for (Partition hivePartition : hivePartitions) {
          StructLike partitionData =
              HivePartitionUtil.buildPartitionData(hivePartition.getValues(), table.spec());
          icebergPartitions.remove(partitionData);
          if (force || partitionHasModified(baseStore, hivePartition, partitionData)) {
            List<DataFile> hiveDataFiles =
                listHivePartitionFiles(
                    table,
                    buildPartitionValueMap(hivePartition.getValues(), table.spec()),
                    hivePartition.getSd().getLocation());
            if (filesGroupedByPartition.get(partitionData) != null) {
              filesToDelete.addAll(filesGroupedByPartition.get(partitionData));
              filesToAdd.addAll(hiveDataFiles);
              // make sure new partition is not created by arctic
            } else if (hivePartition.getParameters().get(HiveTableProperties.ARCTIC_TABLE_FLAG)
                    == null
                && hivePartition.getParameters().get(HiveTableProperties.ARCTIC_TABLE_FLAG_LEGACY)
                    == null) {
              filesToAdd.addAll(hiveDataFiles);
            }
          }
        }

        icebergPartitions.forEach(
            partition -> {
              List<DataFile> dataFiles = Lists.newArrayList(filesGroupedByPartition.get(partition));
              if (dataFiles.size() > 0) {
                // make sure dropped partition with no files
                if (!table.io().exists(dataFiles.get(0).path().toString())) {
                  filesToDelete.addAll(filesGroupedByPartition.get(partition));
                }
              }
            });
        overwriteTable(table, filesToDelete, filesToAdd);
      }
    } catch (TException | InterruptedException e) {
      throw new RuntimeException("Failed to get hive table:" + table.id(), e);
    }
  }

  /**
   * Synchronize the data change of the arctic table to hive table
   *
   * @param table support hive table
   */
  public static void syncArcticDataToHive(SupportHive table) {
    UnkeyedTable baseStore;
    if (table.isKeyedTable()) {
      baseStore = table.asKeyedTable().baseTable();
    } else {
      baseStore = table.asUnkeyedTable();
    }
    StructLikeMap<Map<String, String>> partitionProperty = baseStore.partitionProperty();

    try {
      if (baseStore.spec().isUnpartitioned()) {
        syncNoPartitionTable(table, partitionProperty);
      } else {
        syncPartitionTable(table, partitionProperty);
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to sync arctic data to hive:" + table.id(), e);
    }
  }

  /**
   * once get location from iceberg property, should update hive table location, because only arctic
   * update hive table location for unPartitioned table.
   */
  private static void syncNoPartitionTable(
      SupportHive table, StructLikeMap<Map<String, String>> partitionProperty) {
    Map<String, String> property = partitionProperty.get(TablePropertyUtil.EMPTY_STRUCT);
    if (property == null
        || property.get(HiveTableProperties.PARTITION_PROPERTIES_KEY_HIVE_LOCATION) == null) {
      LOG.debug("{} has no hive location in partition property", table.id());
      return;
    }

    String currentLocation =
        property.get(HiveTableProperties.PARTITION_PROPERTIES_KEY_HIVE_LOCATION);
    String hiveLocation;
    try {
      hiveLocation =
          table
              .getHMSClient()
              .run(
                  client -> {
                    Table hiveTable =
                        client.getTable(table.id().getDatabase(), table.id().getTableName());
                    return hiveTable.getSd().getLocation();
                  });
    } catch (Exception e) {
      LOG.error("{} get hive location failed", table.id(), e);
      return;
    }

    if (!Objects.equals(currentLocation, hiveLocation)) {
      try {
        table
            .getHMSClient()
            .run(
                client -> {
                  Table hiveTable =
                      client.getTable(table.id().getDatabase(), table.id().getTableName());
                  hiveTable.getSd().setLocation(currentLocation);
                  client.alterTable(table.id().getDatabase(), table.id().getTableName(), hiveTable);
                  return null;
                });
      } catch (Exception e) {
        LOG.error("{} alter hive location failed", table.id(), e);
      }
    }
  }

  private static void syncPartitionTable(
      SupportHive table, StructLikeMap<Map<String, String>> partitionProperty) throws Exception {
    Map<String, StructLike> icebergPartitionMap = new HashMap<>();
    for (StructLike structLike : partitionProperty.keySet()) {
      icebergPartitionMap.put(table.spec().partitionToPath(structLike), structLike);
    }
    List<String> icebergPartitions = new ArrayList<>(icebergPartitionMap.keySet());
    List<Partition> hivePartitions =
        table
            .getHMSClient()
            .run(
                client ->
                    client.listPartitions(
                        table.id().getDatabase(), table.id().getTableName(), Short.MAX_VALUE));
    List<String> hivePartitionNames =
        table
            .getHMSClient()
            .run(
                client ->
                    client.listPartitionNames(
                        table.id().getDatabase(), table.id().getTableName(), Short.MAX_VALUE));
    List<FieldSchema> partitionKeys =
        table
            .getHMSClient()
            .run(
                client -> {
                  Table hiveTable =
                      client.getTable(table.id().getDatabase(), table.id().getTableName());
                  return hiveTable.getPartitionKeys();
                });
    Map<String, Partition> hivePartitionMap = new HashMap<>();
    for (Partition hivePartition : hivePartitions) {
      hivePartitionMap.put(
          Warehouse.makePartName(partitionKeys, hivePartition.getValues()), hivePartition);
    }

    Set<String> inIcebergNotInHive =
        icebergPartitions.stream()
            .filter(partition -> !hivePartitionNames.contains(partition))
            .collect(Collectors.toSet());
    Set<String> inHiveNotInIceberg =
        hivePartitionNames.stream()
            .filter(partition -> !icebergPartitions.contains(partition))
            .collect(Collectors.toSet());
    Set<String> inBoth =
        icebergPartitions.stream().filter(hivePartitionNames::contains).collect(Collectors.toSet());

    if (CollectionUtils.isNotEmpty(inIcebergNotInHive)) {
      handleInIcebergPartitions(table, inIcebergNotInHive, icebergPartitionMap, partitionProperty);
    }

    if (CollectionUtils.isNotEmpty(inHiveNotInIceberg)) {
      handleInHivePartitions(table, inHiveNotInIceberg, hivePartitionMap);
    }

    if (CollectionUtils.isNotEmpty(inBoth)) {
      handleInBothPartitions(
          table, inBoth, hivePartitionMap, icebergPartitionMap, partitionProperty);
    }
  }

  /** if iceberg partition location is existed, should update hive table location. */
  private static void handleInIcebergPartitions(
      ArcticTable arcticTable,
      Set<String> inIcebergNotInHive,
      Map<String, StructLike> icebergPartitionMap,
      StructLikeMap<Map<String, String>> partitionProperty) {
    inIcebergNotInHive.forEach(
        partition -> {
          Map<String, String> property = partitionProperty.get(icebergPartitionMap.get(partition));
          if (property == null
              || property.get(HiveTableProperties.PARTITION_PROPERTIES_KEY_HIVE_LOCATION) == null) {
            return;
          }
          String currentLocation =
              property.get(HiveTableProperties.PARTITION_PROPERTIES_KEY_HIVE_LOCATION);

          if (arcticTable.io().exists(currentLocation)) {
            int transientTime =
                Integer.parseInt(
                    property.getOrDefault(
                        HiveTableProperties.PARTITION_PROPERTIES_KEY_TRANSIENT_TIME, "0"));
            List<DataFile> dataFiles =
                getIcebergPartitionFiles(arcticTable, icebergPartitionMap.get(partition));
            HivePartitionUtil.createPartitionIfAbsent(
                ((SupportHive) arcticTable).getHMSClient(),
                arcticTable,
                HivePartitionUtil.partitionValuesAsList(
                    icebergPartitionMap.get(partition), arcticTable.spec().partitionType()),
                currentLocation,
                dataFiles,
                transientTime);
          }
        });
  }

  private static void handleInHivePartitions(
      ArcticTable arcticTable,
      Set<String> inHiveNotInIceberg,
      Map<String, Partition> hivePartitionMap) {
    inHiveNotInIceberg.forEach(
        partition -> {
          Partition hivePartition = hivePartitionMap.get(partition);
          boolean isArctic =
              CompatibleHivePropertyUtil.propertyAsBoolean(
                  hivePartition.getParameters(), HiveTableProperties.ARCTIC_TABLE_FLAG, false);
          if (isArctic) {
            HivePartitionUtil.dropPartition(
                ((SupportHive) arcticTable).getHMSClient(), arcticTable, hivePartition);
          }
        });
  }

  private static void handleInBothPartitions(
      ArcticTable arcticTable,
      Set<String> inBoth,
      Map<String, Partition> hivePartitionMap,
      Map<String, StructLike> icebergPartitionMap,
      StructLikeMap<Map<String, String>> partitionProperty) {
    Set<String> inHiveNotInIceberg = new HashSet<>();
    inBoth.forEach(
        partition -> {
          Map<String, String> property = partitionProperty.get(icebergPartitionMap.get(partition));
          if (property == null
              || property.get(HiveTableProperties.PARTITION_PROPERTIES_KEY_HIVE_LOCATION) == null) {
            inHiveNotInIceberg.add(partition);
            return;
          }

          String currentLocation =
              property.get(HiveTableProperties.PARTITION_PROPERTIES_KEY_HIVE_LOCATION);
          Partition hivePartition = hivePartitionMap.get(partition);

          if (!Objects.equals(currentLocation, hivePartition.getSd().getLocation())) {
            int transientTime =
                Integer.parseInt(
                    property.getOrDefault(
                        HiveTableProperties.PARTITION_PROPERTIES_KEY_TRANSIENT_TIME, "0"));
            List<DataFile> dataFiles =
                getIcebergPartitionFiles(arcticTable, icebergPartitionMap.get(partition));
            HivePartitionUtil.updatePartitionLocation(
                ((SupportHive) arcticTable).getHMSClient(),
                arcticTable,
                hivePartition,
                currentLocation,
                dataFiles,
                transientTime);
          }
        });

    handleInHivePartitions(arcticTable, inHiveNotInIceberg, hivePartitionMap);
  }

  private static List<DataFile> getIcebergPartitionFiles(
      ArcticTable arcticTable, StructLike partition) {
    UnkeyedTable baseStore;
    baseStore =
        arcticTable.isKeyedTable()
            ? arcticTable.asKeyedTable().baseTable()
            : arcticTable.asUnkeyedTable();

    List<DataFile> partitionFiles = new ArrayList<>();
    arcticTable
        .io()
        .doAs(
            () -> {
              try (CloseableIterable<FileScanTask> fileScanTasks =
                  baseStore.newScan().planFiles()) {
                for (FileScanTask fileScanTask : fileScanTasks) {
                  if (fileScanTask.file().partition().equals(partition)) {
                    partitionFiles.add(fileScanTask.file());
                  }
                }
              }

              return null;
            });

    return partitionFiles;
  }

  @VisibleForTesting
  static boolean partitionHasModified(
      UnkeyedTable arcticTable, Partition hivePartition, StructLike partitionData) {
    String hiveTransientTime = hivePartition.getParameters().get("transient_lastDdlTime");
    String arcticTransientTime =
        arcticTable.partitionProperty().containsKey(partitionData)
            ? arcticTable
                .partitionProperty()
                .get(partitionData)
                .get(HiveTableProperties.PARTITION_PROPERTIES_KEY_TRANSIENT_TIME)
            : null;
    String hiveLocation = hivePartition.getSd().getLocation();
    String arcticPartitionLocation =
        arcticTable.partitionProperty().containsKey(partitionData)
            ? arcticTable
                .partitionProperty()
                .get(partitionData)
                .get(HiveTableProperties.PARTITION_PROPERTIES_KEY_HIVE_LOCATION)
            : null;

    // hive partition location is modified only in arctic full optimize, So if the hive partition
    // location is
    // different from the arctic partition location, it is not necessary to trigger synchronization
    // from the hive
    // side to the arctic
    if (arcticPartitionLocation != null && !arcticPartitionLocation.equals(hiveLocation)) {
      return false;
    }

    // compare hive partition parameter transient_lastDdlTime with arctic partition properties to
    // find out if the partition is changed.
    return arcticTransientTime == null || !arcticTransientTime.equals(hiveTransientTime);
  }

  @VisibleForTesting
  static boolean tableHasModified(UnkeyedTable arcticTable, Table table) {
    String hiveTransientTime = table.getParameters().get("transient_lastDdlTime");
    StructLikeMap<Map<String, String>> structLikeMap = arcticTable.partitionProperty();
    String arcticTransientTime = null;
    if (structLikeMap.get(TablePropertyUtil.EMPTY_STRUCT) != null) {
      arcticTransientTime =
          structLikeMap
              .get(TablePropertyUtil.EMPTY_STRUCT)
              .get(HiveTableProperties.PARTITION_PROPERTIES_KEY_TRANSIENT_TIME);
    }
    String hiveLocation = table.getSd().getLocation();
    String arcticPartitionLocation =
        arcticTable.partitionProperty().containsKey(TablePropertyUtil.EMPTY_STRUCT)
            ? arcticTable
                .partitionProperty()
                .get(TablePropertyUtil.EMPTY_STRUCT)
                .get(HiveTableProperties.PARTITION_PROPERTIES_KEY_HIVE_LOCATION)
            : null;

    // hive partition location is modified only in arctic full optimize, So if the hive partition
    // location is
    // different from the arctic partition location, it is not necessary to trigger synchronization
    // from the hive
    // side to the arctic
    if (arcticPartitionLocation != null && !arcticPartitionLocation.equals(hiveLocation)) {
      return false;
    }

    // compare hive partition parameter transient_lastDdlTime with arctic partition properties to
    // find out if the partition is changed.
    return arcticTransientTime == null || !arcticTransientTime.equals(hiveTransientTime);
  }

  private static List<DataFile> listHivePartitionFiles(
      SupportHive arcticTable, Map<String, String> partitionValueMap, String partitionLocation) {
    return arcticTable
        .io()
        .doAs(
            () ->
                TableMigrationUtil.listPartition(
                    partitionValueMap,
                    partitionLocation,
                    arcticTable
                        .properties()
                        .getOrDefault(
                            TableProperties.DEFAULT_FILE_FORMAT,
                            TableProperties.DEFAULT_FILE_FORMAT_DEFAULT),
                    arcticTable.spec(),
                    arcticTable.io().getConf(),
                    MetricsConfig.fromProperties(arcticTable.properties()),
                    NameMappingParser.fromJson(
                        arcticTable
                            .properties()
                            .get(org.apache.iceberg.TableProperties.DEFAULT_NAME_MAPPING))));
  }

  private static Map<String, String> buildPartitionValueMap(
      List<String> partitionValues, PartitionSpec spec) {
    Map<String, String> partitionValueMap = Maps.newHashMap();
    for (int i = 0; i < partitionValues.size(); i++) {
      partitionValueMap.put(spec.fields().get(i).name(), partitionValues.get(i));
    }
    return partitionValueMap;
  }

  private static void overwriteTable(
      ArcticTable table, List<DataFile> filesToDelete, List<DataFile> filesToAdd) {
    if (filesToDelete.size() > 0 || filesToAdd.size() > 0) {
      LOG.info(
          "Table {} sync hive data change to arctic, delete files: {}, add files {}",
          table.id(),
          filesToDelete.stream().map(DataFile::path).collect(Collectors.toList()),
          filesToAdd.stream().map(DataFile::path).collect(Collectors.toList()));
      if (table.isKeyedTable()) {
        long txId = table.asKeyedTable().beginTransaction(null);
        OverwriteBaseFiles overwriteBaseFiles = table.asKeyedTable().newOverwriteBaseFiles();
        overwriteBaseFiles.set(OverwriteHiveFiles.PROPERTIES_VALIDATE_LOCATION, "false");
        filesToDelete.forEach(overwriteBaseFiles::deleteFile);
        filesToAdd.forEach(overwriteBaseFiles::addFile);
        overwriteBaseFiles.updateOptimizedSequenceDynamically(txId);
        overwriteBaseFiles.commit();
      } else {
        OverwriteFiles overwriteFiles = table.asUnkeyedTable().newOverwrite();
        overwriteFiles.set(OverwriteHiveFiles.PROPERTIES_VALIDATE_LOCATION, "false");
        filesToDelete.forEach(overwriteFiles::deleteFile);
        filesToAdd.forEach(overwriteFiles::addFile);
        overwriteFiles.commit();
      }
    }
  }
}
