package edu.berkeley.cs.scads.storage.examples
import edu.berkeley.cs.scads.storage.transactions._
import edu.berkeley.cs.scads.storage._
import edu.berkeley.cs.avro.marker.{AvroRecord, AvroPair}
import edu.berkeley.cs.scads.util.Logger

import scala.actors.Actor
import scala.actors.Actor._

import edu.berkeley.cs.scads.storage.transactions.FieldAnnotations._

import java.util.concurrent.Semaphore

case class DataRecordActor(var id: Int) extends AvroPair {
  var s: String = _
  @FieldGE(0)
  @FieldLT(200)
  var a: Int = _
  @FieldGE(0)
  @FieldLT(200)
  var b: Long = _
  var c: Float = _

  override def toString = "DataRecord(" + id + ", " + s + ", " + a + ", " + b + ", " + c + ")"
}

object Client {
  @volatile var ready = true
}

class Client(id : Int, nsPair: PairNamespace[DataRecordActor] with PairTransactions[DataRecordActor], sema: Semaphore, useLogical: Boolean = false) extends Actor {
  private val logger = Logger(classOf[Client])

  def act() {
    for (i <- 0 until 1000) {
      if(!Client.ready) assert(false, "A Trx was unsuccesfull")
      logger.info("%s Starting update", this.hashCode())
      val s = System.currentTimeMillis
      val tx = new Tx(10000) ({
        if (!useLogical) {
          val dr = nsPair.getRecord(DataRecordActor((i % 10) + 1)).get
          dr.a = dr.a - 1
          nsPair.put(dr)
        } else {
          val dr = DataRecordActor((i % 10) + 1)
          dr.s = ""; dr.a = -1; dr.b = 0; dr.c = 0
          println("Putting " + dr)
          nsPair.putLogical(dr)
        }
      })
      val status = tx.Execute()
      status match {
        case UNKNOWN => {
          println("#####################  UNKNOWN ######################")
          Client.ready =false
        }
        case _ =>
      }

      logger.info("%s Finished the Trx in %s", this.hashCode(), (System.currentTimeMillis() - s))
      //Thread.sleep(1000)
    }

    sema.release
  }
}

class TestTxActors {
  def run() {
    val cluster = TestScalaEngine.newScadsCluster(5)

    val nsPair = cluster.getNamespace[DataRecordActor]("testnsPair", NSTxProtocolMDCC())
    nsPair.setPartitionScheme(List((None, cluster.getAvailableServers)))
    Thread.sleep(1000)
    var dr = DataRecordActor(1)
    dr.s = "a"; dr.a = 100000; dr.b = 100; dr.c = 1.0.floatValue
    nsPair.put(dr)
    (2 to 10).foreach( x => {
      dr.id = x
      nsPair.put(dr)
    })

    val numClients = 10
    val sema = new Semaphore(0)
    val clients = (1 to numClients).map(x => new Client(x, nsPair, sema, true))

    // Start client actors.
    clients.foreach(_.start)

    // Wait for actors to be done.
    clients.foreach(x => sema.acquire)

    // Sleep for a little bit to wait for the commits.
    Thread.sleep(2000)
    println("result: ")
    nsPair.getRange(None, None).foreach(x => println(x))
  }
}

object TestTxActors {
  def main(args: Array[String]) {
    val test = new TestTxActors()
    test.run()
    println("Done with test")
    Thread.sleep(100000)
    println("Exiting...")
    System.exit(0)
  }
}