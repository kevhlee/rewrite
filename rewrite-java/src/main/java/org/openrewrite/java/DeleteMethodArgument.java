/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java;

import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.java.search.UsesMethod;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.Collections.singletonList;
import static org.openrewrite.Tree.randomId;

/**
 * This recipe finds method invocations matching a method pattern and uses a zero-based argument index to determine
 * which argument is removed.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class DeleteMethodArgument extends Recipe {

    /**
     * A method pattern that is used to find matching method invocations.
     * See {@link  MethodMatcher} for details on the expression's syntax.
     */
    @Option(displayName = "Method pattern",
            description = MethodMatcher.METHOD_PATTERN_DESCRIPTION,
            example = "com.yourorg.A foo(int, int)")
    String methodPattern;

    /**
     * A zero-based index that indicates which argument will be removed from the method invocation.
     */
    @Option(displayName = "Argument index",
            description = "A zero-based index that indicates which argument will be removed from the method invocation.",
            example = "0")
    int argumentIndex;

    @Override
    public String getInstanceNameSuffix() {
        return String.format("%d in methods `%s`", argumentIndex, methodPattern);
    }

    @Override
    public String getDisplayName() {
        return "Delete method argument";
    }

    @Override
    public String getDescription() {
        return "Delete an argument from method invocations.";
    }

    @Override
    public Validated<Object> validate() {
        return super.validate().and(MethodMatcher.validate(methodPattern));
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        MethodMatcher methodMatcher = new MethodMatcher(methodPattern);
        return Preconditions.check(new UsesMethod<>(methodMatcher), new DeleteMethodArgumentVisitor(methodMatcher));
    }

    @RequiredArgsConstructor
    private class DeleteMethodArgumentVisitor extends JavaIsoVisitor<ExecutionContext> {
        private final MethodMatcher methodMatcher;

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
            return (J.MethodInvocation) visitMethodCall(m);
        }

        @Override
        public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
            J.NewClass n = super.visitNewClass(newClass, ctx);
            return (J.NewClass) visitMethodCall(n);
        }

        private MethodCall visitMethodCall(MethodCall methodCall) {
            MethodCall m = methodCall;
            List<Expression> originalArgs = m.getArguments();
            if (methodMatcher.matches(m) && originalArgs.stream()
                    .filter(a -> !(a instanceof J.Empty))
                    .count() >= argumentIndex + 1) {
                List<Expression> args = new ArrayList<>(originalArgs);

                Expression removed = args.remove(argumentIndex);
                if (args.isEmpty()) {
                    args = singletonList(new J.Empty(randomId(), Space.EMPTY, Markers.EMPTY));
                } else if (argumentIndex == 0) {
                    args.set(0, args.get(0).withPrefix(removed.getPrefix()));
                }
                m = m.withArguments(args);

                // Remove imports of types used in the removed argument
                new JavaIsoVisitor<Set<String>>() {
                    @Override
                    public @Nullable JavaType visitType(@Nullable JavaType javaType, Set<String> types) {
                        if (javaType instanceof JavaType.Class) {
                            JavaType.Class type = (JavaType.Class) javaType;
                            if (!"java.lang".equals(type.getPackageName())) {
                                types.add(type.getFullyQualifiedName());
                            }
                        } else if (javaType instanceof JavaType.Variable) {
                            JavaType.Variable variable = (JavaType.Variable) javaType;
                            if (variable.hasFlags(Flag.Static) && variable.getOwner() instanceof JavaType.Class) {
                                JavaType.Class owner = (JavaType.Class) variable.getOwner();
                                types.add(owner.getFullyQualifiedName() + "." + variable.getName());
                            }
                        }
                        return super.visitType(javaType, types);
                    }

                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Set<String> strings) {
                        if (mi.getMethodType() != null && mi.getMethodType().hasFlags(Flag.Static) && mi.getSelect() == null) {
                            JavaType.FullyQualified receiverType = mi.getMethodType().getDeclaringType();
                            strings.add(receiverType.getFullyQualifiedName() + "." + mi.getSimpleName());
                        }
                        return super.visitMethodInvocation(mi, strings);
                    }
                }.reduce(removed, new HashSet<>()).forEach(this::maybeRemoveImport);

                // Update the method types
                JavaType.Method methodType = m.getMethodType();
                if (methodType != null) {
                    List<String> parameterNames = new ArrayList<>(methodType.getParameterNames());
                    parameterNames.remove(argumentIndex);
                    List<JavaType> parameterTypes = new ArrayList<>(methodType.getParameterTypes());
                    parameterTypes.remove(argumentIndex);

                    m = m.withMethodType(methodType
                            .withParameterNames(parameterNames)
                            .withParameterTypes(parameterTypes));
                    if (m instanceof J.MethodInvocation && ((J.MethodInvocation) m).getName().getType() != null) {
                        m = ((J.MethodInvocation) m).withName(((J.MethodInvocation) m).getName().withType(m.getMethodType()));
                    }
                }
            }
            return m;
        }
    }
}
