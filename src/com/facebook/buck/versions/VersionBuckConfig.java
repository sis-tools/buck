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
package com.facebook.buck.versions;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.List;

public class VersionBuckConfig implements VersionConfig {

  private static final String UNIVERSES_SECTION = "version_universes";

  private final BuckConfig delegate;

  public VersionBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  private VersionUniverse getVersionUniverse(String name) {
    VersionUniverse.Builder universe = VersionUniverse.builder();
    ImmutableList<String> vals = delegate.getListWithoutComments(UNIVERSES_SECTION, name);
    for (String val : vals) {
      List<String> parts = Splitter.on('=').limit(2).trimResults().splitToList(val);
      if (parts.size() != 2) {
        throw new HumanReadableException(
            "`%s:%s`: must specify version selections as a comma-separated list of " +
                "`//build:target=<version>` pairs: \"%s\"",
            UNIVERSES_SECTION,
            name,
            val);
      }
      universe.putVersions(
          delegate.getBuildTargetForFullyQualifiedTarget(parts.get(0)),
          Version.of(parts.get(1)));
    }
    return universe.build();
  }

  @Override
  public ImmutableMap<String, VersionUniverse> getVersionUniverses() {
    ImmutableMap.Builder<String, VersionUniverse> universes = ImmutableMap.builder();
    for (String name : delegate.getEntriesForSection(UNIVERSES_SECTION).keySet()) {
      universes.put(name, getVersionUniverse(name));
    }
    return universes.build();
  }

}
