package edu.berkeley.cs.scads.placement

import org.apache.thrift.server.THsHaServer
import org.apache.thrift.transport.TNonblockingServerSocket
import org.apache.thrift.protocol.{TBinaryProtocol, XtBinaryProtocol}
import edu.berkeley.cs.scads.thrift.{DataPlacementServer, KnobbedDataPlacementServer,DataPlacement, RangeConversion, NotImplemented, RecordSet,RangeSet,ConflictPolicy, ConflictPolicyType}
import edu.berkeley.cs.scads.keys._
import edu.berkeley.cs.scads.nodes.StorageNode
import edu.berkeley.cs.scads.WriteLock
import org.apache.log4j.Logger
import org.apache.log4j.BasicConfigurator

trait DataPlacementValidator {
    def isValidDataPlacement(dp: DataPlacement): Boolean = {
        dp.node != null && dp.rset != null && dp.syncPort != 0 && dp.thriftPort != 0
    }
}

class SimpleKnobbedDataPlacementServer extends KnobbedDataPlacementServer.Iface with AutoKey with RangeConversion with DataPlacementValidator {
	val writelock = new WriteLock
	val conflictPolicy = new ConflictPolicy()
	conflictPolicy.setType(ConflictPolicyType.CPT_GREATER)

    // because lookup_node cannot return an null value (thrift limitation)
    // this is a necessary evil to distinguish a real return value from
    // just the sentinel value
    val sentinelDP = new DataPlacement() 
    
    override def isValidDataPlacement(dp: DataPlacement): Boolean = {
        dp != sentinelDP && ( dp.node != null && dp.rset != null && dp.syncPort != 0 && dp.thriftPort != 0 )
    }
    

	import java.text.ParsePosition
	val logger = Logger.getLogger("placement.dataplacementserver")
	var spaces = new scala.collection.mutable.HashMap[String, java.util.List[DataPlacement]]

	def lookup_namespace(ns: String): java.util.List[DataPlacement] = {
		logger.debug("Do I have namespace "+ns+ "? "+spaces.contains(ns))
		if (spaces.contains(ns)) { logger.debug("Namespace "+ns+" has "+spaces(ns).size +" entries"); spaces(ns) } else { new java.util.LinkedList[DataPlacement] }
	}
	def lookup_node(ns: String, host: String, thriftPort: Int, syncPort: Int): DataPlacement = {
		var ret:DataPlacement = null
		if (spaces.contains(ns)) {
			val entries = lookup_namespace(ns)
			val iter = entries.iterator
			var entry:DataPlacement = null
			while (iter.hasNext) { // better way than linear scan?
				entry = iter.next
				if (entry.node==host && entry.thriftPort==thriftPort && entry.syncPort==syncPort) ret = entry
			}
		}
		logger.debug("Node "+host+" has entry? "+(ret!=null))
		ret = ret match {
            case null => sentinelDP 
            case _    => ret
        }
        ret
	}
	def lookup_key(ns: String, key: String): java.util.List[DataPlacement] = {
		var ret = new java.util.ArrayList[DataPlacement]()
		if (spaces.contains(ns)) {
			val entries = lookup_namespace(ns)
			val iter = entries.iterator
			var entry:DataPlacement = null
			while (iter.hasNext) {
				entry = iter.next
				if (entry.rset.range.includes(key)) ret.add(entry)
			}
		}
		logger.debug("Key "+key+" available from "+ ret.size+" nodes.")
		ret
	}
	def lookup_range(ns: String, range: RangeSet): java.util.List[DataPlacement] = {
		var ret = new java.util.ArrayList[DataPlacement]()
		if (spaces.contains(ns)) {
			val entries = lookup_namespace(ns)
			val iter = entries.iterator
			while (iter.hasNext) {
				val entry:DataPlacement = iter.next
				val potential = rangeSetToKeyRange(entry.rset.range)
				val adding:Boolean = (potential & range) != KeyRange.EmptyRange
				logger.debug("Target range "+range.start_key+" - "+range.end_key+ " overlaps '"+potential.start+"' - '"+potential.end+"' ? "+adding)
				if ( adding ) { ret.add(entry) }
			}
		}
		logger.debug("Range "+range.start_key+" - "+range.end_key+" available from "+ ret.size+" nodes.")
		ret
	}

	private def add(ns: String, entry: DataPlacement) {
		logger.info("Adding "+ entry.node + ":"+entry.syncPort)
		val prev_entry:DataPlacement = lookup_node(ns, entry.node, entry.thriftPort, entry.syncPort)
		assign(ns, entry.node, entry.thriftPort, entry.syncPort, entry.rset)

		// update state
		writelock.lock
		try {
			if (isValidDataPlacement(prev_entry)) prev_entry.setRset(entry.rset)
			else spaces(ns).add(entry)
			writelock.unlock
		}
		finally writelock.unlock
		logger.debug("Namespace entry size for "+ ns+ " : "+spaces(ns).size)
	}

	private def addAll(ns: String, entries: java.util.List[DataPlacement]): Boolean = {
		val iter = entries.iterator
		var entry:DataPlacement = null
		while (iter.hasNext) {
			entry = iter.next
			logger.info("Adding "+ entry.node + ":"+entry.syncPort)
			assign(ns, entry.node, entry.thriftPort, entry.syncPort, entry.rset)
		}

		// update state
		writelock.lock
		try {
			spaces += (ns -> entries)
			writelock.unlock
		} finally writelock.unlock
		logger.debug("Namespace entry size for "+ ns+ " : "+spaces(ns).size)
		spaces.contains(ns)
	}

	private def assign(ns:String, host: String, thrift: Int, sync: Int, rset: RecordSet) {
		val node = new StorageNode(host,thrift, sync)
		node.useConnection((c) => c.set_responsibility_policy(ns, rset))
	}

	def add(ns: String, entries:java.util.List[DataPlacement]):Boolean = {
		if (!spaces.contains(ns)) addAll(ns,entries)
		else {
			val iter = entries.iterator
			while (iter.hasNext)  add(ns, iter.next)
		}
		spaces.contains(ns)
	}

	def copy(ns: String, rset: RecordSet, src_host: String, src_thrift: Int, src_sync: Int, dest_host: String, dest_thrift: Int, dest_sync: Int) = {
		val target_range = rangeSetToKeyRange(rset.range)
		val oldDestRange = if ( isValidDataPlacement(lookup_node(ns,dest_host,dest_thrift,dest_sync)) ) {rangeSetToKeyRange(lookup_node(ns,dest_host,dest_thrift,dest_sync).rset.range)} else {KeyRange.EmptyRange}
		val newDestRange = oldDestRange + target_range
		val src = new StorageNode(src_host,src_thrift,src_sync) // where copying from

		// Verify the src has our keyRange
		val compare_range = rangeSetToKeyRange(lookup_node(ns, src_host,src_thrift,src_sync).rset.range)
		logger.debug("Verifying node "+src_host+" with range "+compare_range.start +" - "+compare_range.end+" contains "+ target_range.start +" - "+ target_range.end)
		assert( (compare_range & target_range) == target_range )

		// Tell the servers to copy the data
		logger.info("Copying "+ target_range.start + " - "+ target_range.end +" from "+src_host + " to "+ dest_host)
		src.useConnection((c) => c.copy_set(ns, rset, dest_host+":"+dest_sync))

		// Change the assignment
		val list = new java.util.ArrayList[DataPlacement]()
		list.add(new DataPlacement(dest_host,dest_thrift,dest_sync,newDestRange))
		add(ns, list)
		logger.info("Changed assignments: "+dest_host +" gets: "+ newDestRange.start +" - "+newDestRange.end)
		logger.debug("Namespace entry size for "+ ns+ " : "+spaces(ns).size)

		// Sync keys that might have changed
		src.useConnection((c) => c.sync_set(ns, rset, dest_host+":"+dest_sync, conflictPolicy))
	}
	def move(ns: String, rset: RecordSet, src_host: String, src_thrift: Int, src_sync: Int, dest_host: String, dest_thrift: Int, dest_sync: Int) {
		val target_range = rangeSetToKeyRange(rset.range)
		val oldDestRange = if ( isValidDataPlacement(lookup_node(ns,dest_host,dest_thrift,dest_sync)) ) {rangeSetToKeyRange(lookup_node(ns,dest_host,dest_thrift,dest_sync).rset.range)} else {KeyRange.EmptyRange}
		val newDestRange = oldDestRange + target_range
		val src = new StorageNode(src_host,src_thrift,src_sync)

		// Verify the src has our keyRange and set up new range
		val compare_range = rangeSetToKeyRange(lookup_node(ns, src_host,src_thrift,src_sync).rset.range)
		logger.debug("Verifying node "+src_host+" with range "+compare_range.start +" - "+compare_range.end+" contains "+ target_range.start +" - "+ target_range.end)
		assert( (compare_range & target_range) == target_range )
		val newSrcRange = compare_range - target_range

		// Tell the servers to move the data
		logger.info("Moving "+ target_range.start + " - "+ target_range.end +" from "+src_host + " to "+ dest_host)
		src.useConnection((c) => c.copy_set(ns, rset, dest_host+":"+dest_sync))

		// Change the assignment
		val list = new java.util.ArrayList[DataPlacement]()
		list.add(new DataPlacement(dest_host,dest_thrift,dest_sync,newDestRange))
		list.add(new DataPlacement(src_host,src_thrift,src_sync,newSrcRange))
		add(ns, list)
		logger.info("Changed assignments: "+dest_host +" gets: "+ newDestRange.start +" - "+newDestRange.end+"\n"+src_host+ " gets: "+ newSrcRange.start +" - "+newSrcRange.end)
		logger.debug("Namespace entry size for "+ ns+ " : "+spaces(ns).size)

		// Sync and remove moved range from source
		src.useConnection((c) => c.sync_set(ns, rset, dest_host+":"+dest_sync, conflictPolicy) )
		src.useConnection((c) => c.remove_set(ns, rset) )
	}

	def remove(ns: String, entries:java.util.List[DataPlacement]):Boolean = {
		writelock.lock
		try {
			if (!spaces.contains(ns)) return true
			val iter = entries.iterator
			var entry:DataPlacement = null
			val toRemove = new java.util.LinkedList[DataPlacement]

			while (iter.hasNext) {
				entry = iter.next
				val candidate = lookup_node(ns, entry.node, entry.thriftPort, entry.syncPort)
				if (isValidDataPlacement(candidate)) {
					val node = new StorageNode(entry.node,entry.thriftPort,entry.syncPort)

					// before removing, sync range with other nodes that have overlapping range
					val others = lookup_range(ns, entry.rset.range)
					val others_iter = others.iterator
					while (others_iter.hasNext) {
						val other_entry = others_iter.next
						if ( (entry.node+":"+entry.syncPort) != (other_entry.node+":"+other_entry.syncPort)) {
							logger.debug("Syncing "+entry.node+" and "+ other_entry.node)
							node.useConnection((c) => c.sync_set(ns, entry.rset, other_entry.node+":"+other_entry.syncPort, conflictPolicy) )
						}
					}

					// now remove range from the storage node
					node.useConnection((c) => c.remove_set(ns, entry.rset))
				}
				toRemove.add(candidate)
				logger.debug("Removing "+ toRemove.size +" entries from namespace "+ns)
			}
			val ret = spaces(ns).removeAll(toRemove)
			writelock.unlock
			ret
		}
		finally writelock.unlock
	}
}

case class RunnableDataPlacementServer(port:Int) extends Runnable {
	val serverthread = new Thread(this, "DataPlacementServer-" + port)
	serverthread.start

	def run() {
		val logger = Logger.getLogger("placement.dataplacementserver")
		try {
			val serverTransport = new TNonblockingServerSocket(port)
	    	val processor = new KnobbedDataPlacementServer.Processor(new SimpleKnobbedDataPlacementServer)
			val protFactory =
				if (System.getProperty("xtrace")!=null) {new XtBinaryProtocol.Factory(true, true)} else {new TBinaryProtocol.Factory(true, true)}
	    	val options = new THsHaServer.Options
			options.maxWorkerThreads=4
			options.minWorkerThreads=2
			val server = new THsHaServer(processor, serverTransport,protFactory,options)

			if (System.getProperty("xtrace")!=null) { logger.info("Starting data placement with xtrace enabled") }
			logger.info("Starting data placement server on "+port)
	    	server.serve()
	  	} catch {
	    	case x: Exception => x.printStackTrace()
	  	}
	}
}

