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

import java.nio.file.Path;

import javax.script.ScriptException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.service.WatchService;
import org.openhab.core.service.WatchService.Kind;

/**
 * This class caches compiled scripts
 *
 * @author Gwendal Roulleau - Initial contribution
 */
@NonNullByDefault
public class Java223CompiledScriptCache implements WatchService.WatchEventListener {

    public Java223CompiledScriptCache() {
        super();
    }

    public Java223CompiledScript getOrCompile(String script, Compiler compiler) throws ScriptException {
        return compiler.compile(script);
    }

    public interface Compiler {
        Java223CompiledScript compile(String script) throws ScriptException;
    }

    /**
     * If a change is detected somewhere in the libraries,
     * then we invalidate all cache
     */
    @Override
    public void processWatchEvent(Kind kind, Path path) {
    }
}
