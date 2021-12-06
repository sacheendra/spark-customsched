/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.pipeline

import java.io.IOException

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.apache.parquet.io.InputFile

import org.apache.spark.sql.execution.datasources.PipelineSemaphores

object PipelineInputFile {
  def fromPath(file: Path, conf: Configuration): InputFile = {
    val filename = file.toString
    if (PipelineSemaphores.enableCustomSched) {
      val inputFileOption: Option[ByteBufferInputFile] =
        PipelineSemaphores.cache.get(filename)
      if (inputFileOption.isDefined) {
        return inputFileOption.get
      } else {
        throw new IOException (String.format ("File %s not found in local cache", filename) )
      }
    } else {
      return HadoopInputFile.fromPath (file, conf)
    }
  }
}
