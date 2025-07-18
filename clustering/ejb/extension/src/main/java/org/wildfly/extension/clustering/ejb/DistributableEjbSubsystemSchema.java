/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.clustering.ejb;

import java.util.List;
import java.util.Map;

import org.jboss.as.controller.ResourceRegistration;
import org.jboss.as.controller.SubsystemSchema;
import org.jboss.as.controller.persistence.xml.NamedResourceRegistrationXMLElement;
import org.jboss.as.controller.persistence.xml.ResourceXMLChoice;
import org.jboss.as.controller.persistence.xml.ResourceXMLParticleFactory;
import org.jboss.as.controller.persistence.xml.ResourceXMLSequence;
import org.jboss.as.controller.persistence.xml.SingletonResourceRegistrationXMLChoice;
import org.jboss.as.controller.persistence.xml.SingletonResourceRegistrationXMLElement;
import org.jboss.as.controller.persistence.xml.SubsystemResourceRegistrationXMLElement;
import org.jboss.as.controller.persistence.xml.SubsystemResourceXMLSchema;
import org.jboss.as.controller.xml.VersionedNamespace;
import org.jboss.as.controller.xml.XMLCardinality;
import org.jboss.staxmapper.IntVersion;

/**
 * Enumerates the schema versions for the distributable-ejb subsystem.
 * @author Paul Ferraro
 * @author Richard Achmatowicz
 */
public enum DistributableEjbSubsystemSchema implements SubsystemResourceXMLSchema<DistributableEjbSubsystemSchema> {

    VERSION_1_0(1, 0), // WildFly 27-35
    VERSION_2_0(2, 0), // WildFly 36
    ;
    static final DistributableEjbSubsystemSchema CURRENT = VERSION_2_0;

    private final VersionedNamespace<IntVersion, DistributableEjbSubsystemSchema> namespace;
    private final ResourceXMLParticleFactory factory = ResourceXMLParticleFactory.newInstance(this);

    DistributableEjbSubsystemSchema(int major, int minor) {
        this.namespace = SubsystemSchema.createLegacySubsystemURN(DistributableEjbSubsystemResourceDefinitionRegistrar.REGISTRATION.getName(), new IntVersion(major, minor));
    }

    @Override
    public VersionedNamespace<IntVersion, DistributableEjbSubsystemSchema> getNamespace() {
        return this.namespace;
    }

    @Override
    public SubsystemResourceRegistrationXMLElement getSubsystemXMLElement() {
        SubsystemResourceRegistrationXMLElement.Builder builder = this.factory.subsystemElement(DistributableEjbSubsystemResourceDefinitionRegistrar.REGISTRATION);
        ResourceXMLSequence.Builder contentBuilder = this.factory.sequence();
        if (this.since(VERSION_2_0)) {
            contentBuilder.addElement(this.factory.element(this.factory.resolve("bean-management"))
                    .addAttribute(DistributableEjbSubsystemResourceDefinitionRegistrar.DEFAULT_BEAN_MANAGEMENT_PROVIDER)
                    .withContent(this.beanManagementChoice())
                    .build());
        } else {
            builder.addAttribute(DistributableEjbSubsystemResourceDefinitionRegistrar.DEFAULT_BEAN_MANAGEMENT_PROVIDER);
            builder.withLocalNames(Map.of(DistributableEjbSubsystemResourceDefinitionRegistrar.DEFAULT_BEAN_MANAGEMENT_PROVIDER, DistributableEjbSubsystemResourceDefinitionRegistrar.DEFAULT_BEAN_MANAGEMENT_PROVIDER.getName()));
            contentBuilder.addChoice(this.beanManagementChoice());
        }
        contentBuilder.addChoice(this.clientMappingsRegistryChoice());
        contentBuilder.addChoice(this.timerManagementChoice());
        return builder.withContent(contentBuilder.build())
                .build();
    }

    ResourceXMLChoice beanManagementChoice() {
        return this.factory.choice()
                .withCardinality(XMLCardinality.Unbounded.REQUIRED)
                .addElement(this.infinispanBeanManagementElement())
                .build();
    }

    NamedResourceRegistrationXMLElement infinispanBeanManagementElement() {
        return this.beanManagementElementBuilder(BeanManagementResourceRegistration.INFINISPAN)
                .addAttributes(InfinispanBeanManagementResourceDefinitionRegistrar.CACHE_ATTRIBUTE_GROUP.getAttributes())
                .build();
    }

    NamedResourceRegistrationXMLElement.Builder beanManagementElementBuilder(ResourceRegistration registration) {
        return this.factory.namedElement(registration)
                .addAttribute(BeanManagementResourceDefinitionRegistrar.MAX_ACTIVE_BEANS)
                ;
    }

    SingletonResourceRegistrationXMLChoice clientMappingsRegistryChoice() {
        return this.factory.singletonElementChoice()
                .addElement(this.localClientMappingsRegistryElement())
                .addElement(this.infinispanClientMappingsRegistryElement())
                .build();
    }

    SingletonResourceRegistrationXMLElement localClientMappingsRegistryElement() {
        return this.factory.singletonElement(ClientMappingsRegistryProviderResourceRegistration.LOCAL)
                .withElementLocalName("local-client-mappings-registry")
                .build();
    }

    SingletonResourceRegistrationXMLElement infinispanClientMappingsRegistryElement() {
        return this.factory.singletonElement(ClientMappingsRegistryProviderResourceRegistration.INFINISPAN)
                .withElementLocalName("infinispan-client-mappings-registry")
                .addAttributes(InfinispanClientMappingsRegistryProviderResourceDefinitionRegistrar.CACHE_ATTRIBUTE_GROUP.getAttributes())
                .build();
    }

    ResourceXMLChoice timerManagementChoice() {
        return this.factory.choice()
                .withCardinality(XMLCardinality.Unbounded.OPTIONAL)
                .addElement(this.infinispanTimerManagementElement())
                .build();
    }
    NamedResourceRegistrationXMLElement infinispanTimerManagementElement() {
        return this.factory.namedElement(InfinispanTimerManagementResourceDefinitionRegistrar.REGISTRATION)
                .addAttributes(InfinispanTimerManagementResourceDefinitionRegistrar.CACHE_ATTRIBUTE_GROUP.getAttributes())
                .addAttributes(List.of(InfinispanTimerManagementResourceDefinitionRegistrar.MARSHALLER, InfinispanTimerManagementResourceDefinitionRegistrar.MAX_ACTIVE_TIMERS))
                .build();
    }
}
