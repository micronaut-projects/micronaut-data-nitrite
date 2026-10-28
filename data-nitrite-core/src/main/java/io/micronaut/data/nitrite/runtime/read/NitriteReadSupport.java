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
package io.micronaut.data.nitrite.runtime.read;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.data.model.runtime.RuntimePersistentEntity;
import io.micronaut.data.nitrite.runtime.NitriteOperationsHelper;
import io.micronaut.data.nitrite.runtime.mapping.NitriteEntityMapper;
import io.micronaut.data.nitrite.runtime.query.NitriteFilterBuilder;
import io.micronaut.data.nitrite.runtime.query.NitriteQueryParser;
import org.dizitart.no2.collection.NitriteCollection;

import java.util.function.Function;

/**
 * The collaborators the query and criteria executors of one repository operations instance share.
 *
 * @param entityMapper                 the entity mapper
 * @param queryParser                  the query parser
 * @param filterBuilder                the filter builder
 * @param conversionService            the conversion service
 * @param collectionFactory            the collection factory function
 * @param entityFactory                the entity factory function
 * @param useCursorLimitForSortedReads whether sorted bounds should be moved to the cursor
 * @param helper                       the operations helper that logs each executed operation
 * @since 5.2.0
 */
@Internal
public record NitriteReadSupport(
    NitriteEntityMapper entityMapper,
    NitriteQueryParser queryParser,
    NitriteFilterBuilder filterBuilder,
    ConversionService conversionService,
    Function<Class<?>, NitriteCollection> collectionFactory,
    Function<Class<?>, RuntimePersistentEntity<?>> entityFactory,
    boolean useCursorLimitForSortedReads,
    NitriteOperationsHelper helper) {
}
