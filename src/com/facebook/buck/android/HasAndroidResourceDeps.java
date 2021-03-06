/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import javax.annotation.Nullable;

/**
 * Indicates that this class may have android resources that should be packaged into an APK.
 */
@SuppressWarnings("checkstyle:ConstantName") // checkstyle doesn't handle lambdas correctly.
public interface HasAndroidResourceDeps extends HasBuildTarget {

  Function<Iterable<HasAndroidResourceDeps>, Sha1HashCode> ABI_HASHER =
      deps -> {
        Hasher hasher = Hashing.sha1().newHasher();
        for (HasAndroidResourceDeps dep : deps) {
          hasher.putUnencodedChars(dep.getPathToTextSymbolsFile().toString());
          // Avoid collisions by marking end of path explicitly.
          hasher.putChar('\0');
          hasher.putUnencodedChars(dep.getTextSymbolsAbiKey().getHash());
          hasher.putUnencodedChars(dep.getRDotJavaPackage());
          hasher.putChar('\0');
        }
        return Sha1HashCode.fromHashCode(hasher.hash());
      };

  Function<HasAndroidResourceDeps, String> TO_R_DOT_JAVA_PACKAGE =
      HasAndroidResourceDeps::getRDotJavaPackage;

  Predicate<HasAndroidResourceDeps> NON_EMPTY_RESOURCE =
      input -> input.getRes() != null;

  Function<HasAndroidResourceDeps, SourcePath> GET_RES_SYMBOLS_TXT =
      new Function<HasAndroidResourceDeps, SourcePath>() {
        @Nullable
        @Override
        public SourcePath apply(HasAndroidResourceDeps input) {
          return input.getPathToTextSymbolsFile();
        }
      };

  /**
   * @return the package name in which to generate the R.java representing these resources.
   */
  String getRDotJavaPackage();

  /**
   * @return path to a temporary directory for storing text symbols.
   */
  SourcePath getPathToTextSymbolsFile();

  /**
   * @return an ABI for the file pointed by {@link #getPathToTextSymbolsFile()}. Since the symbols
   *     text file is essentially a list of resource id, name and type, this is simply a sha1 of
   *     that file.
   */
  Sha1HashCode getTextSymbolsAbiKey();

  /**
   * @return path to a directory containing Android resources.
   */
  @Nullable
  SourcePath getRes();

  /**
   * @return path to a directory containing Android assets.
   */
  @Nullable
  SourcePath getAssets();

}
