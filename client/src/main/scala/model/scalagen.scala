package edu.berkeley.cs.scads.model.parser

import edu.berkeley.cs.scads.model.{SingleGet, Materialize}

object ScalaGen extends Generator[BoundSpec] {
	val autogenWarning = "/* This is an autogenerated scads spec file, do not modify it unless you know what you are doing */"
	val imports = Array("import edu.berkeley.cs.scads.model")

	protected def generate(spec: BoundSpec)(implicit sb: StringBuilder, indnt: Indentation): Unit = {
		/* Headers */
		output("/* This is an autogenerated scads spec file, do not modify it unless you know what you are doing */")
		output("import edu.berkeley.cs.scads.model._")

		/* Entities */
		spec.entities.foreach((e) => {
			output("class ", e._1, "(implicit env: Environment) extends Entity()(env) {")
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
          e._2.indexes.foreach(_ match {
            case SecondaryIndex(ns, attrs) => {
              output("new AttributeIndex(", quote(ns), ", this, ", attrs(0), ")")
            }
            case _ => null
          })
        }
        output(")")

				/* Primary Key */
				output("val primaryKey = ")
				indent {
					if(e._2.keys.size > 2)
						output("new CompositeKey(" + e._2.keys.mkString("", ",", ""), ")")
					else
						output(e._2.keys(0))
				}

				/* Ouput any queries for this entity */
				e._2.queries.foreach((q) => generateQuery(q._1, q._2))
			}
			output("}")

		})
		/* Output object for Orphan Queries */
		output("object Queries {")
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
				output("val plan = ")
				generatePlan(query.plan)
				output("plan.exec")
			}
		}
		output("}")
	}


	protected def generatePlan(plan: ExecutionNode)(implicit sb: StringBuilder, indnt: Indentation) {
    plan match {
      case m: Materialize[_] => {
        output("Materialize[", m.entityType, "](")
				indent {
					generatePlan(m.child)
				}
        output(")")
      }
			case SingleGet(ns, key, ver) => {
				output("new SingleGet(", quote(ns), ", ", fieldToCode(key), ", ", versionToCode(ver), ") with ReadOneGetter")
			}
			case SequentialDereferenceIndex(tns, tkt, tv, c) => {
				output("new SequentialDereferenceIndex(", quote(tns), ", ", fieldToCode(tkt), ", ", versionToCode(tv), ",")
				indent {
					generatePlan(c)
				}
				output(") with ReadOneGetter")
			}
    }
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
		}
	}

	private def fieldToCode(field: Field): String = {
		field match {
			case BoundParameter(name, aType) => fieldType(aType) + "(" + name + ")"
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
