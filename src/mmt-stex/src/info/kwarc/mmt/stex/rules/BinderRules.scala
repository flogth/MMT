package info.kwarc.mmt.stex.rules

import info.kwarc.mmt.api.{GlobalName, ParametricRule, Rule, RuleSet}
import info.kwarc.mmt.api.checking.{CheckingCallback, ComputationRule, Continue, History, InferenceAndTypingRule, InhabitableRule, Solver, TermBasedEqualityRule}
import info.kwarc.mmt.api.frontend.Controller
import info.kwarc.mmt.api.objects.{Context, Equality, Inhabitable, OMA, OMBIND, OMS, OMV, Stack, Subtyping, Term, Typing}
import info.kwarc.mmt.stex.{SCtx, SHTML, SHTMLHoas, SOMBArg, STerm}
import info.kwarc.mmt.api.objects.Conversions._
import info.kwarc.mmt.api.uom.{RecurseOnly, Simplifiability, Simplify}

object PiLikeRuleInh extends ParametricRule {

  case class PiLikeRuleInhI(hhead: GlobalName) extends InhabitableRule(hhead) {
    override def applicable(t: Term): Boolean = t match {
      case SHTMLHoas.bound(_,OMS(`hhead`), _, _) => true
      case SHTMLHoas.OmaSpine(_,OMS(`hhead`),List(_,_)) => true
      case _ => false
    }

    def apply(solver: Solver)(term: Term)(implicit stack: Stack, history: History): Option[Boolean] = term match {
      case SHTMLHoas.bound(_,OMS(`hhead`), x, tm) if x.tp.isDefined =>
        solver.check(Inhabitable(stack, x.tp.get))
        Some(solver.check(Inhabitable(stack ++ x, tm)))
      case SHTMLHoas.OmaSpine(_,OMS(`hhead`),List(a,b)) =>
        solver.check(Inhabitable(stack, a))
        Some(solver.check(Inhabitable(stack, b)))
      case _ => None
    }
  }

  override def apply(controller: Controller, home: Term, args: List[Term]): Rule = args match {
    case List(OMS(h)) => PiLikeRuleInhI(h)
    case _ =>
      ???
  }
}

object LambdaLikeRule extends ParametricRule {
  case class LambdaTypingRule(lambda:GlobalName,pi:GlobalName) extends InferenceAndTypingRule(lambda,pi) {
    def apply(solver: Solver, tm: Term, tp: Option[Term], covered: Boolean)(implicit stack: Stack, history: History): (Option[Term], Option[Boolean]) = {
      (tm,tp) match {
        case (SHTMLHoas.bound(h,OMS(`lambda`),x,bd),None) if x.tp.isDefined =>
          if (!covered) {
            solver.check(Inhabitable(stack,x.tp.get))
          }
          solver.inferType(bd,covered)(stack ++ x,history) match {
            case Some(tp) =>
              (Some(SHTMLHoas.bound(h,OMS(`pi`),x,tp)),Some(true))
            case _ => (None,None)
          }
        case (SHTMLHoas.bound(h, OMS(`lambda`), x, bd), Some(SHTMLHoas.bound(h2,OMS(`pi`),x2,bd2))) if h == h2 && x.tp.isDefined && x2.tp.isDefined =>
          if (!covered) {
            solver.check(Inhabitable(stack, x.tp.get))
            solver.check(Equality(stack, x.tp.get, x2.tp.get, None))
            (solver.safeSimplifyUntil(x.tp.get)(SHTML.flatseq.tp.unapply)._1, solver.safeSimplifyUntil(x2.tp.get)(SHTML.flatseq.tp.unapply)._1) match {
              case (SHTML.flatseq.tp(_), SHTML.flatseq.tp(_)) =>
                solver.check(Typing(stack ++ x, bd, bd2 ^? (x2.name / OMV(x.name))))
              case (_, SHTML.flatseq.tp(_)) =>
                solver.check(Typing(stack ++ x, bd, bd2 ^? (x2.name / SHTML.flatseq(List(OMV(x.name))))))
              case _ =>
                solver.check(Typing(stack ++ x, bd, bd2 ^? (x2.name / OMV(x.name))))
            }
          }
          (tp,Some(true))
        case _ => (None,None)
      }
    }
  }

  override def apply(controller: Controller, home: Term, args: List[Term]): Rule = args match {
    case List(OMS(l),OMS(p)) => LambdaTypingRule(l,p)
    case _ =>
      ???
  }

}

object LambdaLikeArrowRule extends ParametricRule {
  case class LambdaTypingRule(lambda:GlobalName,arr:GlobalName) extends InferenceAndTypingRule(lambda,arr) {

    override def applicable(t: Term): Boolean = t match {
      case SHTMLHoas.bound(h,OMS(`lambda`),x,bd) if x.tp.isDefined => true
      case _ => false
    }
    def apply(solver: Solver, tm: Term, tp: Option[Term], covered: Boolean)(implicit stack: Stack, history: History): (Option[Term], Option[Boolean]) = {
      (tm,tp) match {
        case (SHTMLHoas.bound(h,OMS(`lambda`),x,bd),None) if x.tp.isDefined =>
          if (!covered) {
            solver.check(Inhabitable(stack, x.tp.get))
          }
          solver.inferType(bd,covered)(stack ++ x,history) match {
            case Some(tp) if !tp.freeVars.contains(x) =>
              (Some(SHTMLHoas.OmaSpine(h,OMS(`arr`),List(x.tp.get,tp))),Some(true))
            case _ => (None,None)
          }
        case (SHTMLHoas.bound(h, OMS(`lambda`), x, bd), Some(SHTMLHoas.OmaSpine(h2,OMS(`arr`),List(xtp2,bd2)))) if h == h2 && x.tp.isDefined =>
          if (!covered) {
            solver.check(Inhabitable(stack, x.tp.get))
            solver.check(Equality(stack, x.tp.get, xtp2, None))
            (solver.safeSimplifyUntil(x.tp.get)(SHTML.flatseq.tp.unapply)._1, solver.safeSimplifyUntil(xtp2)(SHTML.flatseq.tp.unapply)._1) match {
              case (SHTML.flatseq.tp(_), SHTML.flatseq.tp(_)) =>
                solver.check(Typing(stack ++ x, bd, bd2))
              case (_, SHTML.flatseq.tp(_)) =>
                solver.check(Typing(stack ++ x, bd, bd2))
              case _ =>
                solver.check(Typing(stack ++ x, bd, bd2))
            }
          }
          (tp,Some(true))
        case _ => (None,None)
      }
    }
  }


  case class ApplyTypingRule(app: GlobalName, arr: GlobalName) extends InferenceAndTypingRule(app, arr) {
    def apply(solver: Solver, tm: Term, tp: Option[Term], covered: Boolean)(implicit stack: Stack, history: History): (Option[Term], Option[Boolean]) = {
      (tm, tp) match {
        case (OMA(OMS(`app`), List(f, a)), None) =>
          solver.inferType(f, covered) match {
            case Some(SHTMLHoas.OmaSpine(h,OMS(`arr`),tA :: rest)) if rest.nonEmpty =>
              if (!covered) {
                solver.check(Typing(stack,a,tA))
              }
              if (rest.length == 1) {
                (Some(rest.head),Some(true))
              } else {
                (Some(SHTMLHoas.OmaSpine(h,OMS(`arr`),rest)),Some(true))
              }
            case _ => (None, None)
          }
        case (OMA(OMS(`app`), List(f, a)), Some(t)) =>
          solver.inferType(f, covered) match {
            case Some(SHTMLHoas.OmaSpine(h, OMS(`arr`), tA :: rest)) if rest.nonEmpty =>
              if (!covered) {
                solver.check(Typing(stack, a, tA))
                val tpB = if (rest.length == 1) rest.head else SHTMLHoas.OmaSpine(h,OMS(`arr`),rest)
                solver.check(Subtyping(stack,tpB,t))
              }
              (tp, Some(true))
            case _ => (None, None)
          }
        case _ => (None, None)
      }
    }
  }

  override def apply(controller: Controller, home: Term, args: List[Term]): Rule = args match {
    case List(OMS(a),OMS(l),OMS(p)) => RuleSet(LambdaTypingRule(l,p),ApplyTypingRule(a,p),ApplyRule.ApplyCompRule(a,l))
    case _ =>
      ???
  }

}


object ApplyRule extends ParametricRule {
  case class ApplyTypingRule(app:GlobalName,pi:GlobalName) extends InferenceAndTypingRule(app,pi) {

    def makeOMB(args : List[SOMBArg],tp : Term,rettp:Option[Term])(implicit solver : Solver,stack : Stack, history : History,covered : Boolean) : (Option[Term],Option[Boolean]) = (args.headOption,tp) match {
      case (Some(SCtx(Context(vd, rest@_*))),SHTMLHoas.bound(_, OMS(`pi`), x, r)) if !r.freeVars.contains(x.name) && x.tp.isDefined =>
        if (!covered) vd.tp.foreach(t => solver.check(Subtyping(stack,t,x.tp.get)))
        if (rest.isEmpty) {
          makeOMB(args.tail,r,rettp)(solver,stack ++ vd,history,covered)
        } else {
          makeOMB(SCtx(Context(rest:_*)) :: args.tail,r,rettp)(solver,stack ++ vd,history,covered)
        }
      case (Some(STerm(t)), SHTMLHoas.bound(_, OMS(`pi`), x, r)) if x.tp.isDefined =>
        if (!covered) solver.check(Typing(stack,t,x.tp.get,None))
        makeOMB(args.tail,r ^? (x.name / t),rettp)
      case (None,r) =>
        rettp.foreach{tp =>
          solver.check(Subtyping(stack,r,tp))
        }
        (Some(r),Some(true))
      case _ =>
        (None,None)
    }

    def seq(tm : Term)(implicit solver: Solver,stack:Stack,history:History) =
      solver.safeSimplifyUntil(tm)(SHTML.flatseq.tp.unapply)._1
    def piify(tm : Term)(implicit solver: Solver,stack:Stack,history:History) = {
      solver.safeSimplifyUntil(
        solver.safeSimplifyUntil(tm)(SHTMLHoas.bound.unapply)._1
      )(SHTML.implicit_binder.unapply)._1
    }

    def makeOMA(args: List[Term], tp: Term, rettp: Option[Term])(implicit solver: Solver, stack: Stack, history: History, covered: Boolean): (Option[Term], Option[Boolean]) = (args.headOption, piify(tp)) match {
      case (Some(a), SHTMLHoas.bound(_, OMS(`pi`), x, r)) if x.tp.isDefined =>
        if (!covered) solver.check(Typing(stack, a, x.tp.get, None))
        val rtp = r ^? (x.name / a)
        makeOMA(args.tail,rtp,rettp)
      case (Some(a),SHTML.implicit_binder(x,Some(tp),r)) =>
        solver.check(Typing(stack, a, tp, None))
        val rtp = r ^? (x / a)
        makeOMA(args.tail, rtp, rettp)
      case (None,r) =>
        rettp.foreach { tp =>
          solver.check(Subtyping(stack, r, tp))
        }
        (Some(r), Some(true))
      case (Some(t),SHTML.flatseq.tp(r)) =>
        if (!covered) solver.check(Typing(stack,t,OMS(SHTML.ord)))
        makeOMA(args.tail, r, rettp)
      case _ =>
        (None,None)
    }

    def apply(solver: Solver, tm: Term, tp: Option[Term], covered: Boolean)(implicit stack: Stack, history: History): (Option[Term], Option[Boolean]) = {
      (tm,tp) match {
        case (SHTMLHoas.Omb(h,f,args),_) if h.app == app =>
          solver.inferType(f, covered) match {
            case Some(SHTML.implicit_binder.spine(ctx,r)) if args.length >= ctx.length =>
              val nstack = stack ++ args.flatMap {
                case STerm(_) => Nil
                case SCtx(ctx) => ctx
              }
              if (!covered) {
                var nst = nstack
                ctx.zipWithIndex.foreach { case (vd,i) =>
                  vd.tp.foreach { tp =>
                    solver.check(Typing(nst,args(i) match {
                      case STerm(t) => t
                      case _ => return (None, None)
                    },tp))
                  }
                  nst ++= vd
                }
              }
              makeOMB(args.drop(ctx.length),r ^? (ctx /! args.take(ctx.length).map(_.asInstanceOf[STerm].tm)),tp)(solver,nstack,history,covered)
            case Some(t) =>
              makeOMB(args,t,tp)(solver,stack,history,covered)
            case _ =>
              (None, None)
          }
        case (SHTMLHoas.OmaSpine(h,f,args),_) if h.exists(_.app == app) =>
          solver.inferType(f,covered) match {
            case Some(t) =>
              makeOMA(args, t, tp)(solver, stack, history, covered)
            case _ => (None,None)
          }
        case _ => (None,None)
      }
    }
  }

  case class ApplyCompRule(app : GlobalName,lambda:GlobalName) extends ComputationRule(app) {
    override def apply(check: CheckingCallback)(tm: Term, covered: Boolean)(implicit stack: Stack, history: History): Simplifiability = {
      tm match {
        case OMA(OMS(`app`),List(f @ SHTMLHoas.bound(_,OMS(`lambda`),x,bd),a)) if x.tp.isDefined =>
          if (!covered) {
            check.inferType(f)
            check.check(Typing(stack,a,x.tp.get,None))
          }
          val tpA = check.inferType(a).getOrElse {
            return Simplifiability.NoRecurse
          }
          (check.safeSimplifyUntil(tpA)(SHTML.flatseq.tp.unapply)._1, check.safeSimplifyUntil(x.tp.get)(SHTML.flatseq.tp.unapply)._1) match {
            case (SHTML.flatseq.tp(_),SHTML.flatseq.tp(_)) =>
              Simplify(bd ^? (x.name / a))
            case (_,SHTML.flatseq.tp(_)) =>
              Simplify(bd ^? (x.name / SHTML.flatseq(List(a))))
            case _ =>
              Simplify(bd ^? (x.name / a))
          }
        case OMA(OMS(`app`),List(f,_)) =>
          RecurseOnly(tm.subobjects.indexWhere(_._2 == f) :: Nil)
        case _ => Simplifiability.NoRecurse
      }
    }
  }

  override def apply(controller: Controller, home: Term, args: List[Term]): Rule = args match {
    case List(OMS(a),OMS(l),OMS(p)) =>
      RuleSet(ApplyTypingRule(a,p),ApplyCompRule(a,l))
    case _ =>
      ???
  }
}
object PreEqualRule extends ParametricRule {
  case class PreEqualityRule(head : GlobalName) extends TermBasedEqualityRule {
    override def applicable(tm1: Term, tm2: Term): Boolean = (tm1,tm2) match {
      case (SHTMLHoas.bound(_,OMS(`head`),x,_),SHTMLHoas.bound(_,OMS(`head`),y,_)) => x.tp.isDefined && y.tp.isDefined
      case _ => false
    }

    override def apply(check: CheckingCallback)(tm1: Term, tm2: Term, tp: Option[Term])(implicit stack: Stack, history: History): Option[Continue[Boolean]] = (tm1,tm2) match {
      case (SHTMLHoas.bound(_,OMS(`head`),x1,bd1),SHTMLHoas.bound(_,OMS(`head`),x2,bd2)) =>
        check.check(Equality(stack,x1.tp.get,x2.tp.get,None))
        Some(Continue(check.check(Equality(stack ++ x1,bd1,bd2 ^? (x2.name/OMV(x1.name)),None))))
      case _ => None
    }
  }
  override def apply(controller: Controller, home: Term, args: List[Term]): Rule = args match {
    case List(OMS(p)) =>
      PreEqualityRule(p)
    case _ =>
      ???
  }
}