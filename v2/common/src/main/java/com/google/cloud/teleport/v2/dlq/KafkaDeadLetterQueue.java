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
package com.google.cloud.teleport.v2.dlq;

import com.google.auto.value.AutoValue;
import java.util.Map;
import org.apache.beam.sdk.coders.*;
import org.apache.beam.sdk.io.kafka.KafkaIO;
import org.apache.beam.sdk.transforms.*;
import org.apache.beam.sdk.transforms.errorhandling.BadRecord;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollection.IsBounded;
import org.apache.beam.sdk.values.POutput;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.joda.time.Duration;

@AutoValue
public abstract class KafkaDeadLetterQueue extends PTransform<PCollection<BadRecord>, POutput> {
  public abstract String bootStrapServers();

  public abstract String topic();

  public abstract Map<String, Object> config();

  public static KafkaDLQBuilder newBuilder() {
    return new AutoValue_KafkaDeadLetterQueue.Builder();
  }

  @AutoValue.Builder
  public abstract static class KafkaDLQBuilder {
    public abstract KafkaDLQBuilder setBootStrapServers(String value);

    public abstract KafkaDLQBuilder setTopic(String value);

    public abstract KafkaDLQBuilder setConfig(Map<String, Object> value);

    abstract KafkaDeadLetterQueue autoBuild();

    public KafkaDeadLetterQueue build() {
      return autoBuild();
    }
  }

  @Override
  public POutput expand(PCollection<BadRecord> input) {
    return input
        .apply(Window.into(FixedWindows.of(Duration.standardSeconds(5))))
        .apply(ParDo.of(new DlqUtils.getPayLoadFromBadRecord()))
        .setCoder(
            KvCoder.of(
                NullableCoder.of(ByteArrayCoder.of()), NullableCoder.of(ByteArrayCoder.of())))
        .apply(
            KafkaIO.<byte[], byte[]>write()
                .withBootstrapServers(bootStrapServers())
                .withTopic(topic())
                .withProducerConfigUpdates(config())
                .withKeySerializer(ByteArraySerializer.class)
                .withValueSerializer(ByteArraySerializer.class));
  }

  public static class ErrorSinkTransform
      extends PTransform<PCollection<BadRecord>, PCollection<Long>> {
    @Override
    public PCollection<Long> expand(PCollection<BadRecord> input) {
      if (input.isBounded() == IsBounded.BOUNDED) {
        return input.apply("Combine", Combine.globally(Count.<BadRecord>combineFn()));
      } else {
        return input
            .apply("Window", Window.into(FixedWindows.of(Duration.standardDays(1))))
            .apply("Combine", Combine.globally(Count.<BadRecord>combineFn()).withoutDefaults());
      }
    }
  }
}
