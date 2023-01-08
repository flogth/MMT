package info.kwarc.mmt.stex.Extensions

import info.kwarc.mmt.api.documents.{DRef, Document, MRef}
import info.kwarc.mmt.api.modules.Theory
import info.kwarc.mmt.api.objects.OMS
import info.kwarc.mmt.api.symbols.{Constant, Declaration, Include, NestedModule, Structure}
import info.kwarc.mmt.api.{DPath, DefComponent, GlobalName, MPath, Path, TypeComponent}
import info.kwarc.mmt.api.utils.{FilePath, MMTSystem}
import info.kwarc.mmt.api.web.{ServerRequest, ServerResponse}
import info.kwarc.mmt.stex.rules.ModelsOf
import info.kwarc.mmt.stex.xhtml.HTMLParser
import info.kwarc.mmt.stex.xhtml.HTMLParser.ParsingState
import info.kwarc.mmt.stex.{STeXPresenterML, STeXPresenterTex, STeXServer}

import scala.xml.{Node, NodeSeq}

trait OMDocHTML { this : STeXServer =>

  protected def omdocRequest(request: ServerRequest): ServerResponse = {
    request.path.lastOption match {
      case Some("omdoc") =>
        request.query match {
          case "" =>
            ServerResponse("Empty query", "txt")
          case s =>
            if (s startsWith "archive=") {
              val (id, path,lang) = {
                s.drop(8).split('&') match {
                  case Array(s) => (s, FilePath(Nil),"")
                  case Array(s, r) if r.startsWith("filepath=") =>
                    (s, FilePath(r.drop(9).split('/').toList),"")
                  case Array(s, r, l) if r.startsWith("filepath=") && l.startsWith("language=") =>
                    (s, FilePath(r.drop(9).split('/').toList), l.drop(9))
                  case _ =>
                    return ServerResponse(s"Malformed query string ${s}","txt")
                }
              }
              val ns = controller.backend.getArchive(id) match {
                case None => return ServerResponse(s"unknown archive id ${id}","txt")
                case Some(a) => a.narrationBase / (if (path.toString endsWith ".xhtml") path.toString.dropRight(5) + "omdoc" else path.toString)
              }
              var html = MMTSystem.getResourceAsString("mmt-web/stex/mmt-viewer/index.html")
              html = html.replace("CONTENT_URL_PLACEHOLDER", "/:" + this.pathPrefix + "/omdocfrag?" + ns + "&language=" + lang)
              html = html.replace("BASE_URL_PLACEHOLDER", "")
              ServerResponse(html, "text/html")
            } else {
              var html = MMTSystem.getResourceAsString("mmt-web/stex/mmt-viewer/index.html")
              html = html.replace("CONTENT_URL_PLACEHOLDER", "/:" + this.pathPrefix + "/omdocfrag?" + s)
              html = html.replace("BASE_URL_PLACEHOLDER", "")
              ServerResponse(html, "text/html")
            }
        }
      case Some("omdocfrag") =>
        val (comp, lang) = request.query.split('&') match {
          case Array(a) => (a, None)
          case Array(a, l) if l.startsWith("language=") => (a, if (l.drop(9).isEmpty) None else Some(l.drop(9)))
          case _ => (request.query, None)
        }
        val path = Path.parse(comp)
        val ret = path match {
          case d:DPath => omdocDoc(d,lang).toString()
          case mp:MPath => omdocModule(mp,lang).toString()
          case gn:GlobalName => omdocSymbol(gn,lang).toString()
          case _ => "<div>Invalid: " + path.toString + "</div>"
        }
        val (doc, body) = this.emptydoc
        body.add(ret)
        ServerResponse(doc.get("body")()().head.toString,"text/html")
      case _ => ServerResponse(s"Malformed URL path ${request.path}", "txt")
    }
  }

  private def doBarBlock(title:NodeSeq)(content: => NodeSeq): Node = {
    <div style="border-left:2px solid black;padding-left:5px;margin-top:15px;margin-bottom:15px">
      <div style="width:100%;position:relative;border-bottom:1px solid black;border-top:1px solid black;font-weight:bold;margin-bottom:5px;font-size:larger">
        {title}
      </div>
      <div style="margin-left:20px;width:100%;margin-top:5px">{content}</div>
    </div>
  }

  private def doBlock(title: NodeSeq)(content: => NodeSeq): Node = {
    <div style="width:100%">
      <div style="width:100%">{title}</div>
      <div style="width:100%;margin-left:20px">{content}</div>
    </div>
  }

  private def doLink(p:Path,lang:Option[String]) = s"/:${this.pathPrefix}/omdoc?${p.toString}" + (lang match {
    case Some(l) => "&language=" + l
    case _ => ""
  })
  private def doInputref(p:Path,lang:Option[String]) = <div class="inputref" data-inputref-url={doLink(p,lang)}>
    {p.toString}
  </div>
  private def omdocDoc(path:DPath,lang:Option[String]): Node = doBarBlock(
    <span style="font-size:larger">Document <a href={doLink(path,lang)} style="color:blue">{path.toString}</a></span>
  ){
    controller.getO(path) match {
      case Some(d : Document) =>
        d.getDeclarations.map{
          case dr:DRef =>
            //doInputref(dr.target,lang)
            omdocDoc(dr.target,lang)
          case m:MRef =>
            //doInputref(m.target,lang)
            omdocModule(m.target,lang)
          case o =>
            <div style="width:100%">TODO: {o.getClass}</div>
        }
      case _ =>
        <span>Document not found</span>
    }
  }

  def doModuleBody(t : Theory,lang:Option[String]) : NodeSeq = {
    t.getPrimitiveDeclarations.map {
      case c: Constant if c.metadata.get(ModelsOf.sym).nonEmpty =>
          <span/>
      case c: Constant =>
        //doInputref(c.path, lang)
        omdocSymbol(c, lang)
      case Include(i) =>
        <div style="width:100%">
          <b>Include</b>
          <a href={doLink(i.from, lang)} style="color:blue">?{i.from.name.toString}</a>
        </div>
      case nm: NestedModule =>
        nm.metadata.getValues(ModelsOf.tp).headOption match {
          case Some(OMS(const)) =>
            controller.getO(const) match {
              case Some(c: Constant) =>
                omdocStructure(nm.module.asInstanceOf[Theory], c, lang)
              case None =>
                doBarBlock(<span>Structure {const.name}</span>){
                  <span>Structure Not Found</span>
                }
            }
          case _ =>
            <div style="width:100%">TODO: Nested Module</div>
        }
      case o =>
        <div style="width:100%">TODO:
          {o.getClass}
        </div>
    }
  }
  private def omdocModule(mp:MPath,lang:Option[String]) : Node = {
    def doDecl(d : Declaration) : NodeSeq = d match {
      case Include(i) => Nil
      case c: Constant =>
        doBlock(<span><b>Term </b>{c.df.map(df => xhtmlPresenter.asXML(df,Some(c.path $ DefComponent))).getOrElse()}</span>){
          <span>Inferred Type {c.tp.map(tp => xhtmlPresenter.asXML(tp,Some(c.path $ TypeComponent))).getOrElse("(None)")}</span>
        }
      case nm : NestedModule =>
        nm.module.getPrimitiveDeclarations.flatMap(doDecl)
      case o =>
        Seq(<div style="width:100%">TODO:
          {o.getClass}
        </div>)
    }
    controller.getO(mp) match {
      case Some(t: Theory) if this.getLanguage(t).isDefined => doBlock(
        <b><span>Language Module {t.path.name} for </span><a href={doLink(t.path, lang)} style="color:blue">?{t.path.parent.last}</a></b>
      ){<div style="width:100%">
        {
          def getIncls(t : Theory) : List[MPath] =
            t.getIncludesWithoutMeta.tail ::: t.getPrimitiveDeclarations.collect{case nm : NestedModule => getIncls(nm.module.asInstanceOf[Theory])}.flatten
          val includes = getIncls(t)
          if (includes.nonEmpty) {
            <div style="width:100%">
              <b>Uses</b>{
                includes.map(p => <span style="display:inline"> <a href={doLink(p, lang)} style="color:blue">?{p.name.toString}</a></span>)
              }
            </div>
          } else <span/>
        }
        {
          t.getPrimitiveDeclarations.flatMap(doDecl)
        }</div>
      }
      case Some(t: Theory) => doBarBlock(<span>Module <a href={doLink(mp,lang)} style="color:blue">{mp.toString}</a></span>){
        doModuleBody(t,lang)
      }
      case _ => doBarBlock(<span>Module {mp.toString}</span>)(<span>Theory not found</span>)
    }
  }

  // val state = new ParsingState(controller, presentationRules)
  private def omdocSymbol(gn:GlobalName, lang: Option[String]): Node = {
    controller.getO(gn) match {
      case Some(c : Constant) => omdocSymbol(c,lang)
      case Some(o) =>
        <div style="width:100%">TODO:{o.getClass}</div>
      case None => <div style="width:100%;margin-bottom:15px;margin-top:25px">
        <div style="width:100%;font-weight:bold;margin-bottom:15px">{gn.name.toString}</div>
        <span>Symbol not found</span>
      </div>
    }
  }

  private def makeRow(first:NodeSeq)(second:NodeSeq) = <tr>
    <td style="width:1%;padding-right:5px;padding-bottom:2px;vertical-align:top">{first}</td>
    <td style="width:100%;padding-left:5px;padding-bottom:2px;vertical-align:top">{second}</td>
  </tr>

  private def omdocStructure(th:Theory,const:Constant,lang:Option[String]) = {
    doBarBlock(<span>Structure <a href={doLink(const.path,lang)} style="color:blue">{const.name}</a></span>){
      <table style="width:100%;margin-left:30px">
        {this.getMacroName(const) match {
          case Some(mn) => makeRow(<span>macro:</span>)(<code>\{mn}</code>)
          case None =>
        }}
        {
          def getIncls(t: Theory): List[MPath] = t.getIncludesWithoutMeta.flatMap(p => p :: controller.getO(p).map {
            case t : Theory => getIncls(t)
            case _ => Nil
          }.getOrElse(Nil))
          val includes = getIncls(th)
          if (includes.nonEmpty) {
            makeRow(<span>Extends:</span>)(includes.map(p => <span style="display:inline"> <a href={doLink(p, lang)} style="color:blue">?{p.name.toString}</a></span>))
          } else <span/>
        }
        {
          th.getPrimitiveDeclarations.flatMap {
            case c : Constant =>
              val macroname = this.getMacroName(c)
              Some(makeRow(<span>Field {if (macroname.isDefined) macroname.get else c.name}</span>){
                val arity = this.getArity(c)
                <table style="width:100%;margin-left:30px">
                  {if (macroname.nonEmpty && !macroname.contains(c.name.toString)) {
                    makeRow(<span>Name:</span>)(<span>{c.name}</span>)
                  }}
                  {withArguments { (getI, getX) =>
                  arity match {
                    case None | Some("") => <span/>
                    case Some(args) =>
                      makeRow(<span>Arguments:</span>)(<code>{args map {
                          case 'i' => "{" + getI + "}"
                          case 'a' => "{" + {
                            val a = getI;
                            a + "_1,...," + a + "_n"
                          } + "}"
                          case 'b' => "{" + getX + "}"
                          case 'B' => "{" + {
                            val x = getX;
                            x + "_1,...," + x + "_n"
                          } + "}"
                        }}
                      </code>)
                    case _ =>
                  }}}
                {arity.map(a => doNotations(c, a, lang)).getOrElse(doNotations(c, "", lang))}
                {c.tp.map(tp => makeRow(<span>Type:</span>)(xhtmlPresenter.asXML(tp,Some(c.path $ TypeComponent)))).getOrElse()}
                {c.df.map(df => makeRow(<span>Definiens:</span>)(xhtmlPresenter.asXML(df, Some(c.path $ DefComponent)))).getOrElse()}
                </table>
              })
            case Include(_) => None
            case s : Structure =>
              doStructure(s,lang)
            case o =>
              <span>TODO: {o.getClass}</span>
          }
        }
        {makeRow(<span/>)(<span>TODO</span>)}
      </table>
    }
  }

  private def doStructure(s : Structure,lang:Option[String]) : NodeSeq = {
    <span>TODO structure</span>
  }

  private def withArguments[A](f : ( => Char, => Char) => A): A = {
    var iidx = -1
    var vidx = -1
    val is = "abcdefghi"
    val xs = "xyzvwustr"
    def getI = { iidx += 1; is(iidx) }
    def getX = { vidx += 1; xs(vidx) }
    f(getI,getX)
  }

  private def omdocSymbol(c:Constant,lang:Option[String]): Node = doBlock(
    <b>Symbol <a href={doLink(c.path,lang)} style="color:blue">{c.name.toString}</a></b>){
      val arity = this.getArity(c)
      <table style="width:100%;margin-left:30px">
        {withArguments{(getI,getX) => (this.getMacroName(c), arity) match {
          case (Some(mn), None | Some("")) if mn.forall(_.isLetter) =>
            makeRow(<span>Syntax:</span>)(<code>\{mn}</code>)
          case (Some(mn), Some(args)) if mn.forall(_.isLetter) =>
            makeRow(<span>Syntax:</span>)(<code>\{mn}{args map {
              case 'i' => "{" + getI + "}"
              case 'a' => "{" + {
                val a = getI; a + "_1,...," + a + "_n"
              } + "}"
              case 'b' => "{" + getX + "}"
              case 'B' => "{" + {
                val x = getX; x + "_1,...," + x + "_n"
              } + "}"
            }}</code>)
          case _ =>
        }}}
        {arity.map(a => doNotations(c,a,lang)).getOrElse(doNotations(c,"",lang))}
        {c.tp.map(tp => makeRow(<span>Type:</span>)(xhtmlPresenter.asXML(tp, Some(c.path $ TypeComponent)))).getOrElse()}
        {c.df.map(df => makeRow(<span>Definiens:</span>)(xhtmlPresenter.asXML(df, Some(c.path $ DefComponent)))).getOrElse()}
      </table>
    }

  private def doOMSNotation(not : STeXNotation,doin : Boolean,lang:Option[String]) = <tr>
      <td style="padding-right:5px;padding-left:5px;text-align:center">{not.id}</td>
      <td style="padding-right:5px;padding-left:5px;text-align:center"><math xmlns={HTMLParser.ns_mml}><mrow>{not.present(Nil)}</mrow></math></td>
      <td style="padding-right:5px;padding-left:5px;text-align:center">{if (doin) <a href={doLink(not.in.path, lang)} style="color:blue">?{not.in.path.name}</a>}</td>
    </tr>
  private def doComplexNotation(not : STeXNotation,args:List[List[Node]],doin : Boolean,lang:Option[String]) = <tr>
    <td style="padding-right:5px;padding-left:5px;text-align:center">{not.id}</td>
    <td style="padding-right:5px;padding-left:5px;text-align:center"><math xmlns={HTMLParser.ns_mml}><mrow>{not.present(args)}</mrow></math></td>
    <td style="padding-right:5px;padding-left:5px;text-align:center">{not.op match {
      case Some(n) => <math xmlns={HTMLParser.ns_mml}><mrow>{n.plain.node}</mrow></math>
      case _ => "(None)"
    }}</td>
    <td style="padding-right:5px;padding-left:5px;text-align:center">{if (doin) <a href={doLink(not.in.path, lang)} style="color:blue">?{not.in.path.name}</a>}</td>
  </tr>
  private def doNotations(c : Constant,arity:String,lang:Option[String]) : NodeSeq = {
    if (arity.isEmpty) {
      this.getNotations(c) match {
        case Nil => <span/>
        case ls =>
          makeRow(<span>Notations:</span>) {
            <table>
              <tr>
                <th style="padding-right:5px;padding-left:5px;text-align:center">id</th>
                <th style="padding-right:5px;padding-left:5px;text-align:center">notation</th>
                <th style="padding-right:5px;padding-left:5px;text-align:center">in</th>
              </tr>
              {
                val ins = ls.collect { case n if n.in.path == c.path.module => n }
                ins.map { doOMSNotation(_, false, lang) }
              }
              {
                val ins = ls.collect { case n if n.in.path != c.path.module => n }
                ins.map { doOMSNotation(_,true,lang)}
              }
            </table>
          }
      }
    }
    else {
      val args = withArguments { (getI, getX) =>
        arity.map {
          case 'i' => List(<mi>{getI}</mi>)
          case 'b' => List(<mi>{getX}</mi>)
          case 'a' =>
            val a = getI
            List((<msub><mi>{a}</mi> <mn>1</mn></msub>), (<mo>...</mo>), (<msub><mi>{a}</mi> <mi>n</mi></msub>))
          case 'B' =>
            val x = getX
            List((<msub><mi>{x}</mi> <mn>1</mn></msub>), (<mo>...</mo>), (<msub><mi>{x}</mi> <mi>n</mi></msub>))
        }
      }.toList
      this.getNotations(c) match {
        case Nil => <span/>
        case ls =>
          makeRow(<span>Notations:</span>) {
            <table>
              <tr>
                <th style="padding-right:5px;padding-left:5px;text-align:center">id</th>
                <th style="padding-right:5px;padding-left:5px;text-align:center">notation</th>
                <th style="padding-right:5px;padding-left:5px;text-align:center">operator</th>
                <th>in</th>
              </tr>
              {
                val ins = ls.collect { case n if n.in.path == c.path.module => n }
                ins.map { doComplexNotation(_,args,false,lang)}
              }
              {
                val ins = ls.collect { case n if n.in.path != c.path.module => n }
                ins.map { doComplexNotation(_,args,true,lang)}
              }
            </table>
          }
      }
    }
  }

  lazy val xhtmlPresenter = controller.extman.get(classOf[STeXPresenterML]) match {
    case p :: _ => p
    case Nil =>
      val p = new STeXPresenterML
      controller.extman.addExtension(p)
      p
  }

  lazy val texPresenter = controller.extman.get(classOf[STeXPresenterTex]) match {
    case p :: _ => p
    case Nil =>
      val p = new STeXPresenterTex
      controller.extman.addExtension(p)
      p
  }

}
