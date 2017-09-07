package com.gentics.mesh.core.job;

import static com.gentics.mesh.core.rest.admin.migration.MigrationStatus.FAILED;
import static com.gentics.mesh.test.ClientHelper.call;
import static com.gentics.mesh.test.ClientHelper.assertMessage;
import static com.gentics.mesh.test.TestSize.PROJECT_AND_NODE;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com.gentics.mesh.core.data.job.Job;
import com.gentics.mesh.core.rest.admin.migration.MigrationStatusResponse;
import com.gentics.mesh.core.rest.common.GenericMessageResponse;
import com.gentics.mesh.core.rest.job.JobListResponse;
import com.gentics.mesh.core.rest.job.JobResponse;
import com.gentics.mesh.test.context.AbstractMeshTest;
import com.gentics.mesh.test.context.MeshTestSetting;
import com.gentics.mesh.test.util.TestUtils;
import com.syncleus.ferma.tx.Tx;

@MeshTestSetting(useElasticsearch = false, testSize = PROJECT_AND_NODE, startServer = true)
public class JobEndpointTest extends AbstractMeshTest {

	@Test
	public void testListJobs() {

		call(() -> client().findJobs(), FORBIDDEN, "error_admin_permission_required");

		tx(() -> group().addRole(roles().get("admin")));

		JobListResponse jobList = call(() -> client().findJobs());
		assertThat(jobList.getData()).isEmpty();

		tx(() -> {
			boot().jobRoot().enqueueReleaseMigration(user(), initialRelease());
		});

		jobList = call(() -> client().findJobs());
		assertThat(jobList.getData()).hasSize(1);
	}

	@Test
	public void testDeleteFailedJob() {

		String jobUuid = tx(() -> {
			Job job = boot().jobRoot().enqueueReleaseMigration(user(), initialRelease());
			return job.getUuid();
		});

		call(() -> client().deleteJob(jobUuid), FORBIDDEN, "error_admin_permission_required");

		tx(() -> group().addRole(roles().get("admin")));

		call(() -> client().deleteJob(jobUuid), BAD_REQUEST, "job_error_invalid_state", jobUuid);

		triggerAndWaitForMigration(FAILED);

		call(() -> client().deleteJob(jobUuid));

		JobListResponse jobList = call(() -> client().findJobs());
		assertThat(jobList.getData()).isEmpty();

	}

	/**
	 * Verify that no failed jobs are executed again. Those jobs must be ignored by the job worker verticle.
	 */
	@Test
	public void testHandlingOfFailedJobs() {

		String jobUuid = tx(() -> {
			Job job = boot().jobRoot().enqueueReleaseMigration(user(), initialRelease());
			return job.getUuid();
		});

		tx(() -> group().addRole(roles().get("admin")));

		waitForMigration(() -> {
			GenericMessageResponse msg = call(() -> client().invokeJobProcessing());
			assertMessage(msg, "job_processing_invoked");
		}, FAILED, 1);

		call(() -> client().invokeJobProcessing());
		TestUtils.sleep(10_000);
		MigrationStatusResponse status = call(() -> client().migrationStatus());
		assertEquals("No other migration should have been executed.", 1, status.getMigrations().size());
		assertEquals(jobUuid, status.getMigrations().get(0).getJobUuid());

	}

	@Test
	public void testLoadBogusJob() {
		try (Tx tx = tx()) {
			group().addRole(roles().get("admin"));
			tx.success();
		}

		call(() -> client().findJobByUuid("bogus"), NOT_FOUND, "object_not_found_for_uuid", "bogus");
	}

	@Test
	public void testManualInvoke() {
		String jobUuid = tx(() -> {
			Job job = boot().jobRoot().enqueueReleaseMigration(user(), initialRelease());
			return job.getUuid();
		});

		call(() -> client().invokeJobProcessing(), FORBIDDEN, "error_admin_permission_required");

		tx(() -> group().addRole(roles().get("admin")));

		MigrationStatusResponse response = waitForMigration(() -> {
			GenericMessageResponse msg = call(() -> client().invokeJobProcessing());
			assertMessage(msg, "job_processing_invoked");
		}, FAILED, 1);
		assertEquals("The job uuid of the job should match up with the migration status info uuid.", jobUuid,
				response.getMigrations().get(0).getJobUuid());

	}

	@Test
	public void testRetryJob() {

		String jobUuid = tx(() -> {
			Job job = boot().jobRoot().enqueueReleaseMigration(user(), initialRelease());
			return job.getUuid();
		});

		triggerAndWaitForMigration(FAILED);

		call(() -> client().resetJob(jobUuid), FORBIDDEN, "error_admin_permission_required");

		tx(() -> group().addRole(roles().get("admin")));

		JobResponse jobResonse = call(() -> client().findJobByUuid(jobUuid));
		assertNotNull(jobResonse.getErrorMessage());

		call(() -> client().resetJob(jobUuid));

		jobResonse = call(() -> client().findJobByUuid(jobUuid));
		assertNull(jobResonse.getErrorMessage());

	}
}
