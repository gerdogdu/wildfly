/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.extension.messaging.activemq;


import static org.wildfly.extension.messaging.activemq.ActiveMQActivationService.getActiveMQServer;

import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.jboss.as.controller.AbstractRemoveStepHandler;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.dmr.ModelNode;

/**
 * {@code OperationStepHandler} removing an existing address setting.
 *
 * @author Emanuel Muckenhuber
 */
class AddressSettingRemove extends AbstractRemoveStepHandler {

    static final OperationStepHandler INSTANCE = new AddressSettingRemove();

    @Override
    protected void performRuntime(final OperationContext context, final ModelNode operation, final ModelNode model) throws OperationFailedException {
        final ActiveMQServer server = getActiveMQServer(context, operation);
        if (server != null) {
            final PathAddress address = PathAddress.pathAddress(operation.require(ModelDescriptionConstants.OP_ADDR));
            server.getAddressSettingsRepository().removeMatch(address.getLastElement().getValue());
        }
    }
}
