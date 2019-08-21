package es.weso.server

import cats.effect._
import cats.implicits._
import es.weso.rdf.jena.RDFAsJenaModel
import es.weso.rdf.streams.Streams
import es.weso.schema._
import es.weso.server.ApiHelper._
import results._
import es.weso.server.Defaults.{availableDataFormats, availableInferenceEngines, defaultActiveDataTab, defaultDataFormat, defaultInference}
import es.weso.server.QueryParams._
import es.weso.server.helper.DataFormat
import es.weso.server.utils.Http4sUtils._
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
import fs2._
import org.http4s._
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.dsl.Http4sDsl
import org.http4s.headers._
import org.http4s.multipart.Multipart
import org.http4s.server.staticcontent.{ResourceService, resourceService}
import org.log4s.getLogger

import scala.concurrent.duration._
import APIDefinitions._
import es.weso.html

class APIService[F[_]:ConcurrentEffect: Timer](blocker: Blocker,
                                               client: Client[F])(implicit cs: ContextShift[F])
  extends Http4sDsl[F] {

  private val relativeBase = Defaults.relativeBase
  private val logger = getLogger

  private val swagger =
    resourceService[F](ResourceService.Config("/swagger", blocker))

  val routes = HttpRoutes.of[F] {

    case GET -> Root / `api` / "data" / "formats" => {
      val formats = DataFormats.formatNames
      val json = Json.fromValues(formats.map(Json.fromString(_)))
      Ok(json)
    }

    case GET -> Root / `api` / "data" / "visualize" / "formats" => {
      val formats = DataConverter.availableGraphFormatNames ++
        List(
          "DOT", // DOT is not a visual format but can be used to debug
          "JSON" // JSON is the format that can be used by Cytoscape
        )
      val json = Json.fromValues(formats.map(Json.fromString(_)))
      Ok(json)
    }

    case GET -> Root / `api` / "data" / "formats" / "default" => {
      val dataFormat = DataFormats.defaultFormatName
      Ok(Json.fromString(dataFormat))
    }

    case req@GET -> Root / `api` / "dataUrl" / "info" :?
      OptDataURLParam(optDataUrl) +&
        DataFormatParam(optDataFormat) => {
      val dataFormat = dataFormatOrDefault(optDataFormat)
      optDataUrl match {
        case None => errJson(s"Must provide a dataUrl")
        case Some(dataUrl) => client.expect[String](dataUrl).flatMap(data => {
          val result = dataInfoFromString(data, dataFormat)
          Ok(result).map(_.withContentType(`Content-Type`(MediaType.application.json)))
        })
      }
    }

    case req@POST -> Root / `api` / "data" / "info" => {
      println(s"POST /api/data/info, Request: $req")
      req.decode[Multipart[F]] { m =>
        val partsMap = PartsMap(m.parts)
        for {
          maybeData <- DataParam.mkData(partsMap, relativeBase).value
          response <- maybeData match {
            case Left(err) => errJson(
              s"""|Error obtaining RDF data
                  |$err""".stripMargin
            )
            case Right((rdf, dp)) => {
              val dataFormat = dataFormatOrDefault(dp.dataFormat.map(_.name))
              dp.data match {
                case Some(data) => Ok(dataInfoFromString(data, dataFormat))
                case None => Ok(DataInfoResult.fromMsg(s"No data, but RDF=${rdf.serialize("TURTLE").getOrElse("Error")}").toJson)
              }
            }
          }
        } yield response
      }
    }

    case req@GET -> Root / `api` / "data" / "info" :?
      OptDataParam(optData) +&
      OptDataURLParam(optDataURL) +&
      DataFormatParam(maybeDataFormat) +&
      InferenceParam(optInference) +&
      OptEndpointParam(optEndpoint) +&
      OptActiveDataTabParam(optActiveDataTab) => {

      val either: Either[String, Option[DataFormat]] = for {
        df <- maybeDataFormat.map(DataFormat.fromString(_)).sequence
      } yield df

      either match {
        case Left(str) => errJson(str)
        case Right(optDataFormat) => {
          val dp =
            DataParam(optData, optDataURL, None, optEndpoint,
              optDataFormat, optDataFormat, optDataFormat,
              None, //no dataFormatFile
              optInference,
              None, optActiveDataTab)
          val (maybeStr, eitherRDF) = dp.getData(relativeBase)
          eitherRDF.fold(
            str => errJson(str),
            rdf => {
              Ok(dataInfo(rdf, maybeStr, optDataFormat))
            })
        }
      }
    }

    case req @ POST -> Root / `api` / "data" / "convert" => {
      req.decode[Multipart[F]] { m =>
        val partsMap = PartsMap(m.parts)
        for {
          maybeData <- DataParam.mkData(partsMap, relativeBase).value
          response <- maybeData match {
            case Left(msg) => errJson(s"Error obtaining data: $msg")
            case Right((rdf, dp)) => {
              val targetFormat = dp.targetDataFormat.getOrElse(defaultDataFormat).name
              val dataFormat = dp.dataFormat.getOrElse(defaultDataFormat)
              println(s"### POST DataFormat = ${dataFormat}")
              val either = DataConverter.rdfConvert(rdf, dp.data, dataFormat, targetFormat)
              either.fold(e => errJson(e), r => Ok(r.toJson))
            }
          }
        } yield response
      }
    }

    case req @ GET -> Root / `api` / "data" / "convert" :?
      DataParameter(data) +&
      DataFormatParam(optDataFormat) +&
      TargetDataFormatParam(optResultDataFormat) => {
      val either = for {
        dataFormat <- DataFormat.fromString(optDataFormat.getOrElse(defaultDataFormat.name))
        s <- DataConverter.dataConvert(data,dataFormat,optResultDataFormat.getOrElse(defaultDataFormat.name))
      } yield s
      either.fold(e => errJson(s"Error: $e"),r => Ok(r.toJson))
    }

    case req @ POST -> Root / `api` / "data" / "query" => {
      println(s"POST /api/data/query, Request: $req")
      req.decode[Multipart[F]] { m =>
        val partsMap = PartsMap(m.parts)
        for {
          maybeData <- DataParam.mkData(partsMap, relativeBase).value
          response <- maybeData match {
            case Left(err) => errJson(s"Error obtaining RDF data $err")
            case Right((rdf, dp)) => for {
              maybePair <- SparqlQueryParam.mkQuery(partsMap)
              response <- maybePair match {
                case Left(err) => errJson(s"Error obtaining Query data $err")
                case Right((queryStr,qp)) => {
                  val optQueryStr = qp.query.map(_.str)
                  val result = rdf.queryAsJson(optQueryStr.getOrElse(""))
                  result.fold(msg => errJson(msg), Ok(_))
                }
              }
            } yield response
          }
        } yield response
      }
    }

    case req @ POST -> Root / `api` / "data" / "extract" => {
      println(s"POST /api/data/extract, Request: $req")
      req.decode[Multipart[F]] { m =>
        val partsMap = PartsMap(m.parts)
        for {
          maybeData <- DataParam.mkData(partsMap, relativeBase).value
          response <- maybeData match {
            case Left(err) => errJson(
              s"""|Error obtaining RDF data
                  |$err""".stripMargin
            )
            case Right((rdf, dp)) => {
              val dataFormat = dataFormatOrDefault(dp.dataFormat.map(_.name))
              dp.data match {
                case Some(data) => errJson("Not implemented yet extract Schema as API")
                case None => errJson("No data provided")
              }
            }
          }
        } yield response
      }
    }

    case req @ GET -> Root / `api` / "endpoint" / "outgoing" :?
      OptEndpointParam(optEndpoint) +&
      OptNodeParam(optNode)
      => optEndpoint match {
        case None => errJson("No endpoint provided")
        case Some(endpoint) => optNode match {
            case None => errJson("No node provided")
            case Some(node) => Ok(Streams.getOutgoing(endpoint, node))
        }
      }

    // Contents on /swagger are directly mapped to /swagger
    case r @ GET -> _ if r.pathInfo.startsWith("/swagger/") => swagger(r).getOrElseF(NotFound())

  }

  private def errJson(msg: String): F[Response[F]] =
    Ok(Json.fromFields(List(("error",Json.fromString(msg)))))

}

object APIService {
  def apply[F[_]: ConcurrentEffect: ContextShift: Timer](blocker: Blocker, client: Client[F]): APIService[F] =
    new APIService[F](blocker, client)
}
