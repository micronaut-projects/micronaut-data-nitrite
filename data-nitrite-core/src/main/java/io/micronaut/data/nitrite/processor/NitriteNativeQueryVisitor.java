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
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import org.jspecify.annotations.NullUnmarked;

import java.util.regex.Pattern;

/**
 * Runs Jakarta Persistence {@code @NativeQuery} methods as Jakarta Data queries.
 *
 * <p>Nitrite has no SQL engine. A native statement within the subset JDQL shares with SQL is rewritten
 * to JDQL: unnumbered {@code ?} parameters are numbered, {@code SELECT *} is dropped and
 * {@code COUNT(*)} becomes {@code COUNT(THIS)}. Column names must equal entity attribute names.
 * @since 5.2.0
 */
@NullUnmarked
@Internal
public final class NitriteNativeQueryVisitor implements TypeElementVisitor<Object, Object> {

    private static final String NATIVE_QUERY = "jakarta.persistence.query.NativeQuery";
    private static final String JAKARTA_QUERY = "jakarta.data.repository.Query";
    private static final Pattern SELECT_ALL = Pattern.compile("^\\s*SELECT\\s+\\*\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT_ALL = Pattern.compile("COUNT\\s*\\(\\s*\\*\\s*\\)", Pattern.CASE_INSENSITIVE);

    /**
     * Creates the visitor loaded by the annotation processor.
     */
    public NitriteNativeQueryVisitor() {
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
    public void visitMethod(MethodElement element, VisitorContext context) {
        if (element.hasAnnotation(JAKARTA_QUERY)) {
            return;
        }
        element.stringValue(NATIVE_QUERY)
            .map(NitriteNativeQueryVisitor::toJdql)
            .ifPresent(jdql -> element.annotate(JAKARTA_QUERY, builder -> builder.value(jdql)));
    }

    static String toJdql(String sql) {
        StringBuilder jdql = new StringBuilder(sql.length() + 8);
        int parameter = 0;
        boolean literal = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                literal = !literal;
            }
            boolean numbered = i + 1 < sql.length() && Character.isDigit(sql.charAt(i + 1));
            if (c == '?' && !literal && !numbered) {
                jdql.append('?').append(++parameter);
            } else {
                jdql.append(c);
            }
        }
        String counted = COUNT_ALL.matcher(jdql).replaceAll("COUNT(THIS)");
        return SELECT_ALL.matcher(counted).replaceFirst("");
    }
}
