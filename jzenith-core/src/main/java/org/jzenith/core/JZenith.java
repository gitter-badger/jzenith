/**
 * Copyright © 2018 Marcus Thiesen (marcus@thiesen.org)
 *
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
package org.jzenith.core;

import com.englishtown.vertx.guice.GuiceVerticleFactory;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.multibindings.Multibinder;
import io.vertx.core.Vertx;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.logging.SLF4JLogDelegateFactory;
import lombok.NonNull;
import one.util.streamex.StreamEx;
import org.apache.logging.log4j.core.async.AsyncLoggerContextSelector;
import org.apache.logging.log4j.core.util.Constants;
import org.jzenith.core.configuration.ExtraConfiguration;
import org.jzenith.core.health.HealthCheck;
import org.jzenith.core.metrics.JZenithDefaultExports;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class JZenith {

    static {
        System.setProperty(Constants.LOG4J_CONTEXT_SELECTOR, AsyncLoggerContextSelector.class.getName());
        System.setProperty(LoggerFactory.LOGGER_DELEGATE_FACTORY_CLASS_NAME, SLF4JLogDelegateFactory.class.getName());
        JZenithDefaultExports.initialize();
    }

    // Manually (not Lombok) after static block to ensure that the property for the context selector has been set.
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JZenith.class);

    private final LinkedList<AbstractPlugin> plugins = Lists.newLinkedList();
    private final LinkedList<Module> modules = Lists.newLinkedList();
    private final Map<String, String> extraConfiguration = Maps.newHashMap();

    private final CoreConfiguration configuration;

    private JZenith(CoreConfiguration configuration) {
        this.configuration = configuration;
    }

    public static JZenith application(@NonNull String... args) {
        //Thread.currentThread().setUncaughtExceptionHandler((thread, throwable) -> log.error("Uncaught exception", throwable));
        return new JZenith(() -> Arrays.asList(args));
    }

    public JZenith withPlugins(@NonNull AbstractPlugin... modules) {
        Preconditions.checkArgument(modules.length > 0, "You need to provide a module");

        this.plugins.addAll(Arrays.asList(modules));

        return this;
    }

    public void run() {
        final Stopwatch stopwatch = Stopwatch.createStarted();
        if (log.isDebugEnabled()) {
            log.debug("jZenith starting up");
        }

        final Vertx vertx = Vertx.vertx();
        final Injector injector = createInjector(vertx);
        StreamEx.of(vertx.verticleFactories())
                .select(GuiceVerticleFactory.class)
                .findFirst()
                .ifPresent(guiceVerticleFactory -> guiceVerticleFactory.setInjector(injector));

        final CompletableFuture[] deploymentResults = plugins.stream()
                .map(plugin -> plugin.start(injector))
                .toArray(CompletableFuture[]::new);

        try {
            CompletableFuture.allOf(deploymentResults)
                    .get();
        } catch (Exception e) {
            vertx.close();
            Throwables.throwIfUnchecked(e);
            throw new RuntimeException(e);
        }

        log.debug("jZenith startup complete after " + stopwatch);
    }

    public Injector createInjectorForTesting() {
        return createInjector(Vertx.vertx());
    }

    private Injector createInjector(Vertx vertx) {
        final Map<String,String> extraConfigurationCopy = ImmutableMap.copyOf(this.extraConfiguration);

        final List<Module> allModules = ImmutableList.<Module>builder()
                .add(new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(CoreConfiguration.class).toInstance(configuration);
                        bind(ExtraConfiguration.class).toInstance(key -> extraConfigurationCopy.get(key));
                        bind(Vertx.class).toInstance(vertx);
                        bind(io.vertx.reactivex.core.Vertx.class).toInstance(io.vertx.reactivex.core.Vertx.newInstance(vertx));

                        Multibinder.newSetBinder(binder(), HealthCheck.class);
                    }
                })
                .addAll(plugins.stream().flatMap(plugins -> plugins.getModules().stream()).collect(ImmutableList.toImmutableList()))
                .addAll(modules)
                .build();

        return Guice.createInjector(allModules);
    }

    @SafeVarargs
    public final JZenith withModules(AbstractModule... modules) {
        this.modules.addAll(Arrays.asList(modules));

        return this;
    }

    public JZenith withConfiguration(String name, String value) {
        this.extraConfiguration.put(name, value);

        return this;
    }
}
