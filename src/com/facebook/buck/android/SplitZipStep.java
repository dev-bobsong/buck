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

package com.facebook.buck.android;

import com.facebook.buck.dalvik.DalvikAwareZipSplitterFactory;
import com.facebook.buck.dalvik.DefaultZipSplitterFactory;
import com.facebook.buck.dalvik.ZipSplitter;
import com.facebook.buck.dalvik.ZipSplitterFactory;
import com.facebook.buck.dalvik.firstorder.FirstOrderHelper;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nullable;

/**
 * Split zipping tool designed to divide input code blobs into a set of output jar files such that
 * none will exceed the DexOpt LinearAlloc limit or the dx method limit when passed through
 * dx --dex.
 */
public class SplitZipStep implements Step {

  private static final int ZIP_SIZE_SOFT_LIMIT = 11 * 1024 * 1024;

  /**
   * The uncompressed class size is a very simple metric that we can use to roughly estimate
   * whether we will hit the DexOpt LinearAlloc limit.  When we hit the limit, we were around
   * 20 MB uncompressed, so use 13 MB as a safer upper limit.
   */
  private static final int ZIP_SIZE_HARD_LIMIT = ZIP_SIZE_SOFT_LIMIT + (2 * 1024 * 1024);

  // Transform Function that calls String.trim()
  private static final Function<String, String> STRING_TRIM = line -> line.trim();

  // Transform Function that appends ".class"
  private static final Function<String, String> APPEND_CLASS_SUFFIX =
      input -> input + ".class";

  // Predicate that rejects blank lines and lines starting with '#'.
  private static final Predicate<String> IS_NEITHER_EMPTY_NOR_COMMENT =
      line -> !line.isEmpty() && !(line.charAt(0) == '#');

  // Transform Function that calls Type.GetObjectType.
  private static final Function<String, Type> TYPE_GET_OBJECT_TYPE =
      input -> Type.getObjectType(input);

  @VisibleForTesting
  static final Pattern CANARY_CLASS_FILE_PATTERN = Pattern.compile("^([\\w/$]+)\\.Canary\\.class");
  @VisibleForTesting
  static final Pattern CLASS_FILE_PATTERN = Pattern.compile("^([\\w/$]+)\\.class");

  public static final String SECONDARY_DEX_ID = "dex";

  private final ProjectFilesystem filesystem;
  private final Set<Path> inputPathsToSplit;
  private final Path secondaryJarMetaPath;
  private final Path primaryJarPath;
  private final Path secondaryJarDir;
  private final String secondaryJarPattern;
  private final Path addtionalDexStoreJarMetaPath;
  private final Path additionalDexStoreJarDir;
  private final Optional<Path> proguardFullConfigFile;
  private final Optional<Path> proguardMappingFile;
  private final DexSplitMode dexSplitMode;
  private final Path pathToReportDir;

  private final Optional<Path> primaryDexScenarioFile;
  private final Optional<Path> primaryDexClassesFile;
  private final Optional<Path> secondaryDexHeadClassesFile;
  private final Optional<Path> secondaryDexTailClassesFile;
  private final ImmutableMultimap<APKModule, Path> apkModuleToJarPathMap;
  private final APKModuleGraph apkModuleGraph;

  @Nullable
  private ImmutableMultimap<APKModule, Path> outputFiles;

  /**
   * @param inputPathsToSplit Input paths that would otherwise have been passed to a single dx --dex
   *     invocation.
   * @param secondaryJarMetaPath Output location for the metadata text file describing each
   *     secondary jar artifact.
   * @param primaryJarPath Output path for the primary jar file.
   * @param secondaryJarDir Output location for secondary jar files.  Note that this directory may
   *     be empty if no secondary jar files are needed.
   * @param secondaryJarPattern Filename pattern for secondary jar files.  Pattern contains one %d
   *     argument representing the enumerated secondary zip count (starting at 1).
   * @param proguardFullConfigFile Path to the full generated ProGuard configuration, generated
   *     by the -printconfiguration flag.  This is part of the *output* of ProGuard.
   * @param proguardMappingFile Path to the mapping file generated by ProGuard's obfuscation.
   */
  public SplitZipStep(
      ProjectFilesystem filesystem,
      Set<Path> inputPathsToSplit,
      Path secondaryJarMetaPath,
      Path primaryJarPath,
      Path secondaryJarDir,
      String secondaryJarPattern,
      Path addtionalDexStoreJarMetaPath,
      Path additionalDexStoreJarDir,
      Optional<Path> proguardFullConfigFile,
      Optional<Path> proguardMappingFile,
      DexSplitMode dexSplitMode,
      Optional<Path> primaryDexScenarioFile,
      Optional<Path> primaryDexClassesFile,
      Optional<Path> secondaryDexHeadClassesFile,
      Optional<Path> secondaryDexTailClassesFile,
      ImmutableMultimap<APKModule, Path> apkModuleToJarPathMap,
      APKModuleGraph apkModuleGraph,
      Path pathToReportDir) {
    this.filesystem = filesystem;
    this.inputPathsToSplit = ImmutableSet.copyOf(inputPathsToSplit);
    this.secondaryJarMetaPath = secondaryJarMetaPath;
    this.primaryJarPath = primaryJarPath;
    this.secondaryJarDir = secondaryJarDir;
    this.secondaryJarPattern = secondaryJarPattern;
    this.addtionalDexStoreJarMetaPath = addtionalDexStoreJarMetaPath;
    this.additionalDexStoreJarDir = additionalDexStoreJarDir;
    this.proguardFullConfigFile = proguardFullConfigFile;
    this.proguardMappingFile = proguardMappingFile;
    this.dexSplitMode = dexSplitMode;
    this.primaryDexScenarioFile = primaryDexScenarioFile;
    this.primaryDexClassesFile = primaryDexClassesFile;
    this.secondaryDexHeadClassesFile = secondaryDexHeadClassesFile;
    this.secondaryDexTailClassesFile = secondaryDexTailClassesFile;
    this.apkModuleToJarPathMap = apkModuleToJarPathMap;
    this.apkModuleGraph = apkModuleGraph;
    this.pathToReportDir = pathToReportDir;

    Preconditions.checkArgument(
        proguardFullConfigFile.isPresent() == proguardMappingFile.isPresent(),
        "ProGuard configuration and mapping must both be present or absent.");
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) {
    try {
      Set<Path> inputJarPaths = FluentIterable.from(inputPathsToSplit)
          .transform(filesystem.getAbsolutifier())
          .toSet();
      Supplier<ImmutableList<ClassNode>> classes =
          ClassNodeListSupplier.createMemoized(inputJarPaths);
      ProguardTranslatorFactory translatorFactory = ProguardTranslatorFactory.create(
          filesystem,
          proguardFullConfigFile,
          proguardMappingFile);
      Predicate<String> requiredInPrimaryZip =
          createRequiredInPrimaryZipPredicate(translatorFactory, classes);
      final ImmutableSet<String> wantedInPrimaryZip =
          getWantedPrimaryDexEntries(translatorFactory, classes);
      final ImmutableSet<String> secondaryHeadSet = getSecondaryHeadSet(translatorFactory);
      final ImmutableSet<String> secondaryTailSet = getSecondaryTailSet(translatorFactory);
      final ImmutableMultimap<APKModule, String> additionalDexStoreClasses =
          APKModuleGraph.getAPKModuleToClassesMap(
              apkModuleToJarPathMap,
              translatorFactory.createObfuscationFunction(),
              filesystem);

      ZipSplitterFactory zipSplitterFactory;
      if (dexSplitMode.useLinearAllocSplitDex()) {
        zipSplitterFactory = new DalvikAwareZipSplitterFactory(
            dexSplitMode.getLinearAllocHardLimit(),
            wantedInPrimaryZip);
      } else {
        zipSplitterFactory = new DefaultZipSplitterFactory(ZIP_SIZE_SOFT_LIMIT,
            ZIP_SIZE_HARD_LIMIT);
      }

      outputFiles = zipSplitterFactory.newInstance(
          filesystem,
          inputJarPaths,
          primaryJarPath,
          secondaryJarDir,
          secondaryJarPattern,
          additionalDexStoreJarDir,
          requiredInPrimaryZip,
          secondaryHeadSet,
          secondaryTailSet,
          additionalDexStoreClasses,
          apkModuleGraph,
          dexSplitMode.getDexSplitStrategy(),
          ZipSplitter.CanaryStrategy.INCLUDE_CANARIES,
          filesystem.getPathForRelativePath(pathToReportDir))
          .execute();

      for (APKModule dexStore : outputFiles.keySet()) {
        if (dexStore.getName().equals(SECONDARY_DEX_ID)) {
          try (BufferedWriter secondaryMetaInfoWriter = Files.newWriter(
              secondaryJarMetaPath.toFile(),
              Charsets.UTF_8)) {
            writeMetaList(
                secondaryMetaInfoWriter,
                SECONDARY_DEX_ID,
                ImmutableSet.of(),
                outputFiles.get(dexStore).asList(),
                dexSplitMode.getDexStore());
          }
        } else {
          try (BufferedWriter secondaryMetaInfoWriter = Files.newWriter(
              addtionalDexStoreJarMetaPath
                  .resolve("assets")
                  .resolve(dexStore.getName())
                  .resolve("metadata.txt").toFile(),
              Charsets.UTF_8)) {
            writeMetaList(
                secondaryMetaInfoWriter,
                dexStore.getName(),
                apkModuleGraph.getGraph().getOutgoingNodesFor(dexStore),
                outputFiles.get(dexStore).asList(),
                dexSplitMode.getDexStore());
          }
        }
      }

      return StepExecutionResult.SUCCESS;
    } catch (IOException e) {
      context.logError(e, "There was an error running SplitZipStep.");
      return StepExecutionResult.ERROR;
    }
  }

  @VisibleForTesting
  Predicate<String> createRequiredInPrimaryZipPredicate(
      ProguardTranslatorFactory translatorFactory,
      Supplier<ImmutableList<ClassNode>> classesSupplier)
      throws IOException {
    final Function<String, String> deobfuscate = translatorFactory.createDeobfuscationFunction();
    final ImmutableSet<String> primaryDexClassNames =
        getRequiredPrimaryDexClassNames(translatorFactory, classesSupplier);
    final ClassNameFilter primaryDexFilter =
        ClassNameFilter.fromConfiguration(dexSplitMode.getPrimaryDexPatterns());

    return classFileName -> {
      // Drop the ".class" suffix and deobfuscate the class name before we apply our checks.
      String internalClassName = Preconditions.checkNotNull(
          deobfuscate.apply(classFileName.replaceAll("\\.class$", "")));

      if (primaryDexClassNames.contains(internalClassName)) {
        return true;
      }

      return primaryDexFilter.matches(internalClassName);
    };
  }

  /**
   * Construct a {@link Set} of internal class names that must go into the primary dex.
   * <p/>
   * @return ImmutableSet of class internal names.
   */
  private ImmutableSet<String> getRequiredPrimaryDexClassNames(
      ProguardTranslatorFactory translatorFactory,
      Supplier<ImmutableList<ClassNode>> classesSupplier)
      throws IOException {
    ImmutableSet.Builder<String> builder = ImmutableSet.builder();

    if (primaryDexClassesFile.isPresent()) {
      Iterable<String> classes = FluentIterable
          .from(filesystem.readLines(primaryDexClassesFile.get()))
          .transform(STRING_TRIM)
          .filter(IS_NEITHER_EMPTY_NOR_COMMENT);
      builder.addAll(classes);
    }

    // If there is a scenario file but overflow is not allowed, then the scenario dependencies
    // are required, and therefore get added here.
    if (!dexSplitMode.isPrimaryDexScenarioOverflowAllowed() && primaryDexScenarioFile.isPresent()) {
      addScenarioClasses(translatorFactory, classesSupplier, builder);
    }

    return ImmutableSet.copyOf(builder.build());
  }
  /**
   * Construct a {@link Set} of internal class names that must go into the beginning of
   * the secondary dexes.
   * <p/>
   * @return ImmutableSet of class internal names.
   */
  private ImmutableSet<String> getSecondaryHeadSet(
      ProguardTranslatorFactory translatorFactory)
      throws IOException {
    ImmutableSet.Builder<String> builder = ImmutableSet.builder();

    if (secondaryDexHeadClassesFile.isPresent()) {
      Iterable<String> classes = FluentIterable
          .from(filesystem.readLines(secondaryDexHeadClassesFile.get()))
          .transform(STRING_TRIM)
          .filter(IS_NEITHER_EMPTY_NOR_COMMENT)
          .transform(translatorFactory.createObfuscationFunction());
      builder.addAll(classes);
    }

    return ImmutableSet.copyOf(builder.build());
  }
  /**
   * Construct a {@link Set} of internal class names that must go into the beginning of
   * the secondary dexes.
   * <p/>
   * @return ImmutableSet of class internal names.
   */
  private ImmutableSet<String> getSecondaryTailSet(
      ProguardTranslatorFactory translatorFactory)
      throws IOException {
    ImmutableSet.Builder<String> builder = ImmutableSet.builder();

    if (secondaryDexTailClassesFile.isPresent()) {
      Iterable<String> classes = FluentIterable
          .from(filesystem.readLines(secondaryDexTailClassesFile.get()))
          .transform(STRING_TRIM)
          .filter(IS_NEITHER_EMPTY_NOR_COMMENT)
          .transform(translatorFactory.createObfuscationFunction());
      builder.addAll(classes);
    }

    return ImmutableSet.copyOf(builder.build());
  }

  /**
   * Construct a {@link Set} of zip file entry names that should go into the primary dex to
   * improve performance.
   * <p/>
   * @return ImmutableList of zip file entry names.
   */
  private ImmutableSet<String> getWantedPrimaryDexEntries(
      ProguardTranslatorFactory translatorFactory,
      Supplier<ImmutableList<ClassNode>> classesSupplier)
      throws IOException {
    ImmutableSet.Builder<String> builder = ImmutableSet.builder();

    // If there is a scenario file and overflow is allowed, then the scenario dependencies
    // are wanted but not required, and therefore get added here.
    if (dexSplitMode.isPrimaryDexScenarioOverflowAllowed() && primaryDexScenarioFile.isPresent()) {
      addScenarioClasses(translatorFactory, classesSupplier, builder);
    }

    return FluentIterable.from(builder.build())
        .transform(APPEND_CLASS_SUFFIX)
        .toSet();
  }

  /**
   * Adds classes listed in the scenario file along with their dependencies.  This adds classes
   * plus dependencies in the order the classes appear in the scenario file.
   * <p/>
   * @throws IOException
   */
  private void addScenarioClasses(
      ProguardTranslatorFactory translatorFactory,
      Supplier<ImmutableList<ClassNode>> classesSupplier,
      ImmutableSet.Builder<String> builder)
      throws IOException {

    ImmutableList<Type> scenarioClasses = FluentIterable
        .from(filesystem.readLines(primaryDexScenarioFile.get()))
        .transform(STRING_TRIM)
        .filter(IS_NEITHER_EMPTY_NOR_COMMENT)
        .transform(translatorFactory.createObfuscationFunction())
        .transform(TYPE_GET_OBJECT_TYPE)
        .toList();

    FirstOrderHelper.addTypesAndDependencies(
        scenarioClasses,
        classesSupplier.get(),
        builder);
  }

  @VisibleForTesting
  static void writeMetaList(
      BufferedWriter writer,
      String id,
      ImmutableSet<APKModule> requires,
      List<Path> jarFiles,
      DexStore dexStore) throws IOException {
    boolean isSecondaryDexStore = id.equals(SECONDARY_DEX_ID);
    if (DexStore.RAW.equals(dexStore) && isSecondaryDexStore) {
      writer.write(".root_relative");
      writer.newLine();
    }
    if (!isSecondaryDexStore) {
      writer.write(String.format(".id %s", id));
      writer.newLine();
    }
    if (requires != null && !requires.isEmpty()) {
      for (APKModule pkg : requires) {
        writer.write(String.format(".requires %s", pkg.getName()));
        writer.newLine();
      }
    }
    for (int i = 0; i < jarFiles.size(); i++) {
      String filename = dexStore.fileNameForSecondary(i);
      if (!isSecondaryDexStore) {
        filename = dexStore.fileNameForSecondary(id, i);
      }
      String jarHash = hexSha1(jarFiles.get(i));
      String containedClass = findAnyClass(jarFiles.get(i));
      Preconditions.checkNotNull(containedClass);
      writer.write(String.format("%s %s %s",
          filename, jarHash, containedClass));
      writer.newLine();
    }
  }

  private static String findAnyClass(Path jarFile) throws IOException {
    String className = findAnyClass(CANARY_CLASS_FILE_PATTERN, jarFile);
    if (className == null) {
      className = findAnyClass(CLASS_FILE_PATTERN, jarFile);
    }
    if (className != null) {
      return className;
    }

    // TODO(dreiss): It's possible for this to happen by chance, so we should handle it better.
    throw new IllegalStateException("Couldn't find any class in " + jarFile.toAbsolutePath());
  }

  @Nullable
  private static String findAnyClass(Pattern pattern, Path jarFile) throws IOException {
    try (ZipFile inZip = new ZipFile(jarFile.toFile())) {
      for (ZipEntry entry : Collections.list(inZip.entries())) {
        Matcher m = pattern.matcher(entry.getName());
        if (m.matches()) {
          return m.group(1).replace('/', '.');
        }
      }
    }
    return null;
  }

  private static String hexSha1(Path file) throws IOException {
    return MorePaths.asByteSource(file).hash(Hashing.sha1()).toString();
  }

  @Override
  public String getShortName() {
    return "split_zip";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return Joiner.on(' ').join(
        "split-zip",
        Joiner.on(':').join(inputPathsToSplit),
        secondaryJarMetaPath,
        primaryJarPath,
        secondaryJarDir,
        secondaryJarPattern,
        ZIP_SIZE_HARD_LIMIT);
  }

  public Supplier<Multimap<Path, Path>> getOutputToInputsMapSupplier(
      final Path secondaryOutputDir,
      final Path additionalOutputDir) {
    return () -> {
      Preconditions.checkState(outputFiles != null,
          "SplitZipStep must complete successfully before listing its outputs.");

      ImmutableMultimap.Builder<Path, Path> builder = ImmutableMultimap.builder();
      for (APKModule dexStore : outputFiles.keySet()) {
        Path storeRoot;
        if (dexStore.getName().equals(SECONDARY_DEX_ID)) {
          storeRoot = secondaryOutputDir;
        } else {
          storeRoot = additionalOutputDir.resolve(dexStore.getName());
        }
        ImmutableList<Path> outputList = outputFiles.get(dexStore).asList();
        for (int i = 0; i < outputList.size(); i++) {
          String dexName;
          if (dexStore.getName().equals(SECONDARY_DEX_ID)) {
            dexName = dexSplitMode.getDexStore().fileNameForSecondary(i);
          } else {
            dexName = dexSplitMode.getDexStore().fileNameForSecondary(dexStore.getName(), i);
          }
          Path outputDexPath =
              storeRoot.resolve(dexName);
          builder.put(outputDexPath, outputList.get(i));
        }
      }
      return builder.build();
    };
  }
}
