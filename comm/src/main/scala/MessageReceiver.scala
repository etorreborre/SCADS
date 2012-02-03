package edu.berkeley.cs.scads.comm

import scala.actors.Actor

import net.lag.logging.Logger
import org.apache.avro.generic.IndexedRecord
import java.util.concurrent.{ArrayBlockingQueue, PriorityBlockingQueue, ThreadPoolExecutor, CountDownLatch, TimeUnit}
import _root_.org.fusesource.hawtdispatch._

trait MessageReceiver[MessageType <: IndexedRecord] {
  def receiveMessage(src: Option[RemoteServiceProxy[MessageType]], msg: MessageType): Unit
  def unregistered: Unit
}

//TODO: remove extra envelope object creation
case class Envelope[MessageType <: IndexedRecord](src: Option[RemoteServiceProxy[MessageType]], msg: MessageType)


class ActorReceiver[MessageType <: IndexedRecord](actor: Actor) extends MessageReceiver[MessageType] {
  def receiveMessage(src: Option[RemoteServiceProxy[MessageType]], msg: MessageType): Unit = {
    actor ! Envelope(src.asInstanceOf[Option[RemoteService[MessageType]]], msg)
  }

  def unregistered = null
}

class FastMailboxDispatchReceiver[MessageType <: IndexedRecord](processFn: Mailbox[MessageType] => Unit,
                                                                val receiverMailbox : Mailbox[MessageType] = new PlainMailbox[MessageType]("Mailbox") ) extends MessageReceiver[MessageType] {
  private val logger = Logger(classOf[FastMailboxDispatchReceiver[IndexedRecord]])

  val senderMailbox = new PlainMailbox[MessageType]("SenderMailbox")
  val newMessages : Boolean = false

  val dispatcher = createQueue()
  dispatcher.profile(true)
  val stats : Int = 10
  monitor_hawtdispatch()


  def monitor_hawtdispatch() :Unit = {

    import java.util.concurrent.TimeUnit._

    // do the actual check in 1 second..
    getGlobalQueue().after(5, SECONDS) {
      logger.debug( "Maibox Stats: Hash: %s Name: %s Size: %s Stats: %s", receiverMailbox.hashCode(), receiverMailbox.getName,  receiverMailbox.size, dispatcher.metrics)
      // to check again...
      monitor_hawtdispatch
    }
  }


  class MessageReceive() extends Runnable{
    def run(): Unit = {
      processFn(getMailbox())
    }
  }

  def getMailbox() : Mailbox[MessageType]  = {
    senderMailbox.synchronized{
      senderMailbox.drainTo(receiverMailbox)
      logger.debug("Drained mailbox. Hash: %s Name: %s Size: %s Content: %s", receiverMailbox.hashCode(), receiverMailbox.getName,  receiverMailbox.size, receiverMailbox.toString )
    }

    receiverMailbox
  }

  def receiveMessage(src: Option[RemoteServiceProxy[MessageType]], msg: MessageType): Unit = {
    senderMailbox.synchronized{
      if(senderMailbox.isEmpty){
       senderMailbox.add(src.asInstanceOf[Option[RemoteService[MessageType]]], msg)
       dispatcher.execute(new MessageReceive())
       logger.debug("Added Msg and triggered dispatcher. Hash: %s Name: %s Sender-Size: %s Receiver-Size: %s, Sender-Mailbox: %s", receiverMailbox.hashCode(), receiverMailbox.getName, receiverMailbox.size, senderMailbox.size, senderMailbox.toString)
      }else{
        //println(this.toString + ": Sender Mailbox " + senderMailbox.size)
        senderMailbox.add(src.asInstanceOf[Option[RemoteService[MessageType]]], msg)
         logger.debug("Only added message. Hash: %s Name: %s Sender-Size: %s Receiver-Size: %s, Sender-Mailbox: %s", receiverMailbox.hashCode(), receiverMailbox.getName, receiverMailbox.size, senderMailbox.size, senderMailbox.toString)
        //dispatcher.execute(new MessageReceive())
      }
    }
  }

  def unregistered = dispatcher.suspend()
}

class MailboxDispatchReceiver[MessageType <: IndexedRecord](prioFn : MessageType => Int,
                                                            processFn: Mailbox[MessageType] => Unit) extends MessageReceiver[MessageType] {
  val mailbox = new PriorityBlockingMailbox[MessageType](prioFn)

  val queue = createQueue()

  class MessageReceive() extends Runnable{
    def run(): Unit = processFn(mailbox)
  }

  def receiveMessage(src: Option[RemoteServiceProxy[MessageType]], msg: MessageType): Unit = {
    mailbox.add(src.asInstanceOf[Option[RemoteService[MessageType]]], msg)
    queue.execute(new MessageReceive())
  }

  def unregistered = queue.suspend()
}

class DispatchReceiver[MessageType <: IndexedRecord](f: (Envelope[MessageType]) => Unit) extends MessageReceiver[MessageType] {
  val queue = Dispatch.createQueue()

  class MessageReceive(msg: Envelope[MessageType]) extends Runnable{
    def run(): Unit = f(msg)
  }

  def receiveMessage(src: Option[RemoteServiceProxy[MessageType]], msg: MessageType): Unit = {
    queue.execute(new MessageReceive(Envelope(src.asInstanceOf[Option[RemoteService[MessageType]]], msg)))
  }

  def unregistered = queue.suspend()
}

/**
 * Executes requests of type MessageType on a threadpool by calling a user
 * supplied process method.
 *
 * WARNING: Currently, invocations to `startup` run in the constructor of
 * ServiceHandler. This means that subclasses will not be properly constructed
 * (its constructor will not have run yet) when `startup` is invoked. To get
 * around this issue, any members of a subclass which need to be referenced in
 * `startup` <b>cannot</b> be vals (or NPE will ensue). Either use lazy vals, or
 * methods.
 *
 * TODO: fix this problem
 */
abstract trait ServiceHandler[MessageType <: IndexedRecord] extends MessageReceiver[MessageType] {
  protected val logger: Logger
  def registry: ServiceRegistry[MessageType]

  def unregistered = null

  /* Threadpool for execution of incoming requests */
  protected val outstandingRequests = new ArrayBlockingQueue[Runnable](1024) // TODO: read from config
  protected val executor = new ThreadPoolExecutor(5, 20, 30, TimeUnit.SECONDS, outstandingRequests)

  /* Latch for waiting for startup to finish */
  private val startupGuard = new CountDownLatch(1)

  /* Registration (publication of this instance) must happen after
   * initialization statements above. otherwise, NPE will ensue if a message
   * arrives immediately (since the thread pool has not been initialized yet,
   * etc) */
  implicit lazy val remoteHandle = registry.registerService(this)

  // TODO: use an explicit startup pattern - see warning message above
  startup()
  startupGuard.countDown()

  /* signals startup completion */

  /* Guaranteed that no invocations to process will occur during method */
  protected def startup(): Unit

  /* Guaranteed that no invocations to process will occur during method */
  protected def shutdown(): Unit

  /* Callback for when a message is received */
  protected def process(src: Option[RemoteServiceProxy[MessageType]], msg: MessageType): Unit

  def stop {
    stopListening
    executor.shutdownNow()
    shutdown()
  }

  /**
   * Un-registers this ServiceHandler from the MessageHandler. After calling
   * stopListening, this ServiceHandler will no longer receive new requests.
   * However, its resources will remain open until stop is called explicitly
   */
  def stopListening {
    startupGuard.await() /* Let the service start up properly first, before shutting down */
    registry.unregisterService(remoteHandle)
  }

  /* Request handler class to be executed on this StorageHandlers threadpool */
  class Request(src: Option[RemoteServiceProxy[MessageType]], msg: MessageType) extends Runnable {
    def run(): Unit = {
      try {
        startupGuard.await(); process(src, msg)
      } catch {
        case e: Throwable => {
          /* Get the stack trace */
          val stackTrace = e.getStackTrace().mkString("\n")
          /* Log and report the error */
          logger.error(e, "Exception processing storage request")
          //TODO: fix me! src.foreach(_ ! ProcessingException(e.toString, stackTrace))
        }
      }
    }
  }

  /* Enque a recieve message on the threadpool executor */
  final def receiveMessage(src: Option[RemoteServiceProxy[MessageType]], msg: MessageType): Unit = {
    try executor.execute(new Request(src, msg)) catch {
      case ree: java.util.concurrent.RejectedExecutionException => //TODO: Fix me: src.foreach(_ ! RequestRejected("Thread Pool Full", msg))
      case e: Throwable => {
        /* Get the stack trace */
        var stackTrace = e.getStackTrace().mkString("\n")
        /* Log and report the error */
        logger.warning(e, "Exception enquing storage request for execution")
        //TODO Fixme: src.foreach(_ ! ProcessingException(e.toString(), stackTrace))
      }
    }
  }
}
