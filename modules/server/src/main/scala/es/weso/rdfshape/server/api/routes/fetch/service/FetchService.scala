package es.weso.rdfshape.server.api.routes.fetch.service

import cats.effect._
import com.typesafe.scalalogging.LazyLogging
import es.weso.rdfshape.server.api.definitions.ApiDefinitions.api
import es.weso.rdfshape.server.api.routes.ApiService
import es.weso.rdfshape.server.api.routes.IncomingRequestParameters.UrlParam
import org.http4s._
import org.http4s.client.Client
import org.http4s.dsl.Http4sDsl
import scalaj.http.Http

class FetchService() extends Http4sDsl[IO] with ApiService with LazyLogging {

  override val verb: String = "fetch"

  /** Describe the API routes handled by this service and the actions performed on each of them
    */
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    // Query URL and return the response
    case GET -> Root / `api` / `verb` :?
        UrlParam(url) =>
      try {
        val res = Http(url).asString
        if(res.isSuccess) {
          Ok(res.body)
        } else {
          InternalServerError("Could not fetch URL")
        }
      } catch {
        case _: Exception =>
          InternalServerError("Could not fetch URL")
      }
  }

  case class RequestData(domain: String, url: String)
}

object FetchService {

  /** Service factory
    *
    * @param client Underlying http4s client
    * @return A new Fetch Service
    */
  def apply(client: Client[IO]): FetchService =
    new FetchService()
}
