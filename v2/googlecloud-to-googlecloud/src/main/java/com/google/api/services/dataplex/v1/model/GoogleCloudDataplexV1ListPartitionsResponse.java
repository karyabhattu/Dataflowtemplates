/*
 * Copyright (C) 2021 Google LLC
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
package com.google.api.services.dataplex.v1.model;

/**
 * List metadata partitions response.
 *
 * <p>This is the Java data model class that specifies how to parse/serialize into the JSON that is
 * transmitted over HTTP when working with the Cloud Dataplex API. For a detailed explanation see:
 * <a
 * href="https://developers.google.com/api-client-library/java/google-http-java-client/json">https://developers.google.com/api-client-library/java/google-http-java-client/json</a>
 *
 * @author Google, Inc.
 */
@SuppressWarnings("javadoc")
public final class GoogleCloudDataplexV1ListPartitionsResponse
    extends com.google.api.client.json.GenericJson {

  /**
   * Token to retrieve the next page of results, or empty if there are no more results in the list.
   * The value may be {@code null}.
   */
  @com.google.api.client.util.Key private java.lang.String nextPageToken;

  /** Partitions under the given parent entity. The value may be {@code null}. */
  @com.google.api.client.util.Key private java.util.List<GoogleCloudDataplexV1Partition> partitions;

  /**
   * Token to retrieve the next page of results, or empty if there are no more results in the list.
   *
   * @return value or {@code null} for none
   */
  public java.lang.String getNextPageToken() {
    return nextPageToken;
  }

  /**
   * Token to retrieve the next page of results, or empty if there are no more results in the list.
   *
   * @param nextPageToken nextPageToken or {@code null} for none
   */
  public GoogleCloudDataplexV1ListPartitionsResponse setNextPageToken(
      java.lang.String nextPageToken) {
    this.nextPageToken = nextPageToken;
    return this;
  }

  /**
   * Partitions under the given parent entity.
   *
   * @return value or {@code null} for none
   */
  public java.util.List<GoogleCloudDataplexV1Partition> getPartitions() {
    return partitions;
  }

  /**
   * Partitions under the given parent entity.
   *
   * @param partitions partitions or {@code null} for none
   */
  public GoogleCloudDataplexV1ListPartitionsResponse setPartitions(
      java.util.List<GoogleCloudDataplexV1Partition> partitions) {
    this.partitions = partitions;
    return this;
  }

  @Override
  public GoogleCloudDataplexV1ListPartitionsResponse set(String fieldName, Object value) {
    return (GoogleCloudDataplexV1ListPartitionsResponse) super.set(fieldName, value);
  }

  @Override
  public GoogleCloudDataplexV1ListPartitionsResponse clone() {
    return (GoogleCloudDataplexV1ListPartitionsResponse) super.clone();
  }
}
