/**
 * Copyright 2015-2016 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin.collector.kafka;

import java.util.Calendar;
import java.util.Collections;
import java.util.List;

import com.google.gson.annotations.JsonAdapter;
import kafka.consumer.ConsumerIterator;
import kafka.consumer.KafkaStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zipkin.Codec;
import zipkin.Span;
import zipkin.collector.Collector;
import zipkin.collector.CollectorMetrics;

import static zipkin.storage.Callback.NOOP;

/** Consumes spans from Kafka messages, ignoring malformed input */
final class KafkaStreamProcessor implements Runnable {
  final KafkaStream<byte[], byte[]> stream;
  final Collector collector;
  final CollectorMetrics metrics;
  final Logger logger = LoggerFactory.getLogger(KafkaStreamProcessor.class);

  KafkaStreamProcessor(
      KafkaStream<byte[], byte[]> stream, Collector collector, CollectorMetrics metrics) {
    this.stream = stream;
    this.collector = collector;
    this.metrics = metrics;
  }

  @Override
  public void run() {
    ConsumerIterator<byte[], byte[]> messages = stream.iterator();
    while (messages.hasNext()) {
      try {
      byte[] bytes = messages.next().message();
      metrics.incrementMessages();
      logger.info("new message comming from kafka," + new String(bytes));

      if (bytes == null) {
        logger.info("message is null.");
        continue;
      }
      if (bytes.length == 0) {
        metrics.incrementMessagesDropped();
        logger.info("message is empty.");
        continue;
      }
//      logger.info("start read spans.");
//      try {
//        List<Span> spans = Codec.THRIFT.readSpans(bytes);
//        logger.info("span list count: " + spans.size());
//        for (Span span : spans) {
//          logger.info("id:" + span.id + ", name:" + span.name + ", traceId:" + span.traceId);
//        }
//      }catch (Exception e){
//        logger.error(e.getMessage(),e);
//      }

      // In TBinaryProtocol encoding, the first byte is the TType, in a range 0-16
      // .. If the first byte isn't in that range, it isn't a thrift.
      //
      // When byte(0) == '[' (91), assume it is a list of json-encoded spans
      //
      // When byte(0) <= 16, assume it is a TBinaryProtocol-encoded thrift
      // .. When serializing a Span (Struct), the first byte will be the type of a field
      // .. When serializing a List[ThriftSpan], the first byte is the member type, TType.STRUCT(12)
      // .. As ThriftSpan has no STRUCT fields: so, if the first byte is TType.STRUCT(12), it is a list.
        if (bytes[0] == '[') {
          collector.acceptSpans(bytes, Codec.JSON, NOOP);
        } else {
          if (bytes[0] == 12 /* TType.STRUCT */) {
            collector.acceptSpans(bytes, Codec.THRIFT, NOOP);
          } else {
            collector.acceptSpans(Collections.singletonList(bytes), Codec.THRIFT, NOOP);
          }
        }
        logger.info("message process down, has next: " + messages.hasNext());
      }catch (Exception e) {
        logger.error(e.getMessage(), e);
      }
    }
  }
}
