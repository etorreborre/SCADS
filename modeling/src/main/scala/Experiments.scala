package edu.berkeley.cs
package scads
package piql
package modeling

import comm._
import storage._
import deploylib.mesos._

object Experiments {
  val zooKeeperRoot = ZooKeeperNode("zk://zoo.knowsql.org/").getOrCreate("home").getOrCreate(System.getenv("USER"))
  val cluster = new Cluster(zooKeeperRoot)

  implicit def classSource = cluster.classSource
  def serviceScheduler = cluster.serviceScheduler
  def traceRoot = zooKeeperRoot.getOrCreate("traceCollection")

  lazy val scadsCluster = new ScadsCluster(traceRoot)
  lazy val scadrClient = new scadr.ScadrClient(scadsCluster, new ParallelExecutor)

  def laggards = cluster.slaves.pflatMap(_.jps).filter(_.main equals "AvroTaskMain").pfilterNot(_.stack contains "ScalaEngineTask").pfilterNot(_.stack contains "awaitChild")

  def killTask(id: Int): Unit = cluster.serviceScheduler !? KillTaskRequest(id)

  def scadrClusterParams = ScadrClusterParams(
    traceRoot.canonicalAddress, // cluster address
    50,                         // num storage nodes
    50,                         // num load clients
    100,                        // num per page
    1000000,                    // num users
    100,                        // num thoughts per user
    1000                        // num subscriptions per user
  )

  def thoughtstreamRunParams = RunParams(
    scadrClusterParams,
    "thoughtstream",
    "thoughtstream-michael",
    50                          // # trace collectors
  )

  def localUserThoughtstreamRunParams = RunParams(
    scadrClusterParams,
    "localUserThoughtstream",
    "localUserThoughtstream-michael",
    50                          // # trace collectors
  )

  def startScadrTraceCollector: Unit = {
    val traceTask = ScadrTraceCollectorTask(
      RunParams(
        scadrClusterParams,
        "mySubscriptions"
      )
    ).toJvmTask
    
    serviceScheduler !? RunExperimentRequest(traceTask :: Nil)
  }

  def startThoughtstreamTraceCollector: Unit = {
    val traceTasks = Array.fill(thoughtstreamRunParams.numTraceCollectors)(ThoughtstreamTraceCollectorTask(thoughtstreamRunParams).toJvmTask)
    
    serviceScheduler !? RunExperimentRequest(traceTasks.toList)
  }

  def startOneThoughtstreamTraceCollector: Unit = {
    val traceTask = ThoughtstreamTraceCollectorTask(thoughtstreamRunParams).toJvmTask
    
    serviceScheduler !? RunExperimentRequest(traceTask :: Nil)
  }

  def startLocalUserThoughtstreamTraceCollector: Unit = {
    val traceTasks = Array.fill(localUserThoughtstreamRunParams.numTraceCollectors)(ThoughtstreamTraceCollectorTask(localUserThoughtstreamRunParams).toJvmTask)
    
    serviceScheduler !? RunExperimentRequest(traceTasks.toList)
  }

  def startOneLocalUserThoughtstreamTraceCollector: Unit = {
    val traceTask = ThoughtstreamTraceCollectorTask(localUserThoughtstreamRunParams).toJvmTask
    
    serviceScheduler !? RunExperimentRequest(traceTask :: Nil)
  }

  def startScadrDataLoad: Unit = {
    val engineTask = ScalaEngineTask(traceRoot.canonicalAddress).toJvmTask
    val loaderTask = ScadrDataLoaderTask(scadrClusterParams).toJvmTask

    val storageEngines = Vector.fill(scadrClusterParams.numStorageNodes)(engineTask)
    val dataLoadTasks = Vector.fill(scadrClusterParams.numLoadClients)(loaderTask)

    serviceScheduler !? (RunExperimentRequest(storageEngines), 30 * 1000)
    serviceScheduler !? (RunExperimentRequest(dataLoadTasks), 30 * 1000)
  }
}