package es.weso.server

import cats.effect._
import es.weso.schema._
import es.weso.server.APIDefinitions._
import es.weso.shapeMaps.ShapeMap
import io.circe._
import org.http4s._
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.dsl.Http4sDsl
import org.log4s.getLogger

class ShapeMapService[F[_]:ConcurrentEffect: Timer](blocker: Blocker,
                                               client: Client[F])(implicit cs: ContextShift[F])
  extends Http4sDsl[F] {

  val routes = HttpRoutes.of[F] {

    case GET -> Root / `api` / "shapeMap" / "formats" => {
      val formats = ShapeMap.availableFormats
      val json = Json.fromValues(formats.map(str => Json.fromString(str)))
      Ok(json)
    }
 }
}

object ShapeMapService {
  def apply[F[_]: ConcurrentEffect: ContextShift: Timer](blocker: Blocker, client: Client[F]): ShapeMapService[F] =
    new ShapeMapService[F](blocker, client)
}



