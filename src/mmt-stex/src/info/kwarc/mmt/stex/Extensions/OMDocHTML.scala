package info.kwarc.mmt.stex.Extensions

import info.kwarc.mmt.api.checking.{History, HistoryEntry}
import info.kwarc.mmt.api.documents.{DRef, Document, MRef}
import info.kwarc.mmt.api.modules.Theory
import info.kwarc.mmt.api.objects.{IsRealization, OMA, OMFOREIGN, OMID, OMMOD, OMS}
import info.kwarc.mmt.api.presentation.{Presenter, RenderingHandler}
import info.kwarc.mmt.api.symbols.{Constant, Declaration, Include, NestedModule, RuleConstant, Structure}
import info.kwarc.mmt.api.{CPath, DPath, DefComponent, GlobalName, LocalName, MPath, NamespaceMap, Original, Path, SemanticObject, StructuralElement, TypeComponent}
import info.kwarc.mmt.api.utils.{FilePath, MMTSystem}
import info.kwarc.mmt.api.web.{ServerRequest, ServerResponse}
import info.kwarc.mmt.stex.rules.ModelsOf
import info.kwarc.mmt.stex.xhtml.HTMLParser
import info.kwarc.mmt.stex.xhtml.HTMLParser.ParsingState
import info.kwarc.mmt.stex.{SHTML, STeXPresenterML, STeXPresenterTex, STeXServer}

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
              html = html.replace("NO_FRILLS_PLACEHOLDER", "TRUE")
              html = html.replace("SHOW_FILE_BROWSER_PLACEHOLDER", "false")
              html = html.replace("CONTENT_CSS_PLACEHOLDER", "")
              ServerResponse(html, "text/html")
            } else {
              var html = MMTSystem.getResourceAsString("mmt-web/stex/mmt-viewer/index.html")
              html = html.replace("CONTENT_URL_PLACEHOLDER", "/:" + this.pathPrefix + "/omdocfrag?" + s)
              html = html.replace("BASE_URL_PLACEHOLDER", "")
              html = html.replace("NO_FRILLS_PLACEHOLDER", "TRUE")
              html = html.replace("SHOW_FILE_BROWSER_PLACEHOLDER", "false")
              html = html.replace("CONTENT_CSS_PLACEHOLDER", "")
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
        implicit val state = new OMDocState(lang.getOrElse("en"))
        val ret = path match {
          case d:DPath =>
            documentTop(d).toString()
          case mp:MPath => moduleTop(mp).toString()
          case gn:GlobalName => declTop(gn).toString()
          case _ => "<div>Invalid: " + path.toString + "</div>"
        }
        val (doc, body) = this.emptydoc
        body.add(ret)
        ServerResponse(doc.get("body")()().head.toString,"text/html")
      case _ => ServerResponse(s"Malformed URL path ${request.path}", "txt")
    }
  }

  protected class OMDocState(val language: String) {
    private var id = 0
    private var lastSection : Option[String] = None
    def getId = {
      id += 1
      "omdocid"+ id.toString
    }

    def setTitle(n : Node) = {
      lastSection = Some(getString(n))
    }
    var doneincludes : List[MPath] = Nil
    def resetincludes = {
      doneincludes = Nil
      this
    }

    private def getString(n : Node) = {
      val sb = new StringBuilder()
      n.descendant.collect {
        case scala.xml.Text(s) => sb ++= s
      }
      sb.mkString.trim
    }
    def withTitle[A](ttl:Node)(f : Option[Node] => A): A = {
      if (lastSection.contains(getString(ttl))) {
        f(None)
      } else {
        val lasttitle = lastSection
        setTitle(ttl)
        try { f(Some(ttl)) } finally { lastSection = lasttitle }
      }
    }
  }

  private def documentTop(path:DPath)(implicit state:OMDocState): NodeSeq = {
    controller.getO(path) match {
      case Some(d: Document) => documentTop(d)
      case None =>
        <div><h1>Document {path.toString}</h1><hr/><div>Not found</div></div>
    }
  }

  private def documentTop(doc:Document)(implicit state: OMDocState): NodeSeq = {
    val title = getTitle(doc) match {
      case Some(node) => node
      case None => <span>Document {doc.path}</span>
    }
    state.withTitle(title){
      case Some(title) =>
        <div><h1>{present(title)}</h1><hr/>{documentBody(doc)}</div>
      case _ => documentBody(doc)
    }
  }

  private def documentSection(path: DPath)(implicit state: OMDocState): NodeSeq = {
    controller.getO(path) match {
      case Some(d: Document) =>
        documentSection(d)
      case None =>
        <div>
          <div style="font-weight:bold">Document
            {path.toString}
          </div>
          <div>Not found</div>
        </div>
    }
  }

  private def documentSection(doc: Document)(implicit state: OMDocState): NodeSeq = {
    val title = getTitle(doc) match {
      case Some(node) => node
      case None => <span>Document
        {doc.path}
      </span>
    }
    state.withTitle(title) {
      case Some(title) =>
        collapsible() { present(title) } { documentBody(doc) }
      case _ => documentBody(doc)
    }
  }

  private def documentBody(d: Document)(implicit state: OMDocState): NodeSeq = {
    d.getDeclarations.flatMap {
      case mr: MRef if mr.target.parent == d.path =>
        controller.getO(mr.target) match {
          case Some(t : Theory) if t.getDeclarations.nonEmpty =>
            collapsible(false, true){<b>Document Elements</b>}{
              doModuleBody(t)
            }
          case _ =>
            <span></span>
        }
      case dr: DRef =>
        //doInputref(dr.target,lang)
        documentSection(dr.target)
      case d : Document =>
        documentSection(d)
      case m: MRef =>
        controller.getO(m.target) match {
          case Some(t:Theory) if getLanguage(t).isDefined =>
            <span></span>
          case Some(t : Theory) =>
            moduleInner(t)
          case _ =>
            moduleInner(m.target)
        }
      case _ : Definienda.Def => <span></span>
      case o =>
        <div style="width:100%">TODO: {o.getClass} </div>
    }
  }

  private def moduleTop(path: MPath)(implicit state: OMDocState): NodeSeq = {
    controller.getO(path) match {
      case Some(d: Theory) => moduleTop(d)
      case None =>
        <div><h2>Module {path.toString}</h2> <hr/>
          <div>Not found</div>
        </div>
    }
  }

  private def moduleTop(t:Theory)(implicit state: OMDocState): NodeSeq = {
    val ns = t.path.doc
    val docpath = getDocPath(ns.toString, t.path.module.name)
    <div><h2 style="display:inline;">Module {docpath.map(doLink(_)(<span>{ns}</span>)).getOrElse(<span>{ns}</span>)
      }?{t.name}</h2><hr/>{doModuleBody(t)}</div>
  }

  private def moduleInner(path : MPath)(implicit state: OMDocState): NodeSeq = {
    controller.getO(path) match {
      case Some(d: Theory) => moduleInner(d)
      case None =>
        collapsible() { doLink(path) {
          <b>Module {path.name.toString}</b>
        }} {<div>Not found</div>}
    }
  }
  private def moduleInner(t: Theory)(implicit state: OMDocState): NodeSeq = {
    val lastname = t.name.steps.last.toString
    if (lastname.startsWith("EXTSTRUCT_") && lastname.endsWith("-module")) return doConservative(t)
    if (getLanguage(t).isDefined) return {doModuleBody(t,Some(t.path))}
    collapsible() { doLink(t.path) {
        <b>Module {t.name.toString}</b>
    }}{<div>
      {doModuleBody(t)}
      {/*controller.getO(t.path.toDPath ? state.language) match {
        case Some(lt: Theory) => collapsible(false, true)
          {<b>Document Elements</b>}{doModuleBody(lt,Some(t.path))}
        case _ => <span></span>
      }*/}</div>
    }
  }

  private def doModuleBody(theory: Theory,sig:Option[MPath] = None)(implicit state: OMDocState): NodeSeq = <div>
    <div>{theory.getPrimitiveDeclarations.collect {
      case i @ Include(mp) if i.getOrigin == Original && !sig.contains(mp.from) && !mp.total => mp.from
    } match {
      case Nil =>
      case ls => <span style="display:inline;"><b>Includes </b>{
        val ils = ls.map(p => doLink(p){<span>{p.name.toString}</span>})
        ils.flatMap(n => List(n,{<span>, </span>})).init
      }<hr/></span>
    }}</div>
    <div>{theory.getPrimitiveDeclarations.flatMap {
      case Include(id) if !id.total => Nil
      case nm:NestedModule if nm.metadata.getValues(ModelsOf.tp).nonEmpty => Nil
      case nm:NestedModule => moduleInner(nm.module.asInstanceOf[Theory])
      case o => declInner(o)
    }}</div>
  </div>

  private def getDocPath(s : String,mn : LocalName)(implicit state: OMDocState): Option[DPath] =
    controller.getO(Path.parseD(s"$s/$mn.${state.language}.omdoc", NamespaceMap.empty)) match {
      case Some(doc:Document) => Some(doc.path)
      case _ => controller.getO(Path.parseD(s"$s/$mn.en.omdoc", NamespaceMap.empty)) match {
        case Some(doc: Document) => Some(doc.path)
        case _ => controller.getO(Path.parseD(s"$s/$mn.omdoc", NamespaceMap.empty)) match {
          case Some(doc: Document) => Some(doc.path)
          case _ => controller.getO(Path.parseD(s"$s.${state.language}.omdoc", NamespaceMap.empty)) match {
            case Some(doc: Document) => Some(doc.path)
            case _ => controller.getO(Path.parseD(s"$s.en.omdoc", NamespaceMap.empty)) match {
              case Some(doc: Document) => Some(doc.path)
              case _ => controller.getO(Path.parseD(s"$s.omdoc", NamespaceMap.empty)) match {
                case Some(doc: Document) => Some(doc.path)
                case _ => None
              }
            }
          }
        }
      }
  }
  private def declTop(path:GlobalName)(implicit state: OMDocState): NodeSeq = {
    val ns = path.doc
    val docpath = getDocPath(ns.toString,path.module.name)
    <div><h3>Symbol {docpath.map(doLink(_)(<span>{ns}</span>)).getOrElse(<span>ns</span>)
      }?{doLink(path.module)(<span>{path.module.name}</span>)}?{path.name}</h3> <hr/>{
      controller.getO(path) match {
      case Some(d: Declaration) => declInner(d)
      case None => <div>Not found</div>
    }}</div>
  }

  private def declTop(d:Declaration)(implicit state: OMDocState): NodeSeq = {
    val ns = d.path.doc
    val docpath = getDocPath(ns.toString, d.path.module.name)
    <div><h3>Symbol {docpath.map(doLink(_)(<span>{ns}</span>)).getOrElse(<span>ns</span>)
      }?{doLink(d.path.module)(<span>{d.path.module.name}</span>)}?{d.path.name}</h3><hr/>{declInner(d)}</div>
  }

  private def declInner(path: GlobalName)(implicit state: OMDocState): NodeSeq = {
    controller.getO(path) match {
      case Some(d: Declaration) => declInner(d)
      case None =>
        collapsible(true) {doLink(path) {
            <div>Symbol {path.name.toString}</div>
        }} {<div>Not found</div>}
    }
  }

  private def declInner(d:Declaration)(implicit state: OMDocState): NodeSeq = {
    d match {
      case c : Constant if c.metadata.getValues(ModelsOf.sym).nonEmpty =>
        doStructure(c)
      case c : Constant if c.rl.exists(_.contains("mmt_term")) =>
        doTerm(c)
      case c : Constant => doSymbol(c)
      case rc:RuleConstant =>
        rc.tp match {
          case Some(OMMOD(p)) =>
            fakeCollapsible(true){<span style="display:inline-block">Rule <pre style="display:inline;font-size:x-small;">{SemanticObject.mmtToJava(p)}</pre></span>}
          case Some(OMA(OMMOD(p),args)) =>
            fakeCollapsible(true) {
              <span style="display:inline-block">Rule <pre style="display:inline;font-size:x-small;">{SemanticObject.mmtToJava(p)}</pre><math xmlns={HTMLParser.ns_mml}>
                <mrow><mo stretchy="true">(</mo>
                  {args.flatMap(a => List(
                    present(xhtmlPresenter.asXML(a, Some(rc.path $ TypeComponent))),
                    <mo>,</mo>
                  )).init}
                <mo stretchy="true">)</mo></mrow>
              </math></span>
            }
          case _ =>
            <span></span>
        }
      case _ =>
        <span>TODO: {d.getClass.toString} {d.path}</span>
    }
  }

  def doSymbol(c:Constant)(implicit state: OMDocState): NodeSeq = {
    c.rl match {
      case Some(s) if s.contains("textsymdecl") =>
        doTextSymbol(c)
      case Some(s) if s.contains("variable") =>
        doVariable(c)
      case Some(s) if s.contains("assertion") =>
        doAssertion(c,"Assertion")
      case _ =>
        lazy val header = //<span style="display:inline;">Symbol {symbolsyntax(c)}<img src="/stex/guidedtour.svg" height="30px" style="vertical-align:middle;"></img></span>
          <table style="width:calc(100% - 16.53px);vertical-align:middle;display:inline-table;"><tr>
            <td style="text-align:left;"><span style="display:inline;">Symbol {symbolsyntax(c)}</span></td>
            <td class="vollki-guided-tour" style="text-align:right;">
              <a href={scala.xml.Unparsed("/:vollki?path=" + c.path.toString + "&lang=" + state.language)} target="_blank" style="pointer-events:all;color:blue">
                <img src="/stex/guidedtour.svg" height="30px" title="Guided Tour" style="vertical-align:middle;padding-right:5px"></img>
              </a>
            </td>
          </tr></table>
        (c.tp,c.df,getNotationsC(c)) match {
          case (None,None,Nil) => fakeCollapsible(true){header}
          case _ => collapsible(false, true) {header}{symbolTable(c)}
        }
    }
  }

  private def doStructure(c: Constant)(implicit state: OMDocState): NodeSeq = {
    val mod = c.metadata.getValues(ModelsOf.sym).headOption.flatMap {
      case OMMOD(mp) => controller.getO(mp)
      case _ => None
    }
    def head = <b>Structure {symbolsyntax(c)}</b>
    mod match {
      case Some(th:Theory) => collapsible(expanded=false,small=true) { head }{doStructureBody(th)(state.resetincludes)}
      case _ => fakeCollapsible(true){ head }
    }
  }

  private def doConservative(th : Theory)(implicit state: OMDocState): NodeSeq = {
    val base = th.getPrimitiveDeclarations.collect {
      case Include(idata) if !idata.total => idata.from
    } match {
      case List(mp) =>
        controller.getO(mp) match {
          case Some(t: Theory) if t.metadata.getValues(ModelsOf.tp).nonEmpty =>
            t.metadata.getValues(ModelsOf.tp).head match {
              case OMS(p) => Some(p)
              case _ => None
            }
          case _ => None
        }
      case _ => None
    }
    base match {
      case Some(p) =>
        collapsible(true,true){
          <span style="display:inline;"><b>Conservative Extension</b> of {doLink(p){<span>{p.name.toString}</span>}}</span>
        }{<div>{th.getPrimitiveDeclarations.flatMap {
          case Include(id) if !id.total => Nil
          case s:Structure => doCopymoduleInStructure(s)
          case nm: NestedModule if nm.metadata.getValues(ModelsOf.tp).nonEmpty => Nil
          case nm: NestedModule => moduleInner(nm.module.asInstanceOf[Theory])
          case o => fieldInner(o)
        }}</div>}
      case _ =>
        <span style="display:inline;">Unknown Conservative Extension</span>
    }
  }

  private def doCopymoduleInStructure(s : Structure)(implicit state: OMDocState): NodeSeq = {
    val dom = s.from match {
      case OMMOD(p) => controller.getO(p)
      case _ => None
    }
    def head = dom match {
      case Some(t : Theory) if s.isTotal && s.isImplicit && t.metadata.getValues(ModelsOf.tp).nonEmpty =>
        t.metadata.getValues(ModelsOf.tp).head match {
          case OMS(p) =>
            <span style="display:inline">Realizes {doLink(p){<span>{p.name.toString}</span>}}</span>
          case _ => <span style="display:inline">Unknown Realization</span>
        }
      case _ =>
        <span>TODO</span>
    }
    def doBody : NodeSeq = {
      s.getDeclarationsElaborated.flatMap {
        case ec : Constant if ec.metadata.getValues(SHTML.headterm).nonEmpty =>
          ec.metadata.getValues(SHTML.headterm).head match {
            case OMS(p) => controller.getO(p) match {
              case Some(c : Constant) =>
                doField(ec)
              case _ =>
                <span>TODO</span>
            }
            case _ =>
              <span>TODO</span>
          }
      }
    }
    dom match {
      case Some(t : Theory) =>
        collapsible(true,true){head}{doBody}
      case _ =>
        <span style="display:inline">Unknown Morphism</span>
    }
  }

  private def doStructureBody(th : Theory)(implicit state: OMDocState): NodeSeq = {
    state.doneincludes ::= th.path
  <div>
    <div>{th.getPrimitiveDeclarations.collect {
      case i@Include(mp) if i.getOrigin == Original && !mp.total => mp.from
    }.map{mp => controller.getO(mp) match {
      case Some(t: Theory) if t.metadata.getValues(ModelsOf.tp).nonEmpty && !state.doneincludes.contains(t.path) =>
        t.metadata.getValues(ModelsOf.tp).head match {
          case OMS(p) =>
            collapsible(true, true) {<span style="display:inline;">Inherited from {
              doLink(p){<span>{p.name.toString}</span>}
            }</span>} { doStructureBody(t)}
          case _ => <span style="display:inline;"><b>Extends </b>{doLink(mp){<span>{mp.name.toString}</span>}}</span>
        }
      case Some(t: Theory) if !state.doneincludes.contains(t.path) && t.name.last.toString.startsWith("EXTSTRUCT") =>
        collapsible(true, true) {
          <span style="display:inline;">Inherited from Conservative Extension {doLink(mp) {<span>{mp.toString}</span>}}</span>
        } {doStructureBody(t)}
      case Some(t: Theory) if state.doneincludes.contains(t.path) => <span></span>
      case _ => <span style="display:inline;"><b>Extends </b>{doLink(mp){<span>{mp.name.toString}</span>}}</span>
    }}}</div>
      <div>
        {th.getPrimitiveDeclarations.flatMap {
        case Include(id) if !id.total => Nil
        case nm: NestedModule if nm.metadata.getValues(ModelsOf.tp).nonEmpty => Nil
        case nm: NestedModule => moduleInner(nm.module.asInstanceOf[Theory])
        case o => fieldInner(o)
      }}
      </div>
  </div>}

  private def fieldInner(d: Declaration)(implicit state: OMDocState): NodeSeq = {
    d match {
      case c: Constant if c.metadata.getValues(ModelsOf.sym).nonEmpty =>
        doStructure(c) // shoudn't happen
      case c: Constant if c.rl.exists(_.contains("mmt_term")) =>
        doTerm(c)  // shoudn't happen
      case c: Constant => doField(c)
      case rc: RuleConstant =>
        rc.tp match {
          case Some(OMMOD(p)) =>
            fakeCollapsible(true) {<span style="display:inline-block">Rule
              <pre style="display:inline;font-size:x-small;">
                {SemanticObject.mmtToJava(p)}
              </pre>
            </span>}
          case Some(OMA(OMMOD(p), args)) =>
            fakeCollapsible(true) {<span style="display:inline-block">Rule
                <pre style="display:inline;font-size:x-small;">
                  {SemanticObject.mmtToJava(p)}
                </pre> <math xmlns={HTMLParser.ns_mml}>
                <mrow>
                  <mo stretchy="true">(</mo>{args.flatMap(a => List(
                  present(xhtmlPresenter.asXML(a, Some(rc.path $ TypeComponent))),
                  <mo>,</mo>
                )).init}<mo stretchy="true">)</mo>
                </mrow>
              </math>
            </span>}
          case _ =>
            <span></span>
        }
      case _ =>
        <span>TODO:
          {d.getClass.toString}{d.path}
        </span>
    }
  }

  def doField(c: Constant)(implicit state: OMDocState): NodeSeq = {
    c.rl match {
      case Some(s) if s.contains("textsymdecl") =>
        doTextSymbol(c)
      case Some(s) if s.contains("variable") =>
        doVariable(c) // shoudn't happen
      case Some(s) if s.contains("assertion") && c.df.isDefined =>
        doAssertion(c,"Theorem")
      case Some(s) if s.contains("assertion") =>
        doAssertion(c, "Axiom")
      case _ =>
        lazy val header = //<span style="display:inline;">Symbol {symbolsyntax(c)}<img src="/stex/guidedtour.svg" height="30px" style="vertical-align:middle;"></img></span>
          <table style="width:calc(100% - 16.53px);vertical-align:middle;display:inline-table;">
            <tr>
              <td style="text-align:left;">
                <span style="display:inline;">{if (c.df.isDefined) "Defined "}Field {fieldsyntax(c)}</span>
              </td>
              <td class="vollki-guided-tour" style="text-align:right;">
                <a href={scala.xml.Unparsed("/:vollki?path=" + c.path.toString + "&lang=" + state.language)} target="_blank" style="pointer-events:all;color:blue">
                  <img src="/stex/guidedtour.svg" height="30px" title="Guided Tour" style="vertical-align:middle;padding-right:5px"></img>
                </a>
              </td>
            </tr>
          </table>
        (c.tp, c.df, getNotationsC(c)) match {
          case (None, None, Nil) => fakeCollapsible(true) { header }
          case _ => collapsible(false, true) { header } { symbolTable(c) }
        }
    }
  }

  private def doAssertion(c: Constant, typestr : String)(implicit state: OMDocState): NodeSeq = {
    lazy val header = <span style="display:inline;">{typestr} {symbolsyntax(c)}{c.tp match {
        case Some(tp) =>
          <span> <math xmlns={HTMLParser.ns_mml}>
              {present(xhtmlPresenter.asXML(tp, Some(c.path $ TypeComponent)))}
            </math></span>
        case _ => <span>(statement unknown)</span>
      }}
    </span>
    c.df match {
      case None => fakeCollapsible(true) {header}
      case _ => collapsible(false, true) {header} {symbolTable(c, dotype = false)}
    }
  }

  private def doVariable(c: Constant)(implicit state: OMDocState): NodeSeq = {
    lazy val header = <span style="display:inline;">Variable {symbolsyntax(c)}
      {c.tp match {
        case Some(tp) =>
          <span> of type <math xmlns={HTMLParser.ns_mml}>
            {present(xhtmlPresenter.asXML(tp, Some(c.path $ TypeComponent)))}
          </math></span>
        case _ => <span> (No type given)</span>
      }}</span>
    (c.df, getNotationsC(c)) match {
      case (None, Nil) => fakeCollapsible(true) {header}
      case _ => collapsible(false, true) {header} {symbolTable(c,dotype = false)}
    }
  }

  private def doTerm(c: Constant)(implicit state: OMDocState): NodeSeq = {
    lazy val header = <span style="display:inline;">{c.df match {
        case Some(df) =>
            <math xmlns={HTMLParser.ns_mml}>
              {present(xhtmlPresenter.asXML(df, Some(c.path $ DefComponent)))}
            </math>
        case _ => <span>(Term missing)</span>
      }}
    </span>
    c.tp match {
      case None => fakeCollapsible(true) {header}
      case Some(tp) => collapsible(false, true) {header} {
        <span>Inferred Type: <math xmlns={HTMLParser.ns_mml}>{
          present(xhtmlPresenter.asXML(tp, Some(c.path $ TypeComponent)))
        }</math></span>
      }
    }
  }

  private def doTextSymbol(c:Constant)(implicit state: OMDocState): NodeSeq = {
    val notations = getNotationsC(c)
    lazy val header = <span style="display:inline;">Text Symbol {symbolsyntax(c)}: {
      notations match {
        case Nil => <span>(Notation missing)</span>
        case ls => <math xmlns={HTMLParser.ns_mml}>{ls.head.present(Nil)}</math>
      }}
    </span>
    (c.tp,c.df) match {
      case (None,None) if notations.length <= 1 =>
        fakeCollapsible(true){header}
      case _ => collapsible(false,true){header}{symbolTable(c,false)}
    }
  }

  def symbolTable(c: Constant, donotations:Boolean=true, dotype:Boolean=true)(implicit state: OMDocState) =
    <table class="omdoc-symbol-table">{c.df match {
      case Some(df) =>
        <tr>
          <td class="omdoc-symbol-td">Definiens</td>
          <td class="omdoc-symbol-td">{xhtmlPresenter.asXML(df, Some(c.path $ DefComponent))}</td>
        </tr>
      case None =>
    }}{c.tp match {
      case Some(tp) if dotype =>
        <tr>
          <td class="omdoc-symbol-td">Type</td>
          <td class="omdoc-symbol-td">{xhtmlPresenter.asXML(tp, Some(c.path $ TypeComponent))}</td>
        </tr>
      case _ =>
    }}{getNotationsC(c) match {
      case Nil =>
      case List(a) if !donotations =>
      case nls =>
        val ls = if (donotations) nls else nls.tail
        val arity = getArity(c).getOrElse("")
        <tr>
          <td class="omdoc-symbol-td">Notations</td> <td class="omdoc-symbol-td">
          <table class="omdoc-notation-table">
            <tr>
              <th class="omdoc-notation-td">id</th>
              <th class="omdoc-notation-td">notation</th>{if (arity.nonEmpty) <th class="omdoc-notation-td">operator</th>}<th class="omdoc-notation-td">in module</th>
            </tr>{val args = withArguments((getI, getX) => arity.map {
            case 'i' => List(<mi>{getI}</mi>)
            case 'b' => List(<mi>{getX}</mi>)
            case 'a' => val a = getI; List(
                <msub><mi>{a}</mi><mn>1</mn></msub>,
                <mo>...</mo>,
                <msub><mi>{a}</mi><mi>n</mi></msub>)
            case 'B' =>
              val x = getX; List(
                <msub><mi>{x}</mi><mn>1</mn></msub>,
                <mo>...</mo>,
                <msub><mi>{x}</mi><mi>n</mi></msub>)
          }).toList
          ls.map { not =>
            <tr>
              <td class="omdoc-notation-td">{not.id}</td>
              <td class="omdoc-notation-td">
                <math xmlns={HTMLParser.ns_mml}>{present(not.present(args))}</math>
              </td>
              {if (arity.nonEmpty) <td class="omdoc-notation-td">{
                not.op.map(n =>
                  <math xmlns={HTMLParser.ns_mml}>{n.plain.node}</math>
                ).getOrElse(<span>(None)</span>)
              }</td>}
              <td class="omdoc-notation-td">{
                not.in.map(t => doLink(t.path)(<span>{t.name}</span>)).getOrElse(<span></span>)
                }</td>
            </tr>
          }}
          </table></td></tr>
    }}</table>

  def symbolsyntax(c:Constant)(implicit state: OMDocState) = <span>
    <pre style="display:inline;font-size:smaller;">{doLink(c.path)(<span>{c.name}</span>)}</pre>
    {(getMacroName(c), getArity(c)) match {
      case (Some(mn), None | Some("")) =>
        <span> (<pre style="display:inline;font-size:smaller;">\{mn}</pre>)</span>
      case (Some(mn), Some(args)) =>
        <span> (<pre style="display:inline;font-size:smaller;">\{mn}{
            withArguments((getI, getX) => args.map {
                case 'i' => s"{${getI}}"
                case 'a' => s"{${val a = getI; a + "_1,...," + a + "_n"}}"
                case 'b' => s"{${getX}}"
                case 'B' => s"{${val a = getX; a + "_1,...," + a + "_n"}}"
              }.mkString)
            }</pre>)</span>
      case _ => <span></span>
    }}</span>

  def fieldsyntax(c: Constant)(implicit state: OMDocState) = <span>
    <pre style="display:inline;font-size:smaller;">{doLink(c.path)(<span>{c.name}</span>)}</pre>
    {(getMacroName(c), getArity(c)) match {
      case (Some(mn), None | Some("")) =>
        <span>(<pre style="display:inline;font-size:smaller;">\this{s"{$mn}"}</pre>)</span>
      case (Some(mn), Some(args)) =>
        <span>(<pre style="display:inline;font-size:smaller;">\this{s"{$mn}"}{withArguments((getI, getX) => args.map {
            case 'i' => s"{${getI}}"
            case 'a' => s"{${val a = getI; a + "_1,...," + a + "_n"}}"
            case 'b' => s"{${getX}}"
            case 'B' => s"{${val a = getX; a + "_1,...," + a + "_n"}}"
          }.mkString)}</pre>)</span>
      case _ => <span></span>
    }}
  </span>
  private def withArguments[A](f: (=> Char, => Char) => A): A = {
    var iidx = -1
    var vidx = -1
    val is = "abcdefghi"
    val xs = "xyzvwustr"
    def getI = {iidx += 1; is(iidx)}
    def getX = {vidx += 1; xs(vidx)}
    f(getI, getX)
  }

  private def doLink(p: Path)(txt: => NodeSeq)(implicit state: OMDocState) =
    <a href={scala.xml.Unparsed(s"/:${this.pathPrefix}/omdoc?${p.toString}&language=${state.language}")} style="color:blue;">{txt}</a>

  private def collapsible(expanded:Boolean = true,small:Boolean=false)(title: => NodeSeq)(content: =>NodeSeq)(implicit state:OMDocState) = {
    val id = state.getId
    <div class={if (small) "omdoc-collapsible omdoc-collapsible-small" else "omdoc-collapsible"}>
      {if (expanded) <input type="checkbox" name={id} id={id} checked="checked"/>
        else <input type="checkbox" name={id} id={id}/>
      }
      <div class="omdoc-handle"><label for={id}>{title}</label></div>
      <div class="omdoc-content">{content}</div>
    </div>
  }

  private def fakeCollapsible(small: Boolean = false)(title: => NodeSeq)(implicit state: OMDocState) =
    <div class={if (small) "omdoc-fake-collapsible-small" else "omdoc-fake-collapsible"}>{title}</div>

  private def present(n:NodeSeq):Node = present(n.toString())(None).plain.node

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

  lazy val xhtmlPresenter = controller.extman.get(classOf[STeXPresenterML]) match {
    case p :: _ => p
    case Nil =>
      val p = new STeXPresenterML
      controller.extman.addExtension(p)
      p
  }
  lazy val presenter = new Presenter(xhtmlPresenter) {
    val key: String = "stexpres"

    def applyPath(p: Path, standalone: Boolean = true)(implicit rh: RenderingHandler) = {
      controller.getO(p) match {
        case Some(d) => apply(d, standalone)
        case None => rh.apply("Not found: " + p.toString)
      }
    }
    override def apply(e: StructuralElement, standalone: Boolean)(implicit rh: RenderingHandler): Unit = {
      implicit val state = new OMDocState("en")
      e match {
        case d : Document if standalone => rh(documentTop(d))
        case d: Document  => rh(documentSection(d))
        case m : Theory if standalone => rh(moduleTop(m))
        case m: Theory => rh(moduleInner(m))
        case d : Declaration if standalone => rh(declTop(d))
        case d: Declaration => rh(declInner(d))
      }
    }

    def doHistories(cp:CPath,h:History*) = {
      implicit val sb = new StringBuilder()
      sb.append(
      "<div style=\"font-weight:bold;\">" + cp.toString + "</div><hr/>"
      )
      h.foreach{h =>
        sb.append("<ul>")
        new HistoryTree().from(h).present
        sb.append("</ul>")
      }
      sb.mkString
    }

    import info.kwarc.mmt.api.checking._
    import info.kwarc.mmt.api.utils.{Union,Left,Right}

    val self = this
    private class HistoryTree(var steps : List[Union[HistoryTree,HistoryEntry]] = Nil,val lvl : Int = 0) {
      def from(h:History) = {
        doSteps(h.getSteps.map(Right(_)))
        steps match {
          case List(Left(ht)) => ht
          case _ =>
            this
        }
      }
      private def doSteps(ls : List[Union[HistoryTree,HistoryEntry]]) : Unit = {
        var ils = ls
        def doNested(top:Union[HistoryTree,HistoryEntry],lvl:Int) = {
          var done = false
          var ret : List[Union[HistoryTree,HistoryEntry]] = List(top)
          var endlvl = lvl
          while(!done && ils.nonEmpty) {
            val h :: t = ils
            ils = t
            h match {
              case Left(ht) if ht.lvl < lvl =>
                endlvl = ht.lvl
                ret ::= Left(ht)
                done = true
              case Right(IndentedHistoryEntry(e,l)) if l < lvl =>
                endlvl = l
                ret ::= Right(e)
                done = true
              case Right(IndentedHistoryEntry(e,_)) =>
                ret ::= Right(e)
              case o =>
                ret ::= o
            }
          }
          ils ::= Left(new HistoryTree(ret,endlvl))
        }
        while (ils.nonEmpty) {
          val h :: t = ils
          ils = t
          if (t.isEmpty) steps ::= h else {
            h match {
              case Left(ht) if ht.lvl > 0 =>
                doNested(Left(ht), ht.lvl)
              case Right(IndentedHistoryEntry(e, lvl)) if lvl > 0 =>
                doNested(Right(e), lvl)
              case Right(IndentedHistoryEntry(e, _)) =>
                steps ::= Right(e)
              case o =>
                steps ::= o
            }
          }
        }
      }

      def present(implicit sb: StringBuilder): Unit = {
        sb.append("<li><div>")
        steps.head match {
          case Left(ht) =>
            ht.present
          case Right(st) =>
            sb.append(st.present(self.objectLevel.asString(_)))
        }
        sb.append("</div>")
        sb.append("<ul>")
        steps.tail.foreach {
          case Left(ht) =>
            ht.present
          case Right(st) =>
            sb.append("<li><div>")
            sb.append(st.present(self.objectLevel.asString(_)))
            sb.append("</div></li>")
        }
        sb.append("</ul></li>")
      }
    }
  }

  lazy val texPresenter = controller.extman.get(classOf[STeXPresenterTex]) match {
    case p :: _ => p
    case Nil =>
      val p = new STeXPresenterTex
      controller.extman.addExtension(p)
      p
  }

}
