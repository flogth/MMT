package info.kwarc.mmt.stex.lsp

import info.kwarc.mmt.api.DPath
import info.kwarc.mmt.api.archives.{Archive, RedirectableDimension}
import info.kwarc.mmt.api.frontend.Extension
import info.kwarc.mmt.api.utils.{File, JSON, JSONArray, JSONObject, JSONString, MMTSystem, URLEscaping}
import info.kwarc.mmt.api.web.{ServerRequest, ServerResponse}
import info.kwarc.mmt.stex.STeXServer
import info.kwarc.mmt.stex.search.Searcher
import info.kwarc.mmt.stex.xhtml.{HTMLParser, HTMLText}
import org.eclipse.jgit.api.Git

import java.io.{FileOutputStream, PrintWriter, StringWriter}
import java.net.URL
import java.util
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.collection.parallel.CollectionConverters.seqIsParallelizable
import scala.jdk.CollectionConverters._
import scala.util.{Success, Try}

trait MathHubEntry {
  def isLocal : Boolean
}
class MathHubGroup extends MathHubEntry {
  var id: String = null
  var isLocal = false
  var children: java.util.List[MathHubEntry] = java.util.List.of()
  def getLocal : Boolean = children.asScala.exists(_.isLocal)
}
class MathHubRepo extends MathHubEntry {
  var id: String = null
  var deps = java.util.List.of[String]()
  var isLocal = false
  var localPath : String = ""
}

class SearchResultServer extends Extension {
  lazy val server = controller.extman.get(classOf[STeXServer]).head
  var locals: List[String] = Nil
  var remotes: List[String] = Nil

  def serverReturn(request: ServerRequest): ServerResponse = request.path.lastOption match {
    case Some("searchresult") =>
      var html = MMTSystem.getResourceAsString("mmt-web/stex/mmt-viewer/index.html")
      html = html.replace("CONTENT_URL_PLACEHOLDER", "/:" + server.pathPrefix + "/searchresultI?" + request.query)
      html = html.replace("BASE_URL_PLACEHOLDER", "")
      html = html.replace("SHOW_FILE_BROWSER_PLACEHOLDER", "false")
      html = html.replace("NO_FRILLS_PLACEHOLDER", "TRUE")
      html = html.replace("CONTENT_CSS_PLACEHOLDER", "/:" + server.pathPrefix + "/css?" + request.parsedQuery("archive").getOrElse(""))
      ServerResponse(html, "text/html")
    case Some("searchresultI") =>
      val i = request.parsedQuery("num").get.toInt
      val ret = if (request.parsedQuery("type").contains("local"))
        server.present(locals(i), true)(None)
      else
        server.present(remotes(i), true)(None)
      ServerResponse.apply(ret.toString, "text/html")

      /*
      val (html, body) = server.emptydoc
      html.get("head")()().head.add(new HTMLText(body.state,
        """<link rel="stylesheet" href="/stex/rustex-min.css"></link>"""
      )) //)
      if (request.parsedQuery("type").contains("local")) {
        body.add(locals(i))
      }
      else {
        body.add(remotes(i))
      }
      body.plain.attributes.remove((body.namespace, "style"))
      html.get("body")()().foreach(_.plain.attributes.remove((body.namespace, "style")))
      body.get("div")()("paragraph").foreach { par =>
        par.plain.attributes.remove((par.namespace, "style"))
      }
      ServerResponse.apply(body.toString, "text/html")

       */
    case _ =>
      ServerResponse("Unknown request: \"" + request.path.lastOption + "\"\n" + request.query + "\n" + request.parsedQuery.pairs, "text/plain")
  }
}

trait MathHubServer { this : STeXLSPServer =>

  protected var remoteServer = "https://stexmmt.mathhub.info/:sTeX"
  private var searchinitialized = false
  lazy val searcher : Searcher = {
    searchinitialized = true
    new Searcher(controller)
  }

  lazy val searchresultserver = new SearchResultServer

  private val available_updates = mutable.HashMap.empty[Archive,List[(String,List[File])]]

  def checkArchiveUpdates(tk : Option[Int]) = {
    val archives = controller.backend.getArchives
    archives.zipWithIndex.foreach { case (a,i) =>
      tk.foreach{ t => updateProgress(t,archives.length.toFloat / (i+1).toFloat,"Checking for updates: " + a.id) }
      val src = a / info.kwarc.mmt.api.archives.source
      if (src.exists()) {
        val lm = src.descendants.map(_.lastModified()).max
        val url = URLEscaping.apply(remoteServer + "/getupdates?archive=" + a.id + "&timestamp=" + lm.toString)
        val attempt = Try(io.Source.fromURL(url)("UTF8"))
        available_updates.clear()
        if (attempt.isSuccess) {
          val res = attempt.get.toBuffer.mkString
          Try({
            val j = Try(JSON.parse(res))
            j match {
              case Success(o:JSONObject) =>
                val ret = o.flatMap{ case (JSONString(dim),JSONArray(ls@_*)) =>
                  Some((dim,ls.collect{case JSONString(f) => File(f)}.toList))
                case _ => None
                }
                if (ret.nonEmpty) available_updates(a) = ret
              case _ =>
            }
          })
        }
        // TODO
      }
    }
  }

  def searchI(q : String,tps:List[String],syms: Boolean) : (java.util.List[LSPSearchResult],java.util.List[LSPSearchResult]) = if (q.nonEmpty) {
    val archs = controller.backend.getArchives.map(_.id)
    val url = URLEscaping.apply(remoteServer + "/search?skiparchs=" +
      archs.mkString(",") +
      "&types=" + tps.mkString(",") +
      "&infors=" + syms.toString +
      "&query=" + q
    )
    val attempt = Try(io.Source.fromURL(url)("UTF8"))
    searchresultserver.remotes = Nil
     val rems = if (attempt.isSuccess) {
       val res = attempt.get.toBuffer.mkString
       val tr = Try({
         val j = Try(JSON.parse(res))
         j match {
           case Success(JSONArray(vls@_*)) =>
             val ret = vls.map { case jo: JSONObject =>
               val r = new LSPSearchResult
               r.local = false
               r.archive = jo.getAsString("archive")
               r.sourcefile = jo.getAsString("sourcefile")
               r.preview = remoteServer + "/fulldocument?archive=" + jo.getAsString("archive") + "&filepath=" + jo.getAsString("sourcefile").replace(".tex",".xhtml")
               r.html = (localServer / ":sTeX" / "searchresult").toString + "?type=remote&num=" + searchresultserver.remotes.length
               searchresultserver.remotes ::= jo.getAsString("html")
               r
             }
             searchresultserver.remotes = searchresultserver.remotes.reverse
             ret
         }
       })
       tr.getOrElse(Nil)
     } else Nil
    val local = searcher.search(q,10,tps,infors = syms)
    searchresultserver.locals = Nil
    val localres = local.map {
      case res =>
        val r = new LSPSearchResult
        r.local = true
        r.archive = res.archive
        r.sourcefile = res.sourcefile
        r.preview = localServer.toString + ":sTeX/fulldocument?archive=" + res.archive + "&filepath=" + res.sourcefile.replace(".tex",".xhtml")
        res.module match {
          case Some(mod) =>
            controller.backend.getArchive(r.archive) match {
              case Some(a) =>
                val ns = a.ns.map(_.toString).orElse(a.properties.get("source-base"))
                ns match {
                  case Some(p) if mod.toString.startsWith(p) =>
                    r.module = mod.toString.drop(p.length)
                    if (r.module.startsWith("/")) r.module = r.module.drop(1)
                  case _ =>
                }
              case _ =>
            }
          case _ =>
        }
        r.html = (localServer / ":sTeX" / "searchresult").toString + "?type=local&num=" + searchresultserver.locals.length
        val html = res.fragments.collectFirst{case p if p._1 != "title" => p._3}.getOrElse(res.fragments.head._3)
        searchresultserver.locals ::= html
        r.fileuri = (controller.backend.getArchive(res.archive).get / info.kwarc.mmt.api.archives.source / res.sourcefile).toURI.toString
        r
    }
    searchresultserver.locals = searchresultserver.locals.reverse
    (localres.asJava,rems.asJava)
  } else (Nil.asJava,Nil.asJava)

  private sealed abstract class MHE {
    def id : String
  }
  private case class Arch(a : Archive) extends MHE {
    def id = a.id
  }
  private case class Remote(j : JSONObject) extends MHE {
    def id = j.getAsString("id")
    def deps = j.getAsList(classOf[JSONString],"deps").map(_.value)
    def gituri = j.getAsString("git")
  }

  private var allRemotes : List[Remote] = Nil

  def installArchives(ids : String) = if (ids != null) {
    getAllRemotes
    val relevant = getDeps(ids,new {var ls : List[Any] = Nil}).distinct
    relevant.foreach(installArchiveI)
    client.client.updateMathHub()
  }

  private def getAllRemotes = {
    if (allRemotes.isEmpty) {
      val attempt = Try(io.Source.fromURL(remoteServer + "/allarchives")("ISO-8859-1"))
      if (attempt.isSuccess) JSON.parse(attempt.get.toBuffer.mkString) match {
        case JSONArray(vls @_*) if vls.forall(_.isInstanceOf[JSONObject]) =>
          allRemotes = vls.map(j => Remote(j.asInstanceOf[JSONObject])).toList.filterNot(j => controller.backend.getArchives.exists(_.id == j.id))
        case _ =>
      }
    }
  }

  private def getDeps(id : String,currs:{var ls : List[Any]}) : List[Remote] = allRemotes.collect{case r if (r.id == id || r.id.startsWith(id + "/")) && !currs.ls.contains(r) => r}.flatMap{r =>
    currs.ls ::= r
    r :: r.deps.flatMap(getDeps(_,currs))
  }

  private def installArchiveI(archive: Remote): Unit = {
    (mathhub_top,controller.backend.getArchive(archive.id)) match {
      case (Some(mh),None) =>
        // meta-inf
        if (archive.id.contains('/') && !archive.id.endsWith("/meta-inf")) {
          val meta = (mh / archive.id).up / "meta-inf"
          if (!meta.exists()) {
            allRemotes.find(r => r.id == archive.id.split('/').init.mkString("/") + "/meta-inf") match {
              case Some(r) => installArchiveI(r)
              case None =>
                val urls = (archive.gituri.split('/').init.mkString("/")) + "/meta-inf.git"
                val exists = try {
                  val url = new URL(urls).openStream()
                  true
                } catch {
                  case e: Exception => false
                }
                if (exists) withProgress(archive, "Installing " + archive.id, "Cloning git repository") { _ =>
                  try {
                    Git.cloneRepository().setDirectory(meta).setURI(urls).call()
                    ((), "success")
                  } catch {
                    case t: Throwable =>
                      client.log(t.getMessage)
                      val writer = new StringWriter()
                      t.printStackTrace(new PrintWriter(writer))
                      writer.toString.split('\n').foreach(client.log)
                      ((), "failed")
                  }
                }
            }
          }
        }
        withProgress(archive,"Installing " + archive.id,"Cloning git repository"){update =>
          //Thread.sleep(1000)
          try {
            Git.cloneRepository().setDirectory(mh / archive.id).setURI(archive.gituri).call()
            controller.backend.openArchive(mh / archive.id)
          } catch {
            case t : Throwable =>
              client.log(t.getMessage)
              val writer = new StringWriter()
              t.printStackTrace(new PrintWriter(writer))
              writer.toString.split('\n').foreach(client.log)
              ((),"failed")
          }
          controller.backend.getArchive(archive.id) match {
            case Some(a) =>
              update(0,"Fetching File Index...")
              val attempt = Try(io.Source.fromURL(remoteServer + "/allarchfiles?" + archive.id)("ISO-8859-1"))
              val ret = if (attempt.isSuccess) JSON.parse(attempt.get.toBuffer.mkString) match {
                case JSONArray(vls@_*) =>
                  val files = vls.flatMap { j =>
                    val dim = j.asInstanceOf[JSONObject].getAsString("dim")
                    j.asInstanceOf[JSONObject].getAsList(classOf[JSONString], "files").map(js => (dim, js.value))
                  }
                  val max = files.length
                  val count = new AtomicInteger(0)
                  val done = new AtomicInteger(0)
                  files.foreach{ case (dim, f) =>
                    val nc = count.incrementAndGet()
                    update(nc.toDouble / max, "Downloading " + (nc + 1) + "/" + max + "... (" + dim + "/" + f + ")")

                    try {
                      val src = new URL(remoteServer + "/archfile?arch=" + archive.id + "&dim=" + dim + "&file=" + f).openStream()

                      val step = 8192
                        var buf = new Array[Byte](step)
                        var pos, n = 0
                        while ({
                          if (pos + step > buf.length) buf = util.Arrays.copyOf(buf, buf.length << 1)
                          n = src.read(buf, pos, step)
                          n != -1
                        }) pos += n
                        if (pos != buf.length) buf = util.Arrays.copyOf(buf, pos)
                      src.close()

                      val file = (a / RedirectableDimension(dim) / f)
                      if (!file.up.exists()) file.up.mkdirs()
                      file.createNewFile()
                      val target = new FileOutputStream(file.toString)
                      target.write(buf)
                      target.close()



/*
                      var c = 0
                      while ( {
                        c = src.read();
                        c != -1
                      }) {
                        target.write(c)
                      }
                      src.close()
                      target.close()

 */
                    } catch {
                      case t: Throwable =>
                        println(t.getMessage)
                        print("")
                    } finally {
                      done.incrementAndGet()
                    }
                  }
                  ((), "success")
                case _ =>
                  ((), "failed")
              } else ((), "failed")
              update(1,"Loading relational information")
              a.readRelational(Nil,controller,"rel")
              if (searchinitialized) {
                searcher.addArchive(a)
              }
              ret
            case _ => ((),"failed")
          }

        }
      case _ =>
    }
  }

  def getMathHubContentI() : java.util.List[MathHubEntry] = {
    getAllRemotes
    val archives = controller.backend.getArchives.filter(_.properties.get("format").map(_.toLowerCase).contains("stex")).map(Arch)
    val all = archives ::: allRemotes.filterNot(_.id == "MMT/urtheories")
    val ret = all.map(_.id.split('/').head).distinct.map{ s => makeEntries(all,List(s)) }
    ret.asJava
  }
  private def makeEntries(archives: List[MHE],path : List[String]) : MathHubEntry = {
    archives.find(_.id == path.mkString("/")) match {
      case Some(Arch(a)) =>
        val ret = new MathHubRepo
        ret.id = a.id.split('/').last
        ret.deps = a.dependencies.asJava
        ret.localPath = a.root.toString
        ret.isLocal = true
        ret
      case Some(Remote(j)) =>
        val ret = new MathHubRepo
        ret.id = j.getAsString("id").split("/").last
        ret.isLocal = false
        ret
      case _ =>
        val validarchs = archives.filter(_.id.startsWith(path.mkString("/") + "/"))
        val ret = new MathHubGroup
        ret.id = path.last
        val prefixes = validarchs.map(_.id.split('/').drop(path.length).head).distinct
        ret.children = prefixes.map(s => makeEntries(validarchs,path ::: s :: Nil)).asJava
        if (ret.getLocal) ret.isLocal = true
        ret
    }
  }


}
