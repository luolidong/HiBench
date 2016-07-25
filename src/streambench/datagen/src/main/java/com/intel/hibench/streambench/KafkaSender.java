/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intel.hibench.streambench;

import com.intel.hibench.streambench.common.ConfigLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Properties;

import com.intel.hibench.streambench.common.StreamBenchConfig;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Syncable;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * KafkaSender hold an kafka producer. It gets content from input parameter, generates records and
 * sends records to kafka.
 */
public class KafkaSender {
  final Logger logger = LoggerFactory.getLogger(KafkaSender.class);

  String sourcePath;
  int intervalSpan;
  Configuration dfsConf;
  KafkaProducer producer;
  CachedData cachedData;
  StringSerializer serializer = new StringSerializer();

  // offset of file input stream. Currently it's fixed, which means same records will be sent
  // out on very batch.
  long offset;

  // constructor
  public KafkaSender(String sourcePath, long startOffset, ConfigLoader configLoader) {
    String brokerList = configLoader.getProperty("hibench.streamingbench.brokerList");
    int intervalSpan = Integer.parseInt(configLoader.getProperty(StreamBenchConfig.PREPARE_INTERVAL_SPAN));

    // Details of KafkaProducerConfig could be find from:
    //   http://kafka.apache.org/documentation.html#producerconfigs
    Properties props = new Properties();
    props.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokerList);
    props.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
        "org.apache.kafka.common.serialization.StringSerializer");
    props.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
        "org.apache.kafka.common.serialization.StringSerializer");
    props.setProperty(ProducerConfig.ACKS_CONFIG, "1");
    props.getProperty(ProducerConfig.CLIENT_ID_CONFIG, "hibench_data_generator");

    this.producer = new KafkaProducer(props);
    this.sourcePath = sourcePath;
    this.offset = startOffset;
    this.intervalSpan = intervalSpan;
    this.cachedData = CachedData.getInstance(sourcePath, offset, configLoader);
  }

  // The callback function will be triggered when receive ack from kafka.
  // Print error message if exception exist.
  Callback callback = new Callback() {
    public void onCompletion(RecordMetadata metadata, Exception e) {
      if (e != null)
        e.printStackTrace();
    }
  };

  // send content to Kafka
  public long send (String topic, long totalRecords) {

    long sentRecords = 0L;
    long sentBytes = 0L;

    while (sentRecords < totalRecords) {
      String line = cachedData.getRecord();
      if (line == null) {
        break; // no more data from source files
      }
      String currentTime = Long.toString(System.currentTimeMillis());
      ProducerRecord record = new ProducerRecord(topic, currentTime, line);
      producer.send(record, callback);

      // Key and Value will be serialized twice.
      // 1. in producer.send method
      // 2. explicitly serialize here to count byte size.
      byte[] keyByte = serializer.serialize(topic, currentTime);
      byte[] valueByte = serializer.serialize(topic, line);

      //update counter
      sentRecords++;
      sentBytes = sentBytes + keyByte.length + valueByte.length;
    }

    // print out useful info
    double timeCost = (double) intervalSpan / 1000;
    double throughput = (double) (sentBytes / timeCost) / 1000000;
    logger.info("sent " + sentRecords + " records to Kafka topic: " + topic);
    logger.info("totally sent " + sentBytes + " bytes in " + timeCost + " seconds (throughout: " + throughput + " MB/s)");

    return sentRecords;
  }

  // close kafka producer
  public void close() {
    producer.close();
  }
}
