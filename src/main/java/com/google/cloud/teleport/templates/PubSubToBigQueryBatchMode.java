package com.infusionsoft.dataflow.templates;

import static com.google.cloud.teleport.templates.TextToBigQueryStreaming.wrapBigQueryInsertError;

import com.google.api.services.bigquery.model.TableRow;
import com.google.cloud.teleport.coders.FailsafeElementCoder;
import com.google.cloud.teleport.templates.common.BigQueryConverters.FailsafeJsonToTableRow;
import com.google.cloud.teleport.templates.common.ErrorConverters;
import com.google.cloud.teleport.templates.common.JavascriptTextTransformer.FailsafeJavascriptUdf;
import com.google.cloud.teleport.templates.common.JavascriptTextTransformer.JavascriptTextTransformerOptions;
import com.google.cloud.teleport.util.DualInputNestedValueProvider;
import com.google.cloud.teleport.util.DualInputNestedValueProvider.TranslatorInput;
import com.google.cloud.teleport.util.ResourceUtils;
import com.google.cloud.teleport.util.ValueProviderUtils;
import com.google.cloud.teleport.values.FailsafeElement;
import com.google.common.collect.ImmutableList;
import java.nio.charset.StandardCharsets;
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.coders.CoderRegistry;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.CreateDisposition;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.WriteDisposition;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryInsertError;
import org.apache.beam.sdk.io.gcp.bigquery.InsertRetryPolicy;
import org.apache.beam.sdk.io.gcp.bigquery.WriteResult;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessageWithAttributesCoder;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.options.ValueProvider.StaticValueProvider;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Flatten;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionList;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PubSubToBigQueryBatchMode {
    /** The log to output status messages to. */
    private static final Logger LOG = LoggerFactory.getLogger(PubSubToBigQueryBatchMode.class);

    /** The tag for the main output for the UDF. */
    public static final TupleTag<FailsafeElement<PubsubMessage, String>> UDF_OUT =
        new TupleTag<FailsafeElement<PubsubMessage, String>>() {};

    /** The tag for the main output of the json transformation. */
    public static final TupleTag<TableRow> TRANSFORM_OUT = new TupleTag<TableRow>() {};

    /** The tag for the dead-letter output of the udf. */
    public static final TupleTag<FailsafeElement<PubsubMessage, String>> UDF_DEADLETTER_OUT =
        new TupleTag<FailsafeElement<PubsubMessage, String>>() {};

    /** The tag for the dead-letter output of the json to table row transform. */
    public static final TupleTag<FailsafeElement<PubsubMessage, String>> TRANSFORM_DEADLETTER_OUT =
        new TupleTag<FailsafeElement<PubsubMessage, String>>() {};

    /** The default suffix for error tables if dead letter table is not specified. */
    public static final String DEFAULT_DEADLETTER_TABLE_SUFFIX = "_error_records";

    /** Pubsub message/string coder for pipeline. */
    public static final FailsafeElementCoder<PubsubMessage, String> CODER =
        FailsafeElementCoder.of(PubsubMessageWithAttributesCoder.of(), StringUtf8Coder.of());

    /** String/String Coder for FailsafeElement. */
    public static final FailsafeElementCoder<String, String> FAILSAFE_ELEMENT_CODER =
        FailsafeElementCoder.of(StringUtf8Coder.of(), StringUtf8Coder.of());

    /**
     * The {@link PubSubToBigQueryBatchMode.Options} class provides the custom execution options passed by the executor at the
     * command-line.
     */
    public interface Options extends PipelineOptions, JavascriptTextTransformerOptions ,
        DataflowPipelineOptions {
        @Description("Table spec to write the output to")
        ValueProvider<String> getOutputTableSpec();

        void setOutputTableSpec(ValueProvider<String> value);

        @Description("Pub/Sub topic to read the input from")
        ValueProvider<String> getInputTopic();

        void setInputTopic(ValueProvider<String> value);

        @Description(
            "The Cloud Pub/Sub subscription to consume from. "
                + "The name should be in the format of "
                + "projects/<project-id>/subscriptions/<subscription-name>.")
        ValueProvider<String> getInputSubscription();

        void setInputSubscription(ValueProvider<String> value);

        @Description(
            "This determines whether the template reads from " + "a pub/sub subscription or a topic")
        @Default.Boolean(false)
        Boolean getUseSubscription();

        void setUseSubscription(Boolean value);

        @Description(
            "The dead-letter table to output to within BigQuery in <project-id>:<dataset>.<table> "
                + "format. If it doesn't exist, it will be created during pipeline execution.")
        ValueProvider<String> getOutputDeadletterTable();

        void setOutputDeadletterTable(ValueProvider<String> value);
        void setMaxNumWorkers(int value);

        void setWorkerMachineType(String value);

        void setNumWorkers(int value);
        void setStreaming(boolean value);
    }

    /**
     * The main entry-point for pipeline execution. This method will start the pipeline but will not
     * wait for it's execution to finish. If blocking execution is required, use the {@link
     * PubSubToBigQueryBatchMode#run(PubSubToBigQueryBatchMode.Options)} method to start the pipeline and invoke {@code
     * result.waitUntilFinish()} on the {@link PipelineResult}.
     *
     * @param args The command-line args passed by the executor.
     */
    public static void main(String[] args) {
        PubSubToBigQueryBatchMode.Options options = PipelineOptionsFactory
            .fromArgs(args).withValidation().as(PubSubToBigQueryBatchMode.Options.class);

        options.setStreaming(false);
        options.setNumWorkers(1);
        options.setMaxNumWorkers(1);
        options.setWorkerMachineType("n1-standard-1");

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
    public static PipelineResult run(PubSubToBigQueryBatchMode.Options options) {

        Pipeline pipeline = Pipeline.create(options);

        CoderRegistry coderRegistry = pipeline.getCoderRegistry();
        coderRegistry.registerCoderForType(CODER.getEncodedTypeDescriptor(), CODER);

        /*
         * Steps:
         *  1) Read messages in from Pub/Sub
         *  2) Transform the PubsubMessages into TableRows
         *     - Transform message payload via UDF
         *     - Convert UDF result to TableRow objects
         *  3) Write successful records out to BigQuery
         *  4) Write failed records out to BigQuery
         */

        /*
         * Step #1: Read messages in from Pub/Sub
         * Either from a Subscription or Topic
         */

        PCollection<PubsubMessage> messages = null;
        if (options.getUseSubscription()) {
            messages =
                pipeline.apply(
                    "ReadPubSubSubscription",
                    PubsubIO.readMessagesWithAttributes()
                        .fromSubscription(options.getInputSubscription()));
        } else {
            messages =
                pipeline.apply(
                    "ReadPubSubTopic",
                    PubsubIO.readMessagesWithAttributes().fromTopic(options.getInputTopic()));
        }

        PCollectionTuple convertedTableRows =
            messages
                /*
                 * Step #2: Transform the PubsubMessages into TableRows
                 */
                .apply("ConvertMessageToTableRow", new PubSubToBigQueryBatchMode.PubsubMessageToTableRow(options));

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
                        .withMethod(BigQueryIO.Write.Method.STREAMING_INSERTS)
                        .withFailedInsertRetryPolicy(InsertRetryPolicy.retryTransientErrors())
                        .to(options.getOutputTableSpec()));

        /*
         * Step 3 Contd.
         * Elements that failed inserts into BigQuery are extracted and converted to FailsafeElement
         */
        PCollection<FailsafeElement<String, String>> failedInserts =
            writeResult
                .getFailedInsertsWithErr()
                .apply(
                    "WrapInsertionErrors",
                    MapElements.into(FAILSAFE_ELEMENT_CODER.getEncodedTypeDescriptor())
                        .via((BigQueryInsertError e) -> wrapBigQueryInsertError(e)))
                .setCoder(FAILSAFE_ELEMENT_CODER);

        /*
         * Step #4: Write records that failed table row transformation
         * or conversion out to BigQuery deadletter table.
         */
        PCollectionList.of(
            ImmutableList.of(
                convertedTableRows.get(UDF_DEADLETTER_OUT),
                convertedTableRows.get(TRANSFORM_DEADLETTER_OUT)))
            .apply("Flatten", Flatten.pCollections())
            .apply(
                "WriteFailedRecords",
                ErrorConverters.WritePubsubMessageErrors.newBuilder()
                    .setErrorRecordsTable(
                        ValueProviderUtils.maybeUseDefaultDeadletterTable(
                            options.getOutputDeadletterTable(),
                            options.getOutputTableSpec(),
                            DEFAULT_DEADLETTER_TABLE_SUFFIX))
                    .setErrorRecordsTableSchema(ResourceUtils.getDeadletterTableSchemaJson())
                    .build());

        // 5) Insert records that failed insert into deadletter table
        failedInserts.apply(
            "WriteFailedRecords",
            ErrorConverters.WriteStringMessageErrors.newBuilder()
                .setErrorRecordsTable(
                    ValueProviderUtils.maybeUseDefaultDeadletterTable(
                        options.getOutputDeadletterTable(),
                        options.getOutputTableSpec(),
                        DEFAULT_DEADLETTER_TABLE_SUFFIX))
                .setErrorRecordsTableSchema(ResourceUtils.getDeadletterTableSchemaJson())
                .build());

        return pipeline.run();
    }

    /**
     * If deadletterTable is available, it is returned as is, otherwise outputTableSpec +
     * defaultDeadLetterTableSuffix is returned instead.
     */
    private static ValueProvider<String> maybeUseDefaultDeadletterTable(
        ValueProvider<String> deadletterTable,
        ValueProvider<String> outputTableSpec,
        String defaultDeadLetterTableSuffix) {
        return DualInputNestedValueProvider.of(
            deadletterTable,
            outputTableSpec,
            new SerializableFunction<TranslatorInput<String, String>, String>() {
                @Override
                public String apply(TranslatorInput<String, String> input) {
                    String userProvidedTable = input.getX();
                    String outputTableSpec = input.getY();
                    if (userProvidedTable == null) {
                        return outputTableSpec + defaultDeadLetterTableSuffix;
                    }
                    return userProvidedTable;
                }
            });
    }

    /**
     * The {@link PubSubToBigQueryBatchMode.PubsubMessageToTableRow} class is a {@link PTransform} which transforms incoming
     * {@link PubsubMessage} objects into {@link TableRow} objects for insertion into BigQuery while
     * applying an optional UDF to the input. The executions of the UDF and transformation to {@link
     * TableRow} objects is done in a fail-safe way by wrapping the element with it's original payload
     * inside the {@link FailsafeElement} class. The {@link PubSubToBigQueryBatchMode.PubsubMessageToTableRow} transform will
     * output a {@link PCollectionTuple} which contains all output and dead-letter {@link
     * PCollection}.
     *
     * <p>The {@link PCollectionTuple} output will contain the following {@link PCollection}:
     *
     * <ul>
     *   <li>{@link PubSubToBigQueryBatchMode#UDF_OUT} - Contains all {@link FailsafeElement} records
     *       successfully processed by the optional UDF.
     *   <li>{@link PubSubToBigQueryBatchMode#UDF_DEADLETTER_OUT} - Contains all {@link FailsafeElement}
     *       records which failed processing during the UDF execution.
     *   <li>{@link PubSubToBigQueryBatchMode#TRANSFORM_OUT} - Contains all records successfully converted from
     *       JSON to {@link TableRow} objects.
     *   <li>{@link PubSubToBigQueryBatchMode#TRANSFORM_DEADLETTER_OUT} - Contains all {@link FailsafeElement}
     *       records which couldn't be converted to table rows.
     * </ul>
     */
    static class PubsubMessageToTableRow
        extends PTransform<PCollection<PubsubMessage>, PCollectionTuple> {

        private final PubSubToBigQueryBatchMode.Options options;

        PubsubMessageToTableRow(PubSubToBigQueryBatchMode.Options options) {
            this.options = options;
        }

        @Override
        public PCollectionTuple expand(PCollection<PubsubMessage> input) {

            PCollectionTuple udfOut =
                input
                    // Map the incoming messages into FailsafeElements so we can recover from failures
                    // across multiple transforms.
                    .apply("MapToRecord", ParDo.of(new PubSubToBigQueryBatchMode.PubsubMessageToFailsafeElementFn()))
                    .apply(
                        "InvokeUDF",
                        FailsafeJavascriptUdf.<PubsubMessage>newBuilder()
                            .setFileSystemPath(options.getJavascriptTextTransformGcsPath())
                            .setFunctionName(options.getJavascriptTextTransformFunctionName())
                            .setSuccessTag(UDF_OUT)
                            .setFailureTag(UDF_DEADLETTER_OUT)
                            .build());

            // Convert the records which were successfully processed by the UDF into TableRow objects.
            PCollectionTuple jsonToTableRowOut =
                udfOut
                    .get(UDF_OUT)
                    .apply(
                        "JsonToTableRow",
                        FailsafeJsonToTableRow.<PubsubMessage>newBuilder()
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
     * The {@link PubSubToBigQueryBatchMode.PubsubMessageToFailsafeElementFn} wraps an incoming {@link PubsubMessage} with the
     * {@link FailsafeElement} class so errors can be recovered from and the original message can be
     * output to a error records table.
     */
    static class PubsubMessageToFailsafeElementFn
        extends DoFn<PubsubMessage, FailsafeElement<PubsubMessage, String>> {
        @ProcessElement
        public void processElement(ProcessContext context) {
            PubsubMessage message = context.element();
            context.output(
                FailsafeElement.of(message, new String(message.getPayload(), StandardCharsets.UTF_8)));
        }
    }

}
