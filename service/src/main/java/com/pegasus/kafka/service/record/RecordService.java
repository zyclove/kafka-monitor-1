package com.pegasus.kafka.service.record;

import com.pegasus.kafka.common.constant.Constants;
import com.pegasus.kafka.entity.dto.TopicRecord;
import com.pegasus.kafka.entity.po.MaxOffset;
import com.pegasus.kafka.entity.po.Topic;
import com.pegasus.kafka.service.core.KafkaService;
import com.pegasus.kafka.service.core.ThreadService;
import com.pegasus.kafka.service.dto.TopicRecordService;
import com.pegasus.kafka.service.kafka.KafkaConsumerService;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class RecordService implements SmartLifecycle, DisposableBean {
    private static final Logger logger = LoggerFactory.getLogger(RecordService.class);
    private final KafkaService kafkaService;
    private final TopicRecordService topicRecordService;
    private final ThreadService threadService;
    private final KafkaConsumerService kafkaConsumerService;
    private final CoreService kafkaRecordService;
    private final BlockingQueue<TopicRecord> blockingQueue;
    private boolean running;
    private Topic topic;
    private String consumerGroupdId;
    private AtomicLong discardCount;
    private CountDownLatch cdl;

    public RecordService(Topic topic,
                         KafkaService kafkaService,
                         TopicRecordService topicRecordService,
                         ThreadService threadService,
                         KafkaConsumerService kafkaConsumerService,
                         CoreService kafkaRecordService) {
        this.topic = topic;
        this.kafkaService = kafkaService;
        this.topicRecordService = topicRecordService;
        this.threadService = threadService;
        this.kafkaConsumerService = kafkaConsumerService;
        this.kafkaRecordService = kafkaRecordService;
        this.consumerGroupdId = String.format("%s_%s", Constants.KAFKA_MONITOR_SYSTEM_GROUP_NAME_FOR_MESSAGE, this.topic.getName());
        this.discardCount = new AtomicLong(0L);
        this.blockingQueue = new LinkedBlockingQueue(65568);
    }

    public List<String> getTopicsNames() {
        return this.topic.getTopicNameList();
    }

    @Override
    public void start() {
        setRunning(true);
        cdl = new CountDownLatch(2);

        threadService.submit(() -> {
            Thread.currentThread().setName(String.format("thread-kafka-record-%s", this.topic.getName()));

            try {
                List<String> groupIdList = kafkaConsumerService.listAllConsumers();
                for (String groupId : groupIdList) {
                    try {
                        if (groupId.startsWith(Constants.KAFKA_MONITOR_PEGASUS_SYSTEM_PREFIX)) {
                            kafkaConsumerService.delete(groupId);
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }

            KafkaConsumer<String, String> kafkaConsumer = null;
            try {
                Properties properties = new Properties();
                properties.setProperty(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, kafkaService.getBootstrapServers());
                properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, this.consumerGroupdId);
                properties.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
                properties.setProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "2048");
                properties.setProperty(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, String.valueOf(1000));
                properties.setProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, String.valueOf(1000 * 60));
                properties.setProperty(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, String.valueOf(1000 * 60));
                properties.setProperty(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
                properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
                properties.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getCanonicalName());
                properties.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getCanonicalName());

                Map<String, List<MaxOffset>> maxOffsetMap = topicRecordService.listMaxOffset(this.topic.getTopicNameList());
                kafkaConsumer = new KafkaConsumer<>(properties);

                List<TopicPartition> topicPartitionList = new ArrayList<>(maxOffsetMap.size());
                for (String topicName : this.topic.getTopicNameList()) {
                    if (maxOffsetMap.containsKey(topicName)) {
                        List<MaxOffset> maxOffsetList = maxOffsetMap.get(topicName);
                        for (MaxOffset maxOffset : maxOffsetList) {
                            topicPartitionList.add(new TopicPartition(topicName, maxOffset.getPartitionId()));
                        }
                    } else {
                        List<String> partitionIds = kafkaService.listPartitionIds(topicName);
                        for (String partitionId : partitionIds) {
                            topicPartitionList.add(new TopicPartition(topicName, Integer.parseInt(partitionId)));
                        }
                    }
                }
                kafkaConsumer.assign(topicPartitionList);
                for (TopicPartition topicPartition : topicPartitionList) {
                    if (maxOffsetMap.containsKey(topicPartition.topic())) {

                        List<MaxOffset> maxOffsetList = maxOffsetMap.get(topicPartition.topic());
                        Optional<MaxOffset> first = maxOffsetList.stream().filter(p -> p.getPartitionId().equals(topicPartition.partition())).findFirst();
                        if (first.isPresent()) {
                            kafkaConsumer.seek(topicPartition, first.get().getOffset() + 1L);
                        } else {
                            kafkaConsumer.seek(topicPartition, 0L);
                        }
                    } else {
                        kafkaConsumer.seek(topicPartition, 0L);
                    }
                }

                for (String topicName : this.topic.getTopicNameList()) {
                    topicRecordService.createTable(topicName);
                    logger.info(String.format("[%s] : topic [%s] is beginning to collect.", Thread.currentThread().getName(), topicName));
                }

                while (isRunning()) {
                    ConsumerRecords<String, String> records = kafkaConsumer.poll(Duration.ofMillis(100));
                    if (records.isEmpty()) {
                        continue;
                    }
                    for (ConsumerRecord<String, String> record : records) {
                        TopicRecord topicRecord = new TopicRecord();
                        topicRecord.setTopicName(StringUtils.isEmpty(record.topic()) ? "" : record.topic());
                        topicRecord.setPartitionId(record.partition());
                        topicRecord.setOffset(record.offset());
                        topicRecord.setKey(StringUtils.isEmpty(record.key()) ? "" : record.key());
                        topicRecord.setValue(StringUtils.isEmpty(record.value()) ? "" : record.value());
                        topicRecord.setTimestamp(new Date(record.timestamp()));
                        if (!this.blockingQueue.offer(topicRecord)) {
                            logger.error(String.format("buffer full for topic [%s], [%s], content is [%s]", topicRecord.getTopicName(), discardCount.incrementAndGet(), topicRecord));
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (kafkaConsumer != null) {
                    kafkaConsumer.close();
                }
                kafkaRecordService.uninstallTopic(this.topic);
                try {
                    kafkaService.deleteConsumerGroups(consumerGroupdId);
                } catch (Exception ignored) {
                }
                logger.info(String.format("[%s] : topic [%s] is stopping to collect.", Thread.currentThread().getName(), this.topic.getName()));
                cdl.countDown();
            }
        });

        threadService.submit(() -> {
            Thread.currentThread().setName(String.format("thread-record-saving-%s", this.topic.getName()));
            int count = 0;
            List<TopicRecord> topicRecordList = new ArrayList<>(10000);
            while (isRunning()) {
                try {
                    blockingQueue.drainTo(topicRecordList, 10000);
                    if (topicRecordList.size() > 0) {
                        topicRecordService.batchSave(topicRecordList);
                        topicRecordList.clear();
                        if (count > 0) {
                            count--;
                        }
                    } else {
                        count++;
                    }

                    if (count >= 10) {
                        Thread.sleep(1000);
                        count = 0;
                    }
                } catch (Exception ignored) {
                }
            }
            cdl.countDown();
        });
    }

    @Override
    public void destroy() throws Exception {
        setRunning(false);
        cdl.await(10, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        try {
            destroy();
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }


    private void setRunning(boolean running) {
        this.running = running;
    }

}