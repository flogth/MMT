package info.kwarc.mmt.lf

import info.kwarc.mmt.api._
import frontend._
import modules._
import symbols._
import objects._
import checking._
import uom._

/** realize an LF-type/function as a [[SemanticType]] or [[SemanticOperator]] */
object Realize extends ParametricRule {
  
   /** finds the semantic type that was previously declared to realized a syntactic type */
   private def getSemanticType(controller: Controller, thy: MPath, synTp: Term): SemanticType = {
     controller.globalLookup.getDeclarationsInScope(OMMOD(thy)).foreach {
       case rc: RuleConstant => rc.df match {
         case Some(rt: RealizedType) if rt.synType == synTp => return rt.semType
         case _ =>
       }
       case _ =>
     }
     throw ParseError("no realized type known: " + synTp)
   }

   def apply(controller: Controller, thy: MPath, args: List[Term]) = {
     if (args.length != 2) throw ParseError("two arguments expected")
     val List(syn,sem) = args
     val mp = sem match {
       case OMMOD(mp) => mp
       case _ =>  throw ParseError("semantic elemetn must be identifier")
     }
     val obj = controller.backend.loadObject(mp)
     obj match {
       case st: SemanticType =>
         val synC = Solver.check(controller, Stack(Context(thy)), syn)
         synC match {
           case Right(solver) => throw ParseError("type must be LF-type: " + syn) 
           case Left((synR,_)) => RealizedType(synR, st)
         }
       case semOp: SemanticOperator =>
         val synP = syn match {
           case OMS(p) => p
           case _ => throw ParseError("realized operator must be an identifier")
         }
         val synTp = controller.globalLookup.getO(synP) match {
           //TODO RuleConstantInterpreter must be called during checking, not parsing
           case Some(c: Constant) => c.tpC.getAnalyzedIfFullyChecked.getOrElse {
             throw ParseError("type not present or not fully checked")
           }
           case _ => throw ParseError("realized operator must be a constant")
         }
         val FunType(from, to) = synTp
         if (from.exists(_._1.isDefined))
           throw ParseError("can only handle simple function types")
         val args = from.map(_._2)
         if (args.length != semOp.arity)
           throw ParseError("semantic operator has wrong arity")
         val expSynOpTp = SynOpType(args,to)
         def gST(tp: Term) = getSemanticType(controller, thy, tp)
         val expSemOpTp = SemOpType(args map gST, gST(to))
         if (!semOp.getTypes.contains(expSemOpTp))
           throw ParseError("semantic operator has wrong type")
         RealizedOperator(synP, expSynOpTp, semOp, expSemOpTp)
       case _ => throw ParseError("objects exists but is not a semantic type or operator")
     }
   }
}