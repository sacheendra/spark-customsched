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

import java.nio.ByteBuffer

import org.apache.parquet.io.{InputFile, SeekableInputStream}

class PipelineCache {

  val bufferMap = scala.collection.mutable.Map[String, ByteBuffer]()

  def put(k: String, v: ByteBuffer): Unit = {
    bufferMap.put(k, v)
  }

  def delete(k: String): Unit = {
    bufferMap.remove(k)
  }

  def get(k: String): Option[ByteBufferInputFile] = {
    bufferMap.get(k).map(buf => new ByteBufferInputFile(buf))
  }

}

class ByteBufferInputFile(dataBuffer: ByteBuffer) extends InputFile {
  override def getLength: Long = dataBuffer.capacity()

  override def newStream(): SeekableInputStream = {
    new ByteBufferSeekableInputStream(dataBuffer)
  }
}

class ByteBufferSeekableInputStream(dataBuffer: ByteBuffer)
  extends SeekableInputStream {

  override def getPos: Long = dataBuffer.position()

  override def seek(l: Long): Unit = {
    dataBuffer.position(l.toInt)
  }

  override def readFully(bytes: Array[Byte]): Unit = {
    this.readFully(bytes, dataBuffer.position(), bytes.length)
  }

  override def readFully(bytes: Array[Byte], start: Int, len: Int): Unit = {
    dataBuffer.get(bytes, start, len)
  }

  override def read(byteBuffer: ByteBuffer): Int = {
    val prevLimit = dataBuffer.limit()
    val copyLimit = Math.min(byteBuffer.limit(), dataBuffer.capacity())
    val bytesCopied = copyLimit - dataBuffer.position()
    dataBuffer.limit(dataBuffer.position() + copyLimit)
    byteBuffer.put(dataBuffer)
    dataBuffer.limit(prevLimit)
    bytesCopied
  }

  override def readFully(byteBuffer: ByteBuffer): Unit = {
    byteBuffer.put(dataBuffer)
  }

  override def read(): Int = {
    if (dataBuffer.hasRemaining) {
      -1
    } else {
      dataBuffer.get()
    }
  }

  override def read(b: Array[Byte], off: Int, len: Int): Int = {
    val bufCapacity = dataBuffer.capacity()
    if (off + len > bufCapacity) {
      throw new IllegalArgumentException(s"Offset $off + " +
        s"Length $len is greater than buffer capacity $bufCapacity")
    }
    dataBuffer.get(b, off, len)
    len - off
  }
}
