/*
 * Copyright (C) 2020 Google LLC
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

import com.google.cloud.bigtable.beam.CloudBigtableIO;
import com.google.cloud.bigtable.beam.CloudBigtableTableConfiguration;
import com.google.cloud.teleport.metadata.Template;
import com.google.cloud.teleport.metadata.TemplateCategory;
import com.google.cloud.teleport.metadata.TemplateParameter;
import com.google.cloud.teleport.v2.avro.BigtableRow;
import com.google.cloud.teleport.v2.options.BigtableCommonOptions.WriteOptions;
import com.google.cloud.teleport.v2.options.PubsubCommonOptions.ReadSubscriptionOptions;
import com.google.cloud.teleport.v2.transforms.AvroToBigtableMutation;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.Validation;
import org.apache.beam.sdk.transforms.ParDo;

/**
 * A Dataflow pipeline to stream <a href="https://avro.apache.org/">Apache Avro</a> records from
 * Pub/Sub into a Bigtable table.
 *
 * <p>If the pipeline fails to acknowledge the packet received, PubSubIO will forward this
 * unprocessed packet to a dead-letter topic.
 */
@Template(
    name = "PubSub_Avro_to_Bigtable",
    category = TemplateCategory.STREAMING,
    displayName = "Pub/Sub Avro to Bigtable",
    description =
        "A streaming pipeline which inserts Avro records from a Pub/Sub subscription into a"
            + " Bigtable table.",
    optionsClass = PubsubAvroToBigtable.PubsubAvroToBigtableOptions.class,
    flexContainerName = "pubsub-avro-to-bigtable",
    contactInformation = "https://cloud.google.com/support")
public final class PubsubAvroToBigtable {

  /**
   * Validates input flags and executes the Dataflow pipeline.
   *
   * @param args command line arguments to the pipeline
   */
  public static void main(String[] args) {
    PubsubAvroToBigtableOptions options =
        PipelineOptionsFactory.fromArgs(args)
            .withValidation()
            .as(PubsubAvroToBigtableOptions.class);

    run(options);
  }

  /**
   * Provides custom {@link org.apache.beam.sdk.options.PipelineOptions} required to execute the
   * {@link PubsubAvroToBigtable} pipeline.
   */
  public interface PubsubAvroToBigtableOptions extends ReadSubscriptionOptions, WriteOptions {

    @TemplateParameter.PubsubTopic(
        order = 1,
        description = "Dead Letter Pub/Sub topic",
        helpText =
            "The name of the topic to write dead-letter records, in the format of 'projects/your-project-id/topics/your-topic-name'",
        example = "projects/your-project-id/topics/your-topic-name")
    @Validation.Required
    String getDeadLetterTopic();

    void setDeadLetterTopic(String deadLetterTopic);
  }

  /**
   * Runs the pipeline with the supplied options.
   *
   * @param options execution parameters to the pipeline
   * @return result of the pipeline execution as a {@link PipelineResult}
   */
  private static PipelineResult run(PubsubAvroToBigtableOptions options) {

    // Create the pipeline.
    Pipeline pipeline = Pipeline.create(options);

    // Create Bigtable configuration
    CloudBigtableTableConfiguration bigtableTableConfig =
        new CloudBigtableTableConfiguration.Builder()
            .withProjectId(options.getBigtableWriteProjectId())
            .withInstanceId(options.getBigtableWriteInstanceId())
            .withAppProfileId(options.getBigtableWriteAppProfile())
            .withTableId(options.getBigtableWriteTableId())
            .build();

    pipeline
        .apply(
            "Read Avro Records from Pub/Sub Subscription",
            PubsubIO.readAvros(BigtableRow.class)
                .fromSubscription(options.getInputSubscription())
                .withDeadLetterTopic(options.getDeadLetterTopic()))
        .apply("Transform to Bigtable Mutation", ParDo.of(new AvroToBigtableMutation()))
        .apply("Write To Bigtable", CloudBigtableIO.writeToTable(bigtableTableConfig));

    // Execute the pipeline and return the result.
    return pipeline.run();
  }
}
