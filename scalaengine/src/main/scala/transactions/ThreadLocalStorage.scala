package edu.berkeley.cs.scads.storage

import scala.util.DynamicVariable

import net.lag.logging.Logger

object ThreadLocalStorage {
  // Records updated within the tx.
  val updateList = new DynamicVariable[Option[UpdateList]](None)

  // Records read from within the tx.
  val txReadList = new DynamicVariable[Option[ReadList]](None)
}
