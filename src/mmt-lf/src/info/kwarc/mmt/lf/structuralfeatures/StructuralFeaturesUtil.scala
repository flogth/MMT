package info.kwarc.mmt.lf.structuralfeatures

import info.kwarc.mmt.api._
import objects._
import symbols._
import notations._
import checking._
import modules._
import frontend.Controller

import info.kwarc.mmt.lf._
import InternalDeclarationUtil._
import InternalDeclaration._

object StructuralFeatureUtils {
  val theory: MPath = LF._base ? "DHOL"
  object Ded {
    val path = LF._base ? "Ded" ? "DED"
    def apply(t: Term) = ApplySpine(OMS(path), t)
    def unapply(t: Term) : Option[Term] = t match {
      case Apply(OMS(path),tm) => Some(tm)
    }
  }
  object Eq {
    val path = theory ? "EQUAL"
    def apply(tp: Term, tm1: Term, tm2: Term) = Ded(ApplySpine(OMS(path), tp, tm1, tm2))
    def unapply(t: Term) : Option[(Term, Term, Term)] = t match {
      case Ded(ApplyGeneral(OMS(path), List(tp, tm1, tm2))) => Some((tp, tm1, tm2))
    }
  }
  object Neq {
    val path = theory ? "NOTEQUAL"
    def apply(tp: Term, tm1: Term, tm2: Term) = Ded(ApplySpine(OMS(path), tp, tm2, tm2))
    def unapply(t: Term) : Option[(Term, Term, Term)] = t match {
      case Ded(ApplyGeneral(OMS(path), List(tp, tm1, tm2))) => Some((tp, tm1, tm2))
    }
  }
  
  // Needed for the testers
  val Prop = Univ(1)
  val Bool = OMS(LF._base ? "Bool" ? "BOOL")
  val True = OMS(LF._base ? "Bool" ? "TRUE")
  val False = OMS(LF._base ? "Bool" ? "FALSE")
  
  // Needed for the unappliers
  val theoryPath = LF._base ? "Option"
  object OPTION {
    val path = theoryPath ? "OPTION"
    def apply(tp: Term) = ApplySpine(OMS(path), tp)
  }
  object NONE {
    val path = theoryPath ? "NONE"
    def apply(tp: Term) = ApplySpine(OMS(path), tp)
  }
  object SOME {
    val path = theoryPath ? "SOME"
    def apply(tp: Term, tm: Term) = ApplyGeneral(OMS(path), List(tp, tm))
  }
  object MAP {
    val path = theoryPath ? "MAP"
    def apply(a: Term, tp: Term, f: Term) = ApplyGeneral(OMS(path), List(a, tp, f))
  }
  
  val Contra = OMS(theory ? "CONTRA")
  /** negate the statement in the type */
  def neg(tp: Term) : Term = Arrow(tp, Contra)
  
  /** To allow for more user friendly inductive-proof declarations
   * Takes a predicate pr: ⊦ x ≐ y for x: A, y: A, a (initial) function f: A → B and returns a predicate ⊦ f a ≐ f b
   * @param pr the predicate : ⊦ x ≐ y
   * @param f the function f : ⊦ A → B
   */  object CONG {
    val path = theory ? "CONG"
    def apply(A: Term, B: Term, x: Term, y: Term, p_xeqy: Term, f: Term) = ApplyGeneral(OMS(path), List(A,B,x,y, p_xeqy, f))
  }
  /** More convenient version of CONG, which manually infers the first four arguments */
  def Cong(pr: VarDecl, func: VarDecl) = {
    val (pred, ptp) = (pr.toTerm, pr.tp.getOrElse(throw ImplementationError("Type missing for the proof passed to Cong.")))
    val (tp, x, y) = ptp match {
      case Eq(tp, tm1, tm2) => (tp, tm1, tm2)
      case _ => throw ImplementationError("Unexpected type found for the proof. Can't apply Cong. ")  
    }
    val (f, ftp) = (func.df.getOrElse(throw ImplementationError("Definition for function required to apply Cong.")), func.tp.getOrElse(throw ImplementationError("Type missing for the function. Can't apply Cong.")))
    val (a, b) = ftp match {
      case Arrow(dom, codom) => (dom, codom)
      case _ => throw ImplementationError("Unexpected type found for the function. Can't apply Cong.")
    }
    CONG.apply(a, b, x, y, pred, f)
  }
  
  /**
   * Takes a predicate pr: ⊦ x ≐ y for x: C → D, y: C → D, the type tp=C → D and a term tm: C and returns a predicate ⊦ f a ≐ f b, where f x := x tm
   * @param pr the predicate : ⊦ x ≐ y
   * @param f the function f : ⊦ f x ≐ f y
   * @param tm the term tm: C
   */
  def Cong(pr: VarDecl, tp: Term, tm: Term) : Term = {
    val (c, d) = tp match {case Arrow(c, d) => (c, d)}
    val x = newVar("x", tp, Some(pr))
    val df = Lambda(x, Apply(x.toTerm, tm))
    Cong(pr, VarDecl(LocalName("f"), tp, df))
  }
  
  /**
   * Folds a chain of Congs over a list of arguments, given the initial predicate prInitial and the type of the initial terms
   * @param prInitial the initial predicate : ⊦ x ≐ y
   * @param tpInitial the type Z of the initial arguments x, y
   * @param argCtx the chain of arguments to which the terms are to be applied
   */
  def Cong(prInitial: VarDecl, tpInitial: Term, argCtx: Context) : Term = {
      val start = (prInitial.tp.get, prInitial.df.get, tpInitial)
      val (_, finalTm, _) = argCtx.map(_.toTerm).foldLeft(start)({
        case ((prcTp, prcDf, tp), tm) => 
          val prNextDf = Cong(VarDecl(LocalName.empty, prcTp, prcDf), tp, tm)
          val FunType(l, termTp) = tp
          val d = if (l.isEmpty) termTp else FunType(l.tail, termTp)
          val tpNext = d
          val Eq(_, x, y) = prcTp
          val prNextTp = Eq(d, Apply(x, tm), Apply(y, tm))
          (prNextTp, prNextDf, tpNext)
      })
      finalTm
  }
  
  
    /*argCtx.toList match {
      case tm => Cong(prInitial, tpInitial, tm)
      case tm::tms =>
        val prDef = Cong(prInitial, tpInitial, tm)
        val FunType(l, termTp) = tpInitial
        val tp = l match {
          case Nil => throw ImplementationError("Cong applied with two several arguments, but unary function type found for the initial terms.")
          case List(arg) => termTp
          case arg::args => FunType(args, termTp)
        }
        val Eq(_, x, y) = prInitial.tp.get
        val prTp = Eq(tp, Apply(x,tm.toTerm), Apply(y,tm.toTerm))
        Cong(VarDecl(LocalName.empty, prTp, prDef), tp, tms)
    }
  }*/
  
  
  def parseInternalDeclarationsSubstitutingDefiniens(decls: List[Constant], con: Controller, ctx: Option[Context])(implicit parent : GlobalName): List[InternalDeclaration] = {
    val context = ctx.getOrElse(Context.empty)
    var types: List[GlobalName] = Nil

    decls map {c =>
      val intDecl = fromConstant(c, con, types, ctx)
      def repDfs(df: GlobalName) = {utils.listmap(decls.map(t => (t.path, ApplyGeneral(t.df.get, context.map(_.toTerm)))), df)}
      def mpDf(df: Term):Term = OMSReplacer(repDfs)(df, Context.empty)
      val repDf = intDecl.df map (mpDf)
      
      val decl = intDecl match {
          case constr: Constructor => new Constructor(constr.path, constr.args, constr.ret, repDf, constr.notC, constr.ctx)
          case out: OutgoingTermLevel => new OutgoingTermLevel(out.path, out.args, out.ret, repDf, out.notC, out.ctx)
          case d : TypeLevel => d.copy(df = repDf)
          case d : StatementLevel => d.copy(df = repDf)
          case _ => throw ImplementationError("invalid InternalDeclaration")
         }
      if (decl.isTypeLevel) types +:= decl.path
      decl
    }
  }
  
  /**
   * Parse the internal declarations of dd expanding previous definitions
   * @param dd the derived declaration whoose internal declarations to parse
   * @param con the controller
   * @param ctx (optional) a context to parse the declarations in
   * @param parent (implicit) path of the derived declaration over which we want to define a term
   */
  def parseInternalDeclarations(dd: ModuleOrLink, con: Controller, ctx: Option[Context])(implicit parent : GlobalName): List[InternalDeclaration] = {
    val consts = dd.getDeclarations map {case c: Constant=> c case _ => throw GeneralError("unsupported declaration")}
    parseInternalDeclarationsSubstitutingDefiniens(consts, con, ctx)(parent)
  }
}

import StructuralFeatureUtils._
object TermConstructingFeatureUtil {
    def correspondingDecl(dd: DerivedDeclaration, d: LocalName): Option[Constant] = {
      dd.getO(d) map {
        case c: Constant=> c
        case dec => throw GeneralError("Expected a constant at "+dec.path+".")
      }
    }
    /**
   * Parse the internal declarations of dd expanding previous definitions
   * @param dd the derived declaration whoose internal declarations to parse
   * @param con the controller
   * @param ctx (optional) a context to parse the declarations in
   * @param parent path of the derived declaration over which we want to define a term
   */ 
    def parseInternalDeclarationsWithDefiniens(dd: ModuleOrLink, con: Controller, ctx: Option[Context], parent : GlobalName): List[InternalDeclaration] = {
      val decls = parseInternalDeclarations(dd, con, ctx)(parent)
      decls.map(d => if (d.df.isEmpty) throw GeneralError("Unsupported corresponding declaration: Expected constant with definien at "+d.path))
      decls
    }
}