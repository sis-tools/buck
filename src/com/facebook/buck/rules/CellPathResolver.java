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
package com.facebook.buck.rules;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;

public interface CellPathResolver {
  /**
   * @param cellName name of cell, Optional.absent() for root cell.
   * @return Path to the physical location of the cell.
   */
  Path getCellPath(Optional<String> cellName);

  /**
   * @return paths to all cells this resolver knows about.
   */
  ImmutableMap<String, Path> getCellPaths();
}
