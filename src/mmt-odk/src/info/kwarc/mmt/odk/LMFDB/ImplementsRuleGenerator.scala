package info.kwarc.mmt.odk.LMFDB

import info.kwarc.mmt.api._
import frontend._
import objects._
import symbols._
import uom._
import utils._

import info.kwarc.mmt.lf._
import info.kwarc.mmt.odk.LFX

/** generates rules for fields in schema-theories
 *  These rules reduce F(a) is the result of F is stored as a field f in the record a
 *  This happens when f is annotated with a special metadatum that links to F.
 */
class ImplementsRuleGenerator extends ChangeListener {
  override val logPrefix = "impl-rule-gen"
  
  private val nameSuffix = LocalName("implements")

  private def getGeneratedRule(p: Path): Option[ImplementsRule] = {
     p match {
        case p: GlobalName =>
           controller.globalLookup.getO(p / nameSuffix) match {
              case Some(rc: RuleConstant) => rc.df flatMap downcast(classOf[ImplementsRule])
              case _ => None
           }
        case _ => None
     }
  }

  def onUpdate(e: StructuralElement) {
     onAdd(e)
  }
  override def onAdd(e: StructuralElement) {onCheck(e)}
  override def onDelete(e: StructuralElement) {
     getGeneratedRule(e.path).foreach {r => controller.delete(r.from.path / nameSuffix)}
  }
  override def onCheck(e: StructuralElement) {
    e match {
       case c: Constant =>
         val r = getGeneratedRule(c.path)
         if (r.isDefined) return
         val impl = Metadata.ImplementsLinker.get(c).getOrElse(return)
         log("generating rule for " + c.path + " for " + impl)
         val schemaThy = controller.localLookup.getTheory(c.parent)
         val cons = Metadata.ConstructorLinker.get(schemaThy).getOrElse {
           throw LocalError("no constructor annotation found in " + schemaThy.path)
         }
         val ruleName = c.name / nameSuffix
         log("generating rule " + ruleName)
         val rule = new ImplementsRule(c, cons, impl)
         val dbThy = LMFDBStore.getOrAddVirtualTheory(controller, schemaThy).getOrElse {
           throw LocalError("could not obtain virtual theory for " + schemaThy.path)
         }
         val ruleConst = RuleConstant(dbThy.toTerm, ruleName, OMS(c.path), Some(rule)) //TODO better type
         ruleConst.setOrigin(GeneratedFrom(c.path, this))
         log("generated rule " + rule + " for " + c.path + ", added to " + dbThy.path)
         controller.add(ruleConst)
       case _ =>
    }
  }
  private def error(e: StructuralElement, msg: String) {
     logError(e.path + ": " + msg)
  }
}

/**
 * a simplification rule generated by [[ImplementsRuleGenerator]]
 *
 * @param from the constant of the schema theory giving rise to the rule
 * @param constructor the operator used to construct an object from a database record
 * @param impl the semantic operator implemented by this field
 */
class ImplementsRule(val from: Constant, constructor: GlobalName, impl: GlobalName) extends SimplificationRule(impl) {
    override def toString = s"${impl.name}(record) ~~> (record).${from.name}"

    def apply(c: Context, t: Term) = t match {
      case Apply(OMS(`impl`), obj) =>
        obj match {
          case Apply(OMS(`constructor`), rec) =>
            Simplify(LFX.Getfield(rec, from.name))
          case _ => RecurseOnly(List(2))
        }
      case _ =>
        Simplifiability.NoRecurse
    }
}