import DiagramOperatorTest.controller
import info.kwarc.mmt.api.presentation.{ConsoleWriter, FlatMMTSyntaxPresenter, MMTSyntaxPresenter}
import info.kwarc.mmt.api.utils.URI
import info.kwarc.mmt.api.{DPath, Path}

trait DiagramOperatorHelper {
  var presenter: MMTSyntaxPresenter = _

  /**
    * Waits - possibly ad infinitum - for the object identified by the path to appear in the [[controller]].
    *
    * @param path A path to a theory, document etc.
    */
  final protected def waitUntilAvailable(path: Path): Unit = {
    while (controller.getO(path).isEmpty) {
      Thread.sleep(500)
    }
  }

  final protected def waitThenPrint(path: Path): Unit = {
    waitUntilAvailable(path)
    presenter(controller.get(path))(ConsoleWriter)
    print("\n")
  }

  final protected val diagops: DPath = DPath(URI("https://example.com/diagops"))
  final protected val typeindexifier: DPath = diagops / "typeindexifier"
  final protected val typifier: DPath = diagops / "typifier"
  final protected val pushout: DPath = diagops / "pushout"

  final protected def space(): Unit = {
    print("\n".repeat(5))
  }
}

/**
  * Playground for Navid's implementation of diagram operators.
  * For debugging purposes only - might contain dirty code.
  *
  * @author Navid
  */
object DiagramOperatorTest extends MagicTest("debug"/*, "DiagramDefinition"*/) with DiagramOperatorHelper {

  override def doFirst: Unit = {
    // Only uncomment if rebuild is really necessary
    // hl("build MMT/urtheories -mmt-omdoc")
    // hl("build MMT/urtheories mmt-omdoc")

    // Only uncomment if rebuild is really necessary
    // hl("build MitM/Foundation mmt-omdoc")

    // Clean first to prevent some spurious caching errors
    hl("build Playground/diagops -mmt-omdoc")
    hl("build Playground/diagops mmt-omdoc")

    presenter = new FlatMMTSyntaxPresenter()
    controller.extman.addExtension(presenter)
  }

  // This [[run]] method is run in parallel to the build process started above in [[doFirst]],
  // hence, we apply some dirty waiting mechanism here.
  override def run: Unit = {
    // Demo MultiTypeIndexifier and extension to morphisms
    waitThenPrint(typeindexifier ? "EndoMagma_pres")
    waitThenPrint(typeindexifier ? "EndoMagma_https:%2F%2Fexample.com%2Fdiagops%2Ftypeindexifier%3FOppositeMagma")
    waitThenPrint(typeindexifier ? "EndoMonoid_https:%2F%2Fexample.com%2Fdiagops%2Ftypeindexifier%3FOppositeMonoid")
    waitThenPrint(typeindexifier ? "MultiTypeIndexedTestTheory_pres")

    space()

    waitThenPrint(typeindexifier ? "EndoMagmaSingle_pres")

    waitThenPrint(typeindexifier ? "EndoMagmaSingle_pres")
    waitThenPrint(typeindexifier ? "EndoMagmaSingle_https:%2F%2Fexample.com%2Fdiagops%2Ftypeindexifier%3FOppositeMagma")
    waitThenPrint(typeindexifier ? "SingleTypeIndexedTestTheory_pres")

    space()

    waitThenPrint(typifier ? "TypifySFOLTheory_pres")

    space()

    waitThenPrint((pushout / "list") ? "ListNat_pres")
    waitThenPrint((pushout / "nvs") ? "ThingsInNormedVectorspace_pres")

    sys.exit(0)
  }
}

/**
  * Debugging playground for Navid's implementation of diagram operators.
  * For debugging-debugging purposes only - might contain dirty code.
  *
  * @author Navid
  */
object DiagramOperatorDebug extends MagicTest("debug", "DiagramDefinition") with DiagramOperatorHelper {
  override def doFirst: Unit = {
    hl("build MMT/LATIN2 scala-bin")
    hl("build MMT/LATIN2 mmt-omdoc type_theory/operators.mmt")
    // hl("build MMT/LATIN2 lf-scala")

    presenter = new FlatMMTSyntaxPresenter()
    controller.extman.addExtension(presenter)
  }

  override def run: Unit = {
    waitThenPrint(DPath(URI("latin:/")) ? "TestEndoMagmaSingle_pres")
    waitThenPrint(DPath(URI("latin:/")) ? "TestEndoMagmaMulti_pres")
  }
}

