package info.kwarc.mmt.lf.structuralfeatures

import info.kwarc.mmt.api._
import objects._
import symbols._
import notations._
import checking._
import modules._
import frontend.Controller

import info.kwarc.mmt.lf._
import InternalDeclaration._
import InternalDeclarationUtil._
import StructuralFeatureUtils._
import StructuralFeatureUtil._
import TermConstructingFeatureUtil._
import inductiveUtil._

/** theories as a set of types of expressions */ 
class InductiveDefinitions extends StructuralFeature("inductive_definition") with TypedParametricTheoryLike {
  
  /**
   * Checks the validity of the inductive type(s) to be constructed
   * @param dd the derived declaration from which the inductive type(s) are to be constructed
   */
  override def check(dd: DerivedDeclaration)(implicit env: ExtendedCheckingEnvironment) {
    val (context, indParams, indD, indCtx) = parseTypedDerivedDeclaration(dd, "inductive")
    checkParams(indCtx, indParams, Context(dd.parent)++context, env)
  }
  
  /**
   * Compute the expected type for an inductive definition case
   */
  def expectedType(dd: DerivedDeclaration, intDecls: List[InternalDeclaration], indDPath: GlobalName, c: Constant) : (Term, (Boolean, Option[GlobalName])) = {
    val tpdecls = tpls(intDecls)
    val tplPathMap = tpdecls map {tpl =>
      (tpl.externalPath(indDPath), correspondingDecl(dd, tpl.name).get.df.get)
    }
    
    val intC = intDecls.find(_.name == c.name).get
    val tr = TraversingTranslator(OMSReplacer(p => utils.listmap(tplPathMap, p)))
    val cons = intC match {
      case const : Constructor => 
        val intTpl = const.getTpl
        val tplC = dd.getDeclarations.find(_.name == intTpl.name).get.path
        (true, Some(tplC))
      case _ => (false, None)
    }
    (tr(Context.empty, intC.externalTp(indDPath)), cons)
  }  
  
   /**
   * Check that each definien matches the expected type
   */
  override def expectedType(dd: DerivedDeclaration, c: Constant): Option[Term] = {
    val (context, indParams, indD, indCtx) = parseTypedDerivedDeclaration(dd, "inductive")
    
    val intDecls = parseInternalDeclarations(indD, controller, Some(indCtx))
    Some(expectedType(dd, intDecls, indD.path, c)._1)
  }

  /**
   * Elaborates an declaration of one or multiple mutual inductive types into their declaration, 
   * as well as the corresponding no confusion and no junk axioms
   * Constructs a structure whose models are exactly the (not necessarily initial) models of the declared inductive types
   * @param parent The parent module of the declared inductive types
   * @param dd the derived declaration to be elaborated
   */
  def elaborate(parent: ModuleOrLink, dd: DerivedDeclaration) = {
    val (context, _, indD, indCtx) = parseTypedDerivedDeclaration(dd, "inductive")
    
    //The internal definitions we need to give definiens for
    val intDecls = parseInternalDeclarations(indD, controller, None) filterNot (_.isOutgoing)
    var (tpls, tmls) = (InternalDeclaration.tpls(intDecls), InternalDeclaration.tmls(intDecls))
    
    val (tplNames, tmlNames, intDeclNames) = (tpls map (_.name), tmls map (_.name), intDecls map (_.name))
    val tplExtNames = tplNames map (externalName(indD.path, _))
    
//    val expectations = {c:Constant => expectedType(dd, controller, intDecls, indD.path, c)}
    val defDecls = parseInternalDeclarationsWithDefiniens(dd, controller, Some(context), Some(expectedType(dd, intDecls, indD.path, _)._2))
    val (defTpls, defConstrs) = (InternalDeclaration.tpls(defDecls), constrs(defDecls))
    
    // and whether we have all necessary declarations
    intDecls filter {d => defDecls forall (_.name != d.name)} map {d => 
      throw LocalError("No declaration found for the internal declaration "+d.name+" of "+indD.name+".")
    }
    
    val induct_paths = intDecls map (t=>t.path.copy(name=externalName(indD.path, inductName(t.name)).name))
    
    val modelDf = defDecls map (_.df.get)
    val intDeclsArgs = intDecls map(_.argContext(None)(indD.path)._1)
    
    // Correctly sorted version of decls
    val defDeclsDef = intDeclNames map (nm => defDecls.find(_.name == nm).getOrElse(
        throw LocalError("No declaration found for the typelevel: "+nm)))
    val Tps = intDecls zip defDeclsDef map {
      case (tpl, tplDef) if tpl.isTypeLevel => PiOrEmpty(context, Arrow(tpl.toTerm(indD.path), tplDef.df.get))
      case (tml: Constructor, tmlDef) => 
        val tpl = tml.getTpl
        val tplDef = defDecls.find(_.name == tpl.name).get
        val (args, dApplied) = tml.argContext(None)(indD.path)
        PiOrEmpty(context++args, Eq(tplDef.df.get, tpl.applyTo(dApplied)(dd.path), ApplyGeneral(tmlDef.df.get, args map {arg =>
          induct(tpls map (_.externalPath(indD.path)), defTpls, dd.path, arg)})))
    }
    val Dfs = intDecls zip intDeclsArgs zip induct_paths.map(OMS(_)) map {case ((intDecl, intDeclArgs), tm) => 
        LambdaOrEmpty(context++intDeclArgs, ApplyGeneral(tm, context.map(_.toTerm)++modelDf++intDeclArgs.map(_.toTerm)))
    }
    
    val inductDefs = (intDecls zip Tps zip Dfs) map {case ((tpl, tp), df) => 
      makeConst(tpl.name, ()=> {tp}, false,() => {Some(df)})(dd.path)}
    
    //defDeclsDef map (d => println(d.toString(Some(noLookupPresenter.asString(_)))))
    // Add a more convenient version of the declarations for the tmls by adding application to the function arguments via a chain of Congs
    val inductTmlsApplied = defDeclsDef zip (Tps zip Dfs) filter(_._1.isConstructor) map {
      case (d: Constructor, (tp, df)) =>
        val (dargs, tpBody) = unapplyPiOrEmpty(tp)
        val defTplDef = d.getTpl.df.get
        val mdlAgs = defTplDef match {case FunType(ags, rt) => if (ags.isEmpty) Nil else ags.tail.+:(None,rt)}
        val mdlArgs = mdlAgs.zipWithIndex map {case ((n, t), i) => (n.map(_.toString()).getOrElse("y_"+i), t)}
        val mdlArgsCtx = mdlArgs map {case (n, tp) => newVar(n, tp, Some(d.context++dargs))}
        
        val inductDefApplied = Congs(tpBody, df, mdlArgsCtx)
        makeConst(appliedName(d.name), () => {PiOrEmpty(mdlArgsCtx++dargs,inductDefApplied._1)}, false, () => Some(LambdaOrEmpty(mdlArgsCtx++dargs, inductDefApplied._2)))(dd.path)
    }
   
    externalDeclarationsToElaboration(inductDefs++inductTmlsApplied, Some({c => log(defaultPresenter(c)(controller))}))
  }
  
  def induct(tpls: List[GlobalName], defTpls: List[TypeLevel], dd: GlobalName, tm: VarDecl) : Term = {
    val maps : List[(GlobalName, Term => Term)] = tpls map {tpl => 
      (tpl, {t:Term => defTpls.find(_.name.last == tpl.name.last).get.applyTo(t)(dd)})
    }
    tm.tp match {
      case Some(OMS(tpl)) if tpls.contains(tpl) => utils.listmap(maps, tpl).getOrElse(throw ImplementationError("map: "+maps.toString()))(tm.toTerm)
      case Some(OMS(p)) => tm.toTerm
      case _ => tm.toTerm
    }
  }
}

object InductiveDefinitionRule extends StructuralFeatureRule(classOf[InductiveDefinitions], "inductive_definition")