/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.log;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ReferenceCountedWriter extends Writer {
  private final AtomicBoolean hasBeenClosed;
  private final AtomicInteger counter;
  private final OutputStreamWriter innerWriter;

  public ReferenceCountedWriter(OutputStreamWriter innerWriter) {
    this(new AtomicInteger(1), innerWriter);
  }

  private ReferenceCountedWriter(AtomicInteger counter, OutputStreamWriter innerWriter) {
    this.hasBeenClosed = new AtomicBoolean(false);
    this.counter = counter;
    this.innerWriter = innerWriter;
  }

  public ReferenceCountedWriter newReference() {
    counter.incrementAndGet();
    return new ReferenceCountedWriter(counter, innerWriter);
  }

  @Override
  public void write(char[] cbuf, int off, int len) throws IOException {
    innerWriter.write(cbuf, off, len);
  }

  @Override
  public void flush() throws IOException {
    innerWriter.flush();
  }

  @Override
  public void write(int c) throws IOException {
    innerWriter.write(c);
  }

  @Override
  public void write(char[] cbuf) throws IOException {
    innerWriter.write(cbuf);
  }

  @Override
  public void write(String str) throws IOException {
    innerWriter.write(str);
  }

  @Override
  public void write(String str, int off, int len) throws IOException {
    innerWriter.write(str, off, len);
  }

  @Override
  public Writer append(CharSequence csq) throws IOException {
    return innerWriter.append(csq);
  }

  @Override
  public Writer append(CharSequence csq, int start, int end) throws IOException {
    return innerWriter.append(csq, start, end);
  }

  @Override
  public Writer append(char c) throws IOException {
    return innerWriter.append(c);
  }

  @Override
  public void close() throws IOException {
    // Avoid decrementing more than once from the same ReferenceCounted instance.
    if (hasBeenClosed.getAndSet(true)) {
      return;
    }

    int currentCount = counter.decrementAndGet();
    if (currentCount == 0) {
      innerWriter.close();
    }
  }

  public void flushAndClose() throws IOException {
    // Avoid decrementing more than once from the same ReferenceCounted instance.
    if (hasBeenClosed.getAndSet(true)) {
      return;
    }

    int currentCount = counter.decrementAndGet();
    if (currentCount == 0) {
      innerWriter.flush();
      innerWriter.close();
    }
  }
}
