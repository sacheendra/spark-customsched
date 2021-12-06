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

class PipelineCache(maxSize: Long) {
  var currentSize: Long = 0

  val bufferMap = scala.collection.mutable.Map[String, ByteBuffer]()

  def put(k: String, s: Int): ByteBuffer = {

    var done = false
    while (!done) {
      this.synchronized {
        if (currentSize + s <= maxSize) {
          currentSize += s
          done = true
        } else {
          // wait for space to be freed
          this.wait()
        }
      }
    }
    val buf = ByteBuffer.allocateDirect(s)
    bufferMap.put(k, buf)
    buf
  }

  def delete(k: String): Unit = {
    val v = bufferMap.remove(k)
    if (v.isDefined) {
      this.synchronized {
        currentSize -= v.get.capacity()
        this.notifyAll()
      }
    }
  }

  def get(k: String): Option[ByteBufferInputFile] = {
    bufferMap.get(k).map(buf => {
      // Seek back to start of file
      // Doesn't delete data
      buf.clear()
      new ByteBufferInputFile(buf)
    })
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
    this.read(bytes, 0, bytes.length)
  }

  override def readFully(bytes: Array[Byte], start: Int, len: Int): Unit = {
    this.read(bytes, start, len)
  }

  override def read(byteBuffer: ByteBuffer): Int = {
    val prevLimit = dataBuffer.limit()
    val bytesToCopy = Math.min(dataBuffer.limit() - dataBuffer.position(),
      byteBuffer.limit() - byteBuffer.position())
    dataBuffer.limit(dataBuffer.position() + bytesToCopy)
    byteBuffer.put(dataBuffer)
    dataBuffer.limit(prevLimit)
    bytesToCopy
  }

  override def readFully(byteBuffer: ByteBuffer): Unit = {
    this.read(byteBuffer)
  }

  override def read(): Int = {
    if (dataBuffer.hasRemaining) {
      dataBuffer.get() & 0xff // Convert the bytes to unsigned int
    } else {
      -1
    }
  }

  override def read(b: Array[Byte], off: Int, len: Int): Int = {
    val bytesToCopy = Math.min(len, dataBuffer.limit() - dataBuffer.position())
    dataBuffer.get(b, off, bytesToCopy)
    bytesToCopy
  }
}
