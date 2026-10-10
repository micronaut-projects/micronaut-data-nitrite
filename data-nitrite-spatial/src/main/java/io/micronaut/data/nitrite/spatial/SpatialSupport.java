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
package io.micronaut.data.nitrite.spatial;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.exceptions.DataAccessException;
import io.micronaut.data.nitrite.spi.NitriteSpatialSupport;
import org.dizitart.no2.common.module.NitriteModule;
import org.dizitart.no2.filters.Filter;
import org.dizitart.no2.spatial.GeoPoint;
import org.dizitart.no2.spatial.SpatialFluentFilter;
import org.dizitart.no2.spatial.SpatialModule;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

/**
 * Typed spatial module and filter construction.
 */
public final class SpatialSupport implements NitriteSpatialSupport {
    /** Creates a provider. */
    public SpatialSupport() {
    }

    @Override
    public NitriteModule createModule() {
        return new SpatialModule();
    }

    @Override
    public String deserializationPatterns() {
        return "org.locationtech.jts.geom.**";
    }

    @Override
    public Filter near(String field, @Nullable Object center, double distance) {
        if (center == null) {
            return Filter.ALL;
        }
        SpatialFluentFilter filter = SpatialFluentFilter.where(field);
        if (center instanceof GeoPoint geoPoint) {
            return filter.near(geoPoint.getPoint(), distance);
        }
        if (center instanceof Point point) {
            return filter.near(point, distance);
        }
        if (center instanceof Coordinate coordinate) {
            return filter.near(coordinate, distance);
        }
        if (center instanceof Geometry geometry && geometry.getCoordinate() != null) {
            return filter.near(geometry.getCoordinate(), distance);
        }
        throw new DataAccessException("Unsupported center type for $near spatial filter: " + center.getClass().getName());
    }

    @Override
    public Filter filter(String field, Object value, String operation) {
        Object unwrapped = value instanceof GeoPoint geoPoint ? geoPoint.getPoint() : value;
        if (!(unwrapped instanceof Geometry geometry)) {
            throw new DataAccessException("Unsupported geometry type for $" + operation + " spatial filter: " + value.getClass().getName());
        }
        return switch (operation) {
            case "within" -> SpatialFluentFilter.where(field).within(geometry);
            case "intersects" -> SpatialFluentFilter.where(field).intersects(geometry);
            default -> throw new DataAccessException("Unsupported spatial operation: " + operation);
        };
    }
}
