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
package io.micronaut.data.nitrite.runtime.write;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.data.event.EntityEventListener;
import io.micronaut.data.nitrite.runtime.NitriteOperationsHelper;
import io.micronaut.data.nitrite.runtime.mapping.NitriteEntityMapper;
import io.micronaut.data.runtime.operations.internal.SyncCascadeOperations;
import io.micronaut.data.runtime.operations.internal.SyncCascadeOperations.SyncCascadeOperationsHelper;

/**
 * The collaborators every write operation of one repository operations instance shares.
 *
 * @param cascadeOperations   the cascade operations
 * @param entityEventListener the entity event listener
 * @param conversionService   the conversion service
 * @param entityMapper        the entity mapper
 * @param helper              the operations helper
 * @param cascadeHelper       the cascade operations helper
 * @since 5.2.0
 */
@Internal
public record NitriteWriteSupport(
    SyncCascadeOperations<NitriteOperationContext> cascadeOperations,
    EntityEventListener<Object> entityEventListener,
    ConversionService conversionService,
    NitriteEntityMapper entityMapper,
    NitriteOperationsHelper helper,
    SyncCascadeOperationsHelper<NitriteOperationContext> cascadeHelper) {
}
