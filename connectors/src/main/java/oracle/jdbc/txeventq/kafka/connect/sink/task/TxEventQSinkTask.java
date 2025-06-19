/*
** Kafka Connect for TxEventQ.
**
** Copyright (c) 2023, 2024 Oracle and/or its affiliates.
** Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.
*/

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package oracle.jdbc.txeventq.kafka.connect.sink.task;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import oracle.jdbc.txeventq.kafka.connect.common.utils.AppInfoParser;
import oracle.jdbc.txeventq.kafka.connect.sink.utils.TxEventQProducer;
import oracle.jdbc.txeventq.kafka.connect.sink.utils.TxEventQSinkConfig;

public class TxEventQSinkTask extends SinkTask {
    private static final Logger log = LoggerFactory.getLogger(TxEventQSinkTask.class);
    private TxEventQSinkConfig config;
    private TxEventQProducer producer;

    @Override
    public String version() {
        return AppInfoParser.getVersion();
    }

    @Override
    public void start(Map<String, String> properties) {
        log.trace("Entry {}.start, props={}", this.getClass().getName(), properties);

        // Loading Task Configuration

        config = new TxEventQSinkConfig(properties);
        producer = new TxEventQProducer(config);
        this.producer.connect();

        if (!producer.kafkaTopicExists(this.config.getString(TxEventQSinkConfig.KAFKA_TOPIC))) {
            throw new ConnectException("The Kafka topic "
                    + this.config.getString(TxEventQSinkConfig.KAFKA_TOPIC) + " does not exist.");
        }

        try {
            if (!producer.txEventQueueExists(
                    this.config.getString(TxEventQSinkConfig.TXEVENTQ_QUEUE_NAME).toUpperCase())) {
                throw new ConnectException("The TxEventQ queue name "
                        + this.config.getString(TxEventQSinkConfig.TXEVENTQ_QUEUE_NAME)
                        + " does not exist.");
            }
        } catch (SQLException e1) {
            throw new ConnectException(
                    "Error attempting to validate the existence of the TxEventQ queue name: "
                            + e1.getMessage());
        }

        int kafkaPartitionNum = producer
                .getKafkaTopicPartitionSize(this.config.getString(TxEventQSinkConfig.KAFKA_TOPIC));
        int txEventQShardNum = producer.getNumOfShardsForQueue(
                this.config.getString(TxEventQSinkConfig.TXEVENTQ_QUEUE_NAME));
        if (kafkaPartitionNum > txEventQShardNum) {
            throw new ConnectException("The number of Kafka partitions " + kafkaPartitionNum
                    + " must be less than or equal the number TxEventQ event stream "
                    + txEventQShardNum);
        }

        if (!this.producer.createOffsetInfoTable()) {
            throw new ConnectException(
                    "TXEVENTQ_TRACK_OFFSETS table couldn't be created or accessed to setup offset information.");
        }

        log.trace("[{}] Exit {}.start", this.producer.getDatabaseConnection(),
                this.getClass().getName());
    }

    @Override
    public void put(Collection<SinkRecord> records) {
        log.trace("[{}] Entry {}.put", this.producer.getDatabaseConnection(),
                this.getClass().getName());

        if (records.isEmpty()) {
            return;
        }

        log.debug("Number of records sent to put call: {}", records.size());
        producer.put(records);

        log.trace("[{}]  Exit {}.put", this.producer.getDatabaseConnection(),
                this.getClass().getName());
    }

    @Override
    public void stop() {
        log.trace("[{}] Entry {}.stop", this.producer.getDatabaseConnection(),
                this.getClass().getName());

        closeDatabaseConnection();

        log.trace("[{}] Exit {}.stop", this.producer.getDatabaseConnection(),
                this.getClass().getName());
    }

    /**
     * Calls the producer's close method to close the database connection.
     */
    private void closeDatabaseConnection() {
        log.trace("[{}] Entry {}.closeDatabaseConnection", this.producer.getDatabaseConnection(),
                this.getClass().getName());
        try {
            this.producer.close();
        } catch (IOException e) {
            log.error("Exception occurred while closing database connection.");
        }

        log.trace("[{}] Exit {}.closeDatabaseConnection", this.producer.getDatabaseConnection(),
                this.getClass().getName());
    }

    @Override
    public Map<TopicPartition, OffsetAndMetadata> preCommit(
            Map<TopicPartition, OffsetAndMetadata> currentOffsets) {
        log.trace("[{}] Entry {}.preCommit", this.producer.getDatabaseConnection(),
                this.getClass().getName());

        // Returning an empty set of offsets since the connector is going to handle all
        // offsets in the external system.
        currentOffsets.clear();
        log.trace("[{}] Exit {}.preCommit", this.producer.getDatabaseConnection(),
                this.getClass().getName());
        return currentOffsets;
    }

    /**
     * The SinkTask uses this method to create writers for newly assigned partitions in case of
     * partition rebalance. This method will be called after partition re-assignment completes and
     * before the SinkTask starts fetching data. Note that any errors raised from this method will
     * cause the task to stop.
     * 
     * @param partitions The list of partitions that are now assigned to the task (may include
     *                   partitions previously assigned to the task)
     */
    @Override
    public void open(Collection<TopicPartition> partitions) {
        log.info("[{}] Entry {}.open", this.producer.getDatabaseConnection(),
                this.getClass().getName());

        HashMap<TopicPartition, Long> offsetMapNew = new HashMap<>();
        for (TopicPartition tp : partitions) // for each partition assigned
        {
            log.info("Partition assigned to task: [{}]", tp.partition());

            Long offset = this.producer.getOffsetInDatabase(tp.topic(),
                    this.config.getString(TxEventQSinkConfig.TXEVENTQ_QUEUE_NAME),
                    this.config.getString(TxEventQSinkConfig.TXEVENTQ_QUEUE_SCHEMA),
                    tp.partition());
            offsetMapNew.put(tp, offset);
        }
        this.context.offset(offsetMapNew);

        log.info("[{}] Exit {}.open", this.producer.getDatabaseConnection(),
                this.getClass().getName());
    }
}
