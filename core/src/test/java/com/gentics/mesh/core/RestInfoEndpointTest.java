package com.gentics.mesh.core;

import static com.gentics.mesh.test.TestSize.FULL;
import static com.gentics.mesh.test.context.MeshTestHelper.call;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import com.gentics.mesh.Mesh;
import com.gentics.mesh.cli.MeshNameProvider;
import com.gentics.mesh.core.rest.MeshServerInfoModel;
import com.gentics.mesh.test.context.AbstractMeshTest;
import com.gentics.mesh.test.context.MeshTestSetting;
import com.orientechnologies.orient.core.OConstants;

import io.vertx.core.impl.launcher.commands.VersionCommand;


@MeshTestSetting(useElasticsearch = false, testSize = FULL, startServer = true)
public class RestInfoEndpointTest extends AbstractMeshTest {

	@Test
	public void testGetInfo() {
		MeshServerInfoModel info = call(() -> client().getApiInfo());
		assertEquals(Mesh.getPlainVersion(), info.getMeshVersion());
		assertEquals("orientdb", info.getDatabaseVendor());
		assertEquals("dummy", info.getSearchVendor());
		assertEquals(new VersionCommand().getVersion(), info.getVertxVersion());
		assertEquals(MeshNameProvider.getInstance().getName(), info.getMeshNodeId());
		assertEquals("The database version did not match.", OConstants.getVersion(), info.getDatabaseVersion());
		assertEquals("1.0", info.getSearchVersion());
	}

	@Test
	public void testLoadRAML() {
		String raml = call(() -> client().getRAML());
		assertNotNull(raml);
	}
}