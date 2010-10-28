package edu.berkeley.cs.scads.piql

import net.lag.logging.Logger

import scala.util.Random
import collection.mutable.HashMap
import collection.JavaConversions._

import ch.ethz.systems.tpcw.populate.data.Utils

import edu.berkeley.cs.avro.runtime._

/**
 * Warning: Workflow is not threadsafe for now
 */
class TpcwWorkflow(val loader: TpcwLoader, val randomSeed: Option[Int] = None) {

  private val logger = Logger("edu.berkeley.cs.scads.piql.TpcwWorkflow")

  val random = randomSeed.map(new Random(_)).getOrElse(new Random)

  /** All possible subjects */
  val subjects = Utils.getSubjects

  /** All possible ship types */
  val shipTypes = Utils.getShipTypes

  /** All possible credit card types */
  val ccTypes = Utils.getCreditCards

  case class Action(val action: ActionType.Value, var nextActions : List[(Int, Action)])

  object ActionType extends Enumeration {
    type ActionType = Value

    val Home, NewProduct, BestSeller, ProductDetail, SearchRequest, SearchResult, ShoppingCart,
    CustomerReg, BuyRequest, BuyConfirm, OrderInquiry, OrderDisplay, AdminRequest, AdminConfirm = Value

  }

  object SearchResultType extends Enumeration {
    val ByAuthor, ByTitle, BySubject = Value 
  }
  val SearchTypes = Vector(SearchResultType.ByAuthor,
                           SearchResultType.ByTitle,
                           SearchResultType.BySubject)
  def randomSearchType = 
    SearchTypes(random.nextInt(SearchTypes.size))

  def randomUser = 
    loader.toCustomer(random.nextInt(loader.numCustomers) + 1)

  def randomSubject =
    subjects(random.nextInt(subjects.length))

  def randomItem =
    loader.toItem(random.nextInt(loader.numItems) + 1)

  def randomAuthor = 
    loader.toAuthor(random.nextInt(loader.numAuthors) + 1)

  def randomShipType =
    shipTypes(random.nextInt(shipTypes.length))

  def randomCreditCardType = 
    ccTypes(random.nextInt(ccTypes.length))

  val actions = new HashMap[ActionType.ActionType, Action]
  ActionType.values.foreach(a => actions += a -> new Action(a, Nil))

  actions(ActionType.AdminConfirm).nextActions = List((8348, actions(ActionType.Home)))
  println("nb of actions " + actions.size)

//The MarkovChain from TPC-W
//actions(ActionType.AdminConfirm).nextActions = List ( (8348, actions(ActionType.Home)), (9999, actions(ActionType.SearchRequest)))
//actions(ActionType.AdminRequest).nextActions = List ( (8999, actions(ActionType.AdminConfirm)), (9999, actions(ActionType.Home)))
//actions(ActionType.BestSeller).nextActions = List ( (1, actions(ActionType.Home)), (333, actions(ActionType.ProductDetail)), (9998, actions(ActionType.SearchRequest)), (9999, actions(ActionType.ShoppingCart)))
//actions(ActionType.BuyConfirm).nextActions = List ( (2, actions(ActionType.Home)), (9999, actions(ActionType.SearchRequest)))
//actions(ActionType.BuyRequest).nextActions = List ( (7999, actions(ActionType.BuyConfirm)), (9453, actions(ActionType.Home)), (9999, actions(ActionType.ShoppingCart)))
//actions(ActionType.CustomerReg).nextActions = List ( (9899, actions(ActionType.BuyRequest)), (9901, actions(ActionType.Home)), (9999, actions(ActionType.SearchRequest)))
//actions(ActionType.Home).nextActions = List ( (499, actions(ActionType.BestSeller)), (999, actions(ActionType.NewProduct)), (1269, actions(ActionType.OrderInquiry)), (1295, actions(ActionType.SearchRequest)), (9999, actions(ActionType.ShoppingCart)))
//actions(ActionType.NewProduct).nextActions = List ( (504, actions(ActionType.Home)), (9942, actions(ActionType.ProductDetail)), (9976, actions(ActionType.SearchRequest)), (9999, actions(ActionType.ShoppingCart)))
//actions(ActionType.OrderDisplay).nextActions = List ( (9939, actions(ActionType.Home)), (9999, actions(ActionType.SearchRequest)))
//actions(ActionType.OrderInquiry).nextActions = List ( (1168, actions(ActionType.Home)), (9968, actions(ActionType.OrderDisplay)), (9999, actions(ActionType.SearchRequest)))
//actions(ActionType.ProductDetail).nextActions = List ( (99, actions(ActionType.AdminRequest)), (3750, actions(ActionType.Home)), (5621, actions(ActionType.ProductDetail)), (6341, actions(ActionType.SearchRequest)), (9999, actions(ActionType.ShoppingCart)))
//actions(ActionType.SearchRequest).nextActions = List ( (815, actions(ActionType.Home)), (9815, actions(ActionType.SearchResult)), (9999, actions(ActionType.ShoppingCart)))
//actions(ActionType.SearchResult).nextActions = List ( (486, actions(ActionType.Home)), (7817, actions(ActionType.ProductDetail)), (9998, actions(ActionType.SearchRequest)), (9999, actions(ActionType.ShoppingCart)))
//actions(ActionType.ShoppingCart).nextActions = List ( (9499, actions(ActionType.CustomerReg)), (9918, actions(ActionType.Home)), (9999, actions(ActionType.ShoppingCart)))
//

  actions(ActionType.Home).nextActions = List ( (5000, actions(ActionType.Home)), (9999, actions(ActionType.NewProduct)))
  actions(ActionType.NewProduct).nextActions = List ( (9999, actions(ActionType.Home)))


  private var nextAction = actions(ActionType.Home) // HOME is start state
  private var currentUserKey: CustomerKey = _
  private var currentUserValue: CustomerValue = _

  def executeMix() = {
    nextAction match {
      case Action(ActionType.Home, _) => {
        logger.debug("Home")
        val user = randomUser
        val userData = loader.client.homeWI(user)
        currentUserKey = userData(0)(0).toSpecificRecord[CustomerKey]
        currentUserValue = userData(0)(1).toSpecificRecord[CustomerValue]
      }
      case Action(ActionType.NewProduct, _) => {
        logger.debug("NewProduct")
        val subject = randomSubject
        loader.client.newProductWI(subject)
      }
      case Action(ActionType.ProductDetail, _) => 
        logger.debug("ProductDetail")
        val item = randomItem
        loader.client.productDetailWI(item)
      case Action(ActionType.SearchRequest, _) =>
        logger.debug("SearchRequest")
        // NO-OP, since this action is mostly concerned w/ presenting a user
        // w/ a form...
      case Action(ActionType.SearchResult, _) =>
        logger.debug("SearchResult")
        // pick a random search type
        randomSearchType match {
          case SearchResultType.ByAuthor =>
            val author = randomAuthor
            val name = random.nextBoolean match {
              case false => loader.toAuthorFname(author)
              case true => loader.toAuthorLname(author)
            }
            loader.client.searchByAuthorWI(name)
          case SearchResultType.ByTitle =>
            // for now, just pick a random string...
            // TODO: this really should be fixed- we should
            // seed the random gen used to create random strings,
            // so we actually have a hope of matching a title.
            val title = Utils.getRandomAString(14, 60)
            loader.client.searchByTitleWI(title)
          case SearchResultType.BySubject =>
            val subject = randomSubject
            loader.client.searchBySubjectWI(subject)
        }
      case Action(ActionType.ShoppingCart, _) =>
        logger.debug("ShoppingCart")
        assert(currentUserKey != null, "Current user should not be null")
        // generate, for a random user, 1 to 10 items to add to shopping cart.
        // each item will be added from 1 to 5 times
        val items = (1 to random.nextInt(10) + 1).map(_ => (randomItem, random.nextInt(5) + 1))
        loader.client.shoppingCartWI(currentUserKey.C_UNAME, items)
      case Action(ActionType.CustomerReg, _) =>
        logger.debug("CustomerReg")
        //TODO: when do we actually add new customers?

      case Action(ActionType.BuyRequest, _) =>
        logger.debug("BuyRequest")
        // for now always assuming existing user
        loader.client.buyRequestExistingWI(currentUserKey.C_UNAME, currentUserValue.C_PASSWD)

      case Action(ActionType.BuyConfirm, _) =>
        logger.debug("BuyConfirm")

        val cc_type = randomCreditCardType
        val cc_number = Utils.getRandomNString(16) 
        val cc_name = Utils.getRandomAString(14, 30) 
        val cc_expiry = {
          import java.util.Calendar
          val cal = Utils.getCalendar
          cal.add(Calendar.DAY_OF_YEAR, Utils.getRandomInt(10, 730))
          cal.getTime.getTime
        }
        val shipping = randomShipType

        loader.client.buyConfirmWI(
          currentUserKey.C_UNAME,
          cc_type,
          cc_number,
          cc_name,
          cc_expiry,
          shipping)
      case Action(ActionType.AdminRequest, _) =>
        logger.debug("AdminRequest")
        val item = randomItem
        loader.client.adminRequestWI(item)
      case Action(tpe, _) =>
        logger.error("Not supported: " + tpe)
    }

    val rnd = random.nextInt(9999)

    val action = nextAction.nextActions.find(rnd < _._1)
    assert(action.isDefined)
    nextAction = action.get._2

 }
}
