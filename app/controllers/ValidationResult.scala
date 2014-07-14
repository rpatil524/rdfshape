package controllers

import es.weso.shex._
import es.weso.monads.{Failure => FailureMonads}
import es.weso.parser.PrefixMap
import xml.Utility.escape
import es.weso.rdfgraph.nodes.RDFNode
import es.weso.rdfgraph.nodes.IRI
import es.weso.rdf.RDFTriples
import com.hp.hpl.jena.sparql.function.library.e
import scala.util._
import es.weso.rdf.RDF
import play.Logger

case class ValidationResult(
      status: Option[Boolean]
    , msg: String
    , rs: Stream[Typing]
    , nodes: List[RDFNode]
    , str_rdf: String
    , opts_rdf : RDFOptions
    , withSchema : Boolean
    , str_schema: String
    , opt_schema: SchemaOptions
    , pm: PrefixMap
    ) {

  def typing2Html(typing: Typing): String = {
    val sb = new StringBuilder
    sb.append("<tr><th>Node</th><th>Shape</th></tr>")
    for ((node,ts) <- typing.map) {
      sb.append("<tr><td>" + node2Html(node) + 
    		  		 "</td><td>" + nodes2Html(ts) + "</td></tr>")
    }
    sb.toString
  }
  
 def nodes2Html(nodes: Set[RDFNode]): String = {
     val sb = new StringBuilder
     sb.append("<ul class=\"nodes\">")
     for (node <- nodes) {
       sb.append("<li>" + node2Html(node) + "</li>")
     }
     sb.append("</ul>")
     sb.toString
   }

   def node2Html(node: RDFNode): String = {
     if (node.isIRI) ("<a href=\"" + node.toIRI.str + "\">" + escape(node.toIRI.str) + "</a>")
     else escape(node.toString)
   }
    
   def toHTML(): String = {
    val sb = new StringBuilder
    val cut = opt_schema.cut
    // Logger.info("toHTML...cut = " + cut + " rs = " + rs)
    for ((t,n) <- rs.take(cut).toSet zip (1 to cut)) {
      // Logger.info("toHTML...sb = " + sb.toString + " n = " + n + "t = " + t)
      sb.append("<h2 class='shapes'>Result " + n + "</h2>")
      sb.append("<table>")
      sb.append(typing2Html(t))
      sb.append("</table>")
      val nodesWithoutTyping = nodes.filter(n => t.hasType(n).isEmpty).toSet
      if (!nodesWithoutTyping.isEmpty) {
    	  sb.append("<p>Nodes without shapes</p>")
    	  sb.append(nodes2Html(nodesWithoutTyping))
      }
    }
    sb.toString
  }
  
  // Conversions to generate permalinks
  def schema_param : Option[String] = {
    if (withSchema) Some(str_schema)
    else None
  }
  
  def opt_iri_param : Option[String] = {
    opt_schema.opt_iri.map(_.str) 
  }
}

object ValidationResult {
  // TODO: refactor the following ugly code
  def empty = 
    ValidationResult(None,"",Stream(), List(),"", RDFOptions.default,false, "", SchemaOptions.default, PrefixMap.empty)
  
/*  def failure(e: Throwable, str_rdf: String, opt_schema: Option[String], opt_iri: Option[IRI]) : ValidationResult = {
    ValidationResult(Some(false),e.getMessage, Stream(),List(),str_rdf, RDFOptions.default, false, "", SchemaOptions.default, PrefixMap.empty)
  }

  def withMessage(msg: String, str_rdf: String = "", opt_schema: Option[String] = None, opt_iri: Option[IRI] = None) : ValidationResult = {
    ValidationResult(Some(false),msg, Stream(),List(),str_rdf,RDFOptions.default, false,"",SchemaOptions.default,PrefixMap.empty)
  } */
  
    def validateIRI(
        iri : IRI
      , rdf: RDF
      , str_rdf: String
      , rdfOptions: RDFOptions
      , schema: Schema
      , str_schema: String
      , schemaOptions: SchemaOptions
      , pm: PrefixMap
      ): ValidationResult = {
  val rs = Schema.matchSchema(iri,rdf,schema,schemaOptions.withIncoming,schemaOptions.withOpenShapes,schemaOptions.withAny)
  if (rs.isValid) {
   	 ValidationResult(Some(true),"Shapes found",rs.run,List(iri),str_rdf,rdfOptions, true, str_schema, schemaOptions,pm)
  } else {
     ValidationResult(Some(false),"No shapes found",rs.run,List(iri),str_rdf,rdfOptions,true, str_schema,schemaOptions,pm)
  } 
 }

  def validateAny(
        rdf: RDF
      , str_rdf: String
      , rdfOptions: RDFOptions
      , schema: Schema
      , str_schema: String
      , schemaOptions: SchemaOptions
      , pm: PrefixMap
      ): ValidationResult = {
 val nodes = rdf.subjects.toList
 val rs = Schema.matchAll(rdf,schema,schemaOptions.withIncoming,schemaOptions.withOpenShapes,schemaOptions.withAny)
 if (rs.isValid) {
   ValidationResult(Some(true),"Shapes found",rs.run,nodes,str_rdf,rdfOptions,true, str_schema,schemaOptions,pm)
 } else {
   ValidationResult(Some(false),"No shapes found",rs.run,nodes,str_rdf,rdfOptions,true,str_schema,schemaOptions,pm)
 }
}     

  // TODO: Refactor the following code...
 def validate(
        rdf: RDF
      , str_rdf: String
      , rdfOptions: RDFOptions
      , withSchema : Boolean
      , str_schema : String
      , schemaOptions: SchemaOptions 
      ): ValidationResult = {
   if (withSchema) {
         Try(Schema.fromString(str_schema).get) match {
         case Success((schema,pm)) => {
               schemaOptions.opt_iri match {
                 case Some(iri) => validateIRI(iri,rdf,str_rdf,rdfOptions,schema,str_schema,schemaOptions,pm) 
                 case None => validateAny(rdf,str_rdf,rdfOptions,schema,str_schema,schemaOptions,pm)
               }
         }
         case Failure(e) => {
           Logger.info("Schema did not parse..." + e.getMessage)
           ValidationResult(Some(false),
               "Schema did not parse: " + e.getMessage,
               Stream(),List(),str_rdf,rdfOptions, true, 
               str_schema,schemaOptions,
               PrefixMap.empty)
         } 
       } 
    } else  
     ValidationResult(Some(true),"RDF parsed",
         Stream(),List(),str_rdf,rdfOptions,false,
         str_schema,schemaOptions,
         PrefixMap.empty)
 } 

}


