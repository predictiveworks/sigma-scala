package de.kp.works.sigma
/*
 * Copyright (c) 2021 Dr. Krusche & Partner PartG. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 * @author Stefan Krusche, Dr. Krusche & Partner PartG
 *
 */

import akka.actor.ActorRef
import akka.http.scaladsl.model.{Multipart, StatusCodes}
import akka.http.scaladsl.server.directives.BasicDirectives.extractRequestContext
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString

import java.nio.file.{Path, Paths}
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

case class FileSource(fileName: String, source: Source[ByteString, Any])

object SigmaRoutes {

  val RULE_ACTOR = "rule_actor"

}

class SigmaRoutes(actors:Map[String, ActorRef])(implicit context: ExecutionContext) {

  import SigmaRoutes._
  /**
   * We expect that the overall configuration is initialized
   * when we build [SigmaRoutes]. If this is not the case
   * here, we continue with the internal configuration
   */
  if (!SigmaConf.isInit) SigmaConf.init()
  /**
   * Extract the base folder where the `sigma` uploads
   * are located
   */
  protected val uploadFolder: String = SigmaConf.getCfg.get
    .getString("uploadFolder")

  private val ruleActor = actors(RULE_ACTOR)

  /**
   * A HTTP route to upload a Sigma configuration *.yml;
   * note, that current implementation does not use an
   * actor to support upload.
   */
  def uploadConf:Route =
    path("config" / "upload") {
      /*
       * Define file upload as POST request without
       * any size restrictions
       */
      (post & withoutSizeLimit) {
        extractUpload("config")
      }
    }

  /**
   * A HTTP route to upload a Sigma rule *.yml;
   * note, that current implementation does not
   * use an actor to support upload.
   */
  def uploadRule:Route =
    path("rule" / "upload") {
      /*
       * Define file upload as POST request without
       * any size restrictions
       */
      (post & withoutSizeLimit) {
        extractUpload("rules")
      }
    }

  /**
   * A common method to process file uploads for Sigma configuration
   * and rule files.
   */
  private def extractUpload(path:String) =
    extractRequestContext { ctx =>
      import ctx.materializer
      entity(as[Multipart.FormData]) { data =>
        val fileSource = data.parts.collect { case bodyPart =>
          val fileName = bodyPart.filename.fold(s"${UUID.randomUUID()}")(identity)
          FileSource(fileName, bodyPart.entity.dataBytes)
        }
        onComplete(uploadFile(fileSource, path)) {
          case Success(_) => complete("File uploaded")
          case Failure(exception) => complete(StatusCodes.InternalServerError -> exception.getMessage)
        }
      }
    }

  private def uploadFile(source: Source[FileSource, Any], path:String)(implicit materializer: Materializer): Future[Unit] = {
    source.runFoldAsync(()) { (_, fileSource) =>
      val filePath: Path = Paths.get(s"$uploadFolder/$path/${ fileSource.fileName }")
      fileSource.source.runWith(FileIO.toPath(filePath)).map(_ => ())
    }
  }

}
