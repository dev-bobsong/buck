/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class PrebuiltNativeLibraryDescription
    implements Description<PrebuiltNativeLibraryDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("prebuilt_native_library");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> PrebuiltNativeLibrary createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    ImmutableSortedSet<SourcePath> librarySources;
    try {
      librarySources =
          FluentIterable.from(params.getProjectFilesystem().getFilesUnderPath(args.nativeLibs))
          .transform(SourcePaths.toSourcePath(params.getProjectFilesystem()))
          .toSortedSet(Ordering.natural());
    } catch (IOException e) {
      throw new HumanReadableException(e, "Error traversing directory %s.", args.nativeLibs);
    }

    return new PrebuiltNativeLibrary(
        params,
        new SourcePathResolver(resolver),
        args.nativeLibs,
        args.isAsset.orElse(false),
        librarySources
    );
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public Optional<Boolean> isAsset;
    public Path nativeLibs;

    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
  }
}
