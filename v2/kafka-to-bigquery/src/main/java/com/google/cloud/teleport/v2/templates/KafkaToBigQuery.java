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

import com.google.api.services.bigquery.model.TableRow;
import com.google.cloud.teleport.metadata.Template;
import com.google.cloud.teleport.metadata.TemplateCategory;
import com.google.cloud.teleport.metadata.TemplateParameter;
import com.google.cloud.teleport.v2.coders.FailsafeElementCoder;
import com.google.cloud.teleport.v2.common.UncaughtExceptionLogger;
import com.google.cloud.teleport.v2.kafka.transforms.KafkaTransform;
import com.google.cloud.teleport.v2.options.BigQueryCommonOptions;
import com.google.cloud.teleport.v2.options.BigQueryStorageApiStreamingOptions;
import com.google.cloud.teleport.v2.templates.KafkaToBigQuery.KafkaToBQOptions;
import com.google.cloud.teleport.v2.transforms.BigQueryConverters;
import com.google.cloud.teleport.v2.transforms.BigQueryConverters.FailsafeJsonToTableRow;
import com.google.cloud.teleport.v2.transforms.ErrorConverters;
import com.google.cloud.teleport.v2.transforms.ErrorConverters.WriteStringKVMessageErrors;
import com.google.cloud.teleport.v2.transforms.JavascriptTextTransformer.FailsafeJavascriptUdf;
import com.google.cloud.teleport.v2.transforms.JavascriptTextTransformer.JavascriptTextTransformerOptions;
import com.google.cloud.teleport.v2.utils.BigQueryIOUtils;
import com.google.cloud.teleport.v2.utils.MetadataValidator;
import com.google.cloud.teleport.v2.utils.SchemaUtils;
import com.google.cloud.teleport.v2.values.FailsafeElement;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.coders.ByteArrayCoder;
import org.apache.beam.sdk.coders.CoderRegistry;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.NullableCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.extensions.avro.schemas.utils.AvroUtils;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.CreateDisposition;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.WriteDisposition;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryInsertError;
import org.apache.beam.sdk.io.gcp.bigquery.InsertRetryPolicy;
import org.apache.beam.sdk.io.gcp.bigquery.WriteResult;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.schemas.SchemaCoder;
import org.apache.beam.sdk.schemas.transforms.Convert;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Flatten;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Values;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionList;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.apache.beam.sdk.values.TypeDescriptors;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link KafkaToBigQuery} pipeline is a streaming pipeline which ingests text data from Kafka,
 * executes a UDF, and outputs the resulting records to BigQuery. Any errors which occur in the
 * transformation of the data, execution of the UDF, or inserting into the output table will be
 * inserted into a separate errors table in BigQuery. The errors table will be created if it does
 * not exist prior to execution. Both output and error tables are specified by the user as
 * parameters.
 *
 * <p><b>Pipeline Requirements</b>
 *
 * <ul>
 *   <li>The Kafka topic exists and the message is encoded in a valid JSON format.
 *   <li>The BigQuery output table exists.
 *   <li>The Kafka brokers are reachable from the Dataflow worker machines.
 * </ul>
 *
 * <p>Check out <a
 * href="https://github.com/GoogleCloudPlatform/DataflowTemplates/blob/main/v2/kafka-to-bigquery/README_Kafka_to_BigQuery.md">README</a>
 * for instructions on how to use or modify this template.
 */
@Template(
    name = "Kafka_to_BigQuery",
    category = TemplateCategory.STREAMING,
    displayName = "Kafka to BigQuery",
    description =
        "The Apache Kafka to BigQuery template is a streaming pipeline which ingests text data from Apache Kafka, executes a user-defined function (UDF), and outputs the resulting records to BigQuery. "
            + "Any errors which occur in the transformation of the data, execution of the UDF, or inserting into the output table are inserted into a separate errors table in BigQuery. "
            + "If the errors table does not exist prior to execution, then it is created.",
    optionsClass = KafkaToBQOptions.class,
    flexContainerName = "kafka-to-bigquery",
    documentation =
        "https://cloud.google.com/dataflow/docs/guides/templates/provided/kafka-to-bigquery",
    contactInformation = "https://cloud.google.com/support",
    requirements = {
      "The output BigQuery table must exist.",
      "The Apache Kafka broker server must be running and be reachable from the Dataflow worker machines.",
      "The Apache Kafka topics must exist and the messages must be encoded in a valid JSON format."
    },
    streaming = true,
    supportsAtLeastOnce = true,
    supportsExactlyOnce = true,
    hidden = true)
public class KafkaToBigQuery {

  /* Logger for class. */
  private static final Logger LOG = LoggerFactory.getLogger(KafkaToBigQuery.class);

  /** The tag for the main output for the UDF. */
  private static final TupleTag<FailsafeElement<KV<String, String>, String>> UDF_OUT =
      new TupleTag<FailsafeElement<KV<String, String>, String>>() {};

  /** The tag for the main output of the json transformation. */
  static final TupleTag<TableRow> TRANSFORM_OUT = new TupleTag<TableRow>() {};

  /** The tag for the dead-letter output of the udf. */
  static final TupleTag<FailsafeElement<KV<String, String>, String>> UDF_DEADLETTER_OUT =
      new TupleTag<FailsafeElement<KV<String, String>, String>>() {};

  /** The tag for the dead-letter output of the json to table row transform. */
  static final TupleTag<FailsafeElement<KV<String, String>, String>> TRANSFORM_DEADLETTER_OUT =
      new TupleTag<FailsafeElement<KV<String, String>, String>>() {};

  /** The default suffix for error tables if dead letter table is not specified. */
  private static final String DEFAULT_DEADLETTER_TABLE_SUFFIX = "_error_records";

  /** String/String Coder for FailsafeElement. */
  private static final FailsafeElementCoder<String, String> FAILSAFE_ELEMENT_CODER =
      FailsafeElementCoder.of(
          NullableCoder.of(StringUtf8Coder.of()), NullableCoder.of(StringUtf8Coder.of()));

  /**
   * The {@link KafkaToBQOptions} class provides the custom execution options passed by the executor
   * at the command-line.
   */
  public interface KafkaToBQOptions
      extends JavascriptTextTransformerOptions,
          BigQueryCommonOptions.WriteOptions,
          BigQueryStorageApiStreamingOptions {

    @TemplateParameter.Text(
        order = 1,
        groupName = "Source",
        optional = true,
        regexes = {"[,:a-zA-Z0-9._-]+"},
        description = "Kafka Bootstrap Server list",
        helpText = "Kafka Bootstrap Server list, separated by commas.",
        example = "localhost:9092,127.0.0.1:9093")
    String getReadBootstrapServers();

    void setReadBootstrapServers(String bootstrapServers);

    @TemplateParameter.Text(
        order = 2,
        groupName = "Source",
        optional = true,
        regexes = {"[,a-zA-Z0-9._-]+"},
        description = "Kafka Topic(s) to read input from",
        helpText = "Kafka topic(s) to read input from.",
        example = "topic1,topic2")
    String getKafkaReadTopics();

    void setKafkaReadTopics(String value);

    /**
     * Get bootstrap server across releases.
     *
     * @deprecated This method is no longer acceptable to get bootstrap servers.
     *     <p>Use {@link KafkaToBQOptions#getReadBootstrapServers()} instead.
     */
    @TemplateParameter.Text(
        order = 2,
        groupName = "Source",
        optional = true,
        regexes = {"[,:a-zA-Z0-9._-]+"},
        description = "Kafka Bootstrap Server list",
        helpText =
            "The host address of the running Apache Kafka broker servers in a comma-separated list. Each host address must be in the format `35.70.252.199:9092`.",
        example = "localhost:9092,127.0.0.1:9093")
    @Deprecated
    String getBootstrapServers();

    /**
     * Get bootstrap server across releases.
     *
     * @deprecated This method is no longer acceptable to set bootstrap servers.
     *     <p>Use {@link KafkaToBQOptions#setReadBootstrapServers(String)} instead.
     */
    @Deprecated
    void setBootstrapServers(String bootstrapServers);

    /**
     * Get bootstrap server across releases.
     *
     * @deprecated This method is no longer acceptable to get Input topics.
     *     <p>Use {@link KafkaToBQOptions#getKafkaReadTopics()} instead.
     */
    @Deprecated
    @TemplateParameter.Text(
        order = 3,
        groupName = "Source",
        optional = true,
        regexes = {"[,a-zA-Z0-9._-]+"},
        description = "Kafka topic(s) to read the input from",
        helpText = "The Apache Kafka input topics to read from in a comma-separated list. ",
        example = "topic1,topic2")
    String getInputTopics();

    /**
     * Get bootstrap server across releases.
     *
     * @deprecated This method is no longer acceptable to set Input topics.
     *     <p>Use {@link KafkaToBQOptions#getKafkaReadTopics()} instead.
     */
    @Deprecated
    void setInputTopics(String inputTopics);

    @TemplateParameter.BigQueryTable(
        order = 4,
        optional = true,
        description = "The dead-letter table name to output failed messages to BigQuery",
        helpText =
            "BigQuery table for failed messages. Messages failed to reach the output table for different reasons "
                + "(e.g., mismatched schema, malformed json) are written to this table. If it doesn't exist, it will"
                + " be created during pipeline execution. If not specified, \"outputTableSpec_error_records\" is used instead.",
        example = "your-project-id:your-dataset.your-table-name")
    String getOutputDeadletterTable();

    void setOutputDeadletterTable(String outputDeadletterTable);

    @TemplateParameter.Enum(
        order = 5,
        enumOptions = {
          @TemplateParameter.TemplateEnumOption("AVRO"),
          @TemplateParameter.TemplateEnumOption("JSON")
        },
        optional = true,
        description = "The message format",
        helpText = "The message format. Can be AVRO or JSON.")
    @Default.String("JSON")
    String getMessageFormat();

    void setMessageFormat(String value);

    @TemplateParameter.GcsReadFile(
        order = 6,
        optional = true,
        description = "Cloud Storage path to the Avro schema file",
        helpText = "Cloud Storage path to Avro schema file. For example, gs://MyBucket/file.avsc.")
    String getAvroSchemaPath();

    void setAvroSchemaPath(String schemaPath);

    @TemplateParameter.Boolean(
        order = 7,
        optional = true,
        parentName = "useStorageWriteApi",
        parentTriggerValues = {"true"},
        description = "Use at at-least-once semantics in BigQuery Storage Write API",
        helpText =
            "This parameter takes effect only if \"Use BigQuery Storage Write API\" is enabled. If"
                + " enabled the at-least-once semantics will be used for Storage Write API, otherwise"
                + " exactly-once semantics will be used.",
        hiddenUi = true)
    @Default.Boolean(false)
    @Override
    Boolean getUseStorageWriteApiAtLeastOnce();

    void setUseStorageWriteApiAtLeastOnce(Boolean value);
  }

  /**
   * The main entry-point for pipeline execution. This method will start the pipeline but will not
   * wait for it's execution to finish. If blocking execution is required, use the {@link
   * KafkaToBigQuery#run(KafkaToBQOptions)} method to start the pipeline and invoke {@code
   * result.waitUntilFinish()} on the {@link PipelineResult}.
   *
   * @param args The command-line args passed by the executor.
   */
  public static void main(String[] args) {
    UncaughtExceptionLogger.register();

    KafkaToBQOptions options =
        PipelineOptionsFactory.fromArgs(args).withValidation().as(KafkaToBQOptions.class);

    run(options);
  }

  /**
   * Runs the pipeline to completion with the specified options. This method does not wait until the
   * pipeline is finished before returning. Invoke {@code result.waitUntilFinish()} on the result
   * object to block until the pipeline is finished running if blocking programmatic execution is
   * required.
   *
   * @param options The execution options.
   * @return The pipeline result.
   */
  public static PipelineResult run(KafkaToBQOptions options) {

    // Validate BQ STORAGE_WRITE_API options
    BigQueryIOUtils.validateBQStorageApiOptionsStreaming(options);
    MetadataValidator.validate(options);

    // Create the pipeline
    Pipeline pipeline = Pipeline.create(options);

    // Register the coder for pipeline
    FailsafeElementCoder<KV<String, String>, String> coder =
        FailsafeElementCoder.of(
            KvCoder.of(
                NullableCoder.of(StringUtf8Coder.of()), NullableCoder.of(StringUtf8Coder.of())),
            NullableCoder.of(StringUtf8Coder.of()));

    CoderRegistry coderRegistry = pipeline.getCoderRegistry();
    coderRegistry.registerCoderForType(coder.getEncodedTypeDescriptor(), coder);

    List<String> topicsList;
    if (options.getKafkaReadTopics() != null) {
      topicsList = new ArrayList<>(Arrays.asList(options.getKafkaReadTopics().split(",")));
    } else if (options.getInputTopics() != null) {
      topicsList = new ArrayList<>(Arrays.asList(options.getInputTopics().split(",")));
    } else {
      throw new IllegalArgumentException("Please Provide --kafkaReadTopic");
    }
    String bootstrapServers;
    if (options.getReadBootstrapServers() != null) {
      bootstrapServers = options.getReadBootstrapServers();
    } else if (options.getBootstrapServers() != null) {
      bootstrapServers = options.getBootstrapServers();
    } else {
      throw new IllegalArgumentException("Please Provide --bootstrapServers");
    }
    /*
     * Steps:
     *  1) Read messages in from Kafka
     *  2) Transform the messages into TableRows
     *     - Transform message payload via UDF
     *     - Convert UDF result to TableRow objects
     *  3) Write successful records out to BigQuery
     *  4) Write failed records out to BigQuery
     */

    Map<String, Object> kafkaConfig =
        ImmutableMap.of(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

    PCollectionTuple convertedTableRows;
    if (options.getMessageFormat() == null || options.getMessageFormat().equals("JSON")) {

      return runJsonPipeline(pipeline, options, topicsList, bootstrapServers, kafkaConfig);

    } else if (options.getMessageFormat().equals("AVRO")) {

      return runAvroPipeline(pipeline, options, topicsList, bootstrapServers, kafkaConfig);

    } else {
      throw new IllegalArgumentException("Invalid format specified: " + options.getMessageFormat());
    }
  }

  public static PipelineResult runJsonPipeline(
      Pipeline pipeline,
      KafkaToBQOptions options,
      List<String> topicsList,
      String bootstrapServers,
      Map<String, Object> kafkaConfig) {

    PCollectionTuple convertedTableRows;
    convertedTableRows =
        pipeline
            /*
             * Step #1: Read messages in from Kafka
             */
            .apply(
                "ReadFromKafka",
                KafkaTransform.readStringFromKafka(
                        bootstrapServers, topicsList, kafkaConfig, null, false)
                    .withoutMetadata())

            /*
             * Step #2: Transform the Kafka Messages into TableRows
             */
            .apply("ConvertMessageToTableRow", new StringMessageToTableRow(options));
    /*
     * Step #3: Write the successful records out to BigQuery
     */
    WriteResult writeResult =
        convertedTableRows
            .get(TRANSFORM_OUT)
            .apply(
                "WriteSuccessfulRecords",
                BigQueryIO.writeTableRows()
                    .withoutValidation()
                    .withCreateDisposition(CreateDisposition.CREATE_NEVER)
                    .withWriteDisposition(WriteDisposition.WRITE_APPEND)
                    .withExtendedErrorInfo()
                    .withFailedInsertRetryPolicy(InsertRetryPolicy.retryTransientErrors())
                    .to(options.getOutputTableSpec()));

    /*
     * Step 3 Contd.
     * Elements that failed inserts into BigQuery are extracted and converted to FailsafeElement
     */
    PCollection<FailsafeElement<String, String>> failedInserts =
        BigQueryIOUtils.writeResultToBigQueryInsertErrors(writeResult, options)
            .apply(
                "WrapInsertionErrors",
                MapElements.into(FAILSAFE_ELEMENT_CODER.getEncodedTypeDescriptor())
                    .via(KafkaToBigQuery::wrapBigQueryInsertError))
            .setCoder(FAILSAFE_ELEMENT_CODER);

    /*
     * Step #4: Write failed records out to BigQuery
     */
    PCollectionList.of(convertedTableRows.get(UDF_DEADLETTER_OUT))
        .and(convertedTableRows.get(TRANSFORM_DEADLETTER_OUT))
        .apply("Flatten", Flatten.pCollections())
        .apply(
            "WriteTransformationFailedRecords",
            WriteStringKVMessageErrors.newBuilder()
                .setErrorRecordsTable(
                    ObjectUtils.firstNonNull(
                        options.getOutputDeadletterTable(),
                        options.getOutputTableSpec() + DEFAULT_DEADLETTER_TABLE_SUFFIX))
                .setErrorRecordsTableSchema(SchemaUtils.DEADLETTER_SCHEMA)
                .build());

    /*
     * Step #5: Insert records that failed BigQuery inserts into a deadletter table.
     */
    failedInserts.apply(
        "WriteInsertionFailedRecords",
        ErrorConverters.WriteStringMessageErrors.newBuilder()
            .setErrorRecordsTable(
                ObjectUtils.firstNonNull(
                    options.getOutputDeadletterTable(),
                    options.getOutputTableSpec() + DEFAULT_DEADLETTER_TABLE_SUFFIX))
            .setErrorRecordsTableSchema(SchemaUtils.DEADLETTER_SCHEMA)
            .build());
    return pipeline.run();
  }

  public static PipelineResult runAvroPipeline(
      Pipeline pipeline,
      KafkaToBQOptions options,
      List<String> topicsList,
      String bootstrapServers,
      Map<String, Object> kafkaConfig) {

    String avroSchema = options.getAvroSchemaPath();
    Schema schema = SchemaUtils.getAvroSchema(avroSchema);
    PCollectionTuple convertedTableRows;
    PCollection<KV<byte[], GenericRecord>> genericRecords;

    genericRecords =
        pipeline
            /*
             * Step #1: Read messages in from Kafka
             */
            .apply(
                "ReadFromKafka",
                KafkaTransform.readAvroFromKafka(
                    bootstrapServers, topicsList, kafkaConfig, avroSchema, null))
            .setCoder(
                KvCoder.of(
                    ByteArrayCoder.of(),
                    SchemaCoder.of(
                        AvroUtils.toBeamSchema(schema),
                        TypeDescriptor.of(GenericRecord.class),
                        AvroUtils.getToRowFunction(GenericRecord.class, schema),
                        AvroUtils.getFromRowFunction(GenericRecord.class))));

    /*
     * Step #2: Transform the Kafka Messages into TableRows
     */

    WriteResult writeResult;
    // if it has UDF, convert from GenericAvro to JSON and apply UDF
    if (StringUtils.isNotEmpty(options.getJavascriptTextTransformGcsPath())
        && StringUtils.isNotEmpty(options.getJavascriptTextTransformFunctionName())) {
      convertedTableRows =
          new StringMessageToTableRow(options)
              .expand(
                  genericRecords.apply(
                      "ConvertToJson",
                      MapElements.into(
                              TypeDescriptors.kvs(
                                  TypeDescriptors.strings(), TypeDescriptors.strings()))
                          .via(
                              kv ->
                                  KV.of(
                                      new String(kv.getKey(), StandardCharsets.UTF_8),
                                      Objects.requireNonNull(kv.getValue()).toString()))));
      writeResult =
          convertedTableRows
              .get(TRANSFORM_OUT)
              .apply(
                  "WriteSuccessfulRecords",
                  BigQueryIO.writeTableRows()
                      .withoutValidation()
                      .withCreateDisposition(CreateDisposition.CREATE_NEVER)
                      .withWriteDisposition(WriteDisposition.WRITE_APPEND)
                      .withExtendedErrorInfo()
                      .withFailedInsertRetryPolicy(InsertRetryPolicy.retryTransientErrors())
                      .to(options.getOutputTableSpec()));

      /*
       * Step 3 Contd.
       * Elements that failed inserts into BigQuery are extracted and converted to FailsafeElement
       */
      PCollection<FailsafeElement<String, String>> failedInserts =
          BigQueryIOUtils.writeResultToBigQueryInsertErrors(writeResult, options)
              .apply(
                  "WrapInsertionErrors",
                  MapElements.into(FAILSAFE_ELEMENT_CODER.getEncodedTypeDescriptor())
                      .via(KafkaToBigQuery::wrapBigQueryInsertError))
              .setCoder(FAILSAFE_ELEMENT_CODER);

      /*
       * Step #4: Write failed records out to BigQuery
       */
      PCollectionList.of(convertedTableRows.get(UDF_DEADLETTER_OUT))
          .and(convertedTableRows.get(TRANSFORM_DEADLETTER_OUT))
          .apply("Flatten", Flatten.pCollections())
          .apply(
              "WriteTransformationFailedRecords",
              WriteStringKVMessageErrors.newBuilder()
                  .setErrorRecordsTable(
                      ObjectUtils.firstNonNull(
                          options.getOutputDeadletterTable(),
                          options.getOutputTableSpec() + DEFAULT_DEADLETTER_TABLE_SUFFIX))
                  .setErrorRecordsTableSchema(SchemaUtils.DEADLETTER_SCHEMA)
                  .build());

      /*
       * Step #5: Insert records that failed BigQuery inserts into a deadletter table.
       */
      failedInserts.apply(
          "WriteInsertionFailedRecords",
          ErrorConverters.WriteStringMessageErrors.newBuilder()
              .setErrorRecordsTable(
                  ObjectUtils.firstNonNull(
                      options.getOutputDeadletterTable(),
                      options.getOutputTableSpec() + DEFAULT_DEADLETTER_TABLE_SUFFIX))
              .setErrorRecordsTableSchema(SchemaUtils.DEADLETTER_SCHEMA)
              .build());
    } else {
      writeResult =
          genericRecords
              .apply(Values.create())
              .apply(Convert.toRows())
              .apply(BigQueryConverters.<Row>createWriteTransform(options).useBeamSchema());

      /*
       * Step 3 Contd.
       * Elements that failed inserts into BigQuery are extracted and converted to FailsafeElement
       */
      PCollection<FailsafeElement<String, String>> failedInserts =
          BigQueryIOUtils.writeResultToBigQueryInsertErrors(writeResult, options)
              .apply(
                  "WrapInsertionErrors",
                  MapElements.into(FAILSAFE_ELEMENT_CODER.getEncodedTypeDescriptor())
                      .via(KafkaToBigQuery::wrapBigQueryInsertError))
              .setCoder(FAILSAFE_ELEMENT_CODER);

      /*
       * Step #5: Insert records that failed BigQuery inserts into a deadletter table.
       */
      failedInserts.apply(
          "WriteInsertionFailedRecords",
          ErrorConverters.WriteStringMessageErrors.newBuilder()
              .setErrorRecordsTable(
                  ObjectUtils.firstNonNull(
                      options.getOutputDeadletterTable(),
                      options.getOutputTableSpec() + DEFAULT_DEADLETTER_TABLE_SUFFIX))
              .setErrorRecordsTableSchema(SchemaUtils.DEADLETTER_SCHEMA)
              .build());
    }

    return pipeline.run();
  }

  /**
   * The {@link StringMessageToTableRow} class is a {@link PTransform} which transforms incoming
   * Kafka Message objects into {@link TableRow} objects for insertion into BigQuery while applying
   * a UDF to the input. The executions of the UDF and transformation to {@link TableRow} objects is
   * done in a fail-safe way by wrapping the element with it's original payload inside the {@link
   * FailsafeElement} class. The {@link StringMessageToTableRow} transform will output a {@link
   * PCollectionTuple} which contains all output and dead-letter {@link PCollection}.
   *
   * <p>The {@link PCollectionTuple} output will contain the following {@link PCollection}:
   *
   * <ul>
   *   <li>{@link KafkaToBigQuery#UDF_OUT} - Contains all {@link FailsafeElement} records
   *       successfully processed by the UDF.
   *   <li>{@link KafkaToBigQuery#UDF_DEADLETTER_OUT} - Contains all {@link FailsafeElement} records
   *       which failed processing during the UDF execution.
   *   <li>{@link KafkaToBigQuery#TRANSFORM_OUT} - Contains all records successfully converted from
   *       JSON to {@link TableRow} objects.
   *   <li>{@link KafkaToBigQuery#TRANSFORM_DEADLETTER_OUT} - Contains all {@link FailsafeElement}
   *       records which couldn't be converted to table rows.
   * </ul>
   */
  static class StringMessageToTableRow
      extends PTransform<PCollection<KV<String, String>>, PCollectionTuple> {

    private final KafkaToBQOptions options;

    StringMessageToTableRow(KafkaToBQOptions options) {
      this.options = options;
    }

    @Override
    public PCollectionTuple expand(PCollection<KV<String, String>> input) {

      PCollectionTuple udfOut =
          input
              // Map the incoming messages into FailsafeElements so we can recover from failures
              // across multiple transforms.
              .apply("MapToRecord", ParDo.of(new StringMessageToFailsafeElementFn()))
              .apply(
                  "InvokeUDF",
                  FailsafeJavascriptUdf.<KV<String, String>>newBuilder()
                      .setFileSystemPath(options.getJavascriptTextTransformGcsPath())
                      .setFunctionName(options.getJavascriptTextTransformFunctionName())
                      .setReloadIntervalMinutes(
                          options.getJavascriptTextTransformReloadIntervalMinutes())
                      .setSuccessTag(UDF_OUT)
                      .setFailureTag(UDF_DEADLETTER_OUT)
                      .build());

      // Convert the records which were successfully processed by the UDF into TableRow objects.
      PCollectionTuple jsonToTableRowOut =
          udfOut
              .get(UDF_OUT)
              .apply(
                  "JsonToTableRow",
                  FailsafeJsonToTableRow.<KV<String, String>>newBuilder()
                      .setSuccessTag(TRANSFORM_OUT)
                      .setFailureTag(TRANSFORM_DEADLETTER_OUT)
                      .build());

      // Re-wrap the PCollections so we can return a single PCollectionTuple
      return PCollectionTuple.of(UDF_OUT, udfOut.get(UDF_OUT))
          .and(UDF_DEADLETTER_OUT, udfOut.get(UDF_DEADLETTER_OUT))
          .and(TRANSFORM_OUT, jsonToTableRowOut.get(TRANSFORM_OUT))
          .and(TRANSFORM_DEADLETTER_OUT, jsonToTableRowOut.get(TRANSFORM_DEADLETTER_OUT));
    }
  }

  /**
   * The {@link StringMessageToFailsafeElementFn} wraps an Kafka Message with the {@link
   * FailsafeElement} class so errors can be recovered from and the original message can be output
   * to an error records table.
   */
  static class StringMessageToFailsafeElementFn
      extends DoFn<KV<String, String>, FailsafeElement<KV<String, String>, String>> {

    @ProcessElement
    public void processElement(ProcessContext context) {
      KV<String, String> message = context.element();
      context.output(FailsafeElement.of(message, message.getValue()));
    }
  }

  /**
   * The {@link AvroMessageToFailsafeElementFn} wraps a Kafka Message with the {@link
   * FailsafeElement} class so errors can be recovered from and the original message can be output
   * to an error records table.
   */
  static class AvroMessageToFailsafeElementFn
      extends DoFn<
          KV<byte[], GenericRecord>, FailsafeElement<KV<byte[], GenericRecord>, GenericRecord>> {

    @ProcessElement
    public void processElement(ProcessContext context) {
      KV<byte[], GenericRecord> message = context.element();
      context.output(FailsafeElement.of(message, message.getValue()));
    }
  }

  /**
   * Method to wrap a {@link BigQueryInsertError} into a {@link FailsafeElement}.
   *
   * @param insertError BigQueryInsert error.
   * @return FailsafeElement object.
   */
  protected static FailsafeElement<String, String> wrapBigQueryInsertError(
      BigQueryInsertError insertError) {

    FailsafeElement<String, String> failsafeElement;
    try {

      failsafeElement =
          FailsafeElement.of(
              insertError.getRow().toPrettyString(), insertError.getRow().toPrettyString());
      failsafeElement.setErrorMessage(insertError.getError().toPrettyString());

    } catch (IOException e) {
      LOG.error("Failed to wrap BigQuery insert error.");
      throw new RuntimeException(e);
    }
    return failsafeElement;
  }
}
