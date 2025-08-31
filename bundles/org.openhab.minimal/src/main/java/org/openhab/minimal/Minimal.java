package org.openhab.minimal;

import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.osgi.framework.BundleContext;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is a minimal OpenHAB bundle
 *
 */
@Component(service = { Minimal.class }, configurationPid = "minimal")
@NonNullByDefault
public class Minimal {

    private static final Logger logger = LoggerFactory.getLogger(Minimal.class);

    private final BundleWiring bundleWiring;
    private final BundleContext bundleContext;

    @Activate
    public Minimal(BundleContext bundleContext, Map<String, Object> properties) {

        this.bundleContext = bundleContext;
        this.bundleWiring = bundleContext.getBundle().adapt(BundleWiring.class);
        logger.warn("Bundle activated");
    }

    @Modified
    protected void modified(Map<String, Object> properties) {
        logger.warn("Configuration modified");
    }

    @Deactivate
    public void deactivate() {
        logger.warn("Bundle unloaded");
    }
}
