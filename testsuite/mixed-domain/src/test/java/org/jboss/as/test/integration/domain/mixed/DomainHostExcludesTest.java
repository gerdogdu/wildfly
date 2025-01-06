/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.test.integration.domain.mixed;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CHILD_TYPE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CLONE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DOMAIN_CONTROLLER;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.EXTENSION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.IGNORE_UNUSED_CONFIG;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PORT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PROFILE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.READ_CHILDREN_NAMES_OPERATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RUNNING_SERVER;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER_CONFIG;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SHUTDOWN;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SOCKET_BINDING;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SOCKET_BINDING_GROUP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.TO_PROFILE;
import static org.jboss.as.controller.operations.common.Util.createRemoveOperation;
import static org.jboss.as.test.integration.domain.management.util.DomainTestUtils.executeForResult;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jboss.as.controller.ExpressionResolverImpl;
import org.jboss.as.controller.ModelVersion;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.test.integration.domain.management.util.DomainLifecycleUtil;
import org.jboss.as.test.integration.domain.management.util.DomainTestSupport;
import org.jboss.as.test.integration.domain.management.util.DomainTestUtils;
import org.jboss.as.test.integration.domain.management.util.WildFlyManagedConfiguration;
import org.jboss.as.test.integration.management.util.MgmtOperationException;
import org.jboss.as.test.shared.TimeoutUtil;
import org.jboss.dmr.ModelNode;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * Base class for tests of the ability of a DC to exclude resources from visibility to a secondary.
 *
 * @author Brian Stansberry
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public abstract class DomainHostExcludesTest {

    // A set of extensions that are available in the server under test modules but
    // are not configured by default in the legacy host.
    // These extensions will be added on the server under test, and are expected
    // to be ignored by the legacy hosts.
    private static final String[] EXCLUDED_EXTENSIONS = {
            "org.jboss.as.xts",
            "org.wildfly.extension.rts",
            "org.jboss.as.threads"
    };

    public static final Set<String> EXTENSIONS_SET = new HashSet<>(Arrays.asList(EXCLUDED_EXTENSIONS));

    private static final PathElement HOST = PathElement.pathElement("host", "secondary");
    private static final PathAddress HOST_EXCLUDE = PathAddress.pathAddress("host-exclude", "test");
    private static final PathElement SOCKET = PathElement.pathElement(SOCKET_BINDING, "http");
    private static final PathAddress CLONE_PROFILE = PathAddress.pathAddress(PROFILE, CLONE);

    private static DomainTestSupport testSupport;

    private static Version.AsVersion version;

    /** Subclasses call from a @BeforeClass method */
    protected static void setup(Class<?> clazz, String hostRelease, ModelVersion secondaryApiVersion) throws IOException, MgmtOperationException, TimeoutException, InterruptedException {
        version = clazz.getAnnotation(Version.class).value();

        testSupport = MixedDomainTestSuite.getSupport(clazz);

        // note that some of these 7+ specific changes may warrant creating a newer version of testing-host.xml for the newer secondary hosts
        // at some point (the currently used host.xml is quite an old version). If these exceptions become more complicated than this, we should
        // probably do that.

        //Unset the ignore-unused-configuration flag
        ModelNode dc = DomainTestUtils.executeForResult(
                Util.getReadAttributeOperation(PathAddress.pathAddress(HOST), DOMAIN_CONTROLLER),
                testSupport.getDomainSecondaryLifecycleUtil().getDomainClient());

        dc = dc.get("remote");

        dc.get(IGNORE_UNUSED_CONFIG).set(false);
        dc.get(OP).set("write-remote-domain-controller");
        dc.get(OP_ADDR).set(PathAddress.pathAddress(HOST).toModelNode());

        DomainTestUtils.executeForResult(dc, testSupport.getDomainSecondaryLifecycleUtil().getDomainClient());

        stopSecondary();

        // restarting the secondary will recopy the testing-host.xml file over the top, clobbering the ignore-unused-configuration above,
        // so use setRewriteConfigFiles(false) to prevent this.
        WildFlyManagedConfiguration secondaryCfg = testSupport.getDomainSecondaryConfiguration();
        secondaryCfg.setRewriteConfigFiles(false);

        // Setup a host exclude for the secondary ignoring some extensions
        ModelControllerClient client = testSupport.getDomainPrimaryLifecycleUtil().getDomainClient();
        setupExclude(client, hostRelease, secondaryApiVersion);

        // Now, add some ignored extensions to verify they are ignored due to the host-excluded configured before
        addExtensions(true, client);

        startSecondary();
    }

    private static void stopSecondary() throws IOException, MgmtOperationException, InterruptedException {
        ModelControllerClient client = testSupport.getDomainPrimaryLifecycleUtil().getDomainClient();
        executeForResult(Util.createEmptyOperation(SHUTDOWN, PathAddress.pathAddress(HOST)), client);
        boolean gone = false;
        long timeout = TimeoutUtil.adjust(30000);
        long deadline = System.currentTimeMillis() + timeout;
        do {
            ModelNode hosts = readChildrenNames(client, PathAddress.EMPTY_ADDRESS, HOST.getKey());
            gone = true;
            for (ModelNode host : hosts.asList()) {
                if (HOST.getValue().equals(host.asString())) {
                    gone = false;
                    TimeUnit.MILLISECONDS.sleep(100);
                    break;
                }
            }
        } while (!gone && System.currentTimeMillis() < deadline);
        Assert.assertTrue("Secondary was not removed within " + timeout + " ms", gone);
        testSupport.getDomainSecondaryLifecycleUtil().stop();
    }

    private static void setupExclude(ModelControllerClient client, String hostRelease, ModelVersion hostVersion) throws IOException, MgmtOperationException {

        ModelNode addOp = Util.createAddOperation(HOST_EXCLUDE);
        if (hostRelease != null) {
            addOp.get("host-release").set(hostRelease);
        } else {
            addOp.get("management-major-version").set(hostVersion.getMajor());
            addOp.get("management-minor-version").set(hostVersion.getMinor());
            if (hostVersion.getMicro() != 0) {
                addOp.get("management-micro-version").set(hostVersion.getMicro());
            }
        }
        addOp.get("active-server-groups").add("other-server-group");

        ModelNode asbgs = addOp.get("active-socket-binding-groups");
        asbgs.add("full-sockets");
        asbgs.add("full-ha-sockets");

        ModelNode extensions = addOp.get("excluded-extensions");
        for (String ext : getExcludedExtensions()) {
            extensions.add(ext);
        }

        executeForResult(addOp, client);
    }

    private static void addExtensions(boolean evens, ModelControllerClient client) throws IOException, MgmtOperationException {
        for (int i = 0; i < getExcludedExtensions().length; i++) {
            if ((i % 2 == 0) == evens) {
                executeForResult(Util.createAddOperation(PathAddress.pathAddress(EXTENSION, getExcludedExtensions()[i])), client);
            }
        }
    }

    private static void startSecondary() throws TimeoutException, InterruptedException {
        DomainLifecycleUtil legacyUtil = testSupport.getDomainSecondaryLifecycleUtil();
        long start = System.currentTimeMillis();
        legacyUtil.start();
        legacyUtil.awaitServers(start);

    }

    @AfterClass
    public static void tearDown() throws IOException, MgmtOperationException, TimeoutException, InterruptedException {
        try {
            executeForResult(createRemoveOperation(HOST_EXCLUDE), testSupport.getDomainPrimaryLifecycleUtil().getDomainClient());
        } finally {
            restoreSecondary();
        }
    }


    @Test
    public void test001SecondaryBoot() throws Exception {

        ModelControllerClient secondaryClient = testSupport.getDomainSecondaryLifecycleUtil().getDomainClient();

        checkExtensions(secondaryClient);
        checkProfiles(secondaryClient);
        checkSocketBindingGroups(secondaryClient);

        checkSockets(secondaryClient, PathAddress.pathAddress(SOCKET_BINDING_GROUP, "full-sockets"));
        checkSockets(secondaryClient, PathAddress.pathAddress(SOCKET_BINDING_GROUP, "full-ha-sockets"));
    }

    @Test
    public void test002ServerBoot() throws IOException, MgmtOperationException, InterruptedException, OperationFailedException {

        ModelControllerClient primaryClient = testSupport.getDomainPrimaryLifecycleUtil().getDomainClient();

        PathAddress serverCfgAddr = PathAddress.pathAddress(HOST,
                PathElement.pathElement(SERVER_CONFIG, "server-one"));
        ModelNode op = Util.createEmptyOperation("start", serverCfgAddr);
        executeForResult(op, primaryClient);

        PathAddress serverAddr = PathAddress.pathAddress(HOST,
                PathElement.pathElement(RUNNING_SERVER, "server-one"));
        awaitServerLaunch(primaryClient, serverAddr);
        checkSockets(primaryClient, serverAddr.append(PathElement.pathElement(SOCKET_BINDING_GROUP, "full-ha-sockets")));

    }

    private void awaitServerLaunch(ModelControllerClient client, PathAddress serverAddr) throws InterruptedException {
        long timeout = TimeoutUtil.adjust(20000);
        long expired = System.currentTimeMillis() + timeout;
        ModelNode op = Util.getReadAttributeOperation(serverAddr, "server-state");
        do {
            try {
                ModelNode state = DomainTestUtils.executeForResult(op, client);
                if ("running".equalsIgnoreCase(state.asString())) {
                    return;
                }
            } catch (IOException | MgmtOperationException e) {
                // ignore and try again
            }

            TimeUnit.MILLISECONDS.sleep(250L);
        } while (System.currentTimeMillis() < expired);

        Assert.fail("Server did not start in " + timeout + " ms");
    }

    @Test
    public void test003PostBootUpdates() throws IOException, MgmtOperationException {

        ModelControllerClient primaryClient = testSupport.getDomainPrimaryLifecycleUtil().getDomainClient();
        ModelControllerClient secondaryClient = testSupport.getDomainSecondaryLifecycleUtil().getDomainClient();

        // Tweak an ignored profile and socket-binding-group to prove secondary doesn't see it
        updateExcludedProfile(primaryClient);
        updateExcludedSocketBindingGroup(primaryClient);

        // Verify profile cloning is ignored when the cloned profile is excluded
        testProfileCloning(primaryClient, secondaryClient);

        // Add more ignored extensions to verify secondary doesn't see the ops
        addExtensions(false, primaryClient);
        checkExtensions(secondaryClient);

    }

    private void checkExtensions(ModelControllerClient client) throws IOException, MgmtOperationException {
        ModelNode op = Util.createEmptyOperation(READ_CHILDREN_NAMES_OPERATION, PathAddress.EMPTY_ADDRESS);
        op.get(CHILD_TYPE).set(EXTENSION);
        ModelNode result = executeForResult(op, client);
        Assert.assertTrue(result.isDefined());
        Assert.assertTrue(result.asInt() > 0);
        for (ModelNode ext : result.asList()) {
            Assert.assertFalse(ext.asString(), getExtensionsSet().contains(ext.asString()));
        }
    }

    private void checkProfiles(ModelControllerClient client) throws IOException, MgmtOperationException {
        ModelNode result = readChildrenNames(client, PathAddress.EMPTY_ADDRESS, PROFILE);
        Assert.assertTrue(result.isDefined());
        Assert.assertEquals(result.toString(), 1, result.asInt());
        Assert.assertEquals(result.toString(), "full-ha", result.get(0).asString());
    }

    private void checkSocketBindingGroups(ModelControllerClient client) throws IOException, MgmtOperationException {
        ModelNode result = readChildrenNames(client, PathAddress.EMPTY_ADDRESS, SOCKET_BINDING_GROUP);
        Assert.assertTrue(result.isDefined());
        Assert.assertEquals(result.toString(), 2, result.asInt());
        Set<String> expected = new HashSet<>(Arrays.asList("full-sockets", "full-ha-sockets"));
        for (ModelNode sbg : result.asList()) {
            expected.remove(sbg.asString());
        }
        Assert.assertTrue(result.toString(), expected.isEmpty());
    }

    private void checkSockets(ModelControllerClient client, PathAddress baseAddress) throws IOException, MgmtOperationException, OperationFailedException {
        ModelNode result = readChildrenNames(client, baseAddress, SOCKET_BINDING);
        Assert.assertTrue(result.isDefined());
        Assert.assertTrue(result.toString(), result.asInt() > 1);

        ModelNode op = Util.getReadAttributeOperation(baseAddress.append(SOCKET), PORT);
        result = executeForResult(op, client);
        Assert.assertTrue(result.isDefined());

        result = new TestExpressionResolver().resolveExpressions(result);

        Assert.assertEquals(result.toString(), 8080, result.asInt());
    }

    private void updateExcludedProfile(ModelControllerClient client) throws IOException, MgmtOperationException {
        ModelNode op = Util.getWriteAttributeOperation(PathAddress.pathAddress(PathElement.pathElement(PROFILE, "default"),
                PathElement.pathElement(SUBSYSTEM, "jmx")), "non-core-mbean-sensitivity", false);
        executeForResult(op, client);
    }

    private void updateExcludedSocketBindingGroup(ModelControllerClient client) throws IOException, MgmtOperationException {
        ModelNode op = Util.getWriteAttributeOperation(PathAddress.pathAddress(PathElement.pathElement(SOCKET_BINDING_GROUP, "standard-sockets"),
                PathElement.pathElement(SOCKET_BINDING, "http")), PORT, 8080);
        executeForResult(op, client);
    }

    private static ModelNode readChildrenNames(ModelControllerClient client, PathAddress pathAddress, String childType) throws IOException, MgmtOperationException {
        ModelNode op = Util.createEmptyOperation(READ_CHILDREN_NAMES_OPERATION, pathAddress);
        op.get(CHILD_TYPE).set(childType);
        return executeForResult(op, client);
    }

    private void testProfileCloning(ModelControllerClient primaryClient, ModelControllerClient secondaryClient) throws IOException, MgmtOperationException {
        ModelNode profiles = readChildrenNames(primaryClient, PathAddress.EMPTY_ADDRESS, PROFILE);
        Assert.assertTrue(profiles.isDefined());
        Assert.assertTrue(profiles.toString(), profiles.asInt() > 0);

        for (ModelNode mn : profiles.asList()) {
            String profile = mn.asString();
            cloneProfile(primaryClient, profile);
            try {
                checkProfiles(secondaryClient);
            } finally {
                executeForResult(Util.createRemoveOperation(CLONE_PROFILE), primaryClient);
            }
        }
    }

    private void cloneProfile(ModelControllerClient client, String toClone) throws IOException, MgmtOperationException {
        ModelNode op = Util.createEmptyOperation(CLONE, PathAddress.pathAddress(PROFILE, toClone));
        op.get(TO_PROFILE).set(CLONE);
        executeForResult(op, client);
    }

    private static void restoreSecondary() throws TimeoutException, InterruptedException {
        DomainLifecycleUtil secondaryUtil = testSupport.getDomainSecondaryLifecycleUtil();
        if (!secondaryUtil.isHostControllerStarted()) {
            startSecondary();
        }
    }

    private Set<String> getExtensionsSet() {
        if (version.getMajor() >= 7) {
            return EXTENSIONS_SET;
        }
        throw new IllegalStateException("Unknown version " + version);
    }

    private static String[] getExcludedExtensions() {
        if (version.getMajor() >= 7) {
            return EXCLUDED_EXTENSIONS;
        }
        throw new IllegalStateException("Unknown version " + version);
    }

    private static class TestExpressionResolver extends ExpressionResolverImpl {
        public TestExpressionResolver() {
        }
    }
}
