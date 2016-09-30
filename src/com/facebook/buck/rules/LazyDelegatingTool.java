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

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

public class LazyDelegatingTool implements Tool {
  private Supplier<Tool> delegate;

  public LazyDelegatingTool(Supplier<Tool> delegate) {
    this.delegate = Suppliers.memoize(delegate);
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    delegate.get().appendToRuleKey(sink);
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathResolver resolver) {
    return delegate.get().getDeps(resolver);
  }

  @Override
  public ImmutableCollection<SourcePath> getInputs() {
    return delegate.get().getInputs();
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    return delegate.get().getCommandPrefix(resolver);
  }

  @Override
  public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
    return delegate.get().getEnvironment(resolver);
  }
}
