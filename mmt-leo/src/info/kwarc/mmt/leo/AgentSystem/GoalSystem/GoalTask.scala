package info.kwarc.mmt.leo.AgentSystem.GoalSystem

import info.kwarc.mmt.api.frontend.Controller
import info.kwarc.mmt.leo.AgentSystem.{Blackboard, Section, Task}


/**
 * Created by Mark on 7/24/2015.
 *
 * Classes for the Expansion and Search tasks
 */
abstract class MMTTask(agent:GoalAgent)(implicit controller: Controller) extends Task {
  override val name: String = "GoalTask"

  override val sentBy: GoalAgent = agent

  val proofSection = sentBy.blackboard.get.proofSection

  def goal = proofSection.data

  val factSection = sentBy.blackboard.get.factSection

  def facts = factSection.data

  def presentObj = sentBy.presentObj

  def rules = sentBy.rules

}

abstract class GoalTask(agent:GoalAgent,g:Goal)(implicit controller: Controller) extends MMTTask(agent) {

  /** Determines if a given task is applicable given the current blackboard */
  override def isApplicable[BB <: Blackboard](b: BB): Boolean = !sentBy.ignoreGoal(g) //TODO expand this

  //For now give all tasks simplification abilities
  /** statefully changes g to a simpler goal */
  protected def simplifyGoal(g: Goal) {
    g.setConc(controller.simplifier(g.conc, g.fullContext, rules), facts)
    proofSection.passiveChange(g)
  }

  /** simplify a fact */
  protected def simplifyFact(f: Fact): Fact = {
    val tpS = controller.simplifier(f.tp, f.goal.fullContext, rules)
    f.copy(tp = tpS)
  }

  /** Returns a set of all nodes, that will be written by the task. */
  //TODO get write and read sets working with florian's data structures
  override def writeSet(s: Section): Set[s.ObjectType] = {
    if (s == proofSection) return Set(g.asInstanceOf[s.ObjectType])
    Set.empty[s.ObjectType]
  }

  /** Returns a set of all nodes that are read for the task. */
  override def readSet(s: Section): Set[s.ObjectType] = {
    if (s == proofSection) return Set(g.asInstanceOf[s.ObjectType])
    Set.empty[s.ObjectType]
  }

  override def toString:String = {
    name+" Goal: "+g.toString
  }
  /**
   * applies one tactic to a goal and expands the resulting subgoals
   * @return true if the tactic made any progress
   */
  protected def applyAndExpand(at: ApplicableTactic, g: Goal): Boolean = {
    val alt = at.apply().getOrElse(return false)
    // simplify the new goal
    alt.subgoals.foreach { sg =>
      sg.parent = Some(g) // need to set this before working with the goal
      simplifyGoal(sg)
    }

    // avoid cycles/redundancy: skip alternatives with subgoals that we already try to solve
    val path = g.path
    val alreadyOnPath = alt.subgoals.exists { sg =>
      // TODO stronger equality
      path.exists { ag => (ag.context hasheq sg.context) && (ag.conc hasheq sg.conc) }
    }
    if (alreadyOnPath )
      return false

/*
    def altEquality(a1:Alternative,a2:Alternative):Boolean = {
      val a1sg = a1.subgoals
      var a2sg = a2.subgoals
      a1.subgoals.foreach(sg1=>a2sg=a2sg.filter(goalEquality(_,sg1)))
      if (a2sg.isEmpty) {return true}
      false
    }

    def goalEquality(ag:Goal,sg:Goal):Boolean = {(ag.context hasheq sg.context) && (ag.conc hasheq sg.conc)}

    val newAltSubgoals=alt.subgoals
    val subgoalsOnPath = path.flatMap({pg=>pg.getAlternatives.flatMap({a=>a.subgoals})})
    log ("New Alt Subgoals:" + newAltSubgoals)
    log("Subgoals on Path: " +  subgoalsOnPath)
    if  (newAltSubgoals.nonEmpty && subgoalsOnPath.nonEmpty)
      if (goalEquality(newAltSubgoals.head,subgoalsOnPath.head))
        log("HERE IS AN ERROR")

    log("Truth:"+ alt.subgoals.map { sg =>
      path.map { ag =>
        (ag.context hasheq sg.context) && (ag.conc hasheq sg.conc) }
    })
    */


    // add the alternative to the proof tree and expand the subgoals
    g.addAlternative(alt, Some(proofSection))
    log("************************* " + at.label + " at X **************************")
    log("\n" + goal.presentHtml(0)(presentObj, Some(g), Some(alt)))
    if (!g.isSolved) {
      // recursively process subgoals
      alt.subgoals.foreach { sg => expand(sg) }
    }
    true
  }


  /** exhaustively applies invertible tactics to a goal */
  protected def expand(g: Goal) {
    g.setExpansionTactics(blackboard.get, agent.invertibleBackward, agent.invertibleForward)
    g.getNextExpansion match {
      case Some(at) =>
        // apply the next invertible tactic, if any
        val applicable = applyAndExpand(at, g)
        if (!applicable)
        // at not applicable, try next tactic
          expand(g)
      case None =>
        g.setSearchTactics(blackboard.get, agent.searchBackward)
    }
  }

}



case class SearchBackwardTask(agent:SearchBackwardAgent,g:Goal)(implicit controller: Controller) extends GoalTask(agent,g) {
  override val name="SearchBackwardTask"
  /** Determines if a given task is applicable given the current blackboard */


  private def backwardSearch(g: Goal) {
    log("recursing at:" + g)
    // recurse into subgoals first so that we do not recurse into freshly-added goals
    g.getAlternatives.foreach {case Alternative(sgs,_) =>
      sgs.foreach {sg => backwardSearch(sg)}
      if (g.isSolved) return
    }
    // backward search at g
    // new goals are expanded immediately and are subject to forward/backward search in the next iteration

    log("Backward Search at:" + g)
    log("Facts are:" + facts)
    val tactics=g.getNextSearch(blackboard.get)
    log("Backward tacics Are:" + tactics)
    tactics.foreach {at =>
      applyAndExpand(at, g)
      if (g.isSolved) return
    }
  }

  def execute()={
    if (blackboard.get.cycle==0){expand(g); g.isSolved}
    val out=backwardSearch(g)
    true
  }
}

case class SearchForwardTask(agent:SearchForwardAgent)(implicit controller: Controller) extends MMTTask(agent) {
  override def logPrefix="SearchForwardTask"

  /** Determines if a given task is applicable given the current blackboard */
  override def isApplicable[BB <: Blackboard](b: BB): Boolean = !goal.isSolved

  def forwardSearch() {
    log("Performing forward search")
    agent.searchForward.foreach {e =>
      e.generate(blackboard.get,interactive = false)
    }
    facts.integrateFutureFacts(Some(factSection))
  }

  override def writeSet(s: Section): Set[s.ObjectType] = {
    Set.empty[s.ObjectType]
  }

  /** Returns a set of all nodes that are read for the task. */
  override def readSet(s: Section): Set[s.ObjectType] = {
    Set.empty[s.ObjectType]
  }

  def execute()={
    log("Forward Search Executing")
    forwardSearch()
    log("Integrating new facts")
    goal.newFacts(facts)
    true
  }

}