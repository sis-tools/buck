/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.intellij;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.android.AssumeAndroidPlatform;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.ProjectWorkspace.ProcessResult;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

public class ProjectIntegrationTest {

  @Rule
  public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void testBuckProject() throws IOException {
    ProcessResult result = runBuckProjectAndVerify(
        "project1",
        "--deprecated-ij-generation",
        "-v",
        "5");

    assertEquals(
        "`buck project` should report the files it modified.",
        Joiner.on('\n').join(
          "MODIFIED FILES:",
          ".idea/compiler.xml",
          ".idea/libraries/buck_out_gen_libs___generated___generated_jar.xml",
          ".idea/libraries/buck_out_gen_libs___guava___guava_jar.xml",
          ".idea/libraries/buck_out_gen_libs___jsr305___jsr305_jar.xml",
          ".idea/libraries/buck_out_gen_libs___junit___junit_jar.xml",
          ".idea/misc.xml",
          ".idea/modules.xml",
          ".idea/runConfigurations/Debug_Buck_test.xml",
          "modules/dep1/module_modules_dep1.iml",
          "modules/tip/module_modules_tip.iml",
          "root.iml"
        ) + '\n',
        result.getStdout());

    assertThat(
        "`buck project` should contain warning to synchronize IntelliJ.",
        result.getStderr(),
        containsString(
            "  ::  Please resynchronize IntelliJ via File->Synchronize " +
                "or Cmd-Opt-Y (Mac) or Ctrl-Alt-Y (PC/Linux)"));
  }

  @Test
  public void testBuckProjectWithGenruleAsASrc() throws IOException {
    runBuckProjectAndVerify(
        "project_with_genrule_as_a_src",
        "--deprecated-ij-generation",
        "-v",
        "5");
  }

  @Test
  public void testBuckProjectDoesNotCauseUnnecessaryWrites() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "project_with_root_iml_already_present", temporaryFolder);
    workspace.setUp();

    FileTime lastModified = FileTime.fromMillis(0); // We're gonna party like it's 1970!
    Path path = temporaryFolder.getRoot().resolve("root.iml");
    Files.setLastModifiedTime(path, lastModified);
    assertEquals(lastModified, Files.getLastModifiedTime(path));

    ProcessResult result = workspace.runBuckCommand(
        "project",
        "--deprecated-ij-generation");
    result.assertSuccess("buck project should exit cleanly");

    assertEquals(lastModified, Files.getLastModifiedTime(path));
    workspace.verify();
  }

  @Test
  public void testBuckProjectDryRun() throws IOException {
    ProcessResult result = runBuckProjectWithoutVerification(
        "project1",
        "--deprecated-ij-generation",
        "--dry-run",
        "-v",
        "5");

    ImmutableSortedSet<String> expectedResult = ImmutableSortedSet.of(
        "//:project_config",
        "//:root_module",
        "//libs:generated",
        "//libs:generated_jar",
        "//libs:generated_source_jar",
        "//libs:guava",
        "//libs:jsr305",
        "//libs:junit",
        "//modules/dep1:dep1",
        "//modules/dep1:project_config",
        "//modules/dep1:test",
        "//modules/tip:project_config",
        "//modules/tip:test",
        "//modules/tip:tip");

    ImmutableSortedSet<String> actualResult = ImmutableSortedSet.copyOf(
        Splitter.on('\n').omitEmptyStrings().split(result.getStdout()));

    assertEquals(
        "`buck project --dry-run` should print the list of targets that would be included.",
        expectedResult,
        actualResult);
  }

  @Test
  public void testBuckProjectExcludesSubdirectories() throws IOException {
    runBuckProjectAndVerify("project2", "--deprecated-ij-generation");
  }

  /**
   * Verify that if we build a project by specifying a target, the resulting project only contains
   * the transitive deps of that target.  In this example, that means everything except
   * //modules/tip and //tests/tests.
   */
  @Test
  public void testBuckProjectSlice() throws IOException {
    ProcessResult result = runBuckProjectAndVerify(
        "project_slice",
        "--deprecated-ij-generation",
        "--without-tests",
        "//modules/dep1:dep1",
        "//:root",
        "-v", "5");

    assertEquals(
        "`buck project` should report the files it modified.",
        Joiner.on('\n').join(
            "MODIFIED FILES:",
            ".idea/compiler.xml",
            ".idea/libraries/buck_out_gen_libs___guava___guava_jar.xml",
            ".idea/libraries/buck_out_gen_libs___jsr305___jsr305_jar.xml",
            ".idea/libraries/buck_out_gen_libs___junit___junit_jar.xml",
            ".idea/misc.xml",
            ".idea/modules.xml",
            ".idea/runConfigurations/Debug_Buck_test.xml",
            "module_.iml",
            "modules/dep1/module_modules_dep1.iml"
        ) + '\n',
        result.getStdout());

    assertThat(
        "`buck project` should contain warning to synchronize IntelliJ.",
        result.getStderr(),
        containsString(
            "  ::  Please resynchronize IntelliJ via File->Synchronize " +
                "or Cmd-Opt-Y (Mac) or Ctrl-Alt-Y (PC/Linux)"));
  }

  @Test
  public void testBuckProjectSliceDryRun() throws IOException {
    ProcessResult result = runBuckProjectWithoutVerification(
        "project_slice",
        "--deprecated-ij-generation",
        "--dry-run",
        "--without-tests",
        "//modules/dep1:dep1",
        "//:root",
        "-v", "5");

    ImmutableSortedSet<String> expectedResult = ImmutableSortedSet.of(
        "//:project_config",
        "//:root",
        "//libs:guava",
        "//libs:jsr305",
        "//libs:junit",
        "//modules/dep1:dep1",
        "//modules/dep1:project_config",
        "//modules/dep1:test");

    ImmutableSortedSet<String> actualResult = ImmutableSortedSet.copyOf(
        Splitter.on('\n').omitEmptyStrings().split(result.getStdout()));

    assertEquals(
        "`buck project --dry-run` should print the list of targets that would be included.",
        expectedResult,
        actualResult);
  }

  /**
   * Verify we can build a project by specifying a target, even if it depends on a target whose
   * project is not in the same buck file as the targets it's for.
   */
  @Test
  public void testBuckProjectSliceWithProjectInDifferentBuckFile() throws IOException {
    ProcessResult result = runBuckProjectAndVerify(
        "project_slice_with_project_in_different_buck_file",
        "--deprecated-ij-generation",
        "//:root",
        "-v",
        "5");

    assertEquals(
        "`buck project` should report the files it modified.",
        Joiner.on('\n').join(
            "MODIFIED FILES:",
            ".idea/compiler.xml",
            ".idea/misc.xml",
            ".idea/modules.xml",
            ".idea/runConfigurations/Debug_Buck_test.xml",
            "module_.iml",
            "modules/module_modules_dep1.iml"
        ) + '\n',
        result.getStdout());

    assertThat(
        "`buck project` should contain warning to synchronize IntelliJ.",
        result.getStderr(),
        containsString(
            "  ::  Please resynchronize IntelliJ via File->Synchronize " +
                "or Cmd-Opt-Y (Mac) or Ctrl-Alt-Y (PC/Linux)"));
  }

  /**
   * Verify that if we build a project by specifying a target, the resulting project only contains
   * the transitive deps of that target as well as any tests that specify something in those
   * transitive deps as "sources_under_test".  In this example, that means everything except
   * //modules/tip.
   */
  @Test
  public void testBuckProjectSliceWithTests() throws IOException {
    ProcessResult result = runBuckProjectAndVerify(
        "project_slice_with_tests",
        "--deprecated-ij-generation",
        "//modules/dep1:dep1",
        "-v", "5");

    assertEquals(
        "`buck project` should report the files it modified.",
        Joiner.on('\n').join(
            "MODIFIED FILES:",
            ".idea/compiler.xml",
            ".idea/libraries/buck_out_gen_libs___guava___guava_jar.xml",
            ".idea/libraries/buck_out_gen_libs___jsr305___jsr305_jar.xml",
            ".idea/libraries/buck_out_gen_libs___junit___junit_jar.xml",
            ".idea/misc.xml",
            ".idea/modules.xml",
            ".idea/runConfigurations/Debug_Buck_test.xml",
            "modules/dep1/module_modules_dep1.iml",
            "tests/module_tests.iml"
        ) + '\n',
        result.getStdout());

    assertThat(
        "`buck project` should contain warning to synchronize IntelliJ.",
        result.getStderr(),
        containsString(
            "  ::  Please resynchronize IntelliJ via File->Synchronize " +
                "or Cmd-Opt-Y (Mac) or Ctrl-Alt-Y (PC/Linux)"));
  }

  @Test
  public void testBuckProjectSliceWithTestsDryRunShowsNoTests() throws IOException {
    ProcessResult result = runBuckProjectWithoutVerification(
        "project_slice_with_tests",
        "--deprecated-ij-generation",
        "--dry-run",
        "--without-tests",
        "//modules/dep1:dep1",
        "-v", "5");

    ImmutableSortedSet<String> expectedResult = ImmutableSortedSet.of(
        "//libs:guava",
        "//libs:jsr305",
        "//libs:junit",
        "//modules/dep1:dep1",
        "//modules/dep1:project_config",
        "//modules/dep1:test");

    ImmutableSortedSet<String> actualResult = ImmutableSortedSet.copyOf(
        Splitter.on('\n').omitEmptyStrings().split(result.getStdout()));

    assertEquals(
        "`buck project --dry-run` should print the list of targets that would be included.",
        expectedResult,
        actualResult);
  }

  /**
   * Verify that if we build a project by specifying a target, the tests dependencies are
   * referenced even if they are defined in a buck file that would not have been parsed otherwise.
   */
  @Test
  public void testBuckProjectSliceWithTestsDependenciesInDifferentBuckFile() throws IOException {
    ProcessResult result = runBuckProjectAndVerify(
        "project_slice_with_tests_dependencies_in_different_buck_file",
        "--deprecated-ij-generation",
        "//modules/dep1:dep1",
        "-v", "5");

    assertEquals(
        "`buck project` should report the files it modified.",
        Joiner.on('\n').join(
            "MODIFIED FILES:",
            ".idea/compiler.xml",
            ".idea/misc.xml",
            ".idea/modules.xml",
            ".idea/runConfigurations/Debug_Buck_test.xml",
            "modules/dep1/module_modules_dep1.iml",
            "modules/dep2/module_modules_dep2.iml",
            "tests/module_tests.iml"
        ) + '\n',
        result.getStdout());

    assertThat(
        "`buck project` should contain warning to synchronize IntelliJ.",
        result.getStderr(),
        containsString(
            "  ::  Please resynchronize IntelliJ via File->Synchronize " +
                "or Cmd-Opt-Y (Mac) or Ctrl-Alt-Y (PC/Linux)"));
  }

  /**
   * Verify that if we build a project by specifying a target, the tests' projects rules are
   * referenced even if they are defined in a different buck file from the tests.
   */
  @Test
  public void testBuckProjectSliceWithTestsProjectInDifferentBuckFile() throws IOException {
    ProcessResult result = runBuckProjectAndVerify(
        "project_slice_with_tests_project_in_different_buck_file",
        "--deprecated-ij-generation",
        "//modules/dep1:dep1",
        "-v", "5");

    assertEquals(
        "`buck project` should report the files it modified.",
        Joiner.on('\n').join(
            "MODIFIED FILES:",
            ".idea/compiler.xml",
            ".idea/misc.xml",
            ".idea/modules.xml",
            ".idea/runConfigurations/Debug_Buck_test.xml",
            "modules/dep1/module_modules_dep1.iml",
            "tests/module_tests_test1.iml"
        ) + '\n',
        result.getStdout());

    assertThat(
        "`buck project` should contain warning to synchronize IntelliJ.",
        result.getStderr(),
        containsString(
            "  ::  Please resynchronize IntelliJ via File->Synchronize " +
                "or Cmd-Opt-Y (Mac) or Ctrl-Alt-Y (PC/Linux)"));
  }

  /**
   * Tests the case where a build file has a test rule that depends on a library rule in the same
   * build file, and the test rule is specified as the {@code test_target} in its
   * {@code project_config()}. When this happens, all libraries in the generated {@code .iml} file
   * should be listed before any of the modules.
   * <p>
   * This prevents a regression where JUnit was not being loaded early enough in the classpath,
   * which led to a "JUnit version 3.8 or later expected" error when running tests in IntelliJ.
   * (Presumably, IntelliJ ended up loading JUnit 3 from android.jar instead of loading JUnit 4
   * from the version of JUnit checked into version control.)
   */
  @Test
  public void testBuckProjectWithMultipleLibrariesInOneBuildFile() throws IOException {
    runBuckProjectAndVerify(
        "buck_project_multiple_libraries_in_one_build_file",
        "--deprecated-ij-generation");
  }

  @Test
  public void testProjectWithColon() throws IOException {
    runBuckProjectWithoutVerification(
        "project1",
        "--deprecated-ij-generation",
        "//modules/dep1:");
  }

  @Test
  public void testNonexistentTarget() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "project1",
        temporaryFolder);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "project",
        "--deprecated-ij-generation",
        "//modules/dep1:nonexistent-target");
    result.assertFailure("No rule found when resolving target " +
        "//modules/dep1:nonexistent-target in build file //modules/dep1/BUCK");
  }

  @Test
  public void testNonexistentBuckFile() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "project1",
        temporaryFolder);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        "project",
        "--deprecated-ij-generation",
        "//nonexistent/path:target");
    result.assertFailure("No build file at nonexistent/path/BUCK " +
        "when resolving target //nonexistent/path:target.");
  }

  @Test
  public void testBuckProjectGeneratedWithRDotFiles001() throws IOException {
    runBuckProjectAndVerify(
        "project_r_001",
        "--deprecated-ij-generation",
        "app");
  }

  @Test
  public void testBuckProjectGeneratedWithRDotFiles002() throws IOException {
    runBuckProjectAndVerify(
        "project_r_002",
        "--deprecated-ij-generation",
        "--disable-r-java-idea-generator",
        "app");
  }

  @Test
  public void testBuckProjectWithAndroidBinary() throws IOException {
    ProcessResult result = runBuckProjectAndVerify(
        "project_with_android_binary",
        "--deprecated-ij-generation",
        "-v",
        "5");

    assertEquals(
        "`buck project` should report the files it modified.",
        Joiner.on('\n').join(
            "MODIFIED FILES:",
            ".idea/compiler.xml",
            ".idea/misc.xml",
            ".idea/modules.xml",
            ".idea/runConfigurations/Debug_Buck_test.xml",
            "apps/sample/module_apps_sample.iml",
            "java/com/sample/lib/module_java_com_sample_lib.iml",
            "res/com/sample/asset_only/module_res_com_sample_asset_only.iml",
            "res/com/sample/base/module_res_com_sample_base.iml",
            "res/com/sample/title/module_res_com_sample_title.iml",
            "res/com/sample/top/module_res_com_sample_top.iml"
        ) + '\n',
        result.getStdout());
  }

  @Test
  public void testBuckProjectSliceWithAndroidBinary() throws IOException {
    ProcessResult result = runBuckProjectAndVerify(
        "project_with_android_binary",
        "--deprecated-ij-generation",
        "-v", "5",
        "//apps/sample:app");

    assertEquals(
        "`buck project` should report the files it modified.",
        Joiner.on('\n').join(
            "MODIFIED FILES:",
            ".idea/compiler.xml",
            ".idea/misc.xml",
            ".idea/modules.xml",
            ".idea/runConfigurations/Debug_Buck_test.xml",
            "apps/sample/module_apps_sample.iml",
            "java/com/sample/lib/module_java_com_sample_lib.iml",
            "res/com/sample/asset_only/module_res_com_sample_asset_only.iml",
            "res/com/sample/base/module_res_com_sample_base.iml",
            "res/com/sample/title/module_res_com_sample_title.iml",
            "res/com/sample/top/module_res_com_sample_top.iml"
        ) + '\n',
        result.getStdout());
  }

  @Test
  public void testBuckProjectWithAndroidBinaryWithRDotJavaAutogenerationDisabled()
      throws IOException {
    ProcessResult result = runBuckProjectAndVerify(
        "project_with_android_binary_autogeneration_disabled",
        "--deprecated-ij-generation",
        "--disable-r-java-idea-generator",
        "-v", "5");

    assertEquals(
        "`buck project` should report the files it modified.",
        Joiner.on('\n').join(
            "MODIFIED FILES:",
            ".idea/compiler.xml",
            ".idea/misc.xml",
            ".idea/modules.xml",
            ".idea/runConfigurations/Debug_Buck_test.xml",
            "apps/sample/module_apps_sample.iml",
            "java/com/sample/lib/module_java_com_sample_lib.iml",
            "res/com/sample/asset_only/module_res_com_sample_asset_only.iml",
            "res/com/sample/base/module_res_com_sample_base.iml",
            "res/com/sample/title/module_res_com_sample_title.iml",
            "res/com/sample/top/module_res_com_sample_top.iml"
        ) + '\n',
        result.getStdout());
  }

  @Test
  public void testBuckProjectSliceWithAndroidBinaryWithRDotJavaAutogenerationDisabled()
      throws IOException {
    ProcessResult result = runBuckProjectAndVerify(
        "project_with_android_binary_autogeneration_disabled",
        "--deprecated-ij-generation",
        "--disable-r-java-idea-generator",
        "//apps/sample:app",
        "-v", "5");

    assertEquals(
        "`buck project` should report the files it modified.",
        Joiner.on('\n').join(
            "MODIFIED FILES:",
            ".idea/compiler.xml",
            ".idea/misc.xml",
            ".idea/modules.xml",
            ".idea/runConfigurations/Debug_Buck_test.xml",
            "apps/sample/module_apps_sample.iml",
            "java/com/sample/lib/module_java_com_sample_lib.iml",
            "res/com/sample/asset_only/module_res_com_sample_asset_only.iml",
            "res/com/sample/base/module_res_com_sample_base.iml",
            "res/com/sample/title/module_res_com_sample_title.iml",
            "res/com/sample/top/module_res_com_sample_top.iml"
        ) + '\n',
        result.getStdout());
  }

  @Test
  public void testAndroidProjectGeneratedWithGradleConventions() throws IOException {
    runBuckProjectAndVerify(
        "android_project_with_gradle_conventions",
        "--deprecated-ij-generation",
        "app");
  }

  @Test
  public void testVersion2BuckProject() throws IOException {
    runBuckProjectAndVerify("experimental_project1");
  }

  @Test
  public void testVersion2BuckProjectWithoutAutogeneratingSources() throws IOException {
    runBuckProjectAndVerify("experimental_project_without_autogeneration");
  }

  @Test
  public void testVersion2BuckProjectSlice() throws IOException {
    runBuckProjectAndVerify(
        "experimental_project_slice",
        "--without-tests",
        "modules/dep1:dep1");
  }

  @Test
  public void testVersion2BuckProjectSourceMerging() throws IOException {
    runBuckProjectAndVerify("experimental_project_source_merge", "//java/code/modules/tip");
  }

  @Test
  public void testVersion2BuckProjectCustomSdks() throws IOException {
    runBuckProjectAndVerify("experimental_project_with_custom_sdks");
  }

  @Test
  public void testVersion2BuckProjectWithProjectSettings() throws IOException {
    runBuckProjectAndVerify("experimental_project_with_project_settings");
  }

  private ProcessResult runBuckProjectAndVerify(
      String folderWithTestData,
      String... commandArgs) throws IOException {
    return runBuckProject(folderWithTestData, true, commandArgs);
  }

  private ProcessResult runBuckProjectWithoutVerification(
      String folderWithTestData,
      String... commandArgs) throws IOException {
    return runBuckProject(folderWithTestData, false, commandArgs);
  }

  private ProcessResult runBuckProject(
      String folderWithTestData,
      boolean verifyWorkspace,
      String... commandArgs) throws IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();

    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, folderWithTestData, temporaryFolder);
    workspace.setUp();

    ProcessResult result = workspace.runBuckCommand(
        Lists.asList("project", commandArgs).toArray(new String[commandArgs.length + 1]));
    result.assertSuccess("buck project should exit cleanly");

    if (verifyWorkspace) {
      workspace.verify();
    }

    return result;
  }
}
