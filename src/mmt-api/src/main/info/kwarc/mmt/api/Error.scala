package info.kwarc.mmt.api

import Level.Level
import parser.SourceRef
import utils._

import scala.xml._

/** The superclass of all Errors generated by MMT
  *
  * @param shortMsg the error message
  */
abstract class Error(val shortMsg: String) extends java.lang.Exception(shortMsg) {
  /** additional message text, override as needed */
  def extraMessage: String = ""

  /** the severity of the error, override as needed */
  def level: Level = Level.Error
  /** none by default, override for excusable errors */
  val excuse: Option[Level.Excuse] = None

  /** returns "level (excuse)" */
  def levelString = level.toString + excuse.fold("")(e => s" ($e)")

  // this field is transient as some Throwables are not actually serialisable
  @transient private var causedBy: Option[Throwable] = None
  def getCausedBy : Option[Throwable] = causedBy
  /** get the error due to which this error was thrown */
  def setCausedBy(e: Throwable): this.type = {causedBy = Some(e); this}

  def getAllCausedBy: List[Throwable] = getCausedBy match {
    case None => Nil
    case Some(e: Error) =>
      e :: e.getAllCausedBy
    case Some(e) => List(e)
  }

  protected def causedByToNode = causedBy match {
    case Some(e: Error) => e.toNode
    case Some(e) => <cause type={e.getClass.getName} shortMsg={e.getMessage}>{Stacktrace.asNode(e)}</cause>
    case None => Nil
  }

  protected def causedByToString = causedBy match {
    case None => ""
    case Some(e: Error) => "\n\ncaused by\n" + e.toStringLong
    case Some(e) => "\n\ncaused by\n" + e.getClass + ": " + e.getMessage + e.getStackTrace.map(_.toString).mkString("\n", "\n", "")
  }

  override def toString = shortMsg

  def toStringLong: String = {
    shortMsg + "\n" + extraMessage + "\ndetected at\n" + Stacktrace.asString(this) + causedByToString
  }

  def toNode: Elem =
      <error type={getClass.getName} shortMsg={shortMsg} level={level.toString}>
         {if (extraMessage.isEmpty) Nil else extraMessage}
         {Stacktrace.asNode(this)}
         {causedByToNode}
      </error>

  def toHTML: String = HTML.build { h => import h._
    def trace(t: Throwable): Unit = {
      div("stacktrace") {
        Stacktrace.asStringList(t).foreach { s =>
          div("stacktraceline") {
            span {
              text {
                s
              }
            }
          }
        }
      }
    }
    div("error") {
      div {
        span("name") {
          text(this.getClass.getName)
        }
        text(" of level ")
        span("level") {
          text(level.toString)
        }
      }
      div("message") {
        text(shortMsg)
      }
      if (!extraMessage.isEmpty) div {
        text {
          extraMessage
        }
      }
      trace(this)
      causedBy.foreach {e =>
        div {text {"caused by"}}
        div("cause") {
          e match {
            case e: Error => div {
              literal(e.toHTML)
            }
            case e: Throwable => div {
              text {
                e.getClass.toString + " : " + e.getMessage
              }
              trace(e)
            }
          }
        }
      }
    }
  }
}

object Error {
  /** converts java exception to MMT error, identity on MMT errors */
  def apply(e: Exception): Error = e match {
    case e: Error => e
    case e: Exception => GeneralError("unknown error").setCausedBy(e)
  }
}

/** auxiliary functions for handling Java stack traces */
object Stacktrace {
  def asStringList(e: Throwable): List[String] = e.getStackTrace.map(_.toString).toList

  def asString(e: Throwable): String = asStringList(e).mkString("", "\n", "")

  def asNode(e: Throwable): Seq[Node] with Serializable = asStringList(e) match {
    case Nil => Nil
    case st => <stacktrace>{st.map(s => <element>{s}</element>)}</stacktrace>
  }
}

object Level {
  /** error levels, see [[Error]]
    *
    * even fatal errors can be ignored (by comparison)
    */
  sealed abstract class Level(val toInt: Int,str: String) extends Ordered[Level] {
    override def toString = str
    def compare(that: Level) = this.toInt - that.toInt
  }

  case object Info extends Level(0,"info")
  case object Warning extends Level(1,"warning")
  case object Error extends Level(2,"error")
  case object Fatal extends Level(3,"fatal error")

  val levels = List(Info,Warning,Error,Fatal)
  def parse(s: String): Level = {
    levels.find(l => l.toInt.toString == s || l.toString == s).getOrElse {
      if (s.isEmpty) Error else throw ParseError("unknown error level: " + s)
    }
  }

  /** errors may carry an excuse why the error may be acceptable */
  sealed abstract class Excuse(str: String) {
    override def toString = str
  }
  /** partial view, unresolved _, etc. */
  case object Gap extends Excuse("gap")
  /** MMT incompleteness */
  case object Limitation extends Excuse("limitation")
  def excuseOStr(e: Option[Excuse]) = e.fold("unexcused error")(_.toString)
}

/** errors in user content */
trait ContentError {
  def sourceRef: Option[SourceRef]
  def logicalRef: Option[Path]
}

/** errors tied to a structural element */
trait StructuralElementError extends ContentError {
  def elem: StructuralElement
  def sourceRef = SourceRef.get(elem)
  def logicalRef = Some(elem.path)
}

/** other errors that occur during parsing */
case class ParseError(s: String) extends Error("parse error: " + s)

/** errors that occur when parsing a knowledge item */
case class SourceError(origin: String, ref: SourceRef, mainMessage: String, extraMessages: List[String] = Nil,
                       override val level: Level = Level.Error) extends Error(mainMessage) with ContentError {

  override def extraMessage: String = s"source error ($origin) at " + ref.toString + extraMessages.mkString("\n", "\n", "\n")
  override def toNode: Elem = xml.addAttr(xml.addAttr(super.toNode, "sref", ref.toString), "target", origin)
  def sourceRef = Some(ref)
  def logicalRef = None
}

/** errors that occur during compiling */
object CompilerError {
  def apply(key: String, ref: SourceRef, messageList: List[String], level: Level): SourceError =
    SourceError(key, ref, messageList.head, messageList.tail, level)
}

/** errors that occur when checking a knowledge item (generated by the Checker classes) */
abstract class Invalid(s: String) extends Error(s) with ContentError

/** errors that occur when structural elements are invalid */
case class InvalidElement(elem: StructuralElement, s: String) extends Invalid(s"invalid element ${elem.path}: $s") with StructuralElementError

/** errors that occur when objects are invalid */
case class InvalidObject(obj: objects.Obj, s: String) extends Invalid(s"invalid object, $s: " + obj) {
  def sourceRef = SourceRef.get(obj)
  def logicalRef = None
}

/** errors that occur when judgements do not hold */
case class InvalidUnit(unit: checking.CheckingUnit, history: checking.History, msg: String) extends Invalid(s"invalid unit: $msg") {
  def sourceRef = {
    // some WFJudgement must exist because we always start with it
    history.getSteps.mapFind {s =>
      s.removeWrappers match {
        case j: objects.WFJudgement =>
          SourceRef.get(j.wfo)
        case _ =>
          None
      }
    }
  }
  def logicalRef = unit.component
}

/** run time error thrown by executing invalid program */
case class ExecutionError(msg: String) extends Error(msg)

/** other errors */
case class GeneralError(s: String) extends Error("general error: " + s) {
  override def level = Level.Fatal
}

/** errors during library operations */
abstract class LibraryError(s: String) extends Error(s) with ContentError

/** errors that occur when adding a knowledge item */
case class AddError(elem: StructuralElement, s: String) extends
    LibraryError(s"error adding ${elem.path}: $s") with StructuralElementError

/** errors that occur when updating a knowledge item */
case class UpdateError(elem: StructuralElement, s: String) extends
    LibraryError(s"error updating ${elem.path}: $s") with StructuralElementError

/** errors that occur when deleting a knowledge item */
case class DeleteError(path: Path, s: String) extends LibraryError(s"error deleting $path: $s") {
  def sourceRef = None
  def logicalRef = None
}

/** errors that occur when retrieving a knowledge item */
case class GetError(path: Path, s: String) extends LibraryError(s"error getting $path: $s") {
  def sourceRef = None
  def logicalRef = None
}

/** errors that occur when the backend believes it should find an applicable resource but cannot */
case class BackendError(s: String, p: Path) extends Error(s"error retrieving resource $p: $s")
/** general errors involving archives */
case class ArchiveError(id: String, msg: String) extends Error(s"error regarding archive $id: $msg")

/** errors that occur when a configuration entry is missing */
case class ConfigurationError(id: String) extends Error(s"no entry for $id in current configuration")

/** errors that occur when presenting a knowledge item */
case class PresentationError(s: String) extends Error(s)

/** errors that occur when registering extensions  */
case class RegistrationError(s: String) extends Error(s)

/** errors that are not supposed to occur, e.g., when input violates the precondition of a method */
case class ImplementationError(s: String) extends Error("implementation error: " + s)

/** errors involving retrieval of parts of objects */
abstract class ObjectError(msg: String) extends Error(msg)
/** lookup in the context */
case class LookupError(name: LocalName, context: objects.Context) extends ObjectError("variable " + name.toString + " not declared in context " + context)
/** lookup in a substitution */
case class SubstitutionUndefined(name: LocalName, m: String) extends ObjectError("Substitution undefined at " + name.toString + "; " + m)
/** lookup in the context */
case class SubobjectError(obj: objects.Obj, pos: objects.Position) extends ObjectError(s"position $pos does not exist in $obj")

case class HeapLookupError(name: LocalName) extends Error("variable " + name.toString + " not declared")

/** base class for errors that are thrown by an extension */
abstract class ExtensionError(prefix: String, s: String) extends Error(prefix + ": " + s)

/** the type of continuation functions for error handling
  *
  * An ErrorHandler is passed in most situations in which a component (in particular [[archives.BuildTarget]]s)
  * might produce a non-fatal error.
  */
abstract class ErrorHandler {
  /** true if an error was added since last reset */
  protected var newErrors = false
  def reset = {
    newErrors = false
  }
  /** true if errors occurred since last reset */
  def hasNewErrors: Boolean = newErrors

  /** registers an error
    *
    * This should be called exactly once on every error, usually in the order in which they are found.
    */
  def apply(e: Error): Unit = {
    if (e.level > Level.Warning) {
      newErrors = true
    }
    addError(e)
  }

  /** convenience for apply */
  def <<(e: Error): Unit = {
    apply(e)
  }

  /** evaluates a command with this class as the exception handler */
  def catchIn(a: => Unit): Unit = {
    try {
      a
    } catch {
      case e: Error => addError(e)
    }
  }

  protected def addError(e: Error): Unit
}


/** Filters errors before passing them to the another error handler */
abstract class FilteringErrorHandler(handler: ErrorHandler) extends ErrorHandler {
  def filter(e:Error): Boolean
  override def apply(e: Error) = {
    if (filter(e)) {
      newErrors = true
      handler.apply(e)
    } //otherwise ignore
  }
  def addError(e : Error) = {} //nothing to do here, not called
}

/** handles only error at or above a certain threshold */
class HandlerWithTreshold(handler: ErrorHandler, threshold: Level.Level) extends FilteringErrorHandler(handler) {
  def filter(e: Error) = e.level >= threshold
}

/** trivial filter that accepts everything; still useful because it allows tracking if new errors have occurred */
class TrackingHandler(handler: ErrorHandler) extends FilteringErrorHandler(handler) {
  def filter(e: Error) = true
}

/** an error handler that needs opening and closing */
abstract class OpenCloseHandler extends ErrorHandler {
  def open: Unit
  def close: Unit
}

/** combines the actions of multiple handlers */
class MultipleErrorHandler(val handlers: List[ErrorHandler]) extends OpenCloseHandler {
  def addError(e: Error): Unit = {
    handlers.foreach(_.apply(e))
  }
  def open: Unit = {
    handlers.foreach {
      case h: OpenCloseHandler => h.open
      case _ =>
    }
  }
  def close: Unit = {
    handlers.foreach {
      case h: OpenCloseHandler => h.open
      case _ =>
    }
  }
}
object MultipleErrorHandler {
  /** creates a MultipleErrorHandler and adds error reporting if not also present */
  def apply(hs: List[ErrorHandler], rep: frontend.Report) = {
    val handlers = hs.flatMap(handlersFlat)
    val alreadyReports = handlers.exists {
      case h: ErrorLogger => h.report == rep
      case _ => false
    }
    val errorLogger = if (alreadyReports) Nil else List(new ErrorLogger(rep))
    new MultipleErrorHandler(errorLogger:::handlers)
  }
  /** all atomic handlers, i.e., flattening out the multiple handlers */
  def handlersFlat(e: ErrorHandler): List[ErrorHandler] = e match {
    case me: MultipleErrorHandler => me.handlers.flatMap(handlersFlat)
    case h => List(h)
  }
}

/** stores errors in a list */
class ErrorContainer extends ErrorHandler {
  private var errors: List[Error] = Nil
  protected def addError(e: Error): Unit = {
    this.synchronized {
      errors ::= e
    }
  }
  def isEmpty: Boolean = errors.isEmpty
  override def reset: Unit = {
    errors = Nil
    super.reset
  }
  def getErrors: List[Error] = errors.reverse
  def maxLevel = if (errors.isEmpty) Level.Info else errors.map(_.level).max
}

/** writes errors to a file in XML syntax
  *
  * @param fileName the file to write the errors into (convention: file ending 'err')
  */
class ErrorWriter(fileName: File) extends OpenCloseHandler {
  private var file: StandardPrintWriter = null

  protected def addError(e: Error): Unit = {
    if (file == null) open
    file.write(new PrettyPrinter(240, 2).format(e.toNode) + "\n")
  }

  def open: Unit = {
    file = File.Writer(fileName)
    file.write("<errors>\n")
  }
  /** closes the file */
  def close: Unit = {
    file.write("</errors>\n")
    file.close()
  }
}

/** reports errors */
class ErrorLogger(val report: frontend.Report) extends ErrorHandler {
  protected def addError(e: Error): Unit = {
    report(e)
  }
}

/** throws errors */
object ErrorThrower extends ErrorHandler {
  protected def addError(e: Error): Unit = {
    throw e
  }
}
