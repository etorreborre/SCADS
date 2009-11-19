package edu.berkeley.cs.scads.model.parser

import org.apache.log4j.Logger
import edu.berkeley.cs.scads.model.{SingleGet, Materialize}
import scala.collection.mutable.HashMap

object ScalaGen extends Generator[BoundSpec] {
	val logger = Logger.getLogger("scads.scalagen")
	val autogenWarning = "/* This is an autogenerated scads spec file, do not modify it unless you know what you are doing */"
	val imports = Array("import edu.berkeley.cs.scads.model")

	protected def generate(spec: BoundSpec)(implicit sb: StringBuilder, indnt: Indentation): Unit = {
		/* Headers */
		output("/* This is an autogenerated scads spec file, do not modify it unless you know what you are doing */")
		output("import edu.berkeley.cs.scads.model._")
		output("import scala.collection.mutable.HashMap")

		/* Metadata Catalog */
		def classList(aType: AttributeType): String = aType match {
			case BooleanType => "classOf[BooleanField].asInstanceOf[Class[Field]]"
			case StringType => "classOf[StringField].asInstanceOf[Class[Field]]"
			case IntegerType => "classOf[IntegerField].asInstanceOf[Class[Field]]"
			case CompositeType(parts) => parts.map(classList(_)).mkString("", ", ", "")
		}

		output("trait ApplicationQueryExecutor extends QueryExecutor {")
		indent {
			output("val nsKeys: Map[String, List[Class[Field]]] = Map(")
			indent {
				val keys = spec.entities.flatMap((e) => {
					e._2.indexes.map((i) => {
						"(\""  + i.namespace +  "\" -> List(" +  i.attributes.map(e._2.attributes).map(classList(_)).mkString("", ", ", "") + "))"
					})
				})
				output(keys.mkString("", ", ", ""))
			}
			output(")")

			output("val nsVersions: Map[String, Boolean] = Map(")
			indent {
				val versions = spec.entities.flatMap((e) => {
					e._2.indexes.map(_ match {
						case PrimaryIndex(ns, _) => "(\"" + ns + "\" -> true)"
						case SecondaryIndex(ns, _, _) => "(\"" + ns + "\" -> false)"
					})
				})
				output(versions.mkString("", ", ", ""))
			}
			output(")")
		}
		output("}")

		/* Entities */
		spec.entities.foreach((e) => {
			output("class ", e._1, "(implicit env: Environment) extends Entity()(env) with ApplicationQueryExecutor {")
			indent {
				/* Setup namespace and versioning system */
				output("val namespace = \"", Namespaces.entity(e._1), "\"")
				output("val version = new IntegerVersion()")

				/* Attribute holding objects */
				e._2.attributes.foreach((a) => {
					output("object ", a._1, " extends ", fieldType(a._2))
				})

				/* Attribute name to object map */
				output("val attributes = Map(")
				indent {
					val attrMap = e._2.attributes.keys.map((a) =>
						"(\"" + a + "\" -> " + a  +")").mkString("", ",\n", "")
					output(attrMap)

				}
				output(")")

				/* Index Creation */
				output("val indexes = Array[Index](")
        indent {
          output(e._2.indexes.flatMap(_ match {
            case SecondaryIndex(ns, attrs, _) => {
              List("new AttributeIndex(" + quote(ns) + ", this, CompositeField(" + attrs.mkString("", ",", "") + "))")
            }
            case _ => Nil
          }).mkString("", ", ", ""))
        }
        output(")")

				/* Primary Key */
				output("val primaryKey = ")
				indent {
					if(e._2.keys.size > 1)
						output("CompositeField(" + e._2.keys.mkString("", ",", ""), ")")
					else
						output(e._2.keys(0))
				}

				/* Ouput any queries for this entity */
				e._2.queries.foreach((q) => generateQuery(q._1, q._2))
			}
			output("}")

		})
		/* Output object for Orphan Queries */
		output("object Queries extends ApplicationQueryExecutor {")
		indent {
			spec.orphanQueries.foreach((q) => generateQuery(q._1, q._2))
		}
		output("}")
	}

	protected def generateQuery(name: String, query: BoundQuery)(implicit sb: StringBuilder, indnt: Indentation) {
		val args = query.parameters.map((p) => {p.name + ": " + toScalaType(p.aType)}).mkString("", ",", "")

		output("def ", name, "(", args, ")(implicit env: Environment):Seq[", query.fetchTree.entity.name, "] = {")
		indent {
			if(query.plan == null)
				output("null")
			else {
				generatePlan(query.plan)
			}
		}
		output("}")
	}

	def argToCode(arg: Any): String = arg match {
		case c: Class[_] => "classOf[" + c.getName + "]"
		case s: String => "\"" + s + "\""
		case i: Int => i.toString
		case l: List[_] => "List(" + l.map(argToCode).mkString("", ", ", "") + ")"
		case h: HashMap[_, _] => "HashMap(" + h.map(p => "(" + argToCode(p._1) + ", " + argToCode(p._2) + ")").mkString("", ", ", "") + ").asInstanceOf[HashMap[String, Field]]"
		case i: IntegerField => "IntegerField(" + i.value + ")"
		case FalseField => "FalseField"
		case TrueField => "TrueField"
		case ReadRandomPolicy => "ReadRandomPolicy"
		case BoundParameter(name, aType) => fieldType(aType) + "(" + name + ")"
		case BoundThisAttribute(name, aType) => name
		case CompositeField(fields, types) => "new CompositeField(List(" + fields.map(argToCode).mkString("", ", ", "") + "), List(" + types.map(argToCode).mkString("", ", ", "") + "))"
		case u: AnyRef => {
			logger.fatal("I don't know how to generate scala for argument of type: " + u.getClass)
			""
		}
	}

	def output(func: String, args: List[Any], child: QueryPlan)(implicit sb: StringBuilder, indnt: Indentation):Unit = {
		outputPartial(func, "(")
		val argCode = args.map(argToCode)
		sb.append(argCode.mkString("", ", ", ""))

		if(child != null) {
			sb.append(",\n")
			indent {
				generatePlan(child)
			}
			output(")")
		}
		else
			sb.append(")\n")
	}

	def output(func: String, args: List[Any])(implicit sb: StringBuilder, indnt: Indentation):Unit =
		output(func, args, null)

	protected def generatePlan(plan: QueryPlan)(implicit sb: StringBuilder, indnt: Indentation):Unit = {
		val cl = plan.getClass
		val name = cl.getName.split("\\.").last
		val methodName = name.replaceFirst(name.slice(0,1), name.slice(0,1).toLowerCase)
		val fieldNames = cl.getDeclaredFields.reverse.map(_.getName)
		val fieldValues = fieldNames.filter(n => !(n equals "child")).map(cl.getMethod(_).invoke(plan)).toList
		val childValue: QueryPlan =
			if(fieldNames.contains("child"))
				cl.getMethod("child").invoke(plan).asInstanceOf[QueryPlan]
			else
				null
		output(methodName, fieldValues, childValue)
 	}

	protected def generateBoundValue(value: BoundValue): String = {
		value match {
			case BoundParameter(name, _) => "(new " + fieldType(value.aType) + ")(" + name +")"
			case _ => throw new UnimplementedException("Can't handle this value type: " + value)
		}
	}

	protected def toScalaType(aType: AttributeType): String = {
		 aType match {
				case BooleanType => "Boolean"
				case StringType => "String"
				case IntegerType => "Int"
			}
	}

	private def fieldType(aType: AttributeType): String = {
		aType match {
			case BooleanType => "BooleanField"
			case StringType => "StringField"
			case IntegerType => "IntegerField"
			case CompositeType(list) => "CompositeField(" + list.map("new " + fieldType(_)) + ", " + list.map("classOf[" + fieldType(_) + "].asInstanceOf[Class[Field]]") + ")"
		}
	}

	private def fieldToCode(field: Field): String = {
		field match {
			case BoundParameter(name, aType) => fieldType(aType) + "(" + name + ")"
			case BoundThisAttribute(name, aType) => name
			case s: StringField => "new StringField"
			case i: IntegerField => "new IntegerField"
			case b: BooleanField => "new BooleanField"
		}
	}

	private def versionToCode(ver: Version): String = {
		ver match {
			case _: IntegerVersion => "new IntegerVersion"
			case Unversioned => "Unversioned"
		}
	}

	private def quote(string: String) = "\"" + string + "\""
}
