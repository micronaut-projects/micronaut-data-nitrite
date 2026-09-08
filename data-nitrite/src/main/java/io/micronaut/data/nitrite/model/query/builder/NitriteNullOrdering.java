/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.data.nitrite.model.query.builder;

import io.micronaut.core.annotation.Internal;

/**
 * Where nulls of a sort key are placed.
 *
 * <p>Micronaut Data carries an equivalent on {@code Sort.Order} only from 5.2.0. This module targets
 * the released line, so a key encodes its placement here instead and the adapter applies it; see
 * {@link io.micronaut.data.nitrite.runtime.read.NitriteSortPlan}. A placement asked for through a
 * runtime {@code Sort} cannot be read back and is therefore {@link #NONE}.
 *
 * @since 1.0.0
 */
@Internal
public enum NitriteNullOrdering {

    /**
     * Nulls sort before non-nulls, whichever direction the key orders.
     */
    FIRST,

    /**
     * Nulls sort after non-nulls, whichever direction the key orders.
     */
    LAST,

    /**
     * No placement was asked for, leaving it to Nitrite.
     */
    NONE
}
