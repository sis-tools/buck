/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.io;

import static com.facebook.buck.util.concurrent.MostExecutors.newSingleThreadExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;

import com.facebook.buck.bser.BserDeserializer;
import com.facebook.buck.bser.BserSerializer;
import com.facebook.buck.log.Logger;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class WatchmanSocketClient implements WatchmanClient, AutoCloseable {

  private static final Logger LOG = Logger.get(WatchmanSocketClient.class);
  private static final long POLL_TIME_NANOS = TimeUnit.SECONDS.toNanos(1);

  private final ListeningExecutorService listeningExecutorService;
  private final Clock clock;
  private final Socket watchmanSocket;
  private final Console console;
  private final BserSerializer bserSerializer;
  private final BserDeserializer bserDeserializer;

  public WatchmanSocketClient(
      Console console,
      Clock clock,
      Socket watchmanSocket) {
    this.listeningExecutorService = listeningDecorator(newSingleThreadExecutor("Watchman"));
    this.console = console;
    this.clock = clock;
    this.watchmanSocket = watchmanSocket;
    this.bserSerializer = new BserSerializer();
    this.bserDeserializer = new BserDeserializer(BserDeserializer.KeyOrdering.UNSORTED);
  }

  @Override
  public Optional<Map<String, Object>> queryWithTimeout(
      long timeoutNanos,
      Object... query)
    throws IOException, InterruptedException {
    return queryListWithTimeout(
        timeoutNanos,
        ImmutableList.copyOf(query));
  }

  private Optional<Map<String, Object>> queryListWithTimeout(
      long timeoutNanos,
      final List<Object> query)
    throws IOException, InterruptedException {
    ListenableFuture<Optional<Map<String, Object>>> future = listeningExecutorService.submit(
        new Callable<Optional<Map<String, Object>>>() {
          @Override
          public Optional<Map<String, Object>> call() throws IOException {
            return sendWatchmanQuery(query);
          }
        });
    try {
      long startTimeNanos = clock.nanoTime();
      Optional<Map<String, Object>> result = waitForQueryNotifyingUserIfSlow(
          future,
          timeoutNanos,
          POLL_TIME_NANOS,
          query);
      long elapsedNanos = clock.nanoTime() - startTimeNanos;
      LOG.debug("Query %s returned in %d ms", query, TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
      return result;
    } catch (ExecutionException e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      } else {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public void close() throws IOException {
    LOG.debug("Closing Watchman socket.");
    watchmanSocket.close();
    listeningExecutorService.shutdown();
  }

  private Optional<Map<String, Object>> waitForQueryNotifyingUserIfSlow(
      ListenableFuture<Optional<Map<String, Object>>> future,
      long timeoutNanos,
      long pollTimeNanos,
      List<Object> query) throws InterruptedException, ExecutionException {
    long queryStartNanos = clock.nanoTime();
    try {
      return future.get(Math.min(timeoutNanos, pollTimeNanos), TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      long remainingNanos = timeoutNanos - (clock.nanoTime() - queryStartNanos);
      if (remainingNanos > 0) {
        console.getStdErr().getRawStream().format(
            "Waiting for Watchman query [%s]...\n",
            query);
        try {
          return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException te) {
          LOG.debug("Timed out");
        }
      }
      LOG.warn(
          "Watchman did not respond within %d ms, disabling.",
          TimeUnit.NANOSECONDS.toMillis(timeoutNanos));
      console.getStdErr().getRawStream().format(
          "Timed out after %d ms waiting for Watchman command [%s]. Disabling Watchman.\n",
          TimeUnit.NANOSECONDS.toMillis(timeoutNanos),
          query);
      return Optional.absent();
    }
  }

  @SuppressWarnings("unchecked")
  private Optional<Map<String, Object>> sendWatchmanQuery(List<Object> query)
      throws IOException {
    LOG.debug("Sending query: %s", query);
    bserSerializer.serializeToStream(query, watchmanSocket.getOutputStream());
    Object response = bserDeserializer.deserializeBserValue(watchmanSocket.getInputStream());
    LOG.verbose("Got response: %s", response);
    Map<String, Object> responseMap = (Map<String, Object>) response;
    if (responseMap == null) {
      LOG.error("Unrecognized Watchman response: %s", response);
      return Optional.absent();
    }
    return Optional.of(responseMap);
  }
}
