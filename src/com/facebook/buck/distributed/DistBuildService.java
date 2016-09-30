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

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.thrift.BuckVersion;
import com.facebook.buck.distributed.thrift.BuildId;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.BuildStatusRequest;
import com.facebook.buck.distributed.thrift.CASContainsRequest;
import com.facebook.buck.distributed.thrift.CreateBuildRequest;
import com.facebook.buck.distributed.thrift.FetchBuildGraphRequest;
import com.facebook.buck.distributed.thrift.FetchSourceFilesRequest;
import com.facebook.buck.distributed.thrift.FetchSourceFilesResponse;
import com.facebook.buck.distributed.thrift.FileInfo;
import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendRequestType;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.distributed.thrift.SetBuckVersionRequest;
import com.facebook.buck.distributed.thrift.StartBuildRequest;
import com.facebook.buck.distributed.thrift.StoreBuildGraphRequest;
import com.facebook.buck.distributed.thrift.StoreLocalChangesRequest;
import com.facebook.buck.log.Logger;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;


public class DistBuildService implements Closeable {
  private static final Logger LOG = Logger.get(DistBuildService.class);
  private final FrontendService service;

  public DistBuildService(
      FrontendService service) {
    this.service = service;
  }

  public ListenableFuture<Void> uploadTargetGraph(
      final BuildJobState buildJobStateArg,
      final BuildId buildId,
      ListeningExecutorService executorService) throws IOException {
    // TODO(shivanker): We shouldn't be doing this. Fix after we stop reading all files into memory.
    final BuildJobState buildJobState = buildJobStateArg.deepCopy();
    return executorService.submit(new Callable<Void>() {
      @Override
      public Void call() throws IOException {
        // Get rid of file contents from buildJobState
        for (BuildJobStateFileHashes cell : buildJobState.getFileHashes()) {
          if (!cell.isSetEntries()) {
            continue;
          }
          for (BuildJobStateFileHashEntry file : cell.getEntries()) {
            file.unsetContents();
          }
        }

        // Now serialize and send the whole buildJobState
        StoreBuildGraphRequest storeBuildGraphRequest = new StoreBuildGraphRequest();
        storeBuildGraphRequest.setBuildId(buildId);
        storeBuildGraphRequest.setBuildGraph(BuildJobStateSerializer.serialize(buildJobState));

        FrontendRequest request = new FrontendRequest();
        request.setType(FrontendRequestType.STORE_BUILD_GRAPH);
        request.setStoreBuildGraphRequest(storeBuildGraphRequest);
        makeRequestChecked(request);
        // No response expected.
        return null;
      }
    });
  }

  public ListenableFuture<Void> uploadMissingFiles(
      final List<BuildJobStateFileHashes> fileHashes,
      ListeningExecutorService executorService) throws IOException {
    return executorService.submit(new Callable<Void>() {
      @Override
      public Void call() throws IOException {
        Map<String, BuildJobStateFileHashEntry> sha1ToFileEntry = new HashMap<>();
        for (BuildJobStateFileHashes filesystem : fileHashes) {
          if (!filesystem.isSetEntries()) {
            continue;
          }
          for (BuildJobStateFileHashEntry file : filesystem.entries) {
            // TODO(shivanker): Eventually, we won't have file contents in BuildJobState.
            // Then change this code to load file contents inline (only for missing files)
            sha1ToFileEntry.put(file.hashCode, file);
          }
        }

        List<String> contentHashes = ImmutableList.copyOf(sha1ToFileEntry.keySet());
        CASContainsRequest containsReq = new CASContainsRequest();
        containsReq.setContentSha1s(contentHashes);
        FrontendRequest request = new FrontendRequest();
        request.setType(FrontendRequestType.CAS_CONTAINS);
        request.setCasContainsRequest(containsReq);
        FrontendResponse response = makeRequestChecked(request);

        Preconditions.checkState(
            response.getCasContainsResponse().exists.size() == contentHashes.size());
        List<Boolean> isPresent = response.getCasContainsResponse().exists;
        List<FileInfo> filesToBeUploaded = new LinkedList<>();
        for (int i = 0; i < isPresent.size(); ++i) {
          BuildJobStateFileHashEntry fileHashEntry = sha1ToFileEntry.get(contentHashes.get(i));
          if (isPresent.get(i)) {
            LOG.info(
                "File with path [%s] has already been uploaded. Skipping..",
                fileHashEntry.path.getPath());
            continue;
          }
          if (fileHashEntry.isSetRootSymLink()) {
            LOG.info(
                "File with path [%s] is a symlink. Skipping upload..",
                fileHashEntry.path.getPath());
            continue;
          }

          LOG.info("Uploading file with path [%s]",  fileHashEntry.path.getPath());
          FileInfo file = new FileInfo();
          file.setContentHash(fileHashEntry.getHashCode());
          file.setContent(fileHashEntry.getContents());
          filesToBeUploaded.add(file);
        }


        request = new FrontendRequest();
        StoreLocalChangesRequest storeReq = new StoreLocalChangesRequest();
        storeReq.setFiles(filesToBeUploaded);
        request.setType(FrontendRequestType.STORE_LOCAL_CHANGES);
        request.setStoreLocalChangesRequest(storeReq);
        makeRequestChecked(request);
        // No response expected.
        return null;
      }
    });
  }

  public BuildJob createBuild() throws IOException {
    // Tell server to create the build and get the build id.
    CreateBuildRequest createTimeRequest = new CreateBuildRequest();
    createTimeRequest.setCreateTimestampMillis(System.currentTimeMillis());
    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.CREATE_BUILD);
    request.setCreateBuildRequest(createTimeRequest);
    FrontendResponse response = makeRequestChecked(request);

    return response.getCreateBuildResponse().getBuildJob();
  }

  public BuildJob startBuild(BuildId id) throws IOException {
    // Start the build
    StartBuildRequest startRequest = new StartBuildRequest();
    startRequest.setBuildId(id);
    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.START_BUILD);
    request.setStartBuildRequest(startRequest);
    FrontendResponse response = makeRequestChecked(request);

    BuildJob job = response.getStartBuildResponse().getBuildJob();
    Preconditions.checkState(job.getBuildId().equals(id));
    return job;
  }

  public BuildJob getCurrentBuildJobState(BuildId id) throws IOException {
    BuildStatusRequest statusRequest = new BuildStatusRequest();
    statusRequest.setBuildId(id);
    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.BUILD_STATUS);
    request.setBuildStatusRequest(statusRequest);
    FrontendResponse response = makeRequestChecked(request);

    BuildJob job = response.getBuildStatusResponse().getBuildJob();
    Preconditions.checkState(job.getBuildId().equals(id));
    return job;
  }

  public BuildJobState fetchBuildJobState(BuildId buildId) throws IOException {
    FrontendRequest request = createFetchBuildGraphRequest(buildId);
    FrontendResponse response = makeRequestChecked(request);

    Preconditions.checkState(response.isSetFetchBuildGraphResponse());
    Preconditions.checkState(response.getFetchBuildGraphResponse().isSetBuildGraph());
    Preconditions.checkState(response.getFetchBuildGraphResponse().getBuildGraph().length > 0);

    return BuildJobStateSerializer.deserialize(
        response.getFetchBuildGraphResponse().getBuildGraph());
  }

  public static FrontendRequest createFetchBuildGraphRequest(BuildId buildId) {
    FetchBuildGraphRequest fetchBuildGraphRequest = new FetchBuildGraphRequest();
    fetchBuildGraphRequest.setBuildId(buildId);
    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.FETCH_BUILD_GRAPH);
    frontendRequest.setFetchBuildGraphRequest(fetchBuildGraphRequest);
    return frontendRequest;
  }

  public InputStream fetchSourceFile(String hashCode) throws IOException {
    FrontendRequest request = createFetchSourceFileRequest(hashCode);
    FrontendResponse response = makeRequestChecked(request);

    Preconditions.checkState(response.isSetFetchSourceFilesResponse());
    Preconditions.checkState(response.getFetchSourceFilesResponse().isSetFiles());
    FetchSourceFilesResponse fetchSourceFilesResponse = response.getFetchSourceFilesResponse();
    Preconditions.checkState(1 == fetchSourceFilesResponse.getFilesSize());
    FileInfo file = fetchSourceFilesResponse.getFiles().get(0);
    Preconditions.checkState(file.isSetContent());

    return new ByteArrayInputStream(file.getContent());
  }

  public static FrontendRequest createFetchSourceFileRequest(String fileHash) {
    FetchSourceFilesRequest fetchSourceFileRequest = new FetchSourceFilesRequest();
    fetchSourceFileRequest.setContentHashesIsSet(true);
    fetchSourceFileRequest.addToContentHashes(fileHash);
    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.FETCH_SRC_FILES);
    frontendRequest.setFetchSourceFilesRequest(fetchSourceFileRequest);
    return frontendRequest;
  }

  public static FrontendRequest createFrontendBuildStatusRequest(BuildId buildId) {
    BuildStatusRequest buildStatusRequest = new BuildStatusRequest();
    buildStatusRequest.setBuildId(buildId);
    FrontendRequest frontendRequest = new FrontendRequest();
    frontendRequest.setType(FrontendRequestType.BUILD_STATUS);
    frontendRequest.setBuildStatusRequest(buildStatusRequest);
    return frontendRequest;
  }

  public void setBuckVersion(BuildId id, BuckVersion buckVersion) throws IOException {
    SetBuckVersionRequest setBuckVersionRequest = new SetBuckVersionRequest();
    setBuckVersionRequest.setBuildId(id);
    setBuckVersionRequest.setBuckVersion(buckVersion);
    FrontendRequest request = new FrontendRequest();
    request.setType(FrontendRequestType.SET_BUCK_VERSION);
    request.setSetBuckVersionRequest(setBuckVersionRequest);
    makeRequestChecked(request);
  }

  @Override
  public void close() throws IOException {
    service.close();
  }

  private FrontendResponse makeRequestChecked(FrontendRequest request) throws IOException {
    FrontendResponse response = service.makeRequest(request);
    Preconditions.checkState(response.isSetWasSuccessful());
    if (!response.wasSuccessful) {
      throw new IOException(String.format(
          "Stampede request of type [%s] failed with error message [%s].",
          request.getType().toString(),
          response.getErrorMessage()));
    }
    Preconditions.checkState(request.getType().equals(response.getType()));
    return response;
  }
}
