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

import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.model.Association;
import io.micronaut.data.model.PersistentProperty;
import io.micronaut.data.model.PersistentPropertyPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;

/** Maps entity property paths to Nitrite document field names. */
final class NitriteFieldNameResolver {

    static final String ID_FIELD = "id";

    private static final Logger LOG = LoggerFactory.getLogger(NitriteFieldNameResolver.class);

    private NitriteFieldNameResolver() { }

    static String getFieldName(PersistentPropertyPath propertyPath) {
        String result = resolve(propertyPath);
        if (LOG.isDebugEnabled()) {
            LOG.debug("getFieldName: path={}, result={}", propertyPath.getPath(), result);
        }
        return result;
    }

    static String asPath(Collection<Association> associations, PersistentProperty property) {
        if (associations.isEmpty()) {
            return property.getPersistedName();
        }
        StringBuilder sb = new StringBuilder();
        for (Association association : associations) {
            sb.append(association.getPersistedName()).append(".");
        }
        sb.append(property.getPersistedName());
        return sb.toString();
    }

    private static String resolve(PersistentPropertyPath propertyPath) {
        PersistentProperty property = propertyPath.getProperty();
        List<Association> associations = propertyPath.getAssociations();
        if (associations.isEmpty()) {
            return isIdentity(property) ? ID_FIELD : property.getPersistedName();
        }

        StringBuilder sb = new StringBuilder();
        boolean inIdentityPath = false;
        for (Association association : associations) {
            if (association.isEmbedded()) {
                boolean isIdentityAssoc = isIdentity(association);
                sb.append(isIdentityAssoc ? "_id" : association.getPersistedName()).append(".");
                inIdentityPath |= isIdentityAssoc;
            } else if (inIdentityPath) {
                sb.append(embeddedIdentitySegment(association)).append(".");
            } else if (association.getKind() == Relation.Kind.ONE_TO_MANY || association.getKind() == Relation.Kind.MANY_TO_MANY) {
                sb.append(association.getPersistedName()).append(".");
            } else if (association.equals(associations.getLast()) && isAssociatedIdentity(association, property)) {
                return association.getPersistedName();
            } else {
                sb.append(association.getName()).append(".");
            }
        }
        sb.append(inIdentityPath && isIdentity(property) ? "_id" : property.getPersistedName());
        return sb.toString();
    }

    private static String embeddedIdentitySegment(Association association) {
        if (isIdentity(association)) {
            return "_id";
        }
        if (association.getAnnotationMetadata().stringValue(MappedProperty.class).isPresent()) {
            return association.getPersistedName();
        }
        return association.getName();
    }

    private static boolean isIdentity(PersistentProperty property) {
        try {
            PersistentProperty identity = property.getOwner().getIdentity();
            return identity != null && identity.equals(property);
        } catch (IllegalStateException _) {
            // Entity may not have an identity property
            return false;
        }
    }

    private static boolean isAssociatedIdentity(Association association, PersistentProperty property) {
        try {
            return association.getAssociatedEntity().getIdentity().equals(property);
        } catch (Exception _) {
            // Best-effort identity access check
            return false;
        }
    }
}
