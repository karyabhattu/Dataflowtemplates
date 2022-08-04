/*
 * Copyright (C) 2022 Google LLC
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
package com.infusionsoft.dataflow.templates;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.teleport.templates.common.PubsubConverters.PubsubReadOptions;
import com.google.datastore.v1.CommitRequest;
import com.google.datastore.v1.Entity;
import com.google.datastore.v1.EntityResult;
import com.google.datastore.v1.GqlQuery;
import com.google.datastore.v1.QueryResultBatch;
import com.google.datastore.v1.RunQueryRequest;
import com.google.datastore.v1.RunQueryResponse;
import com.google.datastore.v1.Value;
import com.google.datastore.v1.client.Datastore;
import com.google.datastore.v1.client.DatastoreException;
import com.google.datastore.v1.client.DatastoreHelper;
import com.infusionsoft.dataflow.utils.DatastoreUtils;
import com.infusionsoft.dataflow.utils.JavaTimeUtils;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.StreamingOptions;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Reshuffle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A template that listens to pubsub and marks emails as bounced, as appropriate.
 *
 * <p>Used by email-history-api
 *
 * <p>Deploy to sand: mvn compile exec:java
 * -Dexec.mainClass=com.infusionsoft.dataflow.templates.PubsubMarkEmailsBounced
 * -Dexec.args="--project=is-email-history-api-sand
 * --stagingLocation=gs://dataflow-is-email-history-api-sand/staging
 * --templateLocation=gs://dataflow-is-email-history-api-sand/templates/ps_mark_email_bounced
 * --runner=DataflowRunner --serviceAccount=is-email-history-api-sand@appspot.gserviceaccount.com
 * --datastoreProjectId=is-email-history-api-sand"
 *
 * <p>projects/is-flagship-events-sand/topics/v1.bounce-event
 *
 * <p>Deploy to prod: mvn compile exec:java
 * -Dexec.mainClass=com.infusionsoft.dataflow.templates.PubsubMarkEmailsBounced
 * -Dexec.args="--project=is-email-history-api-prod
 * --stagingLocation=gs://dataflow-is-email-history-api-prod/staging
 * --templateLocation=gs://dataflow-is-email-history-api-prod/templates/ps_mark_email_bounced
 * --runner=DataflowRunner --serviceAccount=is-email-history-api-prod@appspot.gserviceaccount.com
 * --datastoreProjectId=is-email-history-api-prod"
 *
 * <p>projects/is-flagship-events-prod/topics/v1.bounce-event
 */
public class PubsubMarkEmailsBounced {

  /**
   * Options supported by {@link PubsubMarkEmailsBounced}.
   *
   * <p>Inherits standard configuration options.
   */
  public interface Options extends PipelineOptions, StreamingOptions, PubsubReadOptions {

    @Description("GCP Project Id of where the datastore entities live")
    ValueProvider<String> getDatastoreProjectId();

    void setDatastoreProjectId(ValueProvider<String> datastoreProjectId);
  }

  public static class ExtractAndHandleEventsFn extends DoFn<String, String> {

    private static final Logger LOG = LoggerFactory.getLogger(ExtractAndHandleEventsFn.class);

    private static final ObjectMapper OBJECT_MAPPER =
        new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private String projectId;

    @StartBundle
    public void startBundle(StartBundleContext context) {
      if (this.projectId != null) {
        return; // has been evaluated already
      }

      final Options options = context.getPipelineOptions().as(Options.class);
      projectId =
          (options.getDatastoreProjectId() == null ? null : options.getDatastoreProjectId().get());
      LOG.info("Enabling event filter [projectId: {}]", projectId);
    }

    @ProcessElement
    public void processElement(ProcessContext context) throws IOException {
      final String message = context.element();
      LOG.debug("processing... {}", message);

      final Map<String, Object> json = OBJECT_MAPPER.readValue(message, Map.class);

      processEvent(context, json);
    }

    private void processEvent(ProcessContext context, Map<String, Object> json) {
      final String accountId = (String) json.get("accountId");
      final String bounceId = (String) json.get("bounceId");
      final ZonedDateTime timestamp =
          JavaTimeUtils.parseFlagshipDate((String) json.get("timestamp"));

      final Datastore datastore =
          DatastoreUtils.getDatastore(context.getPipelineOptions(), projectId);

      findEmails(datastore, accountId, bounceId).stream()
          .forEach(entity -> markBounced(datastore, entity, timestamp));
    }

    private void markBounced(Datastore datastore, Entity entity, ZonedDateTime timestamp) {
      final Entity updated =
          entity.toBuilder()
              .putProperties(
                  "bounced",
                  Value.newBuilder()
                      .setTimestampValue(JavaTimeUtils.toTimestamp(timestamp))
                      .build())
              .build();

      final CommitRequest request =
          CommitRequest.newBuilder()
              .addMutations(DatastoreHelper.makeUpdate(updated))
              .setMode(CommitRequest.Mode.NON_TRANSACTIONAL)
              .build();

      try {
        datastore.commit(request);
      } catch (DatastoreException e) {
        LOG.error("Unable to mark bounced: " + entity, e);
      }
    }

    private List<Entity> findEmails(Datastore datastore, String accountId, String bounceId) {

      final List<Entity> entities = new LinkedList<>();

      final StringBuilder gql =
          new StringBuilder("SELECT * FROM Email")
              .append(" WHERE ")
              .append(String.format("accountId = '%s'", accountId))
              .append(" AND ")
              .append(String.format("bounceId = '%s'", bounceId));

      LOG.debug(gql.toString());

      final RunQueryRequest request =
          RunQueryRequest.newBuilder()
              .setGqlQuery(
                  GqlQuery.newBuilder()
                      .setQueryString(gql.toString())
                      .setAllowLiterals(true)
                      .build())
              .build();

      try {
        final RunQueryResponse response = datastore.runQuery(request);
        final QueryResultBatch batch = response.getBatch();

        batch.getEntityResultsList().stream().map(EntityResult::getEntity).forEach(entities::add);

      } catch (DatastoreException e) {
        LOG.error("Couldn't find emails", e);
      }

      return entities;
    }
  }

  /**
   * Main entry point for executing the pipeline.
   *
   * @param args The command-line arguments to the pipeline.
   */
  public static void main(String[] args) {

    // Parse the user options passed from the command-line
    Options options = PipelineOptionsFactory.fromArgs(args).withValidation().as(Options.class);

    options.setStreaming(true);

    run(options);
  }

  /**
   * Runs the pipeline with the supplied options.
   *
   * @param options The execution parameters to the pipeline.
   * @return The result of the pipeline execution.
   */
  public static PipelineResult run(Options options) {
    // Create the pipeline
    Pipeline pipeline = Pipeline.create(options);

    pipeline
        .apply("Read Events", PubsubIO.readStrings().fromTopic(options.getPubsubReadTopic()))
        .apply(
            "Shard Events",
            Reshuffle.viaRandomKey()) // this ensures that we handle the events in parallel
        .apply("Handle Events", ParDo.of(new ExtractAndHandleEventsFn()));

    // Execute the pipeline and return the result.
    return pipeline.run();
  }
}
