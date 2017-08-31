#!/bin/bash
# Simple Bash script for initialization of a not clustered Kafka Broker
# if you have performance implications with the build in broker and lost messages you should consider using an
# external one wich is setup like this to work with this tool

KAFKA_ROOT='/opt/kafka_2.11-0.11.0.0'
KAFKA_LOGS='/tmp/kafka-logs'
ZOOKEEPER_DATA='/tmp/zookeeper/'



# when started in clean mode we delete the existing topics
if [ "$1" == "clean" ] || [ "$1" == "justClean" ];  then
# Stooping servers if already exist
echo 'Stopping Kafka Daemon'
${KAFKA_ROOT}/bin/kafka-server-stop.sh
echo 'Stopping Zookeeper Daemon'
${KAFKA_ROOT}/bin/zookeeper-server-stop.sh
S
echo 'Deleting Kafka Logs'
rm -rf ${KAFKA_LOGS}
echo 'Deleting Zookeeper Data'
rm -rf ${ZOOKEEPER_DATA}
fi

if [ "$1" != "justClean" ]; then
# starting the servers
echo 'Starting Zookeeper Daemon'
${KAFKA_ROOT}/bin/zookeeper-server-start.sh -daemon ${KAFKA_ROOT}/config/zookeeper.properties

echo 'Starting Kafka Daemon'
${KAFKA_ROOT}/bin/kafka-server-start.sh -daemon ${KAFKA_ROOT}/config/server.properties &


#Creating topics to avoidwarning ons sbt runAll
${KAFKA_ROOT}/bin/kafka-topics.sh --create --zookeeper localhost:2181 --replication-factor 1 --partitions 1 --topic censysStatus
${KAFKA_ROOT}/bin/kafka-topics.sh --create --zookeeper localhost:2181 --replication-factor 1 --partitions 1 --topic ixquickStatus
${KAFKA_ROOT}/bin/kafka-topics.sh --create --zookeeper localhost:2181 --replication-factor 1 --partitions 1 --topic xingStatus
${KAFKA_ROOT}/bin/kafka-topics.sh --create --zookeeper localhost:2181 --replication-factor 1 --partitions 1 --topic linkedinStatus
${KAFKA_ROOT}/bin/kafka-topics.sh --create --zookeeper localhost:2181 --replication-factor 1 --partitions 1 --topic scanStatus
${KAFKA_ROOT}/bin/kafka-topics.sh --create --zookeeper localhost:2181 --replication-factor 1 --partitions 1 --topic dataBreachStatus
${KAFKA_ROOT}/bin/kafka-topics.sh --create --zookeeper localhost:2181 --replication-factor 1 --partitions 1 --topic ixquickUpdate
${KAFKA_ROOT}/bin/kafka-topics.sh --create --zookeeper localhost:2181 --replication-factor 1 --partitions 1 --topic censysUpdate
${KAFKA_ROOT}/bin/kafka-topics.sh --create --zookeeper localhost:2181 --replication-factor 1 --partitions 1 --topic linkedinUpdate
${KAFKA_ROOT}/bin/kafka-topics.sh --create --zookeeper localhost:2181 --replication-factor 1 --partitions 1 --topic xingUpdate
${KAFKA_ROOT}/bin/kafka-topics.sh --create --zookeeper localhost:2181 --replication-factor 1 --partitions 1 --topic databreachUpdate
${KAFKA_ROOT}/bin/kafka-topics.sh --create --zookeeper localhost:2181 --replication-factor 1 --partitions 1 --topic databreachToScan
${KAFKA_ROOT}/bin/kafka-topics.sh --create --zookeeper localhost:2181 --replication-factor 1 --partitions 1 --topic databreachScannerUpdate
fi

