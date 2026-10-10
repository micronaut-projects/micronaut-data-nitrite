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
package io.micronaut.data.nitrite.nativeverification;

import io.micronaut.data.annotation.Query;
import io.micronaut.data.nitrite.annotation.NitriteRepository;
import io.micronaut.data.repository.CrudRepository;
import org.locationtech.jts.geom.Geometry;

import java.util.List;

/** Spatial repository whose filters are built through the optional integration. */
@NitriteRepository
public interface NativePlaceRepository extends CrudRepository<NativePlace, Long> {
    List<NativePlace> findByLocationNear(Geometry location, double distance);

    @Query("{\"location\": {\"$within\": :area}}")
    List<NativePlace> within(Geometry area);

    @Query("{\"location\": {\"$intersects\": :area}}")
    List<NativePlace> intersects(Geometry area);
}
