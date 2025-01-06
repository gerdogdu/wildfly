/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.test.integration.microprofile.config.smallrye.management.config_source.from_dir;

import static org.wildfly.test.integration.microprofile.config.smallrye.management.config_source.from_dir.SetupTask.A;
import static org.wildfly.test.integration.microprofile.config.smallrye.management.config_source.from_dir.SetupTask.B;
import static org.wildfly.test.integration.microprofile.config.smallrye.management.config_source.from_dir.TestApplication.B_OVERRIDES_A;
import static org.wildfly.test.integration.microprofile.config.smallrye.management.config_source.from_dir.TestApplication.DEFAULT;
import static org.wildfly.test.integration.microprofile.config.smallrye.management.config_source.from_dir.TestApplication.FROM_A;
import static org.wildfly.test.integration.microprofile.config.smallrye.management.config_source.from_dir.TestApplication.FROM_B;
import static org.wildfly.test.integration.microprofile.config.smallrye.management.config_source.from_dir.TestApplication.NOT_AVAILABLE_NESTED_DIR_UNDER_A;
import static org.wildfly.test.integration.microprofile.config.smallrye.management.config_source.from_dir.TestApplication.NOT_AVAILABLE_NESTED_DIR_UNDER_B;

import java.net.URL;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.api.ServerSetup;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.test.integration.microprofile.config.smallrye.AbstractMicroProfileConfigTestCase;
import org.wildfly.test.integration.microprofile.config.smallrye.AssertUtils;

/**
 * Load a ConfigSource from a class (in a module).
 *
 * @author <a href="http://jmesnil.net/">Jeff Mesnil</a> (c) 2017 Red Hat inc.
 */
@RunWith(Arquillian.class)
@RunAsClient
@ServerSetup(SetupTask.class)
public class ConfigSourceFromDirTestCase extends AbstractMicroProfileConfigTestCase {

    @Deployment
    public static Archive<?> deploy() {
        WebArchive war = ShrinkWrap.create(WebArchive.class, "ConfigSourceFromDirTestCase.war")
                .addClasses(TestApplication.class, TestApplication.Resource.class, AbstractMicroProfileConfigTestCase.class)
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
        return war;
    }

    @ArquillianResource
    private URL url;

    @Test
    public void testGetWithConfigProperties() throws Exception {
        try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
            HttpResponse response = client.execute(new HttpGet(url + "custom-config-source/test"));
            Assert.assertEquals(200, response.getStatusLine().getStatusCode());
            String text = EntityUtils.toString(response.getEntity());
            AssertUtils.assertTextContainsProperty(text, FROM_A, A);
            AssertUtils.assertTextContainsProperty(text, FROM_B, B);
            AssertUtils.assertTextContainsProperty(text, B_OVERRIDES_A, SetupTask.OVERRIDDEN_B);
            AssertUtils.assertTextContainsProperty(text, NOT_AVAILABLE_NESTED_DIR_UNDER_A, DEFAULT);
            AssertUtils.assertTextContainsProperty(text, NOT_AVAILABLE_NESTED_DIR_UNDER_B, DEFAULT);
        }
    }
}
