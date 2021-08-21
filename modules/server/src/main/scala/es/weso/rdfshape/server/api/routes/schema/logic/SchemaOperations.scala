package es.weso.rdfshape.server.api.routes.schema.logic

import cats.effect.IO
import cats.syntax.either._
import com.typesafe.scalalogging.LazyLogging
import es.weso.rdf.jena.RDFAsJenaModel
import es.weso.rdf.nodes.IRI
import es.weso.rdf.{RDFBuilder, RDFReasoner}
import es.weso.rdfshape.server.api.definitions.ApiDefaults
import es.weso.rdfshape.server.api.definitions.UmlDefinitions.umlOptions
import es.weso.rdfshape.server.api.format.{DataFormat, SchemaFormat}
import es.weso.rdfshape.server.api.routes.data.logic.DataParam
import es.weso.rdfshape.server.api.routes.schema.service.{
  SchemaParam,
  TriggerModeParam
}
import es.weso.schema.{Result, Schema, ValidationTrigger}
import es.weso.uml.Schema2UML
import io.circe.Json

/** Static utilities used by the {@link es.weso.rdfshape.server.api.routes.schema.service.SchemaService}
  * to operate on schemas
  */
private[api] object SchemaOperations extends LazyLogging {

  /** Long value used as a "no time" value for errored validations
    */
  private val NoTime = 0L

  /** Obtain the information from an schema
    *
    * @param schema Input schema
    * @return Schema information as a data instance of {@link SchemaInfo}.
    */
  def schemaInfo(schema: Schema): SchemaInfo = {
    val info = schema.info
    SchemaInfo(
      Some(info.schemaName),
      Some(info.schemaEngine),
      info.isWellFormed,
      schema.shapes,
      schema.pm.pm.toList.map { case (prefix, iri) => (prefix.str, iri.str) },
      info.errors
    )
  }

  /** @param schema Input schema
    * @return JSON representation of the schema as a Cytoscape graph to be drawn on clients (or an error message)
    */
  // TODO: return another status code on failure, so that clients can handle it
  def schemaCytoscape(schema: Schema): Json = {
    val eitherJson = for {
      pair <- Schema2UML.schema2UML(schema)
    } yield {
      val (uml, _) = pair
      uml.toJson
    }
    eitherJson.fold(
      e =>
        Json.fromFields(
          List(
            ("error", Json.fromString(s"Error converting to schema 2 JSON: $e"))
          )
        ),
      identity
    )
  }

  /** @param schema Input schema
    * @return JSON representation of the schema as a Graphviz graph to be drawn on clients (or an error message)
    */
  // TODO: return another status code on failure, so that clients can handle it
  def schemaVisualize(schema: Schema): IO[Json] = for {
    pair <- schema2SVG(schema)
  } yield {
    val (svg, plantuml) = pair
    val info            = schema.info
    val fields: List[(String, Json)] =
      List(
        ("schemaName", Json.fromString(info.schemaName)),
        ("schemaEngine", Json.fromString(info.schemaEngine)),
        ("wellFormed", Json.fromBoolean(info.isWellFormed)),
        ("errors", Json.fromValues(info.errors.map(Json.fromString))),
        ("parsed", Json.fromString("Parsed OK")),
        ("svg", Json.fromString(svg)),
        ("plantUML", Json.fromString(plantuml))
      )
    Json.fromFields(fields)
  }

  def schema2SVG(schema: Schema): IO[(String, String)] = {
    val eitherUML = Schema2UML.schema2UML(schema)
    eitherUML.fold(
      e => IO.pure((s"SVG conversion: $e", s"Error converting UML: $e")),
      pair => {
        val (uml, _) = pair
        logger.debug(s"UML converted: $uml")
        (for {
          str <- uml.toSVG(umlOptions)
        } yield {
          (str, uml.toPlantUML(umlOptions))
        }).handleErrorWith(e =>
          IO.pure(
            (
              s"SVG conversion error: ${e.getMessage}",
              uml.toPlantUML(umlOptions)
            )
          )
        )
      }
    )
  }

  /** @param result Schema validation result
    * @return JSON representation of the schema validation result
    */
  def schemaResult2json(result: Result): IO[Json] = for {
    emptyRes <- RDFAsJenaModel.empty
    json     <- emptyRes.use(emptyBuilder => result.toJson(emptyBuilder))
  } yield json

  /** Get base URI
    *
    * @return default URI obtained from current folder
    */
  def getBase: Option[String] = ApiDefaults.relativeBase.map(_.str)

  /** For a given data (raw text) and schema, attempt to validate it with WESO libraries
    *
    * @param data            Input RDF data
    * @param optDataFormat   RDF data format (optional)
    * @param optSchema       Input validation schema (optional)
    * @param optSchemaFormat Validation schema format (optional)
    * @param optSchemaEngine Validation schema engine (optional)
    * @param tp              Trigger mode
    * @param optInference    Validation inference (optional)
    * @param relativeBase    Relative base (optional)
    * @param builder         RDF builder
    * @return
    */
  private[api] def schemaValidateStr(
      data: String,
      optDataFormat: Option[DataFormat],
      optSchema: Option[String],
      optSchemaFormat: Option[SchemaFormat],
      optSchemaEngine: Option[String],
      tp: TriggerModeParam,
      optInference: Option[String],
      relativeBase: Option[IRI],
      builder: RDFBuilder
  ): IO[(Result, Option[ValidationTrigger], Long)] = {
    val dp = DataParam.empty.copy(
      data = Some(data),
      dataFormatTextarea = optDataFormat,
      inference = optInference
    )
    val sp = SchemaParam.empty.copy(
      schema = optSchema,
      schemaFormatTextArea = optSchemaFormat,
      schemaEngine = optSchemaEngine
    )

    val result: IO[(Result, Option[ValidationTrigger], Long)] = for {
      pair <- dp.getData(relativeBase)
      (_, resourceRdf) = pair
      result <- resourceRdf.use(rdf =>
        for {
          pairSchema <- sp.getSchema(Some(rdf))
          (_, eitherSchema) = pairSchema
          schema <- IO.fromEither(
            eitherSchema.leftMap(s =>
              new RuntimeException(s"Error obtaining schema: $s")
            )
          )
          res <- schemaValidate(rdf, schema, tp, relativeBase, builder)
        } yield res
      )
    } yield result

    result.attempt.flatMap(_.fold(e => schemaErr(e.getMessage), IO.pure))
  }

  /** For a given data and schema, attempt to validate it with WESO libraries
    *
    * @param rdf          Input RDF data
    * @param schema       Input schema
    * @param tp           Trigger mode
    * @param relativeBase Relative base (optional)
    * @param builder      RDF builder
    * @return
    */
  def schemaValidate(
      rdf: RDFReasoner,
      schema: Schema,
      tp: TriggerModeParam,
      relativeBase: Option[IRI],
      builder: RDFBuilder
  ): IO[(Result, Option[ValidationTrigger], Long)] = {
    logger.debug(s"APIHelper: validate")

    val base        = relativeBase.map(_.str) // Some(FileUtils.currentFolderURL)
    val triggerMode = tp.triggerMode
    for {
      pm <- rdf.getPrefixMap
      p  <- tp.getShapeMap(pm, schema.pm)
      (optShapeMapStr, _) = p
      pair <-
        ValidationTrigger.findTrigger(
          triggerMode.getOrElse(ApiDefaults.defaultTriggerMode),
          optShapeMapStr.getOrElse(""),
          base,
          None,
          None,
          pm,
          schema.pm
        ) match {
          case Left(msg) =>
            schemaErr(
              s"Cannot obtain trigger: $triggerMode\nshapeMap: $optShapeMapStr\nmsg: $msg"
            )
          case Right(trigger) =>
            val run = for {
              startTime <- IO {
                System.nanoTime()
              }
              result <- schema.validate(rdf, trigger, builder)
              endTime <- IO {
                System.nanoTime()
              }
              time: Long = endTime - startTime
            } yield (result, Some(trigger), time)
            run.handleErrorWith(e => {
              val msg = s"Error validating: ${e.getMessage}"
              logger.error(msg)
              schemaErr(s"Error validating: ${e.getMessage}")
            })
        }
    } yield pair
  }

  /** Given an error message, return an empty schema validation result containing it
    *
    * @param msg error message
    * @return Empty schema validation result containing the error message
    */
  private def schemaErr(msg: String) =
    IO((Result.errStr(s"Error: $msg"), None, NoTime))

}
