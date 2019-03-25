package info.kwarc.mmt.sql.codegen

import info.kwarc.mmt.api.frontend.Controller
import info.kwarc.mmt.api.modules.Theory
import info.kwarc.mmt.sql.{SQLBridge, SchemaLang, Table}

object CodeGenerator {

  private def isInputTheory(t: Theory, schemaGroup: Option[String]): Boolean = {
    val schemaLangIsMetaTheory = t.meta.contains(SchemaLang._path)
    val isInSchemaGroup = schemaGroup.forall(sg => {
      t.metadata.get(SchemaLang.schemaGroup).headOption.exists(_.value.toString == sg)
    })
    schemaLangIsMetaTheory && isInSchemaGroup
  }

  def main(args: Array[String]): Unit = {
    val outputDir = args(0)
    val archiveId = args(1)
    val schemaGroup = if (args.length > 2) Some(args(2)) else None

    val dirPaths = ProjectPaths(
      outputDir,
      "backend/src/main/scala/xyz/discretezoo/web",
      "frontend/src",
      "db"
    )
    val jdbcInfo = JDBCInfo("jdbc:postgresql://localhost:5432/discretezoo2", "discretezoo", "D!screteZ00")
    val prefix = "MBGEN"
    val generate = true

    val controller = Controller.make(true, true, List())
    // remove later
    controller.handleLine(s"build $archiveId mmt-omdoc")

    val tableCodes = controller.backend.getArchive(archiveId).get.allContent.map(controller.getO).collect({
      case Some(theory : Theory) if isInputTheory(theory, schemaGroup) => {
        SQLBridge.test2(theory.path, controller) match {
          case table: Table => Some(TableCode(prefix, dirPaths.dbPackagePath, table))
          case _ => None
        }
      }
    }).collect({ case Some(t: TableCode) => t })

    val dbCode = DatabaseCode(dirPaths, prefix, tableCodes, jdbcInfo)
    dbCode.writeAll(generate)

  }

}
