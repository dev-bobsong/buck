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
package com.facebook.buck.util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Assert;
import org.junit.Test;

import java.util.function.Function;

public class MoreCollectorsTest {

  @Test
  public void toImmutableListPreservesInsertionOrder() {
    ImmutableList<Integer> sampleInput = ImmutableList.of(4, 2, 3, 7, 1);
    Assert.assertEquals(
        sampleInput,
        sampleInput.stream().collect(MoreCollectors.toImmutableList()));
  }

  @Test
  public void toImmutableSetPreservesInsertionOrder() {
    ImmutableList<Integer> sampleInput = ImmutableList.of(4, 2, 3, 7, 1);
    Assert.assertEquals(
        ImmutableSet.copyOf(sampleInput),
        sampleInput.stream().collect(MoreCollectors.toImmutableSet()));
  }

  @Test
  public void toImmutableMapPreservesInsertionOrder() {
    ImmutableList<Integer> sampleInput = ImmutableList.of(4, 2, 3, 7, 1);
    Assert.assertEquals(
        ImmutableMap.of(4, 4, 2, 2, 3, 3, 7, 7, 1, 1),
        sampleInput.stream().collect(MoreCollectors.toImmutableMap(
            Function.identity(),
            Function.identity())));
  }

  @Test
  public void toImmutableMapMapsKeysAndValues() {
    ImmutableList<Integer> sampleInput = ImmutableList.of(4, 2, 3, 7, 1);
    Assert.assertEquals(
        ImmutableMap.of(5, 6, 3, 4, 4, 5, 8, 9, 2, 3),
        sampleInput.stream().collect(MoreCollectors.toImmutableMap(
            x -> x + 1,
            x -> x + 2)));
  }
}
