package com.gentics.mesh.core.verticle.schema;

import static com.gentics.mesh.core.data.relationship.GraphPermission.READ_PERM;
import static com.gentics.mesh.core.data.relationship.GraphPermission.UPDATE_PERM;
import static com.gentics.mesh.core.rest.common.GenericMessageResponse.message;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

import java.util.concurrent.CountDownLatch;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.gentics.mesh.Mesh;
import com.gentics.mesh.context.InternalActionContext;
import com.gentics.mesh.core.data.Project;
import com.gentics.mesh.core.data.root.RootVertex;
import com.gentics.mesh.core.data.schema.SchemaContainer;
import com.gentics.mesh.core.data.schema.SchemaContainerVersion;
import com.gentics.mesh.core.data.schema.handler.SchemaComparator;
import com.gentics.mesh.core.rest.schema.Schema;
import com.gentics.mesh.core.rest.schema.change.impl.SchemaChangesListModel;
import com.gentics.mesh.core.rest.schema.impl.SchemaModel;
import com.gentics.mesh.core.verticle.handler.AbstractCrudHandler;
import com.gentics.mesh.core.verticle.handler.HandlerUtilities;
import com.gentics.mesh.core.verticle.node.NodeMigrationVerticle;
import com.gentics.mesh.graphdb.NoTrx;
import com.gentics.mesh.json.JsonUtil;

import io.vertx.core.eventbus.DeliveryOptions;
import rx.Observable;

@Component
public class SchemaContainerCrudHandler extends AbstractCrudHandler<SchemaContainer, Schema> {

	@Autowired
	private SchemaComparator comparator;

	@Override
	public RootVertex<SchemaContainer> getRootVertex(InternalActionContext ac) {
		return boot.schemaContainerRoot();
	}

	@Override
	public void handleDelete(InternalActionContext ac, String uuid) {
		validateParameter(uuid, "uuid");
		HandlerUtilities.deleteElement(ac, () -> boot.schemaContainerRoot(), uuid, "schema_deleted");
	}

	@Override
	public void handleUpdate(InternalActionContext ac, String uuid) {
		validateParameter(uuid, "uuid");
		db.asyncNoTrxExperimental(() -> {
			RootVertex<SchemaContainer> root = getRootVertex(ac);
			return root.loadObjectByUuid(ac, uuid, UPDATE_PERM).flatMap(element -> {
				try {
					Schema requestModel = JsonUtil.readValue(ac.getBodyAsString(), SchemaModel.class);
					SchemaChangesListModel model = new SchemaChangesListModel();
					model.getChanges().addAll(SchemaComparator.getIntance().diff(element.getLatestVersion().getSchema(), requestModel));
					String schemaName = element.getName();
					if (model.getChanges().isEmpty()) {
						return Observable.just(message(ac, "schema_update_no_difference_detected", schemaName));
					} else {
						return element.getLatestVersion().applyChanges(ac, model).flatMap(e -> {
							return Observable.just(message(ac, "migration_invoked", schemaName));
						});
					}
				} catch (Exception e) {
					return Observable.error(e);
				}
			});
		}).subscribe(model -> ac.respond(model, OK), ac::fail);
	}

	public void handleDiff(InternalActionContext ac, String uuid) {
		db.asyncNoTrxExperimental(() -> {
			Observable<SchemaContainer> obsSchema = getRootVertex(ac).loadObjectByUuid(ac, uuid, READ_PERM);
			Schema requestModel = JsonUtil.readValue(ac.getBodyAsString(), SchemaModel.class);
			return obsSchema.flatMap(schema -> schema.getLatestVersion().diff(ac, comparator, requestModel));
		}).subscribe(model -> ac.respond(model, OK), ac::fail);
	}

	public void handleReadProjectList(InternalActionContext ac) {
		HandlerUtilities.readElementList(ac, () -> ac.getProject().getSchemaContainerRoot());
	}

	public void handleAddProjectToSchema(InternalActionContext ac, String schemaUuid, String projectUuid) {
		validateParameter(schemaUuid, "schemaUuid");
		validateParameter(projectUuid, "projectUuid");

		db.asyncNoTrxExperimental(() -> {
			Observable<SchemaContainer> obsSchema = getRootVertex(ac).loadObjectByUuid(ac, schemaUuid, READ_PERM);
			Observable<Project> obsProject = boot.projectRoot().loadObjectByUuid(ac, projectUuid, UPDATE_PERM);

			return Observable.zip(obsProject, obsSchema, (project, schema) -> {
				SchemaContainer addedSchema = db.trx(() -> {
					//TODO SQB ?
					project.getSchemaContainerRoot().addSchemaContainer(schema);
					return schema;
				});
				return addedSchema.transformToRest(ac, 0);
			}).flatMap(x -> x);

		}).subscribe(model -> ac.respond(model, OK), ac::fail);

	}

	public void handleRemoveProjectFromSchema(InternalActionContext ac, String projectUuid, String schemaUuid) {
		validateParameter(projectUuid, "projectUuid");
		validateParameter(schemaUuid, "schemaUuid");

		db.asyncNoTrxExperimental(() -> {
			Observable<Project> obsProject = boot.projectRoot().loadObjectByUuid(ac, projectUuid, UPDATE_PERM);
			// TODO check whether schema is assigned to project
			Observable<SchemaContainer> obsSchema = boot.schemaContainerRoot().loadObjectByUuid(ac, schemaUuid, READ_PERM);

			return Observable.zip(obsProject, obsSchema, (project, schema) -> {
				SchemaContainer removedSchema = db.trx(() -> {
					project.getSchemaContainerRoot().removeSchemaContainer(schema);
					return schema;
				});
				return removedSchema.transformToRest(ac, 0);
			}).flatMap(x -> x);
		}).subscribe(model -> ac.respond(model, OK), ac::fail);
	}

	public void handleGetSchemaChanges(InternalActionContext ac) {
		// TODO Auto-generated method stub

	}

	public void handleApplySchemaChanges(InternalActionContext ac, String schemaUuid) {
		validateParameter(schemaUuid, "schemaUuid");

		db.asyncNoTrxExperimental(() -> {
			Observable<SchemaContainer> obsSchema = boot.schemaContainerRoot().loadObjectByUuid(ac, schemaUuid, UPDATE_PERM);
			return obsSchema.flatMap(schema -> {
				return schema.getLatestVersion().applyChanges(ac);
			});
		}).subscribe(model -> ac.respond(model, OK), ac::fail);

	}

	public void handleMigrateRemaining(InternalActionContext ac) {
		db.asyncNoTrxExperimental(() -> {

			for (SchemaContainer schemaContainer : boot.schemaContainerRoot().findAll()) {
				SchemaContainerVersion latestVersion = schemaContainer.getLatestVersion();
				SchemaContainerVersion currentVersion = latestVersion;
				while (true) {
					currentVersion = currentVersion.getPreviousVersion();
					if (currentVersion == null) {
						break;
					}

					System.out.println("Before migration " + schemaContainer.getName() + " - " + currentVersion.getUuid() + "="
							+ currentVersion.getFieldContainers().size());
					//					if (!getLatestVersion().getUuid().equals(version.getUuid())) {
					//						for (GraphFieldContainer container : version.getFieldContainers()) {
					//							NodeImpl node = container.getImpl().in(HAS_FIELD_CONTAINER).nextOrDefaultExplicit(NodeImpl.class, null);
					//							System.out.println(
					//									"Node: " + node.getUuid() + "ne: " + node.getLastEditedTimestamp() + "nc: " + node.getCreationTimestamp());
					//						}
					//					}
					CountDownLatch latch = new CountDownLatch(1);
					DeliveryOptions options = new DeliveryOptions();
					options.addHeader(NodeMigrationVerticle.UUID_HEADER, schemaContainer.getUuid());
					options.addHeader(NodeMigrationVerticle.FROM_VERSION_UUID_HEADER, currentVersion.getUuid());
					options.addHeader(NodeMigrationVerticle.TO_VERSION_UUID_HEADER, latestVersion.getUuid());
					SchemaContainerVersion version = currentVersion;
					Mesh.vertx().eventBus().send(NodeMigrationVerticle.SCHEMA_MIGRATION_ADDRESS, null, options, rh -> {
						try (NoTrx noTrx = db.noTrx()) {
							System.out.println("After migration " + schemaContainer.getName() + " - " + version.getUuid() + "="
									+ version.getFieldContainers().size());
						}
						latch.countDown();
					});
					latch.await();
				}

			}
			return Observable.just(message(ac, "schema_migration_invoked"));
		}).subscribe(model -> ac.respond(model, OK), ac::fail);
	}

}