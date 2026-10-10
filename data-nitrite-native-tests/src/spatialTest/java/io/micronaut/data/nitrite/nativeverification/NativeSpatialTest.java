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

import io.micronaut.context.ApplicationContext;
import io.micronaut.data.nitrite.spi.NitriteProviders;
import io.micronaut.data.nitrite.spi.NitriteSpatialSupport;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises spatial indexes, repository filters and the optional geometry codec. */
class NativeSpatialTest {
    @Test
    void spatialQueriesUseTheInstalledIntegration() throws Exception {
        assertEquals(1, NitriteProviders.load(NitriteSpatialSupport.class).size());
        try (ApplicationContext context = ApplicationContext.run(Map.of(
            "micronaut.nitrite.default.storage-mode", "IN_MEMORY",
            "micronaut.test.resources.enabled", false))) {
            GeometryFactory geometries = new GeometryFactory();
            Geometry point = geometries.createPoint(new Coordinate(1, 1));
            Geometry distant = geometries.createPoint(new Coordinate(50, 50));
            Geometry area = geometries.createPolygon(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(0, 5), new Coordinate(5, 5),
                new Coordinate(5, 0), new Coordinate(0, 0)});
            NativePlaceRepository repository = context.getBean(NativePlaceRepository.class);
            repository.save(new NativePlace(1L, point));
            repository.save(new NativePlace(2L, distant));
            assertEquals(1L, repository.findByLocationNear(point, 2).getFirst().getId());
            assertEquals(1L, repository.within(area).getFirst().getId());
            assertEquals(1L, repository.intersects(area).getFirst().getId());
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            Geometry decoded = mapper.readValue(mapper.writeValueAsString(area), Geometry.class);
            assertTrue(decoded.equalsExact(area));
        }
    }
}
