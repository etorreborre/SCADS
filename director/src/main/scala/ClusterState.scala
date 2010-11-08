package edu.berkeley.cs.scads.director

import edu.berkeley.cs.scads.comm.{PartitionService,StorageService}

object ClusterState {
	val pastServers = scala.collection.mutable.HashSet[String]()
	val pastPartitions = scala.collection.mutable.HashSet[String]()
	
	def getRandomServerNames(cfg:ClusterState,n:Int):List[StorageService] = {
		//val rnd = new java.util.Random(7)

		val newNames = scala.collection.mutable.HashSet[String]()
		var name = ""
		for (i <- 1 to n) {
			var ps = pastServers.clone
			ps--=newNames
			if (cfg!=null) ps--=cfg.servers.map(s=>s.host)
			if (ps.size>0)
				name = ps.toList(0)
			else
				do {
					name = "s"+"%03d".format(Director.nextRndInt(999))
				} while ( (cfg!=null&&cfg.servers.map(s=>s.host).contains(name))||newNames.contains(name) )
			newNames+=name
			pastServers+=name
		}
		newNames.map( name=> new StorageService(name,1,null)).toList
	}
	def getRandomPartitionNames(cfg:ClusterState,n:Int):List[PartitionService] = {
		val newNames = scala.collection.mutable.HashSet[String]()
		var name = ""
		for (i <- 1 to n) {
			var ps = pastPartitions.clone
			ps--=newNames
			if (cfg!=null) ps--=cfg.partitionsToKeys.keys.map(s=>s.host)
			if (ps.size>0)
				name = ps.toList(0)
			else
				do {
					name = "p"+"%03d".format(Director.nextRndInt(999))
				} while ( (cfg!=null&&cfg.partitionsToKeys.keys.toList.map(s=>s.host).contains(name))||newNames.contains(name) )
			newNames+=name
			pastPartitions+=name
		}
		newNames.map( name=> new PartitionService(name,0,null,null,null)).toList
	}
}

class ClusterState(
	val serversToPartitions:Map[StorageService,Set[PartitionService]], // server -> set(partition)
	val keysToPartitions:Map[Option[org.apache.avro.generic.GenericData.Record], Set[PartitionService]], // startkey -> set(partition)
	val partitionsToKeys:Map[PartitionService,Option[org.apache.avro.generic.GenericData.Record]], // partition -> startkey
	val workloadRaw:WorkloadHistogram,
	val time:Long
) {
	def servers:Set[StorageService] = Set( serversToPartitions.keys.toList :_* )

	def partitionsOnServers(servers:List[StorageService]):Set[Option[org.apache.avro.generic.GenericData.Record]] = 
		Set(serversToPartitions.filter(s=>servers.contains(s._1)).values.toList.flatten(r=>r).map(r=>partitionsToKeys(r)):_*)

	def partitionsWithMoreThanKReplicas(k:Int):Set[Option[org.apache.avro.generic.GenericData.Record]] =
		Set(keysToPartitions.filter(entry => entry._2.size > k).keys.toList:_*)

	def serversForKey(startkey:Option[org.apache.avro.generic.GenericData.Record]):Set[StorageService] = 
		Set(serversToPartitions.filter(entry => entry._2.intersect(keysToPartitions(startkey)).size > 0).keys.toList:_*)
	
	def partitionOnServer(startkey:Option[org.apache.avro.generic.GenericData.Record], server:StorageService):PartitionService = {
		val result = serversToPartitions(server).intersect(keysToPartitions(startkey))
		assert (result.size == 1)
		result.toList.head
	}
	
	def getEmptyServers():Set[StorageService] = Set(serversToPartitions.filter(entry => entry._2.size == 0).keySet.toList:_*)
	
	def replicate(part:PartitionService, server:StorageService):ClusterState = {
		val key = partitionsToKeys(part)
		val fakepart = ClusterState.getRandomPartitionNames(this,1).head
		val sToP = serversToPartitions(server) += fakepart
		val kToP = keysToPartitions(key) += fakepart
		val pToK = partitionsToKeys + (fakepart -> key)
		new ClusterState(sToP,kToP,pToK,workloadRaw,time)
	}
	
	def delete(part:PartitionService, server:StorageService):ClusterState = {
		val key = partitionsToKeys(part)
		val sToP = serversToPartitions(server) -= part
		val kToP = keysToPartitions(key) -= part
		val pToK = partitionsToKeys - part
		new ClusterState(sToP,kToP,pToK,workloadRaw,time)
	}
	
	def addServers(num:Int):(ClusterState,List[StorageService]) = {
		val newservers = ClusterState.getRandomServerNames(this,num)
		val sToP = serversToPartitions ++ Map( newservers.map(s => (s,Set[PartitionService]())):_* )
		(new ClusterState(sToP,
			Map(keysToPartitions.toList.map( p=> (p._1,p._2) ):_*),
			Map(partitionsToKeys.toList.map( p=> (p._1,p._2) ):_*),
			workloadRaw,time), newservers)
	}
	
	def addServer(newserver:StorageService):ClusterState = {
		val sToP = serversToPartitions ++ Map( List(newserver).map(s => (s,Set[PartitionService]())):_* )
		new ClusterState(sToP,
			Map(keysToPartitions.toList.map( p=> (p._1,p._2) ):_*),
			Map(partitionsToKeys.toList.map( p=> (p._1,p._2) ):_*),
			workloadRaw,time)
	}
	
	def removeServers(servers:List[StorageService]):ClusterState = {
		servers.foreach(s => assert(serversToPartitions(s).size == 0) ) // there should be no partitions left on these servers...
		new ClusterState(serversToPartitions -- servers,
			Map(keysToPartitions.toList.map( p=> (p._1,p._2) ):_*),
			Map(partitionsToKeys.toList.map( p=> (p._1,p._2) ):_*) ,
			workloadRaw,time)
	}
	
	// TODO: meaningless as immutable?
	override def clone():ClusterState = {
		new ClusterState(
			Map(serversToPartitions.toList.map( p=> (p._1,p._2) ):_*),
			Map(keysToPartitions.toList.map( p=> (p._1,p._2) ):_*),
			Map(partitionsToKeys.toList.map( p=> (p._1,p._2) ):_*) ,
			workloadRaw,time
		)
	}
	
	override def toString:String = {"ClusterState: TODO"}
}