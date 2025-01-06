/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.ejb3.deployment.processors.merging;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import jakarta.ejb.AccessTimeout;
import jakarta.ejb.Lock;
import jakarta.ejb.LockType;

import org.jboss.as.ee.component.EEApplicationClasses;
import org.jboss.as.ee.metadata.MethodAnnotationAggregator;
import org.jboss.as.ee.metadata.RuntimeAnnotationInformation;
import org.jboss.as.ejb3.logging.EjbLogger;
import org.jboss.as.ejb3.component.session.SessionBeanComponentDescription;
import org.jboss.as.ejb3.concurrency.AccessTimeoutDetails;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.reflect.ClassReflectionIndex;
import org.jboss.as.server.deployment.reflect.DeploymentReflectionIndex;
import org.jboss.invocation.proxy.MethodIdentifier;
import org.jboss.metadata.ejb.spec.ConcurrentMethodMetaData;
import org.jboss.metadata.ejb.spec.ConcurrentMethodsMetaData;
import org.jboss.metadata.ejb.spec.NamedMethodMetaData;
import org.jboss.metadata.ejb.spec.SessionBean31MetaData;
import org.jboss.metadata.ejb.spec.SessionBeanMetaData;

/**
 * Class that can merge {@link jakarta.ejb.Lock} and {@link jakarta.ejb.AccessTimeout} metadata
 *
 * @author Stuart Douglas
 */
public class EjbConcurrencyMergingProcessor extends AbstractMergingProcessor<SessionBeanComponentDescription> {

    public EjbConcurrencyMergingProcessor() {
        super(SessionBeanComponentDescription.class);
    }

    protected void handleAnnotations(final DeploymentUnit deploymentUnit, final EEApplicationClasses applicationClasses, final DeploymentReflectionIndex deploymentReflectionIndex, final Class<?> componentClass, final SessionBeanComponentDescription componentConfiguration) {

        //handle lock annotations

        final RuntimeAnnotationInformation<LockType> lockData = MethodAnnotationAggregator.runtimeAnnotationInformation(componentClass, applicationClasses, deploymentReflectionIndex, Lock.class);
        for (Map.Entry<String, List<LockType>> entry : lockData.getClassAnnotations().entrySet()) {
            if (!entry.getValue().isEmpty()) {
                componentConfiguration.setBeanLevelLockType(entry.getKey(), entry.getValue().get(0));
            }
        }
        for (Map.Entry<Method, List<LockType>> entry : lockData.getMethodAnnotations().entrySet()) {
            if (!entry.getValue().isEmpty()) {
                componentConfiguration.setLockType(entry.getValue().get(0), MethodIdentifier.getIdentifierForMethod(entry.getKey()));
            }
        }

        final RuntimeAnnotationInformation<AccessTimeoutDetails> accessTimeout = MethodAnnotationAggregator.runtimeAnnotationInformation(componentClass, applicationClasses, deploymentReflectionIndex, AccessTimeout.class);
        for (Map.Entry<String, List<AccessTimeoutDetails>> entry : accessTimeout.getClassAnnotations().entrySet()) {
            if (!entry.getValue().isEmpty()) {
                componentConfiguration.setBeanLevelAccessTimeout(entry.getKey(), entry.getValue().get(0));
            }
        }
        for (Map.Entry<Method, List<AccessTimeoutDetails>> entry : accessTimeout.getMethodAnnotations().entrySet()) {
            if (!entry.getValue().isEmpty()) {
                componentConfiguration.setAccessTimeout(entry.getValue().get(0), MethodIdentifier.getIdentifierForMethod(entry.getKey()));
            }
        }

    }

    protected void handleDeploymentDescriptor(final DeploymentUnit deploymentUnit, final DeploymentReflectionIndex deploymentReflectionIndex, final Class<?> componentClass, final SessionBeanComponentDescription componentConfiguration) throws DeploymentUnitProcessingException {

        if (componentConfiguration.getDescriptorData() == null) {
            return;
        }
        SessionBeanMetaData sessionBeanMetaData = componentConfiguration.getDescriptorData();
        if (sessionBeanMetaData instanceof SessionBean31MetaData) {
            SessionBean31MetaData descriptor = (SessionBean31MetaData) sessionBeanMetaData;

            //handle lock
            if (descriptor.getLockType() != null) {
                componentConfiguration.setBeanLevelLockType(componentConfiguration.getEJBClassName(), descriptor.getLockType());
            }

            //handle access timeout
            if (descriptor.getAccessTimeout() != null) {
                componentConfiguration.setBeanLevelAccessTimeout(componentConfiguration.getEJBClassName(), new AccessTimeoutDetails(descriptor.getAccessTimeout().getTimeout(), descriptor.getAccessTimeout().getUnit()));
            }

            final ConcurrentMethodsMetaData methods = descriptor.getConcurrentMethods();
            if (methods != null) {
                for (final ConcurrentMethodMetaData method : methods) {
                    final Method realMethod = resolveMethod(deploymentReflectionIndex, componentClass, componentClass, method.getMethod());
                    final MethodIdentifier methodIdentifier = MethodIdentifier.getIdentifierForMethod(realMethod);
                    if (method.getLockType() != null) {
                        componentConfiguration.setLockType(method.getLockType(), methodIdentifier);
                    }
                    if (method.getAccessTimeout() != null) {
                        componentConfiguration.setAccessTimeout(new AccessTimeoutDetails(method.getAccessTimeout().getTimeout(), method.getAccessTimeout().getUnit()), methodIdentifier);
                    }

                }
            }


        }
    }


    private Method resolveMethod(final DeploymentReflectionIndex index, final Class<?> currentClass, final Class<?> componentClass, final NamedMethodMetaData methodData) throws DeploymentUnitProcessingException {
        if (currentClass == null) {
            throw EjbLogger.ROOT_LOGGER.failToFindMethodWithParameterTypes(componentClass.getName(), methodData.getMethodName(), methodData.getMethodParams());
        }
        final ClassReflectionIndex classIndex = index.getClassIndex(currentClass);

        if (methodData.getMethodParams() == null) {
            final Collection<Method> methods = classIndex.getAllMethods(methodData.getMethodName());
            if (methods.isEmpty()) {
                return resolveMethod(index, currentClass.getSuperclass(), componentClass, methodData);
            } else if (methods.size() > 1) {
                throw EjbLogger.ROOT_LOGGER.multipleMethodReferencedInEjbJarXml(methodData.getMethodName(), currentClass.getName());
            }
            return methods.iterator().next();
        } else {
            final Collection<Method> methods = classIndex.getAllMethods(methodData.getMethodName(), methodData.getMethodParams().size());
            for (final Method method : methods) {
                boolean match = true;
                for (int i = 0; i < method.getParameterCount(); ++i) {
                    if (!method.getParameterTypes()[i].getName().equals(methodData.getMethodParams().get(i))) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return method;
                }
            }
        }
        return resolveMethod(index, currentClass.getSuperclass(), componentClass, methodData);
    }
}
