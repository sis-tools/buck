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

package com.facebook.buck.shell;

import static java.lang.Thread.sleep;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.FakeBuckEventListener;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

import javax.annotation.Nullable;

public class WorkerShellStepTest {

  private static String startupCommand = "startupCommand";
  private static String startupArgs = "startupArgs";
  private static final String fakeWorkerStartupCommand =
      String.format("/bin/bash -e -c %s %s", startupCommand, startupArgs);

  private WorkerShellStep createWorkerShellStep(
      @Nullable WorkerJobParams cmdParams,
      @Nullable WorkerJobParams bashParams,
      @Nullable WorkerJobParams cmdExeParams) {
    return new WorkerShellStep(
        new FakeProjectFilesystem(),
        Optional.fromNullable(cmdParams),
        Optional.fromNullable(bashParams),
        Optional.fromNullable(cmdExeParams));
  }

  private WorkerJobParams createJobParams() {
    return createJobParams(
        ImmutableList.<String>of(),
        "",
        ImmutableMap.<String, String>of(),
        "");
  }

  private WorkerJobParams createJobParams(
      ImmutableList<String> startupCommand,
      String startupArgs,
      ImmutableMap<String, String> startupEnv,
      String jobArgs) {
    return createJobParams(startupCommand, startupArgs, startupEnv, jobArgs, Optional.of(1));
  }

  private WorkerJobParams createJobParams(
      ImmutableList<String> startupCommand,
      String startupArgs,
      ImmutableMap<String, String> startupEnv,
      String jobArgs,
      Optional<Integer> maxWorkers) {
    return WorkerJobParams.of(
        Paths.get("tmp").toAbsolutePath().normalize(),
        startupCommand,
        startupArgs,
        startupEnv,
        jobArgs,
        maxWorkers);
  }

  private ExecutionContext createExecutionContextWith(
      int exitCode,
      String stdout,
      String stderr)
      throws IOException {
    WorkerJobResult jobResult = WorkerJobResult.of(
        exitCode,
        Optional.of(stdout),
        Optional.of(stderr));
    return createExecutionContextWith(ImmutableMap.of("myJobArgs", jobResult));
  }

  private ExecutionContext createExecutionContextWith(
      final ImmutableMap<String, WorkerJobResult> jobArgs) {
    return createExecutionContextWith(jobArgs, Optional.of(1));
  }

  private ExecutionContext createExecutionContextWith(
      final ImmutableMap<String, WorkerJobResult> jobArgs,
      final Optional<Integer> poolCapacity) {
    ConcurrentHashMap<String, WorkerProcessPool> workerProcessMap = new ConcurrentHashMap<>();
    WorkerProcessPool workerProcessPool = new WorkerProcessPool(poolCapacity) {
      @Override
      protected WorkerProcess startWorkerProcess() throws IOException {
        return new FakeWorkerProcess(jobArgs);
      }
    };
    workerProcessMap.put(fakeWorkerStartupCommand, workerProcessPool);

    ExecutionContext context = TestExecutionContext
        .newBuilder()
        .setPlatform(Platform.LINUX)
        .setWorkerProcessPools(workerProcessMap)
        .setConsole(new TestConsole(Verbosity.ALL))
        .setBuckEventBus(BuckEventBusFactory.newInstance())
        .build();

    return context;
  }

  @Test
  public void testCmdParamsAreAlwaysUsedIfOthersAreNotSpecified() {
    WorkerJobParams cmdParams = createJobParams();
    WorkerShellStep step = createWorkerShellStep(cmdParams, null, null);
    assertThat(
        step.getWorkerJobParamsToUse(Platform.WINDOWS),
        Matchers.sameInstance(cmdParams));
    assertThat(step.getWorkerJobParamsToUse(Platform.LINUX), Matchers.sameInstance(cmdParams));
    assertThat(step.getWorkerJobParamsToUse(Platform.MACOS), Matchers.sameInstance(cmdParams));
  }

  @Test
  public void testBashParamsAreUsedForNonWindowsPlatforms() {
    WorkerJobParams cmdParams = createJobParams();
    WorkerJobParams bashParams = createJobParams();
    WorkerShellStep step = createWorkerShellStep(cmdParams, bashParams, null);
    assertThat(
        step.getWorkerJobParamsToUse(Platform.WINDOWS),
        Matchers.sameInstance(cmdParams));
    assertThat(
        step.getWorkerJobParamsToUse(Platform.LINUX),
        Matchers.sameInstance(bashParams));
    assertThat(
        step.getWorkerJobParamsToUse(Platform.MACOS),
        Matchers.sameInstance(bashParams));
  }

  @Test
  public void testCmdExeParamsAreUsedForWindows() {
    WorkerJobParams cmdParams = createJobParams();
    WorkerJobParams cmdExeParams = createJobParams();
    WorkerShellStep step = createWorkerShellStep(cmdParams, null, cmdExeParams);
    assertThat(
        step.getWorkerJobParamsToUse(Platform.WINDOWS),
        Matchers.sameInstance(cmdExeParams));
    assertThat(
        step.getWorkerJobParamsToUse(Platform.LINUX),
        Matchers.sameInstance(cmdParams));
    assertThat(
        step.getWorkerJobParamsToUse(Platform.MACOS),
        Matchers.sameInstance(cmdParams));
  }

  @Test
  public void testPlatformSpecificParamsArePreferredOverCmdParams() {
    WorkerJobParams cmdParams = createJobParams();
    WorkerJobParams bashParams = createJobParams();
    WorkerJobParams cmdExeParams = createJobParams();
    WorkerShellStep step = createWorkerShellStep(cmdParams, bashParams, cmdExeParams);
    assertThat(
        step.getWorkerJobParamsToUse(Platform.WINDOWS),
        Matchers.sameInstance(cmdExeParams));
    assertThat(
        step.getWorkerJobParamsToUse(Platform.LINUX),
        Matchers.sameInstance(bashParams));
    assertThat(
        step.getWorkerJobParamsToUse(Platform.MACOS),
        Matchers.sameInstance(bashParams));
  }

  @Test(expected = HumanReadableException.class)
  public void testNotSpecifyingParamsThrowsException() {
    WorkerShellStep step = createWorkerShellStep(null, null, null);
    step.getWorkerJobParamsToUse(Platform.LINUX);
  }

  @Test
  public void testGetCommand() {
    WorkerJobParams cmdParams = createJobParams(
        ImmutableList.of("command"),
        "--platform unix-like",
        ImmutableMap.<String, String>of(),
        "job params");
    WorkerJobParams cmdExeParams = createJobParams(
        ImmutableList.of("command"),
        "--platform windows",
        ImmutableMap.<String, String>of(),
        "job params");

    WorkerShellStep step = createWorkerShellStep(cmdParams, null, cmdExeParams);
    assertThat(
        step.getCommand(Platform.LINUX),
        Matchers.equalTo(
            ImmutableList.of(
                "/bin/bash",
                "-e",
                "-c",
                "command --platform unix-like")));
    assertThat(
        step.getCommand(Platform.WINDOWS),
        Matchers.equalTo(
            ImmutableList.of(
                "cmd.exe",
                "/c",
                "command --platform windows")));
  }

  @Test
  public void testExpandEnvironmentVariables() {
    WorkerShellStep step = createWorkerShellStep(createJobParams(), null, null);
    assertThat(
        step.expandEnvironmentVariables(
            "the quick brown $FOX jumps over the ${LAZY} dog",
            ImmutableMap.of("FOX", "fox_expanded", "LAZY", "lazy_expanded")),
        Matchers.equalTo("the quick brown fox_expanded jumps over the lazy_expanded dog"));
  }

  @Test
  public void testJobIsExecutedAndResultIsReceived()
      throws IOException, InterruptedException {
    String stdout = "my stdout";
    String stderr = "my stderr";
    ExecutionContext context = createExecutionContextWith(0, stdout, stderr);
    WorkerShellStep step = createWorkerShellStep(
        createJobParams(
            ImmutableList.of(startupCommand),
            startupArgs,
            ImmutableMap.<String, String>of(),
            "myJobArgs"),
        null,
        null);

    FakeBuckEventListener listener = new FakeBuckEventListener();
    context.getBuckEventBus().register(listener);

    int exitCode = step.execute(context).getExitCode();
    assertThat(exitCode, Matchers.equalTo(0));

    // assert that the job's stdout and stderr were written to the console
    BuckEvent firstEvent = listener.getEvents().get(0);
    assertTrue(firstEvent instanceof ConsoleEvent);
    assertThat(((ConsoleEvent) firstEvent).getLevel(), Matchers.is(Level.INFO));
    assertThat(((ConsoleEvent) firstEvent).getMessage(), Matchers.is(stdout));
    BuckEvent secondEvent = listener.getEvents().get(1);
    assertTrue(secondEvent instanceof ConsoleEvent);
    assertThat(((ConsoleEvent) secondEvent).getLevel(), Matchers.is(Level.WARNING));
    assertThat(((ConsoleEvent) secondEvent).getMessage(), Matchers.is(stderr));
  }

  @Test
  public void testExecuteTwoShellStepsWithSameWorker()
      throws IOException, InterruptedException, TimeoutException, ExecutionException {
    String jobArgs1 = "jobArgs1";
    String jobArgs2 = "jobArgs2";
    final ExecutionContext context = createExecutionContextWith(
        ImmutableMap.of(
          jobArgs1, WorkerJobResult.of(0, Optional.of("stdout 1"), Optional.of("stderr 1")),
          jobArgs2, WorkerJobResult.of(0, Optional.of("stdout 2"), Optional.of("stderr 2"))));

    WorkerJobParams params = createJobParams(
        ImmutableList.of(startupCommand),
        startupArgs,
        ImmutableMap.<String, String>of(),
        jobArgs1,
        Optional.of(1));

    WorkerShellStep step1 = createWorkerShellStep(params, null, null);
    final WorkerShellStep step2 =
        createWorkerShellStep(params.withJobArgs(jobArgs2), null, null);

    step1.execute(context);

    Future<?> stepExecution = Executors.newSingleThreadExecutor().submit(new Runnable() {
      @Override
      public void run() {
        try {
          step2.execute(context);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    });

    stepExecution.get(25, TimeUnit.MILLISECONDS);
  }

  @Test
  public void testStdErrIsPrintedAsErrorIfJobFails()
      throws IOException, InterruptedException {
    String stderr = "my stderr";
    ExecutionContext context = createExecutionContextWith(1, "", stderr);
    WorkerShellStep step = createWorkerShellStep(
        createJobParams(
            ImmutableList.of(startupCommand),
            startupArgs,
            ImmutableMap.<String, String>of(),
            "myJobArgs"),
        null,
        null);

    FakeBuckEventListener listener = new FakeBuckEventListener();
    context.getBuckEventBus().register(listener);

    int exitCode = step.execute(context).getExitCode();
    assertThat(exitCode, Matchers.equalTo(1));

    // assert that the job's stderr was written to the console as error, not as warning
    BuckEvent firstEvent = listener.getEvents().get(0);
    assertTrue(firstEvent instanceof ConsoleEvent);
    assertThat(((ConsoleEvent) firstEvent).getLevel(), Matchers.is(Level.SEVERE));
    assertThat(((ConsoleEvent) firstEvent).getMessage(), Matchers.is(stderr));
  }

  @Test
  public void testGetEnvironmentForProcess() {
    WorkerShellStep step = new WorkerShellStep(
        new FakeProjectFilesystem(),
        Optional.of(createJobParams(
            ImmutableList.<String>of(),
            "",
            ImmutableMap.<String, String>of("BAK", "chicken"),
            "$FOO $BAR $BAZ $BAK")),
        Optional.<WorkerJobParams>absent(),
        Optional.<WorkerJobParams>absent()) {

      @Override
      protected ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
        return ImmutableMap.of(
            "FOO", "foo_expanded",
            "BAR", "bar_expanded");
      }
    };

    ExecutionContext context = TestExecutionContext
        .newBuilder()
        .setEnvironment(
            ImmutableMap.of(
                "BAR", "this should be ignored for substitution",
                "BAZ", "baz_expanded"))
        .build();

    Map<String, String> processEnv = Maps.newHashMap(step.getEnvironmentForProcess(context));
    processEnv.remove("TMP");
    assertThat(
        processEnv,
        Matchers.<Map<String, String>>equalTo(
            ImmutableMap.of(
                "BAR", "this should be ignored for substitution",
                "BAZ", "baz_expanded",
                "BAK", "chicken")));
    assertThat(
        step.getExpandedJobArgs(context),
        Matchers.equalTo(
            "foo_expanded bar_expanded $BAZ $BAK"));
  }

  @Test
  public void testMultipleWorkerProcesses() throws IOException, InterruptedException {
    String jobArgsA = "jobArgsA";
    String jobArgsB = "jobArgsB";
    final ImmutableMap<String, WorkerJobResult> jobResults =
        ImmutableMap.of(
          jobArgsA, WorkerJobResult.of(0, Optional.of("stdout A"), Optional.of("stderr A")),
          jobArgsB, WorkerJobResult.of(0, Optional.of("stdout B"), Optional.of("stderr B")));

    class WorkerShellStepWithFakeProcesses extends WorkerShellStep {
      WorkerShellStepWithFakeProcesses(WorkerJobParams jobParams) {
        super(
            new FakeProjectFilesystem(),
            Optional.fromNullable(jobParams),
            Optional.<WorkerJobParams>absent(),
            Optional.<WorkerJobParams>absent());
      }

      @Override
      WorkerProcess createWorkerProcess(
          ProcessExecutorParams processParams,
          ExecutionContext context,
          Path tmpDir) throws IOException {
        try {
          sleep(5);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
        return new FakeWorkerProcess(jobResults);
      }
    }

    ExecutionContext context = TestExecutionContext
        .newBuilder()
        .setPlatform(Platform.LINUX)
        .setConsole(new TestConsole(Verbosity.ALL))
        .setBuckEventBus(BuckEventBusFactory.newInstance())
        .build();

    WorkerJobParams jobParamsA = createJobParams(
        ImmutableList.of(startupCommand),
        startupArgs,
        ImmutableMap.<String, String>of(),
        jobArgsA,
        Optional.of(2));
    WorkerShellStep stepA = new WorkerShellStepWithFakeProcesses(jobParamsA);
    WorkerShellStep stepB = new WorkerShellStepWithFakeProcesses(jobParamsA.withJobArgs(jobArgsB));

    Thread[] threads = {
      new ConcurrentExecution(stepA, context),
      new ConcurrentExecution(stepB, context),
    };

    for (Thread t : threads) {
      t.start();
    }

    for (Thread t : threads) {
      t.join();
    }

    Collection<WorkerProcessPool> pools = context.getWorkerProcessPools().values();
    assertThat(pools.size(), Matchers.equalTo(1));

    WorkerProcessPool pool = pools.iterator().next();
    assertThat(pool.getCapacity(), Matchers.equalTo(2));
  }

  @Test
  public void testWarningIsPrintedForIdenticalWorkerToolsWithDifferentCapacity()
      throws InterruptedException {
    int existingPoolSize = 2;
    int stepPoolSize = 4;

    ExecutionContext context = createExecutionContextWith(
        ImmutableMap.of("jobArgs", WorkerJobResult.of(0, Optional.of(""), Optional.of(""))),
        Optional.of(existingPoolSize));

    FakeBuckEventListener listener = new FakeBuckEventListener();
    context.getBuckEventBus().register(listener);

    WorkerJobParams params = createJobParams(
        ImmutableList.of(startupCommand),
        startupArgs,
        ImmutableMap.<String, String>of(),
        "jobArgs",
        Optional.of(stepPoolSize));

    WorkerShellStep step = createWorkerShellStep(params, null, null);
    step.execute(context);

    BuckEvent firstEvent = listener.getEvents().get(0);
    assertThat(firstEvent, Matchers.instanceOf(ConsoleEvent.class));

    ConsoleEvent consoleEvent = (ConsoleEvent) firstEvent;
    assertThat(consoleEvent.getLevel(), Matchers.is(Level.WARNING));
    assertThat(consoleEvent.getMessage(), Matchers.is(String.format(
        "There are two 'worker_tool' targets declared with the same command (%s), but different " +
            "'max_worker' settings (%d and %d). Only the first capacity is applied. Consolidate " +
            "these workers to avoid this warning.",
        fakeWorkerStartupCommand,
        existingPoolSize,
        stepPoolSize
    )));

  }

  private static class ConcurrentExecution extends Thread {
    private final WorkerShellStep step;
    private final ExecutionContext context;

    ConcurrentExecution(WorkerShellStep step, ExecutionContext context) {
      this.step = step;
      this.context = context;
    }

    @Override
    public void run() {
      try {
        step.execute(context);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
  }

}
