/*
 * Copyright (C) 2019 Google LLC
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
package com.google.cloud.teleport.v2.templates;

import com.google.auto.value.AutoValue;
import com.google.cloud.Timestamp;
import com.google.cloud.teleport.v2.templates.session.Session;
import com.google.cloud.teleport.v2.templates.spanner.ddl.Ddl;
import com.google.cloud.teleport.v2.values.FailsafeElement;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.Map;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.gcp.spanner.SpannerConfig;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.PInput;
import org.apache.beam.sdk.values.POutput;
import org.apache.beam.sdk.values.PValue;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;

/**
 * Takes an input of DataStream events as {@link FailsafeElement} objects and writes them to the
 * given Cloud Spanner database.
 *
 * <p>Each event will be written using a single Cloud Spanner Transaction.
 *
 * <p>The {@link Result} object contains two streams: the successfully written Mutation Group
 * objects with their commit timestamps, and the Mutation Group objects that failed to be written
 * along with the text of the exception that caused the failure.
 */
public class SpannerTransactionWriter
    extends PTransform<
        PCollection<FailsafeElement<String, String>>, SpannerTransactionWriter.Result> {

  /* The tag for mutations failed with non-retryable errors. */
  public static final TupleTag<FailsafeElement<String, String>> PERMANENT_ERROR_TAG =
      new TupleTag<FailsafeElement<String, String>>() {};

  /* The Tag for retryable Failed mutations */
  public static final TupleTag<FailsafeElement<String, String>> RETRYABLE_ERROR_TAG =
      new TupleTag<FailsafeElement<String, String>>() {};

  /* The Tag for Successful mutations */
  public static final TupleTag<Timestamp> SUCCESSFUL_EVENT_TAG = new TupleTag<Timestamp>() {};

  /* The spanner config specifying the destination Cloud Spanner database to connect to */
  private final SpannerConfig spannerConfig;

  /* The information schema of the Cloud Spanner database */
  private final PCollectionView<Ddl> ddlView;

  /* The mapping information read from the session file generated by HarbourBridge */
  private final Session session;

  /* The mapping information of database name to shard id */
  private final Map<String, String> shardingConfig;

  /* The prefix for shadow tables */
  private final String shadowTablePrefix;

  /* The datastream source database type. Eg, MySql or Oracle etc. */
  private final String sourceType;

  // If set to true, round decimals inside jsons.
  private final Boolean roundJsonDecimals;

  public SpannerTransactionWriter(
      SpannerConfig spannerConfig,
      PCollectionView<Ddl> ddlView,
      Session session,
      Map<String, String> shardingConfig,
      String shadowTablePrefix,
      String sourceType,
      Boolean roundJsonDecimals) {
    Preconditions.checkNotNull(spannerConfig);
    this.spannerConfig = spannerConfig;
    this.ddlView = ddlView;
    this.session = session;
    this.shardingConfig = shardingConfig;
    this.shadowTablePrefix = shadowTablePrefix;
    this.sourceType = sourceType;
    this.roundJsonDecimals = roundJsonDecimals;
  }

  @Override
  public SpannerTransactionWriter.Result expand(
      PCollection<FailsafeElement<String, String>> input) {
    PCollectionTuple spannerWriteResults =
        input.apply(
            "Write Mutations",
            ParDo.of(
                    new SpannerTransactionWriterDoFn(
                        spannerConfig,
                        ddlView,
                        session,
                        shardingConfig,
                        shadowTablePrefix,
                        sourceType,
                        roundJsonDecimals))
                .withSideInputs(ddlView)
                .withOutputTags(
                    SUCCESSFUL_EVENT_TAG,
                    TupleTagList.of(Arrays.asList(PERMANENT_ERROR_TAG, RETRYABLE_ERROR_TAG))));

    return Result.create(
        spannerWriteResults.get(SUCCESSFUL_EVENT_TAG),
        spannerWriteResults.get(PERMANENT_ERROR_TAG),
        spannerWriteResults.get(RETRYABLE_ERROR_TAG));
  }

  /**
   * Container class for the results of this transform.
   *
   * <p>Use {@link #successfulSpannerWrite()} and {@link #failedSpannerWrite()} to get the two
   * output streams.
   */
  @AutoValue
  public abstract static class Result implements POutput {
    private static Result create(
        PCollection<Timestamp> successfulSpannerWrites,
        PCollection<FailsafeElement<String, String>> permanentErrors,
        PCollection<FailsafeElement<String, String>> retryableErrors) {
      Preconditions.checkNotNull(successfulSpannerWrites);
      Preconditions.checkNotNull(permanentErrors);
      Preconditions.checkNotNull(retryableErrors);
      return new AutoValue_SpannerTransactionWriter_Result(
          successfulSpannerWrites, permanentErrors, retryableErrors);
    }

    public abstract PCollection<Timestamp> successfulSpannerWrites();

    public abstract PCollection<FailsafeElement<String, String>> permanentErrors();

    public abstract PCollection<FailsafeElement<String, String>> retryableErrors();

    @Override
    public void finishSpecifyingOutput(
        String transformName, PInput input, PTransform<?, ?> transform) {
      // required by POutput interface.
    }

    @Override
    public Pipeline getPipeline() {
      return successfulSpannerWrites().getPipeline();
    }

    @Override
    public Map<TupleTag<?>, PValue> expand() {
      return ImmutableMap.of(
          SUCCESSFUL_EVENT_TAG,
          successfulSpannerWrites(),
          PERMANENT_ERROR_TAG,
          permanentErrors(),
          RETRYABLE_ERROR_TAG,
          retryableErrors());
    }
  }
}
