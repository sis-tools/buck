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

package com.facebook.buck.event.listener;

import com.facebook.buck.distributed.FrontendService;
import com.facebook.buck.distributed.thrift.Announcement;
import com.facebook.buck.distributed.thrift.AnnouncementRequest;
import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendRequestType;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.log.CommandThreadFactory;
import com.facebook.buck.log.Logger;
import com.facebook.buck.slb.ClientSideSlb;
import com.facebook.buck.slb.LoadBalancedService;
import com.facebook.buck.slb.ThriftOverHttpServiceConfig;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.network.RemoteLogBuckConfig;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.IOException;
import java.util.concurrent.Callable;

public class PublicAnnouncementManager {

  private static final Logger LOG = Logger.get(PublicAnnouncementManager.class);

  @VisibleForTesting
  static final String HEADER_MSG = "::: Public Announcements :::";
  @VisibleForTesting
  static final String ANNOUNCEMENT_TEMPLATE = "::: Issue: %s Solution: %s";

  private Clock clock;
  private ExecutionEnvironment executionEnvironment;
  private BuckEventBus eventBus;
  private AbstractConsoleEventBusListener consoleEventBusListener;
  private String repository;
  private ListeningExecutorService service;
  private RemoteLogBuckConfig logConfig;

  public PublicAnnouncementManager(
      Clock clock,
      ExecutionEnvironment executionEnvironment,
      BuckEventBus eventBus,
      AbstractConsoleEventBusListener consoleEventBusListener,
      final String repository,
      RemoteLogBuckConfig logConfig,
      ListeningExecutorService service) {
    this.clock = clock;
    this.executionEnvironment = executionEnvironment;
    this.consoleEventBusListener = consoleEventBusListener;
    this.eventBus = eventBus;
    this.repository = repository;
    this.logConfig = logConfig;
    this.service = service;
  }

  public void getAndPostAnnouncements() {
    final ListenableFuture<ImmutableList<Announcement>> message =
        service.submit(new Callable<ImmutableList<Announcement>>() {
          @Override
          public ImmutableList<Announcement> call() throws Exception {
            Optional<ClientSideSlb> slb = logConfig.getFrontendConfig()
                .tryCreatingClientSideSlb(
                    clock,
                    eventBus,
                    new CommandThreadFactory("PublicAnnouncement"));

            if (slb.isPresent()) {
              try (FrontendService frontendService =
                  new FrontendService(ThriftOverHttpServiceConfig.of(
                      new LoadBalancedService(
                          slb.get(),
                          logConfig.createOkHttpClient(),
                          eventBus)))) {
                AnnouncementRequest announcementRequest = new AnnouncementRequest();
                announcementRequest.setBuckVersion(getBuckVersion());
                announcementRequest.setRepository(repository);
                FrontendRequest request = new FrontendRequest();
                request.setType(FrontendRequestType.ANNOUNCEMENT);
                request.setAnnouncementRequest(announcementRequest);

                FrontendResponse response = frontendService.makeRequest(request);
                return ImmutableList.copyOf(response.announcementResponse.announcements);
                } catch (IOException e) {
                throw new HumanReadableException("Failed to perform request", e);
              }
            } else {
              throw new HumanReadableException("Failed to establish connection to server.");
            }
          }
        });

    Futures.addCallback(message, new FutureCallback<ImmutableList<Announcement>>() {

      @Override
      public void onSuccess(ImmutableList<Announcement> announcements) {
        LOG.info("Public announcements fetched successfully.");
        if (!announcements.isEmpty()) {
          ImmutableList.Builder<String> finalMessages = ImmutableList.builder();
          finalMessages.add(HEADER_MSG);
          for (Announcement announcement : announcements) {
            finalMessages.add(String.format(
                ANNOUNCEMENT_TEMPLATE,
                announcement.getErrorMessage(),
                announcement.getSolutionMessage()));
          }
          consoleEventBusListener.setPublicAnnouncements(finalMessages.build());
        }
      }

      @Override
      public void onFailure(Throwable t) {
        LOG.warn("Failed to get public announcements. Reason: %s", t.getMessage());
      }
    });
  }

  private String getBuckVersion() {
    return executionEnvironment.getProperty("buck.git_commit", "unknown");
  }
}
