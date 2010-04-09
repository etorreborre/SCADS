package edu.berkeley.cs.scads.piql.parser

import scala.collection.mutable.HashMap
import scala.collection.jcl.Conversions._

import org.apache.avro.Schema

object NoDuplicateMap {
	def apply[A,B](pairs: List[(A,B)]) = {
		Map(pairs:_*)
	}
}

/* SCADS Spec that has been bound and is ready to be optimized */
case class BoundSpec(entities: Map[String, BoundEntity], orphanQueries: HashMap[String, BoundQuery])

/* BoundEntity and any queries that depend on its ThisParameter */
case class BoundEntity(name: String, keySchema: Schema, valueSchema: Schema, relationships: List[BoundRelationship]) {
	val queries = new HashMap[String, BoundQuery]()
	val indexes = new scala.collection.mutable.ArrayBuffer[Index]()

  def namespace: String = "ent_" + name

	def attributes: Map[String, Schema] = Map(keySchema.getFields.map(f => f.name -> f.schema) ++ valueSchema.getFields.map(f => f.name -> f.schema):_*)
}

/* Bound Relationship */
abstract sealed class RelationshipSide
object ForeignKeyHolder extends RelationshipSide
object ForeignKeyTarget extends RelationshipSide
case class BoundRelationship(name: String, target: String, cardinality: Cardinality, side: RelationshipSide)

/* Joins */
abstract sealed class BoundJoin
abstract class ActualJoin extends BoundJoin {val name: String; val child: BoundFetch}
abstract class FixedCardinalityJoin extends ActualJoin
case class BoundPointerJoin(name: String, child: BoundFetch) extends FixedCardinalityJoin
case class BoundFixedTargetJoin(name: String, cardinality: Int, child: BoundFetch) extends FixedCardinalityJoin
case class BoundInfiniteTargetJoin(name: String, child: BoundFetch) extends ActualJoin
object NoJoin extends BoundJoin

/* Ordering */
abstract sealed class BoundOrder
case class Sorted(attribute: String, ascending: Boolean) extends BoundOrder
object Unsorted extends BoundOrder

/* BoundQuery and FetchTree */
case class BoundQuery(fetchTree: BoundFetch, parameters: List[BoundParameter], range:BoundRange) {var plan: QueryPlan = null}
case class BoundFetch(entity: BoundEntity, predicates: List[BoundPredicate], order: BoundOrder, join: BoundJoin)

abstract sealed class BoundRange
case class BoundLimit(lim: BoundValue, max: Int) extends BoundRange
object BoundUnlimited extends BoundRange

/* Bound Values */
abstract class BoundValue {
  val schema: Schema
}
case class BoundParameter(name: String, schema: Schema) extends BoundValue
case class BoundThisAttribute(name: String, schema: Schema) extends BoundValue

object BoundTrueValue extends BoundValue {
	val schema = Schema.create(Schema.Type.BOOLEAN)
}
object BoundFalseValue extends BoundValue {
	val schema = Schema.create(Schema.Type.BOOLEAN)
}

case class BoundIntegerValue(value: Int) extends BoundValue {
	val schema = Schema.create(Schema.Type.INT)
}
case class BoundStringValue(value: String) extends BoundValue {
	val schema = Schema.create(Schema.Type.STRING)
}

/* Bound Predicates */
abstract class BoundPredicate
case class AttributeEqualityPredicate(attributeName: String, value: BoundValue) extends BoundPredicate
