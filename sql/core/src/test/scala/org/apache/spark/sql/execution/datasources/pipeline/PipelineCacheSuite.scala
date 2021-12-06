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

import java.io.{File, FileInputStream}
import java.nio.ByteBuffer

import org.apache.parquet.hadoop.ParquetFileReader

import org.apache.spark.SparkFunSuite

class PipelineCacheSuite extends SparkFunSuite {

  test("Make sure we can put, get, and delete data from cache") {
    // Create 1KB cache
    val cache = new PipelineCache(1e3.toLong)
    // Put 1kb file
    val putBuffer = cache.put("test1", 1e3.toInt)
    val tempArray = ByteBuffer.allocate(1e3.toInt)
    scala.util.Random.nextBytes(tempArray.array())
    putBuffer.put(tempArray)
    tempArray.clear()

    val readBuffer = ByteBuffer.allocate(1e3.toInt)

    val readToBufferOutputStream = cache.get("test1").get.newStream()
    readToBufferOutputStream.read(readBuffer)
    readBuffer.clear()
    assert(readBuffer.compareTo(tempArray) == 0)

    val readToArrayOutputStream = cache.get("test1").get.newStream()
    readToArrayOutputStream.read(readBuffer.array())
    readBuffer.clear()
    assert(readBuffer.compareTo(tempArray) == 0)

    val readToOffsetArrayOutputStream = cache.get("test1").get.newStream()
    readToOffsetArrayOutputStream.read(readBuffer.array(), 500, 500)
    readBuffer.position(500)
    assert(readBuffer.compareTo(tempArray.limit(500)) == 0)

    val readOneByteOutputStream = cache.get("test1").get.newStream()
    val oneByte = readOneByteOutputStream.read()
    assert(oneByte == tempArray.array()(0))
  }

  test("Block if cache is full") {
    // Create 1KB cache
    val cache = new PipelineCache(1e3.toLong)
    // Put 1kb file
    cache.put("test1", 1e3.toInt)

    val delayThread = new Thread {
      override def run {
        // This should block
        val startTime = System.currentTimeMillis()
        cache.put("test2", 1e3.toInt)
        val endTime = System.currentTimeMillis()
        cache.delete("test2")

        // As we waited for 50ms before deleting prev object
        assert(endTime - startTime >= 50)
      }
    }
    delayThread.start()
    Thread.sleep(50)
    cache.delete("test1")
    delayThread.join()

    cache.put("test3", 500)

    val noDelayThread = new Thread {
      override def run {
        // This should block
        val startTime = System.currentTimeMillis()
        cache.put("test4", 500)
        val endTime = System.currentTimeMillis()

        assert(cache.currentSize == 1e3.toLong)
        // As we waited for 50ms before deleting prev object
        assert(endTime - startTime < 50)
      }
    }
    noDelayThread.start()
    noDelayThread.join()
  }

  test("Parquet footer read") {
    val filepath = this
      .getClass
      .getClassLoader
      .getResource("test-data/timemillis-in-i64.parquet").getFile
    val aFile = new File(filepath)
    val byteBuffer = ByteBuffer.allocateDirect(aFile.length().toInt)

    val inFile = new FileInputStream(aFile)
    val inChannel = inFile.getChannel
    inChannel.read(byteBuffer)
    inChannel.close()
    byteBuffer.clear()

    val inputFile = new ByteBufferInputFile(byteBuffer)
//    val inputFile = HadoopInputFile.fromPath(new Path("file://" + filepath), new Configuration())
    val fileReader = ParquetFileReader.open(inputFile)
    try {
      val footer = fileReader.getFooter
      assert(footer != null)
    } finally fileReader.close()
  }

}
