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
package io.micronaut.data.nitrite.runtime.query;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.Sort;
import io.micronaut.data.model.runtime.RuntimePersistentEntity;
import io.micronaut.data.nitrite.model.query.NitriteInternalKeys;
import io.micronaut.data.nitrite.runtime.mapping.NitriteEntityMapper;
import org.dizitart.no2.filters.Filter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.BETWEEN;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.DIVIDE;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.EQ;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.EXPR;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.GT;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.GTE;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.IN;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.INC;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.LIKE;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.LT;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.LTE;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.MUL;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.MULTIPLY;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.NE;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.NIN;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.NOT;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.NOT_NULL;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.NULL;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.SET;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.STR_LEN_CP;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.TO_LOWER;
import static io.micronaut.data.nitrite.model.query.NitriteQueryOperators.VALUE;

/**
 * Parses the SQL-shaped query string that reaches the Nitrite runtime when a query was not built
 * as a Nitrite JSON filter, for example an update declared through {@code @Query} in JPQL form.
 *
 * <p>Predicates are translated to the module's native field filters where possible. Computed
 * comparisons such as {@code LOWER(name) = LOWER(:p1)}, {@code LENGTH(name) = :p1}, and
 * field-to-field comparisons are routed through the module's expression evaluator. Anything else
 * is rejected with a message naming the offending fragment, so an unsupported query fails loudly
 * instead of silently matching or updating the wrong documents.
 *
 * @since 5.2.0
 */
@Internal
public final class GeneratedQueryParser {

    private static final String WHERE_KEYWORD = " WHERE ";
    private static final String SET_KEYWORD = " SET ";
    private static final String SELECT_KEYWORD = "SELECT ";
    private static final String FROM_KEYWORD = " FROM ";
    private static final String PARAMETER_PREFIX = ":p";
    private static final String ORDER_BY_KEYWORD = " ORDER BY ";
    private static final String AND_KEYWORD = " AND ";
    private static final String EXPRESSION_KIND = "expression";

    /**
     * Clauses a generated {@code SELECT} may append after its {@code WHERE} clause. Ordering and
     * grouping are applied by the caller through Nitrite's {@code FindOptions}, so they must be
     * cut off the predicate text rather than parsed as part of it — leaving them attached makes
     * the trailing operand of the last comparison read as {@code :p1 ORDER BY title ASC}.
     */
    private static final List<String> TRAILING_CLAUSE_KEYWORDS = List.of(ORDER_BY_KEYWORD, " GROUP BY ", " HAVING ");

    private static final String DESCENDING = "DESC";
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private static final Pattern COMPARISON_PATTERN = Pattern.compile(
        "([\\w.]++)\\s*+(=|<>|!=|>=|<=|>|<)\\s*+(.+)", Pattern.DOTALL);
    private static final Pattern NULL_PATTERN = Pattern.compile(
        "([\\w.]++)\\s++IS\\s++(NOT\\s++)?NULL", Pattern.CASE_INSENSITIVE);
    private static final Pattern IN_PATTERN = Pattern.compile(
        "([\\w.]++)\\s++(NOT\\s++)?IN\\s*+\\((.+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIKE_PATTERN = Pattern.compile(
        "([\\w.]++)\\s++(NOT\\s++)?LIKE\\s++(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BETWEEN_PATTERN = Pattern.compile(
        "([\\w.]++)\\s++BETWEEN\\s++(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BETWEEN_LOWER_BOUND_PATTERN = Pattern.compile(
        "[\\w.]++\\s++BETWEEN\\s++[^\\s]++", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASSIGNMENT_PATTERN = Pattern.compile(
        "([\\w.]++)\\s*+=\\s*+(.+)", Pattern.DOTALL);
    private static final Pattern UPDATE_EXPRESSION_PATTERN = Pattern.compile(
        "([\\w.]++)\\s*+([+\\-*/])\\s*+(.+)", Pattern.DOTALL);
    private static final Pattern FUNCTION_PATTERN = Pattern.compile(
        "([a-z]+)\\((.*)\\)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PROJECTION_ALIAS_PATTERN = Pattern.compile("\\w++");
    private static final Pattern FIELD_PATTERN = Pattern.compile("[\\w.]+");

    /** The compiled form of a query carrying no {@code WHERE} clause. */
    private static final CompiledPredicate NO_PREDICATE = params -> null;

    private final LeafFilterFactory leafFilterFactory;
    private final @Nullable PredicateFilterFactory predicateFilterFactory;

    /**
     * Predicates compiled from a query string, keyed by entity and query text.
     *
     * <p>A generated query's text is fixed for the life of its {@code StoredQuery}; only its bound
     * parameters change. Keyword scanning, conjunction splitting and every regex therefore run once
     * per distinct query rather than once per execution, matching how the JSON path compiles a
     * {@code CompiledNitriteFilter} once and binds it per call.
     *
     * <p>The cache is bounded by the number of distinct repository queries in the application,
     * which is fixed at compile time.
     */
    private final Map<String, CompiledPredicate> compiledWhere = new ConcurrentHashMap<>();

    /**
     * Create a new parser.
     *
     * @param leafFilterFactory builds the filter for a single resolved predicate
     */
    public GeneratedQueryParser(LeafFilterFactory leafFilterFactory) {
        this(leafFilterFactory, null);
    }

    /**
     * Create a parser that can also execute computed predicates.
     *
     * @param leafFilterFactory builds a filter for a field predicate
     * @param predicateFilterFactory builds a filter for a computed predicate map
     */
    public GeneratedQueryParser(LeafFilterFactory leafFilterFactory,
                                @Nullable PredicateFilterFactory predicateFilterFactory) {
        this.leafFilterFactory = leafFilterFactory;
        this.predicateFilterFactory = predicateFilterFactory;
    }

    /**
     * Parses the {@code WHERE} clause of a generated query.
     *
     * <p>Any {@code ORDER BY}, {@code GROUP BY} or {@code HAVING} clause following the predicate is
     * discarded: those are applied through {@code FindOptions} by the caller, not through the filter.
     *
     * @param query  the full query string
     * @param entity the root entity
     * @param params the positional parameter values
     * @return the filter, or {@code null} when the query carries no {@code WHERE} clause
     */
    public @Nullable Filter parseWhere(String query, RuntimePersistentEntity<?> entity, Object[] params) {
        return compileWhere(query, entity).bind(params);
    }

    /**
     * Parses the {@code WHERE} clause once and returns it ready to bind. A query text that cannot be
     * parsed throws here rather than being cached, so the failure is reported on every execution as
     * it was before.
     *
     * @param query  the full query string
     * @param entity the root entity
     * @return the compiled predicate, or {@link #NO_PREDICATE} when there is no {@code WHERE} clause
     */
    private CompiledPredicate compileWhere(String query, RuntimePersistentEntity<?> entity) {
        return compiledWhere.computeIfAbsent(entity.getName() + '\u0000' + query, key -> {
            int whereStart = indexOfKeyword(query, WHERE_KEYWORD);
            if (whereStart < 0) {
                return NO_PREDICATE;
            }
            return compilePredicate(
                stripTrailingClauses(query.substring(whereStart + WHERE_KEYWORD.length())), entity);
        });
    }

    /**
     * Truncates the predicate text at the first {@link #TRAILING_CLAUSE_KEYWORDS} keyword that
     * appears outside a quoted literal.
     *
     * @param predicate the text following the {@code WHERE} keyword
     * @return the predicate with any trailing clause removed
     */
    private static String stripTrailingClauses(String predicate) {
        int end = predicate.length();
        for (String keyword : TRAILING_CLAUSE_KEYWORDS) {
            int index = indexOfKeyword(predicate, keyword);
            if (index >= 0 && index < end) {
                end = index;
            }
        }
        return predicate.substring(0, end);
    }

    /**
     * Parses the {@code ORDER BY} clause of a generated query into a {@link Sort}, so a sorted
     * derived finder orders its results even when its query was built as SQL rather than as a
     * Nitrite JSON filter carrying a {@code $sort} key.
     *
     * <p>Property names are returned as written, minus the query alias; the caller resolves them
     * against the entity when it builds the find options.
     *
     * @param query the full query string
     * @return the sort, or {@code null} when the query carries no {@code ORDER BY} clause
     */
    public static @Nullable Sort parseOrderBy(String query) {
        int start = indexOfKeyword(query, ORDER_BY_KEYWORD);
        if (start < 0) {
            return null;
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (String item : splitTopLevel(query.substring(start + ORDER_BY_KEYWORD.length()), ",")) {
            // splitAsStream rather than split: the latter's trailing-empty-token handling
            // depends on the input, which is what makes String/Pattern split surprising.
            String[] parts = WHITESPACE_PATTERN.splitAsStream(item.trim()).toArray(String[]::new);
            if (parts.length == 0 || parts[0].isEmpty()) {
                continue;
            }
            String property = stripAlias(parts[0]);
            orders.add(parts.length > 1 && DESCENDING.equalsIgnoreCase(parts[1])
                ? Sort.Order.desc(property)
                : Sort.Order.asc(property));
        }
        return orders.isEmpty() ? null : Sort.of(orders);
    }

    /**
     * Parses the property fields selected by a generated SQL-shaped query.
     *
     * @param query the full query string
     * @return selected property fields, or an empty list for an entity/count/constant selection
     */
    public static List<String> parseProjectionFields(String query) {
        int selectStart = indexOfKeyword(query, SELECT_KEYWORD);
        int fromStart = indexOfKeyword(query, FROM_KEYWORD);
        if (selectStart < 0 || fromStart < 0 || fromStart <= selectStart) {
            return List.of();
        }
        String clause = query.substring(selectStart + SELECT_KEYWORD.length(), fromStart).trim();
        if (clause.regionMatches(true, 0, "DISTINCT ", 0, 9)) {
            clause = clause.substring(9).trim();
        }
        List<String> fields = new ArrayList<>();
        for (String selection : splitTopLevel(clause, ",")) {
            String expression = stripProjectionAlias(selection);
            if (!FIELD_PATTERN.matcher(expression).matches() || expression.endsWith("_")) {
                return List.of();
            }
            fields.add(stripAlias(expression));
        }
        return fields;
    }

    private static String stripProjectionAlias(String selection) {
        String expression = selection.trim();
        String[] parts = WHITESPACE_PATTERN.splitAsStream(expression).toArray(String[]::new);
        return parts.length == 3 && "AS".equalsIgnoreCase(parts[1]) && PROJECTION_ALIAS_PATTERN.matcher(parts[2]).matches()
            ? parts[0] : expression;
    }

    /**
     * Whether a generated query selects a count rather than entity rows.
     *
     * @param query the full query string
     * @return true for a {@code SELECT COUNT(...)} query
     */
    public static boolean isCountProjection(String query) {
        int selectStart = indexOfKeyword(query, SELECT_KEYWORD);
        int fromStart = indexOfKeyword(query, FROM_KEYWORD);
        if (selectStart < 0 || fromStart < 0 || fromStart <= selectStart) {
            return false;
        }
        String selection = query.substring(selectStart + SELECT_KEYWORD.length(), fromStart).trim();
        return selection.regionMatches(true, 0, "COUNT(", 0, 6) && selection.endsWith(")");
    }

    /**
     * Parses the {@code SET} clause of a generated update.
     *
     * @param query  the full query string
     * @param entity the root entity
     * @param params the positional parameter values
     * @return the assignments, keyed by persisted field path
     */
    public Map<String, Object> parseSet(String query, RuntimePersistentEntity<?> entity, Object[] params) {
        int setStart = indexOfKeyword(query, SET_KEYWORD);
        if (setStart < 0) {
            return Map.of();
        }
        int whereStart = indexOfKeyword(query, WHERE_KEYWORD);
        String clause = query.substring(setStart + SET_KEYWORD.length(),
            whereStart < 0 ? query.length() : whereStart);

        Map<String, Object> assignments = new LinkedHashMap<>();
        for (String assignment : splitTopLevel(clause, ",")) {
            Matcher matcher = ASSIGNMENT_PATTERN.matcher(assignment.trim());
            if (!matcher.matches()) {
                throw unsupported(assignment.trim(), "assignment");
            }
            String field = resolveField(matcher.group(1), entity);
            assignments.put(field, resolveOperand(matcher.group(2), params, assignment.trim()));
        }
        return assignments;
    }

    /**
     * Parses a generated update into the update operators understood by the Nitrite executor.
     * Self-referential arithmetic assignments are retained as increment/multiply operations so
     * their value is computed from each stored document rather than once up front.
     *
     * @param query the full update query
     * @param entity the root entity
     * @param params the positional parameter values
     * @return update operations keyed by Nitrite operator
     */
    public Map<String, Object> parseUpdate(String query, RuntimePersistentEntity<?> entity, Object[] params) {
        int setStart = indexOfKeyword(query, SET_KEYWORD);
        if (setStart < 0) {
            return Map.of();
        }
        int whereStart = indexOfKeyword(query, WHERE_KEYWORD);
        String clause = query.substring(setStart + SET_KEYWORD.length(),
            whereStart < 0 ? query.length() : whereStart);
        Map<String, Map<String, Object>> operations = new LinkedHashMap<>();
        for (String assignment : splitTopLevel(clause, ",")) {
            Matcher matcher = ASSIGNMENT_PATTERN.matcher(assignment.trim());
            if (!matcher.matches()) {
                throw unsupported(assignment.trim(), "assignment");
            }
            String field = resolveField(matcher.group(1), entity);
            String operand = matcher.group(2).trim();
            Matcher update = UPDATE_EXPRESSION_PATTERN.matcher(stripOuterParentheses(operand));
            if (update.matches() && resolveField(update.group(1), entity).equals(field)) {
                String operator = switch (update.group(2)) {
                    case "+", "-" -> INC;
                    default -> MUL;
                };
                Object value = resolveUpdateValue(update, params, assignment.trim());
                operations.computeIfAbsent(operator, ignored -> new LinkedHashMap<>()).put(field, value);
            } else {
                operations.computeIfAbsent(SET, ignored -> new LinkedHashMap<>())
                    .put(field, resolveOperand(operand, params, assignment.trim()));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.putAll(operations);
        return result;
    }

    private @Nullable Object resolveUpdateValue(Matcher update, Object[] params, String fragment) {
        Object value = resolveOperand(update.group(3), params, fragment);
        String operator = update.group(2);
        if ("-".equals(operator) || "/".equals(operator)) {
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put(VALUE, value);
            wrapped.put("-".equals(operator) ? NitriteInternalKeys.NEGATE : NitriteInternalKeys.RECIPROCATE, true);
            return wrapped;
        }
        return value;
    }

    /**
     * Parses one predicate into a form that still needs its parameters. Every decision that depends
     * only on the query text - the splitting, the regex match, the operator, the field reference -
     * is made here; the returned {@link CompiledPredicate} does no more than resolve operands and
     * hand them to the {@link LeafFilterFactory}.
     *
     * @param expression the predicate text
     * @param entity     the root entity
     * @return the compiled predicate
     */
    private CompiledFilter compilePredicate(String expression, RuntimePersistentEntity<?> entity) {
        String predicate = stripOuterParentheses(expression.trim());

        if (predicate.regionMatches(true, 0, "NOT(", 0, 4) && predicate.endsWith(")")) {
            CompiledFilter inner = compilePredicate(predicate.substring(4, predicate.length() - 1), entity);
            return params -> inner.bind(params).not();
        }

        List<String> operands = splitTopLevel(predicate, " OR ");
        if (operands.size() > 1) {
            List<CompiledFilter> parts = operands.stream()
                .map(part -> compilePredicate(part, entity))
                .toList();
            return params -> Filter.or(parts.stream().map(part -> part.bind(params)).toArray(Filter[]::new));
        }
        operands = splitConjunctions(predicate);
        if (operands.size() > 1) {
            List<CompiledFilter> parts = operands.stream()
                .map(part -> compilePredicate(part, entity))
                .toList();
            return params -> Filter.and(parts.stream().map(part -> part.bind(params)).toArray(Filter[]::new));
        }

        return compileLeafPredicate(predicate, entity);
    }

    private CompiledFilter compileLeafPredicate(String predicate, RuntimePersistentEntity<?> entity) {
        Matcher nullMatcher = NULL_PATTERN.matcher(predicate);
        if (nullMatcher.matches()) {
            String reference = nullMatcher.group(1);
            String operator = nullMatcher.group(2) == null ? NULL : NOT_NULL;
            return params -> leafFilter(entity, reference, predicate, operator, true);
        }

        Matcher betweenMatcher = BETWEEN_PATTERN.matcher(predicate);
        if (betweenMatcher.matches()) {
            return compileBetween(betweenMatcher, predicate, entity);
        }

        Matcher inMatcher = IN_PATTERN.matcher(predicate);
        if (inMatcher.matches()) {
            String reference = inMatcher.group(1);
            String operand = inMatcher.group(3);
            String operator = inMatcher.group(2) == null ? IN : NIN;
            return params -> leafFilter(entity, reference, predicate, operator,
                resolveOperandList(operand, params, predicate));
        }

        Matcher likeMatcher = LIKE_PATTERN.matcher(predicate);
        if (likeMatcher.matches()) {
            return compileLike(likeMatcher, predicate, entity);
        }

        Matcher matcher = COMPARISON_PATTERN.matcher(predicate);
        if (matcher.matches()) {
            String reference = matcher.group(1);
            String operand = matcher.group(3);
            String operator = comparisonOperator(matcher.group(2));
            if (isSimpleOperand(operand)) {
                return params -> leafFilter(entity, reference, predicate, operator,
                    resolveOperand(operand, params, predicate));
            }
        }

        Comparison comparison = splitComparison(predicate);
        if (comparison == null || predicateFilterFactory == null) {
            throw unsupported(predicate, "predicate");
        }
        Object left = compileExpressionOperand(comparison.left(), entity, predicate);
        Object right = compileExpressionOperand(comparison.right(), entity, predicate);
        Map<String, Object> expressionFilter = Map.of(
            EXPR, Map.of(comparisonOperator(comparison.operator()), List.of(left, right)));
        return params -> predicateFilterFactory.build(entity, expressionFilter, params);
    }

    private CompiledFilter compileBetween(Matcher matcher, String predicate, RuntimePersistentEntity<?> entity) {
        String reference = matcher.group(1);
        List<String> bounds = splitTopLevel(matcher.group(2), AND_KEYWORD);
        if (bounds.size() != 2) {
            throw unsupported(predicate, "BETWEEN bounds");
        }
        String lower = bounds.get(0);
        String upper = bounds.get(1);
        return params -> {
            List<Object> range = new ArrayList<>(2);
            range.add(resolveOperand(lower, params, predicate));
            range.add(resolveOperand(upper, params, predicate));
            return leafFilter(entity, reference, predicate, BETWEEN, range);
        };
    }

    private CompiledFilter compileLike(Matcher matcher, String predicate, RuntimePersistentEntity<?> entity) {
        String reference = matcher.group(1);
        String operand = matcher.group(3);
        boolean negated = matcher.group(2) != null;
        return params -> {
            Object pattern = resolveOperand(operand, params, predicate);
            return negated
                ? leafFilter(entity, reference, predicate, NOT, Map.of(LIKE, pattern))
                : leafFilter(entity, reference, predicate, LIKE, pattern);
        };
    }

    private static String comparisonOperator(String operator) {
        return switch (operator) {
            case "=" -> EQ;
            case "!=", "<>" -> NE;
            case ">" -> GT;
            case ">=" -> GTE;
            case "<" -> LT;
            default -> LTE;
        };
    }

    private static boolean isSimpleOperand(String operand) {
        String value = operand.trim();
        return value.startsWith(PARAMETER_PREFIX)
            || value.startsWith("'")
            || "NULL".equalsIgnoreCase(value)
            || "TRUE".equalsIgnoreCase(value)
            || "FALSE".equalsIgnoreCase(value)
            || value.matches("[-+]?\\d++(?:\\.\\d++)?");
    }

    private Object compileExpressionOperand(String operand, RuntimePersistentEntity<?> entity, String fragment) {
        String value = stripOuterParentheses(operand.trim());
        Matcher function = FUNCTION_PATTERN.matcher(value);
        if (function.matches()) {
            Object inner = compileExpressionOperand(function.group(2), entity, fragment);
            if ("LOWER".equalsIgnoreCase(function.group(1))) {
                return Map.of(TO_LOWER, inner);
            }
            if ("LENGTH".equalsIgnoreCase(function.group(1))) {
                return Map.of(STR_LEN_CP, inner);
            }
            throw unsupported(fragment, EXPRESSION_KIND);
        }
        BinaryExpression binary = splitBinaryExpression(value);
        if (binary != null) {
            Object left = compileExpressionOperand(binary.left(), entity, fragment);
            Object right = compileExpressionOperand(binary.right(), entity, fragment);
            return switch (binary.operator()) {
                case "*" -> Map.of(MULTIPLY, List.of(left, right));
                case "/" -> Map.of(DIVIDE, List.of(left, right));
                default -> throw unsupported(fragment, EXPRESSION_KIND);
            };
        }
        if (value.startsWith(PARAMETER_PREFIX)) {
            int position = parameterPosition(value, fragment);
            if (position >= 0) {
                return NitriteInternalKeys.QUERY_PARAMETER_PREFIX + position;
            }
            throw unsupported(fragment, "parameter reference");
        }
        if (isSimpleOperand(value)) {
            Object literal = resolveOperand(value, new Object[0], fragment);
            if (literal == null) {
                throw unsupported(fragment, EXPRESSION_KIND);
            }
            return literal;
        }
        if (FIELD_PATTERN.matcher(value).matches()) {
            return "$" + resolveField(value, entity);
        }
        throw unsupported(fragment, EXPRESSION_KIND);
    }

    private static @Nullable Comparison splitComparison(String expression) {
        for (String operator : List.of("!=", "<>", ">=", "<=", "=", ">", "<")) {
            int index = indexOfTopLevel(expression, operator);
            if (index >= 0) {
                return new Comparison(expression.substring(0, index).trim(), operator,
                    expression.substring(index + operator.length()).trim());
            }
        }
        return null;
    }

    private static @Nullable BinaryExpression splitBinaryExpression(String expression) {
        for (String operator : List.of("*", "/", "+", "-")) {
            int index = indexOfTopLevel(expression, operator);
            if (index > 0) {
                return new BinaryExpression(expression.substring(0, index).trim(), operator,
                    expression.substring(index + 1).trim());
            }
        }
        return null;
    }

    private static int indexOfTopLevel(String expression, String token) {
        int depth = 0;
        boolean[] inLiteral = {false};
        int i = 0;
        while (i <= expression.length() - token.length()) {
            i = skipLiterals(expression, i, inLiteral);
            if (i > expression.length() - token.length()) {
                break;
            }
            char ch = expression.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            } else if (depth == 0 && expression.startsWith(token, i)) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /**
     * Hands one resolved predicate to the {@link LeafFilterFactory} as the Nitrite operator object
     * the JSON path would have carried, so both paths resolve the field and coerce the value the
     * same way.
     *
     * @param entity    the root entity
     * @param reference the field reference as written in the query, alias included
     * @param predicate the predicate text, used for error reporting
     * @param operator  the Nitrite operator
     * @param value     the operator's value
     * @return the built filter
     */
    private Filter leafFilter(RuntimePersistentEntity<?> entity, String reference, String predicate,
                              String operator, @Nullable Object value) {
        Map<String, Object> operators = LinkedHashMap.newLinkedHashMap(1);
        operators.put(operator, value);
        Filter filter = leafFilterFactory.build(entity, stripAlias(reference, entity), operators);
        if (filter == null) {
            throw unsupported(predicate, "predicate");
        }
        return filter;
    }

    /**
     * Normalises an {@code IN} operand to a list, so a single bound value and a bound collection
     * reach the filter builder in the same shape.
     *
     * @param value the resolved operand
     * @return the operand as a list
     */
    private static List<?> toValueList(@Nullable Object value) {
        return switch (value) {
            case null -> List.of();
            case Collection<?> collection -> new ArrayList<>(collection);
            case Object[] array -> Arrays.asList(array);
            default -> List.of(value);
        };
    }

    private List<?> resolveOperandList(String operands, Object[] params, String fragment) {
        List<Object> values = new ArrayList<>();
        for (String operand : splitTopLevel(operands, ",")) {
            values.addAll(toValueList(resolveOperand(operand, params, fragment)));
        }
        return values;
    }

    /**
     * Resolves an operand that must be a bound positional parameter or a literal. An expression
     * such as {@code priority + 1} is rejected rather than dropped.
     */
    private @Nullable Object resolveOperand(String operand, Object[] params, String fragment) {
        String value = operand.trim();
        Matcher function = FUNCTION_PATTERN.matcher(value);
        if (function.matches() && "CONCAT".equalsIgnoreCase(function.group(1))) {
            return resolveConcatenation(function.group(2), params, fragment);
        }
        if (value.startsWith(PARAMETER_PREFIX)) {
            return resolveParameter(value, params, fragment);
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("\\'", "'").replace("''", "'");
        }
        if ("NULL".equalsIgnoreCase(value)) {
            return null;
        }
        if ("TRUE".equalsIgnoreCase(value) || "FALSE".equalsIgnoreCase(value)) {
            return Boolean.parseBoolean(value);
        }
        try {
            if (value.contains(".")) {
                return Double.valueOf(value);
            }
            return Long.valueOf(value);
        } catch (NumberFormatException _) {
            throw unsupported(fragment, "operand");
        }
    }

    private @Nullable String resolveConcatenation(String arguments, Object[] params, String fragment) {
        StringBuilder result = new StringBuilder();
        for (String argument : splitTopLevel(arguments, ",")) {
            Object resolved = resolveOperand(argument, params, fragment);
            if (resolved == null) {
                return null;
            }
            result.append(resolved);
        }
        return result.toString();
    }

    private static @Nullable Object resolveParameter(String value, Object[] params, String fragment) {
        int position = parameterPosition(value, fragment);
        if (position < 0 || position >= params.length) {
            throw new IllegalStateException("Missing generated parameter " + value + " for: " + fragment);
        }
        return params[position];
    }

    private static int parameterPosition(String value, String fragment) {
        String index = value.substring(PARAMETER_PREFIX.length());
        if (index.isEmpty() || !index.chars().allMatch(Character::isDigit)) {
            throw unsupported(fragment, "parameter reference");
        }
        return Integer.parseInt(index) - 1;
    }

    /**
     * Drops the query alias from a field reference. A generated query addresses its root as
     * {@code book_.title}; only the property path is meaningful to the filter builder.
     *
     * @param reference the field reference as written in the query
     * @param entity    the root entity
     * @return the property path
     */
    private static String stripAlias(String reference, RuntimePersistentEntity<?> entity) {
        int separator = reference.indexOf('.');
        if (separator < 0) {
            return reference;
        }
        String head = reference.substring(0, separator);
        return entity.getPropertyByName(head) == null ? reference.substring(separator + 1) : reference;
    }

    /**
     * Drops the query alias without entity metadata to check against. A generated alias is the
     * entity's simple name with a trailing underscore, a shape no persistent property has.
     *
     * @param reference the field reference as written in the query
     * @return the property path
     */
    private static String stripAlias(String reference) {
        int separator = reference.indexOf('.');
        if (separator < 0 || reference.charAt(separator - 1) != '_') {
            return reference;
        }
        return reference.substring(separator + 1);
    }

    /**
     * Resolves a possibly dotted field reference to the persisted document path. A leading segment
     * that is not a property of the entity is treated as a query alias and dropped; a segment that
     * is an association or embedded property keeps its place in the path under its persisted name.
     *
     * @param reference the field reference as written in the query
     * @param entity    the root entity
     * @return the persisted document path
     */
    private static String resolveField(String reference, RuntimePersistentEntity<?> entity) {
        return NitriteEntityMapper.persistedPath(stripAlias(reference, entity), entity);
    }

    private static UnsupportedOperationException unsupported(String fragment, String kind) {
        return new UnsupportedOperationException(
            "Nitrite cannot execute this query: the generated " + kind + " [" + fragment
                + "] is not one of the supported forms (comparison, LIKE, BETWEEN, IN, IS NULL)"
                + " against a bound parameter or literal. Express the query with an explicit"
                + " @Query JSON filter.");
    }

    /**
     * Advances past all characters belonging to quoted literals, including opening/closing
     * quotes and backslash escapes. Returns the index of the first character the caller
     * should process (which may equal {@code i} if no literal content was consumed, or
     * may exceed the string length if the remainder was all literal content).
     */
    private static int skipLiterals(String s, int i, boolean[] inLiteral) {
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '\\' && inLiteral[0] && i + 1 < s.length()) {
                i += 2;
            } else if (ch == '\'') {
                inLiteral[0] = !inLiteral[0];
                i++;
            } else if (!inLiteral[0]) {
                return i;
            } else {
                i++;
            }
        }
        return i;
    }

    /**
     * Finds a keyword outside of any quoted literal.
     */
    private static int indexOfKeyword(String query, String keyword) {
        boolean[] inLiteral = {false};
        int i = 0;
        while (i <= query.length() - keyword.length()) {
            i = skipLiterals(query, i, inLiteral);
            if (i > query.length() - keyword.length()) {
                break;
            }
            if (query.regionMatches(true, i, keyword, 0, keyword.length())) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /**
     * Splits conjunctions, leaving the {@code AND} that belongs to a {@code BETWEEN} range with
     * the predicate it bounds.
     */
    private static List<String> splitConjunctions(String expression) {
        List<String> parts = new ArrayList<>();
        for (String part : splitTopLevel(expression, AND_KEYWORD)) {
            if (!parts.isEmpty() && BETWEEN_LOWER_BOUND_PATTERN.matcher(parts.getLast()).matches()) {
                parts.set(parts.size() - 1, parts.getLast() + AND_KEYWORD + part);
            } else {
                parts.add(part);
            }
        }
        return parts.size() == 1 ? List.of(expression) : parts;
    }

    /**
     * Splits on a separator that appears at parenthesis depth zero and outside quoted literals.
     */
    private static List<String> splitTopLevel(String expression, String separator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean[] inLiteral = {false};
        int i = 0;
        while (i <= expression.length() - separator.length()) {
            i = skipLiterals(expression, i, inLiteral);
            if (i > expression.length() - separator.length()) {
                break;
            }
            char ch = expression.charAt(i);
            int next = i + 1;
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            } else if (depth == 0 && expression.regionMatches(true, i, separator, 0, separator.length())) {
                parts.add(expression.substring(start, i).trim());
                start = i + separator.length();
                next = start;
            }
            i = next;
        }
        if (!parts.isEmpty()) {
            parts.add(expression.substring(start).trim());
            return parts;
        }
        return List.of(expression);
    }

    private static String stripOuterParentheses(String expression) {
        String result = expression;
        while (result.startsWith("(") && result.endsWith(")") && enclosesWholeExpression(result)) {
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    private static boolean enclosesWholeExpression(String expression) {
        int depth = 0;
        int i = 0;
        boolean[] inLiteral = {false};
        while (i < expression.length()) {
            i = skipLiterals(expression, i, inLiteral);
            if (i >= expression.length()) {
                return true;
            }
            char ch = expression.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')' && --depth == 0 && i < expression.length() - 1) {
                return false;
            }
            i++;
        }
        return true;
    }

    /**
     * Builds the Nitrite filter for one leaf predicate.
     *
     * <p>Deliberately narrow: it exists so this parser never grows a second implementation of
     * field resolution. Association paths, embedded paths, identity aliasing and value coercion
     * all live in the module's JSON filter builder, and routing every leaf through this factory
     * keeps a SQL-shaped query and its JSON equivalent on exactly the same semantics.
     * @since 5.2.0
     */
    @Internal
    @FunctionalInterface
    public interface LeafFilterFactory {

        /**
         * Builds the filter for one field and its operators.
         *
         * @param entity       the entity the path is rooted at
         * @param propertyPath the property path, with any query alias already stripped
         * @param operators    the Nitrite operator object for the field, e.g. {@code {$gt: 10}}
         * @return the filter, or {@code null} when the path cannot be resolved
         */
        @Nullable Filter build(RuntimePersistentEntity<?> entity, String propertyPath, Map<String, Object> operators);
    }

    /**
     * Builds a computed predicate from the provider's expression-filter representation.
     * @since 5.2.0
     */
    @Internal
    @FunctionalInterface
    public interface PredicateFilterFactory {

        /**
         * Builds a filter from the predicate and its bound parameters.
         *
         * @param entity    the entity being queried
         * @param predicate the predicate representation
         * @param params    the bound query parameters
         * @return the resulting filter
         */
        Filter build(RuntimePersistentEntity<?> entity, Map<String, Object> predicate, Object[] params);
    }

    private record Comparison(String left, String operator, String right) {
    }

    private record BinaryExpression(String left, String operator, String right) {
    }
    /**
     * A predicate whose text has been parsed and whose parameters have not yet been bound.
     */
    @FunctionalInterface
    private interface CompiledPredicate {
        @Nullable Filter bind(Object[] params);
    }

    @FunctionalInterface
    private interface CompiledFilter extends CompiledPredicate {
        @Override
        Filter bind(Object[] params);
    }

}
