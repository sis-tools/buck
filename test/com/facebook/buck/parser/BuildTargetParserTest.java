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

package com.facebook.buck.parser;

import static com.facebook.buck.rules.TestCellBuilder.createCellRoots;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.FakeCellPathResolver;
import com.google.common.collect.ImmutableMap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.nio.file.Path;
import java.nio.file.Paths;

public class BuildTargetParserTest {

  private final BuildTargetParser parser = BuildTargetParser.INSTANCE;
  private BuildTargetPatternParser<BuildTargetPattern> fullyQualifiedParser;


  @Before
  public void setUpFullyQualifiedBuildTargetPatternParser() {
    fullyQualifiedParser = BuildTargetPatternParser.fullyQualified();
  }

  @Rule
  public ExpectedException exception = ExpectedException.none();

  @Test
  public void testParseRootRule() {
    // Parse "//:fb4a" with the BuildTargetParser and test all of its observers.
    BuildTarget buildTarget =
        parser.parse("//:fb4a", fullyQualifiedParser, createCellRoots(null));
    assertEquals("fb4a", buildTarget.getShortNameAndFlavorPostfix());
    assertEquals("//", buildTarget.getBaseName());
    assertEquals(Paths.get(""), buildTarget.getBasePath());
    assertEquals("", buildTarget.getBasePathWithSlash());
    assertEquals("//:fb4a", buildTarget.getFullyQualifiedName());
  }

  @Test
  public void testParseRuleWithFlavors() {
    BuildTarget buildTarget =
        parser.parse("//:lib#foo,bar", fullyQualifiedParser, createCellRoots(null));
    // Note the sort order.
    assertEquals("lib#bar,foo", buildTarget.getShortNameAndFlavorPostfix());
    assertEquals("//", buildTarget.getBaseName());
    assertEquals(Paths.get(""), buildTarget.getBasePath());
    assertEquals("", buildTarget.getBasePathWithSlash());
    // Note the sort order.
    assertEquals("//:lib#bar,foo", buildTarget.getFullyQualifiedName());
    assertThat(
        buildTarget.getFlavors(),
        hasItems((Flavor) ImmutableFlavor.of("foo"), ImmutableFlavor.of("bar")));
  }

  @Test
  public void testParseValidTargetWithDots() {
    BuildTarget buildTarget =
        parser.parse("//..a/b../a...b:assets", fullyQualifiedParser, createCellRoots(null));
    assertEquals("assets", buildTarget.getShortNameAndFlavorPostfix());
    assertEquals("//..a/b../a...b", buildTarget.getBaseName());
    assertEquals(Paths.get("..a", "b..", "a...b"), buildTarget.getBasePath());
    assertEquals("..a/b../a...b/", buildTarget.getBasePathWithSlash());
    assertEquals("//..a/b../a...b:assets", buildTarget.getFullyQualifiedName());
  }

  @Test
  public void testParsePathWithDot() {
    exception.expect(BuildTargetParseException.class);
    exception.expectMessage("Build target path cannot be absolute or contain . or .. " +
            "(found //.:assets)");
    parser.parse("//.:assets", fullyQualifiedParser, createCellRoots(null));
  }

  @Test
  public void testParsePathWithDotDot() {
    exception.expect(BuildTargetParseException.class);
    exception.expectMessage("Build target path cannot be absolute or contain . or .. " +
            "(found //../facebookorca:assets)");
    parser.parse("//../facebookorca:assets", fullyQualifiedParser, createCellRoots(null));
  }

  @Test
  public void testParseAbsolutePath() {
    exception.expect(BuildTargetParseException.class);
    exception.expectMessage("Build target path cannot be absolute or contain . or .. " +
            "(found ///facebookorca:assets)");
    parser.parse("///facebookorca:assets", fullyQualifiedParser, createCellRoots(null));
  }

  @Test
  public void testParseTrailingColon() {
    try {
      parser.parse("//facebook/orca:assets:", fullyQualifiedParser, createCellRoots(null));
      fail("parse() should throw an exception");
    } catch (BuildTargetParseException e) {
      assertEquals("//facebook/orca:assets: cannot end with a colon", e.getMessage());
    }
  }

  @Test
  public void testParseNoColon() {
    try {
      parser.parse("//facebook/orca/assets", fullyQualifiedParser, createCellRoots(null));
      fail("parse() should throw an exception");
    } catch (BuildTargetParseException e) {
      assertEquals("//facebook/orca/assets must contain exactly one colon (found 0)",
          e.getMessage());
    }
  }

  @Test
  public void testParseMultipleColons() {
    try {
      parser.parse("//facebook:orca:assets", fullyQualifiedParser, createCellRoots(null));
      fail("parse() should throw an exception");
    } catch (BuildTargetParseException e) {
      assertEquals("//facebook:orca:assets must contain exactly one colon (found 2)",
          e.getMessage());
    }
  }

  @Test
  public void testParseFullyQualified() {
    BuildTarget buildTarget =
        parser.parse("//facebook/orca:assets", fullyQualifiedParser, createCellRoots(null));
    assertEquals("//facebook/orca", buildTarget.getBaseName());
    assertEquals("assets", buildTarget.getShortNameAndFlavorPostfix());
  }

  @Test
  public void testParseBuildFile() {
    BuildTarget buildTarget = parser.parse(
        ":assets",
        BuildTargetPatternParser.forBaseName("//facebook/orca"),
        createCellRoots(null));
    assertEquals("//facebook/orca", buildTarget.getBaseName());
    assertEquals("assets", buildTarget.getShortNameAndFlavorPostfix());
  }

  @Test
  public void testParseWithVisibilityContext() {
    // Invoke the BuildTargetParser using the VISIBILITY context.
    BuildTargetPatternParser<BuildTargetPattern> buildTargetPatternParser =
        BuildTargetPatternParser.forVisibilityArgument();
    BuildTarget target =
        parser.parse("//java/com/example:", buildTargetPatternParser, createCellRoots(null));
    assertEquals(
        "A build target that ends with a colon should be treated as a wildcard build target " +
        "when parsed in the context of a visibility argument.",
        "//java/com/example:",
        target.getFullyQualifiedName());
  }

  @Test
  public void testParseWithRepoName() {
    final Path localRepoRoot = Paths.get("/opt/local/repo");
    CellPathResolver cellRoots = new FakeCellPathResolver(
        ImmutableMap.of("localreponame", localRepoRoot));
    String targetStr = "localreponame//foo/bar:baz";

    BuildTarget buildTarget = parser.parse(targetStr, fullyQualifiedParser, cellRoots);
    assertEquals("localreponame//foo/bar:baz", buildTarget.getFullyQualifiedName());
    assertTrue(buildTarget.getCell().isPresent());
    assertEquals(localRepoRoot, buildTarget.getCellPath());
  }

  @Test(expected = BuildTargetParseException.class)
  public void testParseFailsWithRepoNameAndRelativeTarget() throws NoSuchBuildTargetException {

    String invalidTargetStr = "myRepo:baz";
    parser.parse(invalidTargetStr, fullyQualifiedParser, createCellRoots(null));
  }

  @Test
  public void testParseWithBackslash() {
    String backslashStr = "//com\\microsoft\\windows:something";
    BuildTarget buildTarget =
        parser.parse(backslashStr, fullyQualifiedParser, createCellRoots(null));
    assertEquals("//com/microsoft/windows", buildTarget.getBaseName());
  }

}
