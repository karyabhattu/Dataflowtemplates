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
package com.google.cloud.teleport.v2.bigtable.options;

import com.google.cloud.teleport.metadata.TemplateParameter;
import org.apache.beam.sdk.extensions.gcp.options.GcpOptions;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.Validation;

/**
 * Common {@link PipelineOptions} for reading and writing data using {@link
 * org.apache.beam.sdk.io.gcp.bigtable.BigtableIO}.
 */
public interface BigtableCommonOptions extends GcpOptions {
  @TemplateParameter.Integer(
      order = 1,
      optional = true,
      description = "The timeout for an RPC attempt in milliseconds",
      helpText = "This sets the timeout for an RPC attempt in milliseconds")
  Integer getBigtableRpcAttemptTimeoutMs();

  void setBigtableRpcAttemptTimeoutMs(Integer value);

  @TemplateParameter.Integer(
      order = 2,
      optional = true,
      description = "The total timeout for an RPC operation in milliseconds",
      helpText = "This sets the total timeout for an RPC operation in milliseconds")
  Integer getBigtableRpcTimeoutMs();

  void setBigtableRpcTimeoutMs(Integer value);

  @TemplateParameter.Text(
      order = 3,
      optional = true,
      description = "The additional retry codes",
      helpText = "This sets the additional retry codes, separated by ','",
      example = "RESOURCE_EXHAUSTED,DEADLINE_EXCEEDED")
  String getBigtableAdditionalRetryCodes();

  void setBigtableAdditionalRetryCodes(String value);

  /** Provides {@link PipelineOptions} to write records to a Bigtable table. */
  interface WriteOptions extends BigtableCommonOptions {
    @TemplateParameter.Text(
        order = 1,
        regexes = {"[a-z][a-z0-9\\-]+[a-z0-9]"},
        description = "Bigtable Instance ID",
        helpText = "The ID of the Cloud Bigtable instance that contains the table")
    @Validation.Required
    String getBigtableWriteInstanceId();

    void setBigtableWriteInstanceId(String value);

    @TemplateParameter.Text(
        order = 2,
        regexes = {"[_a-zA-Z0-9][-_.a-zA-Z0-9]*"},
        description = "Bigtable Table ID",
        helpText = "The ID of the Cloud Bigtable table to write")
    @Validation.Required
    String getBigtableWriteTableId();

    void setBigtableWriteTableId(String value);

    @TemplateParameter.Text(
        order = 3,
        regexes = {"[-_.a-zA-Z0-9]+"},
        description = "The Bigtable Column Family",
        helpText = "This specifies the column family to write data into")
    @Validation.Required
    String getBigtableWriteColumnFamily();

    void setBigtableWriteColumnFamily(String value);

    @TemplateParameter.Text(
        order = 4,
        optional = true,
        regexes = {"[a-z][a-z0-9\\-]+[a-z0-9]"},
        description = "Bigtable App Profile",
        helpText =
            "Bigtable App Profile to use for the export. The default for this parameter "
                + "is the Bigtable instance's default app profile")
    @Default.String("default")
    String getBigtableWriteAppProfile();

    void setBigtableWriteAppProfile(String value);

    @TemplateParameter.ProjectId(
        order = 5,
        optional = true,
        description = "Bigtable Project ID",
        helpText =
            "The ID of the Google Cloud project of the Cloud Bigtable instance that you want "
                + "to write data to.")
    String getBigtableWriteProjectId();

    void setBigtableWriteProjectId(String value);

    @TemplateParameter.Integer(
        order = 6,
        optional = true,
        description = "Bigtable's latency target in milliseconds for latency-based throttling",
        helpText = "This enables latency-based throttling and specifies the target latency")
    Integer getBigtableBulkWriteLatencyTargetMs();

    void setBigtableBulkWriteLatencyTargetMs(Integer value);

    @TemplateParameter.Integer(
        order = 7,
        optional = true,
        description = "The max number of row keys in a Bigtable batch write operation",
        helpText = "This sets the max number of row keys in a Bigtable batch write operation")
    Integer getBigtableBulkWriteMaxRowKeyCount();

    void setBigtableBulkWriteMaxRowKeyCount(Integer value);

    @TemplateParameter.Integer(
        order = 8,
        optional = true,
        description = "The max amount of bytes in a Bigtable batch write operation",
        helpText = "This sets the max amount of bytes in a Bigtable batch write operation")
    Integer getBigtableBulkWriteMaxRequestSizeBytes();

    void setBigtableBulkWriteMaxRequestSizeBytes(Integer value);
  }

  interface ReadChangeStreamsOptions extends BigtableCommonOptions {
    @TemplateParameter.Text(
        order = 1,
        description = "Cloud Bigtable instance ID",
        helpText = "The Cloud Bigtable instance to read change streams from.")
    @Validation.Required
    String getBigtableInstanceId();

    void setBigtableInstanceId(String value);

    @TemplateParameter.Text(
        order = 2,
        description = "Cloud Bigtable table ID",
        helpText = "The Cloud Bigtable table to read change streams from.")
    @Validation.Required
    String getBigtableTableId();

    void setBigtableTableId(String value);

    @TemplateParameter.Text(
        order = 3,
        description = "Cloud Bigtable application profile ID",
        helpText = "The application profile is used to distinguish workload in Cloud Bigtable")
    @Validation.Required
    String getBigtableAppProfileId();

    void setBigtableAppProfileId(String value);

    @TemplateParameter.ProjectId(
        order = 4,
        optional = true,
        description = "Cloud Bigtable Project ID",
        helpText =
            "Project to read change streams from. The default for this parameter is the project "
                + "where the Dataflow pipeline is running.")
    @Default.String("")
    String getBigtableProjectId();

    void setBigtableProjectId(String projectId);

    @TemplateParameter.Text(
        order = 5,
        optional = true,
        description = "Cloud Bigtable metadata instance ID",
        helpText =
            "The Cloud Bigtable instance to use for the change streams connector metadata table.")
    @Default.String("")
    String getBigtableMetadataInstanceId();

    void setBigtableMetadataInstanceId(String value);

    @TemplateParameter.Text(
        order = 6,
        optional = true,
        description = "Cloud Bigtable metadata table ID",
        helpText =
            "The Cloud Bigtable change streams connector metadata table ID to use. If not "
                + "provided, a Cloud Bigtable change streams connector metadata table will automatically be "
                + "created during the pipeline flow. This parameter must be provided when updating an "
                + "existing pipeline and should not be provided otherwise.")
    @Default.String("")
    String getBigtableMetadataTableTableId();

    void setBigtableMetadataTableTableId(String value);

    @TemplateParameter.Text(
        order = 7,
        optional = true,
        description = "Bigtable charset name when reading values and column qualifiers",
        helpText =
            "Bigtable charset name when reading values and column qualifiers. "
                + "Default is UTF-8")
    @Default.String("UTF-8")
    String getBigtableCharset();

    void setBigtableCharset(String value);

    @TemplateParameter.DateTime(
        order = 8,
        optional = true,
        description = "The timestamp to read change streams from",
        helpText =
            "The starting DateTime, inclusive, to use for reading change streams "
                + "(https://tools.ietf.org/html/rfc3339). For example, 2022-05-05T07:59:59Z. Defaults to the "
                + "timestamp when the pipeline starts.")
    @Default.String("")
    String getStartTimestamp();

    void setStartTimestamp(String startTimestamp);
  }
}
