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

case class ValidationResult(
      msg: String
    , rs: Stream[Typing]
    , str_rdf: String
    , opt_schema: Option[String]
    , opt_iri: Option[IRI]
    , withIncoming: Boolean
    , pm: PrefixMap
    ) {

  def opt_iri_str: Option[String] = opt_iri.map(iri => iri.str)
  
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
    
   def toHTML(cut: Int): String = {
    val sb = new StringBuilder
    sb.append("<p class=\"result_ok\">" + msg + "</p>")
    sb.append("<table>")
    for (t <- rs.take(cut)) {
      sb.append("<tr>" + typing2Html(t) + "</tr>")
    }
    sb.append("</table>") 
    sb.toString
  }
}

object ValidationResult {
  def empty = ValidationResult("",Stream(), "", None, None, false,PrefixMap.empty)
  
  def failure(e: Throwable, str_rdf: String, opt_schema: Option[String], opt_iri: Option[IRI]) : ValidationResult = {
    ValidationResult(e.getMessage, Stream(),str_rdf, opt_schema, opt_iri, false,PrefixMap.empty)
  }

  def withMessage(msg: String, str_rdf: String, opt_schema: Option[String], opt_iri: Option[IRI]) : ValidationResult = {
    ValidationResult(msg, Stream(),str_rdf,opt_schema,opt_iri,false,PrefixMap.empty)
  }

  // TODO: Refactor the following code...
  def validate(
        rdf: RDF
      , str_rdf: String
      , opt_schema:Option[String] 
      , opt_iri: Option[IRI]
      , withIncoming: Boolean = false): ValidationResult = {
    RDFTriples.parse(str_rdf) match {
   	case Success(rdf) => {
      opt_schema match {
         case Some(str_schema) => {
           Schema.fromString(str_schema) match {
             case Success((schema,pm)) => {
               opt_iri match {
                  case Some(iri) => {
                    val rs = Schema.matchSchema(iri,rdf,schema,withIncoming)
                    if (rs.isValid) {
                    	 ValidationResult("Shapes found",rs.run,str_rdf,Some(str_schema),Some(iri),withIncoming,pm)
                    } else {
                       	 ValidationResult("No shapes found",rs.run,str_rdf,Some(str_schema),Some(iri),withIncoming,pm)
                    } 
                 }
                 case None => {
	               val rs = Schema.matchAll(rdf,schema,withIncoming)
	               if (rs.isValid) {
	                    ValidationResult("Shapes found",rs.run,str_rdf,Some(str_schema),None,withIncoming,pm)
	               } else {
	                    ValidationResult("No shapes found",rs.run,str_rdf,Some(str_schema),None,withIncoming,pm)
	               }
                 } 
                }
               }
       case Failure(e) => {
             ValidationResult("Schema did not parse: " + e.getMessage,Stream(),str_rdf,opt_schema,opt_iri,withIncoming,PrefixMap.empty)
           } 
       } 
  } // Some(schema...)
  case None => { 
    ValidationResult("RDF parsed",Stream(),str_rdf,opt_schema,opt_iri,withIncoming, PrefixMap.empty)
 }
 } 
 }
 case Failure(e) => {
   ValidationResult("RDF Not parsed",Stream(),str_rdf,opt_schema,opt_iri,withIncoming,PrefixMap.empty)
  }
  }
 }

}