package edu.berkeley.cs.scads.storage

import edu.berkeley.cs.scads.comm._

import scala.collection.mutable.ListBuffer

import net.lag.logging.Logger

sealed trait UpdateInfo
case class VersionUpdateInfo(servers: Seq[PartitionService],
                             key: Array[Byte],
                             rec: Option[Array[Byte]]) extends UpdateInfo
case class ValueUpdateInfo(servers: Seq[PartitionService],
                           key: Array[Byte],
                           rec: Option[Array[Byte]]) extends UpdateInfo
case class LogicalUpdateInfo(servers: Seq[PartitionService],
                             key: Array[Byte],
                             schema: String,
                             rec: Option[Array[Byte]]) extends UpdateInfo

// TODO: Worry about thread safety?
class UpdateList {
  private val updateList = new ListBuffer[UpdateInfo]

  def appendVersionUpdate(servers: Seq[PartitionService],
                          key: Array[Byte],
                          rec: Option[Array[Byte]]) = {
    updateList.append(VersionUpdateInfo(servers, key, rec))
  }

  def appendLogicalUpdate(servers: Seq[PartitionService],
                          key: Array[Byte],
                          schema: String,
                          rec: Option[Array[Byte]]) = {
    updateList.append(LogicalUpdateInfo(servers, key, schema, rec))
  }

  def getUpdateList() = {
    updateList.readOnly
  }

  def print() {
    println("len: " + updateList.length)
    updateList.foreach(x => println(x))
  }
}
