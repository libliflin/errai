package org.jboss.errai.bus.server.service;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import org.jboss.errai.bus.client.MessageBus;
import org.jboss.errai.bus.client.MessageCallback;
import org.jboss.errai.bus.server.ErraiModule;
import org.jboss.errai.bus.server.Module;
import org.jboss.errai.bus.server.ServerMessageBus;
import org.jboss.errai.bus.server.annotations.ExtensionConfigurator;
import org.jboss.errai.bus.server.annotations.LoadModule;
import org.jboss.errai.bus.server.annotations.Service;
import org.jboss.errai.bus.server.annotations.security.RequireAuthentication;
import org.jboss.errai.bus.server.annotations.security.RequireRoles;
import org.jboss.errai.bus.server.ext.ErraiConfigExtension;
import org.jboss.errai.bus.server.security.auth.rules.RolesRequiredRule;
import org.jboss.errai.bus.server.util.ConfigUtil;
import org.jboss.errai.bus.server.util.ConfigVisitor;

import java.io.File;
import java.util.*;

public class ErraiServiceConfiguratorImpl implements ErraiServiceConfigurator {
    private ServerMessageBus bus;
    private ErraiModule module;
    private List<File> configRootTargets;
    private Set<String> loadedTargets;

    private ErraiServiceConfigurator configInst = this;

    @Inject
    public ErraiServiceConfiguratorImpl(ServerMessageBus bus, ErraiModule module) {
        this.bus = bus;
        this.module = module;
    }

    public void configure() {
        loadedTargets = new HashSet<String>();
        configRootTargets = ConfigUtil.findAllConfigTargets();

        ConfigUtil.visitAllTargets(configRootTargets,
                new ConfigVisitor() {
                    public void visit(Class<?> loadClass) {
                        if (Module.class.isAssignableFrom(loadClass)) {
                            final Class<? extends Module> clazz = loadClass.asSubclass(Module.class);

                            if (clazz.isAnnotationPresent(LoadModule.class)) {
                                Guice.createInjector(new AbstractModule() {
                                    @Override
                                    protected void configure() {
                                        bind(Module.class).to(clazz);
                                        bind(MessageBus.class).toInstance(bus);
                                    }
                                }).getInstance(Module.class).init();
                            }

                        } else if (MessageCallback.class.isAssignableFrom(loadClass)) {
                            final Class<? extends MessageCallback> clazz = loadClass.asSubclass(MessageCallback.class);
                            if (clazz.isAnnotationPresent(Service.class)) {
                                MessageCallback svc = Guice.createInjector(new AbstractModule() {
                                    @Override
                                    protected void configure() {
                                        bind(MessageCallback.class).to(clazz);
                                        bind(MessageBus.class).toInstance(bus);
                                    }
                                }).getInstance(MessageCallback.class);

                                String svcName = clazz.getAnnotation(Service.class).value();

                                if ("".equals(svcName)) {
                                    svcName = clazz.getSimpleName();
                                }

                                bus.subscribe(svcName, svc);

                                RolesRequiredRule rule = null;
                                if (clazz.isAnnotationPresent(RequireRoles.class)) {
                                    rule = new RolesRequiredRule(clazz.getAnnotation(RequireRoles.class).value(), bus);
                                } else if (clazz.isAnnotationPresent(RequireAuthentication.class)) {
                                    rule = new RolesRequiredRule(new HashSet<Object>(), bus);
                                }
                                if (rule != null) {
                                    bus.addRule(svcName, rule);
                                }
                            }
                        } else if (ErraiConfigExtension.class.isAssignableFrom(loadClass)) {
                            if (loadClass.isAnnotationPresent(ExtensionConfigurator.class)) {

                                ErraiConfigExtension configExt = Guice.createInjector(new AbstractModule() {
                                    @Override
                                    protected void configure() {
                                        bind(MessageBus.class).toInstance(bus);
                                        bind(ErraiModule.class).toInstance(module);
                                        bind(ErraiServiceConfigurator.class).toInstance(configInst);
                                    }
                                }).getInstance(ErraiConfigExtension.class);

                                configExt.configure();
                            }
                        }
                    }
                }
        );


        try {
            ResourceBundle erraiServiceConfig = ResourceBundle.getBundle("ErraiService");

            Enumeration<String> keys = erraiServiceConfig.getKeys();
            String key;
            while (keys.hasMoreElements()) {
                key = keys.nextElement();

                if ("errai.require_authentication_for_all".equals(key)) {
                    if ("true".equals(erraiServiceConfig.getString(key))) {
                        bus.addRule("ClientNegotiationService", new RolesRequiredRule(new HashSet<Object>(), bus));
                    }
                }

            }
        }
        catch (Exception e) {
            throw new RuntimeException("error reading from configuration", e);
        }
    }

    public List<File> getConfigurationRoots() {
        return this.configRootTargets;
    }
}
