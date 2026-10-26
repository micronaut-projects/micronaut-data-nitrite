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
package io.micronaut.data.nitrite.runtime;

import io.micronaut.core.annotation.Internal;
import io.micronaut.data.nitrite.conf.NitriteConfiguration;
import io.micronaut.data.nitrite.transaction.NitriteTransactionHolder;
import org.dizitart.no2.Nitrite;

/**
 * The beans of one named Nitrite datasource that its repository operations are built from.
 *
 * @param database          the Nitrite database
 * @param configuration     the datasource configuration
 * @param transactionHolder the datasource's transaction context holder
 * @since 5.2.0
 */
@Internal
public record NitriteDatasource(
    Nitrite database,
    NitriteConfiguration configuration,
    NitriteTransactionHolder transactionHolder) {
}
