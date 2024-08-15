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
package com.google.cloud.teleport.v2.templates.transforms;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.teleport.v2.spanner.ddl.Ddl;
import com.google.cloud.teleport.v2.spanner.ddl.IndexColumn;
import com.google.cloud.teleport.v2.spanner.ddl.Table;
import com.google.cloud.teleport.v2.spanner.migrations.convertors.ChangeEventSpannerConvertor;
import com.google.cloud.teleport.v2.spanner.migrations.schema.Schema;
import com.google.cloud.teleport.v2.spanner.migrations.shard.Shard;
import com.google.cloud.teleport.v2.templates.changestream.TrimmedShardedDataChangeRecord;
import com.google.cloud.teleport.v2.templates.constants.Constants;
import com.google.cloud.teleport.v2.templates.utils.InputRecordProcessor;
import com.google.cloud.teleport.v2.templates.utils.MySqlDao;
import com.google.cloud.teleport.v2.templates.utils.SpannerDao;
import com.google.common.collect.ImmutableList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.beam.sdk.io.gcp.spanner.SpannerConfig;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Distribution;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.DoFn.ProcessContext;
import org.apache.beam.sdk.transforms.DoFn.ProcessElement;
import org.apache.beam.sdk.values.KV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** This class writes to source based on commit timestamp captured in shadow table. */
public class SourceWriterFn extends DoFn<KV<Long, TrimmedShardedDataChangeRecord>, Void> {
  private static final Logger LOG = LoggerFactory.getLogger(SourceWriterFn.class);
  private transient ObjectMapper mapper;

  private final Counter numRecProcessedMetric =
      Metrics.counter(SourceWriterFn.class, "records_processed");

  private final Distribution lagMetric =
      Metrics.distribution(SourceWriterFn.class, "replication_lag_in_milli");
  private transient Map<String, MySqlDao> mySqlDaoMap = new HashMap<>();

  private final Schema schema;
  private final String sourceDbTimezoneOffset;
  private final List<Shard> shards;
  private final SpannerConfig spannerConfig;
  private transient SpannerDao spannerDao;
  private final Ddl ddl;
  private final String shadowTablePrefix;

  public SourceWriterFn(
      List<Shard> shards,
      Schema schema,
      SpannerConfig spannerConfig,
      String sourceDbTimezoneOffset,
      Ddl ddl,
      String shadowTablePrefix) {

    this.schema = schema;
    this.sourceDbTimezoneOffset = sourceDbTimezoneOffset;
    this.shards = shards;
    this.spannerConfig = spannerConfig;
    this.ddl = ddl;
    this.shadowTablePrefix = shadowTablePrefix;
  }

  // for unit testing purposes
  public void setSpannerDao(SpannerDao spannerDao) {
    this.spannerDao = spannerDao;
  }

  // for unit testing purposes
  public void setMySqlDaoMap(Map<String, MySqlDao> mySqlDaoMap) {
    this.mySqlDaoMap = mySqlDaoMap;
  }

  // for unit testing purposes
  public void setObjectMapper(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /** Setup function connects to Cloud Spanner. */
  @Setup
  public void setup() {
    mapper = new ObjectMapper();
    mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    // TODO: Support multiple databases
    for (Shard shard : shards) {
      String sourceConnectionUrl =
          "jdbc:mysql://" + shard.getHost() + ":" + shard.getPort() + "/" + shard.getDbName();
      mySqlDaoMap = new HashMap<>();
      mySqlDaoMap.put(
          shard.getLogicalShardId(),
          new MySqlDao(sourceConnectionUrl, shard.getUserName(), shard.getPassword()));
    }
    spannerDao = new SpannerDao(spannerConfig);
  }

  /** Teardown function disconnects from the Cloud Spanner. */
  @Teardown
  public void teardown() throws Exception {
    if (mySqlDaoMap != null) {
      for (MySqlDao mySqlDao : mySqlDaoMap.values()) {
        mySqlDao.cleanup();
      }
    }
    spannerDao.close();
    mySqlDaoMap.clear();
  }

  @ProcessElement
  public void processElement(ProcessContext c) {
    KV<Long, TrimmedShardedDataChangeRecord> element = c.element();
    TrimmedShardedDataChangeRecord spannerRec = element.getValue();
    String shardId = spannerRec.getShard();
    // Get the latest commit timestamp processed at source
    try {
      JsonNode keysJson = mapper.readTree(spannerRec.getMod().getKeysJson());
      String tableName = spannerRec.getTableName();
      com.google.cloud.spanner.Key primaryKey =
          ChangeEventSpannerConvertor.changeEventToPrimaryKey(
              tableName, ddl, keysJson, /* convertNameToLowerCase= */ false);
      String shadowTableName = shadowTablePrefix + tableName;
      boolean isSourceAhead = false;

      com.google.cloud.Timestamp processedCommitTimestamp =
          spannerDao.getProcessedCommitTimestamp(shadowTableName, primaryKey);
      isSourceAhead =
          processedCommitTimestamp != null
              && (processedCommitTimestamp.compareTo(spannerRec.getCommitTimestamp()) > 0);

      if (!isSourceAhead) {
        MySqlDao mySqlDao = mySqlDaoMap.get(shardId);

        InputRecordProcessor.processRecord(
            spannerRec, schema, mySqlDao, shardId, sourceDbTimezoneOffset);

        try {
          spannerDao.updateProcessedCommitTimestamp(
              getShadowTableMutation(
                  tableName, shadowTableName, keysJson, spannerRec.getCommitTimestamp()));
        } catch (Exception e) {
          LOG.error("Failed to update last commit timestamp", e);
          // TODO DLQ handling
        }
      }
    } catch (Exception e) {
      LOG.error("Failed to write to source", e);
    }
  }

  private Mutation getShadowTableMutation(
      String tableName,
      String shadowTableName,
      JsonNode keysJson,
      com.google.cloud.Timestamp commitTimestamp) {
    Mutation.WriteBuilder mutationBuilder = null;
    try {
      Table table = ddl.table(tableName);
      ImmutableList<IndexColumn> keyColumns = table.primaryKeys();
      List<String> keyColumnNames =
          keyColumns.stream().map(k -> k.name()).collect(Collectors.toList());
      Set<String> keyColumnNamesSet = new HashSet<>(keyColumnNames);
      mutationBuilder =
          ChangeEventSpannerConvertor.mutationBuilderFromEvent(
              shadowTableName,
              table,
              keysJson,
              keyColumnNames,
              keyColumnNamesSet,
              /* convertNameToLowerCase= */ false);
      mutationBuilder.set(Constants.PROCESSED_COMMIT_TS_COLUMN_NAME).to(commitTimestamp);
    } catch (Exception e) {
      LOG.error("Failed to update last commit timestamp", e);
    }
    return mutationBuilder.build();
  }
}
