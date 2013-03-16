package spark.streaming.dstream

import spark.Logging
import spark.storage.StorageLevel
import spark.streaming.{Time, DStreamCheckpointData, StreamingContext}

import java.util.Properties
import java.util.concurrent.Executors

import kafka.consumer._
import kafka.message.{Message, MessageSet, MessageAndMetadata}
import kafka.serializer.StringDecoder
import kafka.utils.{Utils, ZKGroupTopicDirs}
import kafka.utils.ZkUtils._
import kafka.utils.ZKStringSerializer
import org.I0Itec.zkclient._

import scala.collection.mutable.HashMap
import scala.collection.JavaConversions._


/**
 * Input stream that pulls messages from a Kafka Broker.
 * 
 * @param kafkaParams Map of kafka configuration paramaters. See: http://kafka.apache.org/configuration.html
 * @param topics Map of (topic_name -> numPartitions) to consume. Each partition is consumed
 * in its own thread.
 * @param storageLevel RDD storage level.
 */
private[streaming]
class KafkaInputDStream[T: ClassManifest](
    @transient ssc_ : StreamingContext,
    kafkaParams: Map[String, String],
    topics: Map[String, Int],
    storageLevel: StorageLevel
  ) extends NetworkInputDStream[T](ssc_ ) with Logging {


  def getReceiver(): NetworkReceiver[T] = {
    new KafkaReceiver(kafkaParams, topics, storageLevel)
        .asInstanceOf[NetworkReceiver[T]]
  }
}

private[streaming]
class KafkaReceiver(kafkaParams: Map[String, String],
  topics: Map[String, Int],
  storageLevel: StorageLevel) extends NetworkReceiver[Any] {

  // Handles pushing data into the BlockManager
  lazy protected val blockGenerator = new BlockGenerator(storageLevel)
  // Connection to Kafka
  var consumerConnector : ConsumerConnector = null

  def onStop() {
    blockGenerator.stop()
  }

  def onStart() {

    blockGenerator.start()

    // In case we are using multiple Threads to handle Kafka Messages
    val executorPool = Executors.newFixedThreadPool(topics.values.reduce(_ + _))

    logInfo("Starting Kafka Consumer Stream with group: " + kafkaParams("groupid"))

    // Kafka connection properties
    val props = new Properties()
    kafkaParams.foreach(param => props.put(param._1, param._2))

    // Create the connection to the cluster
    logInfo("Connecting to Zookeper: " + kafkaParams("zk.connect"))
    val consumerConfig = new ConsumerConfig(props)
    consumerConnector = Consumer.create(consumerConfig)
    logInfo("Connected to " + kafkaParams("zk.connect"))

    // When autooffset.reset is 'smallest', it is our responsibility to try and whack the
    // consumer group zk node.
    if (kafkaParams.get("autooffset.reset").exists(_ == "smallest")) {
      tryZookeeperConsumerGroupCleanup(kafkaParams("zk.connect"), kafkaParams("groupid"))
    }

    // Create Threads for each Topic/Message Stream we are listening
    val topicMessageStreams = consumerConnector.createMessageStreams(topics, new StringDecoder())

    // Start the messages handler for each partition
    topicMessageStreams.values.foreach { streams =>
      streams.foreach { stream => executorPool.submit(new MessageHandler(stream)) }
    }
  }

  // Handles Kafka Messages
  private class MessageHandler(stream: KafkaStream[String]) extends Runnable {
    def run() {
      logInfo("Starting MessageHandler.")
      for (msgAndMetadata <- stream) {
        blockGenerator += msgAndMetadata.message
      }
    }
  }

  // Delete consumer group from zookeeper. This effectivly resets the group so we can consume from the beginning again.
  // The kafka high level consumer doesn't expose setting offsets currently, this is a trick copied from Kafkas'
  // ConsoleConsumer. See code related to 'autooffset.reset' when it is set to 'smallest':
  // https://github.com/apache/kafka/blob/0.7.2/core/src/main/scala/kafka/consumer/ConsoleConsumer.scala
  private def tryZookeeperConsumerGroupCleanup(zkUrl: String, groupId: String) {
    try {
      val dir = "/consumers/" + groupId
      logInfo("Cleaning up temporary zookeeper data under " + dir + ".")
      val zk = new ZkClient(zkUrl, 30*1000, 30*1000, ZKStringSerializer)
      zk.deleteRecursive(dir)
      zk.close()
    } catch {
      case _ => // swallow
    }
  }
}
