package edu.berkeley.cs
package scads
package piql
package opt

import plans._
import storage.client.index._

import org.apache.avro.Schema
import org.apache.avro.Schema.Field
import org.apache.avro.util.Utf8
import scala.collection.JavaConversions._
import net.lag.logging.Logger

case class ImplementationLimitation(desc: String) extends Exception

class OptimizedQuery(val name: Option[String], val physicalPlan: QueryPlan, executor: QueryExecutor, val logicalPlan: Option[LogicalPlan] = None) {
  def apply(args: Any*): QueryResult = {
    try {
      storage.pushTag(name.getOrElse("query"))
      val encodedArgs = args.map {
        case s: String => new Utf8(s)
        case o => o
      }
      val iterator = executor(physicalPlan, encodedArgs: _*)
      iterator.open
      val ret = iterator.toList
      iterator.close
      ret
    } finally {
      storage.popTag
    }
  }

  def toHtml: xml.NodeSeq = {
    <b>
      {physicalPlan}
    </b>
  }
}

object Optimizer {
  val logger = Logger()
  val defaultFetchSize = 10

  def apply(logicalPlan: LogicalPlan): QueryPlan = {
    logger.info("Optimizing subplan: %s", logicalPlan)

    logicalPlan match {
      case IndexRange(equalityPreds, None, None, None, r: Relation) if ((equalityPreds.size == r.keySchema.getFields.size) && isPrefix(equalityPreds.map(_.attribute), r)) => {
        logger.debug("Case 0")
        IndexLookup(r, makeKeyGenerator(r, equalityPreds))
      }
      case IndexRange(equalityPreds, Some(TupleLimit(count, dataStop)), None, None, r: Relation) => {
        logger.debug("Case 1")
        if (isPrefix(equalityPreds.map(_.attribute), r)) {
          logger.info("Using primary index for predicates: %s", equalityPreds)
          val idxScanPlan = IndexScan(r, makeKeyGenerator(r, equalityPreds), count, true)
          val fullPlan = dataStop match {
            case true => idxScanPlan
            case false => LocalStopAfter(count, idxScanPlan)
          }
          fullPlan
        } else {
          logger.info("Using secondary index for predicates: %s", equalityPreds)

          val idx = r.index(equalityPreds.map(_.attribute))
          val idxScanPlan = IndexScan(idx, makeKeyGenerator(idx, equalityPreds), count, true)
          val derefedPlan = derefPlan(r, idxScanPlan)

          val fullPlan = dataStop match {
            case true => derefedPlan
            case false => LocalStopAfter(count, derefedPlan)
          }
          fullPlan
        }
      }
      case IndexRange(equalityPreds, bound, Some(Ordering(attrs, asc)), rangeConstraint, r: Relation) => {
        logger.debug("Case 2")
        val limitHint = bound.map(_.count).getOrElse {
          logger.warning("UnboundedPlan %s: %s", r, logicalPlan)
          FixedLimit(defaultFetchSize)
        }
        val isDataStop = bound.map(_.isDataStop).getOrElse(true)
        val prefixAttrs = equalityPreds.map(_.attribute) ++ attrs
        logger.debug("Prefix attrs are %s", prefixAttrs)
        val idxScanPlan =
          // Checks that equality preds come before attrs for rangeConstraints.
          if ((!rangeConstraint.isDefined || isPrefix(equalityPreds.map(_.attribute), r)) && isPrefix(prefixAttrs, r)) {
            IndexScan(r, makeKeyGenerator(r, equalityPreds), limitHint, asc, rangeConstraint)
          }
          else if (isPrefix(prefixAttrs, r)) {
            val idx = r.index(prefixAttrs)
            IndexScan(idx, makeKeyGenerator(idx, equalityPreds), limitHint, asc, rangeConstraint)
          }
          else {
            logger.debug("Creating index for attributes: %s", prefixAttrs)
            val idx = r.index(prefixAttrs)
            derefPlan(
              r,
              IndexScan(idx,
                makeKeyGenerator(idx, equalityPreds),
                limitHint,
                asc,
                rangeConstraint))
          }

        val fullPlan = isDataStop match {
          case true => idxScanPlan
          case false => LocalStopAfter(limitHint, idxScanPlan)
        }
        fullPlan
      }
      case IndexRange(equalityPreds, limit, None, None, Join(child, r: Relation))
        if (equalityPreds.size == r.keySchema.getFields.size) &&
          isPrefix(equalityPreds.map(_.attribute), r) => {
        logger.debug("Case 3")
        val optChild = apply(child)
        val plan = IndexLookupJoin(r, makeKeyGenerator(r, equalityPreds), optChild)
        limit match {
          case Some(TupleLimit(c, false)) => LocalStopAfter(c, plan)
          case _ => plan
        }
      }
      case IndexRange(equalityPreds, Some(TupleLimit(count, dataStop)), None, None, Join(child, r: Relation)) => {
        logger.debug("Case 4")
        val prefixAttrs = equalityPreds.map(_.attribute)
        val optChild = apply(child)

        val joinPlan =
          if (isPrefix(prefixAttrs, r)) {
            IndexMergeJoin(r,
              makeKeyGenerator(r, equalityPreds),
              Nil,
              count,
              true,
              optChild)
          } else {
            val idx = r.index(prefixAttrs)
            val idxJoinPlan = IndexMergeJoin(idx,
              makeKeyGenerator(idx, equalityPreds),
              Nil,
              count,
              true,
              optChild)
            derefPlan(r, idxJoinPlan)
          }

        val fullPlan = dataStop match {
          case true => joinPlan
          case false => LocalStopAfter(count, joinPlan)
        }

        fullPlan
      }
      case IndexRange(equalityPreds, Some(TupleLimit(count, dataStop)), Some(Ordering(attrs, asc)), rangeConstraint, Join(child, r: Relation)) => {
        logger.debug("Case 5")
        val prefixAttrs = equalityPreds.map(_.attribute) ++ attrs
        val optChild = apply(child)

        val joinPlan =
          if (isPrefix(prefixAttrs, r)) {
            logger.debug("Using index special orders for %s", attrs)
            IndexMergeJoin(r,
              makeKeyGenerator(r, equalityPreds),
              attrs,
              count,
              asc,
              optChild,
              rangeConstraint)
          } else {
            val idx = r.index(prefixAttrs)
            val idxJoinPlan = IndexScanJoin(idx,
              makeKeyGenerator(idx, equalityPreds),
              count,
              asc,
              optChild,
              rangeConstraint)
            derefPlan(r, idxJoinPlan)
          }

        val fullPlan = dataStop match {
          case true => joinPlan
          case false => LocalStopAfter(count, joinPlan)
        }

        fullPlan
      }
      case Selection(pred, child) => {
        LocalSelection(pred, apply(child))
      }
      case Project(values, child, optSchema) => {
        val schema = optSchema.getOrElse({
          val tmp = Schema.createRecord("LocalProjection", null, null, false)
          tmp.setFields(values.map(_ match {
            case QualifiedAttributeValue(relation, field) =>
              new Schema.Field(field.name, field.schema, field.doc, null)
            case ParameterValue(i, typ) =>
              new Schema.Field("param_" + i, Schema.create(typ.getOrElse(throw new ImplementationLimitation("must specify type to project parameters"))), "", null)
            case other =>
              throw new ImplementationLimitation("cannot project this type of field")
          }))
          tmp
        })

        LocalProjection(values, apply(child), schema)
      }
      case lt @ LocalTuples(ord, _, _, _) =>
        LocalIterator(ord, lt, false)
    }
  }

  protected def derefPlan(r: Relation, idxPlan: RemotePlan): QueryPlan = {
    val idxAttrMap = idxPlan.namespace.keyAttributes.map(a => (a.fieldName, a)).toMap
    logger.debug("idxMap: %s", idxAttrMap)
    val keyGenerator = r.keyAttributes.map(_.fieldName).map(idxAttrMap)
    IndexLookupJoin(r, keyGenerator, idxPlan)
  }

  /**
   * Returns true only if the given equality predicates can be satisfied by a prefix scan
   * over the given namespace
   */
  protected def isPrefix(attrs: Seq[Value], ns: Relation): Boolean = {
    val primaryKeyAttrs = ns.keyAttributes.take(attrs.size)
    attrs.map(primaryKeyAttrs.contains(_)).reduceLeft(_ && _)
  }



  /**
   * Given a namespace and a set of attribute equality predicates return
   * at the keyGenerator
   */
  protected def makeKeyGenerator(ns: TupleProvider, equalityPreds: Seq[AttributeEquality]): KeyGenerator = {
    ns.keySchema.getFields.take(equalityPreds.size).map(f => {
      logger.info("Looking for key generator value for field %s in %s", f.name, equalityPreds)
      val value = equalityPreds.find(_.attribute.fieldName equals f.name).getOrElse(throw new ImplementationLimitation("Invalid prefix")).value
      value
    })
  }

  case class AttributeEquality(attribute: QualifiedAttributeValue, value: Value)

  case class Ordering(attributes: Seq[QualifiedAttributeValue], ascending: Boolean)

  case class TupleLimit(count: Limit, isDataStop: Boolean)

  /**
   * Groups sets of logical operations that can be executed as a
   * single get operations against the key value store
   */
  protected object IndexRange {
    def unapply(logicalPlan: LogicalPlan): Option[(Seq[AttributeEquality], Option[TupleLimit], Option[Ordering], Option[RangeConstraint], LogicalPlan)] = {
      val (limit, planWithoutStop) = logicalPlan match {
        case Paginate(count, child) => (Some(TupleLimit(count, false)), child)
        case StopAfter(count, child) => (Some(TupleLimit(count, false)), child)
        case DataStopAfter(count, child) => (Some(TupleLimit(count, true)), child)
        case otherOp => (None, otherOp)
      }

      val (ordering, rangeConstraint: Option[RangeConstraint], planWithoutSort) = planWithoutStop match {
        case Sort(attrs, asc, child) if (attrs.map(_.isInstanceOf[QualifiedAttributeValue]).reduceLeft(_ && _)) =>
          (Some(Ordering(attrs.asInstanceOf[Seq[QualifiedAttributeValue]], asc)), None, child)
        case SortedRange(attr, constraint, child) =>
          (Some(Ordering(attr.asInstanceOf[QualifiedAttributeValue] :: Nil, true)), Some(constraint), child)
        case otherOp => (None, None, otherOp)
      }

      val (predicates, planWithoutPredicates) = planWithoutSort.gatherUntil {
        case Selection(pred, _) => pred
      }

      val basePlan = planWithoutPredicates.getOrElse {
        logger.info("IndexRange match failed.  No base plan")
        return None
      }

      val relation = basePlan match {
        case r: Relation => r
        case Join(_, r: Relation) => r
        case otherOp => {
          logger.info("IndexRange match failed.  Invalid base plan: %s", otherOp)
          return None
        }
      }

      val idxEqPreds = predicates.map {
        case EqualityPredicate(v: Value, a@QualifiedAttributeValue(r, f)) if r == relation =>
          AttributeEquality(a, v)
        case EqualityPredicate(a@QualifiedAttributeValue(r, f), v: Value) if r == relation =>
          AttributeEquality(a, v)
        case otherPred => {
          logger.info("IndexScan match failed.  Can't apply %s to index scan of %s.{%s}", otherPred, relation, relation.keyAttributes)
          return None
        }
      }

      val getOp = (idxEqPreds, limit, ordering, rangeConstraint, basePlan)
      logger.info("Matched IndexRange%s", getOp)
      Some(getOp)
    }
  }

}
