/*
 * Copyright (C) 2023 Google LLC
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
package com.google.cloud.teleport.v2.auto.dlq;

import com.google.cloud.teleport.metadata.TemplateParameter;
import com.google.cloud.teleport.metadata.auto.Consumes;
import com.google.cloud.teleport.v2.auto.blocks.BlockConstants;
import com.google.cloud.teleport.v2.auto.schema.RowTypes;
import com.google.cloud.teleport.v2.auto.schema.TemplateWriteTransform;
import com.google.cloud.teleport.v2.auto.schema.TemplateOptionSchema;
import com.google.cloud.teleport.v2.coders.FailsafeElementCoder;
import com.google.cloud.teleport.v2.transforms.ErrorConverters;
import com.google.cloud.teleport.v2.utils.ResourceUtils;
import com.google.cloud.teleport.v2.values.FailsafeElement;
import org.apache.beam.sdk.coders.NullableCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessageWithAttributesAndMessageIdCoder;
import org.apache.beam.sdk.schemas.annotations.DefaultSchema;
import org.apache.beam.sdk.schemas.annotations.SchemaFieldDescription;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.values.PCollectionRowTuple;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.checkerframework.checker.nullness.qual.NonNull;

public class WriteDlqToBigQuery
    extends TemplateWriteTransform<WriteDlqToBigQuery.BigQueryDlqConfiguration> {

  @Override
  public PCollectionRowTuple transform(PCollectionRowTuple input, BigQueryDlqConfiguration config) {
    return null;
  }

  @Override
  public @NonNull String identifier() {
    return "blocks:external:org.apache.beam:write_dlq_to_bigquery:v1";
  }

  @DefaultSchema(TemplateOptionSchema.class)
  public interface BigQueryDlqConfiguration extends TemplateBlockOptions {

    @TemplateParameter.BigQueryTable(
        order = 4,
        optional = true,
        description =
            "Table for messages failed to reach the output table (i.e., Deadletter table).",
        helpText =
            "Messages failed to reach the output table for all kind of reasons (e.g., mismatched"
                + " schema, malformed json) are written to this table. It should be in the format"
                + " of \"your-project-id:your-dataset.your-table-name\". If it doesn't exist, it"
                + " will be created during pipeline execution. If not specified,"
                + " \"{outputTableSpec}_error_records\" is used instead.")
    @SchemaFieldDescription(
        "Table for messages failed to reach the output table (i.e., Deadletter table).")
    String getOutputDeadletterTable();

    void setOutputDeadletterTable(String value);
  }

  @Consumes(RowTypes.FailsafePubSubRow.class)
  public void writeDLQToBigQueryForPubsubMessage(
      PCollectionRowTuple input, BigQueryDlqConfiguration options) {
    input
        .get(BlockConstants.ERROR_TAG)
        .apply(
            MapElements.into(new TypeDescriptor<FailsafeElement<PubsubMessage, String>>() {})
                .via(RowTypes.FailsafePubSubRow::rowToFailsafePubSub))
        .setCoder(
            FailsafeElementCoder.of(
                NullableCoder.of(PubsubMessageWithAttributesAndMessageIdCoder.of()),
                NullableCoder.of(StringUtf8Coder.of())))
        .apply(
            ErrorConverters.WritePubsubMessageErrors.newBuilder()
                .setErrorRecordsTable(options.getOutputDeadletterTable())
                .setErrorRecordsTableSchema(ResourceUtils.getDeadletterTableSchemaJson())
                .build());
  }

  @Consumes(RowTypes.FailsafeStringRow.class)
  public void writeDLQToBigQueryForString(
      PCollectionRowTuple input, BigQueryDlqConfiguration options) {
    input
        .get(BlockConstants.ERROR_TAG)
        .apply(
            MapElements.into(new TypeDescriptor<FailsafeElement<String, String>>() {})
                .via(RowTypes.FailsafeStringRow::rowToFailsafeString))
        .apply(
            ErrorConverters.WriteStringMessageErrors.newBuilder()
                .setErrorRecordsTable(options.getOutputDeadletterTable())
                .setErrorRecordsTableSchema(ResourceUtils.getDeadletterTableSchemaJson())
                .build());
  }

  @Override
  public Class<BigQueryDlqConfiguration> getOptionsClass() {
    return BigQueryDlqConfiguration.class;
  }
}
