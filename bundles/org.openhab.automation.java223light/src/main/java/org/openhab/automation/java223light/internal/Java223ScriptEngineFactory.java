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

import static org.openhab.automation.java223light.common.Java223Constants.LIB_DIR;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.script.ScriptEngine;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.automation.java223light.common.Java223Constants;
import org.openhab.automation.java223light.common.Java223Exception;
import org.openhab.automation.java223light.internal.strategy.Java223Strategy;
import org.openhab.automation.java223light.internal.strategy.ScriptWrappingStrategy;
import org.openhab.core.automation.RuleManager;
import org.openhab.core.automation.module.script.ScriptEngineFactory;
import org.openhab.core.config.core.ConfigParser;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.items.MetadataRegistry;
import org.openhab.core.service.WatchService;
import org.openhab.core.thing.ThingManager;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingStatus;
import org.osgi.framework.BundleContext;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.obermuhlner.scriptengine.java.JavaScriptEngineFactory;
import ch.obermuhlner.scriptengine.java.compilation.ScriptInterceptorStrategy;
import ch.obermuhlner.scriptengine.java.packagelisting.PackageResourceListingStrategy;

/**
 * This is an implementation of a {@link ScriptEngineFactory} for Java
 *
 * @author Gwendal Roulleau - Initial contribution
 */
@Component(service = { ScriptEngineFactory.class,
        Java223ScriptEngineFactory.class }, configurationPid = "automation.java223light")
@NonNullByDefault
public class Java223ScriptEngineFactory extends JavaScriptEngineFactory implements ScriptEngineFactory {

    private static final Logger logger = LoggerFactory.getLogger(Java223ScriptEngineFactory.class);

    private final BundleWiring bundleWiring;
    private final BundleContext bundleContext;

    private final PackageResourceListingStrategy osgiPackageResourceListingStrategy;
    private final Java223Strategy java223Strategy;
    private final ScriptInterceptorStrategy scriptWrappingStrategy;
    private final Java223CompiledScriptCache compiledScriptCache;

    private final WatchService watchService;

    private static final Set<ThingStatus> INITIALIZED = Set.of(ThingStatus.ONLINE, ThingStatus.OFFLINE,
            ThingStatus.UNKNOWN);

    @Activate
    public Java223ScriptEngineFactory(BundleContext bundleContext, Map<String, Object> properties,
            @Reference(target = WatchService.CONFIG_WATCHER_FILTER) WatchService watchService,
            @Reference ItemRegistry itemRegistry, @Reference ThingRegistry thingRegistry) {

        try {
            Files.createDirectories(LIB_DIR);
        } catch (IOException e) {
            logger.warn("Failed to create directory '{}': {}", LIB_DIR, e.getMessage());
            throw new IllegalStateException("Failed to initialize lib folder.");
        }

        this.bundleContext = bundleContext;
        this.bundleWiring = bundleContext.getBundle().adapt(BundleWiring.class);

        String additionalBundlesConfig = ConfigParser
                .valueAsOrElse(properties.get("additionalBundles"), String.class, "").trim();
        String additionalClassesConfig = ConfigParser
                .valueAsOrElse(properties.get("additionalClasses"), String.class, "").trim();
        Integer scriptCacheSize = ConfigParser.valueAsOrElse(properties.get("scriptCacheSize"), Integer.class, 50);
        Boolean allowInstanceReuse = ConfigParser.valueAsOrElse(properties.get("allowInstanceReuse"), Boolean.class,
                false);

        osgiPackageResourceListingStrategy = this::listClassResources;
        java223Strategy = new Java223Strategy(getAdditionalBindings(),
                bundleContext.getBundle().adapt(BundleWiring.class).getClassLoader());
        java223Strategy.setAllowInstanceReuse(allowInstanceReuse);
        scriptWrappingStrategy = new ScriptWrappingStrategy();
        compiledScriptCache = new Java223CompiledScriptCache(scriptCacheSize);

        this.watchService = watchService;
        // first building of internal in memory lib representation
        java223Strategy.scanLibDirectory();
        // When a lib change, update internal lib storage
        watchService.registerListener(java223Strategy, LIB_DIR);
        // When a lib change, invalidate cache of compiled script
        watchService.registerListener(compiledScriptCache, LIB_DIR);

        logger.info("Bundle activated");
    }

    @Modified
    protected void modified(Map<String, Object> properties) {
        String additionalBundlesConfig = ConfigParser.valueAsOrElse(properties.get("additionalBundles"), String.class,
                "");
        String additionalClassesConfig = ConfigParser.valueAsOrElse(properties.get("additionalClasses"), String.class,
                "");
        Integer scriptCacheSize = ConfigParser.valueAsOrElse(properties.get("scriptCacheSize"), Integer.class, 50);
        Boolean allowInstanceReuse = ConfigParser.valueAsOrElse(properties.get("allowInstanceReuse"), Boolean.class,
                false);

        compiledScriptCache.setCacheSize(scriptCacheSize);
        java223Strategy.setAllowInstanceReuse(allowInstanceReuse);
        logger.debug("java223 configuration update received ({})", properties);
    }

    @Deactivate
    public void deactivate() {
        watchService.unregisterListener(java223Strategy);
        watchService.unregisterListener(compiledScriptCache);
    }

    @Override
    public List<String> getScriptTypes() {
        String[] types = { Java223Constants.JAVA_FILE_TYPE };
        return Arrays.asList(types);
    }

    @Override
    public void scopeValues(ScriptEngine scriptEngine, Map<String, Object> scopeValues) {
        for (Entry<String, Object> entry : scopeValues.entrySet()) {
            scriptEngine.put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public @Nullable ScriptEngine createScriptEngine(String scriptType) {
        if (getScriptTypes().contains(scriptType)) {
            return new Java223ScriptEngine(compiledScriptCache, java223Strategy, osgiPackageResourceListingStrategy,
                    scriptWrappingStrategy, Arrays.asList("-g", "-parameters"));
        }
        return null;
    }

    @Override
    public ScriptEngine getScriptEngine() {
        ScriptEngine scriptEngine = createScriptEngine(Java223Constants.JAVA_FILE_TYPE);
        if (scriptEngine == null) {
            throw new Java223Exception("Null script engine returned. Should not happened");
        }
        return scriptEngine;
    }

    /**
     * Additional data to put into bindings so the scripts could use them.
     *
     * @return Additional data to use when binding
     */
    private Map<String, Object> getAdditionalBindings() {
        RuleManager ruleManager = bundleContext.getService(bundleContext.getServiceReference(RuleManager.class));
        ThingManager thingManager = bundleContext.getService(bundleContext.getServiceReference(ThingManager.class));
        MetadataRegistry metadataRegistry = bundleContext
                .getService(bundleContext.getServiceReference(MetadataRegistry.class));
        return Map.of(Java223Constants.RULE_MANAGER, ruleManager, //
                Java223Constants.METADATA_REGISTRY, metadataRegistry, //
                Java223Constants.THING_MANAGER, thingManager);
    }

    private Collection<String> listClassResources(String packageName) {
        String path = packageName.replace(".", "/");
        path = "/" + path;

        return bundleWiring.listResources(path, "*.class", 0);
    }
}
