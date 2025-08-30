/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.automation.java223light.internal;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.automation.java223light.common.BindingInjector;
import org.openhab.automation.java223light.common.Java223Exception;
import org.openhab.automation.java223light.internal.strategy.Java223Strategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.obermuhlner.scriptengine.java.JavaCompiledScript;
import ch.obermuhlner.scriptengine.java.JavaScriptEngine;
import ch.obermuhlner.scriptengine.java.execution.DefaultExecutionStrategy;

/**
 * Custom java compiled script instance wrapping additional information
 *
 * @author Gwendal Roulleau - Initial contribution
 */
@NonNullByDefault
public class Java223CompiledScript extends JavaCompiledScript {

    // overwrite compiledInstance from super class
    /**
     * Write access mandatory for setting instance after creation.
     */
    @Nullable
    private Object java223CompiledInstance;

    private Class<?> java223CompiledClass;
    private final Logger logger = LoggerFactory.getLogger(Java223CompiledScript.class);

    /**
     * Hold the script source if, and only if, the script should be recompiled the next time it is needed
     */
    @Nullable
    private String recompileScriptSource = null;

    private final Java223Strategy java223Strategy;

    /**
     * Construct a {@link JavaCompiledScript}.
     *
     * @param engine the {@link JavaScriptEngine} that compiled this script
     * @param compiledClass the compiled {@link Class}
     * @param java223Strategy the {@link Java223Strategy}
     */
    public Java223CompiledScript(JavaScriptEngine engine, Class<?> compiledClass, Java223Strategy java223Strategy) {
        super(engine, compiledClass, null, new DefaultExecutionStrategy(compiledClass), java223Strategy);
        this.java223CompiledClass = compiledClass;
        this.java223Strategy = java223Strategy;
    }

    @Override
    public synchronized Class<?> getCompiledClass() {
        try {
            return getCompiledClassSafe();
        } catch (ScriptException e) {
            throw new Java223Exception("Cannot recompile class", e);
        }
    }

    /**
     * Get the class, possibly recompiling it if necessary
     * 
     * @return The compiled class
     * @throws ScriptException Only when the script should be recompiled and there is an error during it.
     */
    public synchronized Class<?> getCompiledClassSafe() throws ScriptException {
        Class<?> localCompiledClass = java223CompiledClass;
        String localRecompileScriptSource = recompileScriptSource;
        if (localRecompileScriptSource != null) { // a recompilation has been asked
            this.java223CompiledInstance = null;
            localCompiledClass = ((Java223ScriptEngine) getEngine()).internalCompilation(localRecompileScriptSource);
            this.java223CompiledClass = localCompiledClass;
            this.recompileScriptSource = null;
        }
        return localCompiledClass;
    }

    @Override
    public @Nullable Object eval(@Nullable ScriptContext context) throws ScriptException {

        // prepare bindings data
        if (context == null) {
            throw new IllegalArgumentException("ScriptContext must not be null");
        }
        Bindings globalBindings = context.getBindings(ScriptContext.GLOBAL_SCOPE);
        Bindings engineBindings = context.getBindings(ScriptContext.ENGINE_SCOPE);
        Map<String, Object> mergedBindings = new HashMap<>();
        if (globalBindings != null) {
            mergedBindings.putAll(globalBindings);
        }
        if (engineBindings != null) {
            mergedBindings.putAll(engineBindings);
        }
        java223Strategy.associateBindings(null, null, mergedBindings);

        // instantiate the script
        Object compiledInstance = java223Strategy.construct(this, mergedBindings);

        Class<?> compiledClass = compiledInstance.getClass();

        // inject bindings data in the script
        ClassLoader classLoader = compiledClass.getClassLoader();
        if (classLoader == null) { // should not happen
            throw new Java223Exception("Cannot get the classloader of " + compiledClass.getName());
        }
        BindingInjector.injectBindingsInto(classLoader, mergedBindings, compiledInstance);

        // find methods to execute
        Optional<Object> returned = null;
        for (Method method : compiledInstance.getClass().getMethods()) {
            // methods with a special name
            if (method.getName().equals("main")) {
                try {
                    Object[] parameterValues = BindingInjector.getParameterValuesFor(classLoader, method,
                            mergedBindings, null);
                    var returnedLocal = method.invoke(compiledInstance, parameterValues);
                    // keep arbitrarily only the first returned value
                    if (returned == null || returned.isEmpty()) {
                        if (returnedLocal != null) {
                            returned = Optional.of(returnedLocal);
                        } else {
                            returned = Optional.empty();
                        }
                    }
                } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException
                        | InstantiationException e) {
                    String simpleName = compiledInstance.getClass().getSimpleName();
                    logger.error("Error executing entry point {} in {}", method.getName(), simpleName, e);
                    throw new ScriptException(String.format("Error executing entry point %s in %s, exception %s",
                            method.getName(), simpleName, e.getMessage()));
                }
            }
        }

        // return if there was at least one execution
        if (returned != null) {
            return returned.orElse(null);
        }

        throw new ScriptException(
                String.format("cannot execute: %s doesn't have a method named main", compiledClass.getSimpleName()));
    }

    @Override
    public @Nullable Object getCompiledInstance() {
        return java223CompiledInstance;
    }

    public void invalidate(String scriptSource) {
        this.recompileScriptSource = scriptSource;
    }

    public void setCompiledInStance(Object compiledInstance) {
        this.java223CompiledInstance = compiledInstance;
    }
}
