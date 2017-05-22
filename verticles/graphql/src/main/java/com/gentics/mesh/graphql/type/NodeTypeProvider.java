package com.gentics.mesh.graphql.type;

import static com.gentics.mesh.core.data.relationship.GraphPermission.READ_PERM;
import static com.gentics.mesh.core.data.relationship.GraphPermission.READ_PUBLISHED_PERM;
import static graphql.Scalars.GraphQLBoolean;
import static graphql.Scalars.GraphQLString;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLObjectType.newObject;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;

import java.util.List;
import java.util.Stack;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.gentics.mesh.cli.BootstrapInitializer;
import com.gentics.mesh.core.data.ContainerType;
import com.gentics.mesh.core.data.NodeGraphFieldContainer;
import com.gentics.mesh.core.data.Project;
import com.gentics.mesh.core.data.Release;
import com.gentics.mesh.core.data.User;
import com.gentics.mesh.core.data.node.Node;
import com.gentics.mesh.core.data.node.NodeContent;
import com.gentics.mesh.core.data.page.Page;
import com.gentics.mesh.core.rest.error.GenericRestException;
import com.gentics.mesh.error.MeshConfigurationException;
import com.gentics.mesh.graphql.context.GraphQLContext;
import com.gentics.mesh.parameter.PagingParameters;
import com.gentics.mesh.path.Path;
import com.gentics.mesh.path.PathSegment;
import com.gentics.mesh.search.index.node.NodeIndexHandler;

import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLObjectType.Builder;
import graphql.schema.GraphQLTypeReference;

/**
 * Type provider for the node type. Internally this will map partially to {@link Node} and {@link NodeGraphFieldContainer} vertices.
 */
@Singleton
public class NodeTypeProvider extends AbstractTypeProvider {

	@Inject
	public NodeIndexHandler nodeIndexHandler;

	@Inject
	public InterfaceTypeProvider interfaceTypeProvider;

	@Inject
	public TagTypeProvider tagTypeProvider;

	@Inject
	public BootstrapInitializer boot;

	@Inject
	public NodeFieldTypeProvider nodeFieldTypeProvider;

	@Inject
	public NodeTypeProvider() {
	}

	/**
	 * Fetcher for the parent node reference of a node.
	 * 
	 * @param env
	 * @return
	 */
	public Object parentNodeFetcher(DataFetchingEnvironment env) {
		NodeContent content = env.getSource();
		if (content == null) {
			return null;
		}
		GraphQLContext gc = env.getContext();
		String uuid = gc.getRelease().getUuid();
		Node parentNode = content.getNode().getParentNode(uuid);
		// The project root node can have no parent. Lets check this and exit early.
		if (parentNode == null) {
			return null;
		}
		return gc.requiresPerm(parentNode, READ_PERM, READ_PUBLISHED_PERM);
	}

	public Object nodeLanguageFetcher(DataFetchingEnvironment env) {
		NodeContent content = env.getSource();
		if (content == null) {
			return null;
		}
		List<String> languageTags = getLanguageArgument(env);
		GraphQLContext gc = env.getContext();

		Node node = content.getNode();
		Release release = gc.getRelease();
		NodeGraphFieldContainer container = node.findNextMatchingFieldContainer(gc, languageTags);
		// There might not be a container for the selected language (incl. fallback language)
		if (container == null) {
			return null;
		}

		// Check whether the user is allowed to read the published container
		boolean isPublished = container.isPublished(release.getUuid());
		if (isPublished) {
			gc.requiresPerm(node, READ_PERM, READ_PUBLISHED_PERM);
		} else {
			// Otherwise the container is a draft and we need to use the regular read permission
			gc.requiresPerm(node, READ_PERM);
		}
		return new NodeContent(container).setNode(node);
	}

	public Object breadcrumbFetcher(DataFetchingEnvironment env) {
		GraphQLContext gc = env.getContext();
		NodeContent content = env.getSource();
		if (content == null) {
			return null;
		}
		return content.getNode().getBreadcrumbNodes(gc);
	}

	public GraphQLObjectType createNodeType(Project project) {
		Builder nodeType = newObject();
		nodeType.name("Node");
		nodeType.description(
				"A Node is the basic building block for contents. Nodes can contain multiple language specific contents. These contents contain the fields with the actual content.");
		interfaceTypeProvider.addCommonFields(nodeType, true);

		// .project
		nodeType.field(newFieldDefinition().name("project").description("Project of the node").type(new GraphQLTypeReference("Project"))
				.dataFetcher((env) -> {
					GraphQLContext gc = env.getContext();
					NodeContent content = env.getSource();
					if (content == null) {
						return null;
					}
					Project projectOfNode = content.getNode().getProject();
					return gc.requiresPerm(projectOfNode, READ_PERM);
				}));

		// .breadcrumb
		nodeType.field(newFieldDefinition().name("breadcrumb").description("Breadcrumb of the node")
				.type(new GraphQLList(new GraphQLTypeReference("Node"))).dataFetcher(this::breadcrumbFetcher));

		// .availableLanguages
		nodeType.field(newFieldDefinition().name("availableLanguages").description("List all available languages for the node")
				.type(new GraphQLList(GraphQLString)).dataFetcher((env) -> {
					NodeContent content = env.getSource();
					if (content == null) {
						return null;
					}
					// TODO handle release!
					return content.getNode().getAvailableLanguageNames();
				}));

		// .child
		nodeType.field(newFieldDefinition().name("child").description("Resolve a webroot path to a specific child node.").argument(createPathArg())
				.type(new GraphQLTypeReference("Node")).dataFetcher((env) -> {
					String nodePath = env.getArgument("path");
					if (nodePath != null) {
						GraphQLContext gc = env.getContext();

						NodeContent content = env.getSource();
						if (content == null) {
							return null;
						}
						Node node = content.getNode();
						// Resolve the given path and return the found container
						Release release = gc.getRelease();
						String releaseUuid = release.getUuid();
						ContainerType type = ContainerType.forVersion(gc.getVersioningParameters().getVersion());
						Stack<String> pathStack = new Stack<>();
						pathStack.add(nodePath);
						Path path = new Path();
						try {
							node.resolvePath(releaseUuid, type, path, pathStack);
						} catch (GenericRestException e) {
							// Check whether the path could not be resolved
							if (e.getStatus() == NOT_FOUND) {
								return null;
							} else {
								throw e;
							}
						}
						// Check whether the path could not be resolved. In those cases the segments is empty
						if (path.getSegments().isEmpty()) {
							return null;
						}
						// Otherwise return the last segment.
						PathSegment lastSegment = path.getSegments().get(path.getSegments().size() - 1);
						return new NodeContent(lastSegment.getContainer());
					}
					return null;
				}));

		// .children
		nodeType.field(newPagingFieldWithFetcherBuilder("children", "Load child nodes of the node.", (env) -> {
			GraphQLContext gc = env.getContext();
			NodeContent content = env.getSource();
			if (content == null) {
				return null;
			}
			Node node = content.getNode(); // The obj type is validated by graphtype
			List<String> languageTags = env.getArgument("languages");
			return node.getChildren(gc.getUser(), languageTags, gc.getRelease().getUuid(), null, getPagingInfo(env));

		}, "Node").argument(createLanguageTagArg()));

		// .parent
		nodeType.field(newFieldDefinition().name("parent").description("Parent node").type(new GraphQLTypeReference("Node"))
				.dataFetcher(this::parentNodeFetcher));

		// .tags
		nodeType.field(newFieldDefinition().name("tags").argument(createPagingArgs()).type(tagTypeProvider.createTagType()).dataFetcher((env) -> {
			GraphQLContext gc = env.getContext();
			NodeContent content = env.getSource();
			if (content == null) {
				return null;
			}
			Node node = content.getNode();
			return node.getTags(gc.getUser(), createPagingParameters(env), gc.getRelease());
		}));

		// TODO Fix name confusion and check what version of schema should be used to determine this type
		// .isContainer
		nodeType.field(newFieldDefinition().name("isContainer").description("Check whether the node can have subnodes via children")
				.type(GraphQLBoolean).dataFetcher((env) -> {
					NodeContent content = env.getSource();
					if (content == null) {
						return null;
					}
					Node node = content.getNode();
					return node.getSchemaContainer().getLatestVersion().getSchema().isContainer();
				}));

		// Content specific fields

		// .node
		nodeType.field(newFieldDefinition().name("node").description("Load the node with a different language.").argument(createLanguageTagArg())
				.argument(createLanguageTagArg()).dataFetcher(this::nodeLanguageFetcher).type(new GraphQLTypeReference("Node")).build());

		// .path
		nodeType.field(newFieldDefinition().name("path").description("Webroot path of the content.").type(GraphQLString).dataFetcher(env -> {
			GraphQLContext gc = env.getContext();
			NodeContent content = env.getSource();
			if (content == null) {
				return null;
			}
			NodeGraphFieldContainer container = content.getContainer();
			if (container == null) {
				return null;
			}
			ContainerType containerType = ContainerType.forVersion(gc.getVersioningParameters().getVersion());
			String releaseUuid = gc.getRelease().getUuid();
			String languageTag = container.getLanguage().getLanguageTag();
			return container.getParentNode().getPath(releaseUuid, containerType, languageTag);
		}));

		// .edited
		nodeType.field(newFieldDefinition().name("edited").description("ISO8601 formatted edit timestamp.").type(GraphQLString).dataFetcher(env -> {
			NodeContent content = env.getSource();
			NodeGraphFieldContainer container = content.getContainer();
			if (container == null) {
				return null;
			}
			return container.getLastEditedDate();
		}));

		// .editor
		nodeType.field(newFieldDefinition().name("editor").description("Editor of the element").type(new GraphQLTypeReference("User"))
				.dataFetcher(this::editorFetcher));

		// .isPublished
		nodeType.field(newFieldDefinition().name("isPublished").description("Check whether the content is published.").type(GraphQLBoolean)
				.dataFetcher(env -> {
					GraphQLContext gc = env.getContext();
					NodeContent content = env.getSource();
					if (content == null) {
						return null;
					}
					NodeGraphFieldContainer container = content.getContainer();
					if (container == null) {
						return null;
					}
					return container.isPublished(gc.getRelease().getUuid());
				}));

		// .isDraft
		nodeType.field(
				newFieldDefinition().name("isDraft").description("Check whether the content is a draft.").type(GraphQLBoolean).dataFetcher(env -> {
					GraphQLContext gc = env.getContext();
					NodeContent content = env.getSource();
					NodeGraphFieldContainer container = content.getContainer();
					if (container == null) {
						return null;
					}
					return container.isDraft(gc.getRelease().getUuid());
				}));

		// .version
		nodeType.field(newFieldDefinition().name("version").description("Version of the content.").type(GraphQLString).dataFetcher(env -> {
			NodeContent content = env.getSource();
			NodeGraphFieldContainer container = content.getContainer();
			if (container == null) {
				return null;
			}
			return container.getVersion().getFullVersion();
		}));

		// .fields
		nodeType.field(newFieldDefinition().name("fields").description("Contains the fields of the content.")
				.type(nodeFieldTypeProvider.getSchemaFieldsType(project)).dataFetcher(env -> {
					// The fields can be accessed via the container so we can directly pass it along.
					NodeContent content = env.getSource();
					return content.getContainer();
				}));

		// .language
		nodeType.field(newFieldDefinition().name("language").description("The language of this content.").type(GraphQLString).dataFetcher(env -> {
			NodeContent content = env.getSource();
			NodeGraphFieldContainer container = content.getContainer();
			if (container == null) {
				return null;
			}
			return container.getLanguage().getLanguageTag();
		}));

		return nodeType.build();
	}

	public Object editorFetcher(DataFetchingEnvironment env) {
		GraphQLContext gc = env.getContext();
		NodeContent content = env.getSource();
		if (content == null) {
			return null;
		}
		User user = content.getContainer().getEditor();
		return gc.requiresPerm(user, READ_PERM);
	}

	/**
	 * Invoke a elasticsearch using the provided query and return a page of found containers.
	 * 
	 * @param gc
	 * @param query
	 * @param pagingInfo
	 * @return
	 */
	public Page<? extends NodeContent> handleContentSearch(GraphQLContext gc, String query, PagingParameters pagingInfo) {
		try {
			return nodeIndexHandler.handleContainerSearch(gc, query, pagingInfo, READ_PERM, READ_PUBLISHED_PERM);
		} catch (MeshConfigurationException | InterruptedException | ExecutionException | TimeoutException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Invoke the given query and return a page of nodes.
	 * 
	 * @param gc
	 * @param query
	 *            Elasticsearch query
	 * @param pagingInfo
	 * @return
	 */
	public Page<? extends Node> handleSearch(GraphQLContext gc, String query, PagingParameters pagingInfo) {
		try {
			return nodeIndexHandler.query(gc, query, pagingInfo, READ_PERM, READ_PUBLISHED_PERM);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
