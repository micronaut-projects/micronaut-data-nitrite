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
import org.dizitart.no2.Nitrite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.geom.CoordinateXYM;
import org.locationtech.jts.geom.CoordinateXYZM;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;

import java.util.Map;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exercises spatial indexes, repository filters and the optional geometry codec. */
class NativeSpatialTest {
    @TempDir
    Path directory;

    @Test
    void mvStoreSpatialDocumentsSurviveReopening() {
        assumeTrue(System.getProperty("nitrite.native.backend", "all").equals("all"));
        Map<String, Object> properties = Map.of(
            "micronaut.nitrite.default.storage-mode", "MVSTORE",
            "micronaut.nitrite.default.db-path", directory.resolve("places.db").toString(),
            "micronaut.test.resources.enabled", false);
        GeometryFactory factory = new GeometryFactory();
        Point point = factory.createPoint(new Coordinate(1, 1));
        LineString line = factory.createLineString(new Coordinate[]{new Coordinate(0, 0), new Coordinate(1, 1)});
        LinearRing ring = factory.createLinearRing(new Coordinate[]{new Coordinate(0, 0), new Coordinate(0, 5),
            new Coordinate(5, 5), new Coordinate(5, 0), new Coordinate(0, 0)});
        Polygon polygon = factory.createPolygon(ring);
        GeometryFactory packedDouble = new GeometryFactory(new PrecisionModel(), 0,
            new PackedCoordinateSequenceFactory(PackedCoordinateSequenceFactory.DOUBLE));
        GeometryFactory packedFloat = new GeometryFactory(new PrecisionModel(), 0,
            new PackedCoordinateSequenceFactory(PackedCoordinateSequenceFactory.FLOAT));
        Geometry[] geometries = {point, line, ring, polygon, factory.createMultiPoint(new Point[]{point}),
            factory.createMultiLineString(new LineString[]{line}), factory.createMultiPolygon(new Polygon[]{polygon}),
            factory.createGeometryCollection(new Geometry[]{point, line, polygon}),
            factory.createPoint(new CoordinateXY(1, 1)), factory.createPoint(new CoordinateXYM(1, 1, 2)),
            factory.createPoint(new CoordinateXYZM(1, 1, 2, 3)),
            packedDouble.createPoint(new Coordinate(1, 1)), packedFloat.createPoint(new Coordinate(1, 1))};
        try (ApplicationContext context = ApplicationContext.run(properties)) {
            NativePlaceRepository repository = context.getBean(NativePlaceRepository.class);
            for (int i = 0; i < geometries.length; i++) {
                repository.save(new NativePlace((long) i + 1, geometries[i]));
            }
            context.getBean(Nitrite.class).commit();
        }
        try (ApplicationContext context = ApplicationContext.run(properties)) {
            NativePlaceRepository repository = context.getBean(NativePlaceRepository.class);
            for (int i = 0; i < geometries.length; i++) {
                assertTrue(repository.findById((long) i + 1).orElseThrow().getLocation().equalsExact(geometries[i]));
            }
        }
    }

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
