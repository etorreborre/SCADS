package edu.berkeley.cs.avro
package plugin

import scala.tools._
import nsc.Global
import nsc.Phase
import nsc.plugins.Plugin
import nsc.plugins.PluginComponent
import nsc.transform.Transform
import nsc.transform.InfoTransform
import nsc.transform.TypingTransformers
import nsc.symtab.Flags
import nsc.symtab.Flags._
import nsc.ast.TreeDSL

trait Extender extends ScalaAvroPluginComponent
               with    Transform 
               with    InfoTransform
               with    TypingTransformers
               with    TreeDSL {
  import global._
  import global.definitions._

  val runsAfter = List[String]("typer")
  override val runsBefore = List[String]("superaccessors")
  val phaseName = "extender"

  def newTransformer(unit: CompilationUnit) = new ExtenderTransformer(unit)

  override def transformInfo(sym: Symbol, tpe: Type): Type = tpe match {
    case ClassInfoType(parents, decls, clazz) 
      if (!clazz.isPackageClass && isMarked(clazz)) =>
      // 1) warn if current parent is not java.lang.Object AND if it is not a
      // subtype of SpecificRecordBase
      val (car, cdr) = clazz.tpe.parents.splitAt(1)
      if (car.head != ObjectClass.tpe && !(car.head <:< SpecificRecordBaseClass.tpe))
        warn("Replacing inheritance of non specific record base type")
      ClassInfoType(List(SpecificRecordBaseClass.tpe, AvroConversions.tpe) ::: cdr, decls, clazz)
    case _ => tpe
  }

  class ExtenderTransformer(unit: CompilationUnit) extends TypingTransformer(unit) {
    import CODE._

    private val DefaultValues = Map(
      IntClass     -> LIT(0),
      LongClass    -> LIT(0L),
      FloatClass   -> LIT(0.f),
      DoubleClass  -> LIT(0.0),
      BooleanClass -> FALSE,
      ShortClass   -> LIT(0),
      ByteClass    -> LIT(0),
      CharClass    -> LIT(0))



    private def preTransform(tree: Tree): Tree = tree match {
      case cd @ ClassDef(mods, name, tparams, impl) if isMarked(cd) =>

        // check that this annotation is a case class
        if (!mods.hasFlag(Flags.CASE))
          throw new NonCaseClassException(name.toString)

        // todo: for case objects, throw exception

        debug("Extending class: " + name.toString)

        def isCtor(tree: Tree): Boolean = {
          (tree.symbol ne null) && tree.symbol.name == nme.CONSTRUCTOR
        }
        val ctors = for (member <- impl.body if isCtor(member)) yield { member.symbol }
        assert (!ctors.isEmpty)

        val containsDefaultCtor = !ctors.map(_.info).filter {
          case MethodType(Nil, MethodType(_, _)) =>
            /** case class Foo()(...) */
            false
          case MethodType(Nil, _) => 
            /** case class Foo() */
            true
          case _ => false
        }.isEmpty

        val ctor = 
          if (containsDefaultCtor) {
            None
          } else {
            // TODO: not sure if this pos stuff is really how we're supposed
            // to be manipulating
            val pos = ctors.last.pos

            // make new default ctor symbmol
            val ctorSym = cd.symbol.newConstructor(pos.withPoint(pos.point + 1))
            ctorSym setFlag METHOD
            ctorSym setInfo MethodType(ctorSym.newSyntheticValueParams(List()), cd.symbol.tpe)
            cd.symbol.info.decls enter ctorSym

            val innerCtorTpe = cd.symbol.primaryConstructor.tpe

            debug("clazz.caseFieldAccessors: " + cd.symbol.caseFieldAccessors)
            debug("clazz.primaryConstructor.tpe.paramTypes: " + cd.symbol.primaryConstructor.tpe.paramTypes)
            debug("clazz.primaryConstructor.tpe.resultType: " + cd.symbol.primaryConstructor.tpe.resultType)
            debug("clazz.primaryConstructor.tpe.finalResultType: " + cd.symbol.primaryConstructor.tpe.finalResultType)
            debug("clazz.primaryConstructor.tpe.boundSyms: " + cd.symbol.primaryConstructor.tpe.boundSyms)

            def mapToDefaults(params: List[Symbol]) =
              params.map(v => DefaultValues.get(v.tpe.typeSymbol).getOrElse(LIT(null)))

            val innerParamDefaults = mapToDefaults(innerCtorTpe.params)
            val apply0 = Apply(This(cd.symbol) DOT nme.CONSTRUCTOR, innerParamDefaults)
            val apply = innerCtorTpe.resultType match {
              case MethodType(outerParams, _) =>
                /** primaryCtor is curried */
                Apply(apply0, mapToDefaults(outerParams))
              case _ =>
                /** primaryCtor is not curried */
                apply0
            }

            Some(localTyper typed {
              DEF(ctorSym) === Block(List(apply), Literal(Constant(())))
            })
          }

        def toTypedSelectTree(s: String): Tree = {
          if ((s eq null) || s.isEmpty)
            throw new IllegalArgumentException("Bad FQDN")
          val (car, cdr) = s.split("\\.").toList.splitAt(1)
          if (cdr isEmpty)
            throw new IllegalArgumentException("Nothing to select: " + s)
          else {
            val sym = definitions.getModule(car.head)
            val first = (car.head, Ident(newTermName(car.head)) setSymbol sym setType sym.tpe)
            cdr.zipWithIndex.foldLeft[(String,Tree)](first)((tuple1, tuple2) => {
              val (name, tree) = tuple1
              val (sel, idx) = tuple2
              val newName = name + "." + sel
              val sym = 
                if (idx == cdr.length - 1)
                  definitions.getClass(newName)
                else 
                  definitions.getModule(newName)
              (newName, Select(tree, if (idx == cdr.length - 1) newTypeName(sel) else newTermName(sel)) setSymbol sym setType sym.tpe)
            })._2
          }
        }

        val specificRecordBase = toTypedSelectTree("org.apache.avro.specific.SpecificRecordBase")

        val avroConversions = toTypedSelectTree("edu.berkeley.cs.avro.runtime.HasAvroConversions")

        val (car, cdr) = impl.parents.splitAt(1)
        val newImpl = treeCopy.Template(impl, List(specificRecordBase, avroConversions) ::: cdr, impl.self, impl.body ::: ctor.toList)
        treeCopy.ClassDef(tree, mods, name, tparams, newImpl)
      case _ => tree
    }

    override def transform(tree: Tree): Tree = {
      val t = preTransform(tree)
      super.transform(t)
    }
  }
}
