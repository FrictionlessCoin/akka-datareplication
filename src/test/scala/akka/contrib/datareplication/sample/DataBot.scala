package akka.contrib.datareplication.sample

import scala.concurrent.duration._
import akka.actor.ActorLogging
import akka.contrib.datareplication.protobuf.msg.ReplicatorMessages.GetSuccess
import akka.contrib.datareplication.DataReplication
import akka.cluster.Cluster
import akka.contrib.datareplication.Replicator
import akka.actor.Actor
import akka.contrib.datareplication.ORSet
import scala.concurrent.forkjoin.ThreadLocalRandom
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import akka.actor.Props

object DataBot {

  def main(args: Array[String]): Unit = {
    if (args.isEmpty)
      startup(Seq("2551", "2552", "0"))
    else
      startup(args)
  }

  def startup(ports: Seq[String]): Unit = {
    ports foreach { port =>
      // Override the configuration of the port
      val config = ConfigFactory.parseString("akka.remote.netty.tcp.port=" + port).
        withFallback(ConfigFactory.load(
          ConfigFactory.parseString("""
            akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
            akka.remote {
              netty.tcp {
                hostname = "127.0.0.1"
                port = 0
              }
            }
            
            akka.cluster {
              seed-nodes = [
                "akka.tcp://ClusterSystem@127.0.0.1:2551",
                "akka.tcp://ClusterSystem@127.0.0.1:2552"]
            
              auto-down-unreachable-after = 10s
            }
            """)))

      // Create an Akka system
      val system = ActorSystem("ClusterSystem", config)
      // Create an actor that handles cluster domain events
      system.actorOf(Props[DataBot], name = "dataBot")
    }
  }

  private case object Tick

}

class DataBot extends Actor with ActorLogging {
  import DataBot._
  import Replicator._

  val replicator = DataReplication(context.system).replicator
  implicit val cluster = Cluster(context.system)

  import context.dispatcher
  val tickTask = context.system.scheduler.schedule(5.seconds, 5.seconds, self, Tick)

  replicator ! Subscribe("key", self)

  var seqNo = 0L
  var current = ORSet()

  def receive = {
    case Tick =>
      val s = ThreadLocalRandom.current().nextInt(97, 123).toChar.toString
      if (ThreadLocalRandom.current().nextBoolean()) {
        // add
        val newData = current :+ s
        log.info("Adding: {}", s)
        replicator ! Update("key", newData, seqNo)
      } else {
        // remove
        val newData = current :- s
        log.info("Removing: {}", s)
        replicator ! Update("key", newData, seqNo)
      }
      seqNo += 1

    case Changed("key", data: ORSet) =>
      current = data
      log.info("Current elements: {}", data.value)
  }

  override def postStop(): Unit = tickTask.cancel()

}

