package es.weso.rdfshape

import com.typesafe.scalalogging._
import es.weso.rdfshape.cli.ArgumentsData.unapply
import es.weso.rdfshape.cli.{ArgumentsData, CliManager}
import es.weso.rdfshape.logging.LoggingManager
import es.weso.rdfshape.logging.LoggingManager.systemPropertyVerbosity
import es.weso.rdfshape.server.Server
import es.weso.rdfshape.server.Server.systemPropertyStreamTimeout

object Main extends App with LazyLogging {

  run(args)

  /** Entrypoint to the application. Parse arguments and start the API server
    * @param args Command line arguments entered when launching the application
    * @see {@link es.weso.rdfshape.server.Server}
    */
  private def run(args: Array[String]): Unit = {
    // Parse arguments
    val argumentsData                                   = parseArguments(args)
    val (port, https, verbosity, silent, streamTimeout) = unapply(argumentsData)
    // Set up the logging framework depending on the verbose argument
    setUpLogger(verbosity, silent)
    // Set up timeout for streaming validations
    setUpStreamTimeout(streamTimeout)
    // Start the server module
    CliManager.showBanner()
    Server(port, https)
  }

  /** Parse and validate the user-entered arguments
    *
    * @param args Array of arguments passed to the executable
    * @return An {@linkplain es.weso.rdfshape.cli.ArgumentsData inmutable instance} that provides access to the arguments
    */
  private def parseArguments(args: Array[String]): ArgumentsData = {
    val cliManager = new CliManager(args)
    ArgumentsData(
      port = cliManager.port.apply(),
      https = cliManager.https.apply(),
      verbosity = cliManager.verbose.apply(),
      silent = cliManager.silent.apply(),
      streamTimeout = cliManager.streamTimeout.apply()
    )
  }

  /** Let [[LoggingManager]] prepare the logging framework according to the user's CLI arguments and inform the user
    * @param verbosity Verbosity level introduced by the user
    */
  private def setUpLogger(verbosity: Int, silent: Boolean = false): Unit = {
    LoggingManager.setUp(verbosity, silent)
    if(!silent)
      logger.info(
        s"Console logging filter set to ${System.getProperty(systemPropertyVerbosity)}"
      )
  }

  /** Set up the system property storing the stream timeout
    * @param timeout Timeout in seconds
    */
  private def setUpStreamTimeout(
      timeout: Int
  ): Unit = {
    System.setProperty(
      systemPropertyStreamTimeout,
      timeout.toString
    )
  }
}
