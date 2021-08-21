package es.weso.rdfshape.server.api.routes.data.service

import cats.effect._
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import es.weso.rdfshape.server.api.definitions.ApiDefaults
import es.weso.rdfshape.server.api.definitions.ApiDefaults.{
  availableInferenceEngines,
  defaultDataFormat,
  defaultInference
}
import es.weso.rdfshape.server.api.definitions.ApiDefinitions.api
import es.weso.rdfshape.server.api.format._
import es.weso.rdfshape.server.api.routes.IncomingRequestParameters._
import es.weso.rdfshape.server.api.routes.data.logic.DataExtract.dataExtract
import es.weso.rdfshape.server.api.routes.data.logic.DataInfo.{
  dataInfoFromRdf,
  dataInfoFromString
}
import es.weso.rdfshape.server.api.routes.data.logic.DataOperations.dataFormatOrDefault
import es.weso.rdfshape.server.api.routes.data.logic.{
  DataConversion,
  DataExtract,
  DataParam
}
import es.weso.rdfshape.server.api.routes.endpoint.logic.SparqlQuery
import es.weso.rdfshape.server.api.routes.{ApiService, PartsMap}
import es.weso.rdfshape.server.api.utils.OptEitherF._
import es.weso.rdfshape.server.utils.json.JsonUtils.responseJson
import es.weso.schema._
import es.weso.utils.IOUtils._
import io.circe._
import org.http4s._
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.dsl.Http4sDsl
import org.http4s.headers._
import org.http4s.multipart.Multipart

/** API Service to handle RDF data
  *
  * @param client HTTP4S client object
  */
class DataService(client: Client[IO])
    extends Http4sDsl[IO]
    with ApiService
    with LazyLogging {

  override val verb: String = "data"

  /** Describe the API routes handled by this service and the actions performed on each of them
    */
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    // Input RDF data formats include html-microdata, turtle, json-ld...
    case GET -> Root / `api` / `verb` / "formats" / "input" =>
      val formats = DataFormat.availableFormats.map(_.name)
      val json    = Json.fromValues(formats.map(Json.fromString))
      Ok(json)

    // Output RDF data conversion formats
    case GET -> Root / `api` / `verb` / "formats" / "output" =>
      val formats = DataFormats.availableFormats.map(_.name)
      val json    = Json.fromValues(formats.map(Json.fromString))
      Ok(json)

    case GET -> Root / `api` / `verb` / "formats" / "default" =>
      val dataFormat = DataFormat.defaultFormat.name
      Ok(Json.fromString(dataFormat))

    case GET -> Root / `api` / `verb` / "inferenceEngines" =>
      val inferenceEngines = availableInferenceEngines
      val json             = Json.fromValues(inferenceEngines.map(Json.fromString))
      Ok(json)

    case GET -> Root / `api` / `verb` / "inferenceEngines" / "default" =>
      val defaultInferenceEngine = defaultInference
      Ok(Json.fromString(defaultInferenceEngine))

    case GET -> Root / `api` / `verb` / "visualize" / "formats" =>
      val formats = DataConversion.availableGraphFormatNames ++
        List(
          "DOT", // DOT is not a visual format but can be used to debug
          "JSON" // JSON is the format that can be used by Cytoscape
        )
      val json = Json.fromValues(formats.map(Json.fromString))
      Ok(json)

    case req @ GET -> Root / `api` / "dataUrl" / "info" :?
        OptDataURLParam(optDataUrl) +&
        DataFormatParam(optDataFormat) =>
      val dataFormat = dataFormatOrDefault(optDataFormat)
      optDataUrl match {
        case None => responseJson("Must provide a dataUrl", BadRequest)
        case Some(dataUrl) =>
          for {
            data   <- client.expect[String](dataUrl)
            result <- io2f(dataInfoFromString(data, dataFormat))
            json = result.toJson
            r <- Ok(json).map(
              _.withContentType(`Content-Type`(MediaType.application.json))
            )
          } yield r
      }

    case req @ POST -> Root / `api` / `verb` / "info" =>
      req.decode[Multipart[IO]] { m =>
        val partsMap = PartsMap(m.parts)
        for {
          dataParam <- DataParam.mkData(partsMap, relativeBase)
          (resourceRdf, dp) = dataParam
          dataFormat        = dataFormatOrDefault(dp.dataFormat.map(_.name))
          response <- dp.data match {
            case Some(data) =>
              for {
                result <- dataInfoFromString(data, dataFormat)
                json: Json = result.toJson
                ok <- Ok(json)
              } yield ok
            case None =>
              for {
                d <-
                  resourceRdf.use(rdf =>
                    dataInfoFromRdf(rdf, None, dp.dataFormat)
                  )
                json <- IO(d.toJson)
                ok   <- Ok(json)
              } yield ok
          }
        } yield response
      }

    case GET -> Root / `api` / `verb` / "info" :?
        OptDataParam(optData) +&
        OptDataURLParam(optDataURL) +&
        CompoundDataParam(optCompoundData) +&
        DataFormatParam(maybeDataFormat) +&
        InferenceParam(optInference) +&
        OptEndpointParam(optEndpoint) +&
        OptActiveDataTabParam(optActiveDataTab) =>
      val either: Either[String, Option[DataFormat]] = for {
        df <- maybeDataFormat.map(DataFormat.fromString).sequence
      } yield df

      val r: IO[Response[IO]] = either.fold(
        str => responseJson(str, BadRequest),
        optDataFormat => {
          val dp =
            DataParam(
              optData,
              optDataURL,
              None,
              optEndpoint,
              optDataFormat,
              optDataFormat,
              optDataFormat,
              None, //no dataFormatFile
              optInference,
              None,
              optActiveDataTab,
              optCompoundData
            )
          for {
            dataParam <- io2f(dp.getData(relativeBase))
            (maybeStr, resourceRdf) = dataParam
            d <- resourceRdf.use(rdf =>
              dataInfoFromRdf(rdf, maybeStr, optDataFormat)
            )
            json <- IO(d.toJson)
            ok   <- Ok(json)
          } yield ok
        }
      )
      r

    case req @ POST -> Root / `api` / `verb` / "convert" =>
      req.decode[Multipart[IO]] { m =>
        val partsMap = PartsMap(m.parts)
        for {
          dataParam <- DataParam.mkData(partsMap, relativeBase)
          (resourceRdf, dp) = dataParam
          targetFormat      = dp.targetDataFormat.getOrElse(defaultDataFormat).name
          dataFormat        = dp.dataFormat.getOrElse(defaultDataFormat)
          result <- io2f(
            resourceRdf.use(rdf => {
              logger.debug(s"Data convert dataParam: $dp")
              DataConversion.rdfConvert(rdf, dp.data, dataFormat, targetFormat)
            })
          )
          ok <- Ok(result.toJson)
        } yield ok
      }

    case req @ GET -> Root / `api` / `verb` / "convert" :?
        DataParameter(data) +&
        DataFormatParam(optDataFormat) +&
        CompoundDataParam(optCompoundData) +&
        TargetDataFormatParam(optResultDataFormat) =>
      for {
        eitherDataFormat <- either2ef[DataFormat, IO](
          DataFormat.fromString(optDataFormat.getOrElse(defaultDataFormat.name))
        ).value
        result <- eitherDataFormat.fold(
          e => BadRequest(e),
          dataFormat =>
            for {
              r <- io2f(
                DataConversion.dataConvert(
                  data,
                  dataFormat,
                  optCompoundData,
                  optResultDataFormat.getOrElse(defaultDataFormat.name)
                )
              )
              ok <- Ok(r.toJson)
            } yield ok
        )
      } yield result

    case req @ POST -> Root / `api` / `verb` / "query" =>
      req.decode[Multipart[IO]] { m =>
        val partsMap = PartsMap(m.parts)
        logger.debug(s"Data query params map: $partsMap")
        for {
          dataParam <- DataParam.mkData(partsMap, relativeBase)
          (resourceRdf, dp) = dataParam
          maybeQuery <- SparqlQuery.getSparqlQuery(partsMap)
          resp <- maybeQuery match {
            case Left(err) =>
              responseJson(s"Error obtaining query data: $err", BadRequest)
            case Right(query) =>
              val optQueryStr = query.query
              logger.debug(s"Data query with querystring: $optQueryStr")
              for {
                json <- io2f(
                  resourceRdf.use(rdf => rdf.queryAsJson(optQueryStr))
                )
                v <- Ok(json)
              } yield v
          }
        } yield resp
      }

    case req @ POST -> Root / `api` / `verb` / "extract" =>
      req.decode[Multipart[IO]] { m =>
        val partsMap = PartsMap(m.parts)
        for {
          maybeData          <- DataParam.mkData(partsMap, relativeBase).attempt
          schemaEngine       <- partsMap.optPartValue("schemaEngine")
          optSchemaFormatStr <- partsMap.optPartValue("schemaFormat")
          inference          <- partsMap.optPartValue("inference")
          label              <- partsMap.optPartValue("labelName")
          optBaseStr         <- partsMap.optPartValue("base")
          nodeSelector       <- partsMap.optPartValue("nodeSelector")
          schemaFormat <- optEither2f(
            optSchemaFormatStr,
            SchemaFormat.fromString
          )
          response <- maybeData match {
            case Left(err) =>
              for {
                res <- io2f(
                  DataExtract
                    .fromMsg(s"Error obtaining data: ${err.getMessage}")
                    .toJson
                )
                ok <- Ok(res)
              } yield ok
            /* Ok(DataExtractResult.fromMsg(s"Error obtaining data:
             * $err").toJson) */
            case Right((resourceRdf, dp)) =>
              for {
                d <- io2f(
                  resourceRdf.use(rdf =>
                    dataExtract(
                      rdf,
                      dp.data,
                      dp.dataFormatValue,
                      nodeSelector,
                      inference,
                      schemaEngine,
                      schemaFormat,
                      label,
                      None
                    )
                  )
                )
                json <- io2f(d.toJson)
                ok   <- Ok(json)
              } yield ok
          }
        } yield response
      }

  }
  private val relativeBase = ApiDefaults.relativeBase

  private def errJson(msg: String): IO[Response[IO]] =
    Ok(Json.fromFields(List(("error", Json.fromString(msg)))))

}

object DataService {

  /** Service factory
    *
    * @param client Underlying http4s client
    * @return A new Data Service
    */
  def apply(client: Client[IO]): DataService =
    new DataService(client)
}
