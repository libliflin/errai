package org.jboss.errai.ioc.rebind.ioc.injector;

import org.jboss.errai.codegen.Parameter;
import org.jboss.errai.codegen.Statement;
import org.jboss.errai.codegen.builder.AnonymousClassStructureBuilder;
import org.jboss.errai.codegen.builder.BlockBuilder;
import org.jboss.errai.codegen.builder.impl.ObjectBuilder;
import org.jboss.errai.codegen.meta.MetaClass;
import org.jboss.errai.codegen.meta.MetaClassMember;
import org.jboss.errai.codegen.meta.MetaMethod;
import org.jboss.errai.codegen.meta.MetaParameter;
import org.jboss.errai.codegen.util.PrivateAccessType;
import org.jboss.errai.codegen.util.PrivateAccessUtil;
import org.jboss.errai.codegen.util.Refs;
import org.jboss.errai.codegen.util.Stmt;
import org.jboss.errai.ioc.client.container.CreationalCallback;
import org.jboss.errai.ioc.client.container.CreationalContext;
import org.jboss.errai.ioc.client.container.DestructionCallback;
import org.jboss.errai.ioc.rebind.ioc.bootstrapper.IOCProcessingContext;
import org.jboss.errai.ioc.rebind.ioc.injector.api.InjectableInstance;
import org.jboss.errai.ioc.rebind.ioc.injector.api.InjectionContext;
import org.jboss.errai.ioc.rebind.ioc.injector.api.InjectionPoint;
import org.jboss.errai.ioc.rebind.ioc.injector.api.TypeDiscoveryListener;
import org.jboss.errai.ioc.rebind.ioc.injector.api.WiringElementType;
import org.jboss.errai.ioc.rebind.ioc.metadata.QualifyingMetadata;

import javax.annotation.PreDestroy;
import javax.enterprise.inject.Disposes;

import static org.jboss.errai.codegen.meta.MetaClassFactory.parameterizedAs;
import static org.jboss.errai.codegen.meta.MetaClassFactory.typeParametersOf;
import static org.jboss.errai.codegen.util.Stmt.loadVariable;

/**
 * @author Mike Brock
 */
public class ProducerInjector extends AbstractInjector {
  private final MetaClass injectedType;
  private final MetaClassMember producerMember;
  private final InjectableInstance producerInjectableInstance;
  private final MetaMethod disposerMethod;

  public ProducerInjector(final InjectionContext injectionContext,
                          final MetaClass injectedType,
                          final MetaClassMember producerMember,
                          final QualifyingMetadata metadata,
                          final InjectableInstance producerInjectableInstance) {

    switch (producerInjectableInstance.getTaskType()) {
      case PrivateField:
      case PrivateMethod:
        producerInjectableInstance.ensureMemberExposed(PrivateAccessType.Read);
    }

    super.qualifyingMetadata = metadata;
    this.provider = true;
    this.injectedType = injectedType;
    this.enclosingType = producerMember.getDeclaringClass();
    this.producerMember = producerMember;
    this.producerInjectableInstance = producerInjectableInstance;

    this.singleton = injectionContext.isElementType(WiringElementType.SingletonBean, getProducerMember());

    this.disposerMethod = findDisposerMethod();

    if (injectionContext.isInjectorRegistered(enclosingType, qualifyingMetadata)) {
      setRendered(true);
    }
    else {
      injectionContext.getProcessingContext().registerTypeDiscoveryListener(new TypeDiscoveryListener() {
        @Override
        public void onDiscovery(IOCProcessingContext context, InjectionPoint injectionPoint) {
          if (injectionPoint.getEnclosingType().equals(enclosingType)) {
            setRendered(true);
          }
        }
      });
    }
  }

  @Override
  public Statement getBeanInstance(InjectableInstance injectableInstance) {
    final InjectionContext injectionContext = injectableInstance.getInjectionContext();


    if (isDependent()) {
      return registerDestructorCallback(injectionContext, injectionContext.getProcessingContext().getBlockBuilder(),
              producerInjectableInstance.getValueStatement(), disposerMethod);
    }

    final BlockBuilder callbackBuilder = injectionContext.getProcessingContext().getBlockBuilder();

    final MetaClass creationCallbackRef = parameterizedAs(CreationalCallback.class,
            typeParametersOf(injectedType));

    final String var = InjectUtil.getUniqueVarName();

    final Statement producerCreationalCallback = ObjectBuilder.newInstanceOf(creationCallbackRef)
            .extend()
            .publicOverridesMethod("getInstance", Parameter.of(CreationalContext.class, "pContext"))
            ._(Stmt.declareVariable(injectedType)
                    .named(var).initializeWith(producerInjectableInstance.getValueStatement()))
            ._(loadVariable("context").invoke("addBean",
                    loadVariable("context").invoke("getBeanReference",
                            Stmt.load(injectedType),
                            Stmt.load(qualifyingMetadata.getQualifiers())), Refs.get(var)))
            ._(Stmt.loadVariable(var).returnValue())
            .finish().finish();

    callbackBuilder.append(Stmt.declareVariable(creationCallbackRef).asFinal().named(var)
            .initializeWith(producerCreationalCallback));

    final Statement retVal = loadVariable("context").invoke("getSingletonInstanceOrNew",
            Stmt.loadVariable("injContext"),
            Stmt.loadVariable(var),
            Stmt.load(injectedType),
            Stmt.load(qualifyingMetadata.getQualifiers()));

    return registerDestructorCallback(injectionContext, callbackBuilder, retVal, disposerMethod);
  }

  private boolean hasCompanionDisposer() {
    return disposerMethod != null;
  }

  private MetaMethod findDisposerMethod() {
    final MetaClass declaringClass = producerMember.getDeclaringClass();

    for (final MetaMethod method : declaringClass.getDeclaredMethods()) {
      final MetaParameter[] parameters = method.getParameters();
      if (parameters.length != 1) continue;

      if (parameters[0].isAnnotationPresent(Disposes.class)
              && parameters[0].getType().isAssignableFrom(injectedType)) {
        return method;
      }
    }

    return null;
  }

  private Statement registerDestructorCallback(final InjectionContext injectionContext,
                                          final BlockBuilder<?> bb,
                                          final Statement beanValue,
                                          final MetaMethod disposerMethod) {

    if (disposerMethod == null) {
      return beanValue;
    }

    final String varName = InjectUtil.getUniqueVarName();
    bb._(Stmt.declareVariable(injectedType).asFinal().named(varName).initializeWith(beanValue));

    final MetaClass destructionCallbackType =
            parameterizedAs(DestructionCallback.class, typeParametersOf(injectedType));

    final BlockBuilder<AnonymousClassStructureBuilder> initMeth
            = ObjectBuilder.newInstanceOf(destructionCallbackType).extend()
            .publicOverridesMethod("destroy", Parameter.of(injectedType, "obj", true));

    final String destroyVarName = "destroy_" + varName;

    if (!disposerMethod.isPublic()) {
      injectionContext.addExposedMethod(disposerMethod);
    }

    final String beanVar = producerInjectableInstance.getTargetInjector().getVarName();

    final Statement disposerInvoke = InjectUtil.invokePublicOrPrivateMethod(injectionContext.getProcessingContext(),
            Refs.get(beanVar),
            disposerMethod,
            Refs.get("obj"));

    initMeth._(disposerInvoke);

    final AnonymousClassStructureBuilder classStructureBuilder = initMeth.finish();

    bb._(Stmt.declareVariable(destructionCallbackType).asFinal().named(destroyVarName)
            .initializeWith(classStructureBuilder.finish()));

    bb._(Stmt.loadVariable("context").invoke("addDestructionCallback",
            Refs.get(varName), Refs.get(destroyVarName)));

    return Refs.get(varName);
  }

  @Override
  public boolean isStatic() {
    return getProducerMember().isStatic();
  }

  public MetaClassMember getProducerMember() {
    return producerMember;
  }

  @Override
  public MetaClass getInjectedType() {
    return injectedType;

  }
}