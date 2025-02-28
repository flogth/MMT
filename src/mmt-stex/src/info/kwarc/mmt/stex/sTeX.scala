package info.kwarc.mmt.stex

import info.kwarc.mmt.api._
import info.kwarc.mmt.api.checking.History
import info.kwarc.mmt.api.frontend.Controller
import info.kwarc.mmt.api.metadata.HasMetaData
import info.kwarc.mmt.api.notations.{HOAS, HOASNotation, NestedHOASNotation}
import info.kwarc.mmt.api.symbols.{Constant, Declaration, Structure}
import info.kwarc.mmt.api.uom.{RepresentedRealizedType, StandardDouble, StandardInt, StandardNat, StandardPositive, StandardRat, StandardString}
import info.kwarc.mmt.api.utils.{URI, XMLEscaping}
import info.kwarc.mmt.lf.ApplySpine
import info.kwarc.mmt.odk.LFX
import info.kwarc.mmt.sequences.Sequences
import info.kwarc.mmt.stex.Extensions.NotationExtractor
import info.kwarc.mmt.stex.rules.{Getfield, ModelsOf, ModuleType, RecordError, StringLiterals}
import info.kwarc.mmt.stex.xhtml.HTMLParser
import info.kwarc.mmt.stex.xhtml.HTMLParser.ParsingState

import scala.xml._
import objects._

sealed abstract class SOMBArg {
  def obj:Obj
}
case class STerm(tm : Term) extends SOMBArg {
  val obj = tm
}
case class SCtx(ctx : Context) extends SOMBArg {
  val obj = ctx
}

object SOMBArg {
  implicit def toSOMB(ctx: Context) = SCtx(ctx)
  implicit def toSOMB(tm : Term) = STerm(tm)
}

object STeXTraverser {
  def apply[State](trav : Traverser[State], t : Term)(implicit con : Context, state : State) : Term = t match {
    case SHTMLHoas.Omb(hoas,head,args) =>
      val nhead = trav.traverse(head)(con,state)
      var ncon = con
      val nargs = args.map {
        case SCtx(ctx) =>
          val nctx = trav.traverseContext(ctx)(ncon,state)
          ncon = ncon ++ nctx
          SCtx(nctx)
        case STerm(tm) => STerm(trav.traverse(tm)(ncon,state))
      }
      hoas.HOMB(nhead,nargs).from(t)
    case _ => Traverser(trav,t)
  }
}

object SHTMLHoas {
  val sym = SHTML.mmtmeta_path ? "hoas"

  case class HoasRule(app : GlobalName, lambda : GlobalName, pi: GlobalName) extends Rule {
    lazy val asterm = OMA(OMS(sym),List(OMS(app),OMS(lambda),OMS(pi)))
    def apply[A <: HasMetaData](o : A): A = {
      o.metadata.update(sym,asterm)
      o
    }
    private val self = this
    def has(o: HasMetaData) = o.metadata.getValues(sym).contains(
      OMA(OMS(sym),List(OMS(app),OMS(lambda),OMS(pi)))
    )
    object HOMA {
      def apply(f: Term, args: List[Term]) = args match {
        case Nil => f
        case ls =>
          args.tail.foldLeft(
            self.apply(OMA(OMS(app),List(f,ls.head)))
          )((f,a) => self.apply(OMA(OMS(app),List(f,a))))
      }

      def unapply(tm: Term) = tm match {
        case OMA(OMS(`app`), f :: a :: Nil) if self.has(tm) => Some((f, a))
        case _ => None
      }
    }
    object HOMB {
      def apply(f : Term, scopes: List[SOMBArg]) = {
        val ctx = Context(scopes.flatMap {
          case SCtx(ctx) => ctx.variables
          case _ => Nil
        }: _*)
        self.apply(
          OMBIND(OMS(app), ctx, OMA(f, scopes.map {
            case STerm(tm) => tm
            case SCtx(ctx) => OMA(OMS(SHTML.meta_quantification), ctx.map(vd => OMV(vd.name)))
          }))
        )
      }

      def unapply(tm : Term): Option[(Term,List[SOMBArg])] = tm match {
        case OMBIND(OMS(`app`),ctx,OMA(f,scopes)) if self.has(tm) =>
          var nctx = Context.empty
          val ret = (f, scopes.map {
            case OMA(OMS(SHTML.meta_quantification), args: List[OMV@unchecked]) =>
              SCtx(args.map(v => {
                val vd = ctx(v.name);
                nctx = nctx ++ vd;
                vd
              }))
            case t => STerm(t)
          })
          if (ctx.forall(nctx.contains)) Some(ret) else None
        case _ => None
      }
    }
  }

  def get(o: HasMetaData): Option[HoasRule] = o.metadata.getValues(sym).headOption match {
    case Some(OMA(OMS(`sym`), List(OMS(app), OMS(lambda), OMS(pi)))) => Some(HoasRule(app, lambda, pi))
    case _ => None // should be unreachable
  }
  private def getHoas(tm : Term) = tm.metadata.getValues(sym).headOption match {
    case Some(OMA(OMS(`sym`),List(OMS(app),OMS(lambda),OMS(pi)))) => Some(HoasRule(app,lambda,pi))
    case _ => None // should be unreachable
  }
  object Oma {
    def unapply(tm: Term): Option[(HoasRule, Term, Term)] = tm match {
      case t@OMA(_,_) =>
        getHoas(t).flatMap(h => h.HOMA.unapply(t).map{case (t,ls) => (h,t,ls)})
      case _ => None
    }
  }
  object OmaSpine {
    def apply(h : Option[HoasRule],f : Term,args : List[Term]) = h match {
      case Some(h) => h.HOMA(f,args)
      case _ => OMA(f,args)
    }
    def unapply(tm : Term) : Option[(Option[HoasRule], Term, List[Term])] = tm match {
      case OMA(OMS(_),List(f,a)) if getHoas(tm).isDefined =>
        (getHoas(tm),unapply(f)) match {
          case (Some(h),Some((Some(hr),nf,na))) if h == hr =>
            Some((Some(h),nf,na ::: a :: Nil))
          case (Some(h),None) =>
            h.HOMA.unapply(tm).map { case (t, a) => (Some(h), t, List(a)) }
          case _ =>
            None
        }
      case OMA(f,List(a)) if getHoas(tm).isEmpty =>
        unapply(f) match {
          case Some((None,nf,na)) => Some((None,nf,na ::: a :: Nil))
          case None => Some((None,f,a :: Nil))
        }
      case _ => None
    }
  }

  object Omb {
    def apply(hoas:HoasRule, binder: Term, ctx: Context, body: Term) = hoas.HOMB(binder, SCtx(ctx) :: STerm(body) :: Nil)

    def unapply(tm: Term): Option[(HoasRule,Term,List[SOMBArg])] = tm match {
      case OMBIND(OMS(_), _, OMA(_, _)) =>
        getHoas(tm) match {
          case Some(hoas) => hoas.HOMB.unapply(tm) match {
            case Some((t,ls)) => Some((hoas,t,ls))
            case _ => None
          }
          case _ => None
        }
      case _ => None
    }
  }

  object bound {

    def unapply(tm: Term) = tm match {
      case Omb(h,f,List(SCtx(Context(vd, rest@_*)), STerm(bd))) =>
        if (rest.isEmpty) Some(Some(h),f, vd, bd) else Some(Some(h),f, vd, Omb(h,f, Context(rest: _*), bd))
      case OMBIND(f,Context(vd,rest@_*),bd) =>
        if (rest.isEmpty) Some(None,f,vd,bd) else Some(None,f,vd,OMBIND(f,Context(rest:_*),bd))
      case _ => None
    }
    def apply(h : Option[HoasRule],f:Term,vd:VarDecl,bd:Term) = h match {
      case Some(h) => Omb(h,f,vd,bd)
      case _ => OMBIND(f,Context(vd),bd)
    }
  }
}

object IsSeq{
    def apply(tm: Term) = tm match {
      case SHTML.flatseq(_) => true
      case OMV(_) if tm.metadata.get(SHTML.flatseq.sym).nonEmpty => true
      case _ => false
    }

    def unapply(tms: List[Term]) = {
      val i = tms.indexWhere(apply)
      if (i == -1) None else {
        Some(tms.take(i), tms(i), tms.drop(i + 1))
      }
    }
}

sealed trait STeXHOAS {
  def toTerm : Term

  def apply(head: Term, args: List[Term]): Term

  def apply(head: Term, vd:VarDecl,bd:Term): Term
}


object SHTML {
  val all_languages = List("en","de","ar","bg","ru","fi","ro","tr","fr")

  val meta_dpath = DPath(URI.http colon "mathhub.info") / "sTeX" / "meta"
  val meta_path = meta_dpath ? "Metatheory"
  val mmtmeta_path = meta_dpath ? "MMTMeta"
  val meta_quantification = mmtmeta_path ? "quantification"
  val meta_srefid = mmtmeta_path ? "srefid"

  val string = mmtmeta_path ? "stringliteral"
  val int = meta_path ? "integer literal"
  val ord = meta_path ? "ordinal"

  val headterm = mmtmeta_path ? "headsymbol"
  val title = mmtmeta_path ? "title"

  val parens = new {
    val sym = meta_path ? "internal parentheses"
    def apply(tm:Term) = OMA(OMS(sym),List(tm))
    def unapply(tm:Term) = tm match {
      case OMA(OMS(`sym`),List(t)) => Some(t)
      case _ => None
    }
  }

  val of_type = new {
    val sym = meta_path ? "of type"
    def unapply(tm:Term) = tm match {
      case OMA(OMS(`sym`),List(tm,tp)) => Some((tm,tp))
      case _ => None
    }
  }

  val informal = new {
    val sym = mmtmeta_path ? "informalsym"
    val op = new {
      val opsym = mmtmeta_path ? "informalapply"

      def apply(label: String, args: List[Term]): Term = {
        if (label == "math" && args.length == 1) args.head else
        OMA(OMS(opsym), StringLiterals(label) :: args)
      }

      def unapply(tm: Term) = tm match {
        case OMA(OMS(`opsym`), StringLiterals(label) :: args) => Some((label, args))
        case _ => None
      }
    }

    def apply(n: Node) = OMA(OMS(sym), OMFOREIGN(n) :: Nil)

    def unapply(tm: Term) = tm match {
      case OMA(OMS(`sym`), OMFOREIGN(n) :: Nil) =>
        Some(n)
      case _ => None
    }
  }

  val flatseq = new {
    val sym = meta_path ? "sequence expression"

    def apply(tms: List[Term]) = OMA(OMS(sym), tms)

    def unapply(tm: Term) = tm match {
      case OMA(OMS(`sym`), ls) => Some(ls)
      case _ => None
    }

    val tp = new {
      val sym = meta_path ? "sequence type"

      def apply(tp: Term /*,lower:Term,upper:Term*/) = OMA(OMS(sym), List(tp /*,lower,upper*/))

      def unapply(tm: Term) = tm match {
        case OMA(OMS(`sym`), List(tp /*,lower,upper*/)) => Some(tp)
        case _ => None
      }
    }
  }


  val binder = new {
    val path = meta_path ? "bind"

    def apply(ctx: Context, body: Term) : Term = if (ctx.isEmpty) body else OMBIND(OMS(path), ctx, body)

    def apply(vd:VarDecl, body: Term) = OMBIND(OMS(path), Context(vd), body)

    def unapply(tm: Term) = tm match {
      case OMBIND(OMS(`path`), Context(vd, rest@_*), bd) =>
        if (rest.isEmpty) Some((vd, bd)) else Some((vd, apply(Context(rest: _*), bd)))
      case _ => None
    }
  }

  val implicit_binder = new {
    val path = meta_path ? "implicit bind"

    def apply(ctx: Context, body: Term) : Term = if (ctx.isEmpty) body else OMBIND(OMS(path), ctx, body)

    def apply(ln: LocalName, tp: Option[Term], body: Term) = OMBIND(OMS(path), Context(tp match {
      case Some(t) => OMV(ln) % t
      case None => VarDecl(ln)
    }), body)

    def unapply(tm: Term) = tm match {
      case OMBIND(OMS(`path`), Context(vd, rest@_*), bd) =>
        if (rest.isEmpty) Some(vd.name, vd.tp, bd) else Some(vd.name, vd.tp, apply(Context(rest: _*), bd))
      case _ => None
    }
    private val self = this
    val spine = new {
      def unapply(tm: Term) : Option[(Context,Term)] = tm match {
        case self(l,tp,bd) => unapply(bd) match {
          case Some((ct,r)) => Some(Context(VarDecl(l,None,tp,None,None)) ++ ct,r)
          case _ => Some(Context(VarDecl(l,None,tp,None,None)),bd)
        }
        case _ => None
      }
    }
  }

  val prop = meta_path ? "proposition"

  val apply = meta_path ? "apply"


  import info.kwarc.mmt.api.objects.Conversions._

  val seqfoldleft = new {
    val sym = meta_path ? "fold left"

    def apply(init: Term, seq: Term, v1: LocalName, tp1: Term, v2: LocalName, tp2: Term, f: Term) =
      OMBINDC(OMS(sym),Context(v1%tp1,v2%tp2),init :: seq :: f :: Nil)//SOMB(OMS(sym), STerm(init), STerm(seq), SCtx(Context(v1 % tp1)), SCtx(v2 % tp2), STerm(f))

    def unapply(tm: Term) = tm match {
      case OMBINDC(OMS(`sym`), Context(v1,v2),List(init,seq,f)) =>
        Some((seq, init, v1.name, v1.tp, v2.name, v2.tp, f))
      case _ => None
    }
  }

  val seqfoldright = new {
    val sym = meta_path ? "fold right"

    def apply(init: Term, seq: Term, v1: LocalName, tp1: Term, v2: LocalName, tp2: Term, f: Term) =
      OMBINDC(OMS(sym), Context(v1 % tp1, v2 % tp2), init :: seq :: f :: Nil) //SOMB(OMS(sym), STerm(init), STerm(seq), SCtx(Context(v1 % tp1)), SCtx(v2 % tp2), STerm(f))

    def unapply(tm: Term) = tm match {
      case OMBINDC(OMS(`sym`), Context(v1, v2), List(init, seq, f)) =>
        Some((seq, init, v1.name, v1.tp, v2.name, v2.tp, f))
      case _ => None
    }
  }

  val seqhead = new {
    val sym = meta_path ? "head"

    def apply(seq: Term) = OMA(OMS(`sym`), List(seq))

    def unapply(tm: Term) = tm match {
      case OMA(OMS(`sym`), List(seq)) =>
        Some(seq)
      case _ => None
    }
  }

  val seqtail = new {
    val sym = meta_path ? "tail"

    def apply(seq: Term) = OMA(OMS(`sym`), List(seq))

    def unapply(tm: Term) = tm match {
      case OMA(OMS(`sym`), seq) =>
        Some(seq)
      case _ => None
    }
  }

  val seqlast = new {
    val sym = meta_path ? "last"

    def apply(seq: Term) = OMA(OMS(`sym`), List(seq))

    def unapply(tm: Term) = tm match {
      case OMA(OMS(`sym`), List(seq)) =>
        Some(seq)
      case _ => None
    }
  }

  val seqinit = new {
    val sym = meta_path ? "init"

    def apply(seq: Term) = OMA(OMS(`sym`), List(seq))

    def unapply(tm: Term) = tm match {
      case OMA(OMS(`sym`), List(seq)) =>
        Some(seq)
      case _ => None
    }
  }


  val seqmap = new {
    val sym = meta_path ? "sequence map"

    def apply(f: Term, seq: Term) = OMA(OMS(sym), List(f,seq))

    def unapply(tm: Term) = tm match {
      case OMA(OMS(`sym`),List(f,seq)) => Some((f,seq))
      case _ => None
    }
  }

  val seqprepend = new {
    val sym = meta_path ? "seqprepend"

    def apply(a: Term, seq: Term) = OMA(OMS(`sym`), List(a, seq))

    def unapply(tm: Term) = tm match {
      case OMA(OMS(`sym`), List(a, seq)) =>
        Some((a, seq))
      case _ => None
    }
  }

  val seqappend = new {
    val sym = meta_path ? "seqappend"

    def apply(seq: Term, a: Term) = OMA(OMS(`sym`), List(seq, a))

    def unapply(tm: Term) = tm match {
      case OMA(OMS(`sym`), List(seq, a)) =>
        Some((seq, a))
      case _ => None
    }
  }


  val judgmentholds = new {
    val sym = meta_path ? "judgmentholds"
  }

}