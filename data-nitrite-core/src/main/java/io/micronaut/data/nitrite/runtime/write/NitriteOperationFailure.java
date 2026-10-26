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

import io.micronaut.data.exceptions.DataAccessException;
import org.dizitart.no2.exceptions.NitriteSecurityException;

/**
 * Builds the {@link DataAccessException} for a failed write and, when MVStore rejected a stored
 * class during deserialization, names the datasource property that allowlists it. Nitrite wraps
 * the rejection (for example as "Unable to open map ..."), so the whole cause chain is searched.
 */
final class NitriteOperationFailure {

    private static final String DESERIALIZATION_REJECTED = "is not allowed to be deserialized";

    private NitriteOperationFailure() {
    }

    static DataAccessException from(Exception cause, String operation, String dataSourceName) {
        String message = "Error executing " + operation + ": " + cause.getMessage();
        if (isDeserializationRejection(cause)) {
            message += ". For Micronaut Data, configure `micronaut.nitrite." + dataSourceName
                    + ".mvstore-allowed-classes` with a JEP 290 pattern for the trusted application class.";
        }
        return new DataAccessException(message, cause);
    }

    private static boolean isDeserializationRejection(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof NitriteSecurityException
                    && current.getMessage() != null
                    && current.getMessage().contains(DESERIALIZATION_REJECTED)) {
                return true;
            }
        }
        return false;
    }
}
