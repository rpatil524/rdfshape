package es.weso.rdfshape.server.api.routes.data

import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import es.weso.rdf.RDFReasoner
import es.weso.rdf.jena.RDFAsJenaModel
import es.weso.rdf.sgraph.{RDF2SGraph, RDFDotPreferences}
import es.weso.rdfshape.server.api.format.DataFormat
import es.weso.rdfshape.server.api.merged.CompoundData
import es.weso.rdfshape.server.api.results.DataConversionResult
import es.weso.utils.IOUtils.{either2io, err}
import guru.nidi.graphviz.engine.{Format, Graphviz}
import guru.nidi.graphviz.model.MutableGraph
import guru.nidi.graphviz.parse.Parser

import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import scala.collection.immutable
import scala.util.Try

object DataConverter extends LazyLogging {

  lazy val availableGraphFormatNames: immutable.Seq[String] =
    availableGraphFormats.map(_.name)
  private lazy val rdfDataFormats =
    RDFAsJenaModel.availableFormats.map(_.toUpperCase)
  private lazy val availableGraphFormats = List(
    GraphFormat("SVG", "application/svg", Format.SVG),
    GraphFormat("PNG", "application/png", Format.PNG),
    GraphFormat("PS", "application/ps", Format.PS)
  )

  private[api] def dataConvert(
      maybeData: Option[String],
      dataFormat: DataFormat,
      maybeCompoundData: Option[String],
      targetFormat: String
  ): IO[DataConversionResult] = {
    logger.debug(
      s"Converting $maybeData with format $dataFormat to $targetFormat. OptTargetFormat: $targetFormat"
    )

    maybeData match {
      case None =>
        maybeCompoundData match {
          case None =>
            err(s"dataConvert: no data and no compoundData parameters")
          case Some(compoundDataStr) =>
            for {
              ecd <- either2io(CompoundData.fromString(compoundDataStr))
              cd  <- cnvEither(ecd, str => s"dataConvert: Error: $str")
              result <- cd.toRDF.flatMap(
                _.use(rdf => rdfConvert(rdf, None, dataFormat, targetFormat))
              )
            } yield result
        }
      case Some(data) =>
        RDFAsJenaModel
          .fromChars(data, dataFormat.name, None)
          .flatMap(
            _.use(rdf => rdfConvert(rdf, Some(data), dataFormat, targetFormat))
          )
    }
  }

  private def cnvEither[A](e: Either[String, A], cnv: String => String): IO[A] =
    e.fold(s => IO.raiseError(new RuntimeException(cnv(s))), IO.pure)

  private[api] def rdfConvert(
      rdf: RDFReasoner,
      data: Option[String],
      dataFormat: DataFormat,
      targetFormat: String
  ): IO[DataConversionResult] = {
    val doConversion: IO[String] = {
      logger.info(s"Conversion target format: $targetFormat")
      targetFormat.toUpperCase match {
        case "JSON" =>
          for {
            sgraph <- RDF2SGraph.rdf2sgraph(rdf)
          } yield sgraph.toJson.spaces2
        case "DOT" =>
          for {
            sgraph <- RDF2SGraph.rdf2sgraph(rdf)
          } yield sgraph.toDot(RDFDotPreferences.defaultRDFPrefs)
        case t if rdfDataFormats.contains(t) => rdf.serialize(t)
        case t if availableGraphFormatNames.contains(t) =>
          val doS: IO[String] = for {
            sgraph       <- RDF2SGraph.rdf2sgraph(rdf)
            eitherFormat <- either2io(getTargetFormat(t))
            dotStr = sgraph.toDot(RDFDotPreferences.defaultRDFPrefs)
            eitherConverted <- eitherFormat.fold(
              e => IO.raiseError(new RuntimeException(e)),
              format => either2io(dotConverter(dotStr, format))
            )
            c <- eitherFormat.fold(
              e => IO.raiseError(new RuntimeException(e)),
              _ => IO(dotStr)
            )
          } yield c
          doS
        case t =>
          IO.raiseError(new RuntimeException(s"Unsupported format: $t"))
      }
    }

    for {
      converted <- doConversion
    } yield DataConversionResult(
      "Conversion successful!",
      data,
      dataFormat,
      targetFormat,
      converted
    )
  }

  private[api] def dotConverter(
      dot: String,
      targetFormat: Format
  ): Either[String, String] = {
    logger.debug(s"dotConverter to $targetFormat. dot\n$dot")
    Try {
      val g: MutableGraph = Parser.read(dot)
      targetFormat match {
        case Format.SVG =>
          val renderer = Graphviz
            .fromGraph(g) //.width(200)
            .render(targetFormat)
          logger.info(s"SVG converted: ${renderer.toString}")
          renderer.toString
        case Format.PNG =>
          val renderer = Graphviz.fromGraph(g).render(Format.PNG)
          val image    = renderer.toImage
          val baos     = new ByteArrayOutputStream()
          ImageIO.write(image, "png", baos)
          val data        = Base64.getEncoder.encodeToString(baos.toByteArray)
          val imageString = "data:image/png;base64," + data
          "<html><body><img src='" + imageString + "'></body></html>"
        case _ => s"Error converting to $targetFormat"
      }

    }.fold(
      e => Left(e.getMessage),
      s => Right(s)
    )
  }

  private def getTargetFormat(str: String): Either[String, Format] =
    str.toUpperCase match {
      case "SVG" => Right(Format.SVG)
      case "PNG" => Right(Format.PNG)
      case "PS"  => Right(Format.PS)
      case _     => Left(s"Unsupported format $str")
    }

  private case class GraphFormat(name: String, mime: String, fmt: Format)

}
