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

package com.facebook.buck.thrift;

import com.facebook.buck.cxx.CxxCompilables;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.io.Files;

import java.nio.file.Path;
import java.util.Optional;

public class ThriftCxxEnhancer implements ThriftLanguageSpecificEnhancer {

  private static final Flavor CPP_FLAVOR = ImmutableFlavor.of("cpp");
  private static final Flavor CPP2_FLAVOR = ImmutableFlavor.of("cpp2");

  private final ThriftBuckConfig thriftBuckConfig;
  private final CxxLibraryDescription cxxLibraryDescription;
  private final boolean cpp2;

  public ThriftCxxEnhancer(
      ThriftBuckConfig thriftBuckConfig,
      CxxLibraryDescription cxxLibraryDescription,
      boolean cpp2) {
    this.thriftBuckConfig = thriftBuckConfig;
    this.cxxLibraryDescription = cxxLibraryDescription;
    this.cpp2 = cpp2;
  }

  @Override
  public String getLanguage() {
    return cpp2 ? "cpp2" : "cpp";
  }

  @Override
  public Flavor getFlavor() {
    return cpp2 ? CPP2_FLAVOR : CPP_FLAVOR;
  }

  // Return the gen-dir relative sources for the given services and options.
  @VisibleForTesting
  protected ImmutableSortedSet<String> getGeneratedSources(
      String thriftName,
      ImmutableList<String> services,
      ImmutableSet<String> options) {

    final String base = Files.getNameWithoutExtension(thriftName);
    final boolean bootstrap = options.contains("bootstrap");
    final boolean layouts = options.contains("frozen2");
    final boolean templates = cpp2 || options.contains("templates");
    final boolean perfhash = !cpp2 && options.contains("perfhash");
    final boolean separateProcessmap = cpp2 && options.contains("separate_processmap");
    final boolean fatal = cpp2 && options.contains("fatal");

    ImmutableSortedSet.Builder<String> sources = ImmutableSortedSet.naturalOrder();

    sources.add(base + "_constants.h");
    sources.add(base + "_constants.cpp");

    sources.add(base + "_types.h");
    sources.add(base + "_types.cpp");

    if (templates) {
      sources.add(base + "_types.tcc");
    }

    if (layouts) {
      sources.add(base + "_layouts.h");
      sources.add(base + "_layouts.cpp");
    }

    if (!bootstrap && !cpp2) {
      sources.add(base + "_reflection.h");
      sources.add(base + "_reflection.cpp");
    }

    if (fatal) {
      final String[] suffixes = new String[] {
        "", "_enum", "_union", "_struct",
        "_constant", "_service",
        "_types", "_all"
      };

      for (String suffix : suffixes) {
        sources.add(base + "_fatal" + suffix + ".h");
        sources.add(base + "_fatal" + suffix + ".cpp");
      }
    }

    if (cpp2) {
      sources.add(base + "_types_custom_protocol.h");
    }

    for (String service : services) {

      sources.add(service + ".h");
      sources.add(service + ".cpp");

      if (cpp2) {
        sources.add(service + "_client.cpp");
        sources.add(service + "_custom_protocol.h");
      }

      if (separateProcessmap) {
        sources.add(service + "_processmap_binary.cpp");
        sources.add(service + "_processmap_compact.cpp");
      }

      if (templates) {
        sources.add(service + ".tcc");
      }

      if (perfhash) {
        sources.add(service + "_gperf.tcc");
      }

    }

    return sources.build();
  }

  // Return the gen-dir relative sources for the given services and options.
  @Override
  public ImmutableSortedSet<String> getGeneratedSources(
      BuildTarget target,
      ThriftConstructorArg args,
      String thriftName,
      ImmutableList<String> services) {
    return getGeneratedSources(thriftName, services, getOptions(target, args));
  }

  // Find all the generated sources and headers generated by these thrift sources.
  private CxxHeadersAndSources getThriftHeaderSourceSpec(
      BuildRuleParams params,
      ThriftConstructorArg args,
      ImmutableMap<String, ThriftSource> sources) {

    ImmutableMap.Builder<String, SourceWithFlags> cxxSourcesBuilder = ImmutableMap.builder();
    ImmutableMap.Builder<String, SourcePath> headersBuilder = ImmutableMap.builder();

    for (ImmutableMap.Entry<String, ThriftSource> ent : sources.entrySet()) {
      final String thriftName = ent.getKey();
      final ThriftSource source = ent.getValue();
      final Path outputDir = source.getOutputDir();

      for (String partialName :
           getGeneratedSources(params.getBuildTarget(), args, thriftName, source.getServices())) {
        String name = ThriftCompiler.resolveLanguageDir(getLanguage(), partialName);
        String extension = Files.getFileExtension(name);
        if (CxxCompilables.SOURCE_EXTENSIONS.contains(extension)) {
          cxxSourcesBuilder.put(
              name,
              SourceWithFlags.of(
                  new BuildTargetSourcePath(
                      source.getCompileRule().getBuildTarget(),
                      outputDir.resolve(name))));
        } else if (CxxCompilables.HEADER_EXTENSIONS.contains(extension)) {
          headersBuilder.put(
              name,
              new BuildTargetSourcePath(
                  source.getCompileRule().getBuildTarget(),
                  outputDir.resolve(name)));
        } else {
          throw new HumanReadableException(String.format(
              "%s: unexpected extension for \"%s\"",
              params.getBuildTarget(),
              name));
        }
      }
    }

    return new CxxHeadersAndSources(
        headersBuilder.build(),
        cxxSourcesBuilder.build());
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      ThriftConstructorArg args,
      ImmutableMap<String, ThriftSource> sources,
      ImmutableSortedSet<BuildRule> deps) throws NoSuchBuildTargetException {

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    // Grab all the sources and headers generated from the passed in thrift sources.
    CxxHeadersAndSources spec = getThriftHeaderSourceSpec(params, args, sources);

    // Add all the passed in language-specific thrift deps, and any C/C++ specific deps
    // passed in via the constructor arg.
    ImmutableSortedSet<BuildRule> allDeps =
        ImmutableSortedSet.<BuildRule>naturalOrder()
            .addAll(deps)
            .addAll(
                resolver.getAllRules(
                    (cpp2 ? args.cpp2Deps : args.cppDeps)))
            .build();

    // Create language specific build params by using the deps we formed above.
    BuildRuleParams langParams = params.copyWithDeps(
        Suppliers.ofInstance(ImmutableSortedSet.of()),
        Suppliers.ofInstance(allDeps));

    // Merge the thrift generated headers with the ones passed in via the description.
    ImmutableSortedMap.Builder<String, SourcePath> headersBuilder =
        ImmutableSortedMap.naturalOrder();
    headersBuilder.putAll(spec.getHeaders());
    if (args.cppExportedHeaders.getNamedSources().isPresent()) {
      headersBuilder.putAll(args.cppExportedHeaders.getNamedSources().get());
    } else {
      headersBuilder.putAll(
          pathResolver.getSourcePathNames(
              params.getBuildTarget(),
              "cpp_headers",
              args.cppExportedHeaders.getUnnamedSources().get()));
    }
    ImmutableSortedMap<String, SourcePath> headers = headersBuilder.build();

    // Merge the thrift generated sources with the ones passed in via the description.
    ImmutableSortedMap.Builder<String, SourceWithFlags> srcsBuilder =
        ImmutableSortedMap.naturalOrder();
    srcsBuilder.putAll(spec.getSources());
    if (args.cppSrcs.getNamedSources().isPresent()) {
      srcsBuilder.putAll(args.cppSrcs.getNamedSources().get());
    } else {
      for (SourceWithFlags sourceWithFlags : args.cppSrcs.getUnnamedSources().get()) {
        srcsBuilder.put(
            pathResolver.getSourcePathName(
                params.getBuildTarget(),
                sourceWithFlags.getSourcePath()),
            sourceWithFlags);
      }
    }
    ImmutableSortedMap<String, SourceWithFlags> srcs = srcsBuilder.build();

    // Construct the C/C++ library description argument to pass to the
    CxxLibraryDescription.Arg langArgs = CxxLibraryDescription.createEmptyConstructorArg();
    langArgs.headerNamespace = args.cppHeaderNamespace;
    langArgs.srcs = ImmutableSortedSet.copyOf(srcs.values());
    langArgs.exportedHeaders = SourceList.ofNamedSources(headers);
    langArgs.canBeAsset = Optional.empty();
    langArgs.compilerFlags = cpp2 ? args.cpp2CompilerFlags : args.cppCompilerFlags;

    // Since thrift generated C/C++ code uses lots of templates, just use exported deps throughout.
    langArgs.exportedDeps =
        FluentIterable.from(allDeps)
            .transform(HasBuildTarget::getBuildTarget)
            .toSortedSet(Ordering.natural());

    return cxxLibraryDescription.createBuildRule(targetGraph, langParams, resolver, langArgs);
  }

  private ImmutableSet<BuildTarget> getImplicitDepsFromOptions(
      UnflavoredBuildTarget target,
      ImmutableSet<String> options) {

    ImmutableSet.Builder<BuildTarget> implicitDeps = ImmutableSet.builder();

    if (!options.contains("bootstrap")) {
      if (cpp2) {
        implicitDeps.add(thriftBuckConfig.getCpp2Dep());
      } else {
        implicitDeps.add(thriftBuckConfig.getCppDep());
      }
      implicitDeps.add(thriftBuckConfig.getCppReflectionDep());
    }

    if (options.contains("frozen2")) {
      implicitDeps.add(thriftBuckConfig.getCppFrozenDep());
    }

    if (options.contains("json")) {
      implicitDeps.add(thriftBuckConfig.getCppJsonDep());
    }

    if (cpp2 && options.contains("compatibility")) {
      implicitDeps.add(thriftBuckConfig.getCppDep());
      BuildTarget cppTarget = BuildTargets.createFlavoredBuildTarget(target, CPP_FLAVOR);
      implicitDeps.add(cppTarget);
    }

    if (!cpp2 && options.contains("cob_style")) {
      implicitDeps.add(thriftBuckConfig.getCppAyncDep());
    }

    if (cpp2) {
      boolean flagLeanMeanMetaMachine = options.contains("lean_mean_meta_machine");
      if (options.contains("lean_mean_meta_machine")) {
        implicitDeps.add(thriftBuckConfig.getCpp2LeanMeanMetaMachineDep());
      }

      boolean flagReflection = flagLeanMeanMetaMachine ||
        options.contains("fatal") ||
        options.contains("reflection");
      if (flagReflection) {
        implicitDeps.add(thriftBuckConfig.getCpp2ReflectionDep());
      }
    }

    return implicitDeps.build();
  }

  @Override
  public ImmutableSet<BuildTarget> getImplicitDepsForTargetFromConstructorArg(
      BuildTarget target,
      ThriftConstructorArg arg) {
    ImmutableSet<String> options = cpp2 ? arg.cpp2Options : arg.cppOptions;
    return getImplicitDepsFromOptions(
        target.getUnflavoredBuildTarget(),
        options);
  }

  @Override
  public ImmutableSet<String> getOptions(
      BuildTarget target,
      ThriftConstructorArg args) {
    return ImmutableSet.<String>builder()
        .add(String.format("include_prefix=%s", target.getBasePath()))
        .addAll((cpp2 ? args.cpp2Options : args.cppOptions))
        .build();
  }

  @Override
  public ThriftLibraryDescription.CompilerType getCompilerType() {
    return cpp2 ?
        ThriftLibraryDescription.CompilerType.THRIFT2 :
        ThriftLibraryDescription.CompilerType.THRIFT;
  }

  private static class CxxHeadersAndSources {

    private final ImmutableMap<String, SourcePath> headers;
    private final ImmutableMap<String, SourceWithFlags> sources;

    public CxxHeadersAndSources(
        ImmutableMap<String, SourcePath> headers,
        ImmutableMap<String, SourceWithFlags> sources) {
      this.headers = headers;
      this.sources = sources;
    }

    public ImmutableMap<String, SourcePath> getHeaders() {
      return headers;
    }

    public ImmutableMap<String, SourceWithFlags> getSources() {
      return sources;
    }

  }

}
