package info.kwarc.mmt.api.ontology
import info.kwarc.mmt.api._
import utils._
import documents._
import modules._
import archives._
import java.util.ResourceBundle.Control
import info.kwarc.mmt.api.frontend.Controller

class StatisticsExporter extends Exporter { // TODO adapt to rdf
  def key = "statistics"

  override def outExt = "json"

  def rs = controller.depstore match {
    case rl:ClassicRelStore => Some(rl)
    //case rl: RelStore if rl.classic.isDefined => rl.classic
    case _ => None
  }

  /**
    * Get the statistic for the document convert it to json and write it to the respective file in export
    * @param doc the document to make the statistics for
    * @param bf the build task
    */  
  def exportDocument(doc: Document, bf: BuildTask): Unit = rs.foreach { rs =>
    log("[  -> statistics]     "+doc.path.toPath+" "+bf.outFile.toString())
    rh(rs.makeStatistics(doc.path).toJSON.toString)
    val root = bf.archive.rootString
    val filename : String = (root+"/export/statistics/description_of_statistics_keys.json")
    log(filename)
    val usageFile = File(filename)
    utils.File.write(usageFile, rs.statDescription.toString())
  }

  /**
    * Get the statistic for the theory convert it to json and write it to the respective file in export
    * @param doc the theory to make the statistics for
    * @param bf the build task
    */
  def exportTheory(thy: Theory, bf: BuildTask): Unit = rs.foreach { rs =>
    rh(rs.makeStatistics(thy.path).toJSON.toString)
  }
  def exportView(view: View, bf: BuildTask): Unit =  {}
  def exportNamespace(dpath: DPath, bd: BuildTask, namespaces: List[BuildTask], modules: List[BuildTask]): Unit = {}
}
