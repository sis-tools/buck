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

package com.facebook.buck.rules;

import static org.junit.Assert.assertThat;

import com.facebook.buck.log.LogFormatter;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;

import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DiffRuleKeysScriptIntegrationTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();
  private Logger ruleKeyBuilderLogger;
  private Level previousRuleKeyBuilderLevel;
  private int lastPositionInLog;

  @Before
  public void enableVerboseRuleKeys() throws Exception {
    lastPositionInLog = 0;
    ruleKeyBuilderLogger = Logger.getLogger(RuleKeyBuilder.class.getName());
    previousRuleKeyBuilderLevel = ruleKeyBuilderLogger.getLevel();
    ruleKeyBuilderLogger.setLevel(Level.FINER);
    Path fullLogFilePath = tmp.getRoot().resolve(getLogFilePath());
    Files.createDirectories(fullLogFilePath.getParent());
    FileHandler handler = new FileHandler(fullLogFilePath.toString());
    handler.setFormatter(new LogFormatter());
    ruleKeyBuilderLogger.addHandler(handler);
  }

  @After
  public void disableVerboseRuleKeys() {
    ruleKeyBuilderLogger.setLevel(previousRuleKeyBuilderLevel);
  }

  @Test
  public void fileContentsChanged() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "diff_rulekeys_script", tmp);
    workspace.setUp();

    invokeBuckCommand(workspace, "buck-0.log");
    workspace.writeContentsToPath(
        "public class JavaLib1 { /* change */ }",
        "JavaLib1.java");
    invokeBuckCommand(workspace, "buck-1.log");

    String expectedResult = Joiner.on('\n').join(
        "Change details for [//:java_lib_1]",
        "  (srcs):",
        "    -[path(JavaLib1.java:e3506ff7c11f638458d08120d54f186dc79ddada)]",
        "    +[path(JavaLib1.java:7d82c86f964af479abefa21da1f19b1030649314)]",
        "");
    assertThat(
        runRuleKeyDiffer(workspace).getStdout(),
        Matchers.equalTo(Optional.of(expectedResult)));
  }

  @Test
  public void pathAdded() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "diff_rulekeys_script", tmp);
    workspace.setUp();

    invokeBuckCommand(workspace, "buck-0.log");
    workspace.writeContentsToPath(
        "public class JavaLib3 { /* change */ }",
        "JavaLib3.java");
    invokeBuckCommand(workspace, "buck-1.log");

    String expectedResult = Joiner.on('\n').join(
        "Change details for [//:java_lib_2]",
        "  (srcs):",
        "    -[<missing>]",
        "    +[path(JavaLib3.java:3396c5e71e9fad8e8f177af9d842f1b9b67bfb46)]",
        "");
    assertThat(
        runRuleKeyDiffer(workspace).getStdout(),
        Matchers.equalTo(Optional.of(expectedResult)));
  }

  @Test
  public void javacOptionsChanged() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "diff_rulekeys_script", tmp);
    workspace.setUp();

    writeBuckConfig(workspace, "6");
    invokeBuckCommand(workspace, "buck-0.log");
    writeBuckConfig(workspace, "7");
    invokeBuckCommand(workspace, "buck-1.log");

    String expectedResult = Joiner.on('\n').join(
        "Change details for " +
            "[//:java_lib_2->compileStepFactory.appendableSubKey->javacOptions.appendableSubKey]",
        "  (sourceLevel):",
        "    -[string(\"6\")]",
        "    +[string(\"7\")]",
        "  (targetLevel):",
        "    -[string(\"6\")]",
        "    +[string(\"7\")]",
        "");
    assertThat(
        runRuleKeyDiffer(workspace).getStdout(),
        Matchers.equalTo(Optional.of(expectedResult)));
  }

  @Test
  public void dependencyAdded() throws Exception {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "diff_rulekeys_script", tmp);
    workspace.setUp();

    invokeBuckCommand(workspace, "buck-0.log");
    workspace.writeContentsToPath(
        Joiner.on('\n').join(
            "java_library(",
            "  name = 'java_lib_1',",
            "  srcs = [ 'JavaLib1.java'],",
            ")",
            "java_library(",
            "  name = 'java_lib_3',",
            "  srcs = ['JavaLib1.java'],",
            "  deps = [':java_lib_1']",
            ")",
            "java_library(",
            "  name = 'java_lib_2',",
            "  srcs = ['JavaLib2.java'],",
            "  deps = [':java_lib_1', ':java_lib_3']",
            ")"),
        "BUCK");
    invokeBuckCommand(workspace, "buck-1.log");

    assertThat(
        runRuleKeyDiffer(workspace).getStdout().get(),
        Matchers.stringContainsInOrder(
            "Change details for [//:java_lib_2]",
            "  (abiClasspath):",
            "    -[<missing>]",
            "    +[\"//:java_lib_1#abi\"@ruleKey(sha1=", /* some rulekey */ ")]",
            "  (buck.declaredDeps):",
            "    -[<missing>]",
            "    +[\"//:java_lib_3\"@ruleKey(sha1=", /* some rulekey */ ")]",
            "  (buck.extraDeps):",
            "    -[<missing>]",
            "    +[\"//:java_lib_1#abi\"@ruleKey(sha1=", /* some rulekey */ ")]",
            "Change details for [//:java_lib_2->abiClasspath]",
            "  (binaryJar):",
            "    -[\"//:java_lib_1\"@ruleKey(sha1=", /* some rulekey */ ")]",
            "    +[\"//:java_lib_3\"@ruleKey(sha1=", /* some rulekey */ ")]",
            "  (buck.declaredDeps):",
            "    -[\"//:java_lib_1\"@ruleKey(sha1=", /* some rulekey */ ")]",
            "    +[\"//:java_lib_3\"@ruleKey(sha1=", /* some rulekey */ ")]",
            "  (name):",
            "    -[string(\"//:java_lib_1#abi\")]",
            "    +[string(\"//:java_lib_3#abi\")]"));
  }

  private void writeBuckConfig(
      ProjectWorkspace projectWorkspace,
      String javaVersion) throws Exception {
    projectWorkspace.writeContentsToPath(
        Joiner.on('\n').join(
            "[java]",
            "source_level = " + javaVersion,
            "target_level = " + javaVersion),
        ".buckconfig");

  }

  private ProcessExecutor.Result runRuleKeyDiffer(
      ProjectWorkspace workspace) throws IOException, InterruptedException {
    String cmd = Platform.detect() == Platform.WINDOWS ? "python" : "python2.7";
    ProcessExecutor.Result result = workspace.runCommand(
        cmd,
        Paths.get("scripts", "diff_rulekeys.py").toString(),
        tmp.getRoot().resolve("buck-0.log").toString(),
        tmp.getRoot().resolve("buck-1.log").toString(),
        "//:java_lib_2");
    assertThat(result.getStderr(), Matchers.equalTo(Optional.of("")));
    assertThat(result.getExitCode(), Matchers.is(0));
    return result;
  }

  private void invokeBuckCommand(ProjectWorkspace workspace, String logOut) throws IOException {
    ProjectWorkspace.ProcessResult buckCommandResult = workspace.runBuckCommand(
        "targets",
        "--show-rulekey",
        "//:java_lib_2");
    buckCommandResult.assertSuccess();
    String fullLogContents = workspace.getFileContents(getLogFilePath());
    String logContentsForThisInvocation = fullLogContents.substring(lastPositionInLog);
    lastPositionInLog += logContentsForThisInvocation.length();
    workspace.writeContentsToPath(logContentsForThisInvocation, logOut);
  }

  private Path getLogFilePath() {
    return tmp.getRoot().resolve("buck.test.log");
  }
}
