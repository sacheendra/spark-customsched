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

package org.apache.spark.sql.execution.datasources

import java.io.{FileNotFoundException, IOException}
import java.net.URI
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Semaphore

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.GetObjectRequest
import org.apache.parquet.io.ParquetDecodingException

import org.apache.spark.{Partition => RDDPartition, SparkEnv, SparkUpgradeException, TaskContext}
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.rdd.{InputFileBlockHolder, RDD}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.errors.QueryExecutionErrors
import org.apache.spark.sql.execution.datasources.pipeline.PipelineCache
import org.apache.spark.sql.vectorized.ColumnarBatch
import org.apache.spark.util.NextIterator

/**
 * A part (i.e. "block") of a single file that should be read, along with partition column values
 * that need to be prepended to each row.
 *
 * @param partitionValues value of partition columns to be prepended to each row.
 * @param filePath URI of the file to read
 * @param start the beginning offset (in bytes) of the block.
 * @param length number of bytes to read.
 * @param locations locality information (list of nodes that have the data).
 */
case class PartitionedFile(
    partitionValues: InternalRow,
    filePath: String,
    start: Long,
    length: Long,
    @transient locations: Array[String] = Array.empty) {
  override def toString: String = {
    s"path: $filePath, range: $start-${start + length}, partition values: $partitionValues"
  }
}

object PipelineSemaphores {
  val env = SparkEnv.get
  val conf = env.conf
  val enableCustomSched = conf.getBoolean("customsched.enabled", false)
  val maxReadParallelism = conf.getInt("customsched.maxreadparallelism", 8)
  val maxFastComputeParallelism = conf.getInt("customsched.maxfastcomputeparallelism", 1)
  val maxComputeParallelism = conf.getInt("customsched.maxcomputeparallelism", 7)
  var readSemaphore = new Semaphore(maxReadParallelism)
  var fastComputeSemaphore = new Semaphore(maxFastComputeParallelism)
  var computeSemaphore = new Semaphore(maxComputeParallelism)

  val cache = new PipelineCache()
}

/**
 * An RDD that scans a list of file partitions.
 */
class FileScanRDD(
    @transient private val sparkSession: SparkSession,
    readFunction: (PartitionedFile) => Iterator[InternalRow],
    @transient val filePartitions: Seq[FilePartition])
  extends RDD[InternalRow](sparkSession.sparkContext, Nil) {

  private val ignoreCorruptFiles = sparkSession.sessionState.conf.ignoreCorruptFiles
  private val ignoreMissingFiles = sparkSession.sessionState.conf.ignoreMissingFiles

  override def compute(split: RDDPartition, context: TaskContext): Iterator[InternalRow] = {
    val readSemaphore = PipelineSemaphores.readSemaphore
    val fastComputeSemaphore = PipelineSemaphores.fastComputeSemaphore
    val computeSemaphore = PipelineSemaphores.computeSemaphore
    var currentComputeSemaphore = fastComputeSemaphore

    val iterator = new Iterator[Object] with AutoCloseable {
      private val inputMetrics = context.taskMetrics().inputMetrics
      private val existingBytesRead = inputMetrics.bytesRead

      // Find a function that will return the FileSystem bytes read by this thread. Do this before
      // apply readFunction, because it might read some bytes.
      private val getBytesReadCallback =
        SparkHadoopUtil.get.getFSBytesReadOnThreadCallback()

      // We get our input bytes from thread-local Hadoop FileSystem statistics.
      // If we do a coalesce, however, we are likely to compute multiple partitions in the same
      // task and in the same thread, in which case we need to avoid override values written by
      // previous partitions (SPARK-13071).
      private def incTaskInputMetricsBytesRead(): Unit = {
        inputMetrics.setBytesRead(existingBytesRead + getBytesReadCallback())
      }

      private[this] var files = split.asInstanceOf[FilePartition].files.toIterator

      if (PipelineSemaphores.enableCustomSched) {
        val filesToCache = files
        val tempBufSize = 8192
        val tempBuf = new Array[Byte](tempBufSize)
        val s3Client = AmazonS3ClientBuilder.standard()
          .withPathStyleAccessEnabled(true)
          .withCredentials(new DefaultAWSCredentialsProviderChain())
          .build()

        readSemaphore.acquire()
        files = filesToCache.map(file => {
          val uri = new URI(file.filePath)
          val fullObject = s3Client.getObject(new GetObjectRequest(uri.getHost, uri.getPath))
          val dataSize = fullObject.getObjectMetadata.getContentLength
          val dataBuf = ByteBuffer.allocateDirect(dataSize.toInt)
          val inputStream = fullObject.getObjectContent
          var totalBytesRead = 0
          var bytesRead = 0
          while (bytesRead >= 0) {
            bytesRead = inputStream.read(tempBuf)
            dataBuf.put(tempBuf, totalBytesRead, bytesRead)
            totalBytesRead += bytesRead
          }
          inputStream.close()

          val uuid = "local$_" + UUID.randomUUID().toString
          PipelineSemaphores.cache.put(uuid, dataBuf)
          file.copy(filePath = uuid)
        })
        // fetch data and save in cache
        readSemaphore.release()

        // check if its a fast task or slow task
        currentComputeSemaphore = computeSemaphore
        currentComputeSemaphore.acquire()

        context.addTaskCompletionListener[Unit](_ => {
          currentComputeSemaphore.release()
        })
      }

      private[this] var currentFile: PartitionedFile = null
      private[this] var currentIterator: Iterator[Object] = null

      def hasNext: Boolean = {
        // Kill the task in case it has been marked as killed. This logic is from
        // InterruptibleIterator, but we inline it here instead of wrapping the iterator in order
        // to avoid performance overhead.
        context.killTaskIfInterrupted()
        if (currentIterator != null && !currentIterator.hasNext) {
          PipelineSemaphores.cache.delete(currentFile.filePath)
        }
        (currentIterator != null && currentIterator.hasNext) || nextIterator()
      }
      def next(): Object = {
        // check read speed and task duration
        // mark task as long here

        val nextElement = currentIterator.next()
        // TODO: we should have a better separation of row based and batch based scan, so that we
        // don't need to run this `if` for every record.
        val preNumRecordsRead = inputMetrics.recordsRead
        if (nextElement.isInstanceOf[ColumnarBatch]) {
          incTaskInputMetricsBytesRead()
          inputMetrics.incRecordsRead(nextElement.asInstanceOf[ColumnarBatch].numRows())
        } else {
          // too costly to update every record
          if (inputMetrics.recordsRead %
              SparkHadoopUtil.UPDATE_INPUT_METRICS_INTERVAL_RECORDS == 0) {
            incTaskInputMetricsBytesRead()
          }
          inputMetrics.incRecordsRead(1)
        }
        nextElement
      }

      private def readCurrentFile(): Iterator[InternalRow] = {
        try {
          readFunction(currentFile)
        } catch {
          case e: FileNotFoundException =>
            throw QueryExecutionErrors.readCurrentFileNotFoundError(e)
        }
      }

      /** Advances to the next file. Returns true if a new non-empty iterator is available. */
      private def nextIterator(): Boolean = {
        if (files.hasNext) {
          currentFile = files.next()
          logInfo(s"Reading File $currentFile")
          // Sets InputFileBlockHolder for the file block's information
          InputFileBlockHolder.set(currentFile.filePath, currentFile.start, currentFile.length)

          if (ignoreMissingFiles || ignoreCorruptFiles) {
            currentIterator = new NextIterator[Object] {
              // The readFunction may read some bytes before consuming the iterator, e.g.,
              // vectorized Parquet reader. Here we use lazy val to delay the creation of
              // iterator so that we will throw exception in `getNext`.
              private lazy val internalIter = readCurrentFile()

              override def getNext(): AnyRef = {
                try {
                  if (internalIter.hasNext) {
                    internalIter.next()
                  } else {
                    finished = true
                    null
                  }
                } catch {
                  case e: FileNotFoundException if ignoreMissingFiles =>
                    logWarning(s"Skipped missing file: $currentFile", e)
                    finished = true
                    null
                  // Throw FileNotFoundException even if `ignoreCorruptFiles` is true
                  case e: FileNotFoundException if !ignoreMissingFiles => throw e
                  case e @ (_: RuntimeException | _: IOException) if ignoreCorruptFiles =>
                    logWarning(
                      s"Skipped the rest of the content in the corrupted file: $currentFile", e)
                    finished = true
                    null
                }
              }

              override def close(): Unit = {}
            }
          } else {
            currentIterator = readCurrentFile()
          }

          try {
            hasNext
          } catch {
            case e: SchemaColumnConvertNotSupportedException =>
              throw QueryExecutionErrors.unsupportedSchemaColumnConvertError(
                currentFile.filePath, e.getColumn, e.getLogicalType, e.getPhysicalType, e)
            case e: ParquetDecodingException =>
              if (e.getCause.isInstanceOf[SparkUpgradeException]) {
                throw e.getCause
              } else if (e.getMessage.contains("Can not read value at")) {
                throw QueryExecutionErrors.cannotReadParquetFilesError(e)
              }
              throw e
          }
        } else {
          currentFile = null
          InputFileBlockHolder.unset()
          false
        }
      }

      override def close(): Unit = {
        incTaskInputMetricsBytesRead()
        InputFileBlockHolder.unset()
      }
    }

    // Register an on-task-completion callback to close the input stream.
    context.addTaskCompletionListener[Unit](_ => {
      iterator.close()
    })

    iterator.asInstanceOf[Iterator[InternalRow]] // This is an erasure hack.
  }

  override protected def getPartitions: Array[RDDPartition] = filePartitions.toArray

  override protected def getPreferredLocations(split: RDDPartition): Seq[String] = {
    val enableCustomSched = this.sparkContext.conf.getBoolean("customsched.enabled", false)
    if (enableCustomSched) {
      val filePartition = split.asInstanceOf[FilePartition]
      if (filePartition.files.length == 1) {
        // For stages which read a single input file per partition
        val filePath = filePartition.files(0).filePath
        Seq(this.sparkContext.schedulerBackend.executorHashRing.locate(filePath).get().getKey)
      } else {
        // For stages which read multiple files; should not encounter them for now; crossed
        split.asInstanceOf[FilePartition].preferredLocations()
      }
    } else {
      split.asInstanceOf[FilePartition].preferredLocations()
    }
  }
}
