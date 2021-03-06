/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.planner.iterative.rule;

import com.facebook.presto.Session;
import com.facebook.presto.sql.planner.DependencyExtractor;
import com.facebook.presto.sql.planner.ExpressionSymbolInliner;
import com.facebook.presto.sql.planner.PlanNodeIdAllocator;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.SymbolAllocator;
import com.facebook.presto.sql.planner.iterative.Lookup;
import com.facebook.presto.sql.planner.iterative.Pattern;
import com.facebook.presto.sql.planner.iterative.Rule;
import com.facebook.presto.sql.planner.plan.Assignments;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.ProjectNode;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.Literal;
import com.facebook.presto.sql.tree.TryExpression;
import com.facebook.presto.sql.util.AstUtils;
import com.google.common.collect.Sets;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toSet;

/**
 * Inlines expressions from a child project node into a parent project node
 * as long as they are simple constants, or they are referenced only once (to
 * avoid introducing duplicate computation) and the references don't appear
 * within a TRY block (to avoid changing semantics).
 */
public class InlineProjections
        implements Rule
{
    private static final Pattern PATTERN = Pattern.node(ProjectNode.class);

    @Override
    public Pattern getPattern()
    {
        return PATTERN;
    }

    @Override
    public Optional<PlanNode> apply(PlanNode node, Lookup lookup, PlanNodeIdAllocator idAllocator, SymbolAllocator symbolAllocator, Session session)
    {
        ProjectNode parent = (ProjectNode) node;

        PlanNode source = lookup.resolve(parent.getSource());
        if (!(source instanceof ProjectNode)) {
            return Optional.empty();
        }

        ProjectNode child = (ProjectNode) source;

        Sets.SetView<Symbol> targets = extractInliningTargets(parent, child);
        if (targets.isEmpty()) {
            return Optional.empty();
        }

        // inline the expressions
        Assignments assignments = child.getAssignments().filter(targets::contains);
        Map<Symbol, Expression> parentAssignments = parent.getAssignments()
                .entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> inlineReferences(entry.getValue(), assignments)));

        // Synthesize identity assignments for the inputs of expressions that were inlined
        // to place in the child projection.
        // If all assignments end up becoming identity assignments, they'll get pruned by
        // other rules
        Set<Symbol> inputs = child.getAssignments()
                .entrySet().stream()
                .filter(entry -> targets.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .flatMap(entry -> DependencyExtractor.extractAll(entry).stream())
                .collect(toSet());

        Assignments.Builder childAssignments = Assignments.builder();
        for (Map.Entry<Symbol, Expression> assignment : child.getAssignments().entrySet()) {
            if (!targets.contains(assignment.getKey())) {
                childAssignments.put(assignment);
            }
        }
        for (Symbol input : inputs) {
            childAssignments.putIdentity(input);
        }

        return Optional.of(
                new ProjectNode(
                        parent.getId(),
                        new ProjectNode(
                                child.getId(),
                                child.getSource(),
                                childAssignments.build()),
                        Assignments.copyOf(parentAssignments)));
    }

    private Expression inlineReferences(Expression expression, Assignments assignments)
    {
        Function<Symbol, Expression> mapping = symbol -> {
            Expression result = assignments.get(symbol);
            if (result != null) {
                return result;
            }

            return symbol.toSymbolReference();
        };

        return new ExpressionSymbolInliner(mapping).rewrite(expression);
    }

    private Sets.SetView<Symbol> extractInliningTargets(ProjectNode parent, ProjectNode child)
    {
        // candidates for inlining are
        //   1. references to simple constants
        //   2. references to complex expressions that
        //      a. are not inputs to try() expressions
        //      b. appear only once across all expressions
        //      c. are not identity projections

        Map<Symbol, Long> dependencies = parent.getAssignments()
                .getExpressions().stream()
                .flatMap(expression -> DependencyExtractor.extractAll(expression).stream())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        // find references to simple constants
        Set<Symbol> constants = dependencies.keySet().stream()
                .filter(input -> child.getAssignments().get(input) instanceof Literal)
                .collect(toSet());

        // exclude any complex inputs to TRY expressions. Inlining them would potentially
        // change the semantics of those expressions
        Set<Symbol> tryArguments = parent.getAssignments()
                .getExpressions().stream()
                .flatMap(expression -> extractTryArguments(expression).stream())
                .collect(toSet());

        Set<Symbol> singletons = dependencies.entrySet().stream()
                .filter(entry -> entry.getValue() == 1) // reference appears just once across all expressions in parent project node
                .filter(entry -> !tryArguments.contains(entry.getKey())) // they are not inputs to TRY. Otherwise, inlining might change semantics
                .filter(entry -> !child.getAssignments().isIdentity(entry.getKey())) // skip identities, otherwise, this rule will keep firing forever
                .map(Map.Entry::getKey)
                .collect(toSet());

        return Sets.union(singletons, constants);
    }

    private Set<Symbol> extractTryArguments(Expression expression)
    {
        return AstUtils.preOrder(expression)
                .filter(TryExpression.class::isInstance)
                .map(TryExpression.class::cast)
                .flatMap(tryExpression -> DependencyExtractor.extractAll(tryExpression).stream())
                .collect(toSet());
    }
}
