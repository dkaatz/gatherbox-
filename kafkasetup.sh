#!/bin/bash
# Simple Bash script for initialization of a not clustered Kafka Broker

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
fi