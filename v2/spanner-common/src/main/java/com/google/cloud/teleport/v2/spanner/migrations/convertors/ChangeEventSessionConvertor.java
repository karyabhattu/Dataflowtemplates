/*
 * Copyright (C) 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.teleport.v2.spanner.migrations.convertors;

import static com.google.cloud.teleport.v2.spanner.migrations.constants.Constants.EVENT_METADATA_KEY_PREFIX;
import static com.google.cloud.teleport.v2.spanner.migrations.constants.Constants.EVENT_SCHEMA_KEY;
import static com.google.cloud.teleport.v2.spanner.migrations.constants.Constants.EVENT_STREAM_NAME;
import static com.google.cloud.teleport.v2.spanner.migrations.constants.Constants.EVENT_TABLE_NAME_KEY;
import static com.google.cloud.teleport.v2.spanner.migrations.constants.Constants.EVENT_UUID_KEY;
import static com.google.cloud.teleport.v2.spanner.migrations.constants.Constants.MYSQL_SOURCE_TYPE;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.teleport.v2.spanner.ddl.Ddl;
import com.google.cloud.teleport.v2.spanner.migrations.exceptions.InvalidChangeEventException;
import com.google.cloud.teleport.v2.spanner.migrations.schema.ISchemaOverridesParser;
import com.google.cloud.teleport.v2.spanner.migrations.schema.NameAndCols;
import com.google.cloud.teleport.v2.spanner.migrations.schema.Schema;
import com.google.cloud.teleport.v2.spanner.migrations.schema.SourceColumnDefinition;
import com.google.cloud.teleport.v2.spanner.migrations.schema.SourceTable;
import com.google.cloud.teleport.v2.spanner.migrations.schema.SpannerColumnDefinition;
import com.google.cloud.teleport.v2.spanner.migrations.schema.SpannerTable;
import com.google.cloud.teleport.v2.spanner.migrations.schema.SyntheticPKey;
import com.google.cloud.teleport.v2.spanner.migrations.shard.ShardingContext;
import com.google.cloud.teleport.v2.spanner.migrations.transformation.TransformationContext;
import com.google.cloud.teleport.v2.spanner.migrations.utils.ChangeEventUtils;
import com.google.cloud.teleport.v2.spanner.type.Type;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Utility class with methods which converts change events based on {@link
 * com.google.cloud.teleport.v2.spanner.migrations.schema}.
 */
public class ChangeEventSessionConvertor {
  // The mapping information read from the session file generated by HarbourBridge.
  private final Schema schema;

  private final ISchemaOverridesParser schemaOverridesParser;

  /* The context used to populate transformation information */
  private final TransformationContext transformationContext;

  /* The context used to populate shard id in the spanner database. */
  private final ShardingContext shardingContext;

  // The source database type.
  private final String sourceType;

  // If set to true, round decimals inside jsons.
  private final Boolean roundJsonDecimals;

  public ChangeEventSessionConvertor(
      Schema schema,
      ISchemaOverridesParser schemaOverridesParser, TransformationContext transformationContext,
      ShardingContext shardingContext,
      String sourceType,
      boolean roundJsonDecimals) {
    this.schema = schema;
    this.schemaOverridesParser = schemaOverridesParser;
    this.transformationContext = transformationContext;
    this.shardingContext = shardingContext;
    this.sourceType = sourceType;
    this.roundJsonDecimals = roundJsonDecimals;
  }

  /**
   * This function modifies the change event using transformations based on the session file (stored
   * in the Schema object). This includes column/table name changes and adding of synthetic Primary
   * Keys.
   */
  public JsonNode transformChangeEventViaSessionFile(JsonNode changeEvent) {
    String tableName = changeEvent.get(EVENT_TABLE_NAME_KEY).asText();
    String tableId = schema.getSrcToID().get(tableName).getName();

    // Convert table and column names in change event.
    changeEvent = convertTableAndColumnNames(changeEvent, tableName);

    // Add synthetic PK to change event.
    changeEvent = addSyntheticPKs(changeEvent, tableId);

    // Remove columns present in change event that were dropped in Spanner.
    changeEvent = removeDroppedColumns(changeEvent, tableId);

    // Add shard id to change event.
    changeEvent = populateShardId(changeEvent, tableId);

    return changeEvent;
  }

  public String getShardId(JsonNode changeEvent) {
    if (!MYSQL_SOURCE_TYPE.equals(this.sourceType)
        || ((shardingContext.getStreamToDbAndShardMap() == null
                || shardingContext.getStreamToDbAndShardMap().isEmpty())
            && (transformationContext.getSchemaToShardId() == null
                || transformationContext.getSchemaToShardId().isEmpty()))) {
      return "";
    }
    String shardId = "";
    // Fetch shard id from sharding/transformation context.
    if (shardingContext != null && !shardingContext.getStreamToDbAndShardMap().isEmpty()) {
      Map<String, Map<String, String>> streamToDbAndShardMap =
          shardingContext.getStreamToDbAndShardMap();
      if (streamToDbAndShardMap != null && !streamToDbAndShardMap.isEmpty()) {
        String streamName =
            changeEvent
                .get(EVENT_STREAM_NAME)
                .asText()
                .substring(changeEvent.get(EVENT_STREAM_NAME).asText().lastIndexOf('/') + 1);
        Map<String, String> schemaToShardId = streamToDbAndShardMap.get(streamName);
        if (schemaToShardId != null && !schemaToShardId.isEmpty()) {
          String schemaName = changeEvent.get(EVENT_SCHEMA_KEY).asText();
          shardId = schemaToShardId.getOrDefault(schemaName, "");
        }
      }
    } else {
      Map<String, String> schemaToShardId = transformationContext.getSchemaToShardId();
      String schemaName = changeEvent.get(EVENT_SCHEMA_KEY).asText();
      shardId = schemaToShardId.get(schemaName);
    }
    return shardId;
  }

  JsonNode populateShardId(JsonNode changeEvent, String tableId) {
    if (!MYSQL_SOURCE_TYPE.equals(this.sourceType)
        || ((shardingContext.getStreamToDbAndShardMap() == null
                || shardingContext.getStreamToDbAndShardMap().isEmpty())
            && (transformationContext.getSchemaToShardId() == null
                || transformationContext.getSchemaToShardId().isEmpty()))) {
      return changeEvent; // Nothing to do
    }

    SpannerTable table = schema.getSpSchema().get(tableId);
    String shardIdColumn = table.getShardIdColumn();
    if (shardIdColumn == null) {
      return changeEvent;
    }
    SpannerColumnDefinition shardIdColDef = table.getColDefs().get(table.getShardIdColumn());
    if (shardIdColDef == null) {
      return changeEvent;
    }
    String shardId = getShardId(changeEvent);
    ((ObjectNode) changeEvent).put(shardIdColDef.getName(), shardId);
    return changeEvent;
  }

  JsonNode convertTableAndColumnNames(JsonNode changeEvent, String tableName) {
    NameAndCols nameAndCols = schema.getToSpanner().get(tableName);
    String spTableName = nameAndCols.getName();
    Map<String, String> cols = nameAndCols.getCols();

    // Convert the table name to corresponding Spanner table name.
    ((ObjectNode) changeEvent).put(EVENT_TABLE_NAME_KEY, spTableName);
    // Convert the column names to corresponding Spanner column names.
    for (Map.Entry<String, String> col : cols.entrySet()) {
      String srcCol = col.getKey(), spCol = col.getValue();
      if (!srcCol.equals(spCol)) {
        ((ObjectNode) changeEvent).set(spCol, changeEvent.get(srcCol));
        ((ObjectNode) changeEvent).remove(srcCol);
      }
    }
    return changeEvent;
  }

  JsonNode addSyntheticPKs(JsonNode changeEvent, String tableId) {
    Map<String, SpannerColumnDefinition> spCols = schema.getSpSchema().get(tableId).getColDefs();
    Map<String, SyntheticPKey> synthPks = schema.getSyntheticPks();
    if (synthPks.containsKey(tableId)) {
      String colID = synthPks.get(tableId).getColId();
      if (!spCols.containsKey(colID)) {
        throw new IllegalArgumentException(
            "Missing entry for "
                + colID
                + " in colDefs for tableId: "
                + tableId
                + ", provide a valid session file.");
      }
      ((ObjectNode) changeEvent)
          .put(spCols.get(colID).getName(), changeEvent.get(EVENT_UUID_KEY).asText());
    }
    return changeEvent;
  }

  JsonNode removeDroppedColumns(JsonNode changeEvent, String tableId) {
    Map<String, SpannerColumnDefinition> spCols = schema.getSpSchema().get(tableId).getColDefs();
    SourceTable srcTable = schema.getSrcSchema().get(tableId);
    Map<String, SourceColumnDefinition> srcCols = srcTable.getColDefs();
    for (String colId : srcTable.getColIds()) {
      // If spanner columns do not contain this column Id, drop from change event.
      if (!spCols.containsKey(colId)) {
        ((ObjectNode) changeEvent).remove(srcCols.get(colId).getName());
      }
    }
    return changeEvent;
  }


  public JsonNode transformChangeEventViaOverrides(JsonNode changeEvent)
      throws InvalidChangeEventException {
    String sourceTableName = changeEvent.get(EVENT_TABLE_NAME_KEY).asText();
    String spTableName = schemaOverridesParser.getTableOverrideOrDefault(sourceTableName);
    //Replace the source table name with the overridden spanner table name if the override
    //is specified at the table level.
    if (!sourceTableName.equals(spTableName)) {
      ((ObjectNode) changeEvent).put(EVENT_TABLE_NAME_KEY, spTableName);
    }
    //Get the list of sourceColumnNames from the event
    List<String> sourceFieldNames = ChangeEventUtils.getEventColumnKeys(changeEvent);
    sourceFieldNames.forEach( sourceFieldName -> {
      Pair<String, String> spannerTableColumn = schemaOverridesParser.getColumnOverrideOrDefault(sourceTableName, sourceFieldName);
      // a valid column override for the table in this changeEvent exist
      //1.  the table name of the source should match the one specified in the override
      //2. the column name override should be a different value than the current source field name.
      if (sourceTableName.equals(spannerTableColumn.getLeft()) && !sourceFieldName.equals(spannerTableColumn.getRight())) {
        ((ObjectNode) changeEvent).set(spannerTableColumn.getRight(), changeEvent.get(sourceFieldName));
        ((ObjectNode) changeEvent).remove(sourceFieldName);
      }
    });
    return changeEvent;
  }

  /**
   * This function changes the modifies and data of the change event. Currently, only supports a
   * single transformation set by roundJsonDecimals.
   */
  public JsonNode transformChangeEventData(JsonNode changeEvent, DatabaseClient dbClient, Ddl ddl)
      throws Exception {
    if (!roundJsonDecimals) {
      return changeEvent;
    }
    String tableName = changeEvent.get(EVENT_TABLE_NAME_KEY).asText();
    if (ddl.table(tableName) == null) {
      throw new Exception("Table from change event does not exist in Spanner. table=" + tableName);
    }
    Iterator<String> fieldNames = changeEvent.fieldNames();
    List<String> columnNames =
        StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(fieldNames, Spliterator.ORDERED), false)
            .filter(f -> !f.startsWith(EVENT_METADATA_KEY_PREFIX))
            .collect(Collectors.toList());
    for (String columnName : columnNames) {
      Type columnType = ddl.table(tableName).column(columnName).type();
      if (columnType.getCode() == Type.Code.JSON || columnType.getCode() == Type.Code.PG_JSONB) {
        // JSON type cannot be a key column, hence setting requiredField to false.
        String jsonStr =
            ChangeEventTypeConvertor.toString(
                changeEvent, columnName.toLowerCase(), /* requiredField= */ false);
        if (jsonStr != null) {
          Statement statement =
              Statement.newBuilder(
                      "SELECT PARSE_JSON(@jsonStr, wide_number_mode=>'round') as newJson")
                  .bind("jsonStr")
                  .to(jsonStr)
                  .build();
          ResultSet resultSet = dbClient.singleUse().executeQuery(statement);
          while (resultSet.next()) {
            // We want to send the errors to the severe error queue, hence we do not catch any error
            // here.
            String val = resultSet.getJson("newJson");
            ((ObjectNode) changeEvent).put(columnName.toLowerCase(), val);
          }
        }
      }
    }
    return changeEvent;
  }
}
