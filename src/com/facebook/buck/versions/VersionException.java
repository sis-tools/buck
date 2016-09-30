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

import com.facebook.buck.model.BuildTarget;

/**
 * Error thrown when version selection fails.
 */
public class VersionException extends Exception {

  /**
   * The root of the version sub-graph that failed.
   */
  private final BuildTarget root;

  public VersionException(BuildTarget root, String message) {
    super(String.format("%s: %s", root, message));
    this.root = root;
  }

  public BuildTarget getRoot() {
    return root;
  }

}
