// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.analysis.util;

import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.OutputGroupProvider;

/**
 * Common code for unit tests that validate --compile_only behavior.
 */
public abstract class CompileOnlyTestCase extends BuildViewTestCase {

  protected Artifact getArtifactByExecPathSuffix(ConfiguredTarget target, String path) {
    for (Artifact artifact : getOutputGroup(target, OutputGroupProvider.FILES_TO_COMPILE)) {
      if (artifact.getExecPathString().endsWith(path)) {
        return artifact;
      }
    }
    return null;
  }
}
