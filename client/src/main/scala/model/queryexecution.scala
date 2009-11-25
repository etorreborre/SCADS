package edu.berkeley.cs.scads.model

import scala.collection.mutable.HashMap
import org.apache.log4j.Logger

import edu.berkeley.cs.scads.thrift.StorageNode
import edu.berkeley.cs.scads.storage.RangedPolicy

abstract sealed class JoinCondition
case class AttributeCondition(attrName: String) extends JoinCondition
case class FieldLiteralCondition(fieldValue: Field) extends JoinCondition

abstract trait QueryExecutor {
	val qLogger = Logger.getLogger("scads.queryexecution")
	/* Type Definitions */
	type TupleStream = Seq[Tuple]
	type EntityStream = Seq[Entity]
	type LimitValue = Field

	/* Metadata Catalog */
	protected val nsKeys: Map[String, List[Class[Field]]]
	protected val nsVersions: Map[String, Boolean]

	/* Tuple Providers */
	protected def singleGet(namespace: String, key: Field, policy: ReadPolicy)(implicit env: Environment): TupleStream = {
		List(policy.get(namespace, key.serializeKey, nsKeys(namespace), nsVersions(namespace)))
	}

	protected def prefixGet(namespace: String, prefix: Field, limit: LimitValue, ascending: Boolean, policy: ReadPolicy)(implicit env: Environment): TupleStream = {
		val serializedPrefix = prefix.serializeKey
		if(ascending)
			policy.get_set(namespace, serializedPrefix, serializedPrefix + "~", limitToInt(limit), nsKeys(namespace), nsVersions(namespace))
		else
			policy.get_set(namespace, serializedPrefix + "~", serializedPrefix, limitToInt(limit), nsKeys(namespace), nsVersions(namespace))
	}

	protected def sequentialDereferenceIndex(targetNamespace: String, policy: ReadPolicy, child: TupleStream)(implicit env: Environment): TupleStream = {
		child.map((t) => {
			policy.get(targetNamespace, t.value, nsKeys(targetNamespace), nsVersions(targetNamespace))
		})
	}

	protected def prefixJoin(namespace: String, conditions: List[JoinCondition], limit: LimitValue, ascending: Boolean, policy: ReadPolicy, child: EntityStream)(implicit env: Environment): TupleStream = {
		child.flatMap((e) => {
			val prefix = CompositeField(conditions.map(_ match {
				case AttributeCondition(attr) => e.attributes(attr)
				case FieldLiteralCondition(f) => f
			}): _*).serializeKey

			if(ascending)
				policy.get_set(namespace, prefix, prefix + "~", limitToInt(limit), nsKeys(namespace), nsVersions(namespace))
			else
				policy.get_set(namespace, prefix + "~", prefix, limitToInt(limit), nsKeys(namespace), nsVersions(namespace))
		})
	}

	protected def pointerJoin(namespace: String, conditions: List[JoinCondition], policy: ReadPolicy, child: EntityStream)(implicit env: Environment): TupleStream = {
		child.map((e) => {
				val key = CompositeField(conditions.map(_ match {
					case AttributeCondition(attr) => e.attributes(attr)
					case FieldLiteralCondition(f) => f
				}): _*).serializeKey

				policy.get(namespace, key, nsKeys(namespace), nsVersions(namespace))
		})
	}

	/* Entity Providers */
	protected def materialize[EntityType <: Entity](entityClass: Class[EntityType], child: TupleStream)(implicit env: Environment): Seq[EntityType] = {
		child.map((t) => {
			val entity = entityClass.getConstructors()(0).newInstance(env).asInstanceOf[EntityType]
			entity.deserializeAttributes(t.value)
			entity
		})
	}

	protected def selection[EntityType <: Entity](equalityMap: HashMap[String, Field], child: Seq[EntityType]): Seq[EntityType] = {
		child.filter((e) => {
			equalityMap.foldLeft(true)((value: Boolean, equality: (String, Field)) => {
					value && (e.attributes(equality._1) == equality._2)
				})
		})
	}

	protected def sort[EntityType <: Entity](fields: List[String], ascending: Boolean, child: Seq[EntityType]): Seq[EntityType] = {
		if(ascending)
			child.toList.sort((e1, e2) => {
				(fields.map(e1.attributes).map(_.serializeKey).mkString("", "", "") compare fields.map(e2.attributes).map(_.serializeKey).mkString("", "", "")) < 0
			})
		else
			child.toList.sort((e1, e2) => {
				(fields.map(e1.attributes).map(_.serializeKey).mkString("", "", "") compare fields.map(e2.attributes).map(_.serializeKey).mkString("", "", "")) > 0
			})
	}

	protected def topK[EntityType <: Entity](k: Field, child: Seq[EntityType]): Seq[EntityType] = {
		child.slice(0, limitToInt(k))
	}

	/* Helper functions */
	private def limitToInt(lim: LimitValue): Int = lim match {
		case i: IntegerField => i.value
		case _ => throw new IllegalArgumentException("Only integerFields are accepted as limit parameters")
	}

  def configureStorageEngine(n: StorageNode): Unit = {
    n.useConnection(c => {
      nsKeys.keys.foreach(ns => {
        c.set_responsibility_policy(ns, RangedPolicy.convert(List((null, null))))
      })
    })
  }
}

/* Query Plan Nodes */
abstract sealed class QueryPlan
abstract class TupleProvider extends QueryPlan
abstract class EntityProvider extends QueryPlan
case class SingleGet(namespace: String, key: Field, policy: ReadPolicy) extends TupleProvider
case class PrefixGet(namespace: String, prefix: Field, limit: Field, ascending: Boolean, policy: ReadPolicy) extends TupleProvider
case class SequentialDereferenceIndex(targetNamespace: String, policy: ReadPolicy, child: TupleProvider) extends TupleProvider
case class PrefixJoin(namespace: String, conditions: List[JoinCondition], limit: Field, ascending: Boolean, policy: ReadPolicy, child: EntityProvider) extends TupleProvider
case class PointerJoin(namespace: String, conditions: List[JoinCondition], policy: ReadPolicy, child: EntityProvider) extends TupleProvider
case class Materialize(entityClass: Class[Entity], child: TupleProvider) extends EntityProvider
case class Selection(equalityMap: HashMap[String, Field], child: EntityProvider) extends EntityProvider
case class Sort(fields: List[String], ascending: Boolean, child: EntityProvider) extends EntityProvider
case class TopK(k: Field, child: EntityProvider) extends EntityProvider
