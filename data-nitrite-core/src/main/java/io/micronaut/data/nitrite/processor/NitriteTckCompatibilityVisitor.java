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
package io.micronaut.data.nitrite.processor;

import io.micronaut.core.annotation.Internal;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.annotation.Transient;
import io.micronaut.data.nitrite.annotation.NitriteRepository;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Supplies the Nitrite annotations missing from the released Jakarta Data TCK visitor.
 * @since 5.2.0
 */
@NullUnmarked
@Internal
public final class NitriteTckCompatibilityVisitor implements TypeElementVisitor<Object, Object> {

    private final boolean nitrite;

    public NitriteTckCompatibilityVisitor() {
        Properties properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/aprocessor.properties")) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException ignored) {
            // Ignore an unreadable optional TCK marker.
        }
        nitrite = "nitrite".equals(properties.getProperty("implementation"));
    }

    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public int getOrder() {
        return 89;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (nitrite && element.hasStereotype(Repository.class)) {
            element.annotate(NitriteRepository.class);
        }
    }

    @Override
    public void visitField(FieldElement element, VisitorContext context) {
        if (!nitrite) {
            return;
        }
        if (element.getOwningType().hasStereotype(Entity.class)) {
            element.annotate(Nullable.class);
        }
        if (element.hasStereotype(ElementCollection.class)) {
            element.annotate(Transient.class);
        }
    }
}
